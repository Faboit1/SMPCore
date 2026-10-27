package net.siftvanilla.siftcore.feature.teams;

import java.util.Locale;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;

/**
 * The teams settings players pick in {@code /settings}: how team news shows, teammate login alerts, who may invite
 * them, and whether team chat mode survives a relog (Friends &amp; teams), plus team chat spy for staff (Staff). The team
 * chat sound is the shared {@code sound-team-chat} (Sounds), which team chat reads.
 */
final class TeamPrefs {

    /**
     * How team news shows: new members, promotions, home and friendly fire changes. The member who made the change and a
     * new owner always get it (in chat when they picked off); disbanding and removals always come in chat.
     */
    static final Choice<AlertStyle> NOTICES = Choices.alert("team-notices", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .text(TeamsMessages.SETTING_NOTICES, TeamsMessages.SETTING_NOTICES_DESCRIPTION).build();
    /** Which of a teammate's logins and logouts a player hears about. */
    static final Choice<MemberAlerts> MEMBER_ALERTS = Choice.ofEnum("team-member-alerts", MemberAlerts.class, MemberAlerts::id,
            MemberAlerts.JOINS)
        .option(MemberAlerts.JOINS_AND_LEAVES, TeamsMessages.SETTING_MEMBER_ALERTS_BOTH)
        .option(MemberAlerts.JOINS, TeamsMessages.SETTING_MEMBER_ALERTS_JOINS)
        .option(MemberAlerts.OFF, OptionTexts.ALERT_OFF)
        .text(TeamsMessages.SETTING_MEMBER_ALERTS, TeamsMessages.SETTING_MEMBER_ALERTS_DESCRIPTION).build();
    /**
     * Who may invite the player to a team. "Friends" is offered while the server has a friends system; a player who
     * picked it reads "nobody" meanwhile (nobody can be their friend then).
     */
    static final Choice<Audience> INVITES = Choice.ofEnum("team-invites", Audience.class, Audience::id, Audience.EVERYONE)
        .option(Audience.EVERYONE, Audience.EVERYONE.label())
        .option(Audience.FRIENDS, Audience.FRIENDS.label(), null, Audience.NOBODY.id())
        .option(Audience.NOBODY, Audience.NOBODY.label())
        .text(TeamsMessages.SETTING_INVITES, TeamsMessages.SETTING_INVITES_DESCRIPTION).build();
    /** Team chat mode comes back after a relog (while the player is still in the same team). */
    static final Toggle CHAT_STICKY = new Toggle("team-chat-sticky", false, TeamsMessages.SETTING_CHAT_STICKY,
        TeamsMessages.SETTING_CHAT_STICKY_DESCRIPTION, null);

    /** Which teammate logins and logouts a player hears about ({@code team-member-alerts}). */
    enum MemberAlerts {
        JOINS_AND_LEAVES,
        JOINS,
        OFF;

        /** The stored id: {@code joins-and-leaves}, {@code joins} or {@code off}. */
        String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        boolean joins() {
            return this != OFF;
        }

        boolean leaves() {
            return this == JOINS_AND_LEAVES;
        }
    }

    private TeamPrefs() {
    }

    /**
     * Registers the teams settings at their places in the catalog's order (the friends settings fill the gaps of
     * Friends &amp; teams), team chat spy with the staff settings, and declares that team chat plays the shared team chat
     * sound and that the team member lists apply the shared last-seen privacy.
     */
    static void register(PlayerSettings settings, Toggle spy, Relations relations) {
        settings.register(SettingCategories.SOCIAL, NOTICES, SettingOptions.<AlertStyle>builder().order(3).build());
        settings.register(SettingCategories.SOCIAL, MEMBER_ALERTS, SettingOptions.<MemberAlerts>builder().order(5).build());
        settings.register(SettingCategories.SOCIAL, INVITES, SettingOptions.<Audience>builder().order(6)
            .optionAvailableWhen(Audience.FRIENDS.id(), relations::friendsAvailable).placeholder(false).build());
        settings.register(SettingCategories.SOCIAL, CHAT_STICKY, SettingOptions.<Boolean>builder().order(11).build());
        settings.register(SettingCategories.STAFF, spy, SettingOptions.<Boolean>builder().order(5).build());
        settings.reads(SharedSettings.SOUND_TEAM_CHAT);
        // The member lists of /team and /team info hide an offline member's last-seen time as they chose (TeamSeen).
        settings.reads(SharedSettings.SEEN_PRIVACY);
    }
}
