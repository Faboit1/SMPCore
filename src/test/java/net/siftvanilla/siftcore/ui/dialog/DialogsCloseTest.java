package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

/**
 * What the router closes after a click whose handler shows nothing of SiftCore's: the dialog and the screen under it,
 * but never a container another plugin opened in the meantime (AxAuctions' menu after the main menu's auction button).
 */
class DialogsCloseTest {

    /** A player whose open container and close calls are recorded, built as a proxy (no server needed). */
    private static final class FakePlayer {
        final Inventory crafting = inventory("crafting");
        Inventory top = this.crafting;
        int dialogCloses;
        int containerCloses;
        final Player player;

        FakePlayer() {
            UUID id = UUID.randomUUID();
            InventoryView view = (InventoryView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {InventoryView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> this.top;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
            this.player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Tester";
                    case "isOnline" -> true;
                    case "getOpenInventory" -> view;
                    case "closeDialog" -> {
                        this.dialogCloses++;
                        yield null;
                    }
                    case "closeInventory" -> {
                        this.containerCloses++;
                        this.top = this.crafting;
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlayer";
                    default -> null;
                });
        }
    }

    private static Inventory inventory(String name) {
        return (Inventory) Proxy.newProxyInstance(DialogsCloseTest.class.getClassLoader(), new Class<?>[] {Inventory.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> name;
                default -> null;
            });
    }

    /** Runs entity tasks at once on the "player's thread" and keeps delayed ones until {@link #runLater()}. */
    private final List<Runnable> later = new ArrayList<>();
    private final Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Scheduler.class},
        (proxy, method, args) -> switch (method.getName()) {
            case "owns" -> true;
            case "entity" -> {
                ((Runnable) args[1]).run();
                yield Task.NONE;
            }
            case "entityLater" -> {
                this.later.add((Runnable) args[1]);
                yield Task.NONE;
            }
            default -> null;
        });
    private final Dialogs dialogs = new Dialogs(this.scheduler, null, Logger.getAnonymousLogger());

    private void runLater() {
        List<Runnable> due = new ArrayList<>(this.later);
        this.later.clear();
        due.forEach(Runnable::run);
    }

    @Test
    void aMenuAnotherPluginOpenedIsNotClosed() {
        FakePlayer fake = new FakePlayer();
        Inventory auctions = inventory("AxAuctions menu");
        this.dialogs.runRoute(fake.player, player -> fake.top = auctions);
        runLater();
        assertEquals(1, fake.dialogCloses, "the dialog is still cleared (the client ignores it over a container)");
        assertEquals(0, fake.containerCloses, "the menu the click opened stays open");
        assertTrue(fake.top == auctions);
    }

    @Test
    void aClickThatShowsNothingClosesEveryScreen() {
        FakePlayer fake = new FakePlayer();
        this.dialogs.runRoute(fake.player, player -> { });
        runLater();
        assertEquals(1, fake.dialogCloses);
        assertEquals(1, fake.containerCloses, "the container close also leaves a waiting screen");
    }

    @Test
    void theMenuUnderTheDialogIsClosedWhenNothingNewOpened() {
        FakePlayer fake = new FakePlayer();
        Inventory chest = inventory("SiftCore menu under the dialog");
        fake.top = chest;
        this.dialogs.runRoute(fake.player, player -> { });
        runLater();
        assertEquals(1, fake.dialogCloses);
        assertEquals(1, fake.containerCloses);
    }

    @Test
    void somethingShownIsLeftAlone() {
        FakePlayer fake = new FakePlayer();
        this.dialogs.runRoute(fake.player, this.dialogs::markShown);
        runLater();
        assertEquals(0, fake.dialogCloses);
        assertEquals(0, fake.containerCloses);
    }

    @Test
    void closeScreenClosesEverything() {
        FakePlayer fake = new FakePlayer();
        fake.top = inventory("any menu");
        this.dialogs.closeScreen(fake.player);
        assertEquals(1, fake.dialogCloses);
        assertEquals(1, fake.containerCloses);
    }
}
