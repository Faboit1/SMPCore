package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Combat: the combat tag (hits between players put both in combat; commands, teleports, elytra and spawn are
 * refused while it runs), combat logging, kill credit with anti-farm rules (teams, friends, alt accounts, repeated
 * pairs), clean death messages and kill streak announcements. Kills and deaths go to the stats; bounties hook into
 * counted kills through {@link net.siftvanilla.siftcore.api.event.PlayerKillCreditEvent}. Vanished staff take no
 * part in combat and are never named to players who can't see them. Players are named as they show themselves
 * (nicknames), and a killer's kill effect plays where the victim fell ({@link Cosmetics}).
 * <p>
 * Player settings: where the combat timer, the tag alert, the end notice and the kill confirmation show, the private
 * death location and recap (Combat &amp; stats), which death messages, kill streak and combat log lines a player sees
 * (Server announcements), and the staff combat alerts (Staff).
 */
public final class CombatFeature implements Feature {

    /** Which deaths of other players a player sees. Was a switch: on reads as all, off as none. */
    public static final Choice<DeathFilter> DEATH_MESSAGES = Choice.ofEnum("death-messages", DeathFilter.class, DeathFilter::id,
            DeathFilter.ALL)
        .option(DeathFilter.ALL, DeathFilter.ALL.label())
        .option(DeathFilter.PVP, DeathFilter.PVP.label())
        .option(DeathFilter.FRIENDS_TEAM, DeathFilter.FRIENDS_TEAM.label())
        .option(DeathFilter.OFF, DeathFilter.OFF.label())
        .legacyValue("true", DeathFilter.ALL.id()).legacyValue("false", DeathFilter.OFF.id())
        .text(CombatMessages.SETTING_DEATH_MESSAGES, CombatMessages.SETTING_DEATH_MESSAGES_DESCRIPTION).build();
    /** "X is on a kill streak" and "Y ended X's streak" lines. */
    public static final Toggle STREAK_ANNOUNCEMENTS = new Toggle("kill-streak-announcements", true, CombatMessages.SETTING_STREAKS,
        CombatMessages.SETTING_STREAKS_DESCRIPTION, null);
    /** "X logged out in combat" lines. */
    public static final Toggle LOG_ANNOUNCEMENTS = new Toggle("combat-log-announcements", true, CombatMessages.SETTING_LOG_ANNOUNCEMENTS,
        CombatMessages.SETTING_LOG_ANNOUNCEMENTS_DESCRIPTION, null);
    /** Where the "In combat 12s" timer shows. */
    public static final Choice<AlertStyle> TIMER_DISPLAY = Choices.alert("combat-timer-display", AlertStyle.ACTIONBAR,
        AlertStyle.ACTIONBAR, AlertStyle.BOSSBAR, AlertStyle.BOTH, AlertStyle.OFF)
        .text(CombatMessages.SETTING_TIMER, CombatMessages.SETTING_TIMER_DESCRIPTION).build();
    /** How a player is told they just entered combat. */
    public static final Choice<AlertStyle> TAG_ALERT = Choices.alert("combat-tag-alert", AlertStyle.CHAT,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.TITLE, AlertStyle.OFF)
        .text(CombatMessages.SETTING_TAG_ALERT, CombatMessages.SETTING_TAG_ALERT_DESCRIPTION).build();
    /** How the killer is told whether their kill counted. */
    public static final Choice<AlertStyle> KILL_FEEDBACK = Choices.alert("kill-feedback", AlertStyle.ACTIONBAR,
        AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.TITLE, AlertStyle.OFF)
        .text(CombatMessages.SETTING_KILL_FEEDBACK, CombatMessages.SETTING_KILL_FEEDBACK_DESCRIPTION).build();
    /** A private line with where the player died. */
    public static final Toggle DEATH_COORDINATES = new Toggle("death-coordinates", true, CombatMessages.SETTING_DEATH_COORDINATES,
        CombatMessages.SETTING_DEATH_COORDINATES_DESCRIPTION, null);
    /** How a player is told they are out of combat. */
    public static final Choice<AlertStyle> END_NOTICE = Choices.alert("combat-end-notice", AlertStyle.ACTIONBAR,
        AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.TITLE, AlertStyle.OFF)
        .text(CombatMessages.SETTING_END_NOTICE, CombatMessages.SETTING_END_NOTICE_DESCRIPTION).build();
    /** The killer's weapon and the health they had left, told to their victim. */
    public static final Toggle DEATH_RECAP = new Toggle("death-recap", true, CombatMessages.SETTING_DEATH_RECAP,
        CombatMessages.SETTING_DEATH_RECAP_DESCRIPTION, null);
    /** Which combat alerts staff get: combat logs, also kills that didn't count, or none. */
    public static final Choice<StaffAlerts> STAFF_ALERTS = Choice.ofEnum("staff-combat-alerts", StaffAlerts.class, StaffAlerts::id,
            StaffAlerts.COMBAT_LOGS)
        .option(StaffAlerts.COMBAT_LOGS, StaffAlerts.COMBAT_LOGS.label())
        .option(StaffAlerts.LOGS_AND_FARMING, StaffAlerts.LOGS_AND_FARMING.label())
        .option(StaffAlerts.OFF, StaffAlerts.OFF.label())
        .permission(CombatCommands.ADMIN)
        .text(CombatMessages.SETTING_STAFF_ALERTS, CombatMessages.SETTING_STAFF_ALERTS_DESCRIPTION).build();

    private final Services services;
    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final SpawnArea spawn;
    private final RecentPairs pairs = new RecentPairs();
    private final KillLog killLog;
    private final CombatTimer timer;
    private final CombatListener listener;
    private final CombatCommands commands;
    private Task timerTask = Task.NONE;

    public CombatFeature(Services services, List<ConfigProblem> problems, CombatTags tags, StatsRecorder stats, TeamLookup teams,
                         FriendLookup friends, VanishStatus vanish, SpawnArea spawn, Cosmetics cosmetics) {
        this.services = services;
        this.tags = tags;
        this.spawn = spawn;
        this.settings = services.configs().register("features/combat.yml", CombatSettings::parse, problems);
        services.lang().register(CombatMessages.class);
        registerSettings(services.settings(), services.relations(), this.settings::get);
        var perms = services.permissions();
        perms.declare(CombatCommands.USE, "See whether you are in combat with /combat", true);
        perms.declare(CombatCommands.ADMIN, "Inspect, tag and untag players and read the kill log with /combat", false);
        perms.declare(CombatTagger.BYPASS, "Never be put in combat", PermissionDefault.FALSE);

        HitLog<ItemStack> hits = new HitLog<>();
        TagTicker ticker = new TagTicker();
        Participants participants = new Participants(vanish);
        this.killLog = new KillLog(services.database());
        TimerDisplay display = new TimerDisplay(services.settings(), services.messenger(), services.lang(), services.statusBars(), tags,
            services.scheduler());
        CombatTagger tagger = new CombatTagger(this.settings, tags, hits, ticker, services.scheduler(), services.messenger(),
            services.settings(), display, cosmetics::name);
        KillTracker kills = new KillTracker(this.settings, hits, this.pairs, this.killLog, stats, teams, friends, services.directory(),
            participants, services.scheduler(), services.plugin().getLogger());
        DeathMessages deathMessages = new DeathMessages(services.lang(), services.settings(), services.relations(), participants, cosmetics);
        StaffNotices staff = new StaffNotices(services.settings(), services.messenger(), services.lang(), services.directory());
        CombatLogs logs = new CombatLogs(this.settings, tags, tagger, deathMessages, services.audit(), services.directory(), participants,
            staff);
        this.listener = new CombatListener(this.settings, tags, tagger, kills, deathMessages, logs, spawn, services.directory(),
            participants, services.messenger(), cosmetics, services.settings(), services.scheduler(), staff);
        this.timer = new CombatTimer(this.settings, tags, ticker, hits, this.pairs, services.messenger(), services.settings(), display);
        this.commands = new CombatCommands(services, tags, tagger, this.killLog);
    }

    /**
     * Registers the combat settings in their groups, in the catalog's order. Settings whose behaviour the server can
     * turn off in {@code combat.yml} are only offered while it is on.
     */
    static void registerSettings(PlayerSettings prefs, Relations relations, Supplier<CombatSettings> config) {
        prefs.register(SettingCategories.COMBAT, TIMER_DISPLAY, SettingOptions.<AlertStyle>builder().order(1)
            .availableWhen(() -> config.get().actionBar()).build());
        prefs.register(SettingCategories.COMBAT, TAG_ALERT, SettingOptions.<AlertStyle>builder().order(2).build());
        prefs.register(SettingCategories.COMBAT, KILL_FEEDBACK, SettingOptions.<AlertStyle>builder().order(3).build());
        prefs.register(SettingCategories.COMBAT, DEATH_COORDINATES, SettingOptions.<Boolean>builder().order(4).build());
        prefs.register(SettingCategories.COMBAT, END_NOTICE, SettingOptions.<AlertStyle>builder().order(5).build());
        prefs.register(SettingCategories.COMBAT, DEATH_RECAP, SettingOptions.<Boolean>builder().order(8).build());
        prefs.register(SettingCategories.ANNOUNCEMENTS, DEATH_MESSAGES, SettingOptions.<DeathFilter>builder().order(1)
            .availableWhen(() -> config.get().deathMessages())
            .optionAvailableWhen(DeathFilter.FRIENDS_TEAM.id(), () -> relationsAvailable(relations)).build());
        prefs.register(SettingCategories.ANNOUNCEMENTS, STREAK_ANNOUNCEMENTS, SettingOptions.<Boolean>builder().order(6)
            .availableWhen(() -> announcesStreaks(config.get().streaks())).build());
        prefs.register(SettingCategories.ANNOUNCEMENTS, LOG_ANNOUNCEMENTS, SettingOptions.<Boolean>builder().order(7)
            .availableWhen(() -> config.get().announceLogout()).build());
        prefs.register(SettingCategories.STAFF, STAFF_ALERTS, SettingOptions.<StaffAlerts>builder().order(10).build());
        // Death locations leave the coordinates out in streamer mode.
        prefs.reads(SharedSettings.HIDE_COORDINATES);
    }

    /** Whether "friends and teammates" means anything here: the server has friends or teams. */
    static boolean relationsAvailable(Relations relations) {
        return relations.friendsAvailable() || relations.teams() != TeamLookup.NONE;
    }

    /** Whether the config announces any kill streak line. */
    static boolean announcesStreaks(CombatSettings.Streaks streaks) {
        return !streaks.announceAt().isEmpty() || streaks.endedFrom() > 0;
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public void enable() throws Exception {
        long since = System.currentTimeMillis() - CombatSettings.MAX_PAIR_COOLDOWN.toMillis();
        for (KillLog.CountedPair pair : this.killLog.countedSince(since).get()) {
            this.pairs.record(pair.killer(), pair.victim(), pair.at());
        }
        Bukkit.getPluginManager().registerEvents(this.listener, this.services.plugin());
        if (this.spawn != SpawnArea.NONE) {
            AsyncTeleportGuard.install(this.services.plugin(), this.listener, this.listener::refuseTeleportIntoSpawn);
        }
        this.timerTask = this.services.scheduler().asyncTimer(this.timer::tick, Duration.ofSeconds(1), Duration.ofSeconds(1));
        registerPlaceholders();
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("combat_tagged", "Whether you are in combat (true or false)",
            p -> Boolean.toString(this.tags.tagged(p.getUniqueId())));
        placeholders.register("combat_time", "Whole seconds of combat left (0 when not in combat)",
            p -> Long.toString(TagTicker.secondsLeft(this.tags.remaining(p.getUniqueId()).toMillis(), 0)));
    }

    @Override
    public void disable() {
        this.timerTask.cancel();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "anti-farm rules", () -> {
            AntiFarm.Rules rules = new AntiFarm.Rules(true, true, true, Duration.ofMinutes(10));
            long now = 1_000_000_000L;
            OptionalLong never = OptionalLong.empty();
            if (!AntiFarm.decide(rules, AntiFarm.Facts.FAIR, now).counted()) {
                return "a fair kill was not counted";
            }
            if (AntiFarm.decide(rules, new AntiFarm.Facts(true, false, false, never), now).reason() != AntiFarm.Reason.SAME_TEAM
                || AntiFarm.decide(rules, new AntiFarm.Facts(false, true, false, never), now).reason() != AntiFarm.Reason.FRIENDS
                || AntiFarm.decide(rules, new AntiFarm.Facts(false, false, true, never), now).reason() != AntiFarm.Reason.SAME_IP
                || AntiFarm.decide(rules, new AntiFarm.Facts(false, false, false, OptionalLong.of(now - 60_000)), now).reason()
                    != AntiFarm.Reason.REPEATED_PAIR) {
                return "a farmed kill was counted";
            }
            return null;
        });
        test.check(id(), "combat timer is running", () -> {
            long age = System.currentTimeMillis() - this.timer.lastTick();
            return age <= 5_000 ? null : "the combat timer last ran " + age + " ms ago";
        });
        test.check(id(), "only online players are in combat", () -> {
            long now = System.currentTimeMillis();
            int stale = 0;
            for (var entry : this.tags.snapshot().entrySet()) {
                if (entry.getValue().until() > now && Bukkit.getPlayer(entry.getKey()) == null) {
                    stale++;
                }
            }
            return stale == 0 ? null : stale + " offline player(s) are still tagged";
        });
        test.checkAsync(id(), "kill log is readable", () -> this.killLog.count().handle((count, error) ->
            error == null ? null : "reading the kill log failed: " + error));
    }
}
