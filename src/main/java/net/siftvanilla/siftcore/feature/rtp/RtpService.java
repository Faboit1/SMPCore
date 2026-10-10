package net.siftvanilla.siftcore.feature.rtp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.RandomTeleportEvent;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.feature.spawn.BorderSpec;
import net.siftvanilla.siftcore.feature.spawn.WorldBorders;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The random teleport flow. A player picks a region; after the warmup (cancelled by moving, taking damage or combat)
 * the search runs off the world threads; when it found a safe spot, every condition is checked again on the
 * player's thread, the cost is taken in one ledger transaction and the player is teleported.
 * <p>
 * Money: the cost is charged only once a safe spot exists, right before the teleport, so a failed search costs
 * nothing. If the teleport itself then does not happen (the player left, got into combat in that instant, or the
 * server refused the move), the same amount is paid back in a silent {@code rtp_refund} transaction. Charging before
 * the move rather than after it means nobody can get a free teleport by spending their money during the search.
 */
final class RtpService {

    static final String BYPASS_COOLDOWN = "siftcore.bypass.cooldown";
    /** The free-form per-player value holding the region of the last random teleport that happened. */
    static final String LAST_REGION = "rtp_last";

    /** One player's teleport in progress: what was charged, for a refund if the teleport does not happen. */
    private static final class Attempt {
        private final String region;
        private final AtomicLong charged = new AtomicLong();
        /** Completes once the charge is stored; fails when storing it failed (and the ledger already gave it back). */
        private volatile CompletableFuture<Void> chargeStored = CompletableFuture.completedFuture(null);
        private volatile Location spot;

        private Attempt(String region) {
            this.region = region;
        }
    }

    private final Services services;
    private final Setting<RtpSettings> settings;
    private final RtpSearch search;
    private final Logger logger;
    /** The attempt whose search is running, per player (an old search that ends late can't clear a newer one). */
    private final Map<UUID, Attempt> searching = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    RtpService(Services services, Setting<RtpSettings> settings, RtpSearch search) {
        this.services = services;
        this.settings = settings;
        this.search = search;
        this.logger = services.plugin().getLogger();
    }

    RtpSearch search() {
        return this.search;
    }

    // ------------------------------------------------------------------ cooldowns

    private static String key(UUID player, String region) {
        return player + ":" + region;
    }

    /** Time left before the player may use the region again (kept across relogs, not restarts). */
    Duration cooldownLeft(UUID player, String region) {
        Long until = this.cooldownUntil.get(key(player, region));
        long left = until == null ? 0 : until - System.currentTimeMillis();
        return left <= 0 ? Duration.ZERO : Duration.ofMillis(left);
    }

    void sweepCooldowns() {
        long now = System.currentTimeMillis();
        this.cooldownUntil.values().removeIf(until -> until <= now);
    }

    int cooldownCount() {
        return this.cooldownUntil.size();
    }

    // ------------------------------------------------------------------ checks

    /** The ring's usable outer radius in its world right now, or 0 when the region can't be used. */
    static double usableMax(RtpSettings.Region region, int margin) {
        if (Bukkit.getWorld(region.world()) == null) {
            return 0;
        }
        BorderSpec border = WorldBorders.live(region.world()).orElse(null);
        double max = RtpGeometry.usableMax(region.maxRadius(), border, region.centerX(), region.centerZ(), margin);
        return max > region.minRadius() ? max : 0;
    }

    /** Checks everything that must hold to use a region, telling the player what is wrong. Player's thread. */
    private boolean ready(Player player, RtpSettings.Region region, int margin) {
        Messenger messenger = this.services.messenger();
        Arg name = Arg.text("region", region.name());
        if (!region.enabled()) {
            messenger.send(player, RtpMessages.DISABLED, name);
            return false;
        }
        if (region.permission() != null && !player.hasPermission(region.permission())) {
            messenger.send(player, RtpMessages.NO_PERMISSION, name);
            return false;
        }
        if (usableMax(region, margin) <= 0) {
            messenger.send(player, RtpMessages.UNAVAILABLE, name);
            return false;
        }
        Duration left = cooldownLeft(player.getUniqueId(), region.id());
        if (!left.isZero() && !player.hasPermission(BYPASS_COOLDOWN)) {
            messenger.send(player, RtpMessages.COOLDOWN, name, Arg.text("time", Durations.format(left)));
            return false;
        }
        if (region.cost() > 0) {
            if (!this.services.ledger().available()) {
                messenger.send(player, CoreMessages.ECONOMY_UNAVAILABLE);
                return false;
            }
            if (this.services.ledger().balance(player.getUniqueId(), Currency.MONEY) < region.cost()) {
                messenger.send(player, CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", region.cost()));
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ player flow

    /**
     * /rtp with no region, as the player's "/rtp with no region" setting says: the region picker, or straight to the
     * region they used last when it can be used now (enabled, allowed, fits its world, cooldown over); otherwise the
     * picker. Runs on the player's thread.
     */
    void bare(Player player) {
        if (this.services.settings().get(player, RtpFeature.DEFAULT) == RtpDefault.LAST) {
            RtpSettings s = this.settings.get();
            String last = this.services.settings().raw(player.getUniqueId(), LAST_REGION, null);
            RtpSettings.Region region = last == null ? null : s.regions().get(last);
            if (RtpDefault.LAST.straight(region != null, region != null && usableNow(player, region, s.borderMargin()))) {
                start(player, region.id(), false);
                return;
            }
        }
        openMenu(player, null);
    }

    /** Whether the player could start a random teleport to the region now (money aside). Player's thread. */
    private boolean usableNow(Player player, RtpSettings.Region region, int margin) {
        return region.enabled() && (region.permission() == null || player.hasPermission(region.permission()))
            && usableMax(region, margin) > 0
            && (player.hasPermission(BYPASS_COOLDOWN) || cooldownLeft(player.getUniqueId(), region.id()).isZero());
    }

    /**
     * Starts a random teleport to a region (by id or world name). A typed /rtp to a region that costs money shows the
     * price and asks first, unless the player turned that off. Runs on the player's thread.
     *
     * @param priceShown the player already saw the price (the picker shows it, or they confirmed it)
     */
    void start(Player player, String regionName, boolean priceShown) {
        RtpSettings s = this.settings.get();
        RtpSettings.Region region = s.find(regionName).orElse(null);
        if (region == null) {
            this.services.messenger().send(player, RtpMessages.UNKNOWN, Arg.text("name", regionName));
            return;
        }
        if (!ready(player, region, s.borderMargin())) {
            return;
        }
        if (this.searching.containsKey(player.getUniqueId())) {
            this.services.messenger().send(player, RtpMessages.ALREADY_SEARCHING);
            return;
        }
        if (RtpDefault.asksCost(this.services.settings().get(player, RtpFeature.CONFIRM_COST), region.cost(), priceShown)) {
            confirmCost(player, region);
            return;
        }
        Attempt attempt = new Attempt(region.id());
        this.services.teleports().teleport(player, "rtp", s.warmup(), () -> destination(player, attempt),
            ok -> finish(player, attempt, ok), true);
    }

    /**
     * "A random teleport to X costs $1,000." Teleport starts it (every check runs again at that moment); Cancel just
     * closes. Player's thread.
     */
    private void confirmCost(Player player, RtpSettings.Region region) {
        Lang lang = this.services.lang();
        String id = region.id();
        List<Component> lines = List.of(
            lang.get(RtpMessages.CONFIRM_BODY, Arg.text("region", region.name()), Arg.money("amount", region.cost())));
        View confirm = this.services.templates().confirm(lang.get(RtpMessages.CONFIRM_TITLE), lines, lang.get(RtpMessages.CONFIRM_BUTTON),
            lang.get(CoreMessages.UI_CANCEL), yes -> start(yes.player(), id, true), null);
        // Teleport finishes here: the window closes at once and the warmup shows. When the money is taken is on its tooltip.
        List<Button> buttons = List.of(confirm.buttons().get(0).tooltip(lang.get(RtpMessages.CONFIRM_NOTE)).closes(),
            confirm.buttons().get(1));
        this.services.dialogs().show(player, new View(confirm.kind(), confirm.title(), confirm.body(), confirm.inputs(), buttons,
            confirm.exit(), confirm.columns(), confirm.escapable()));
    }

    /** Runs on the player's thread when the warmup is over: searches, then pays. */
    private CompletableFuture<Location> destination(Player player, Attempt attempt) {
        RtpSettings s = this.settings.get();
        RtpSettings.Region region = s.regions().get(attempt.region);
        if (region == null) {
            this.services.messenger().send(player, RtpMessages.UNKNOWN, Arg.text("name", attempt.region));
            return CompletableFuture.completedFuture(null);
        }
        if (!ready(player, region, s.borderMargin())) {
            return CompletableFuture.completedFuture(null);
        }
        UUID id = player.getUniqueId();
        if (this.searching.putIfAbsent(id, attempt) != null) {
            this.services.messenger().send(player, RtpMessages.ALREADY_SEARCHING);
            return CompletableFuture.completedFuture(null);
        }
        World world = Bukkit.getWorld(region.world());
        if (world == null) {
            this.searching.remove(id, attempt);
            this.services.messenger().send(player, RtpMessages.UNAVAILABLE, Arg.text("region", region.name()));
            return CompletableFuture.completedFuture(null);
        }
        this.services.messenger().send(player, RtpMessages.SEARCHING);
        CompletableFuture<Location> result = new CompletableFuture<>();
        CompletableFuture<Location> found;
        try {
            found = this.search.find(world, region, s, player.getLocation().getYaw());
        } catch (RuntimeException e) {
            this.searching.remove(id, attempt);
            throw e;
        }
        Runnable gone = () -> {
            this.searching.remove(id, attempt);
            result.complete(null);
        };
        found.whenComplete((spot, error) -> {
            Task scheduled = this.services.scheduler().entity(player, () -> {
                this.searching.remove(id, attempt);
                if (error != null || spot == null) {
                    if (error != null && !(error instanceof TimeoutException)) {
                        this.logger.log(Level.WARNING, "Random teleport search for " + player.getName() + " failed", error);
                    }
                    // "Nothing was charged" only where something could have been.
                    this.services.messenger().send(player, region.cost() > 0 ? RtpMessages.NO_SPOT_PAID : RtpMessages.NO_SPOT);
                    result.complete(null);
                    return;
                }
                result.complete(pay(player, attempt, spot) ? spot : null);
            }, gone);
            if (scheduled == Task.NONE) {
                gone.run();
            }
        });
        return result;
    }

    /** Forgets a player who left (their search, if one still runs, ends without them). */
    void forget(UUID player) {
        this.searching.remove(player);
    }

    /** Re-checks everything and takes the cost right before the teleport. Player's thread. */
    private boolean pay(Player player, Attempt attempt, Location spot) {
        RtpSettings s = this.settings.get();
        RtpSettings.Region region = s.regions().get(attempt.region);
        if (region == null || !ready(player, region, s.borderMargin())) {
            return false;
        }
        UUID id = player.getUniqueId();
        long cost = region.cost();
        if (!new RandomTeleportEvent(id, region.id(), spot, cost).callEvent()) {
            this.services.messenger().send(player, RtpMessages.CANCELLED);
            return false;
        }
        attempt.spot = spot;
        if (cost <= 0) {
            return true;
        }
        LedgerTx tx = LedgerTx.builder()
            .actor(id)
            .note("random teleport to " + region.id())
            .sink(id, Currency.MONEY, cost, "rtp_cost", region.id())
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        switch (result.status()) {
            case SUCCESS -> {
                attempt.chargeStored = result.committed();
                attempt.charged.set(cost);
                return true;
            }
            case INSUFFICIENT_FUNDS -> this.services.messenger().send(player, CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", cost));
            case UNAVAILABLE -> this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            case CANCELLED -> this.services.messenger().send(player, RtpMessages.CANCELLED);
            default -> this.services.messenger().send(player, CoreMessages.ACTION_FAILED);
        }
        return false;
    }

    /** After the teleport (or its failure). Starts the cooldown on success; pays the cost back otherwise. */
    private void finish(Player player, Attempt attempt, boolean ok) {
        UUID id = player.getUniqueId();
        RtpSettings.Region region = this.settings.get().regions().get(attempt.region);
        if (!ok) {
            refund(player, attempt);
            return;
        }
        Duration cooldown = region == null ? Duration.ZERO : region.cooldown();
        if (!cooldown.isZero() && !player.hasPermission(BYPASS_COOLDOWN)) {
            this.cooldownUntil.put(key(id, attempt.region), System.currentTimeMillis() + cooldown.toMillis());
        }
        // Remembered for "/rtp with no region: last region" (a row only when it changes).
        if (!attempt.region.equals(this.services.settings().raw(id, LAST_REGION, null))) {
            this.services.settings().setRaw(id, LAST_REGION, attempt.region);
        }
        Location spot = attempt.spot;
        String name = region == null ? attempt.region : region.name();
        long charged = attempt.charged.get();
        if (spot != null) {
            landed(player, name, spot, charged);
        }
    }

    /**
     * The landing line, where the player's teleport display setting says (a line saying money was taken shows even
     * with the display off), without the position while they hide coordinates.
     */
    private void landed(Player player, String name, Location spot, long charged) {
        Arg region = Arg.text("region", name);
        boolean hidden = this.services.settings().get(player, SharedSettings.HIDE_COORDINATES);
        if (charged > 0) {
            Arg amount = Arg.money("amount", charged);
            if (hidden) {
                this.services.teleports().arrival(player, true, RtpMessages.LANDED_PAID_HIDDEN, region, amount);
            } else {
                this.services.teleports().arrival(player, true, RtpMessages.LANDED_PAID, region, Arg.text("x", Lang.number(spot.getBlockX())),
                    Arg.text("z", Lang.number(spot.getBlockZ())), amount);
            }
        } else if (hidden) {
            this.services.teleports().arrival(player, RtpMessages.LANDED_HIDDEN, region);
        } else {
            this.services.teleports().arrival(player, RtpMessages.LANDED, region, Arg.text("x", Lang.number(spot.getBlockX())),
                Arg.text("z", Lang.number(spot.getBlockZ())));
        }
    }

    private void refund(Player player, Attempt attempt) {
        long amount = attempt.charged.getAndSet(0);
        if (amount <= 0) {
            return;
        }
        afterCharge(attempt.chargeStored, () -> payBack(player, attempt.region, amount),
            () -> this.logger.info("The random teleport charge of " + player.getName() + " could not be stored and was "
                + "already given back; nothing more to refund"));
    }

    /**
     * Runs {@code refund} once the charge it pays back is stored, or {@code reverted} instead when storing the charge
     * failed: the ledger has then already given the money back, and refunding it again would create it (dupe audit
     * R13). Either runs on the thread that completes the charge (a database callback thread), or at once.
     */
    static void afterCharge(CompletableFuture<?> chargeStored, Runnable refund, Runnable reverted) {
        chargeStored.whenComplete((ignored, error) -> {
            if (error == null) {
                refund.run();
            } else {
                reverted.run();
            }
        });
    }

    /** Pays a charge back in a silent {@code rtp_refund} source and tells the player. Any thread. */
    private void payBack(Player player, String region, long amount) {
        UUID id = player.getUniqueId();
        LedgerTx tx = LedgerTx.builder()
            .actor("system")
            .note("random teleport did not happen")
            .source(id, Currency.MONEY, amount, "rtp_refund", region)
            .silent()
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        if (result.success()) {
            if (player.isOnline()) {
                this.services.messenger().send(player, RtpMessages.REFUNDED, Arg.money("amount", amount));
            }
        } else {
            this.logger.severe("Could not refund " + amount + " to " + player.getName() + " (" + id + ") after a random teleport "
                + "that did not happen: " + result.status() + ". Give it back with /eco give.");
        }
    }

    // ------------------------------------------------------------------ staff

    /** Sends a player to a random spot at once: no cost, cooldown or warmup (staff and console). */
    void send(CommandSender sender, Player target, RtpSettings.Region region) {
        RtpSettings s = this.settings.get();
        World world = Bukkit.getWorld(region.world());
        Arg name = Arg.text("name", target.getName());
        Arg regionName = Arg.text("region", region.name());
        if (world == null || usableMax(region, s.borderMargin()) <= 0) {
            this.services.messenger().chat(sender, RtpMessages.SENT_FAILED, name, regionName);
            return;
        }
        this.services.scheduler().entity(target, () -> this.search.find(world, region, s, target.getLocation().getYaw())
            .whenComplete((spot, error) -> this.services.scheduler().entity(target, () -> {
                if (error != null || spot == null || !new RandomTeleportEvent(target.getUniqueId(), region.id(), spot, 0).callEvent()) {
                    this.services.messenger().chat(sender, RtpMessages.SENT_FAILED, name, regionName);
                    return;
                }
                target.teleportAsync(spot, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((ok, failure) -> {
                    if (failure == null && Boolean.TRUE.equals(ok)) {
                        this.services.messenger().chat(sender, RtpMessages.SENT, name, regionName,
                            Arg.number("x", spot.getBlockX()), Arg.number("z", spot.getBlockZ()));
                    } else {
                        this.services.messenger().chat(sender, RtpMessages.SENT_FAILED, name, regionName);
                    }
                });
            }, null)), null);
    }

    // ------------------------------------------------------------------ dialog

    /** The picker ({@link RtpPicker}): a button per place the player may use. {@code back} null shows Close. Player's thread. */
    void openMenu(Player player, Button.Handler back) {
        RtpSettings s = this.settings.get();
        List<RtpPicker.Choice> choices = new ArrayList<>();
        for (RtpSettings.Region region : s.regions().values()) {
            if (!region.enabled() || (region.permission() != null && !player.hasPermission(region.permission()))) {
                continue;
            }
            double max = usableMax(region, s.borderMargin());
            if (max <= 0) {
                continue;
            }
            Duration left = player.hasPermission(BYPASS_COOLDOWN) ? Duration.ZERO : cooldownLeft(player.getUniqueId(), region.id());
            choices.add(new RtpPicker.Choice(region, (long) Math.floor(max), left));
        }
        if (choices.isEmpty()) {
            this.services.messenger().send(player, RtpMessages.NONE);
            return;
        }
        this.services.dialogs().show(player, RtpPicker.view(this.services.lang(), this.services.templates(), choices,
            region -> submission -> start(submission.player(), region.id(), true), back));
    }
}
