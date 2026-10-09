package net.siftvanilla.siftcore.feature.scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Every online player's scoreboard: the sidebar, the nametag teams and the tab list.
 * <p>
 * Threading (Folia/Canvas, see docs/research/runtime.md section 7): scoreboards, objectives, scores and teams are
 * created and changed on the global region thread only, and all state of this class is confined to that thread.
 * {@code Player#setScoreboard}, the tab list name and the list order are set on the player's own thread. A board is
 * filled before it is handed to its player and is not touched again until the player's thread reports that the
 * player uses it, so the server never reads a board on the player's thread while the global thread changes it.
 * <p>
 * One timer refreshes everything; only what changed is sent (lines by their text, teams by their members and
 * prefixes, the header and footer by their text).
 */
final class Boards {

    /** The sidebar objective's name (at most 16 characters for old clients). */
    static final String OBJECTIVE = "sift_sidebar";
    /** How often the lang text is compared with what was compiled (changes only come with /sift reload). */
    private static final long TEXT_CHECK_MILLIS = 5_000;

    /** What other threads may read: counts from the end of the last refresh. */
    record Status(int players, int sidebars, int hidden, int teams, int pending, long refreshMicros, long refreshes) {
        static final Status EMPTY = new Status(0, 0, 0, 0, 0, 0, 0);
    }

    /** A tab list name as it was last set: the inputs, so it is only rebuilt when one of them changes. */
    private record TabName(String label, boolean away, int order, long epoch) {

        /** Stands for a name that must be set again (it equals no real one), after /sidebar refresh or a reload. */
        static final TabName RESEND = new TabName("", false, -1, -1);
    }

    /** One player's own scoreboard. */
    private static final class Board {
        final Scoreboard scoreboard;
        Objective sidebar;
        SidebarLines lines;
        long linesEpoch = -1;
        Component title;
        List<?>[] lastValues;
        Map<String, TeamDiff.View> teams = new HashMap<>();
        long teamsVersion = -1;

        Board(Scoreboard scoreboard) {
            this.scoreboard = scoreboard;
        }
    }

    /** An online player as the scoreboard knows them. */
    private static final class Viewer implements Values.Subject {
        final Player player;
        final UUID id;
        final String name;
        final long joinedAt;
        RankOrder.PlayerRank rank;
        boolean vanished;
        Board board;
        /** The player uses {@link #board} (their thread confirmed it). */
        boolean onBoard;
        /** The scoreboard a setScoreboard on the player's thread is about to apply, or null. */
        Scoreboard requested;
        int failures;
        /** How often this player's board had to be rebuilt after an error; after three the scoreboard gives up. */
        int rebuilds;
        boolean broken;
        Component header;
        Component footer;
        /** Send the header and footer at the next refresh even when unchanged. */
        boolean resendHeader;
        TabName tabName;

        Viewer(Player player, RankOrder.PlayerRank rank, TabName tabName, long joinedAt) {
            this.player = player;
            this.id = player.getUniqueId();
            this.name = player.getName();
            this.rank = rank;
            this.tabName = tabName;
            this.joinedAt = joinedAt;
        }

        @Override
        public Player player() {
            return this.player;
        }

        @Override
        public UUID id() {
            return this.id;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public String rankLabel() {
            return this.rank.label();
        }
    }

    private final Scheduler scheduler;
    private final Setting<ScoreboardSettings> settings;
    private final PlayerSettings playerSettings;
    private final Toggle toggle;
    private final Texts texts;
    private final Values values;
    private final Ranks ranks;
    private final AfkStatus afk;
    private final VanishStatus vanish;
    private final TextStyle style;
    private final Logger logger;

    // Global region thread only.
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final NametagModel nametags = new NametagModel();
    private long lastTab;
    private long lastRanks;
    private long lastTextCheck;
    private long refreshes;
    private boolean warnedFailure;

    // Readable from any thread.
    private final Set<UUID> sidebars = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>();
    private volatile Status status = Status.EMPTY;

    Boards(Scheduler scheduler, Setting<ScoreboardSettings> settings, PlayerSettings playerSettings, Toggle toggle, Texts texts,
           Values values, Ranks ranks, AfkStatus afk, VanishStatus vanish, TextStyle style, Logger logger) {
        this.scheduler = scheduler;
        this.settings = settings;
        this.playerSettings = playerSettings;
        this.toggle = toggle;
        this.texts = texts;
        this.values = values;
        this.ranks = ranks;
        this.afk = afk;
        this.vanish = vanish;
        this.style = style;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ queries (any thread)

    /** The settings in force: the config, with every part another plugin shows instead turned off. Any thread. */
    ScoreboardSettings current() {
        return this.settings.get().effective(yielded());
    }

    /** Which parts other plugins show instead right now. Any thread. */
    ScoreboardSettings.Yielded yielded() {
        return this.settings.get().yielded(name -> Bukkit.getPluginManager().isPluginEnabled(name));
    }

    Status status() {
        return this.status;
    }

    /** Players who have the sidebar now. */
    Set<UUID> sidebarOwners() {
        return Set.copyOf(this.sidebars);
    }

    /** When a player joined (milliseconds), or null when the scoreboard does not know them. */
    Long joinedAt(UUID player) {
        return this.joined.get(player);
    }

    /** Whether the player wants the sidebar (their switch; true unless they turned it off). */
    boolean wantsSidebar(UUID player) {
        return this.playerSettings.enabled(player, this.toggle);
    }

    // ------------------------------------------------------------------ ranks and tab names (player thread)

    /** The player's rank. Reads the player's permissions: call it on the player's thread. */
    private RankOrder.PlayerRank rankOf(Player player) {
        RankOrder order = this.settings.get().ranks();
        String label;
        String group;
        try {
            label = this.ranks.label(player.getUniqueId());
            group = this.ranks.group(player.getUniqueId());
        } catch (RuntimeException e) {
            label = "";
            group = "default";
        }
        return order.resolve(label, group, name -> {
            String node = RankOrder.groupPermission(name);
            return player.isPermissionSet(node) && player.hasPermission(node);
        });
    }

    /**
     * Sets the tab list name and order of a player who is joining, on their thread, before the server lists them,
     * so other players never see the plain name first. Returns what was set (null when tab names are off).
     */
    private TabName applyJoinTabName(Player player, RankOrder.PlayerRank rank) {
        ScoreboardSettings settings = current();
        if (!settings.tabNames()) {
            return null;
        }
        boolean away = settings.afkMarker() && this.afk.afk(player.getUniqueId());
        player.playerListName(this.texts.tabName(rank.label(), player.getName(), away));
        player.setPlayerListOrder(settings.ranks().listOrder(rank));
        return new TabName(rank.label(), away, settings.ranks().listOrder(rank), -1);
    }

    // ------------------------------------------------------------------ join and quit

    /** A player joined (on their thread): set their tab list name now and give them a board on the global thread. */
    void join(Player player) {
        long now = System.currentTimeMillis();
        this.joined.put(player.getUniqueId(), now);
        RankOrder.PlayerRank rank = rankOf(player);
        TabName tabName;
        try {
            tabName = applyJoinTabName(player, rank);
        } catch (RuntimeException e) {
            this.logger.log(Level.FINE, "Could not set the tab list name of " + player.getName() + " while joining", e);
            tabName = null;
        }
        TabName applied = tabName;
        this.scheduler.global(() -> attach(player, rank, applied, now));
    }

    /** A player left (on their thread): drop their board and take them out of everyone's teams. */
    void quit(Player player) {
        this.joined.remove(player.getUniqueId());
        this.scheduler.global(() -> detach(player));
    }

    private void attach(Player player, RankOrder.PlayerRank rank, TabName tabName, long joinedAt) {
        if (!player.isOnline()) {
            return;
        }
        Viewer previous = this.viewers.get(player.getUniqueId());
        if (previous != null) {
            drop(previous);
        }
        refreshTexts();
        Viewer viewer = new Viewer(player, rank, tabName == null ? null : new TabName(tabName.label(), tabName.away(), tabName.order(),
            this.texts.epoch()), joinedAt);
        this.viewers.put(viewer.id, viewer);
        ScoreboardSettings settings = current();
        int online = visibleOnline();
        viewer.vanished = this.vanish.vanished(viewer.id);
        updateMembership(viewer, settings);
        ensureBoard(viewer, settings, online);
        syncAllTeams(settings);
        if (settings.tabEnabled()) {
            sendHeaderFooter(viewer, online);
        }
    }

    private void detach(Player player) {
        Viewer viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null || viewer.player != player) {
            return;
        }
        drop(viewer);
        syncAllTeams(current());
    }

    private void drop(Viewer viewer) {
        this.viewers.remove(viewer.id, viewer);
        this.sidebars.remove(viewer.id);
        this.nametags.remove(viewer.name);
        viewer.board = null;
        viewer.onBoard = false;
        viewer.requested = null;
    }

    // ------------------------------------------------------------------ the refresh (global thread)

    /** Refreshes every board, the nametag teams and the tab list. Runs on the global region thread. */
    void refresh() {
        long start = System.nanoTime();
        ScoreboardSettings settings = current();
        long now = System.currentTimeMillis();
        if (now - this.lastTextCheck >= TEXT_CHECK_MILLIS) {
            this.lastTextCheck = now;
            refreshTexts();
        }
        boolean ranksDue = now - this.lastRanks >= settings.rankRefresh().toMillis();
        if (ranksDue) {
            this.lastRanks = now;
        }
        int online = visibleOnline();
        List<Viewer> all = new ArrayList<>(this.viewers.values());
        for (Viewer viewer : all) {
            if (!viewer.player.isOnline()) {
                drop(viewer);
                continue;
            }
            viewer.vanished = this.vanish.vanished(viewer.id);
            updateMembership(viewer, settings);
        }
        if (ranksDue) {
            readRanks();
        }
        if (!settings.nametagsEnabled()) {
            this.nametags.clear();
        }
        for (Viewer viewer : this.viewers.values()) {
            try {
                ensureBoard(viewer, settings, online);
                if (viewer.board != null && viewer.onBoard) {
                    syncTeams(viewer.board, settings);
                    syncSidebar(viewer, viewer.board, settings, online);
                }
                updateTabName(viewer, settings);
            } catch (RuntimeException e) {
                failed(viewer, e);
            }
        }
        boolean tabDue = now - this.lastTab >= settings.tabRefresh().toMillis();
        if (tabDue) {
            this.lastTab = now;
            for (Viewer viewer : this.viewers.values()) {
                if (settings.tabEnabled()) {
                    sendHeaderFooter(viewer, online);
                } else if (viewer.header != null) {
                    viewer.header = null;
                    viewer.footer = null;
                    viewer.player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                }
            }
        }
        this.refreshes++;
        publish((System.nanoTime() - start) / 1_000);
    }

    /**
     * Compares the lang text with what was compiled. When it changed, every board compares its nametag prefixes again
     * and the tab list is due; sidebars notice the new epoch themselves.
     */
    private boolean refreshTexts() {
        if (!this.texts.refresh()) {
            return false;
        }
        this.nametags.touch();
        this.lastTab = 0;
        return true;
    }

    /** Applies one player's sidebar switch right away (after /sidebar), instead of at the next refresh. */
    void refreshPlayer(UUID player) {
        Viewer viewer = this.viewers.get(player);
        if (viewer == null || viewer.board == null || !viewer.onBoard) {
            return;
        }
        try {
            syncSidebar(viewer, viewer.board, current(), visibleOnline());
        } catch (RuntimeException e) {
            failed(viewer, e);
        }
    }

    /** Updates one player's tab list name right away (after they went AFK or came back). */
    void refreshTabName(UUID player) {
        Viewer viewer = this.viewers.get(player);
        if (viewer == null) {
            return;
        }
        try {
            updateTabName(viewer, current());
        } catch (RuntimeException e) {
            failed(viewer, e);
        }
    }

    /** Forgets what every board shows and reads every rank again, so the next refresh sends everything. */
    int forceRefresh() {
        this.lastRanks = 0;
        this.lastTab = 0;
        this.lastTextCheck = 0;
        this.nametags.touch();
        for (Viewer viewer : this.viewers.values()) {
            viewer.resendHeader = true;
            if (viewer.tabName != null) {
                viewer.tabName = TabName.RESEND;
            }
            if (viewer.board != null) {
                viewer.board.linesEpoch = -1;
            }
        }
        refresh();
        return this.viewers.size();
    }

    private void failed(Viewer viewer, RuntimeException e) {
        viewer.rebuilds++;
        if (!this.warnedFailure) {
            this.warnedFailure = true;
            this.logger.log(Level.WARNING, "Could not refresh the scoreboard of " + viewer.name + "; their board is rebuilt", e);
        } else {
            this.logger.log(Level.FINE, "Could not refresh the scoreboard of " + viewer.name, e);
        }
        // Rebuild from scratch: a fresh board is filled and handed over again.
        this.sidebars.remove(viewer.id);
        viewer.board = null;
        viewer.onBoard = false;
        viewer.requested = null;
        if (viewer.rebuilds >= 3 && !viewer.broken) {
            viewer.broken = true;
            this.logger.warning("The scoreboard of " + viewer.name + " failed three times; they keep the default scoreboard until they rejoin");
            request(viewer, Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private int visibleOnline() {
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!this.vanish.vanished(online.getUniqueId())) {
                count++;
            }
        }
        return count;
    }

    private void publish(long micros) {
        int hidden = 0;
        int pending = 0;
        for (Viewer viewer : this.viewers.values()) {
            if (!wantsSidebar(viewer.id)) {
                hidden++;
            }
            if (viewer.requested != null) {
                pending++;
            }
        }
        this.status = new Status(this.viewers.size(), this.sidebars.size(), hidden, this.nametags.teams().size(), pending, micros,
            this.refreshes);
    }

    // ------------------------------------------------------------------ boards and handing them over

    /** Gives the viewer a board when they need one (sidebar or nametags on), or puts them back on the main one. */
    private void ensureBoard(Viewer viewer, ScoreboardSettings settings, int online) {
        if (viewer.requested != null || viewer.broken) {
            return;
        }
        if (settings.boards() && viewer.board == null) {
            Board board = new Board(Bukkit.getScoreboardManager().getNewScoreboard());
            viewer.board = board;
            // Filled before the player gets it, so they receive everything at once.
            syncTeams(board, settings);
            syncSidebar(viewer, board, settings, online);
            request(viewer, board.scoreboard);
        } else if (!settings.boards() && viewer.board != null) {
            viewer.board = null;
            this.sidebars.remove(viewer.id);
            request(viewer, Bukkit.getScoreboardManager().getMainScoreboard());
        } else if (viewer.board != null && !viewer.onBoard) {
            // A hand-over failed earlier: try again.
            request(viewer, viewer.board.scoreboard);
        }
    }

    /** Sets the player's scoreboard on their thread, then reports back on the global thread. */
    private void request(Viewer viewer, Scoreboard target) {
        viewer.requested = target;
        viewer.onBoard = false;
        Player player = viewer.player;
        Task task = this.scheduler.entity(player, () -> {
            boolean ok;
            try {
                player.setScoreboard(target);
                ok = true;
            } catch (RuntimeException e) {
                this.logger.log(Level.FINE, "Could not set the scoreboard of " + player.getName(), e);
                ok = false;
            }
            boolean applied = ok;
            this.scheduler.global(() -> handedOver(viewer, target, applied));
        }, null);
        if (task == Task.NONE) {
            viewer.requested = null;
        }
    }

    private void handedOver(Viewer viewer, Scoreboard target, boolean ok) {
        if (this.viewers.get(viewer.id) != viewer || viewer.requested != target) {
            return;
        }
        viewer.requested = null;
        if (!ok) {
            viewer.failures++;
            if (viewer.failures == 3) {
                this.logger.warning("The scoreboard of " + viewer.name + " could not be set three times; still trying");
            }
            return;
        }
        viewer.failures = 0;
        Board board = viewer.board;
        viewer.onBoard = board != null && board.scoreboard == target;
        if (viewer.onBoard) {
            ScoreboardSettings settings = current();
            try {
                syncTeams(board, settings);
                syncSidebar(viewer, board, settings, visibleOnline());
            } catch (RuntimeException e) {
                failed(viewer, e);
            }
        }
    }

    // ------------------------------------------------------------------ ranks

    /**
     * Reads every player's rank again on their own thread (it checks their permissions) and applies the ones that
     * changed back on the global thread.
     */
    private void readRanks() {
        for (Viewer viewer : this.viewers.values()) {
            Player player = viewer.player;
            this.scheduler.entity(player, () -> {
                RankOrder.PlayerRank rank = rankOf(player);
                this.scheduler.global(() -> rankRead(viewer, rank));
            }, null);
        }
    }

    private void rankRead(Viewer viewer, RankOrder.PlayerRank rank) {
        if (this.viewers.get(viewer.id) != viewer || rank.equals(viewer.rank)) {
            return;
        }
        viewer.rank = rank;
        ScoreboardSettings settings = current();
        updateMembership(viewer, settings);
        syncAllTeams(settings);
        try {
            updateTabName(viewer, settings);
        } catch (RuntimeException e) {
            failed(viewer, e);
        }
    }

    // ------------------------------------------------------------------ nametag teams

    private void updateMembership(Viewer viewer, ScoreboardSettings settings) {
        if (!settings.nametagsEnabled() || viewer.vanished) {
            this.nametags.remove(viewer.name);
            return;
        }
        this.nametags.set(viewer.name, new NametagModel.Key(Math.min(viewer.rank.order(), 99), viewer.rank.label()));
    }

    private void syncAllTeams(ScoreboardSettings settings) {
        for (Viewer viewer : this.viewers.values()) {
            if (viewer.board != null && viewer.onBoard) {
                try {
                    syncTeams(viewer.board, settings);
                } catch (RuntimeException e) {
                    failed(viewer, e);
                }
            }
        }
    }

    /** Brings a board's nametag teams to the model: only the differences are applied. */
    private void syncTeams(Board board, ScoreboardSettings settings) {
        long version = this.nametags.version();
        if (board.teamsVersion == version) {
            return;
        }
        Map<String, TeamDiff.View> wanted = new HashMap<>();
        if (settings.nametagsEnabled()) {
            for (Map.Entry<String, NametagModel.Team> entry : this.nametags.teams().entrySet()) {
                wanted.put(entry.getKey(), new TeamDiff.View(this.texts.prefix(entry.getValue().key().label()), entry.getValue().members()));
            }
        }
        for (TeamDiff.Op op : TeamDiff.between(board.teams, wanted)) {
            apply(board.scoreboard, op);
        }
        board.teams = wanted;
        board.teamsVersion = version;
    }

    private void apply(Scoreboard scoreboard, TeamDiff.Op op) {
        switch (op) {
            case TeamDiff.Remove remove -> {
                Team team = scoreboard.getTeam(remove.team());
                if (team != null) {
                    team.unregister();
                }
            }
            case TeamDiff.Leave leave -> {
                Team team = scoreboard.getTeam(leave.team());
                if (team != null) {
                    for (String player : leave.players()) {
                        team.removeEntry(player);
                    }
                }
            }
            case TeamDiff.Create create -> {
                Team team = scoreboard.getTeam(create.team());
                if (team == null) {
                    team = scoreboard.registerNewTeam(create.team());
                }
                team.prefix(create.prefix());
                team.color(NamedTextColor.nearestTo(this.style.palette().primary()));
                // Players of the same rank share a team; they must not see each other through invisibility.
                team.setCanSeeFriendlyInvisibles(false);
            }
            case TeamDiff.Prefix prefix -> {
                Team team = scoreboard.getTeam(prefix.team());
                if (team != null) {
                    team.prefix(prefix.prefix());
                }
            }
            case TeamDiff.Join join -> {
                Team team = scoreboard.getTeam(join.team());
                if (team != null) {
                    team.addEntries(join.players());
                }
            }
        }
    }

    // ------------------------------------------------------------------ the sidebar

    /** Shows, hides, rebuilds or updates a board's sidebar. Only lines whose text changed are sent. */
    private void syncSidebar(Viewer viewer, Board board, ScoreboardSettings settings, int online) {
        boolean wanted = settings.sidebarEnabled() && wantsSidebar(viewer.id);
        if (!wanted) {
            if (board.sidebar != null) {
                board.sidebar.unregister();
                board.sidebar = null;
                board.lines = null;
            }
            this.sidebars.remove(viewer.id);
            return;
        }
        List<String> names = settings.lines();
        long epoch = this.texts.epoch();
        boolean rebuild = board.sidebar == null || board.lines == null || board.lines.size() != names.size() || board.linesEpoch != epoch;
        if (rebuild) {
            if (board.sidebar != null) {
                board.sidebar.unregister();
            }
            Objective objective = board.scoreboard.registerNewObjective(OBJECTIVE, Criteria.DUMMY, this.texts.title());
            objective.numberFormat(NumberFormat.blank());
            board.sidebar = objective;
            board.title = this.texts.title();
            board.lines = new SidebarLines(names.size());
            board.lastValues = new List<?>[names.size()];
            board.linesEpoch = epoch;
            applyLines(board, render(viewer, board, names, online));
            // Displaying it last sends the objective and every line together.
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            if (!Objects.equals(board.title, this.texts.title())) {
                board.sidebar.displayName(this.texts.title());
                board.title = this.texts.title();
            }
            applyLines(board, render(viewer, board, names, online));
        }
        this.sidebars.add(viewer.id);
    }

    /** The text of every line for this player; a line whose values did not change keeps its last text. */
    private List<Component> render(Values.Subject subject, Board board, List<String> names, int online) {
        List<Component> lines = new ArrayList<>(names.size());
        for (int slot = 0; slot < names.size(); slot++) {
            LineTemplate template = this.texts.line(names.get(slot));
            if (template == null) {
                lines.add(Component.empty());
                continue;
            }
            List<String> current = this.values.resolve(subject, template.tokens(), online);
            if (board != null && board.lines != null && current.equals(board.lastValues[slot]) && board.lastValues[slot] != null) {
                lines.add(board.lines.shown(slot));
                continue;
            }
            if (board != null) {
                board.lastValues[slot] = current;
            }
            lines.add(LineTemplate.hidden(current) ? null : template.render(current));
        }
        return lines;
    }

    private static void applyLines(Board board, List<Component> text) {
        for (SidebarLines.Change change : board.lines.update(text)) {
            Score score = board.sidebar.getScore(SidebarLines.entry(change.slot()));
            if (change.text() == null) {
                score.resetScore();
                continue;
            }
            score.customName(change.text());
            int value = board.lines.score(change.slot());
            if (score.getScore() != value) {
                score.setScore(value);
            }
        }
    }

    /** What a player's sidebar would show right now, top to bottom (for /sidebar preview). */
    List<Component> preview(UUID player) {
        Viewer viewer = this.viewers.get(player);
        if (viewer == null) {
            return null;
        }
        refreshTexts();
        List<Component> lines = render(viewer, null, this.settings.get().lines(), visibleOnline());
        List<Component> shown = new ArrayList<>(lines.size());
        for (Component line : lines) {
            if (line != null) {
                shown.add(line);
            }
        }
        return shown;
    }

    // ------------------------------------------------------------------ the tab list

    private void sendHeaderFooter(Viewer viewer, int online) {
        Component header = this.texts.header().render(this.values.resolve(viewer, this.texts.header().tokens(), online));
        Component footer = this.texts.footer().render(this.values.resolve(viewer, this.texts.footer().tokens(), online));
        if (!viewer.resendHeader && header.equals(viewer.header) && footer.equals(viewer.footer)) {
            return;
        }
        viewer.resendHeader = false;
        viewer.header = header;
        viewer.footer = footer;
        viewer.player.sendPlayerListHeaderAndFooter(header, footer);
    }

    /** Sets the tab list name and order on the player's thread when the rank or AFK state changed. */
    private void updateTabName(Viewer viewer, ScoreboardSettings settings) {
        if (!settings.tabNames()) {
            if (viewer.tabName != null) {
                viewer.tabName = null;
                Player player = viewer.player;
                this.scheduler.entity(player, () -> {
                    player.playerListName(null);
                    player.setPlayerListOrder(0);
                }, null);
            }
            return;
        }
        boolean away = settings.afkMarker() && this.afk.afk(viewer.id);
        int order = settings.ranks().listOrder(viewer.rank);
        TabName wanted = new TabName(viewer.rank.label(), away, order, this.texts.epoch());
        if (wanted.equals(viewer.tabName)) {
            return;
        }
        viewer.tabName = wanted;
        Component name = this.texts.tabName(viewer.rank.label(), viewer.name, away);
        Player player = viewer.player;
        this.scheduler.entity(player, () -> {
            player.playerListName(name);
            player.setPlayerListOrder(order);
        }, null);
    }

    // ------------------------------------------------------------------ self-test support (global thread)

    /** Checks that every board in use shows exactly the model's teams; null when they all do. */
    String checkTeams() {
        Map<String, Set<String>> wanted = new HashMap<>();
        if (current().nametagsEnabled()) {
            this.nametags.teams().forEach((name, team) -> wanted.put(name, team.members()));
        }
        for (Viewer viewer : this.viewers.values()) {
            Board board = viewer.board;
            if (board == null || !viewer.onBoard || board.teamsVersion != this.nametags.version()) {
                continue;
            }
            Map<String, Set<String>> shown = new HashMap<>();
            for (Team team : board.scoreboard.getTeams()) {
                if (team.getName().startsWith(NametagModel.PREFIX)) {
                    shown.put(team.getName(), Set.copyOf(team.getEntries()));
                }
            }
            if (!shown.equals(wanted)) {
                return viewer.name + "'s board shows " + shown + " instead of " + wanted;
            }
        }
        return null;
    }

    /** Checks that every placeholder in the scoreboard's text is provided; null when they all are. */
    String checkPlaceholders() {
        refreshTexts();
        List<String> unknown = new ArrayList<>();
        this.texts.placeholderUse().forEach((where, tokens) -> {
            for (String token : tokens) {
                if (!this.values.known(token)) {
                    unknown.add("{" + token + "} in " + where);
                }
            }
        });
        unknown.sort(null);
        return unknown.isEmpty() ? null : "nothing provides " + String.join(", ", unknown);
    }

    /** Runs a check on the global region thread. */
    CompletableFuture<String> onGlobal(java.util.function.Supplier<String> check) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Task task = this.scheduler.global(() -> {
            try {
                result.complete(check.get());
            } catch (RuntimeException e) {
                result.complete("threw " + e);
            }
        });
        if (task == Task.NONE) {
            result.complete(null);
        }
        return result;
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Puts every player back on the main scoreboard and forgets all boards. Runs in onDisable on the shutdown thread,
     * which may touch players and scoreboards directly (the region scheduler has stopped).
     */
    void shutdown() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Viewer viewer : new ArrayList<>(this.viewers.values())) {
            try {
                if (viewer.player.isOnline() && viewer.board != null) {
                    viewer.player.setScoreboard(main);
                }
            } catch (RuntimeException e) {
                this.logger.log(Level.FINE, "Could not reset the scoreboard of " + viewer.name + " at shutdown", e);
            }
        }
        this.viewers.clear();
        this.sidebars.clear();
        this.joined.clear();
        this.nametags.clear();
        this.status = Status.EMPTY;
    }
}
