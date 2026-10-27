package net.siftvanilla.siftcore.core.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.title.Title;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.teleport.TeleportDisplay.Place;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Teleport countdown" (teleport-display): where the warmup countdown and the arrival line show, as a pure rule and
 * through the shared teleports. Cancel messages are not affected.
 */
class TeleportDisplayTest {

    private Fakes.ImmediateScheduler scheduler;
    private PlayerSettings settings;
    private Teleports teleports;
    private Fakes.FakePlayer player;
    private final Location away = new Location(Fakes.world("world"), 200.5, 70, 200.5);

    @BeforeEach
    void setUp() {
        this.scheduler = new Fakes.ImmediateScheduler();
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class, TeleportMessages.class), new Sounds());
        this.settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        this.teleports = new Teleports(this.scheduler, messenger, CombatStatus.NONE, this.settings);
        this.player = new Fakes.FakePlayer("Traveller");
    }

    /** Everyone reads {@code style} (the server default, so no storage is needed). */
    private void display(AlertStyle style) {
        this.settings.overrides(new Overrides(Map.of(Teleports.DISPLAY.id(), style.id()), Map.of(), Set.of()));
    }

    private AtomicReference<Boolean> warmUpAndGo(boolean announces) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.player.player, "home", Duration.ofSeconds(3), () -> CompletableFuture.completedFuture(this.away),
            result::set, announces);
        for (int second = 0; second < 5; second++) {
            this.scheduler.tick();
        }
        return result;
    }

    private long titles() {
        return this.player.calls.stream().filter("showTitle"::equals).count();
    }

    @Test
    void theRules() {
        assertEquals(Place.STATUS, TeleportDisplay.countdown(AlertStyle.ACTIONBAR, true));
        assertEquals(Place.STATUS, TeleportDisplay.countdown(AlertStyle.ACTIONBAR, false));
        assertEquals(Place.TITLE, TeleportDisplay.countdown(AlertStyle.TITLE, false));
        assertEquals(Place.CHAT, TeleportDisplay.countdown(AlertStyle.CHAT, true));
        assertEquals(Place.NONE, TeleportDisplay.countdown(AlertStyle.CHAT, false), "one chat line, not one per second");
        assertEquals(Place.NONE, TeleportDisplay.countdown(AlertStyle.OFF, true));
        assertEquals(Place.FEEDBACK, TeleportDisplay.arrival(AlertStyle.ACTIONBAR, false));
        assertEquals(Place.TITLE, TeleportDisplay.arrival(AlertStyle.TITLE, false));
        assertEquals(Place.CHAT, TeleportDisplay.arrival(AlertStyle.CHAT, true));
        assertEquals(Place.NONE, TeleportDisplay.arrival(AlertStyle.OFF, false));
        assertEquals(Place.FEEDBACK, TeleportDisplay.arrival(AlertStyle.OFF, true), "a line about money still shows");
    }

    @Test
    void theSettingIsRegisteredOnceInTheTeleportGroup() {
        new Teleports(this.scheduler, null, CombatStatus.NONE, this.settings);
        Registry.Entry<?> entry = this.settings.registry().entry("teleport-display");
        assertEquals("teleport", entry.category().id());
        assertEquals(List.of("actionbar", "title", "chat", "off"), Teleports.DISPLAY.optionIds());
        assertEquals(AlertStyle.ACTIONBAR, Teleports.DISPLAY.defaultValue());
    }

    @Test
    void byDefaultTheCountdownAndArrivalAreAboveTheHotbar() {
        AtomicReference<Boolean> result = warmUpAndGo(false);
        assertEquals(Boolean.TRUE, result.get());
        assertEquals(List.of("Teleporting in 3s. Don't move.", "Teleporting in 2s. Don't move.", "Teleporting in 1s. Don't move.",
            "Teleported."), this.player.said());
        assertTrue(this.player.chat.isEmpty());
        assertEquals(0, titles());
    }

    @Test
    void asATitleNothingIsSentToChatOrTheHotbar() {
        display(AlertStyle.TITLE);
        assertEquals(Boolean.TRUE, warmUpAndGo(false).get());
        assertEquals(4, titles(), "three seconds of countdown and the arrival");
        assertTrue(this.player.said().isEmpty(), this.player.said().toString());
    }

    @Test
    void aTitleCountdownChangesTheNumberInPlace() {
        Title.Times first = TeleportDisplay.countdownTimes(true);
        Title.Times next = TeleportDisplay.countdownTimes(false);
        assertTrue(first.fadeIn().toMillis() > 0, "the countdown fades in once");
        assertEquals(Duration.ZERO, next.fadeIn(), "later seconds don't fade in again (no pulsing number)");
        assertTrue(next.stay().toMillis() > 1_000, "each second meets the next one, so the count never blinks out");
        assertTrue(next.stay().plus(next.fadeOut()).toMillis() < 2_500,
            "the last second leaves soon after the warmup, not seconds later");
    }

    /** Starts a 3 second warmup and runs its first two seconds (the countdown shows 3s and 2s). */
    private AtomicReference<Boolean> twoSecondsOfWarmup(java.util.function.Supplier<CompletableFuture<Location>> destination) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.player.player, "home", Duration.ofSeconds(3), destination, result::set);
        this.scheduler.tick();
        this.scheduler.tick();
        return result;
    }

    @Test
    void movingTakesTheCountdownTitleAwayBeforeTheCancelLine() {
        display(AlertStyle.TITLE);
        AtomicReference<Boolean> result = twoSecondsOfWarmup(() -> CompletableFuture.completedFuture(this.away));
        assertEquals(2, titles());
        Location here = this.player.location;
        this.teleports.onMove(new PlayerMoveEvent(this.player.player, here, here.clone().add(2, 0, 0)));
        assertEquals(Boolean.FALSE, result.get());
        assertTrue(this.player.called("clearTitle"), "the countdown title must not sit over the cancel line");
        assertTrue(this.player.calls.lastIndexOf("clearTitle") > this.player.calls.lastIndexOf("showTitle"));
        assertEquals(List.of("Teleport cancelled because you moved."), this.player.said());
    }

    @Test
    void aCancelledTeleportClearsItsTitle() {
        display(AlertStyle.TITLE);
        AtomicReference<Boolean> result = twoSecondsOfWarmup(() -> CompletableFuture.completedFuture(this.away));
        assertTrue(this.teleports.cancel(this.player.id));
        assertEquals(Boolean.FALSE, result.get());
        assertTrue(this.player.called("clearTitle"));
    }

    @Test
    void aFailureAfterTheWarmupClearsTheTitle() {
        display(AlertStyle.TITLE);
        AtomicReference<Boolean> result = twoSecondsOfWarmup(() -> CompletableFuture.failedFuture(new IllegalStateException()));
        this.scheduler.tick();
        this.scheduler.tick();
        assertEquals(Boolean.FALSE, result.get());
        assertTrue(this.player.called("clearTitle"));
        assertEquals(List.of("That teleport didn't work. Try again."), this.player.said());
    }

    @Test
    void aDestinationThatSaysNoClearsTheTitle() {
        display(AlertStyle.TITLE);
        AtomicReference<Boolean> result = twoSecondsOfWarmup(() -> CompletableFuture.completedFuture(null));
        this.scheduler.tick();
        this.scheduler.tick();
        assertEquals(Boolean.FALSE, result.get());
        assertTrue(this.player.called("clearTitle"), "the other player left: their message, then no countdown left on screen");
    }

    @Test
    void anArrivalReplacesTheTitleWithoutClearingIt() {
        display(AlertStyle.TITLE);
        assertEquals(Boolean.TRUE, warmUpAndGo(false).get());
        assertFalse(this.player.called("clearTitle"), "the arrival title replaces the countdown");
    }

    @Test
    void otherDisplaysNeverTouchTitles() {
        AtomicReference<Boolean> result = twoSecondsOfWarmup(() -> CompletableFuture.completedFuture(this.away));
        Location here = this.player.location;
        this.teleports.onMove(new PlayerMoveEvent(this.player.player, here, here.clone().add(2, 0, 0)));
        assertEquals(Boolean.FALSE, result.get());
        assertFalse(this.player.called("clearTitle"), "a title from something else stays");
    }

    @Test
    void inChatTheCountdownIsOneLine() {
        display(AlertStyle.CHAT);
        assertEquals(Boolean.TRUE, warmUpAndGo(false).get());
        assertTrue(this.player.actionBar.isEmpty());
        assertEquals(List.of("Teleporting in 3s. Don't move.", "Teleported."), this.player.said());
    }

    @Test
    void offShowsNothingButStillTeleports() {
        display(AlertStyle.OFF);
        assertEquals(Boolean.TRUE, warmUpAndGo(false).get());
        assertEquals(List.of(this.away), this.player.teleports);
        assertTrue(this.player.said().isEmpty(), this.player.said().toString());
        assertEquals(0, titles());
    }

    @Test
    void offStillShowsALineAboutMoney() {
        display(AlertStyle.OFF);
        this.teleports.arrival(this.player.player, true, TeleportMessages.FAILED);
        this.teleports.arrival(this.player.player, TeleportMessages.DONE);
        assertEquals(List.of("That teleport didn't work. Try again."), this.player.said(), "only the essential line");
    }

    @Test
    void failuresShowWhateverTheDisplay() {
        display(AlertStyle.OFF);
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.player.player, "home", Duration.ZERO, () -> CompletableFuture.failedFuture(new IllegalStateException()),
            result::set);
        assertEquals(Boolean.FALSE, result.get());
        assertEquals(List.of("That teleport didn't work. Try again."), this.player.said());
    }

    @Test
    void aFeatureThatAnnouncesItsOwnArrivalGetsNoTeleportedLine() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        this.teleports.teleport(this.player.player, "home", Duration.ZERO, () -> CompletableFuture.completedFuture(this.away), ok -> {
            result.set(ok);
            this.teleports.arrival(this.player.player, TeleportMessages.WARMUP, Arg.time("time", Duration.ofSeconds(9)));
        }, true);
        assertEquals(Boolean.TRUE, result.get());
        assertEquals(List.of("Teleporting in 9s. Don't move."), this.player.said(), "the feature's line, no Teleported.");
        assertFalse(this.player.said().contains("Teleported."));
    }
}
