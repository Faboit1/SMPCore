package net.siftvanilla.siftcore.feature.staff;

import java.util.function.BooleanSupplier;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;

/**
 * The staff tools' player settings, in the Staff group (only staff see them: each needs the permission of the tool it
 * changes). Their order is the catalog's; the gaps belong to settings of other features in the same group (social spy,
 * team spy, staff combat alerts, config problem alerts).
 */
public final class StaffPreferences {

    /** Staff chat from other staff (your own lines always echo). */
    public static final Toggle STAFF_CHAT = new Toggle("staff-chat", true, StaffMessages.SETTING_CHAT,
        StaffMessages.SETTING_CHAT_DESCRIPTION, StaffNodes.CHAT);
    /** How other staff's bans, mutes, kicks, warnings and freezes are told. */
    public static final Choice<AlertStyle> PUNISH_ALERTS = Choices.alert("staff-punish-alerts", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .permission(StaffNodes.NOTIFY)
        .text(StaffMessages.SETTING_PUNISH_ALERTS, StaffMessages.SETTING_PUNISH_ALERTS_DESCRIPTION).build();
    /** How new player reports are told ({@code /reports} always lists them). */
    public static final Choice<AlertStyle> REPORT_ALERTS = Choices.alert("staff-report-alerts", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .permission(StaffNodes.REPORTS)
        .text(StaffMessages.SETTING_REPORT_ALERTS, StaffMessages.SETTING_REPORT_ALERTS_DESCRIPTION).build();
    /** Always join the server vanished. */
    public static final Toggle VANISH_ON_JOIN = new Toggle("vanish-on-join", false, StaffMessages.SETTING_VANISH_ON_JOIN,
        StaffMessages.SETTING_VANISH_ON_JOIN_DESCRIPTION, StaffNodes.VANISH);
    /** The "You are vanished" reminder above the hotbar. */
    public static final Toggle VANISH_REMINDER = new Toggle("vanish-reminder", true, StaffMessages.SETTING_VANISH_REMINDER,
        StaffMessages.SETTING_VANISH_REMINDER_DESCRIPTION, StaffNodes.VANISH);
    /** Whether other vanished staff show to a viewer who may see them. */
    public static final Toggle SEE_VANISHED = new Toggle("vanish-see-vanished", true, StaffMessages.SETTING_SEE_VANISHED,
        StaffMessages.SETTING_SEE_VANISHED_DESCRIPTION, StaffNodes.VANISH_SEE);
    /** How a frozen player logging out is told. */
    public static final Choice<AlertStyle> FREEZE_ALERTS = Choices.alert("staff-freeze-alerts", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .permission(StaffNodes.FREEZE)
        .text(StaffMessages.SETTING_FREEZE_ALERTS, StaffMessages.SETTING_FREEZE_ALERTS_DESCRIPTION).build();
    /** A confirmation (player, length, reason) before a ban from a player goes through. */
    public static final Toggle CONFIRM_BANS = new Toggle("staff-confirm-bans", false, StaffMessages.SETTING_CONFIRM_BANS,
        StaffMessages.SETTING_CONFIRM_BANS_DESCRIPTION, StaffNodes.BAN);
    /** A normal leave line when vanishing and a join line when reappearing. */
    public static final Toggle FAKE_MESSAGES = new Toggle("vanish-fake-messages", false, StaffMessages.SETTING_FAKE_MESSAGES,
        StaffMessages.SETTING_FAKE_MESSAGES_DESCRIPTION, StaffNodes.VANISH);

    private StaffPreferences() {
    }

    /**
     * Registers the staff settings in the Staff group, in the catalog's order.
     *
     * @param seeVanishedChanged re-applies who sees vanished staff for a viewer at once (on the viewer's thread)
     * @param fakeLinesAvailable whether the server shows any join or leave line a fake one could imitate
     */
    static void register(PlayerSettings prefs, SettingOptions.ChangeHook<Boolean> seeVanishedChanged, BooleanSupplier fakeLinesAvailable) {
        prefs.register(SettingCategories.STAFF, STAFF_CHAT, SettingOptions.<Boolean>builder().order(2).build());
        prefs.register(SettingCategories.STAFF, PUNISH_ALERTS, SettingOptions.<AlertStyle>builder().order(3).build());
        prefs.register(SettingCategories.STAFF, REPORT_ALERTS, SettingOptions.<AlertStyle>builder().order(4).build());
        prefs.register(SettingCategories.STAFF, VANISH_ON_JOIN, SettingOptions.<Boolean>builder().order(6).build());
        prefs.register(SettingCategories.STAFF, VANISH_REMINDER, SettingOptions.<Boolean>builder().order(7).build());
        prefs.register(SettingCategories.STAFF, SEE_VANISHED, SettingOptions.<Boolean>builder().order(8)
            .onChange(seeVanishedChanged).build());
        prefs.register(SettingCategories.STAFF, FREEZE_ALERTS, SettingOptions.<AlertStyle>builder().order(9).build());
        prefs.register(SettingCategories.STAFF, CONFIRM_BANS, SettingOptions.<Boolean>builder().order(11).build());
        prefs.register(SettingCategories.STAFF, FAKE_MESSAGES, SettingOptions.<Boolean>builder().order(12)
            .availableWhen(fakeLinesAvailable).build());
    }

    /**
     * Whether a staff member gets a notice: they hold the tool's permission, they did not cause it (they already got a
     * confirmation), and their alert setting is not off. Returns where it shows, {@link AlertStyle#OFF} for no notice.
     */
    static AlertStyle noticeStyle(boolean permitted, boolean actor, AlertStyle chosen) {
        return !permitted || actor ? AlertStyle.OFF : chosen;
    }

    /**
     * Whether a viewer sees a player: everyone sees a visible player; a vanished one only shows to viewers with the
     * see permission who kept "See vanished staff" on.
     */
    static boolean shouldSee(boolean targetVanished, boolean seePermission, boolean seeSetting) {
        return !targetVanished || seePermission && seeSetting;
    }

    /** Whether a staff chat line shows to a reader: their own lines always echo, others' only with Show staff chat on. */
    static boolean showsStaffChat(boolean fromSelf, boolean readsStaffChat) {
        return fromSelf || readsStaffChat;
    }
}
