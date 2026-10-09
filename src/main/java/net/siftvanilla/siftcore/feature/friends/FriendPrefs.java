package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import org.bukkit.entity.Player;

/**
 * The friends settings of a player: registered settings in the shared Friends &amp; teams group (so they show in
 * {@code /settings} with everything else), read through core {@link PlayerSettings}, with typed getters for the
 * feature. Players who are not loaded read the server's default; the request unit reads the stored
 * {@code friends-requests} row in its own transaction through {@link StoredSetting}.
 * <p>
 * {@code /friend settings <key> <value>} takes short keys ({@link Key}) and goes through the same registry, so both
 * places always agree (a locked or hidden setting is refused the same way).
 * <p>
 * Whose {@code /tpa} a player accepts without being asked is the shared {@code friends-tpa} setting
 * ({@link SharedSettings#FRIENDS_TPA}); {@link FriendGraph#autoAcceptTeleport} answers it for the teleport feature.
 */
public final class FriendPrefs {

    /** Who may send requests (stored ids {@code everyone}, {@code known}, {@code nobody}). */
    public static final Choice<Privacy> REQUESTS = Choice.ofEnum("friends-requests", Privacy.class, Privacy::id, Privacy.EVERYONE)
        .option(Privacy.EVERYONE, OptionTexts.AUDIENCE_EVERYONE)
        .option(Privacy.KNOWN, FriendsMessages.SETTING_REQUESTS_KNOWN)
        .option(Privacy.NOBODY, OptionTexts.AUDIENCE_NOBODY)
        .text(FriendsMessages.SETTING_REQUESTS, FriendsMessages.SETTING_REQUESTS_DESCRIPTION).build();
    /**
     * Which friends' logins a player hears about. "Favourites only" is offered while favourites exist
     * ({@code limits.favourites} above 0); a player who picked it reads "off" meanwhile.
     */
    public static final Choice<JoinAlerts> JOIN_ALERTS = Choice.ofEnum("friends-join-alerts", JoinAlerts.class, JoinAlerts::id,
            JoinAlerts.ALL)
        .option(JoinAlerts.ALL, FriendsMessages.SETTING_JOIN_ALERTS_ALL)
        .option(JoinAlerts.FAVOURITES, FriendsMessages.SETTING_JOIN_ALERTS_FAVOURITES, null, JoinAlerts.OFF.id())
        .option(JoinAlerts.OFF, OptionTexts.ALERT_OFF)
        .text(FriendsMessages.SETTING_JOIN_ALERTS, FriendsMessages.SETTING_JOIN_ALERTS_DESCRIPTION).build();
    /** A chat line with Accept and Deny when someone sends a request (a switch: its buttons only work in chat). */
    public static final Toggle REQUEST_ALERTS = new Toggle("friends-request-alerts", true,
        FriendsMessages.SETTING_REQUEST_ALERTS, FriendsMessages.SETTING_REQUEST_ALERTS_DESCRIPTION, null);
    /** Whether the player's friends hear about their logins and logouts. */
    public static final Toggle ANNOUNCE = new Toggle("friends-announce", true,
        FriendsMessages.SETTING_ANNOUNCE, FriendsMessages.SETTING_ANNOUNCE_DESCRIPTION, null);
    /** The summary a few seconds after joining: friends online, waiting requests, new friends. */
    public static final Toggle JOIN_SUMMARY = new Toggle("friends-join-summary", true,
        FriendsMessages.SETTING_JOIN_SUMMARY, FriendsMessages.SETTING_JOIN_SUMMARY_DESCRIPTION, null);
    /** A chat line when a friend logs off. */
    public static final Toggle LEAVE_ALERTS = new Toggle("friends-leave-alerts", false,
        FriendsMessages.SETTING_LEAVE_ALERTS, FriendsMessages.SETTING_LEAVE_ALERTS_DESCRIPTION, null);
    /** The order of the player's friends list (the dialog and {@code /friend list}). */
    public static final Choice<ListOrder.Sort> LIST_ORDER = Choice.ofEnum("friends-list-order", ListOrder.Sort.class,
            ListOrder.Sort::id, ListOrder.Sort.STATUS)
        .option(ListOrder.Sort.STATUS, FriendsMessages.SETTING_LIST_ORDER_STATUS)
        .option(ListOrder.Sort.NAME, FriendsMessages.SETTING_LIST_ORDER_NAME)
        .option(ListOrder.Sort.LAST_SEEN, FriendsMessages.SETTING_LIST_ORDER_LAST_SEEN)
        .option(ListOrder.Sort.OLDEST, FriendsMessages.SETTING_LIST_ORDER_OLDEST)
        .text(FriendsMessages.SETTING_LIST_ORDER, FriendsMessages.SETTING_LIST_ORDER_DESCRIPTION).build();

    /** Which friends' joins a player hears about (the stored ids are the lowercase names). */
    public enum JoinAlerts {
        ALL,
        FAVOURITES,
        OFF;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The keys {@code /friend settings <key> <value>} takes, each one a registered setting, in the order Settings shows
     * them. Switches take {@code on}/{@code off} there; choices take their option ids.
     */
    public enum Key {
        REQUESTS("requests", FriendPrefs.REQUESTS),
        JOIN_ALERTS("join-alerts", FriendPrefs.JOIN_ALERTS),
        LEAVE_ALERTS("leave-alerts", FriendPrefs.LEAVE_ALERTS),
        REQUEST_ALERTS("request-alerts", FriendPrefs.REQUEST_ALERTS),
        ANNOUNCE("announce", FriendPrefs.ANNOUNCE),
        JOIN_SUMMARY("join-summary", FriendPrefs.JOIN_SUMMARY),
        LIST_ORDER("list-order", FriendPrefs.LIST_ORDER);

        private final String id;
        private final PlayerSetting<?> setting;

        Key(String id, PlayerSetting<?> setting) {
            this.id = id;
            this.setting = setting;
        }

        public String id() {
            return this.id;
        }

        /** The registered setting behind the key. */
        public PlayerSetting<?> setting() {
            return this.setting;
        }

        public static Key parse(String input) {
            for (Key key : values()) {
                if (key.id.equalsIgnoreCase(input == null ? "" : input.strip())) {
                    return key;
                }
            }
            return null;
        }
    }

    private final PlayerSettings settings;

    public FriendPrefs(PlayerSettings settings) {
        this.settings = settings;
    }

    /**
     * Registers the friends settings in the Friends &amp; teams group, at their places in the catalog's order (the teams
     * settings fill the gaps). Favourites-only join alerts are offered while favourites exist.
     */
    static void register(PlayerSettings settings, BooleanSupplier favouritesOn) {
        settings.register(SettingCategories.SOCIAL, REQUESTS, SettingOptions.<Privacy>builder().order(1).placeholder(false).build());
        settings.register(SettingCategories.SOCIAL, JOIN_ALERTS, SettingOptions.<JoinAlerts>builder().order(2)
            .optionAvailableWhen(JoinAlerts.FAVOURITES.id(), favouritesOn).build());
        settings.register(SettingCategories.SOCIAL, REQUEST_ALERTS, SettingOptions.<Boolean>builder().order(4).build());
        settings.register(SettingCategories.SOCIAL, ANNOUNCE, SettingOptions.<Boolean>builder().order(7).build());
        settings.register(SettingCategories.SOCIAL, JOIN_SUMMARY, SettingOptions.<Boolean>builder().order(8).build());
        settings.register(SettingCategories.SOCIAL, LEAVE_ALERTS, SettingOptions.<Boolean>builder().order(9).build());
        settings.register(SettingCategories.SOCIAL, LIST_ORDER, SettingOptions.<ListOrder.Sort>builder().order(10).build());
        // Friend profiles leave the last-seen time out for players who keep it from the viewer.
        settings.reads(SharedSettings.SEEN_PRIVACY);
    }

    public Privacy privacy(UUID player) {
        return this.settings.get(player, REQUESTS);
    }

    /** The rule the request unit reads a target's stored privacy with (the server's lock and default now). */
    StoredSetting<Privacy> privacyRule() {
        return StoredSetting.of(this.settings, REQUESTS);
    }

    /** The player's join alerts; "favourites" reads as off while favourites are turned off. */
    public JoinAlerts joinAlerts(UUID player) {
        return this.settings.get(player, JOIN_ALERTS);
    }

    /** Whose plain {@code /tpa} the player accepts without being asked (the shared {@code friends-tpa}). */
    public AutoAccept autoTpa(UUID player) {
        return this.settings.get(player, SharedSettings.FRIENDS_TPA);
    }

    public boolean leaveAlerts(UUID player) {
        return this.settings.enabled(player, LEAVE_ALERTS);
    }

    public boolean requestAlerts(UUID player) {
        return this.settings.enabled(player, REQUEST_ALERTS);
    }

    public boolean announce(UUID player) {
        return this.settings.enabled(player, ANNOUNCE);
    }

    public boolean joinSummary(UUID player) {
        return this.settings.enabled(player, JOIN_SUMMARY);
    }

    public ListOrder.Sort listOrder(UUID player) {
        return this.settings.get(player, LIST_ORDER);
    }

    // ------------------------------------------------------------------ /friend settings

    /** The current value of a key as {@code /friend settings} shows it ({@code on}/{@code off} or an option id). */
    public String value(Player player, Key key) {
        return current(player, key.setting());
    }

    private <T> String current(Player player, PlayerSetting<T> setting) {
        return word(setting, this.settings.get(player, setting));
    }

    /** The values the player may give a key now: on/off, or the options open to them (none if they can't change it). */
    public List<String> values(Player player, Key key) {
        Registry.Entry<?> entry = this.settings.registry().entry(key.setting().id());
        if (entry == null || !this.settings.visible(entry, player::hasPermission)) {
            return List.of();
        }
        return words(entry, player::hasPermission);
    }

    private <T> List<String> words(Registry.Entry<T> entry, Predicate<String> permissions) {
        if (entry.setting() instanceof Toggle) {
            return List.of("on", "off");
        }
        List<String> words = new ArrayList<>();
        for (Choice.Option<T> option : this.settings.options(entry, permissions)) {
            words.add(option.id());
        }
        return words;
    }

    /** Whether the server fixed the key's setting for everyone. */
    public boolean locked(Key key) {
        return this.settings.locked(key.setting());
    }

    /**
     * Stores a value the player typed, as they would in Settings (their permissions apply, a locked or hidden setting
     * refuses). {@link SetResult#INVALID} when the text is not one of {@link #values}.
     */
    public SetResult set(Player player, Key key, String value) {
        String typed = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (this.settings.locked(key.setting())) {
            return SetResult.LOCKED;
        }
        if (!values(player, key).contains(typed)) {
            return this.settings.hidden(key.setting()) ? SetResult.NOT_ALLOWED : SetResult.INVALID;
        }
        return this.settings.setParsed(player, key.setting().id(), typed, Change.command(player.getName()));
    }

    /** A value as a command word: {@code on}/{@code off} for a switch, else the stored option id. */
    static <T> String word(PlayerSetting<T> setting, T value) {
        if (setting instanceof Toggle) {
            return Boolean.TRUE.equals(value) ? "on" : "off";
        }
        return setting.encode(value);
    }
}
