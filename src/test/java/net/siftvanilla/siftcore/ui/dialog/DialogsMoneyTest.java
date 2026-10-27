package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Screens built after a wait (a database read, a stored crate opening) run outside the click or command that asked for
 * them, so no viewer's scope is open there: {@link Dialogs#show(Player, java.util.function.Supplier)} builds the view in
 * the player's scope, so its money follows their money format.
 */
class DialogsMoneyTest {

    private static final long AMOUNT = 1_234_567L;

    private Lang lang;
    private Dialogs dialogs;
    private Fakes.FakePlayer shortReader;
    private Fakes.FakePlayer fullReader;
    /** What each player was shown, as "name: title" (through a form bridge, so no client dialog is rendered). */
    private final List<String> shown = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.lang = Fakes.lang(List.of("lang/core.yml"), CoreMessages.class);
        this.shortReader = new Fakes.FakePlayer("Brief");
        this.fullReader = new Fakes.FakePlayer("Every");
        this.lang.viewers(id -> id.equals(this.shortReader.id) ? MoneyStyle.SHORT
            : id.equals(this.fullReader.id) ? MoneyStyle.FULL : MoneyStyle.SERVER);
        this.dialogs = new Dialogs(new Fakes.ImmediateScheduler(), new Messenger(this.lang, new Sounds()),
            Logger.getLogger("dialogs-money-test"), () -> 1_000L);
        this.dialogs.bedrock(new FormBridge() {
            @Override
            public boolean handles(Player player) {
                return true;
            }

            @Override
            public void show(Player player, View view, Response response) {
                DialogsMoneyTest.this.shown.add(player.getName() + ": " + TextStyle.plain(view.title()));
            }
        });
    }

    /** A screen whose title carries an amount of money, written the way the current scope says. */
    private View result() {
        return new View(View.Kind.LIST, Component.text("You won " + this.lang.money(AMOUNT)), List.of(), List.of(), List.of(),
            Button.of(Component.text("Back"), s -> { }), 1, true);
    }

    @Test
    void aViewBuiltAfterAWaitIsBuiltForThePlayerWhoSeesIt() {
        assertFalse(this.lang.inScope(), "a callback after a database read: no scope");
        this.dialogs.show(this.shortReader.player, this::result);
        this.dialogs.show(this.fullReader.player, this::result);
        assertEquals(List.of("Brief: You won $1.2m", "Every: You won $1,234,567"), this.shown, "each reader's own format");
        assertFalse(this.lang.inScope(), "the scope ends with the build");
        assertEquals("$1.23m", this.lang.money(AMOUNT), "and the server's way outside it again");
    }

    @Test
    void aViewBuiltBeforeItIsShownKeepsTheScopeItWasBuiltIn() {
        // The plain overload can't know whom the view was built for: built in no scope, it is the server's way. That is
        // what a screen built in a callback looked like before it used the supplier.
        this.dialogs.show(this.fullReader.player, result());
        assertEquals(List.of("Every: You won $1.23m"), this.shown);
    }

    @Test
    void theViewFollowsThePlayerWhoSeesItEvenInsideAnotherPlayersScope() {
        // Someone's command ending in a dialog for another player: the view follows the player who sees it, and the
        // outer scope comes back once it is built.
        String after = this.lang.viewing(this.fullReader.player, () -> {
            this.dialogs.show(this.shortReader.player, this::result);
            return this.lang.money(AMOUNT);
        });
        assertEquals(List.of("Brief: You won $1.2m"), this.shown);
        assertEquals("$1,234,567", after);
    }

    @Test
    void withoutAMessengerTheViewIsStillBuilt() {
        Dialogs bare = new Dialogs(new Fakes.ImmediateScheduler(), null, Logger.getLogger("dialogs-money-test"), () -> 1_000L);
        bare.bedrock(new FormBridge() {
            @Override
            public boolean handles(Player player) {
                return true;
            }

            @Override
            public void show(Player player, View view, Response response) {
                DialogsMoneyTest.this.shown.add(TextStyle.plain(view.title()));
            }
        });
        bare.show(this.shortReader.player, this::result);
        assertEquals(List.of("You won $1.23m"), this.shown);
    }
}
