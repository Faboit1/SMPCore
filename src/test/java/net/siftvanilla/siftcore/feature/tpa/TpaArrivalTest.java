package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.teleport.Teleports;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An accepted teleport request moves one player to the other through the shared teleports, which re-check only the
 * mover's combat tag. The player who stays put can be attacked during the warmup; then nobody arrives in the middle of
 * that fight.
 */
class TpaArrivalTest {

    private final Set<UUID> tagged = ConcurrentHashMap.newKeySet();
    private Fakes.ImmediateScheduler scheduler;
    private Teleports teleports;
    private Fakes.FakePlayer mover;
    private Fakes.FakePlayer staying;
    private final Location meetingPoint = new Location(Fakes.world("world"), 800.5, 70, -300.5);

    @BeforeEach
    void setUp() {
        this.scheduler = new Fakes.ImmediateScheduler();
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class, TeleportMessages.class), new Sounds());
        CombatStatus combat = new CombatStatus() {
            @Override
            public boolean tagged(UUID player) {
                return TpaArrivalTest.this.tagged.contains(player);
            }

            @Override
            public Duration remaining(UUID player) {
                return tagged(player) ? Duration.ofSeconds(15) : Duration.ZERO;
            }
        };
        this.teleports = new Teleports(this.scheduler, messenger, combat);
        this.mover = new Fakes.FakePlayer("Mover");
        this.staying = new Fakes.FakePlayer("Staying");
    }

    /** Starts the warmup like TpaService does after an accept, with the destination checked by the TPA rules. */
    private AtomicReference<Boolean> accept(AtomicInteger refusals) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.mover.player, "tpa", Duration.ofSeconds(3),
            () -> TpaGate.unlessFighting(CompletableFuture.completedFuture(this.meetingPoint),
                () -> this.tagged.contains(this.staying.id), refusals::incrementAndGet),
            result::set);
        return result;
    }

    private void finishWarmup() {
        for (int second = 0; second < 5; second++) {
            this.scheduler.tick();
        }
    }

    @Test
    void withoutAFightTheMoverArrives() {
        AtomicInteger refusals = new AtomicInteger();
        AtomicReference<Boolean> result = accept(refusals);
        finishWarmup();
        assertEquals(Boolean.TRUE, result.get());
        assertEquals(List.of(this.meetingPoint), this.mover.teleports);
        assertEquals(0, refusals.get());
    }

    @Test
    void nobodyArrivesAtAPlayerWhoGotIntoAFightDuringTheWarmup() {
        AtomicInteger refusals = new AtomicInteger();
        AtomicReference<Boolean> result = accept(refusals);
        assertTrue(this.teleports.pending(this.mover.id), "the warmup runs");
        this.tagged.add(this.staying.id);
        finishWarmup();
        assertEquals(Boolean.FALSE, result.get(), "the teleport reports failure, so the staying player is told");
        assertFalse(this.mover.called("teleportAsync"), "the ally was not delivered into the fight");
        assertEquals(1, refusals.get(), "the mover is told why");
    }

    @Test
    void aFightThatEndedBeforeTheWarmupDidDoesNotStopTheMove() {
        AtomicInteger refusals = new AtomicInteger();
        this.tagged.add(this.staying.id);
        AtomicReference<Boolean> result = accept(refusals);
        this.tagged.remove(this.staying.id);
        finishWarmup();
        assertEquals(Boolean.TRUE, result.get());
        assertEquals(List.of(this.meetingPoint), this.mover.teleports);
    }

    @Test
    void aMissingDestinationStaysSilent() {
        AtomicInteger refusals = new AtomicInteger();
        this.tagged.add(this.staying.id);
        Location none = TpaGate.unlessFighting(CompletableFuture.completedFuture(null), () -> true, refusals::incrementAndGet).join();
        assertEquals(null, none);
        assertEquals(0, refusals.get(), "the player who left was already reported; no combat message on top");
    }
}
