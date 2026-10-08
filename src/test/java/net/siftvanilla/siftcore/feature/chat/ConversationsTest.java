package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConversationsTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();
    private static final UUID KAI = UUID.randomUUID();

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicReference<Duration> expiry = new AtomicReference<>(Duration.ofMinutes(10));
    private final Conversations conversations = new Conversations(this.clock::get, this.expiry::get);

    @Test
    void bothSidesReplyToEachOther() {
        this.conversations.record(ALEX, SAM);
        assertEquals(Optional.of(SAM), this.conversations.replyTarget(ALEX));
        assertEquals(Optional.of(ALEX), this.conversations.replyTarget(SAM));
        assertEquals(Optional.empty(), this.conversations.replyTarget(KAI));
    }

    @Test
    void theLatestConversationWins() {
        this.conversations.record(ALEX, SAM);
        this.clock.addAndGet(1_000);
        this.conversations.record(KAI, ALEX);
        assertEquals(Optional.of(KAI), this.conversations.replyTarget(ALEX));
        assertEquals(Optional.of(ALEX), this.conversations.replyTarget(SAM), "Sam still answers Alex");
    }

    @Test
    void replyTargetsExpire() {
        this.conversations.record(ALEX, SAM);
        this.clock.addAndGet(Duration.ofMinutes(10).toMillis() - 1);
        assertEquals(Optional.of(SAM), this.conversations.replyTarget(ALEX), "just inside the window");
        this.clock.addAndGet(1);
        assertEquals(Optional.empty(), this.conversations.replyTarget(ALEX), "expired after ten minutes");
        assertFalse(this.conversations.wroteTo(ALEX, SAM));
    }

    @Test
    void expiryFollowsTheConfig() {
        this.conversations.record(ALEX, SAM);
        this.clock.addAndGet(Duration.ofMinutes(2).toMillis());
        this.expiry.set(Duration.ofMinutes(1));
        assertEquals(Optional.empty(), this.conversations.replyTarget(ALEX));
    }

    @Test
    void remembersWhoWroteToWhom() {
        this.conversations.record(ALEX, SAM);
        assertTrue(this.conversations.wroteTo(ALEX, SAM), "Alex wrote to Sam");
        assertFalse(this.conversations.wroteTo(SAM, ALEX), "Sam never wrote to Alex");
        this.conversations.record(SAM, ALEX);
        assertTrue(this.conversations.wroteTo(SAM, ALEX));
        this.conversations.record(KAI, SAM);
        assertFalse(this.conversations.wroteTo(ALEX, SAM), "only the last writer counts");
    }

    @Test
    void theConsoleTakesPart() {
        this.conversations.record(Conversations.CONSOLE, ALEX);
        assertEquals(Optional.of(Conversations.CONSOLE), this.conversations.replyTarget(ALEX));
    }

    @Test
    void sweepDropsExpiredEntries() {
        this.conversations.record(ALEX, SAM);
        assertEquals(3, this.conversations.size());
        this.clock.addAndGet(Duration.ofMinutes(11).toMillis());
        this.conversations.record(KAI, Conversations.CONSOLE);
        this.conversations.sweep();
        assertEquals(3, this.conversations.size(), "only the fresh conversation is left");
    }
}
