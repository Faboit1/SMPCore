package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.FreezeStatus;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Staff moderation tools: vanish, freeze, mutes, bans, kicks, warnings and their history, staff chat, player
 * reports, inventory and ender chest inspection, alt and player lookups, broadcasts and clearing chat. Implements
 * {@link MuteStatus}, {@link VanishStatus} and {@link FreezeStatus} for the other features and the core. Every staff
 * action is written to the audit log. Staff tune their alerts, staff chat and vanish in the Staff settings group
 * ({@link StaffPreferences}).
 */
public final class StaffFeature implements Feature {

    private static final Duration SWEEP = Duration.ofSeconds(5);

    private final Services services;
    private final Setting<StaffSettings> settings;
    private final StaffStore store;
    private final Punishments punishments;
    private final VanishService vanish;
    private final FreezeService freeze;
    private final StaffChat staffChat;
    private final Reports reports;
    private final ReportDialogs reportDialogs;
    private final FakeLines fakeLines;
    private final List<SiftCommand> commands;
    private final List<Task> timers = new ArrayList<>();
    private volatile StaffRanks luckPerms;

    public StaffFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/staff.yml", StaffSettings::parse, problems);
        services.lang().register(StaffMessages.class);
        StaffNodes.declare(services.permissions());
        Logger logger = services.plugin().getLogger();
        services.lang().register(FakeLines.class);
        StaffText text = new StaffText(services.lang());
        StaffNotices notices = new StaffNotices(services.messenger(), services.settings());
        this.store = new StaffStore(services.database(), logger);
        this.punishments = new Punishments(this.store, services.audit(), services.messenger(), text, this.settings::get, logger);
        this.vanish = new VanishService(services.plugin(), services.scheduler(), this.store, services.audit(), services.messenger(),
            this.settings, services.settings(), logger);
        this.fakeLines = new FakeLines(services.lang(), services.settings(), services.plugin().getDataFolder().toPath(), logger);
        StaffPreferences.register(services.settings(), this.vanish::seeVanishedChanged, this.fakeLines::available);
        this.freeze = new FreezeService(services.scheduler(), this.store, services.audit(), services.messenger(), text, notices,
            this.punishments, this.settings, logger);
        this.staffChat = new StaffChat(services.lang(), services.settings());
        this.reports = new Reports(this.store, services.audit(), services.messenger(), notices, this.settings, logger);
        this.reportDialogs = new ReportDialogs(services, this.reports, this.settings);
        StaffHierarchy hierarchy = new StaffHierarchy(services.scheduler(), services.messenger(), services.audit(), this.settings,
            this::ranks, Bukkit::getPlayer, logger);
        Inspector inspector = new Inspector(services, hierarchy, logger);
        HistoryView history = new HistoryView(services, this.store, text, this.settings, logger);
        Lookups lookups = new Lookups(services, this.store, this.punishments, this.freeze, this.vanish, history, inspector, logger);
        Announcements announcements = new Announcements(services, this.settings);
        List<SiftCommand> all = new ArrayList<>();
        all.addAll(new PunishCommands(services, this.punishments, history, notices, text, this.settings, hierarchy).all());
        all.addAll(new ToolCommands(services, this.vanish, this.freeze, this.staffChat, announcements, inspector, lookups, notices, text,
            hierarchy, this.fakeLines).all());
        all.addAll(new ReportCommands(services, this.reports, this.reportDialogs).all());
        this.commands = List.copyOf(all);
    }

    @Override
    public String id() {
        return "staff";
    }

    /** Staff weights for the hierarchy: LuckPerms group weights while LuckPerms is enabled, otherwise the bypass only. */
    private StaffRanks ranks() {
        if (!Bukkit.getPluginManager().isPluginEnabled(LuckPermsStaffRanks.PLUGIN)) {
            return StaffRanks.PERMISSIONS;
        }
        StaffRanks ranks = this.luckPerms;
        if (ranks == null) {
            ranks = LuckPermsStaffRanks.connect();
            this.luckPerms = ranks;
        }
        return ranks;
    }

    /** Who is muted, for the chat feature and private messages. Thread-safe. */
    public MuteStatus mutes() {
        return this.punishments;
    }

    /** Who is vanished, for join and quit messages, online counts and /seen. Thread-safe. */
    public VanishStatus vanish() {
        return this.vanish;
    }

    /** Who is frozen, for the shared teleports and the dialog router (frozen players can't use either). Lock-free. */
    public FreezeStatus freezes() {
        return this.freeze;
    }

    /**
     * Rank join and leave lines and nicknames (the cosmetics feature, built after this one), so a fake join or leave
     * line on vanish looks exactly like the real one. The composition root calls it once cosmetics is built
     * ({@code staff.cosmetics(cosmetics.cosmetics())} in FeatureCatalog); without it fake lines imitate only the plain
     * join and leave lines of the extras feature.
     */
    public void cosmetics(Cosmetics cosmetics) {
        this.fakeLines.cosmetics(cosmetics);
    }

    @Override
    public void enable() throws Exception {
        this.store.open();
        this.punishments.load();
        this.vanish.load();
        this.freeze.load();
        this.reports.load();
        Plugin plugin = this.services.plugin();
        PluginManager plugins = Bukkit.getPluginManager();
        plugins.registerEvents(new PunishmentListener(this.punishments, this.store, this.services.scheduler(), this.services.messenger(),
            new StaffText(this.services.lang()), plugin.getLogger()), plugin);
        plugins.registerEvents(new ChatGuard(this.staffChat, this.punishments, this.settings), plugin);
        plugins.registerEvents(this.vanish, plugin);
        plugins.registerEvents(this.freeze, plugin);
        this.freeze.installAsyncTeleportGuard(plugin);
        this.freeze.installAsyncPortalGuard(plugin);
        this.timers.add(this.services.scheduler().asyncTimer(() -> {
            this.punishments.sweep();
            this.reports.sweep();
        }, SWEEP, SWEEP));
        this.services.hub().register(new HubEntry("report", 90, StaffMessages.HUB_REPORT, StaffMessages.HUB_REPORT_DESCRIPTION,
            StaffNodes.REPORT, player -> this.reportDialogs.openForm(player, "", "", submission -> {
                HubEntry menu = this.services.hub().get("menu");
                if (menu != null) {
                    menu.open().accept(submission.player());
                }
            })));
        registerPlaceholders();
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("staff_visible_online", "Online players, not counting vanished staff",
            player -> Integer.toString(Math.max(0, Bukkit.getOnlinePlayers().size() - this.vanish.online())));
        placeholders.register("staff_vanished", "true when the player is vanished, otherwise false",
            player -> Boolean.toString(this.vanish.vanished(player.getUniqueId())));
        placeholders.register("staff_reports_open", "Number of open player reports",
            player -> Integer.toString(this.reports.openCount()));
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands;
    }

    @Override
    public void disable() {
        for (Task task : this.timers) {
            task.cancel();
        }
        this.timers.clear();
        this.vanish.stopAll();
        this.freeze.shutdown();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.checkAsync(id(), "bans and mutes match storage", this.punishments::checkStorage);
        test.checkAsync(id(), "vanished staff match storage", this.vanish::checkStorage);
        test.checkAsync(id(), "frozen players match storage", this.freeze::checkStorage);
        test.checkAsync(id(), "open reports match storage", this.reports::checkStorage);
        test.check(id(), "vanished staff are hidden", this.vanish::checkHidden);
        test.check(id(), "times and reasons are read", () -> {
            Duration max = this.settings.get().maxLength();
            DurationInput.Parsed mute = DurationInput.optionalLength("1h30m spamming in chat", max);
            if (!mute.ok() || !Duration.ofMinutes(90).equals(mute.length()) || !mute.reason().equals("spamming in chat")) {
                return "1h30m spamming in chat was read as " + mute;
            }
            DurationInput.Parsed reasonOnly = DurationInput.optionalLength("5 alts", max);
            if (!reasonOnly.ok() || !reasonOnly.permanent() || !reasonOnly.reason().equals("5 alts")) {
                return "5 alts was read as " + reasonOnly;
            }
            return DurationInput.requiredLength("permanent", max).ok() ? "a temporary ban accepted 'permanent'" : null;
        });
    }
}
