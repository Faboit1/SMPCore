package net.siftvanilla.siftcore.feature.scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingOptions;
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
 *       of their placeholders is empty. Players hide it with {@code /sidebar} (the {@code scoreboard} setting) and pick
 *       a shorter layout ({@code sidebar-layout}: everything, a money view or fight stats).</li>
 *   <li>Tab list: a header and footer from lang, refreshed every few seconds, names shown as "rank name" with an AFK
 *       marker, and higher ranks listed first.</li>
 *   <li>Nametags: the rank label in front of names above heads, through scoreboard teams that are the same on every
 *       board. Vanished staff are in no team, so their names never reach other players.</li>
 * </ul>
 * It reads from the stats recorder (kills, deaths, streaks, playtime), teams (the team line), ranks (labels and
 * order), AFK status (the tab marker), combat tags (the combat line) and vanish (online counts and nametags). A player
 * who turned {@code show-my-rank} off (defined by the integrations feature) is shown like an ordinary member: no label
 * in the tab list, nametags or {@code {rank}}, the members' team and their tab list order. That choice applies only
 * while the setting is offered (LuckPerms connected), exactly like in chat and the placeholders.
 */
public final class ScoreboardFeature implements Feature, Listener {

    /** The display group of the settings dialog (the shared {@link SettingCategories#DISPLAY}). */
    public static final SettingCategory DISPLAY = SettingCategories.DISPLAY;

    /** The player's sidebar switch, shown in the settings dialog. */
    public static final Toggle TOGGLE = new Toggle("scoreboard", true, ScoreboardMessages.TOGGLE_LABEL,
        ScoreboardMessages.TOGGLE_DESCRIPTION, null);
    /** Which lines the player's sidebar shows (the layouts of features/scoreboard.yml). */
    public static final Choice<SidebarLayout> LAYOUT = Choice.ofEnum("sidebar-layout", SidebarLayout.class, SidebarLayout::id,
            SidebarLayout.FULL)
        .option(SidebarLayout.FULL, SidebarLayout.FULL.label())
        .option(SidebarLayout.COMPACT, SidebarLayout.COMPACT.label())
        .option(SidebarLayout.COMBAT, SidebarLayout.COMBAT.label())
        .text(ScoreboardMessages.LAYOUT_LABEL, ScoreboardMessages.LAYOUT_DESCRIPTION).build();
    /**
     * The id of Show my rank, which the integrations feature defines and registers (Privacy group). The scoreboard
     * looks it up in the settings registry by id instead of importing another feature's constant, and applies it only
     * while it is offered (see {@link Boards.RankPrivacy#of}).
     */
    static final String SHOW_MY_RANK_ID = "show-my-rank";
    /** The places of the two settings in the Display group (the catalog's order; the feedback channel is second). */
    static final int TOGGLE_ORDER = 1;
    static final int LAYOUT_ORDER = 3;

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
        services.permissions().declare(ScoreboardCommands.USE, "Show or hide your sidebar with /sidebar", true);
        services.permissions().declare(ScoreboardCommands.ADMIN, "Refresh, inspect and preview sidebars with /sidebar refresh, status and preview",
            false);
        Texts texts = new Texts(services.lang());
        Values values = new Values(services.placeholders(), stats, teams, combat);
        PlayerSettings playerSettings = services.settings();
        // Show my rank is defined by the integrations feature (it hides the label in chat and placeholders); the
        // scoreboard applies it to the tab list, nametags and the sidebar's {rank}, while the settings offer it.
        this.boards = new Boards(services.scheduler(), this.settings, playerSettings, TOGGLE, LAYOUT,
            Boards.RankPrivacy.of(playerSettings, SHOW_MY_RANK_ID),
            texts, values, ranks, afk, vanish, services.lang().style(), services.plugin().getLogger());
        registerSettings(playerSettings, () -> this.boards.current(), this::redraw);
        this.commands = new ScoreboardCommands(services, this.settings, TOGGLE, this.boards);
    }

    /**
     * Registers the sidebar switch and the layout in the Display group. Both apply at once ({@code redraw} runs on the
     * player's thread) and are only offered while SiftCore draws the sidebar: not while TAB or another plugin shows
     * it, nor with the sidebar turned off in features/scoreboard.yml. A layout is offered while it has lines.
     *
     * @param current the settings in force (the config with the parts other plugins show turned off)
     */
    static void registerSettings(PlayerSettings settings, Supplier<ScoreboardSettings> current, Consumer<Player> redraw) {
        settings.register(DISPLAY, TOGGLE, SettingOptions.<Boolean>builder().order(TOGGLE_ORDER)
            .onChange((player, before, now) -> redraw.accept(player))
            .availableWhen(() -> current.get().sidebarEnabled()).build());
        SettingOptions.Builder<SidebarLayout> layout = SettingOptions.<SidebarLayout>builder().order(LAYOUT_ORDER)
            .onChange((player, before, now) -> redraw.accept(player))
            .availableWhen(() -> current.get().sidebarEnabled() && current.get().offersChoice());
        for (SidebarLayout option : SidebarLayout.values()) {
            if (option != SidebarLayout.FULL) {
                layout.optionAvailableWhen(option.id(), () -> current.get().offers(option));
            }
        }
        settings.register(DISPLAY, LAYOUT, layout.build());
    }

    /** A change hook (player's thread): the sidebar follows the new switch or layout right away on the global thread. */
    private void redraw(Player player) {
        UUID id = player.getUniqueId();
        this.services.scheduler().global(() -> this.boards.refreshPlayer(id));
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
