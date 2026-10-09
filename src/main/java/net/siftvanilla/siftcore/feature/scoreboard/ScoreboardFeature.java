package net.siftvanilla.siftcore.feature.scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.siftvanilla.siftcore.api.event.AfkStatusChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The sidebar, the tab list and nametags.
 * <ul>
 *   <li>Sidebar: a per-player board with one objective in the sidebar slot, titled from lang, with blank number
 *       format and one line per configured lang entry. Lines show any placeholder ({@code {balance}}, a feature's
 *       registry entry, or the scoreboard's own such as {@code {kills}} and {@code {team}}) and disappear while one
 *       of their placeholders is empty. Players hide it with {@code /sidebar} (the {@code scoreboard} setting).</li>
 *   <li>Tab list: a header and footer from lang, refreshed every few seconds, names shown as "rank name" with an AFK
 *       marker, and higher ranks listed first.</li>
 *   <li>Nametags: the rank label in front of names above heads, through scoreboard teams that are the same on every
 *       board. Vanished staff are in no team, so their names never reach other players.</li>
 * </ul>
 * It reads from the stats recorder (kills, deaths, streaks, playtime), teams (the team line), ranks (labels and
 * order), AFK status (the tab marker), combat tags (the combat line) and vanish (online counts and nametags).
 */
public final class ScoreboardFeature implements Feature, Listener {

    /** The display group of the settings dialog (what is shown on the player's screen). */
    public static final SettingCategory DISPLAY = new SettingCategory("display", 30, ScoreboardMessages.SETTINGS_CATEGORY,
        ScoreboardMessages.SETTINGS_CATEGORY_DESCRIPTION);

    /** The player's sidebar switch, shown in the settings dialog. */
    public static final Toggle TOGGLE = new Toggle("scoreboard", true, ScoreboardMessages.TOGGLE_LABEL,
        ScoreboardMessages.TOGGLE_DESCRIPTION, null);

    /** How long after joining a player may still be waiting for their board (self-test grace). */
    private static final long JOIN_GRACE_MILLIS = 5_000;

    private final Services services;
    private final Setting<ScoreboardSettings> settings;
    private final Boards boards;
    private final ScoreboardCommands commands;
    private final Object timerLock = new Object();
    private Task timer = Task.NONE;
    private Duration scheduledRefresh;

    public ScoreboardFeature(Services services, List<ConfigProblem> problems, StatsRecorder stats, TeamLookup teams, AfkStatus afk,
                             Ranks ranks, CombatTags combat, VanishStatus vanish) {
        this.services = services;
        this.settings = services.configs().register("features/scoreboard.yml", ScoreboardSettings::parse, problems);
        services.lang().register(ScoreboardMessages.class);
        services.settings().register(DISPLAY, TOGGLE);
        services.permissions().declare(ScoreboardCommands.USE, "Show or hide your sidebar with /sidebar", true);
        services.permissions().declare(ScoreboardCommands.ADMIN, "Refresh, inspect and preview sidebars with /sidebar refresh, status and preview",
            false);
        Texts texts = new Texts(services.lang());
        Values values = new Values(services.placeholders(), stats, teams, combat);
        this.boards = new Boards(services.scheduler(), this.settings, services.settings(), TOGGLE, texts, values, ranks, afk, vanish,
            services.lang().style(), services.plugin().getLogger());
        this.commands = new ScoreboardCommands(services, this.settings, TOGGLE, this.boards);
    }

    @Override
    public String id() {
        return "scoreboard";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        startTimer();
        this.settings.onReload(settings -> {
            boolean restart;
            synchronized (this.timerLock) {
                restart = !settings.sidebarRefresh().equals(this.scheduledRefresh);
            }
            if (restart) {
                startTimer();
            }
            // Ranks, lines and the tab list are rebuilt at the next refresh with the new settings.
            // Two ticks later, so a reload run from a player's thread has also finished loading the lang files.
            this.services.scheduler().globalLater(this.boards::forceRefresh, 2);
        });
        // Players already online (never at startup, but harmless) get their boards like a join.
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.services.scheduler().entity(online, () -> this.boards.join(online), null);
        }
    }

    private void startTimer() {
        synchronized (this.timerLock) {
            this.timer.cancel();
            Duration period = this.settings.get().sidebarRefresh();
            long ticks = Math.max(1, period.toMillis() / 50);
            this.scheduledRefresh = period;
            this.timer = this.services.scheduler().globalTimer(this.boards::refresh, ticks, ticks);
        }
    }

    @Override
    public void disable() {
        synchronized (this.timerLock) {
            this.timer.cancel();
            this.timer = Task.NONE;
        }
        this.boards.shutdown();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    // ------------------------------------------------------------------ joins and quits

    /** After vanish has hidden the player (LOWEST), so tab list updates go only to those who may see them. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.boards.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.boards.quit(event.getPlayer());
    }

    /** The AFK mark in the tab list follows at once instead of at the next refresh. Any thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAfkChange(AfkStatusChangeEvent event) {
        UUID player = event.player().getUniqueId();
        this.services.scheduler().global(() -> this.boards.refreshTabName(player));
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "every player with the sidebar on has one", () -> {
            if (!this.boards.current().sidebarEnabled()) {
                return this.boards.sidebarOwners().isEmpty() ? null : "sidebars are shown while the sidebar is turned off";
            }
            long now = System.currentTimeMillis();
            Set<UUID> expected = new TreeSet<>();
            Set<UUID> settled = new TreeSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                Long joined = this.boards.joinedAt(online.getUniqueId());
                if (joined == null || now - joined < JOIN_GRACE_MILLIS) {
                    continue;
                }
                settled.add(online.getUniqueId());
                if (this.boards.wantsSidebar(online.getUniqueId())) {
                    expected.add(online.getUniqueId());
                }
            }
            Set<UUID> actual = new TreeSet<>(this.boards.sidebarOwners());
            actual.retainAll(settled);
            if (actual.equals(expected)) {
                return null;
            }
            List<String> missing = new ArrayList<>();
            List<String> extra = new ArrayList<>();
            for (UUID id : expected) {
                if (!actual.contains(id)) {
                    missing.add(name(id));
                }
            }
            for (UUID id : actual) {
                if (!expected.contains(id)) {
                    extra.add(name(id));
                }
            }
            return expected.size() + " players want a sidebar, " + actual.size() + " have one"
                + (missing.isEmpty() ? "" : "; missing for " + String.join(", ", missing))
                + (extra.isEmpty() ? "" : "; shown although hidden for " + String.join(", ", extra));
        });
        test.checkAsync(id(), "nametag teams are the same on every board", () -> this.boards.onGlobal(this.boards::checkTeams));
        test.checkAsync(id(), "every placeholder in the sidebar and tab list is provided",
            () -> this.boards.onGlobal(this.boards::checkPlaceholders));
        test.check(id(), "the sidebar refresh keeps up", () -> {
            Boards.Status status = this.boards.status();
            long budget = this.settings.get().sidebarRefresh().toMillis() * 1000 / 4;
            return status.refreshMicros() <= budget ? null
                : "the last refresh took " + ScoreboardCommands.milliseconds(status.refreshMicros()) + " for " + status.players() + " players";
        });
    }

    private String name(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player == null ? id.toString() : player.getName();
    }
}
