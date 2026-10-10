package net.siftvanilla.siftcore.feature.teams;

/**
 * The decisions behind teammate login alerts ({@code team-member-alerts}), as pure functions so the unit tests can
 * drive them with a fake clock. {@link LoginAlerts} does the Bukkit side (events, timers, threads, sending).
 * <p>
 * A teammate who is also a friend may already get the friends feature's alert for the same login or logout; they are
 * not told twice. That is read from the friends settings by id (the teams feature never imports the friends
 * feature): unknown ids (no friends feature) read as null, and then the teammate alert is told.
 */
final class MemberAlertRules {

    /** The friends feature's join alert choice ({@code all}, {@code favourites}, {@code off}). */
    static final String FRIENDS_JOIN_ALERTS = "friends-join-alerts";
    /** The friends feature's leave alert switch. */
    static final String FRIENDS_LEAVE_ALERTS = "friends-leave-alerts";
    /** The friends feature's "tell friends when I join" switch. */
    static final String FRIENDS_ANNOUNCE = "friends-announce";

    private MemberAlertRules() {
    }

    /**
     * Whether a member's login is told to their team at all.
     *
     * @param vanished     the member is vanished when the login is looked at (after the join delay)
     * @param lastLeave    when they last left, 0 when unknown
     * @param now          the time of the decision
     * @param relogGrace   a login this soon after leaving is a relog and is not told
     * @param enabledAt    when the feature started
     * @param startupQuiet no login is told this soon after the start
     */
    static boolean announceJoin(boolean vanished, long lastLeave, long now, long relogGrace, long enabledAt, long startupQuiet) {
        if (vanished) {
            return false;
        }
        if (lastLeave > 0 && now - lastLeave < relogGrace) {
            return false;
        }
        return now - enabledAt >= startupQuiet;
    }

    /**
     * Whether one teammate hears about the login.
     *
     * @param mode        the teammate's {@code team-member-alerts}
     * @param ignores     the teammate ignores the member
     * @param friendsTold the friends feature already tells the teammate about this login ({@link #friendsTellJoin})
     */
    static boolean tellJoin(TeamPrefs.MemberAlerts mode, boolean ignores, boolean friendsTold) {
        return mode.joins() && !ignores && !friendsTold;
    }

    /**
     * Whether one teammate hears about a logout, decided when the leave delay is over.
     *
     * @param mode        the teammate's {@code team-member-alerts}
     * @param ignores     the teammate ignores the member
     * @param friendsTold the friends feature already tells the teammate about this logout ({@link #friendsTellLeave})
     * @param vanished    the member was vanished when they left
     * @param back        the member is online again
     */
    static boolean tellLeave(TeamPrefs.MemberAlerts mode, boolean ignores, boolean friendsTold, boolean vanished, boolean back) {
        return mode.leaves() && !ignores && !friendsTold && !vanished && !back;
    }

    /**
     * Whether the friends feature tells the viewer about the member's login: they are friends, the viewer hears about
     * all friends' logins (or about favourites, and the member is one) and the member lets friends know.
     *
     * @param friends        the viewer and the member are friends
     * @param viewerSetting  the viewer's {@value #FRIENDS_JOIN_ALERTS} (stored form), null without a friends feature
     * @param memberAnnounce the member's {@value #FRIENDS_ANNOUNCE} (stored form), null without a friends feature
     * @param favourite      the viewer marked the member as a favourite (and favourites are on)
     */
    static boolean friendsTellJoin(boolean friends, String viewerSetting, String memberAnnounce, boolean favourite) {
        boolean wants = "all".equals(viewerSetting) || "favourites".equals(viewerSetting) && favourite;
        return friends && wants && "true".equals(memberAnnounce);
    }

    /**
     * Whether the friends feature tells the viewer about the member's logout: they are friends, the viewer turned friend
     * leave alerts on and the member lets friends know.
     *
     * @param friends        the viewer and the member are friends
     * @param viewerSetting  the viewer's {@value #FRIENDS_LEAVE_ALERTS} (stored form), null without a friends feature
     * @param memberAnnounce the member's {@value #FRIENDS_ANNOUNCE} when they left (stored form), null without one
     */
    static boolean friendsTellLeave(boolean friends, String viewerSetting, String memberAnnounce) {
        return friends && "true".equals(viewerSetting) && "true".equals(memberAnnounce);
    }
}
