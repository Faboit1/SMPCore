package net.siftvanilla.siftcore.feature.shards;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.feature.afk.AfkZoneInfo;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Shards, the second currency: {@code /shards}, the shard shop ({@code /shardshop}: crate keys and items, never money),
 * staff tools ({@code /shards give|take|set}, waiting key purchases) and the shards page of the main menu, which also
 * shows what the AFK zone pays and leads there. The balance placeholders ({@code shards}, {@code shards_raw}) belong
 * to the economy feature.
 */
public final class ShardsFeature implements Feature {

    private static final Duration RETRY = Duration.ofMinutes(5);

    private final Services services;
    private final Logger logger;
    private final Setting<ShardsSettings> settings;
    private final AfkZoneInfo zone;
    private final CrateKeys crates;
    private final KeyGrants grants;
    private final ShardHandouts handouts;
    private final ShardShop shop;
    private final ShardsCommands commands;
    private Task retry = Task.NONE;

    /**
     * @param zone   what the AFK zone pays, for the shards page (the AFK feature)
     * @param crates gives crate keys bought in the shop (the crates feature)
     * @param combat keeps combat-tagged players out of the shop
     */
    public ShardsFeature(Services services, List<ConfigProblem> problems, AfkZoneInfo zone, CrateKeys crates, CombatStatus combat) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.zone = zone;
        this.crates = crates;
        this.settings = services.configs().register("features/shards.yml",
            reader -> ShardsSettings.parse(reader, ShardsFeature::itemExists), problems);
        services.lang().register(ShardsMessages.class);
        var perms = services.permissions();
        perms.declare(ShardsCommands.COMMAND, "See your shards with /shards", true);
        perms.declare(ShardsCommands.OTHERS, "See other players' shards with /shards <player>", true);
        perms.declare(ShardsCommands.SHOP, "Open the shard shop with /shardshop", true);
        perms.declare(ShardsCommands.ADMIN, "Give, take and set shards, and see or retry waiting key purchases (/shards pending)", false);
        for (ShardOffer offer : this.settings.get().offers()) {
            if (!offer.permission().isEmpty()) {
                perms.declare(offer.permission(), "See and buy " + offer.id() + " in the shard shop", false);
            }
        }
        this.grants = new KeyGrants(services.ledger(), services.database(), crates, services.scheduler().asyncExecutor(), this.logger);
        this.handouts = new ShardHandouts(services);
        this.shop = new ShardShop(services, this.settings, crates, this.grants, this.handouts, combat);
        this.commands = new ShardsCommands(services, this.shop, this);
    }

    private static boolean itemExists(String id) {
        Material material = Material.matchMaterial(id);
        return material != null && !material.isAir() && material.isItem();
    }

    @Override
    public String id() {
        return "shards";
    }

    KeyGrants grants() {
        return this.grants;
    }

    @Override
    public void enable() throws Exception {
        int waiting = this.grants.load();
        if (waiting > 0) {
            this.logger.info("Resuming " + waiting + " shard shop key purchase(s) that were not finished before the last stop.");
        }
        resume();
        this.retry = this.services.scheduler().asyncTimer(this::resume, RETRY, RETRY);
        this.services.hub().register(new HubEntry("shards", 85, ShardsMessages.HUB_LABEL, ShardsMessages.HUB_DESCRIPTION,
            ShardsCommands.COMMAND, this::openHub));
    }

    /** Retries waiting key purchases and tells online buyers how they ended. */
    void resume() {
        this.grants.resume((purchase, outcome) -> {
            Player player = Bukkit.getPlayer(purchase.player());
            if (player == null) {
                return;
            }
            ShardOffer offer = this.settings.get().offer(purchase.offer());
            Component name = offer != null ? this.shop.name(offer) : Component.text(purchase.crate());
            this.shop.tell(player, purchase, outcome, name);
        });
    }

    /** The shards page: balance, what the AFK zone pays and today's progress, the shop and the way to the zone. */
    public void openHub(Player player) {
        Lang lang = this.services.lang();
        long shards = this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS);
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(ShardsMessages.HUB_BALANCE, Arg.number("shards", shards)));
        if (this.zone.open()) {
            lines.add(lang.get(ShardsMessages.HUB_ZONE_RATE, Arg.number("shards", this.zone.shardsPerInterval(player)),
                Arg.time("time", this.zone.interval())));
            long today = this.zone.earnedToday(player.getUniqueId());
            long cap = this.zone.dailyCap();
            lines.add(cap > 0
                ? lang.get(ShardsMessages.HUB_ZONE_TODAY_CAP, Arg.number("today", today), Arg.number("cap", cap))
                : lang.get(ShardsMessages.HUB_ZONE_TODAY, Arg.number("today", today)));
            if (this.zone.inside(player.getUniqueId())) {
                lines.add(lang.get(ShardsMessages.HUB_ZONE_INSIDE));
            }
        } else {
            lines.add(lang.get(ShardsMessages.HUB_ZONE_CLOSED));
        }
        lines.add(lang.get(ShardsMessages.HUB_SPEND));
        List<Button> buttons = new ArrayList<>();
        if (player.hasPermission(ShardsCommands.SHOP)) {
            buttons.add(Button.of(lang.get(ShardsMessages.HUB_SHOP), lang.get(ShardsMessages.HUB_SHOP_TOOLTIP),
                s -> this.shop.open(s.player(), () -> openHub(s.player()))).width(150));
        }
        if (this.zone.open() && this.zone.mayTeleport(player) && !this.zone.inside(player.getUniqueId())) {
            buttons.add(Button.of(lang.get(ShardsMessages.HUB_ZONE), lang.get(ShardsMessages.HUB_ZONE_TOOLTIP), s -> {
                s.close();
                this.zone.teleport(s.player());
            }).width(150));
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(ShardsMessages.HUB_TITLE), lines, buttons, 2,
            s -> openMenu(s.player())));
    }

    private void openMenu(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(player);
        } else {
            this.services.dialogs().close(player);
        }
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void disable() {
        this.retry.cancel();
        try {
            // Let every queued purchase and claim commit and register its hand-over (players can no longer receive
            // items: the region threads have stopped), then put every item not handed out back into the claim box,
            // all before storage closes.
            this.services.database().flush();
            if (!this.handouts.awaitIdle(Duration.ofSeconds(10))) {
                this.logger.warning("Some shard shop items were still waiting for storage at shutdown");
            }
            this.handouts.drain();
            this.services.database().flush();
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Returning shard shop items to the claim box on shutdown failed", e);
        }
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "shop offers are sellable", () -> {
            List<String> problems = new ArrayList<>();
            boolean cratesInstalled = !this.crates.crates().isEmpty();
            for (ShardOffer offer : this.settings.get().offers()) {
                if (offer.kind() == ShardOffer.Kind.ITEM && ShardShop.unit(offer).isEmpty()) {
                    problems.add(offer.id() + " sells an unknown item");
                }
                // Without a crates feature key offers are simply hidden; with one, each must name a real crate.
                if (offer.kind() == ShardOffer.Kind.KEY && cratesInstalled && !this.crates.crates().contains(offer.target())) {
                    problems.add(offer.id() + " gives keys of an unknown crate " + offer.target());
                }
            }
            return problems.isEmpty() ? null : String.join("; ", problems);
        });
        test.check(id(), "shard purchase math", () -> {
            ShardOffer offer = new ShardOffer("test", ShardOffer.Kind.ITEM, "", "", "minecraft:stone", 32, 40, 16, "");
            if (offer.total(16).orElse(-1) != 640 || offer.given(16) != 512) {
                return "16 units of 32 items at 40 shards should cost 640 and give 512";
            }
            boolean[] chosen = ShardMath.pick(new int[] {64, 64, 64, 64, 64, 20}, 300);
            return Arrays.equals(chosen, new boolean[] {true, true, true, true, false, true}) ? null
                : "stacks of 64, 64, 64, 64, 64 and 20 with room for 300 should move all but one stack of 64";
        });
        test.checkAsync(id(), "key purchases in memory match storage", this.grants::check);
    }
}
