package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * Dialogs on screen and dialogs embedded in chat live in separate pools: browsing menus never expires a teleport
 * request's answer waiting in chat, and a burst of chat requests never expires the dialog on screen. A chat dialog
 * also lives as long as the longest request it answers (a team invite, up to an hour), not just the screens' 15 minutes.
 */
class DialogSessionsTest {

    private static final long TTL = 15 * 60 * 1000L;
    private static final long CHAT_TTL = 60 * 60 * 1000L;
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID BLAKE = UUID.randomUUID();
    private static final View VIEW = new View(View.Kind.NOTICE, Component.text("Notice"), List.of(), List.of(),
        List.of(Button.of(Component.text("OK"), null)), null, 1, true);

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final DialogSessions sessions = new DialogSessions(TTL, CHAT_TTL, this.now::get);
    private long nextToken = 1;

    private long screen(UUID player) {
        long token = this.nextToken++;
        this.sessions.add(player, token, VIEW, false);
        return token;
    }

    private long chat(UUID player) {
        long token = this.nextToken++;
        this.sessions.add(player, token, VIEW, true);
        return token;
    }

    @Test
    void aChatAnswerSurvivesBrowsingManyScreens() {
        long answer = chat(ALEX);
        for (int i = 0; i < 50; i++) {
            screen(ALEX);
        }
        assertNotNull(this.sessions.get(ALEX, answer), "the teleport request's answer still works from chat");
    }

    @Test
    void theDialogOnScreenSurvivesABurstOfChatRequests() {
        long open = screen(ALEX);
        for (int i = 0; i < 100; i++) {
            chat(ALEX);
        }
        assertNotNull(this.sessions.get(ALEX, open), "the dialog on screen still answers its click");
    }

    @Test
    void screensKeepTheNewestFew() {
        long first = screen(ALEX);
        long[] later = new long[DialogSessions.MAX_SCREENS];
        for (int i = 0; i < later.length; i++) {
            later[i] = screen(ALEX);
        }
        assertNull(this.sessions.get(ALEX, first), "the oldest screen made room");
        for (long token : later) {
            assertNotNull(this.sessions.get(ALEX, token));
        }
        assertEquals(DialogSessions.MAX_SCREENS, this.sessions.size());
    }

    @Test
    void answeredChatDialogsMakeRoomBeforeUnansweredOnes() {
        long waiting = chat(ALEX);
        long answered = chat(ALEX);
        this.sessions.get(ALEX, answered).consumedAt().set(this.now.get());
        for (int i = 0; i < DialogSessions.MAX_CHAT - 1; i++) {
            chat(ALEX);
        }
        assertNull(this.sessions.get(ALEX, answered), "the answered request went first");
        assertNotNull(this.sessions.get(ALEX, waiting), "the older unanswered request is still there");
        chat(ALEX);
        assertNull(this.sessions.get(ALEX, waiting), "with nothing answered left, the oldest goes");
    }

    @Test
    void sessionsAgeOut() {
        long answer = chat(ALEX);
        long open = screen(ALEX);
        this.now.addAndGet(TTL);
        assertNotNull(this.sessions.get(ALEX, open), "a screen is still fresh at its limit");
        this.now.addAndGet(1);
        assertNull(this.sessions.get(ALEX, open), "a screen older than 15 minutes is gone");
        assertNotNull(this.sessions.get(ALEX, answer), "a chat dialog is not");
        this.now.addAndGet(CHAT_TTL - TTL - 1);
        assertNotNull(this.sessions.get(ALEX, answer), "a chat dialog is still fresh at its own limit");
        this.now.addAndGet(1);
        assertNull(this.sessions.get(ALEX, answer), "a chat dialog older than its time to live is gone");
        chat(ALEX);
        screen(ALEX);
        assertEquals(2, this.sessions.size(), "aged-out sessions are dropped when new ones come");
    }

    @Test
    void aTeamInviteAnswerInChatOutlivesTheScreensTimeToLive() {
        long invite = chat(ALEX);
        this.now.addAndGet(16 * 60 * 1000L);
        long fresh = screen(ALEX);
        chat(ALEX);
        assertNotNull(this.sessions.get(ALEX, invite), "an invite answered after 16 minutes still works from chat");
        this.now.addAndGet(43 * 60 * 1000L);
        assertNotNull(this.sessions.get(ALEX, invite), "and after 59 minutes, within an hour-long invite");
        assertNull(this.sessions.get(ALEX, fresh), "while a screen made 43 minutes ago is gone");
    }

    @Test
    void theChatTimeToLiveCoversTheLongestRequest() {
        assertEquals(TTL, Dialogs.SCREEN_TTL_MILLIS);
        assertEquals(CHAT_TTL, Dialogs.CHAT_TTL_MILLIS, "an hour: the longest a team invite can be configured to last");
    }

    @Test
    void aTokenOnlyWorksForItsOwnPlayer() {
        long token = screen(ALEX);
        assertNull(this.sessions.get(BLAKE, token));
        assertNotNull(this.sessions.get(ALEX, token));
    }

    @Test
    void forgettingAPlayerDropsBothPools() {
        long answer = chat(ALEX);
        long open = screen(ALEX);
        screen(BLAKE);
        this.sessions.forget(ALEX);
        assertNull(this.sessions.get(ALEX, answer));
        assertNull(this.sessions.get(ALEX, open));
        assertEquals(1, this.sessions.size());
        this.sessions.clear();
        assertEquals(0, this.sessions.size());
    }
}
