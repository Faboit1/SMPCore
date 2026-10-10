package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatRepeatsTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    @Test
    void anErrorTheChannelMovesToChatShowsOncePerBurst() {
        ChatRepeats repeats = new ChatRepeats(1_000);
        assertTrue(repeats.allow(ALEX, "spawn.use-denied", 0), "the first one shows");
        assertFalse(repeats.allow(ALEX, "spawn.use-denied", 200), "holding the use key: held back");
        for (long at = 400; at <= 10_000; at += 200) {
            assertFalse(repeats.allow(ALEX, "spawn.use-denied", at), "every attempt keeps the burst going (" + at + ")");
        }
        assertTrue(repeats.allow(ALEX, "spawn.use-denied", 11_000), "shows again after a pause");
    }

    @Test
    void aRefusalAlreadyToldOnceASecondIsStillOneBurst() {
        // Spawn protection and the AFK zone tell a refusal at most once a second while the player keeps trying.
        ChatRepeats repeats = new ChatRepeats();
        assertTrue(repeats.allow(ALEX, "spawn.build-denied", 0));
        for (long at = 1_000; at <= 20_000; at += 1_000) {
            assertFalse(repeats.allow(ALEX, "spawn.build-denied", at), "once a second keeps the burst going (" + at + ")");
        }
        assertTrue(repeats.allow(ALEX, "spawn.build-denied", 20_000 + ChatRepeats.WINDOW_MILLIS), "after a real pause");
    }

    @Test
    void anotherErrorOrAnotherPlayerIsNotARepeat() {
        ChatRepeats repeats = new ChatRepeats(1_000);
        assertTrue(repeats.allow(ALEX, "a", 0));
        assertTrue(repeats.allow(SAM, "a", 10), "players are independent");
        assertTrue(repeats.allow(ALEX, "b", 20), "a different error shows");
        assertTrue(repeats.allow(ALEX, "a", 30), "and the first one again after it");
    }

    @Test
    void oldEntriesAreDroppedOnceManyPlayersWereSeen() {
        ChatRepeats repeats = new ChatRepeats(1_000);
        for (int i = 0; i < 300; i++) {
            repeats.allow(UUID.randomUUID(), "a", 0);
        }
        repeats.allow(ALEX, "a", 5_000);
        assertTrue(repeats.size() < 10, "only recent players are remembered: " + repeats.size());
    }
}
