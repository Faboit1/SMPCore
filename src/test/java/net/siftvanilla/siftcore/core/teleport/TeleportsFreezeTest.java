package net.siftvanilla.siftcore.core.teleport;

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
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A player frozen by staff never moves through the shared teleports (homes, spawn, RTP, TPA, team home): not when
 * the teleport starts, not when the warmup ends (a frozen player can't change block, so the warmup never cancels on
 * its own) and not when the destination arrives.
 */
class TeleportsFreezeTest {

    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private Fakes.ImmediateScheduler scheduler;
    private Teleports teleports;
    private Fakes.FakePlayer suspect;
    private final Location away = new Location(Fakes.world("world"), 500.5, 70, 500.5);

    @BeforeEach
    void setUp() {
        this.scheduler = new Fakes.ImmediateScheduler();
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class, TeleportMessages.class), new Sounds());
        this.teleports = new Teleports(this.scheduler, messenger, CombatStatus.NONE);
        this.teleports.freezes(this.frozen::contains);
        this.suspect = new Fakes.FakePlayer("Suspect");
    }

    @Test
    void anUnfrozenPlayerTeleports() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.suspect.player, "spawn", Duration.ZERO, () -> CompletableFuture.completedFuture(this.away), result::set);
        assertEquals(Boolean.TRUE, result.get());
        assertEquals(List.of(this.away), this.suspect.teleports);
    }

    @Test
    void aFrozenPlayerIsRefusedAtTheStart() {
        this.frozen.add(this.suspect.id);
        AtomicInteger asked = new AtomicInteger();
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.suspect.player, "spawn", Duration.ZERO, () -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(this.away);
        }, result::set);
        assertEquals(Boolean.FALSE, result.get());
        assertEquals(0, asked.get(), "the destination is never looked up");
        assertFalse(this.suspect.called("teleportAsync"), "never moved");
        assertTrue(this.suspect.said().contains("You can't teleport while frozen."), this.suspect.said().toString());
        assertFalse(this.teleports.pending(this.suspect.id), "no warmup is left running");
    }

    @Test
    void aPlayerFrozenDuringTheWarmupIsRefusedWhenItEnds() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.suspect.player, "home", Duration.ofSeconds(3), () -> CompletableFuture.completedFuture(this.away),
            result::set);
        assertTrue(this.teleports.pending(this.suspect.id), "the warmup runs");
        this.frozen.add(this.suspect.id);
        for (int second = 0; second < 5; second++) {
            this.scheduler.tick();
        }
        assertEquals(Boolean.FALSE, result.get());
        assertFalse(this.suspect.called("teleportAsync"), "the warmup ended but the frozen player stays");
        assertTrue(this.suspect.said().contains("You can't teleport while frozen."), this.suspect.said().toString());
    }

    @Test
    void aPlayerFrozenWhileTheDestinationLoadsIsRefusedBeforeTheMove() {
        CompletableFuture<Location> destination = new CompletableFuture<>();
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.suspect.player, "rtp", Duration.ZERO, () -> destination, result::set);
        this.frozen.add(this.suspect.id);
        destination.complete(this.away);
        assertEquals(Boolean.FALSE, result.get());
        assertFalse(this.suspect.called("teleportAsync"));
    }
}
