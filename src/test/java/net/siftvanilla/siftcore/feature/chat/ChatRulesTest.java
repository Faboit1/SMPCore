package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import org.junit.jupiter.api.Test;

/** The deciders behind the chat settings, with the "who can" answers {@link Relations} gives. */
class ChatRulesTest {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Test
    void brandNewPlayersAreBelowThePlaytimeThreshold() {
        assertTrue(ChatRules.newPlayer(0, HALF_HOUR, false));
        assertTrue(ChatRules.newPlayer(1_799, HALF_HOUR, false));
        assertFalse(ChatRules.newPlayer(1_800, HALF_HOUR, false), "exactly the threshold is not new");
        assertFalse(ChatRules.newPlayer(0, HALF_HOUR, true), "staff are never new");
        assertFalse(ChatRules.newPlayer(0, Duration.ZERO, false), "a zero threshold turns it off");
    }

    @Test
    void readersLeaveALineByTheirOwnChoice() {
        assertFalse(ChatRules.hides(false, true, false, true), "public chat on, not hiding new players");
        assertTrue(ChatRules.hides(false, false, false, false), "public chat off hides everyone");
        assertTrue(ChatRules.hides(false, true, true, true), "hiding new players hides a new sender");
        assertFalse(ChatRules.hides(false, true, true, false), "but not a regular");
        assertFalse(ChatRules.hides(true, false, true, true), "the sender always reads their own line");
    }

    @Test
    void mentionAlertsNeedAStyleTheRightKindOfMentionAndAnAllowedSender() {
        assertTrue(ChatRules.alerts(AlertStyle.ACTIONBAR, true, false, true), "@name with plain pings off");
        assertTrue(ChatRules.alerts(AlertStyle.TITLE, false, true, true), "a bare name with plain pings on");
        assertFalse(ChatRules.alerts(AlertStyle.CHAT, false, false, true), "a bare name with plain pings off");
        assertFalse(ChatRules.alerts(AlertStyle.OFF, true, true, true), "mention alerts off");
        assertFalse(ChatRules.alerts(AlertStyle.ACTIONBAR, true, true, false), "a sender outside 'who can ping me'");
    }

    @Test
    void whoCanPingMeFollowsTheAudience() {
        boolean stranger = Relations.allows(Audience.FRIENDS, false, false);
        boolean friend = Relations.allows(Audience.FRIENDS, true, false);
        boolean teammate = Relations.allows(Audience.FRIENDS_TEAM, false, true);
        assertFalse(ChatRules.alerts(AlertStyle.ACTIONBAR, true, true, stranger));
        assertTrue(ChatRules.alerts(AlertStyle.ACTIONBAR, true, true, friend));
        assertTrue(ChatRules.alerts(AlertStyle.ACTIONBAR, true, true, teammate));
        assertFalse(ChatRules.alerts(AlertStyle.ACTIONBAR, true, true, Relations.allows(Audience.FRIENDS, false, true)),
            "a teammate is not a friend");
    }

    @Test
    void privateMessagesFollowTheReceiversAudienceWithTheExceptions() {
        assertTrue(ChatRules.acceptsMessage(Relations.allows(Audience.EVERYONE, false, false), false, false));
        assertFalse(ChatRules.acceptsMessage(Relations.allows(Audience.NOBODY, true, true), false, false), "nobody means nobody");
        assertTrue(ChatRules.acceptsMessage(Relations.allows(Audience.NOBODY, false, false), true, false),
            "someone who wrote to you can be answered");
        assertTrue(ChatRules.acceptsMessage(Relations.allows(Audience.NOBODY, false, false), false, true), "staff with the bypass");
        assertTrue(ChatRules.acceptsMessage(Relations.allows(Audience.FRIENDS_TEAM, false, true), false, false), "a teammate");
        assertFalse(ChatRules.acceptsMessage(Relations.allows(Audience.FRIENDS, false, true), false, false), "friends only");
    }

    @Test
    void theBalanceOnTheCardFollowsBalancePrivacy() {
        assertTrue(ChatRules.showsBalance(true, false, false), "your own card");
        assertTrue(ChatRules.showsBalance(false, Relations.allows(Audience.EVERYONE, false, false), false));
        assertFalse(ChatRules.showsBalance(false, Relations.allows(Audience.NOBODY, true, true), false));
        assertTrue(ChatRules.showsBalance(false, Relations.allows(Audience.FRIENDS, true, false), false), "a friend");
        assertFalse(ChatRules.showsBalance(false, Relations.allows(Audience.FRIENDS, false, false), false), "a stranger");
        assertTrue(ChatRules.showsBalance(false, false, true), "staff with the economy node");
    }

    @Test
    void namesOpenSiftCoresOwnProfilesOnly() {
        assertEquals("/profile ", ChatRules.profileCommand(true, true, true, "siftcore"), "the plain label is ours");
        assertEquals("/siftcore:profile ", ChatRules.profileCommand(true, false, true, "siftcore"),
            "another plugin holds /profile: ours by its namespaced label, never theirs");
        assertNull(ChatRules.profileCommand(true, false, false, "siftcore"), "ours is off in commands.yml (or left to another plugin)");
        assertNull(ChatRules.profileCommand(false, true, true, "siftcore"), "no friends, no profiles");
    }

    @Test
    void readersWhoMayNotOpenProfilesGetAMessageClick() {
        assertEquals("/profile ", ChatRules.readerProfileCommand("/profile ", true));
        assertNull(ChatRules.readerProfileCommand("/profile ", false), "siftcore.command.profile taken away: /msg instead");
        assertNull(ChatRules.readerProfileCommand(null, true), "no profiles on the server");
    }

    @Test
    void thePublicChatReminderOnlyPointsToTheSettingsWhenThePlayerCanChangeIt() {
        assertSame(ChatMessages.PUBLIC_OFF, ChatRules.publicOffReminder(false, false), "the player's own choice: /settings chat");
        assertSame(ChatMessages.PUBLIC_OFF_SERVER, ChatRules.publicOffReminder(true, false), "locked by the server");
        assertSame(ChatMessages.PUBLIC_OFF_SERVER, ChatRules.publicOffReminder(false, true), "hidden: the server's value");
    }

    @Test
    void messageSettingsNeedSiftCoresMessageCommands() {
        assertTrue(ChatRules.messagesOffered(true, true, false) && ChatRules.messagesOffered(true, true, true));
        assertTrue(ChatRules.messagesOffered(true, false, false), "/r off: who can message me still matters");
        assertFalse(ChatRules.messagesOffered(true, false, true), "/r off: no /r target");
        assertFalse(ChatRules.messagesOffered(false, true, false) || ChatRules.messagesOffered(false, true, true), "/msg off");
    }
}
