package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class StatusBarsTest {

    private static StatusBars.Bar bar(String text, int priority) {
        return new StatusBars.Bar(Component.text(text), 0.5f, BossBar.Color.RED, BossBar.Overlay.PROGRESS, priority);
    }

    @Test
    void theHighestPriorityStatusShows() {
        Map<String, StatusBars.Bar> owners = new HashMap<>();
        assertNull(StatusBars.pick(owners));
        owners.put("afk", bar("AFK", StatusBars.PRIORITY_IDLE));
        assertEquals("afk", StatusBars.pick(owners).getKey());
        owners.put("combat", bar("Combat", StatusBars.PRIORITY_COMBAT));
        assertEquals("combat", StatusBars.pick(owners).getKey(), "the combat timer beats the AFK countdown");
        owners.remove("combat");
        assertEquals("afk", StatusBars.pick(owners).getKey(), "and the AFK countdown comes back");
        owners.put("aaa", bar("Tie", StatusBars.PRIORITY_IDLE));
        assertEquals("aaa", StatusBars.pick(owners).getKey(), "ties go by owner name");
    }

    @Test
    void progressStaysWithinTheBar() {
        assertEquals(1f, new StatusBars.Bar(Component.empty(), 3f, BossBar.Color.RED, BossBar.Overlay.PROGRESS, 0).progress());
        assertEquals(0f, new StatusBars.Bar(Component.empty(), -1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS, 0).progress());
        assertEquals(0f, new StatusBars.Bar(Component.empty(), Float.NaN, BossBar.Color.RED, BossBar.Overlay.PROGRESS, 0).progress());
    }
}
