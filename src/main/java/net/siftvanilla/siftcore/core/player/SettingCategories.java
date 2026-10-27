package net.siftvanilla.siftcore.core.player;

import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The shared groups of the settings dialog. Every feature registers its settings into one of these (never a group of
 * its own), so players find each kind of setting in one place whatever feature it comes from. The text lives in
 * {@code lang/settings.yml} under {@code settings.categories.<id>}; the icons are {@code icons.yml} names. Each group has
 * its own button colour in the dialog (the server can change it in {@code features/settings.yml}); AFK &amp; shards
 * uses the shards colour and Money &amp; selling the money colour.
 */
public final class SettingCategories {

    public static final MessageKey CHAT_LABEL = MessageKey.ui("settings.categories.chat.label");
    public static final MessageKey CHAT_DESCRIPTION = MessageKey.ui("settings.categories.chat.description");
    public static final MessageKey SOCIAL_LABEL = MessageKey.ui("settings.categories.social.label");
    public static final MessageKey SOCIAL_DESCRIPTION = MessageKey.ui("settings.categories.social.description");
    public static final MessageKey ANNOUNCEMENTS_LABEL = MessageKey.ui("settings.categories.announcements.label");
    public static final MessageKey ANNOUNCEMENTS_DESCRIPTION = MessageKey.ui("settings.categories.announcements.description");
    public static final MessageKey SOUND_LABEL = MessageKey.ui("settings.categories.sound.label");
    public static final MessageKey SOUND_DESCRIPTION = MessageKey.ui("settings.categories.sound.description");
    public static final MessageKey TELEPORT_LABEL = MessageKey.ui("settings.categories.teleport.label");
    public static final MessageKey TELEPORT_DESCRIPTION = MessageKey.ui("settings.categories.teleport.description");
    public static final MessageKey ECONOMY_LABEL = MessageKey.ui("settings.categories.economy.label");
    public static final MessageKey ECONOMY_DESCRIPTION = MessageKey.ui("settings.categories.economy.description");
    public static final MessageKey MARKET_LABEL = MessageKey.ui("settings.categories.market.label");
    public static final MessageKey MARKET_DESCRIPTION = MessageKey.ui("settings.categories.market.description");
    public static final MessageKey COMBAT_LABEL = MessageKey.ui("settings.categories.combat.label");
    public static final MessageKey COMBAT_DESCRIPTION = MessageKey.ui("settings.categories.combat.description");
    public static final MessageKey DISPLAY_LABEL = MessageKey.ui("settings.categories.display.label");
    public static final MessageKey DISPLAY_DESCRIPTION = MessageKey.ui("settings.categories.display.description");
    public static final MessageKey PRIVACY_LABEL = MessageKey.ui("settings.categories.privacy.label");
    public static final MessageKey PRIVACY_DESCRIPTION = MessageKey.ui("settings.categories.privacy.description");
    public static final MessageKey AFK_LABEL = MessageKey.ui("settings.categories.afk.label");
    public static final MessageKey AFK_DESCRIPTION = MessageKey.ui("settings.categories.afk.description");
    public static final MessageKey CRATES_LABEL = MessageKey.ui("settings.categories.crates.label");
    public static final MessageKey CRATES_DESCRIPTION = MessageKey.ui("settings.categories.crates.description");
    public static final MessageKey SPAWNERS_LABEL = MessageKey.ui("settings.categories.spawners.label");
    public static final MessageKey SPAWNERS_DESCRIPTION = MessageKey.ui("settings.categories.spawners.description");
    public static final MessageKey STAFF_LABEL = MessageKey.ui("settings.categories.staff.label");
    public static final MessageKey STAFF_DESCRIPTION = MessageKey.ui("settings.categories.staff.description");
    public static final MessageKey GENERAL_LABEL = MessageKey.ui("settings.general.label");
    public static final MessageKey GENERAL_DESCRIPTION = MessageKey.ui("settings.general.description");

    /** Mentions, private messages and what you see in public chat. */
    public static final SettingCategory CHAT = new SettingCategory("chat", 10, CHAT_LABEL, CHAT_DESCRIPTION, "chat", TextColor.color(0x7DD3FC));
    /** Friend requests, join alerts, team news and invites. */
    public static final SettingCategory SOCIAL = new SettingCategory("social", 20, SOCIAL_LABEL, SOCIAL_DESCRIPTION, "social", TextColor.color(0xF9A8D4));
    /** Which of other players' events show in chat. */
    public static final SettingCategory ANNOUNCEMENTS = new SettingCategory("announcements", 30, ANNOUNCEMENTS_LABEL,
        ANNOUNCEMENTS_DESCRIPTION, "bell", TextColor.color(0xFDE68A));
    /** Volume and which sounds play. */
    public static final SettingCategory SOUND = new SettingCategory("sound", 40, SOUND_LABEL, SOUND_DESCRIPTION, "sound", TextColor.color(0x5EEAD4));
    /** Teleport requests, auto-accept, homes and random teleport. */
    public static final SettingCategory TELEPORT = new SettingCategory("teleport", 50, TELEPORT_LABEL, TELEPORT_DESCRIPTION, "teleport", TextColor.color(0xC4B5FD));
    /** Payments, /sell and sell receipts. */
    public static final SettingCategory ECONOMY = new SettingCategory("economy", 60, ECONOMY_LABEL, ECONOMY_DESCRIPTION, "economy", TextColor.color(0x1AFF1A));
    /** The shop, the auction house and buy orders. */
    public static final SettingCategory MARKET = new SettingCategory("market", 70, MARKET_LABEL, MARKET_DESCRIPTION, "auction", TextColor.color(0xFDBA74));
    /** Combat timer, kills, deaths, bounties and leaderboard alerts. */
    public static final SettingCategory COMBAT = new SettingCategory("combat", 80, COMBAT_LABEL, COMBAT_DESCRIPTION, "kill", TextColor.color(0xFF7B7B));
    /** Sidebar, where quick messages appear and holograms. */
    public static final SettingCategory DISPLAY = new SettingCategory("display", 90, DISPLAY_LABEL, DISPLAY_DESCRIPTION, "display", TextColor.color(0xA3E635));
    /** Coordinates, last-seen time, balance and what the server announces about you. */
    public static final SettingCategory PRIVACY = new SettingCategory("privacy", 100, PRIVACY_LABEL, PRIVACY_DESCRIPTION, "privacy", TextColor.color(0xCBD5E1));
    /** AFK zone countdown and payouts, AFK messages and the shard shop. */
    public static final SettingCategory AFK = new SettingCategory("afk", 110, AFK_LABEL, AFK_DESCRIPTION, "afk", TextColor.color(0x915DFF));
    /** Crate results and keys, keyall, kit reminders and the trash bin. */
    public static final SettingCategory CRATES = new SettingCategory("crates", 120, CRATES_LABEL, CRATES_DESCRIPTION, "crates", TextColor.color(0xFFC94D));
    /** Opening and stacking spawners, storage alerts and teammate activity. */
    public static final SettingCategory SPAWNERS = new SettingCategory("spawners", 130, SPAWNERS_LABEL, SPAWNERS_DESCRIPTION, "spawners", TextColor.color(0xE879F9));
    /** Spy, staff chat, alerts and vanish (only staff see settings here). */
    public static final SettingCategory STAFF = new SettingCategory("staff", 900, STAFF_LABEL, STAFF_DESCRIPTION, "staff", TextColor.color(0x60A5FA));
    /** Settings registered without a category. */
    public static final SettingCategory GENERAL = new SettingCategory("general", Integer.MAX_VALUE, GENERAL_LABEL, GENERAL_DESCRIPTION,
        "info");

    /** Every shared category in dialog order (General last). */
    public static final List<SettingCategory> ALL = List.of(CHAT, SOCIAL, ANNOUNCEMENTS, SOUND, TELEPORT, ECONOMY, MARKET, COMBAT,
        DISPLAY, PRIVACY, AFK, CRATES, SPAWNERS, STAFF, GENERAL);

    /** The fewest settings a category should hold once every feature registered its own. */
    public static final int MIN_SETTINGS = 4;
    /** The most settings one category may hold (a longer list of buttons is hard to scan). */
    public static final int MAX_SETTINGS = 15;

    private SettingCategories() {
    }
}
