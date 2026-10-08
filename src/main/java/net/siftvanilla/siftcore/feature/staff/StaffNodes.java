package net.siftvanilla.siftcore.feature.staff;

import net.siftvanilla.siftcore.core.permission.Permissions;
import org.bukkit.permissions.PermissionDefault;

/** Permission nodes of the staff tools. Everything is operator-only by default except sending reports. */
final class StaffNodes {

    static final String VANISH = "siftcore.staff.vanish";
    static final String VANISH_OTHERS = "siftcore.staff.vanish.others";
    static final String VANISH_SEE = "siftcore.staff.vanish.see";
    static final String FREEZE = "siftcore.staff.freeze";
    static final String MUTE = "siftcore.staff.mute";
    static final String BAN = "siftcore.staff.ban";
    static final String TEMPBAN = "siftcore.staff.tempban";
    static final String UNBAN = "siftcore.staff.unban";
    static final String KICK = "siftcore.staff.kick";
    static final String WARN = "siftcore.staff.warn";
    static final String HISTORY = "siftcore.staff.history";
    static final String CHAT = "siftcore.staff.chat";
    static final String NOTIFY = "siftcore.staff.notify";
    static final String REPORTS = "siftcore.staff.reports";
    static final String INVSEE = "siftcore.staff.invsee";
    static final String INVSEE_EDIT = "siftcore.staff.invsee.edit";
    static final String ECSEE = "siftcore.staff.ecsee";
    static final String ECSEE_EDIT = "siftcore.staff.ecsee.edit";
    static final String ALTS = "siftcore.staff.alts";
    static final String WHOIS = "siftcore.staff.whois";
    static final String BROADCAST = "siftcore.staff.broadcast";
    static final String CLEARCHAT = "siftcore.staff.clearchat";
    static final String CLEARCHAT_BYPASS = "siftcore.staff.clearchat.bypass";
    static final String REPORT = "siftcore.command.report";

    private StaffNodes() {
    }

    static void declare(Permissions permissions) {
        staff(permissions, VANISH, "Vanish with /vanish");
        staff(permissions, VANISH_OTHERS, "Vanish other staff with /vanish <player>");
        staff(permissions, VANISH_SEE, "See vanished staff");
        staff(permissions, FREEZE, "Freeze players with /freeze and be told when a frozen player logs out");
        staff(permissions, MUTE, "Mute and unmute players");
        staff(permissions, BAN, "Ban players permanently with /ban");
        staff(permissions, TEMPBAN, "Ban players for a while with /tempban");
        staff(permissions, UNBAN, "Lift bans with /unban");
        staff(permissions, KICK, "Kick players with /kick");
        staff(permissions, WARN, "Warn players with /warn");
        staff(permissions, HISTORY, "See a player's punishments with /history");
        staff(permissions, CHAT, "Read and write staff chat (/sc)");
        staff(permissions, NOTIFY, "Be told about bans, mutes, kicks, warnings and freezes by other staff");
        staff(permissions, REPORTS, "Get report notifications and handle reports with /reports");
        staff(permissions, INVSEE, "Look into players' inventories with /invsee");
        staff(permissions, INVSEE_EDIT, "Take and delete items in /invsee");
        staff(permissions, ECSEE, "Look into players' ender chests with /ecsee");
        staff(permissions, ECSEE_EDIT, "Take and delete items in /ecsee");
        staff(permissions, ALTS, "List accounts sharing a player's address with /alts");
        staff(permissions, WHOIS, "Look a player up with /whois");
        staff(permissions, BROADCAST, "Announce to everyone with /broadcast");
        staff(permissions, CLEARCHAT, "Clear everyone's chat with /clearchat");
        staff(permissions, CLEARCHAT_BYPASS, "Keep your chat when it is cleared");
        permissions.declare(REPORT, "Report a player to staff with /report", PermissionDefault.TRUE);
    }

    private static void staff(Permissions permissions, String node, String description) {
        permissions.declare(node, description, PermissionDefault.OP);
    }
}
