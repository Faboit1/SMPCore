package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The messenger renders every message for its recipient: a payment told to two players reads in each one's money
 * format, whoever's screen or command caused it, and the console reads the server's way.
 */
class MessengerMoneyTest {

    static final class Keys {
        static final MessageKey RECEIVED = MessageKey.chat("test.messenger.received", "amount");
        static final MessageKey SOLD = MessageKey.success("test.messenger.sold", "total");
        static final MessageKey TITLE = MessageKey.title("test.messenger.title", "amount");
    }

    private static final long AMOUNT = 1_234_567L;

    private Lang lang;
    private Messenger messenger;
    private Fakes.FakePlayer full;
    private Fakes.FakePlayer brief;
    private Fakes.FakePlayer usual;

    @BeforeEach
    void setUp() {
        this.lang = new Lang(new TextStyle(Palette.defaults(), new Icons(Set.of())), MoneyFormat::defaults);
        this.lang.register(Keys.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("test.messenger.received", "<primary>You got <amount>.");
        yaml.set("test.messenger.sold", "<primary>Sold for <total>.");
        yaml.set("test.messenger.title", "<amount>");
        assertEquals(List.of(), this.lang.load(yaml, yaml, "test.yml"));
        this.full = new Fakes.FakePlayer("Full");
        this.brief = new Fakes.FakePlayer("Brief");
        this.usual = new Fakes.FakePlayer("Usual");
        this.lang.viewers(Map.of(this.full.id, MoneyStyle.FULL, this.brief.id, MoneyStyle.SHORT)::get);
        this.messenger = new Messenger(this.lang, new Sounds());
    }

    @Test
    void eachRecipientReadsTheirOwnFormatWhoeverCausedIt() {
        // Sent while the payer's screen renders (the payer chose short): the receivers still read their own format.
        this.lang.viewing(this.brief.id, () -> {
            this.messenger.send(this.full.player, Keys.RECEIVED, Arg.money("amount", AMOUNT));
            this.messenger.send(this.usual.player, Keys.RECEIVED, Arg.money("amount", AMOUNT));
            this.messenger.send(this.brief.player, Keys.RECEIVED, Arg.money("amount", AMOUNT));
            return null;
        });
        assertEquals(List.of("You got $1,234,567."), this.full.said());
        assertEquals(List.of("You got $1.23m."), this.usual.said());
        assertEquals(List.of("You got $1.2m."), this.brief.said());
    }

    @Test
    void chatActionBarAlertsAndTitlesAllFollowTheRecipient() {
        this.messenger.chat(this.brief.player, Keys.RECEIVED, Arg.money("amount", AMOUNT));
        this.messenger.actionbar(this.brief.player, Keys.SOLD, Arg.money("total", 15_500));
        this.messenger.alert(this.brief.player, AlertStyle.CHAT, Keys.SOLD, Arg.money("total", AMOUNT));
        assertEquals(List.of("Sold for $15.5k.", "You got $1.2m.", "Sold for $1.2m."), this.brief.said());
        Component[] title = new Component[1];
        Audience recorder = (Audience) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {org.bukkit.entity.Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("getUniqueId")) {
                    return this.full.id;
                }
                if (method.getName().equals("showTitle")) {
                    title[0] = ((net.kyori.adventure.title.Title) args[0]).title();
                    return null;
                }
                return Fakes.defaultValue(method.getReturnType());
            });
        this.messenger.title(recorder, Keys.TITLE, null, Arg.money("amount", AMOUNT));
        assertEquals("$1,234,567", TextStyle.plain(title[0]));
    }

    @Test
    void theConsoleReadsTheServersWayEvenInsideAPlayersScope() {
        List<Component> console = new ArrayList<>();
        Audience consoleLike = new Audience() {
            @Override
            public void sendMessage(Component message) {
                console.add(message);
            }
        };
        this.lang.viewing(this.full.id, () -> {
            this.messenger.send(consoleLike, Keys.RECEIVED, Arg.money("amount", AMOUNT));
            return null;
        });
        assertEquals("You got $1.23m.", TextStyle.plain(console.getFirst()));
        assertEquals("You got $1.2m.", TextStyle.plain(this.messenger.render(this.brief.player, Keys.RECEIVED, Arg.money("amount", AMOUNT))));
    }
}
