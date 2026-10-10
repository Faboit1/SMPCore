package net.siftvanilla.siftcore.feature.bounties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.BountyClaimEvent;
import net.siftvanilla.siftcore.api.event.BountyPlaceEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Announce;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * What players and the server do with bounties: placing (checked, confirmed above the sponsor's threshold,
 * announced), claiming on a counted kill, refunding expired contributions and staff removal. Every action re-checks
 * its inputs when it runs and leaves the money to one ledger transaction in {@link BountyService}. Announcements go
 * to each player as their {@code bounty-announcements} filter says; the target is told in their
 * {@code bounty-target-alert} style.
 */
final class BountyActions {

    /** The outcome of a staff removal or an expiry run. */
    record RefundRun(int refunded, long amount, int failed) {
    }

    private static final int CLAIM_ATTEMPTS = 3;
    /** The cooldown key of placing bounties ({@code place.cooldown}). */
    private static final String PLACE_COOLDOWN = "bounty-place";

    private final Services services;
    private final Setting<BountiesSettings> settings;
    private final BountyService service;
    private final Logger logger;
    private final Set<Long> warnedRefunds = ConcurrentHashMap.newKeySet();

    BountyActions(Services services, Setting<BountiesSettings> settings, BountyService service) {
        this.services = services;
        this.settings = settings;
        this.service = service;
        this.logger = services.plugin().getLogger();
    }

    BountyService service() {
        return this.service;
    }

    private Lang lang() {
        return this.services.lang();
    }

    private String name(UUID player) {
        return this.services.directory().name(player);
    }

    // ------------------------------------------------------------------ placing

    /** Why a placement can't go ahead: the message and its arguments. */
    private record Refusal(MessageKey key, List<Arg> args) {

        Arg[] arguments() {
            return this.args.toArray(Arg[]::new);
        }
    }

    /**
     * Why this bounty can't be placed right now, or null when it can. Reads the sponsor's balance. Runs again when the
     * placement is confirmed, the sponsor's permission included (a confirmation stays clickable for a while).
     */
    private Refusal refusal(Player sponsor, UUID target, long amount) {
        if (!sponsor.hasPermission(BountyCommands.PLACE)) {
            return new Refusal(CoreMessages.NO_PERMISSION, List.of());
        }
        BountiesSettings s = this.settings.get();
        BountyService.PlaceProblem problem = BountyService.check(sponsor.getUniqueId(), target, amount, s.minimum());
        if (problem == BountyService.PlaceProblem.YOURSELF) {
            return new Refusal(BountiesMessages.PLACE_YOURSELF, List.of());
        }
        if (problem == BountyService.PlaceProblem.BELOW_MINIMUM) {
            return new Refusal(BountiesMessages.PLACE_MINIMUM, List.of(Arg.money("amount", s.minimum())));
        }
        if (!this.services.ledger().available()) {
            return new Refusal(CoreMessages.ECONOMY_UNAVAILABLE, List.of());
        }
        if (this.services.ledger().balance(sponsor.getUniqueId(), Currency.MONEY) < amount) {
            return new Refusal(CoreMessages.NOT_ENOUGH_MONEY, List.of(Arg.money("amount", amount)));
        }
        return null;
    }

    /**
     * Why this bounty can't be placed, as a line for a dialog, or null when it can. Also says when the placement
     * cooldown still runs (without starting it: {@link #request} does that), so the form shows it instead of closing
     * first. Runs on the sponsor's thread.
     */
    Component problem(Player sponsor, UUID target, long amount) {
        Refusal refusal = refusal(sponsor, target, amount);
        if (refusal != null) {
            return lang().get(refusal.key(), refusal.arguments());
        }
        Duration wait = sponsor.hasPermission("siftcore.bypass.cooldown") ? Duration.ZERO
            : this.services.cooldowns().remaining(sponsor.getUniqueId(), PLACE_COOLDOWN);
        return wait.isZero() ? null : lang().get(CoreMessages.COOLDOWN, Arg.time("time", wait));
    }

    /** Tells the sponsor (action bar, error sound) why the bounty can't be placed; false when it can. */
    private boolean refuse(Player sponsor, UUID target, long amount) {
        Refusal refusal = refusal(sponsor, target, amount);
        if (refusal == null) {
            return false;
        }
        this.services.messenger().send(sponsor, refusal.key(), refusal.arguments());
        return true;
    }

    /**
     * Starts a placement from a command or form: checks it, applies the cooldown, then asks for confirmation above
     * the configured amount or places it right away. {@code done} runs after a successful placement (may be null).
     */
    void request(Player sponsor, UUID target, long amount, Consumer<Player> done) {
        if (refuse(sponsor, target, amount)) {
            return;
        }
        BountiesSettings s = this.settings.get();
        if (!this.services.commands().cooldown(sponsor, PLACE_COOLDOWN, s.placeCooldown())) {
            return;
        }
        if (asks(this.services.settings().get(sponsor, BountiesFeature.CONFIRM_ABOVE), amount, s.confirmAbove())) {
            confirm(sponsor, target, amount, done);
        } else {
            place(sponsor, target, amount, done);
        }
    }

    /**
     * Whether placing {@code amount} asks for confirmation: the sponsor's {@code bounty-confirm-above} choice, where
     * "server default" follows {@code place.confirm-above} ({@code serverAbove}, 0 never asks).
     */
    static boolean asks(ConfirmAbove choice, long amount, long serverAbove) {
        return choice.asks(amount, serverAbove > 0 && amount >= serverAbove);
    }

    /**
     * The target's line in their {@code bounty-target-alert} style: the short text for a title, the full line for chat
     * and the action bar, and null when they turned it off. A title only while it shows as one: quiet in combat
     * ({@code quiet}) turns it into a chat line, which gets the full text.
     */
    static MessageKey targetLine(AlertStyle style, boolean quiet) {
        return switch (style) {
            case OFF -> null;
            case TITLE -> quiet ? BountiesMessages.PLACED_TARGET : BountiesMessages.PLACED_TARGET_TITLE;
            default -> BountiesMessages.PLACED_TARGET;
        };
    }

    private void confirm(Player sponsor, UUID target, long amount, Consumer<Player> done) {
        BountiesSettings s = this.settings.get();
        List<Component> body = new ArrayList<>(lang().lines(BountiesMessages.CONFIRM_BODY,
            Arg.money("amount", amount),
            Arg.text("name", name(target)),
            BountyViews.time(s.expireAfter())));
        if (s.taxPercent() > 0) {
            body.add(lang().get(BountiesMessages.CONFIRM_TAX, Arg.text("tax", Lang.number(s.taxPercent()))));
        }
        View asked = this.services.templates().confirm(
            lang().get(BountiesMessages.CONFIRM_TITLE),
            body,
            lang().get(BountiesMessages.CONFIRM_BUTTON),
            lang().get(CoreMessages.UI_CANCEL),
            // The details (done) replace this dialog after a placement; after a refusal the router closes it.
            submission -> place(submission.player(), target, amount, done),
            submission -> {
                submission.close();
                this.services.messenger().send(submission.player(), BountiesMessages.PLACE_CANCELLED);
            });
        // Cancel finishes it. So does Confirm from /bounty, where nothing follows a placement; from the form the
        // details follow, so Confirm keeps the dialog on screen until they replace it.
        Button yes = asked.buttons().get(0).tooltip(lang().get(BountiesMessages.CONFIRM_TOOLTIP));
        View view = new View(asked.kind(), asked.title(), asked.body(), asked.inputs(),
            List.of(done == null ? yes.closes() : yes, asked.buttons().get(1).closes()), asked.exit(), asked.columns(), asked.escapable());
        this.services.dialogs().show(sponsor, view);
    }

    /** Places the bounty after checking everything again. Runs on the sponsor's thread. */
    void place(Player sponsor, UUID target, long amount, Consumer<Player> done) {
        if (refuse(sponsor, target, amount)) {
            return;
        }
        UUID sponsorId = sponsor.getUniqueId();
        if (!new BountyPlaceEvent(sponsorId, target, amount).callEvent()) {
            this.services.messenger().send(sponsor, BountiesMessages.PLACE_CANCELLED);
            return;
        }
        BountyService.Placed placed = this.service.place(sponsorId, target, amount, System.currentTimeMillis());
        TransactionResult result = placed.result();
        switch (result.status()) {
            case SUCCESS -> placed(sponsor, target, amount, placed.totalAfter(), done);
            case INSUFFICIENT_FUNDS -> this.services.messenger().send(sponsor, CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", amount));
            case UNAVAILABLE -> this.services.messenger().send(sponsor, CoreMessages.ECONOMY_UNAVAILABLE);
            case CANCELLED, REJECTED -> this.services.messenger().send(sponsor, BountiesMessages.PLACE_CANCELLED);
            case BALANCE_LIMIT -> this.services.messenger().send(sponsor, CoreMessages.ACTION_FAILED);
        }
        result.committed().exceptionally(error -> {
            this.services.messenger().send(sponsor, CoreMessages.ACTION_FAILED);
            return null;
        });
    }

    private void placed(Player sponsor, UUID target, long amount, long total, Consumer<Player> done) {
        BountiesSettings s = this.settings.get();
        String targetName = name(target);
        this.services.messenger().send(sponsor, BountiesMessages.PLACED, Arg.money("amount", amount),
            Arg.text("name", targetName), Arg.money("total", total));
        Player online = Bukkit.getPlayer(target);
        if (online != null && s.notifyTarget()) {
            AlertStyle style = this.services.settings().get(target, BountiesFeature.TARGET_ALERT);
            MessageKey line = targetLine(style, this.services.messenger().quietNow(target));
            if (line != null) {
                this.services.messenger().alert(online, style, line, Arg.money("amount", amount), Arg.money("total", total));
            }
        }
        if (s.announcePlacements() && amount >= s.announceAbove()) {
            broadcast(() -> lang().get(BountiesMessages.PLACED_ANNOUNCE, Arg.money("amount", amount), Arg.text("name", targetName),
                Arg.money("total", total)), amount, Set.of(sponsor.getUniqueId(), target));
        }
        if (done != null) {
            done.accept(sponsor);
        }
    }

    // ------------------------------------------------------------------ claiming

    /**
     * Pays the bounty on {@code victim} to {@code killer} after a counted kill. Runs on the victim's thread while
     * their death is processed; the killer may be offline.
     */
    void claim(UUID killer, Player victim) {
        UUID victimId = victim.getUniqueId();
        BountiesSettings s = this.settings.get();
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            BountyBook.Bounty bounty = this.service.book().get(victimId);
            boolean ownPart = bounty != null && bounty.from(killer) > 0;
            var plan = this.service.plan(killer, victimId, s.taxPercent());
            if (plan.isEmpty()) {
                if (ownPart) {
                    tell(killer, BountiesMessages.CLAIM_OWN, Arg.text("name", victim.getName()));
                }
                return;
            }
            BountyService.Plan claim = plan.get();
            BountyMath.Split split = claim.split();
            if (!new BountyClaimEvent(killer, victimId, split.total(), split.tax(), claim.contributions().size()).callEvent()) {
                return;
            }
            TransactionResult result = this.service.claim(claim, System.currentTimeMillis());
            switch (result.status()) {
                case SUCCESS -> {
                    claimed(claim, victim.getName(), s);
                    if (ownPart) {
                        tell(killer, BountiesMessages.CLAIM_OWN, Arg.text("name", victim.getName()));
                    }
                    result.committed().exceptionally(error -> {
                        this.logger.log(Level.SEVERE, "The bounty claim on " + victim.getName() + " by " + name(killer)
                            + " could not be stored and was reverted; the bounty stays", error);
                        return null;
                    });
                    return;
                }
                case REJECTED -> {
                    // A contribution changed between planning and paying (claimed, refunded or added): plan again.
                }
                case BALANCE_LIMIT -> {
                    tell(killer, BountiesMessages.CLAIM_TOO_RICH, Arg.text("name", victim.getName()));
                    return;
                }
                default -> {
                    this.logger.info("The bounty on " + victim.getName() + " was not paid to " + name(killer) + ": "
                        + result.status().name().toLowerCase(java.util.Locale.ROOT) + "; it stays");
                    return;
                }
            }
        }
        this.logger.warning("The bounty on " + victim.getName() + " kept changing while " + name(killer) + " claimed it; it stays");
    }

    private void claimed(BountyService.Plan claim, String victimName, BountiesSettings s) {
        BountyMath.Split split = claim.split();
        String killerName = name(claim.killer());
        if (split.tax() > 0) {
            tell(claim.killer(), BountiesMessages.CLAIMED, Arg.money("payout", split.payout()), Arg.text("name", victimName),
                Arg.money("tax", split.tax()));
        } else {
            tell(claim.killer(), BountiesMessages.CLAIMED_NO_TAX, Arg.money("payout", split.payout()), Arg.text("name", victimName));
        }
        if (s.announceClaims()) {
            broadcast(() -> lang().get(BountiesMessages.CLAIM_ANNOUNCE, Arg.text("killer", killerName), Arg.money("total", split.total()),
                Arg.text("name", victimName)), split.total(), Set.of(claim.killer()));
        }
        if (s.notifySponsors()) {
            for (UUID sponsor : claim.sponsors()) {
                tell(sponsor, BountiesMessages.CLAIM_SPONSOR, Arg.text("name", victimName), Arg.text("killer", killerName));
            }
        }
    }

    // ------------------------------------------------------------------ refunds

    /** Refunds every contribution that ran out (the expiry timer, async). */
    RefundRun expire() {
        long now = System.currentTimeMillis();
        List<BountyService.Refund> refunds = this.service.expire(now, this.settings.get().expireAfter());
        return refunded(refunds, BountiesMessages.REFUND_EXPIRED);
    }

    /** Refunds every contribution on a player (staff). */
    RefundRun remove(UUID target, String actor) {
        List<BountyService.Refund> refunds = this.service.removeAll(target, actor, System.currentTimeMillis());
        RefundRun run = refunded(refunds, BountiesMessages.REFUND_REMOVED);
        if (run.refunded() > 0) {
            this.services.audit().record(actor, "bounties.remove", target.toString(),
                "refunded " + run.amount() + " in " + run.refunded() + " part(s), " + run.failed() + " failed");
        }
        return run;
    }

    private RefundRun refunded(List<BountyService.Refund> refunds, MessageKey message) {
        int refunded = 0;
        int failed = 0;
        long amount = 0;
        for (BountyService.Refund refund : refunds) {
            BountyBook.Contribution contribution = refund.contribution();
            TransactionResult result = refund.result();
            if (result.success()) {
                refunded++;
                amount = BountyMath.add(amount, contribution.amount());
                this.warnedRefunds.remove(contribution.id());
                tell(contribution.sponsor(), message, Arg.money("amount", contribution.amount()), Arg.text("name", name(contribution.target())));
                result.committed().exceptionally(error -> {
                    this.logger.log(Level.SEVERE, "The refund of bounty part " + contribution.id() + " could not be stored and was reverted", error);
                    return null;
                });
            } else if (result.status() != TransactionStatus.REJECTED) {
                failed++;
                if (this.warnedRefunds.add(contribution.id())) {
                    this.logger.warning("Bounty part " + contribution.id() + " (" + contribution.amount() + " on " + name(contribution.target())
                        + " from " + name(contribution.sponsor()) + ") could not be refunded: "
                        + result.status().name().toLowerCase(java.util.Locale.ROOT) + ". It stays active and is retried.");
                }
            }
        }
        return new RefundRun(refunded, amount, failed);
    }

    // ------------------------------------------------------------------ messages

    private void tell(UUID player, MessageKey key, Arg... args) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            this.services.messenger().send(online, key, args);
        }
    }

    /**
     * Chat to every online player except {@code skip} whose {@code bounty-announcements} filter shows a bounty of
     * {@code amount}, and the console. Each reader gets the line with money in their money format.
     */
    private void broadcast(Supplier<Component> render, long amount, Set<UUID> skip) {
        Function<Audience, Component> line = lang().perViewer(render);
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (!skip.contains(id) && shows(this.services.settings().get(id, BountiesFeature.ANNOUNCEMENTS), amount)) {
                online.sendMessage(line.apply(online));
            }
        }
        Bukkit.getConsoleSender().sendMessage(line.apply(Bukkit.getConsoleSender()));
    }

    /** Whether a player's announcement filter shows a bounty line about {@code amount}. */
    static boolean shows(Announce filter, long amount) {
        return filter.shows(amount);
    }
}
