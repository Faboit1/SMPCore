package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dialog router is the one place every menu click passes (the pause-menu hub's static routes, every dialog
 * session, Bedrock forms), so a player frozen by staff is stopped there: no route or handler runs, they are told and
 * the dialog closes. A plain close button still works, and a refused click doesn't use up the dialog.
 */
class DialogsFreezeTest {

    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private Dialogs dialogs;
    private Fakes.FakePlayer suspect;

    @BeforeEach
    void setUp() {
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class), new Sounds());
        this.dialogs = new Dialogs(new Fakes.ImmediateScheduler(), messenger, Logger.getLogger("dialogs-test"));
        this.dialogs.freezes(this.frozen::contains);
        this.suspect = new Fakes.FakePlayer("Suspect");
    }

    /** A play-phase custom click, as the client sends it from a pause-menu button. */
    private static PlayerCustomClickEvent click(Player player, String id) {
        PlayerGameConnection connection = (PlayerGameConnection) Proxy.newProxyInstance(DialogsFreezeTest.class.getClassLoader(),
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

    @Test
    void pauseMenuRoutesRunForPlayersWhoAreNotFrozen() {
        AtomicInteger spawn = new AtomicInteger();
        this.dialogs.route("hub/spawn", player -> spawn.incrementAndGet());
        this.dialogs.onCustomClick(click(this.suspect.player, "siftcore:hub/spawn"));
        assertEquals(1, spawn.get());
    }

    @Test
    void aFrozenPlayerCantUseThePauseMenu() {
        AtomicInteger spawn = new AtomicInteger();
        AtomicInteger money = new AtomicInteger();
        this.dialogs.route("hub/spawn", player -> spawn.incrementAndGet());
        this.dialogs.route("hub/money", player -> money.incrementAndGet());
        this.frozen.add(this.suspect.id);
        this.dialogs.onCustomClick(click(this.suspect.player, "siftcore:hub/spawn"));
        this.dialogs.onCustomClick(click(this.suspect.player, "siftcore:hub/money"));
        assertEquals(0, spawn.get(), "the spawn route did not run");
        assertEquals(0, money.get(), "the money route did not run");
        assertTrue(this.suspect.said().contains("You can't do that while frozen."), this.suspect.said().toString());
        assertTrue(this.suspect.called("closeDialog"), "the dialog closed");
        assertEquals(2, this.dialogs.rejectedCount());
        assertEquals(0, this.dialogs.handledCount());

        this.frozen.remove(this.suspect.id);
        this.dialogs.onCustomClick(click(this.suspect.player, "siftcore:hub/spawn"));
        assertEquals(1, spawn.get(), "it works again once unfrozen");
    }

    @Test
    void aFrozenPlayerCantClickADialogButOnlyClose() {
        AtomicReference<FormBridge.Response> answer = new AtomicReference<>();
        this.dialogs.bedrock(new FormBridge() {
            @Override
            public boolean handles(Player player) {
                return true;
            }

            @Override
            public void show(Player player, View view, Response response) {
                answer.set(response);
            }
        });
        AtomicInteger paid = new AtomicInteger();
        View pay = new View(View.Kind.CONFIRM, Component.text("Pay"), List.of(), List.of(),
            List.of(Button.of(Component.text("Pay $1,000"), s -> paid.incrementAndGet()), Button.of(Component.text("Close"), null)),
            null, 1, true);
        this.dialogs.show(this.suspect.player, pay);
        this.frozen.add(this.suspect.id);

        answer.get().answer(0, Map.of());
        assertEquals(0, paid.get(), "the handler did not run");
        assertTrue(this.suspect.said().contains("You can't do that while frozen."), this.suspect.said().toString());
        assertTrue(this.suspect.called("closeDialog"), "the dialog closed");

        this.frozen.remove(this.suspect.id);
        answer.get().answer(0, Map.of());
        assertEquals(1, paid.get(), "the refused click did not use the dialog up: it works once unfrozen");

        this.dialogs.show(this.suspect.player, pay);
        this.frozen.add(this.suspect.id);
        this.suspect.actionBar.clear();
        answer.get().answer(1, Map.of());
        assertFalse(this.suspect.said().contains("You can't do that while frozen."), "closing is allowed, without a message");
        assertEquals(1, paid.get());
    }
}
