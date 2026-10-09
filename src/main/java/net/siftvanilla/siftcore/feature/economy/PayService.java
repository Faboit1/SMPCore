package net.siftvanilla.siftcore.feature.economy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.PlayerPayEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * /pay: validates, asks for confirmation above the configured amount (or the payer's lower {@code pay-confirm-above}),
 * checks that the receiver accepts payments from the payer ({@code pay-accept-from}; never from someone they ignore),
 * enforces the daily limit (which grows with time played) atomically with the transfer, and tells both players (the
 * receiver in their {@code pay-notifications} style, from their {@code pay-alert-minimum}; the money arrives either
 * way).
 */
public final class PayService {

    /** The cooldown key of payments (commands and the form share it). */
    static final String COOLDOWN_KEY = "pay";

    /** The chat feature's node for players who can't be ignored (staff). */
    static final String UNIGNORABLE = "siftcore.chat.unignorable";

    private final Services services;
    private final Setting<EconomySettings> settings;
    private final PayLimits limits;

    public PayService(Services services, Setting<EconomySettings> settings, PayLimits limits) {
        this.services = services;
        this.settings = settings;
        this.limits = limits;
    }

    public PayLimits limits() {
        return this.limits;
    }

    private PlayerSettings prefs() {
        return this.services.settings();
    }

    /** Today's limit for a player. Reads statistics, so call on the player's thread. */
    public long limitFor(Player player) {
        EconomySettings s = this.settings.get();
        if (!s.dailyLimitEnabled() || player.hasPermission("siftcore.pay.unlimited")) {
            return Long.MAX_VALUE;
        }
        long hours = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 3600L;
        return PayLimits.limit(s.dailyLimitBase(), s.dailyLimitPerHour(), s.dailyLimitMaximum(), hours);
    }

    /** Why a payment can't go ahead: the message and its arguments. */
    public record Refusal(MessageKey key, Arg... args) {
    }

    /**
     * Why this payment can't go ahead, from what is known without a storage read (the daily limit and who the receiver
     * accepts payments from are checked later), or null when it can. Runs on the payer's thread.
     */
    public Refusal refusal(Player payer, UUID target, long amount) {
        EconomySettings s = this.settings.get();
        if (target.equals(payer.getUniqueId())) {
            return new Refusal(CoreMessages.NOT_YOURSELF);
        }
        if (!s.payOfflineTargets() && Bukkit.getPlayer(target) == null) {
            return new Refusal(EconomyMessages.PAY_OFFLINE, Arg.text("name", name(target)));
        }
        if (amount < s.payMinimum()) {
            return new Refusal(EconomyMessages.PAY_MINIMUM, Arg.money("amount", s.payMinimum()));
        }
        if (!this.services.ledger().available()) {
            return new Refusal(CoreMessages.ECONOMY_UNAVAILABLE);
        }
        if (this.services.ledger().balance(payer.getUniqueId(), Currency.MONEY) < amount) {
            return new Refusal(CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", amount));
        }
        return null;
    }

    /**
     * Starts the pay cooldown ({@code pay.cooldown}, bypass {@code siftcore.bypass.cooldown}). Returns how long is
     * left when it is still running, or zero when the payment may go ahead.
     */
    public Duration tryCooldown(Player payer) {
        Duration cooldown = this.settings.get().payCooldown();
        if (cooldown.isZero() || payer.hasPermission("siftcore.bypass.cooldown")) {
            return Duration.ZERO;
        }
        return this.services.cooldowns().tryUse(payer.getUniqueId(), COOLDOWN_KEY, cooldown);
    }

    /** Starts a payment from a command: refusals and the cooldown go to the action bar. Runs on the payer's thread. */
    public void pay(Player payer, UUID target, long amount) {
        Refusal refusal = refusal(payer, target, amount);
        if (refusal != null) {
            this.services.messenger().send(payer, refusal.key(), refusal.args());
            return;
        }
        Duration wait = tryCooldown(payer);
        if (!wait.isZero()) {
            this.services.messenger().send(payer, CoreMessages.COOLDOWN, Arg.time("time", wait));
            return;
        }
        proceed(payer, target, amount, null);
    }

    /**
     * Goes on with a payment from the pay form, after {@link #refusal} and {@link #tryCooldown} passed there. The
     * form stays on screen while the day's total and the receiver's choice load: the confirmation replaces it, a
     * refusal (the daily limit, the receiver not accepting) comes back in it with what was typed, and a payment without
     * confirmation (or a failed load) closes it.
     */
    public void payFromForm(Submission form, UUID target, long amount) {
        this.services.dialogs().markShown(form.player());
        proceed(form.player(), target, amount, form);
    }

    /**
     * Loads the day's total and whom the receiver accepts payments from (one settings read when they are offline),
     * then checks both and confirms or pays. {@code form} may be null.
     */
    private void proceed(Player payer, UUID target, long amount, Submission form) {
        EconomySettings s = this.settings.get();
        long limit = limitFor(payer);
        boolean unignorable = payer.hasPermission(UNIGNORABLE);
        CompletableFuture<Long> sent = this.limits.load(payer.getUniqueId());
        CompletableFuture<Audience> accepts = prefs().lookup(target, EconomyFeature.PAY_ACCEPT_FROM);
        // Answered after the reads, outside the command or click: written for the payer (the daily limit left in their
        // money format; the confirmation writes every digit anyway).
        CompletableFuture.allOf(sent, accepts).whenComplete((ignored, error) -> this.services.scheduler().entity(payer, () -> this.services.lang().viewing(payer, () -> {
            if (error != null) {
                if (form != null) {
                    form.close();
                }
                this.services.messenger().send(payer, CoreMessages.ACTION_FAILED);
                return;
            }
            Audience choice = accepts.join();
            if (!accepts(payer.getUniqueId(), unignorable, target, choice)) {
                refuseNotAccepted(payer, target, form);
                return;
            }
            long left = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, limit - sent.join());
            if (amount > left) {
                Arg[] args = {Arg.money("left", left), Arg.money("limit", limit)};
                if (form != null) {
                    form.error(this.services.lang().get(EconomyMessages.PAY_LIMIT, args));
                } else {
                    this.services.messenger().send(payer, EconomyMessages.PAY_LIMIT, args);
                }
                return;
            }
            if (PayRules.asks(prefs().get(payer.getUniqueId(), EconomyFeature.PAY_CONFIRM_ABOVE), amount, s.payConfirmAbove())) {
                confirm(payer, target, amount, limit, left, choice);
            } else {
                if (form != null) {
                    form.close();
                }
                execute(payer, target, amount, limit, choice);
            }
        }), null));
    }

    /**
     * Whether the receiver accepts a payment from the payer now: their {@code pay-accept-from} choice (the value in
     * memory while they are online or logging in, otherwise {@code offlineChoice}, read before) and how the two are
     * related right now (friends, teammates, ignored). Thread-safe.
     */
    private boolean accepts(UUID payer, boolean unignorable, UUID target, Audience offlineChoice) {
        Audience choice = prefs().loaded(target) ? prefs().get(target, EconomyFeature.PAY_ACCEPT_FROM) : offlineChoice;
        Relations relations = this.services.relations();
        return PayRules.accepts(choice, relations.areFriends(target, payer), relations.sameTeam(target, payer),
            relations.ignores(target, payer), unignorable);
    }

    private void refuseNotAccepted(Player payer, UUID target, Submission form) {
        Arg name = Arg.text("name", name(target));
        if (form != null) {
            form.error(this.services.lang().get(EconomyMessages.PAY_NOT_ACCEPTED, name));
        } else {
            this.services.messenger().send(payer, EconomyMessages.PAY_NOT_ACCEPTED, name);
        }
    }

    private void confirm(Player payer, UUID target, long amount, long limit, long left, Audience choice) {
        var lang = this.services.lang();
        // The exact amount, in the money colour like every amount of money.
        Arg amountArg = Arg.component("amount", Component.text(this.services.money().get().formatExact(amount),
            lang.style().palette().money()));
        Arg nameArg = Arg.text("name", name(target));
        View view = this.services.templates().confirm(
            lang.get(EconomyMessages.PAY_CONFIRM_TITLE),
            left == Long.MAX_VALUE
                ? lang.lines(EconomyMessages.PAY_CONFIRM_BODY_UNLIMITED, nameArg, amountArg)
                : lang.lines(EconomyMessages.PAY_CONFIRM_BODY, nameArg, amountArg, Arg.money("left", left - amount)),
            lang.get(EconomyMessages.PAY_CONFIRM_BUTTON),
            lang.get(CoreMessages.UI_CANCEL),
            submission -> {
                submission.close();
                execute(submission.player(), target, amount, limit, choice);
            },
            submission -> {
                submission.close();
                this.services.messenger().send(submission.player(), EconomyMessages.PAY_CANCELLED);
            });
        // Both buttons finish here (the result is a chat or action bar line), so the dialog goes at once on a click.
        this.services.dialogs().show(payer, view.closing());
    }

    /**
     * Checks again that the receiver accepts the payment (they may have changed their mind, or the two their relation,
     * while the confirmation was open), fires the event and runs the transfer with the limit check inside the
     * transaction. Payer's thread.
     */
    private void execute(Player payer, UUID target, long amount, long limit, Audience choice) {
        UUID from = payer.getUniqueId();
        if (!accepts(from, payer.hasPermission(UNIGNORABLE), target, choice)) {
            refuseNotAccepted(payer, target, null);
            return;
        }
        if (!new PlayerPayEvent(from, target, amount).callEvent()) {
            this.services.messenger().send(payer, EconomyMessages.PAY_CANCELLED);
            return;
        }
        LedgerTx tx = LedgerTx.builder()
            .actor(from)
            .transfer(from, target, Currency.MONEY, amount, PaymentsAway.KIND, null)
            .check(() -> limit != Long.MAX_VALUE && this.limits.sent(from) + amount > limit ? "limit" : null)
            .apply(() -> this.limits.add(from, amount), () -> this.limits.add(from, -amount))
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        switch (result.status()) {
            case SUCCESS -> {
                String targetName = name(target);
                this.services.messenger().send(payer, EconomyMessages.PAY_SENT, Arg.text("name", targetName), Arg.money("amount", amount));
                Player online = Bukkit.getPlayer(target);
                if (online != null) {
                    AlertStyle style = prefs().get(target, EconomyFeature.PAY_NOTIFICATIONS);
                    if (PayRules.alerts(style, amount, prefs().get(target, EconomyFeature.PAY_ALERT_MINIMUM))) {
                        this.services.messenger().alert(online, style, EconomyMessages.PAY_RECEIVED, Arg.text("name", payer.getName()),
                            Arg.money("amount", amount));
                    }
                }
            }
            case INSUFFICIENT_FUNDS -> this.services.messenger().send(payer, CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", amount));
            case BALANCE_LIMIT -> this.services.messenger().send(payer, EconomyMessages.PAY_TARGET_FULL, Arg.text("name", name(target)));
            case REJECTED -> {
                long sent = this.limits.sent(from);
                this.services.messenger().send(payer, EconomyMessages.PAY_LIMIT, Arg.money("left", Math.max(0, limit - sent)),
                    Arg.money("limit", limit));
            }
            case CANCELLED -> this.services.messenger().send(payer, EconomyMessages.PAY_CANCELLED);
            case UNAVAILABLE -> this.services.messenger().send(payer, CoreMessages.ECONOMY_UNAVAILABLE);
        }
    }

    private String name(UUID uuid) {
        return this.services.directory().name(uuid);
    }
}
