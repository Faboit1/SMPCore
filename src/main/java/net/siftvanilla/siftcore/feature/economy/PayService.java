package net.siftvanilla.siftcore.feature.economy;

import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.PlayerPayEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * /pay: validates, asks for confirmation above the configured amount, enforces the daily limit (which grows with
 * time played) atomically with the transfer, and tells both players (the receiver only when they want pay
 * notifications and don't ignore the payer; the money arrives either way).
 */
public final class PayService {

    private final Services services;
    private final Setting<EconomySettings> settings;
    private final PayLimits limits;
    private final Toggle notifications;
    private volatile IgnoreLookup ignores = IgnoreLookup.NONE;

    /** The chat feature's node for players who can't be ignored (staff). */
    static final String UNIGNORABLE = "siftcore.chat.unignorable";

    public PayService(Services services, Setting<EconomySettings> settings, PayLimits limits, Toggle notifications) {
        this.services = services;
        this.settings = settings;
        this.limits = limits;
        this.notifications = notifications;
    }

    /** Installs the ignore lists (the chat feature is built after the economy). */
    void ignores(IgnoreLookup ignores) {
        this.ignores = ignores;
    }

    /** Whether the receiver is told about a payment: their pay notifications are on and they don't ignore the payer. */
    static boolean notifies(boolean notificationsOn, boolean ignoresPayer, boolean payerUnignorable) {
        return notificationsOn && (!ignoresPayer || payerUnignorable);
    }

    public PayLimits limits() {
        return this.limits;
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

    /** Starts a payment from a command or form. Runs on the payer's thread. */
    public void pay(Player payer, UUID target, long amount) {
        EconomySettings s = this.settings.get();
        if (target.equals(payer.getUniqueId())) {
            this.services.messenger().send(payer, CoreMessages.NOT_YOURSELF);
            return;
        }
        if (!s.payOfflineTargets() && Bukkit.getPlayer(target) == null) {
            this.services.messenger().send(payer, EconomyMessages.PAY_OFFLINE, Arg.text("name", name(target)));
            return;
        }
        if (amount < s.payMinimum()) {
            this.services.messenger().send(payer, EconomyMessages.PAY_MINIMUM, Arg.money("amount", s.payMinimum()));
            return;
        }
        if (!this.services.ledger().available()) {
            this.services.messenger().send(payer, CoreMessages.ECONOMY_UNAVAILABLE);
            return;
        }
        if (this.services.ledger().balance(payer.getUniqueId(), Currency.MONEY) < amount) {
            this.services.messenger().send(payer, CoreMessages.NOT_ENOUGH_MONEY, Arg.money("amount", amount));
            return;
        }
        long limit = limitFor(payer);
        this.limits.load(payer.getUniqueId()).whenComplete((sent, error) -> this.services.scheduler().entity(payer, () -> {
            if (error != null) {
                this.services.messenger().send(payer, CoreMessages.ACTION_FAILED);
                return;
            }
            long left = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, limit - sent);
            if (amount > left) {
                this.services.messenger().send(payer, EconomyMessages.PAY_LIMIT, Arg.money("left", left), Arg.money("limit", limit));
                return;
            }
            if (amount >= s.payConfirmAbove() && s.payConfirmAbove() > 0) {
                confirm(payer, target, amount, limit, left);
            } else {
                execute(payer, target, amount, limit);
            }
        }, null));
    }

    private void confirm(Player payer, UUID target, long amount, long limit, long left) {
        var lang = this.services.lang();
        Arg leftArg = left == Long.MAX_VALUE ? Arg.text("left", "-") : Arg.money("left", left - amount);
        View view = this.services.templates().confirm(
            lang.get(EconomyMessages.PAY_CONFIRM_TITLE),
            lang.lines(EconomyMessages.PAY_CONFIRM_BODY, Arg.text("name", name(target)),
                Arg.text("amount", this.services.money().get().formatExact(amount)), leftArg),
            lang.get(EconomyMessages.PAY_CONFIRM_BUTTON),
            lang.get(CoreMessages.UI_CANCEL),
            submission -> {
                submission.close();
                execute(submission.player(), target, amount, limit);
            },
            submission -> {
                submission.close();
                this.services.messenger().send(submission.player(), EconomyMessages.PAY_CANCELLED);
            });
        this.services.dialogs().show(payer, view);
    }

    /** Fires the event and runs the transfer with the limit check inside the transaction. */
    private void execute(Player payer, UUID target, long amount, long limit) {
        UUID from = payer.getUniqueId();
        if (!new PlayerPayEvent(from, target, amount).callEvent()) {
            this.services.messenger().send(payer, EconomyMessages.PAY_CANCELLED);
            return;
        }
        LedgerTx tx = LedgerTx.builder()
            .actor(from)
            .transfer(from, target, Currency.MONEY, amount, "pay", null)
            .check(() -> limit != Long.MAX_VALUE && this.limits.sent(from) + amount > limit ? "limit" : null)
            .apply(() -> this.limits.add(from, amount), () -> this.limits.add(from, -amount))
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        switch (result.status()) {
            case SUCCESS -> {
                String targetName = name(target);
                this.services.messenger().send(payer, EconomyMessages.PAY_SENT, Arg.text("name", targetName), Arg.money("amount", amount));
                Player online = Bukkit.getPlayer(target);
                if (online != null && notifies(this.services.settings().enabled(target, this.notifications),
                    this.ignores.ignores(target, from), payer.hasPermission(UNIGNORABLE))) {
                    this.services.messenger().send(online, EconomyMessages.PAY_RECEIVED, Arg.text("name", payer.getName()),
                        Arg.money("amount", amount));
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
