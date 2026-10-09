package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.DialogBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the router ends a click: a {@link Button#closes() closing} button closes its dialog at once even when the
 * dialog's other buttons lead on (no grace), a leading button still waits a moment for the next screen, and a click on
 * a dialog whose session is gone closes every screen, so a waiting dialog never leaves the player on its waiting
 * screen.
 */
class DialogsClickTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    /** Entity tasks run at once; delayed ones wait for {@link #runLater()}. */
    private final List<Runnable> later = new ArrayList<>();
    private Dialogs dialogs;
    private Fakes.FakePlayer player;

    @BeforeEach
    void setUp() {
        Fakes.ImmediateScheduler immediate = new Fakes.ImmediateScheduler();
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Scheduler.class},
            (proxy, method, args) -> {
                if (method.getName().equals("entityLater")) {
                    this.later.add((Runnable) args[1]);
                    return Task.NONE;
                }
                try {
                    return method.invoke(immediate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class), new Sounds());
        this.dialogs = new Dialogs(scheduler, messenger, Logger.getLogger("dialogs-test"), this.now::get);
        this.player = new Fakes.FakePlayer("Clicker");
    }

    private void runLater() {
        List<Runnable> due = new ArrayList<>(this.later);
        this.later.clear();
        due.forEach(Runnable::run);
    }

    private long calls(String method) {
        return this.player.calls.stream().filter(method::equals).count();
    }

    /** A list with a finishing button, a button that leads on, and a Back footer: it renders without a client close. */
    private static View mixed(Button.Handler finish, Button.Handler next) {
        return new View(View.Kind.LIST, Component.text("Mixed"), List.of(), List.of(),
            List.of(Button.of(Component.text("Finish"), finish).closes(), Button.of(Component.text("Next"), next)),
            Button.of(Component.text("Back"), s -> { }), 1, true);
    }

    private static PlayerCustomClickEvent click(Player player, String id) {
        PlayerGameConnection connection = (PlayerGameConnection) Proxy.newProxyInstance(DialogsClickTest.class.getClassLoader(),
            new Class<?>[] {PlayerGameConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getPlayer" -> player;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> Fakes.defaultValue(method.getReturnType());
            });
        return new PlayerCustomClickEvent(Key.key(id), connection) {
            @Override
            public BinaryTagHolder getTag() {
                return null;
            }

            @Override
            public DialogResponseView getDialogResponseView() {
                return null;
            }
        };
    }

    private void clickToken(long token, int button) {
        this.dialogs.onCustomClick(click(this.player.player, "siftcore:ui/" + Long.toString(token, 36) + "/" + button));
    }

    @Test
    void aMixedDialogRendersWithoutAClientClose() {
        View view = mixed(s -> { }, s -> { });
        assertEquals(DialogBase.DialogAfterAction.NONE, Dialogs.afterAction(view.allButtons()));
        assertEquals(DialogBase.DialogAfterAction.CLOSE, Dialogs.afterAction(view.closing().allButtons()),
            "a dialog whose buttons all close closes on the client");
        assertEquals(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, Dialogs.afterAction(view.waiting().allButtons()));
    }

    @Test
    void aClosingButtonClosesItsDialogAtOnce() {
        AtomicInteger finished = new AtomicInteger();
        long token = this.dialogs.register(this.player.player, mixed(s -> finished.incrementAndGet(), s -> { }), false);
        this.dialogs.dispatch(this.player.player, token, 0, Map.of());
        assertEquals(1, finished.get());
        assertEquals(1, calls("closeDialog"), "closed in the same tick, without waiting for the grace");
        assertEquals(1, calls("closeInventory"));
        assertTrue(this.later.isEmpty(), "no delayed close is left behind");
    }

    @Test
    void aButtonThatLeadsOnWaitsForTheNextScreen() {
        long token = this.dialogs.register(this.player.player, mixed(s -> { }, s -> { }), false);
        this.dialogs.dispatch(this.player.player, token, 1, Map.of());
        assertEquals(0, calls("closeDialog"), "the dialog stays while the next screen may still load");
        runLater();
        assertEquals(1, calls("closeDialog"), "nothing came, so the router closes it after the grace");
    }

    @Test
    void aClosingButtonWhoseHandlerShowsSomethingLeavesIt() {
        long token = this.dialogs.register(this.player.player, mixed(s -> this.dialogs.markShown(s.player()), s -> { }), false);
        this.dialogs.dispatch(this.player.player, token, 0, Map.of());
        runLater();
        assertEquals(0, calls("closeDialog"), "what the handler opened is not closed");
    }

    @Test
    void anExpiredClickClosesEveryScreen() {
        long token = this.dialogs.register(this.player.player, mixed(s -> { }, s -> { }).waiting(), false);
        this.now.addAndGet(15 * 60 * 1000L + 1);
        clickToken(token, 0);
        assertTrue(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
        assertEquals(1, calls("closeDialog"));
        assertEquals(1, calls("closeInventory"), "the container close also leaves the waiting screen");
    }

    @Test
    void anUnknownTokenIsToldItExpiredAndClosesEveryScreen() {
        clickToken(123_456_789L, 0);
        assertTrue(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
        assertEquals(1, calls("closeDialog"));
        assertEquals(1, calls("closeInventory"));
        assertEquals(1, this.dialogs.rejectedCount());
    }

    @Test
    void anOldWaitingDialogClickedAgainLeavesItsWaitingScreen() {
        long token = this.dialogs.register(this.player.player, mixed(Submission::close, s -> { }).waiting(), false);
        clickToken(token, 0);
        this.player.calls.clear();
        this.now.addAndGet(6_000);
        clickToken(token, 0);
        assertTrue(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
        assertEquals(1, calls("closeDialog"));
        assertEquals(1, calls("closeInventory"), "a waiting dialog clicked again is on its waiting screen");
    }

    @Test
    void anOldDialogClickedAgainOnlyClosesTheDialog() {
        long token = this.dialogs.register(this.player.player, mixed(Submission::close, s -> { }), true);
        clickToken(token, 0);
        this.player.calls.clear();
        this.now.addAndGet(6_000);
        clickToken(token, 0);
        assertTrue(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
        assertEquals(1, calls("closeDialog"));
        assertEquals(0, calls("closeInventory"), "the screen under a dialog that stays on screen is left alone");
    }

    @Test
    void aSecondClickRightAfterTheFirstIsIgnoredSilently() {
        AtomicInteger finished = new AtomicInteger();
        long token = this.dialogs.register(this.player.player, mixed(s -> finished.incrementAndGet(), s -> { }).waiting(), false);
        clickToken(token, 0);
        this.player.calls.clear();
        this.now.addAndGet(1_000);
        clickToken(token, 0);
        assertEquals(1, finished.get());
        assertFalse(this.player.said().contains("That menu expired. Open it again."));
        assertEquals(0, calls("closeDialog"));
    }

    @Test
    void aChatAnswerStillWorksAfterBrowsingManyMenus() {
        AtomicInteger accepted = new AtomicInteger();
        long answer = this.dialogs.register(this.player.player, mixed(s -> accepted.incrementAndGet(), s -> { }).closing(), true);
        for (int i = 0; i < 20; i++) {
            this.dialogs.register(this.player.player, mixed(s -> { }, s -> { }), false);
        }
        clickToken(answer, 0);
        assertEquals(1, accepted.get(), "Accept from chat ran");
        assertFalse(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
    }

    @Test
    void aChatAnswerStillWorksAfterTheScreensTimeToLive() {
        AtomicInteger joined = new AtomicInteger();
        long invite = this.dialogs.register(this.player.player, mixed(s -> joined.incrementAndGet(), s -> { }).closing(), true);
        long screen = this.dialogs.register(this.player.player, mixed(s -> { }, s -> { }), false);
        this.now.addAndGet(30 * 60 * 1000L);
        clickToken(screen, 1);
        assertTrue(this.player.said().contains("That menu expired. Open it again."), "a screen opened 30 minutes ago expired");
        this.player.actionBar.clear();
        this.player.chat.clear();
        clickToken(invite, 0);
        assertEquals(1, joined.get(), "Join from a 30-minute-old team invite in chat ran");
        assertFalse(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
    }

    @Test
    void aChatAnswerExpiresAfterAnHour() {
        AtomicInteger joined = new AtomicInteger();
        long invite = this.dialogs.register(this.player.player, mixed(s -> joined.incrementAndGet(), s -> { }).closing(), true);
        this.now.addAndGet(Dialogs.CHAT_TTL_MILLIS + 1);
        clickToken(invite, 0);
        assertEquals(0, joined.get());
        assertTrue(this.player.said().contains("That menu expired. Open it again."), this.player.said().toString());
    }
}
