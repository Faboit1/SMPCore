package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the sidebar, the tab list and nametags ({@code lang/scoreboard.yml}). */
public final class ScoreboardMessages {

    private ScoreboardMessages() {
    }

    // ------------------------------------------------------------------ sidebar

    public static final MessageKey SIDEBAR_TITLE = MessageKey.ui("scoreboard.sidebar.title");

    public static final MessageKey LINE_BALANCE = MessageKey.ui("scoreboard.sidebar.lines.balance");
    public static final MessageKey LINE_SHARDS = MessageKey.ui("scoreboard.sidebar.lines.shards");
    public static final MessageKey LINE_KILLS = MessageKey.ui("scoreboard.sidebar.lines.kills");
    public static final MessageKey LINE_DEATHS = MessageKey.ui("scoreboard.sidebar.lines.deaths");
    public static final MessageKey LINE_KDR = MessageKey.ui("scoreboard.sidebar.lines.kdr");
    public static final MessageKey LINE_STREAK = MessageKey.ui("scoreboard.sidebar.lines.streak");
    public static final MessageKey LINE_PLAYTIME = MessageKey.ui("scoreboard.sidebar.lines.playtime");
    public static final MessageKey LINE_TEAM = MessageKey.ui("scoreboard.sidebar.lines.team");
    public static final MessageKey LINE_RANK = MessageKey.ui("scoreboard.sidebar.lines.rank");
    public static final MessageKey LINE_KEYALL = MessageKey.ui("scoreboard.sidebar.lines.keyall");
    public static final MessageKey LINE_BOUNTY = MessageKey.ui("scoreboard.sidebar.lines.bounty");
    public static final MessageKey LINE_COMBAT = MessageKey.ui("scoreboard.sidebar.lines.combat");
    public static final MessageKey LINE_ONLINE = MessageKey.ui("scoreboard.sidebar.lines.online");
    public static final MessageKey LINE_PING = MessageKey.ui("scoreboard.sidebar.lines.ping");
    public static final MessageKey LINE_WEBSITE = MessageKey.ui("scoreboard.sidebar.lines.website");

    /** Every sidebar line by the name features/scoreboard.yml uses for it, in the order the docs list them. */
    public static final Map<String, MessageKey> LINES;

    static {
        Map<String, MessageKey> lines = new LinkedHashMap<>();
        lines.put("balance", LINE_BALANCE);
        lines.put("shards", LINE_SHARDS);
        lines.put("kills", LINE_KILLS);
        lines.put("deaths", LINE_DEATHS);
        lines.put("kdr", LINE_KDR);
        lines.put("streak", LINE_STREAK);
        lines.put("playtime", LINE_PLAYTIME);
        lines.put("team", LINE_TEAM);
        lines.put("rank", LINE_RANK);
        lines.put("keyall", LINE_KEYALL);
        lines.put("bounty", LINE_BOUNTY);
        lines.put("combat", LINE_COMBAT);
        lines.put("online", LINE_ONLINE);
        lines.put("ping", LINE_PING);
        lines.put("website", LINE_WEBSITE);
        LINES = Collections.unmodifiableMap(lines);
    }

    // ------------------------------------------------------------------ tab list and nametags

    public static final MessageKey TAB_HEADER = MessageKey.ui("scoreboard.tab.header");
    public static final MessageKey TAB_FOOTER = MessageKey.ui("scoreboard.tab.footer");
    public static final MessageKey TAB_NAME = MessageKey.ui("scoreboard.tab.name", "name", "afk");
    public static final MessageKey TAB_NAME_RANKED = MessageKey.ui("scoreboard.tab.name-ranked", "rank", "name", "afk");
    public static final MessageKey TAB_AFK = MessageKey.ui("scoreboard.tab.afk");
    public static final MessageKey NAMETAG_PREFIX = MessageKey.ui("scoreboard.nametag.prefix", "rank");

    // ------------------------------------------------------------------ the player's switch

    public static final MessageKey SETTINGS_CATEGORY = MessageKey.ui("scoreboard.toggle.category");
    public static final MessageKey SETTINGS_CATEGORY_DESCRIPTION = MessageKey.ui("scoreboard.toggle.category-description");
    public static final MessageKey TOGGLE_LABEL = MessageKey.ui("scoreboard.toggle.label");
    public static final MessageKey TOGGLE_DESCRIPTION = MessageKey.ui("scoreboard.toggle.description");
    public static final MessageKey SHOWN = MessageKey.success("scoreboard.shown");
    public static final MessageKey HIDDEN = MessageKey.success("scoreboard.hidden");
    public static final MessageKey ALREADY_SHOWN = MessageKey.info("scoreboard.already-shown");
    public static final MessageKey ALREADY_HIDDEN = MessageKey.info("scoreboard.already-hidden");
    public static final MessageKey OFF_ON_SERVER = MessageKey.error("scoreboard.off-on-server");
    public static final MessageKey YIELDED = MessageKey.error("scoreboard.yielded", "plugin");

    // ------------------------------------------------------------------ parts (for the staff status)

    public static final MessageKey PART_SIDEBAR = MessageKey.ui("scoreboard.parts.sidebar");
    public static final MessageKey PART_TAB = MessageKey.ui("scoreboard.parts.tab");
    public static final MessageKey PART_NAMETAGS = MessageKey.ui("scoreboard.parts.nametags");
    public static final MessageKey PART_YIELDED = MessageKey.ui("scoreboard.parts.yielded", "part", "plugin");
    public static final MessageKey PARTS_NONE = MessageKey.ui("scoreboard.parts.none");

    // ------------------------------------------------------------------ staff

    public static final MessageKey REFRESHED = MessageKey.chat("scoreboard.admin.refreshed", "count");
    public static final MessageKey STATUS = MessageKey.chat("scoreboard.admin.status",
        "players", "sidebars", "hidden", "teams", "pending", "time", "yielded");
    public static final MessageKey PREVIEW_TITLE = MessageKey.chat("scoreboard.admin.preview-title", "name");
    public static final MessageKey PREVIEW_LINE = MessageKey.chat("scoreboard.admin.preview-line", "number", "line");
    public static final MessageKey PREVIEW_EMPTY = MessageKey.chat("scoreboard.admin.preview-empty");
    public static final MessageKey PREVIEW_HIDDEN = MessageKey.chat("scoreboard.admin.preview-hidden", "name");
    public static final MessageKey PREVIEW_WAIT = MessageKey.chat("scoreboard.admin.preview-wait", "name");
}
