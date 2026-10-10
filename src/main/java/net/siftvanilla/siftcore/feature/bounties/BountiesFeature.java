package net.siftvanilla.siftcore.feature.bounties;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.event.PlayerKillCreditEvent;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Announce;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Bounties: players put money on other players' heads (it waits in the bounty escrow), whoever gets a counted kill
 * on them claims it minus a tax, and contributions nobody claims are refunded after a while. Kills come from the
 * combat feature through {@link PlayerKillCreditEvent}, so the anti-farm rules apply to claims too.
 * <p>
 * Player settings: how the target is told about a bounty on them, when placing asks for confirmation and the join
 * reminder (Combat &amp; stats), and which bounty announcements a player sees (Server announcements).
 */
public final class BountiesFeature implements Feature, Listener {

    /** How a player is told someone put money on their head. */
    public static final Choice<AlertStyle> TARGET_ALERT = Choices.alert("bounty-target-alert", AlertStyle.CHAT,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.TITLE, AlertStyle.OFF)
        .text(BountiesMessages.SETTING_TARGET_ALERT, BountiesMessages.SETTING_TARGET_ALERT_DESCRIPTION).build();
    /** Which new and claimed bounties a player sees in chat: all, from an amount, or none. */
    public static final Choice<Announce> ANNOUNCEMENTS = Choices.announce("bounty-announcements", Currency.MONEY, "100k", "1m", "10m")
        .text(BountiesMessages.SETTING_ANNOUNCEMENTS, BountiesMessages.SETTING_ANNOUNCEMENTS_DESCRIPTION).build();
    /** From which amount placing a bounty asks first. No "never": a bounty can't be taken back. */
    public static final Choice<ConfirmAbove> CONFIRM_ABOVE = Choices.confirmAbove("bounty-confirm-above", Currency.MONEY, false,
            "10k", "100k", "1m")
        .text(BountiesMessages.SETTING_CONFIRM_ABOVE, BountiesMessages.SETTING_CONFIRM_ABOVE_DESCRIPTION).build();
    /** The reminder of the bounty on a player's head when they join. */
    public static final Toggle JOIN_REMINDER = new Toggle("bounty-join-reminder", true, BountiesMessages.SETTING_JOIN_REMINDER,
        BountiesMessages.SETTING_JOIN_REMINDER_DESCRIPTION, null);

    private static final long JOIN_REMINDER_DELAY_TICKS = 60L;

    private final Services services;
    private final Setting<BountiesSettings> settings;
    private final BountyService service;
    private final BountyActions actions;
    private final BountyViews views;
    private final BountyCommands commands;
    private volatile Task expiryTask = Task.NONE;
    private volatile Duration expiryPeriod = Duration.ZERO;

    public BountiesFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/bounties.yml",
            reader -> BountiesSettings.parse(reader, services.core().get().money()), problems);
        services.lang().register(BountiesMessages.class);
        registerSettings(services.settings(), this.settings::get);
        var perms = services.permissions();
        perms.declare(BountyCommands.USE, "See bounties with /bounties", true);
        perms.declare(BountyCommands.PLACE, "Put bounties on players with /bounty <player> <amount>", true);
        perms.declare(BountyCommands.ADMIN, "Inspect, remove and expire bounties with /bountyadmin", false);
        this.service = new BountyService(services.ledger(), services.database(), new BountyBook());
        this.actions = new BountyActions(services, this.settings, this.service);
        this.views = new BountyViews(services, this.settings, this.actions);
        this.commands = new BountyCommands(services, this.settings, this.actions, this.views);
    }

    /** Registers the bounty settings in their groups, in the catalog's order; config-dependent ones only while it is on. */
    static void registerSettings(PlayerSettings prefs, Supplier<BountiesSettings> config) {
        prefs.register(SettingCategories.COMBAT, TARGET_ALERT, SettingOptions.<AlertStyle>builder().order(6)
            .availableWhen(() -> config.get().notifyTarget()).build());
        prefs.register(SettingCategories.COMBAT, CONFIRM_ABOVE, SettingOptions.<ConfirmAbove>builder().order(10).build());
        prefs.register(SettingCategories.COMBAT, JOIN_REMINDER, SettingOptions.<Boolean>builder().order(11)
            .availableWhen(() -> config.get().remindOnJoin()).build());
        prefs.register(SettingCategories.ANNOUNCEMENTS, ANNOUNCEMENTS, SettingOptions.<Announce>builder().order(4)
            .availableWhen(() -> config.get().announcePlacements() || config.get().announceClaims()).build());
    }

    @Override
    public String id() {
        return "bounties";
    }

    @Override
    public void enable() throws Exception {
        this.service.load();
        String escrow = this.service.checkEscrow();
        if (escrow != null) {
            this.services.plugin().getLogger().severe("Bounties: " + escrow + ". Run /sift selftest and check the ledger.");
        }
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        scheduleExpiry(this.settings.get().expiryCheck());
        this.settings.onReload(s -> {
            if (!s.expiryCheck().equals(this.expiryPeriod)) {
                scheduleExpiry(s.expiryCheck());
            }
        });
        this.services.hub().register(new HubEntry("bounties", 75, BountiesMessages.HUB_LABEL, BountiesMessages.HUB_DESCRIPTION,
            BountyCommands.USE, player -> this.views.openList(player, true)));
        registerPlaceholders();
    }

    private synchronized void scheduleExpiry(Duration period) {
        this.expiryTask.cancel();
        this.expiryPeriod = period;
        this.expiryTask = this.services.scheduler().asyncTimer(() -> {
            try {
                this.actions.expire();
            } catch (RuntimeException e) {
                this.services.plugin().getLogger().log(Level.SEVERE, "Refunding expired bounties failed", e);
            }
        }, Duration.ofSeconds(10), period);
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        // Amounts in the money format of the player PlaceholderAPI asks for, like the balance placeholders.
        var lang = this.services.lang();
        BountyBook book = this.service.book();
        placeholders.register("bounty_total", "The bounty on you, in your money format ($50,000)",
            p -> lang.moneyFor(p, book.total(p.getUniqueId())));
        placeholders.register("bounty_total_raw", "The bounty on you as a plain number",
            p -> Long.toString(book.total(p.getUniqueId())));
        placeholders.registerPrefix("bounty_top_name_", "bounty_top_name_<n>", "Name of the player with the n-th biggest bounty (1-20)",
            (p, arg) -> top(arg).map(bounty -> this.services.directory().name(bounty.target())).orElse("-"));
        placeholders.registerPrefix("bounty_top_value_", "bounty_top_value_<n>",
            "The n-th biggest bounty, in the viewer's money format (the server's way without a viewer)",
            (p, arg) -> top(arg).map(bounty -> lang.moneyFor(p, bounty.total())).orElse("-"));
    }

    private java.util.Optional<BountyBook.Bounty> top(String rankText) {
        int rank;
        try {
            rank = Integer.parseInt(rankText);
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
        if (rank < 1 || rank > 20) {
            return java.util.Optional.empty();
        }
        List<BountyBook.Bounty> top = this.service.book().top(rank);
        return rank <= top.size() ? java.util.Optional.of(top.get(rank - 1)) : java.util.Optional.empty();
    }

    /** A counted kill: the killer claims the bounty on the victim (the combat feature already applied anti-farm). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKillCredit(PlayerKillCreditEvent event) {
        this.actions.claim(event.killer(), event.victim());
    }

    /**
     * Reminds a player with a bounty on their head, a moment after they joined (so the line is not lost), unless
     * they turned the reminder off.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!this.settings.get().remindOnJoin() || this.service.book().total(player.getUniqueId()) <= 0
            || !this.services.settings().get(player.getUniqueId(), JOIN_REMINDER)) {
            return;
        }
        this.services.scheduler().entityLater(player, () -> {
            long total = this.service.book().total(player.getUniqueId());
            if (total > 0 && player.isOnline() && this.services.settings().get(player.getUniqueId(), JOIN_REMINDER)) {
                this.services.messenger().send(player, BountiesMessages.JOIN_REMINDER, Arg.money("total", total));
            }
        }, null, JOIN_REMINDER_DELAY_TICKS);
    }

    @Override
    public void disable() {
        this.expiryTask.cancel();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "bounty escrow equals active bounties", this.service::checkEscrow);
        test.checkAsync(id(), "stored bounty escrow equals stored active bounties", this.service::checkStoredEscrow);
        test.check(id(), "claim tax math", () -> {
            BountyMath.Split split = BountyMath.split(12_345, 10);
            if (split.tax() != 1_234 || split.payout() != 11_111) {
                return "10% of 12,345 split into " + split.tax() + " tax and " + split.payout() + " payout";
            }
            return BountyMath.tax(Long.MAX_VALUE, 90) > 0 ? null : "the tax of a huge bounty overflowed";
        });
    }
}
