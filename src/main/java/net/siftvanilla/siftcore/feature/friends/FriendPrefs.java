package net.siftvanilla.siftcore.feature.friends;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Toggle;

/**
 * The friends settings of a player, kept in core {@link PlayerSettings} (the {@code settings} table): three
 * on/off toggles that also show in the general settings dialog, and choices stored as raw values. A raw value that is
 * not one of the choices reads as the default. Players who are not loaded read as defaults; the request unit reads
 * the stored privacy directly from the table instead.
 * <p>
 * {@code friends-tpa} is read for {@code FriendLookup#autoAcceptTeleport} but not offered to players: TPA still decides
 * friends' teleports with its own "Friends skip requests" toggle, and two controls for one thing would contradict
 * each other. The integration pass that makes TPA ask {@code autoAcceptTeleport} adds the choice to the settings.
 */
public final class FriendPrefs {

    /** {@code friends-requests}: who may send requests ({@link Privacy}). */
    public static final String REQUESTS = "friends-requests";
    /** {@code friends-join-alerts}: which friends' joins are announced ({@link JoinAlerts}). */
    public static final String JOIN_ALERTS = "friends-join-alerts";
    /** {@code friends-tpa}: whose plain teleport requests are accepted without asking ({@link AutoTpa}). */
    public static final String TPA = "friends-tpa";

    public static final Toggle LEAVE_ALERTS = new Toggle("friends-leave-alerts", false,
        FriendsMessages.SETTING_LEAVE_ALERTS, FriendsMessages.SETTING_LEAVE_ALERTS_DESCRIPTION, null);
    public static final Toggle REQUEST_ALERTS = new Toggle("friends-request-alerts", true,
        FriendsMessages.SETTING_REQUEST_ALERTS, FriendsMessages.SETTING_REQUEST_ALERTS_DESCRIPTION, null);
    public static final Toggle ANNOUNCE = new Toggle("friends-announce", true,
        FriendsMessages.SETTING_ANNOUNCE, FriendsMessages.SETTING_ANNOUNCE_DESCRIPTION, null);

    /** Which friends' joins a player hears about. */
    public enum JoinAlerts {
        ALL,
        FAVOURITES,
        OFF;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static JoinAlerts parse(String value) {
            for (JoinAlerts alerts : values()) {
                if (alerts.id().equalsIgnoreCase(value == null ? "" : value.strip())) {
                    return alerts;
                }
            }
            return ALL;
        }
    }

    /** Whose plain teleport requests a player accepts without being asked. */
    public enum AutoTpa {
        NOBODY,
        FAVOURITES,
        ALL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static AutoTpa parse(String value) {
            for (AutoTpa mode : values()) {
                if (mode.id().equalsIgnoreCase(value == null ? "" : value.strip())) {
                    return mode;
                }
            }
            return NOBODY;
        }
    }

    /** The keys {@code /friend settings <key> <value>} takes, in the order the settings dialog shows them. */
    public enum Key {
        REQUESTS("requests", List.of("everyone", "known", "nobody")),
        JOIN_ALERTS("join-alerts", List.of("all", "favourites", "off")),
        LEAVE_ALERTS("leave-alerts", List.of("on", "off")),
        REQUEST_ALERTS("request-alerts", List.of("on", "off")),
        ANNOUNCE("announce", List.of("on", "off"));

        private final String id;
        private final List<String> options;

        Key(String id, List<String> options) {
            this.id = id;
            this.options = options;
        }

        public String id() {
            return this.id;
        }

        /** The values this key takes, as typed in {@code /friend settings <key> <value>}. */
        public List<String> options() {
            return this.options;
        }

        public static Key parse(String input) {
            for (Key key : values()) {
                if (key.id.equalsIgnoreCase(input)) {
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

    public Privacy privacy(UUID player) {
        return Privacy.parse(this.settings.raw(player, REQUESTS, Privacy.EVERYONE.id()));
    }

    public JoinAlerts joinAlerts(UUID player) {
        return JoinAlerts.parse(this.settings.raw(player, JOIN_ALERTS, JoinAlerts.ALL.id()));
    }

    public AutoTpa autoTpa(UUID player) {
        return AutoTpa.parse(this.settings.raw(player, TPA, AutoTpa.NOBODY.id()));
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

    /** The current value of a key as {@code /friend settings} shows it. */
    public String value(UUID player, Key key) {
        return switch (key) {
            case REQUESTS -> privacy(player).id();
            case JOIN_ALERTS -> joinAlerts(player).id();
            case LEAVE_ALERTS -> leaveAlerts(player) ? "on" : "off";
            case REQUEST_ALERTS -> requestAlerts(player) ? "on" : "off";
            case ANNOUNCE -> announce(player) ? "on" : "off";
        };
    }

    /**
     * Stores one value. Returns false (and stores nothing) when the value is not one of the key's choices.
     */
    public boolean set(UUID player, Key key, String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!key.options().contains(normalized)) {
            return false;
        }
        switch (key) {
            case REQUESTS -> this.settings.setRaw(player, REQUESTS, normalized);
            case JOIN_ALERTS -> this.settings.setRaw(player, JOIN_ALERTS, normalized);
            case LEAVE_ALERTS -> this.settings.set(player, LEAVE_ALERTS, normalized.equals("on"));
            case REQUEST_ALERTS -> this.settings.set(player, REQUEST_ALERTS, normalized.equals("on"));
            case ANNOUNCE -> this.settings.set(player, ANNOUNCE, normalized.equals("on"));
        }
        return true;
    }
}
