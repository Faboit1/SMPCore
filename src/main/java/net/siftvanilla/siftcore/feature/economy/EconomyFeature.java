package net.siftvanilla.siftcore.feature.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.integration.vault.VaultHook;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Money: balances, /pay with confirmation and daily limits, the leaderboard, admin tools and the public API.
 * The money engine itself (the ledger) is core; this feature is how players use it.
 * <p>
 * Player settings (Money &amp; selling): how payment alerts show and from which amount, when {@code /pay} asks first,
 * who may pay the player and the summary of payments received while offline. It also acts on the shared
 * {@code balance-privacy} ({@code /balance <name>}) and {@code hide-from-leaderboards} (the money leaderboard).
 */
public final class EconomyFeature implements Feature, Listener {

    /** How a player is told someone paid them. Was a switch: on reads as chat, off as off. */
    public static final Choice<AlertStyle> PAY_NOTIFICATIONS = Choices.alert("pay-notifications", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .legacyValue("true", AlertStyle.CHAT.id()).legacyValue("false", AlertStyle.OFF.id())
        .text(EconomyMessages.SETTING_NOTIFICATIONS, EconomyMessages.SETTING_NOTIFICATIONS_DESCRIPTION).build();
    /** From which amount the payer's {@code /pay} asks first; never looser than the server's {@code pay.confirm-above}. */
    public static final Choice<ConfirmAbove> PAY_CONFIRM_ABOVE = Choices.confirmAbove("pay-confirm-above", Currency.MONEY, false,
            "1k", "10k", "100k")
        .text(EconomyMessages.SETTING_CONFIRM_ABOVE, EconomyMessages.SETTING_CONFIRM_ABOVE_DESCRIPTION).build();
    /**
     * Who may pay the player. "Friends and teammates" is offered while there are friends or teams (with only teams it
     * means teammates), "friends" while there are friends; an option that is not offered reads as nobody (never more
     * open than the player chose). Ignored players can never pay, whatever the choice.
     */
    public static final Choice<Audience> PAY_ACCEPT_FROM = Choice.ofEnum("pay-accept-from", Audience.class, Audience::id, Audience.EVERYONE)
        .option(Audience.EVERYONE, Audience.EVERYONE.label())
        .option(Audience.FRIENDS_TEAM, Audience.FRIENDS_TEAM.label(), null, Audience.NOBODY.id())
        .option(Audience.FRIENDS, Audience.FRIENDS.label(), null, Audience.NOBODY.id())
        .option(Audience.NOBODY, Audience.NOBODY.label())
        .text(EconomyMessages.SETTING_ACCEPT_FROM, EconomyMessages.SETTING_ACCEPT_FROM_DESCRIPTION).build();
    /** The summary of payments received while offline, shown on join. */
    public static final Toggle PAY_JOIN_SUMMARY = new Toggle("pay-join-summary", true, EconomyMessages.SETTING_JOIN_SUMMARY,
        EconomyMessages.SETTING_JOIN_SUMMARY_DESCRIPTION, null);
    /** Payment alerts below this amount are skipped (the money arrives either way). */
    public static final Choice<Long> PAY_ALERT_MINIMUM = alertMinimum("any", "100", "1k", "10k", "100k");

    /** How long after joining the summary of payments received while away shows (so it is not lost in the join lines). */
    private static final long AWAY_DELAY_TICKS = 50L;
    /** Payers listed by name in that summary. */
    private static final int AWAY_SHOWN = 5;

    private final Services services;
    private final Setting<EconomySettings> settings;
    private final EconomyService economy;
    private final PayService pay;
    private final EconomyCommands commands;
    private final HiddenAccounts hidden;
    private final PaymentsAway away;
    /** Who the money leaderboard left out at the last rebuild that could read it (kept when a read fails). */
    private volatile Predicate<UUID> lastHidden = account -> false;
    /** The Vault economy registration, when VaultUnlocked is installed. */
    private volatile VaultHook vault;

    public EconomyFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/economy.yml",
            reader -> EconomySettings.parse(reader, services.core().get().money()), problems);
        services.lang().register(EconomyMessages.class);
        registerSettings(services.settings(), services.relations(), this.settings::get);
        var perms = services.permissions();
        perms.declare("siftcore.command.balance", "Use /balance", true);
        perms.declare("siftcore.command.balance.others", "See other players' balances (when their balance privacy lets you)", true);
        perms.declare("siftcore.command.pay", "Use /pay", true);
        perms.declare("siftcore.command.baltop", "Use /baltop", true);
        perms.declare("siftcore.pay.unlimited", "No daily /pay limit", false);
        perms.declare("siftcore.admin.eco", "Change balances and read the ledger with /eco; see every balance", false);
        BalanceTop top = new BalanceTop(services.ledger(), services.directory());
        this.economy = new EconomyService(services.ledger(), top, services.core()::get);
        this.pay = new PayService(services, this.settings, new PayLimits(services.database()));
        this.commands = new EconomyCommands(services, this.economy, this.pay, this.settings);
        this.hidden = new HiddenAccounts(services.database(), services.settings());
        this.away = new PaymentsAway(services.database());
    }

    /**
     * Registers the payment settings in Money &amp; selling, in the catalog's order (sale receipts, a shared setting,
     * is first; the sell feature's settings sit in between), and declares the shared settings this feature acts on.
     */
    static void registerSettings(PlayerSettings prefs, Relations relations, Supplier<EconomySettings> config) {
        prefs.register(SettingCategories.ECONOMY, PAY_NOTIFICATIONS, SettingOptions.<AlertStyle>builder().order(2).build());
        prefs.register(SettingCategories.ECONOMY, PAY_CONFIRM_ABOVE, SettingOptions.<ConfirmAbove>builder().order(6).build());
        prefs.register(SettingCategories.ECONOMY, PAY_ACCEPT_FROM, SettingOptions.<Audience>builder().order(7)
            .optionAvailableWhen(Audience.FRIENDS_TEAM.id(), () -> friendsOrTeams(relations))
            .optionAvailableWhen(Audience.FRIENDS.id(), relations::friendsAvailable)
            .placeholder(false).build());
        // Payments reach offline players only when the server allows it; otherwise there is never anything to sum up.
        prefs.register(SettingCategories.ECONOMY, PAY_JOIN_SUMMARY, SettingOptions.<Boolean>builder().order(8)
            .availableWhen(() -> config.get().payOfflineTargets()).build());
        prefs.register(SettingCategories.ECONOMY, PAY_ALERT_MINIMUM, SettingOptions.<Long>builder().order(9).build());
        // /balance <name> follows balance-privacy; the money leaderboard leaves out hide-from-leaderboards.
        prefs.reads(SharedSettings.BALANCE_PRIVACY);
        prefs.reads(SharedSettings.HIDE_FROM_LEADERBOARDS);
    }

    /** Whether "friends and teammates" means anyone: the server has friends or teams. */
    static boolean friendsOrTeams(Relations relations) {
        return relations.friendsAvailable() || relations.teams() != TeamLookup.NONE;
    }

    /**
     * The {@code pay-alert-minimum} choice: {@code any} (0), then amount presets such as {@code 1k}, read with the
     * default money suffixes so their ids stay stable whatever the server's currency format.
     */
    private static Choice<Long> alertMinimum(String any, String... presets) {
        Choice.Builder<Long> builder = Choice.builder("pay-alert-minimum", 0L);
        builder.option(any, 0L, EconomyMessages.SETTING_ALERT_MINIMUM_ANY);
        for (String preset : presets) {
            MoneyFormat.ParseResult parsed = MoneyFormat.defaults().parse(preset);
            if (!parsed.ok() || parsed.amount() <= 0) {
                throw new IllegalArgumentException("Bad payment alert preset " + preset);
            }
            builder.option(preset, parsed.amount(), OptionTexts.FROM_AMOUNT, Arg.money("amount", parsed.amount()));
        }
        return builder.text(EconomyMessages.SETTING_ALERT_MINIMUM, EconomyMessages.SETTING_ALERT_MINIMUM_DESCRIPTION).build();
    }

    @Override
    public String id() {
        return "economy";
    }

    public EconomyService economy() {
        return this.economy;
    }

    /**
     * Kept for the composition root, which still hands over the chat feature's ignore lists. Payments now ask
     * {@code services.relations()} (bound by the composition root to the same lists), so this does nothing.
     */
    public void ignores(IgnoreLookup ignores) {
        // Nothing to do: see the comment above.
    }

    @Override
    public void enable() {
        refreshTop();
        this.services.scheduler().asyncTimer(this::refreshTop, this.settings.get().topRefresh(), this.settings.get().topRefresh());
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

    /**
     * Rebuilds the money leaderboard without the players who hide from leaderboards. Reads who is hidden from the
     * settings table first (most accounts are offline), so it runs on an async thread (and once at startup); when
     * that read fails the last known answer is kept, so a database hiccup never shows hidden players.
     */
    private void refreshTop() {
        Predicate<UUID> left = this.lastHidden;
        try {
            left = this.hidden.load();
            this.lastHidden = left;
        } catch (Exception e) {
            this.services.plugin().getLogger().log(Level.WARNING, "Could not read who hides from the money leaderboard; "
                + "keeping the last list", e);
        }
        this.economy.leaderboard().refresh(this.settings.get().topSize(), left);
    }

    /**
     * Tells a returning player who paid them while they were away (their {@code pay-join-summary} switch), a moment
     * after they joined so the lines are not lost among the join messages. Counts {@code /pay} transfers after the end
     * of their last session ({@code PlayerDirectory#previousSeen}); nothing on a first join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long since = this.services.directory().previousSeen(uuid);
        if (since <= 0 || !this.settings.get().payOfflineTargets() || !this.services.settings().get(uuid, PAY_JOIN_SUMMARY)) {
            return;
        }
        this.services.scheduler().entityLater(player, () -> this.away.since(uuid, since).whenComplete((rows, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "Could not read the payments " + player.getName()
                    + " received while away", error);
                return;
            }
            this.services.scheduler().entity(player, () -> showAway(player, PayRules.away(rows, AWAY_SHOWN)), null);
        }), null, AWAY_DELAY_TICKS);
    }

    /** The "while you were away" lines, in one chat message with the notify sound. Player's thread. */
    private void showAway(Player player, PayRules.Away away) {
        if (away.empty() || !player.isOnline() || !this.services.settings().get(player.getUniqueId(), PAY_JOIN_SUMMARY)) {
            return;
        }
        Lang lang = this.services.lang();
        // Written for the returning player: amounts in their money format.
        player.sendMessage(lang.viewing(player, () -> {
            List<Component> lines = new ArrayList<>();
            lines.add(lang.get(EconomyMessages.AWAY_HEADER, Arg.money("total", away.total())));
            for (PayRules.Payer payer : away.payers()) {
                Arg name = Arg.text("name", this.services.directory().name(payer.payer()));
                Arg amount = Arg.money("amount", payer.total());
                lines.add(payer.payments() > 1
                    ? lang.get(EconomyMessages.AWAY_LINE_MANY, name, amount, Arg.number("count", payer.payments()))
                    : lang.get(EconomyMessages.AWAY_LINE, name, amount));
            }
            if (away.more() > 0) {
                lines.add(lang.get(EconomyMessages.AWAY_MORE, Arg.number("count", away.more())));
            }
            return Component.join(JoinConfiguration.newlines(), lines);
        }));
        this.services.messenger().feedback(player, Feedback.NOTIFY);
    }

    /**
     * The money format of the player a placeholder is asked for (PlaceholderAPI's player): the viewer for the SiftCore
     * sidebar and TAB's header and footer, but the subject for tab list names, nametags, below-name lines and chat
     * formats, where everyone then sees that player's choice. The server's way when no player is given; a player whose
     * settings are not loaded (offline) reads the server's default or lock for {@code money-format}.
     */
    private MoneyStyle style(OfflinePlayer player) {
        return player == null ? MoneyStyle.SERVER : this.services.lang().styleOf(player.getUniqueId());
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        var money = this.services.money();
        placeholders.register("balance", "Your money in your money format ($1,500, $2.5m, or as set in your settings)",
            p -> money.get().format(this.economy.balance(p.getUniqueId(), Currency.MONEY), style(p)));
        placeholders.register("balance_server", "Your money the server's way for everyone ($1,500 or $2.5m), whatever money format anyone chose",
            p -> money.get().format(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("balance_exact", "Your money with every digit ($2,500,000)",
            p -> money.get().formatExact(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("balance_number", "Your money without the currency sign, in your money format (1,500 or 2.5m)",
            p -> style(p).formatNumber(money.get(), this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("balance_raw", "Your money as a plain number (2500000)",
            p -> Long.toString(this.economy.balance(p.getUniqueId(), Currency.MONEY)));
        placeholders.register("shards", "Your shards with separators (1,250)",
            p -> Lang.number(this.economy.balance(p.getUniqueId(), Currency.SHARDS)));
        placeholders.register("shards_raw", "Your shards as a plain number",
            p -> Long.toString(this.economy.balance(p.getUniqueId(), Currency.SHARDS)));
        placeholders.register("baltop_rank", "Your place on the money leaderboard (0 when unranked or hidden)",
            p -> Integer.toString(this.economy.leaderboard().rankOf(Currency.MONEY, p.getUniqueId(), this.economy.balance(p.getUniqueId(), Currency.MONEY))));
        placeholders.registerPrefix("baltop_name_", "baltop_name_<rank>", "Name at a leaderboard place (1-100)",
            (p, arg) -> topEntry(arg).map(e -> e.name()).orElse("-"));
        placeholders.registerPrefix("baltop_value_", "baltop_value_<rank>",
            "Money at a leaderboard place, in the viewer's money format (the server's way without a viewer)",
            (p, arg) -> topEntry(arg).map(e -> money.get().format(e.value(), style(p))).orElse("-"));
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
        // Built on the player's thread after the load, for them: amounts in their money format.
        this.pay.limits().load(player.getUniqueId()).whenComplete((sent, error) -> this.services.scheduler().entity(player, () -> lang.viewing(player, () -> {
            HubLimit line = HubLimit.of(limit, sent, error);
            int rank = this.economy.leaderboard().rankOf(Currency.MONEY, player.getUniqueId(), balance);
            // The balance and shards; how much can still be sent and the leaderboard place are in the buttons' tooltips.
            var body = lang.lines(EconomyMessages.HUB_BODY, Arg.money("amount", balance),
                Arg.shards("shard-count", this.economy.balance(player.getUniqueId(), Currency.SHARDS)));
            List<Component> payTooltip = new ArrayList<>(lang.lines(EconomyMessages.HUB_PAY_TOOLTIP));
            if (line == HubLimit.LEFT) {
                payTooltip.addAll(lang.lines(EconomyMessages.HUB_PAY_LEFT, Arg.money("left", Math.max(0, limit - sent))));
            }
            List<Component> topTooltip = new ArrayList<>(lang.lines(EconomyMessages.HUB_TOP_TOOLTIP,
                Arg.number("count", this.settings.get().topSize())));
            if (rank > 0) {
                topTooltip.addAll(lang.lines(EconomyMessages.HUB_TOP_RANK, Arg.text("rank", Lang.number(rank))));
            }
            Button.Handler back = s -> openHub(s.player());
            View page = this.services.templates().list(lang.get(EconomyMessages.HUB_TITLE), body,
                List.of(
                    Button.of(lang.get(EconomyMessages.HUB_PAY), Templates.lines(payTooltip),
                        s -> this.commands.openPayForm(s.player(), "", "", back)).width(150),
                    Button.of(lang.get(EconomyMessages.HUB_TOP), Templates.lines(topTooltip),
                        s -> this.commands.openTopDialog(s.player(), back)).width(150)),
                2, s -> openMenu(s.player()));
            this.services.dialogs().show(player, line == HubLimit.UNKNOWN
                ? page.withError(lang.get(EconomyMessages.HUB_LIMIT_FAILED), FormValues.EMPTY)
                : page);
        }), null));
    }

    /** What the money page says about today's pay limit. */
    enum HubLimit {
        /** "You can still send ... today" (in the Pay button's tooltip). */
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
        test.check(id(), "payment settings are in Money & selling", () -> {
            for (var setting : List.of(PAY_NOTIFICATIONS, PAY_CONFIRM_ABOVE, PAY_ACCEPT_FROM, PAY_JOIN_SUMMARY, PAY_ALERT_MINIMUM)) {
                if (!SettingCategories.ECONOMY.equals(this.services.settings().category(setting))) {
                    return setting.id() + " is not in the Money & selling group";
                }
            }
            return null;
        });
        test.check(id(), "payment rules", () -> {
            if (!PayRules.asks(ConfirmAbove.SERVER, 100_000, 100_000) || PayRules.asks(ConfirmAbove.SERVER, 99_999, 100_000)) {
                return "the server's confirm-above is not followed";
            }
            if (!PayRules.asks(PAY_CONFIRM_ABOVE.decodeOrNull("1k"), 1_000, 0)) {
                return "a player's lower confirm amount does not ask";
            }
            if (PayRules.accepts(Audience.EVERYONE, false, false, true, false) || !PayRules.accepts(Audience.FRIENDS, true, false, false, false)) {
                return "who may pay is not followed";
            }
            return PayRules.alerts(AlertStyle.CHAT, 99, 100) ? "a payment below the alert minimum alerts" : null;
        });
    }
}
