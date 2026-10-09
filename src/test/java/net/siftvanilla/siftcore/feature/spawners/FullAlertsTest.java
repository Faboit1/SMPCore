package net.siftvanilla.siftcore.feature.spawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Full storage alert: a storage that fills owes its owner one alert until it is delivered or the storage drains,
 * so the throttle and an offline owner hold alerts back instead of losing them.
 */
class FullAlertsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final Cooldowns throttle = new Cooldowns();
    private final Map<UUID, List<ManagedSpawner>> owned = new HashMap<>();
    private final List<List<Long>> sent = new ArrayList<>();
    private AlertStyle style = AlertStyle.CHAT;
    private boolean online = true;
    private boolean leavesWhileSending;
    private FullAlerts alerts;

    @BeforeEach
    void setUp() {
        this.alerts = new FullAlerts(this.throttle, owner -> this.owned.getOrDefault(owner, List.of()), new FullAlerts.Owners() {
            @Override
            public AlertStyle style(UUID owner) {
                return FullAlertsTest.this.online ? FullAlertsTest.this.style : null;
            }

            @Override
            public boolean send(UUID owner, AlertStyle chosen, List<ManagedSpawner> full) {
                if (FullAlertsTest.this.leavesWhileSending) {
                    return false;
                }
                FullAlertsTest.this.sent.add(full.stream().map(s -> s.id).toList());
                return true;
            }
        });
    }

    private ManagedSpawner spawner(long id) {
        ManagedSpawner spawner = new ManagedSpawner(id, new SpawnerPos("world", (int) id * 16, 64, 0), OWNER, "zombie", 1, 0, 0);
        this.owned.computeIfAbsent(OWNER, k -> new ArrayList<>()).add(spawner);
        return spawner;
    }

    /** One loot cycle of these spawners, as LootCycle.store runs it, then the delivery it asks for. */
    private void cycle(boolean full, ManagedSpawner... spawners) {
        boolean owed = false;
        for (ManagedSpawner spawner : spawners) {
            spawner.active(1, full);
            owed |= spawner.alertOwed();
        }
        if (owed) {
            this.alerts.deliver(Set.of(OWNER));
        }
    }

    @Test
    void aStorageThatFillsIsReportedOnceWhileItStaysFull() {
        ManagedSpawner a = spawner(1);
        cycle(false, a);
        assertFalse(a.alertOwed(), "not full: nothing owed");
        cycle(true, a);
        assertEquals(List.of(List.of(1L)), this.sent);
        this.throttle.clear(OWNER, FullAlerts.THROTTLE);
        cycle(true, a);
        cycle(true, a);
        assertEquals(1, this.sent.size(), "still full: told once");
        cycle(false, a);
        cycle(true, a);
        assertEquals(List.of(List.of(1L), List.of(1L)), this.sent, "drained and filled again: told again");
    }

    @Test
    void aStorageTheThrottleHeldBackIsReportedWhenItRunsOut() {
        ManagedSpawner a = spawner(1);
        ManagedSpawner b = spawner(2);
        cycle(true, a);
        cycle(true, b);
        assertEquals(List.of(List.of(1L)), this.sent, "B fills a minute after A: held back by the throttle");
        assertTrue(b.alertOwed(), "but still owed");
        cycle(true, b);
        assertEquals(1, this.sent.size());
        this.throttle.clear(OWNER, FullAlerts.THROTTLE);
        cycle(true, b);
        assertEquals(List.of(List.of(1L), List.of(2L)), this.sent, "the next cycle after the throttle ran out names B");
        assertFalse(b.alertOwed());
    }

    @Test
    void oneAlertCoversEveryOwedStorageOfTheOwnerInAnyChunk() {
        ManagedSpawner a = spawner(1);
        ManagedSpawner b = spawner(2);
        ManagedSpawner c = spawner(3);
        this.online = false;
        cycle(true, a);
        cycle(true, b);
        assertTrue(this.sent.isEmpty(), "offline: nothing sent");
        assertTrue(a.alertOwed() && b.alertOwed(), "offline: both still owed");
        this.online = true;
        cycle(true, c);
        assertEquals(List.of(List.of(1L, 2L, 3L)), this.sent, "back online: one line naming all three");
    }

    @Test
    void aStorageThatDrainedOwesNothing() {
        ManagedSpawner a = spawner(1);
        ManagedSpawner b = spawner(2);
        this.online = false;
        cycle(true, a);
        cycle(false, a);
        assertFalse(a.alertOwed(), "emptied before the owner came back");
        this.online = true;
        cycle(true, b);
        assertEquals(List.of(List.of(2L)), this.sent);
    }

    @Test
    void alertsOffKeepTheAlertOwedAndTheThrottleUnused() {
        ManagedSpawner a = spawner(1);
        this.style = AlertStyle.OFF;
        cycle(true, a);
        assertTrue(this.sent.isEmpty());
        assertTrue(this.throttle.remaining(OWNER, FullAlerts.THROTTLE).isZero(), "nothing sent, no throttle");
        this.style = AlertStyle.ACTIONBAR;
        cycle(true, a);
        assertEquals(List.of(List.of(1L)), this.sent, "turned back on while still full: told");
    }

    @Test
    void anAlertThatCouldNotBeDeliveredStaysOwed() {
        ManagedSpawner a = spawner(1);
        this.leavesWhileSending = true;
        cycle(true, a);
        assertTrue(this.sent.isEmpty());
        assertTrue(a.alertOwed(), "owed again");
        assertTrue(this.throttle.remaining(OWNER, FullAlerts.THROTTLE).isZero(), "and the throttle is not used up");
        this.leavesWhileSending = false;
        cycle(true, a);
        assertEquals(List.of(List.of(1L)), this.sent);
    }

    @Test
    void aRemovedSpawnerIsNotNamed() {
        ManagedSpawner a = spawner(1);
        ManagedSpawner b = spawner(2);
        this.online = false;
        cycle(true, a, b);
        b.removed(true);
        this.online = true;
        this.alerts.deliver(Set.of(OWNER));
        assertEquals(List.of(List.of(1L)), this.sent);
    }
}
