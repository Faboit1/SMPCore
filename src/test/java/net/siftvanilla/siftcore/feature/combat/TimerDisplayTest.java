package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.StatusBars;
import net.siftvanilla.siftcore.testing.Fakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The boss bar timer across threads: the async timer queues the bar for the player's thread, and death or quit end
 * combat on that thread. A bar queued just before the end must not come back after the end took it off.
 */
class TimerDisplayTest {

    /** Entity tasks wait until {@link #runQueued}; the current thread owns the player only while {@code owning}. */
    private static final class Threads {
        final List<Runnable> queued = new ArrayList<>();
        boolean owning;

        final Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] {Scheduler.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "owns" -> this.owning;
                case "entity" -> {
                    this.queued.add((Runnable) args[1]);
                    yield Task.NONE;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Threads";
                default -> Fakes.defaultValue(method.getReturnType());
            });

        /** The player's thread runs what was handed to it. */
        void runQueued() {
            boolean was = this.owning;
            this.owning = true;
            try {
                while (!this.queued.isEmpty()) {
                    this.queued.removeFirst().run();
                }
            } finally {
                this.owning = was;
            }
        }
    }

    private static final Duration TWENTY = Duration.ofSeconds(20);

    private Threads threads;
    private CombatTags tags;
    private StatusBars bars;
    private TimerDisplay display;
    private Fakes.FakePlayer player;

    @BeforeEach
    void setUp() {
        this.threads = new Threads();
        this.tags = new CombatTags();
        this.bars = new StatusBars(this.threads.scheduler);
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        settings.register(SettingCategories.COMBAT, CombatFeature.TIMER_DISPLAY);
        settings.overrides(new Overrides(Map.of(CombatFeature.TIMER_DISPLAY.id(), "bossbar"), Map.of(), Set.of()));
        this.display = new TimerDisplay(settings, new Messenger(Fakes.lang(), new Sounds()), Fakes.lang(), this.bars, this.tags,
            this.threads.scheduler);
        this.player = new Fakes.FakePlayer("Timer");
    }

    /** What {@link CombatTagger#clear} does on the player's thread when they die or leave. */
    private void clearOnThePlayersThread() {
        this.threads.owning = true;
        try {
            this.tags.untag(this.player.id);
            this.display.hide(this.player.player);
        } finally {
            this.threads.owning = false;
        }
    }

    @Test
    void aBarQueuedBeforeDeathStaysOffAfterIt() {
        this.tags.tag(this.player.id, null, TWENTY);
        this.display.show(this.player.player, 12, TWENTY);
        assertEquals(1, this.threads.queued.size(), "the async timer hands the bar to the player's thread");
        assertFalse(this.display.showing(this.player.id));

        clearOnThePlayersThread();
        this.threads.runQueued();

        assertFalse(this.display.showing(this.player.id), "the queued bar must not come back after the death cleared combat");
        assertFalse(this.player.called("showBossBar"), "the client never got the bar: " + this.player.calls);
    }

    @Test
    void aBarShownBeforeDeathIsTakenOff() {
        this.tags.tag(this.player.id, null, TWENTY);
        this.display.show(this.player.player, 12, TWENTY);
        this.threads.runQueued();
        assertTrue(this.display.showing(this.player.id));
        assertTrue(this.player.called("showBossBar"));

        clearOnThePlayersThread();

        assertFalse(this.display.showing(this.player.id));
        assertTrue(this.player.called("hideBossBar"));
    }

    @Test
    void aTagThatRanOutBeforeTheQueuedBarRanShowsNothing() {
        this.tags.tag(this.player.id, null, TWENTY);
        this.display.show(this.player.player, 1, TWENTY);
        this.tags.untag(this.player.id);
        this.threads.runQueued();
        assertFalse(this.display.showing(this.player.id));
    }

    @Test
    void onThePlayersOwnThreadTheBarShowsAtOnce() {
        this.tags.tag(this.player.id, null, TWENTY);
        this.threads.owning = true;
        this.display.show(this.player.player, 20, TWENTY);
        assertTrue(this.threads.queued.isEmpty());
        assertTrue(this.display.showing(this.player.id), "a new tag (the hit runs on the player's thread) shows the bar right away");
    }
}
