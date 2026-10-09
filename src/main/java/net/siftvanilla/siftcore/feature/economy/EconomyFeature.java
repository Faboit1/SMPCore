package net.siftvanilla.siftcore.feature.economy;

import java.util.List;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.integration.vault.VaultHook;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Money: balances, /pay with confirmation and daily limits, the leaderboard, admin tools and the public API.
 * The money engine itself (the ledger) is core; this feature is how players use it.
 */
public final class EconomyFeature implements Feature, Listener {

    public static final Toggle PAY_NOTIFICATIONS = new Toggle("pay-notifications", true,
        EconomyMessages.SETTING_NOTIFICATIONS, EconomyMessages.SETTING_NOTIFICATIONS_DESCRIPTION, null);

    private final Services services;
    private final Setting<EconomySettings> settings;
    private final EconomyService economy;
    private final PayService pay;
    private final EconomyCommands commands;
    /** The Vault economy registration, when VaultUnlocked is installed. */
    private volatile VaultHook vault;

    public EconomyFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/economy.yml",
            reader -> EconomySettings.parse(reader, services.core().get().money()), problems);
        services.lang().register(EconomyMessages.class);
        services.settings().register(PAY_NOTIFICATIONS);
        var perms = services.permissions();
        perms.declare("siftcore.command.balance", "Use /balance", true);
        perms.declare("siftcore.command.balance.others", "See other players' balances", true);
        perms.declare("siftcore.command.pay", "Use /pay", true);
        perms.declare("siftcore.command.baltop", "Use /baltop", true);
        perms.declare("siftcore.pay.unlimited", "No daily /pay limit", false);
        perms.declare("siftcore.admin.eco", "Change balances and read the ledger with /eco", false);
        BalanceTop top = new BalanceTop(services.ledger(), services.directory());
        this.economy = new EconomyService(services.ledger(), top, services.core()::get);
        this.pay = new PayService(services, this.settings, new PayLimits(services.database()), PAY_NOTIFICATIONS);
        this.commands = new EconomyCommands(services, this.economy, this.pay, this.settings);
    }

    @Override
    public String id() {
        return "economy";
    }

    public EconomyService economy() {
        return this.economy;
    }

    /**
     * The ignore lists (the chat feature, built after this one): a player who ignores the payer isn't told about the
     * payment. Until set, nobody ignores anybody.
     */
    public void ignores(IgnoreLookup ignores) {
        this.pay.ignores(ignores);
    }

    @Override
    public void enable() {
        BalanceTop top = this.economy.leaderboard();
        top.refresh(this.settings.get().topSize());
        this.services.scheduler().asyncTimer(() -> top.refresh(this.settings.get().topSize()),
            this.settings.get().topRefresh(), this.settings.get().topRefresh());
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        this.services.hub().register(new HubEntry("money", 10, EconomyMessages.HUB_LABEL, EconomyMessages.HUB_DESCRIPTION,
            null, this::openHub));
        registerPlaceholders();
        if (Bukkit.getPluginManager().isPluginEnabled(VaultHook.PLUGIN)) {
            this.vault = VaultHook.register(this.services.plugin(), this.services.ledger(), this.services.directory(),
                this.services.money(), this.services.plugin().getLogger());
        }
    }

    @Override
    public void disable() {
        if (this.vault != null) {
            this.vault.unregister();
            this.vault = null;
        }
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        var money = this.services.money();
        placeholders.register("balance", "Your money, formatted ($1,500 or $2.5m)",
            p -> money.get().format(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("balance_exact", "Your money with every digit ($2,500,000)",
            p -> money.get().formatExact(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("balance_number", "Your money without the currency sign (1,500 or 2.5m)",
            p -> money.get().formatNumber(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("balance_raw", "Your money as a plain number (2500000)",
            p -> Long.toString(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("shards", "Your shards with separators (1,250)",
            p -> Lang.number(this.economy.balance(p.getUniqueId(), Currency.SHARDS)));
        placeholders.register("shards_raw", "Your shards as a plain number",
            p -> Long.toString(this.economy.balance(p.getUniqueId(), Currency.SHARDS)));
        placeholders.register("baltop_rank", "Your place on the money leaderboard (0 when unranked)",
            p -> Integer.toString(this.economy.leaderboard().rankOf(Currency.MONEY, this.economy.balance(p.getUniqueId(), Currency.MONEY))));
        placeholders.registerPrefix("baltop_name_", "baltop_name_<rank>", "Name at a leaderboard place (1-100)",
            (p, arg) -> topEntry(arg).map(e -> e.name()).orElse("-"));
        placeholders.registerPrefix("baltop_value_", "baltop_value_<rank>", "Money at a leaderboard place, formatted",
            (p, arg) -> topEntry(arg).map(e -> money.get().format(e.value())).orElse("-"));
    }

    private java.util.Optional<net.siftvanilla.siftcore.api.economy.EconomyApi.TopEntry> topEntry(String rankText) {
        int rank;
        try {
            rank = Integer.parseInt(rankText);
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
        var top = this.economy.top(Currency.MONEY, rank);
        return rank >= 1 && rank <= top.size() ? java.util.Optional.of(top.get(rank - 1)) : java.util.Optional.empty();
    }

    /**
     * The money page of the hub: balance, shards, rank, daily pay limit left, and actions. The screen it was opened
     * from stays until the page (shown after the day's pay total loads) replaces it. Pay and Richest players come back
     * here with Back.
     */
    public void openHub(Player player) {
        Lang lang = this.services.lang();
        long balance = this.economy.balance(player.getUniqueId(), Currency.MONEY);
        long limit = this.pay.limitFor(player);
        this.services.dialogs().markShown(player);
        this.pay.limits().load(player.getUniqueId()).whenComplete((sent, error) -> this.services.scheduler().entity(player, () -> {
            HubLimit line = HubLimit.of(limit, sent, error);
            int rank = this.economy.leaderboard().rankOf(Currency.MONEY, balance);
            Arg amount = Arg.money("amount", balance);
            Arg shards = Arg.number("shards", this.economy.balance(player.getUniqueId(), Currency.SHARDS));
            Arg place = rank == 0 ? Arg.text("rank", "-") : Arg.number("rank", rank);
            // Without a limit there is no "you can still send" line rather than a dash.
            var body = line == HubLimit.LEFT
                ? lang.lines(EconomyMessages.HUB_BODY, amount, shards, place, Arg.money("left", Math.max(0, limit - sent)))
                : lang.lines(EconomyMessages.HUB_BODY_UNLIMITED, amount, shards, place);
            Button.Handler back = s -> openHub(s.player());
            View page = this.services.templates().list(lang.get(EconomyMessages.HUB_TITLE), body,
                List.of(
                    Button.of(lang.get(EconomyMessages.HUB_PAY), s -> this.commands.openPayForm(s.player(), "", "", back)).width(150),
                    Button.of(lang.get(EconomyMessages.HUB_TOP), s -> this.commands.openTopDialog(s.player(), 1, back)).width(150)),
                2, s -> openMenu(s.player()));
            this.services.dialogs().show(player, line == HubLimit.UNKNOWN
                ? page.withError(lang.get(EconomyMessages.HUB_LIMIT_FAILED), FormValues.EMPTY)
                : page);
        }, null));
    }

    /** What the money page says about today's pay limit. */
    enum HubLimit {
        /** "You can still send ... today". */
        LEFT,
        /** Nothing: this player has no daily limit. */
        NONE,
        /** Today's total couldn't be loaded: a red line says so, since no line at all would read like having no limit. */
        UNKNOWN;

        /**
         * @param limit the player's daily limit ({@link Long#MAX_VALUE}: none)
         * @param sent  what they sent today, or null when it failed to load
         * @param error why it failed to load, or null
         */
        static HubLimit of(long limit, Long sent, Throwable error) {
            if (limit == Long.MAX_VALUE) {
                return NONE;
            }
            return error != null || sent == null ? UNKNOWN : LEFT;
        }
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.pay.limits().forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "pay limit formula", () -> {
            long limit = PayLimits.limit(250_000, 50_000, 100_000_000, 10);
            return limit == 750_000 ? null : "expected 750000, got " + limit;
        });
        test.check(id(), "money format round trip", () -> {
            var format = this.services.money().get();
            var parsed = format.parse("1.5k");
            return parsed.ok() && parsed.amount() == 1500 ? null : "1.5k did not parse to 1500";
        });
        test.check(id(), "leaderboard is built", () -> this.economy.top(Currency.MONEY, 1) != null ? null : "no leaderboard");
        test.check(id(), "Vault economy is SiftCore's", () -> {
            if (!Bukkit.getPluginManager().isPluginEnabled(VaultHook.PLUGIN)) {
                return null;
            }
            VaultHook hook = this.vault;
            return hook != null && hook.active() ? null : "another economy plugin is registered with Vault above SiftCore";
        });
    }
}
