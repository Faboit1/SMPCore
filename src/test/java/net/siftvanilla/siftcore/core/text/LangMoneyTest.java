package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Money written the way each reader chose: the viewer's scope, confirmations staying exact, pinned amounts, and
 * lines rendered once per format for many readers.
 */
class LangMoneyTest {

    static final class Keys {
        static final MessageKey PAID = MessageKey.chat("test.money.paid", "amount");
        static final MessageKey AMOUNT = MessageKey.chat("test.money.amount", "amount");
        static final MessageKey CONFIRM = MessageKey.ui("test.money.confirm-body", "amount", "left");
        static final MessageKey CONFIRM_SECTION = MessageKey.ui("test.confirm.body", "amount");
        static final MessageKey PINNED = MessageKey.ui("test.money.sample", "sample");
    }

    private static final long AMOUNT = 1_234_567L;
    private static final UUID FULL = UUID.randomUUID();
    private static final UUID SHORT = UUID.randomUUID();
    private static final UUID DEFAULT = UUID.randomUUID();

    private Lang lang;

    @BeforeEach
    void setUp() {
        this.lang = new Lang(new TextStyle(Palette.defaults(), new Icons(Set.of())), MoneyFormat::defaults);
        this.lang.register(Keys.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("test.money.paid", "<primary>You paid <amount>.");
        yaml.set("test.money.amount", "<amount>");
        yaml.set("test.money.confirm-body", "<primary>Pay <amount>? <amount> leaves you <left>.");
        yaml.set("test.confirm.body", "<amount>");
        yaml.set("test.money.sample", "<sample>");
        assertEquals(List.of(), this.lang.load(yaml, yaml, "test.yml"));
        this.lang.viewers(Map.of(FULL, MoneyStyle.FULL, SHORT, MoneyStyle.SHORT)::get);
    }

    private String plain(MessageKey key, Arg... args) {
        return this.lang.plain(key, args);
    }

    @Test
    void outsideAnyScopeMoneyIsTheServersWay() {
        assertEquals(MoneyStyle.SERVER, this.lang.moneyStyle());
        assertFalse(this.lang.inScope());
        assertEquals("You paid $1.23m.", plain(Keys.PAID, Arg.money("amount", AMOUNT)));
        assertEquals("$1.23m", this.lang.money(AMOUNT));
        assertEquals("$1,500", this.lang.money(1_500));
    }

    @Test
    void aViewersScopeWritesMoneyTheWayTheyChose() {
        assertEquals("You paid $1,234,567.", this.lang.viewing(FULL, () -> plain(Keys.PAID, Arg.money("amount", AMOUNT))));
        assertEquals("You paid $1.2m.", this.lang.viewing(SHORT, () -> plain(Keys.PAID, Arg.money("amount", AMOUNT))));
        assertEquals("You paid $1.23m.", this.lang.viewing(DEFAULT, () -> plain(Keys.PAID, Arg.money("amount", AMOUNT))),
            "a player who never chose reads the server's way");
        assertEquals("$1.5k", this.lang.viewing(SHORT, () -> this.lang.money(1_500)));
        assertEquals("$1,234,567", this.lang.viewing(FULL, () -> TextStyle.plain(this.lang.moneyComponent(AMOUNT))));
        assertFalse(this.lang.inScope(), "the scope ends with the call");
    }

    @Test
    void linesAndItemTextFollowTheScopeToo() {
        assertEquals("$1.2m", TextStyle.plain(this.lang.viewing(SHORT, () -> this.lang.lines(Keys.AMOUNT, Arg.money("amount", AMOUNT))).getFirst()));
        assertEquals("$1.2m", TextStyle.plain(this.lang.viewing(SHORT, () -> this.lang.item(Keys.AMOUNT, Arg.money("amount", AMOUNT)))));
    }

    @Test
    void moneyAmountsFollowAndShardsStayNumbers() {
        assertEquals("$1.2m", this.lang.viewing(SHORT, () -> plain(Keys.AMOUNT, Arg.amount("amount", Currency.MONEY, AMOUNT))));
        assertEquals("1,234,567", this.lang.viewing(SHORT, () -> plain(Keys.AMOUNT, Arg.amount("amount", Currency.SHARDS, AMOUNT))));
    }

    @Test
    void scopesNestAndTheInnermostWins() {
        String[] seen = new String[3];
        this.lang.viewing(FULL, () -> {
            seen[0] = this.lang.money(AMOUNT);
            seen[1] = this.lang.viewing(SHORT, () -> this.lang.money(AMOUNT));
            seen[2] = this.lang.money(AMOUNT);
            return null;
        });
        assertEquals(List.of("$1,234,567", "$1.2m", "$1,234,567"), List.of(seen));
        assertEquals("$1.23m", this.lang.viewing(SHORT, () -> this.lang.asServer(() -> this.lang.money(AMOUNT))),
            "asServer is the server's way inside anyone's scope");
    }

    @Test
    void aFailedRenderStillEndsTheScope() {
        assertThrows(IllegalStateException.class, () -> this.lang.viewing(SHORT, () -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(this.lang.inScope());
        assertEquals("$1.23m", this.lang.money(AMOUNT));
    }

    @Test
    void anOpenedScopeLastsUntilClosed() {
        try (Lang.Scope _ = this.lang.open(player(SHORT))) {
            assertTrue(this.lang.inScope());
            assertEquals("$1.2m", this.lang.money(AMOUNT));
        }
        assertFalse(this.lang.inScope());
        try (Lang.Scope _ = this.lang.open(Audience.empty())) {
            assertEquals(MoneyStyle.SERVER, this.lang.moneyStyle(), "the console reads the server's way");
        }
    }

    @Test
    void theScopeBelongsToItsThread() {
        String other = this.lang.viewing(SHORT, () -> CompletableFuture.supplyAsync(() -> this.lang.money(AMOUNT)).join());
        assertEquals("$1.23m", other, "another thread renders outside the viewer's scope");
    }

    @Test
    void confirmationsWriteEveryDigitWhateverTheReaderChose() {
        for (UUID reader : List.of(SHORT, FULL, DEFAULT)) {
            assertEquals("Pay $1,234,567? $1,234,567 leaves you $5,000,000.", this.lang.viewing(reader,
                () -> plain(Keys.CONFIRM, Arg.money("amount", AMOUNT), Arg.money("left", 5_000_000))));
            assertEquals("$1,234,567", this.lang.viewing(reader, () -> plain(Keys.CONFIRM_SECTION, Arg.amount("amount", Currency.MONEY, AMOUNT))));
        }
        assertEquals("Pay $1,234,567? $1,234,567 leaves you $5,000,000.",
            plain(Keys.CONFIRM, Arg.money("amount", AMOUNT), Arg.money("left", 5_000_000)), "also outside any scope");
    }

    @Test
    void exactAndPinnedAmountsKeepTheirFormat() {
        assertEquals("$1,234,567", this.lang.viewing(SHORT, () -> plain(Keys.AMOUNT, Arg.exact("amount", AMOUNT))));
        assertEquals("$1.2m", this.lang.viewing(FULL, () -> plain(Keys.PINNED, Arg.money("sample", AMOUNT, MoneyStyle.SHORT))));
        assertEquals("$1.23m", this.lang.viewing(SHORT, () -> plain(Keys.PINNED, Arg.money("sample", AMOUNT, MoneyStyle.SERVER))));
        assertEquals("$1,234,567", this.lang.moneyExact(AMOUNT));
        assertEquals("$1.2m", this.lang.money(AMOUNT, MoneyStyle.SHORT));
    }

    @Test
    void manyReadersGetTheirOwnCopyRenderedOncePerFormat() {
        AtomicInteger renders = new AtomicInteger();
        Function<Audience, Component> line = this.lang.perViewer(() -> {
            renders.incrementAndGet();
            return this.lang.get(Keys.PAID, Arg.money("amount", AMOUNT));
        });
        List<Audience> readers = List.of(player(SHORT), player(FULL), player(SHORT), player(DEFAULT), Audience.empty(), player(FULL));
        List<String> seen = readers.stream().map(reader -> TextStyle.plain(line.apply(reader))).toList();
        assertEquals(List.of("You paid $1.2m.", "You paid $1,234,567.", "You paid $1.2m.", "You paid $1.23m.", "You paid $1.23m.",
            "You paid $1,234,567."), seen);
        assertEquals(3, renders.get(), "one render per format");
    }

    @Test
    void aReaderWhoIsNoPlayerReadsTheServersWay() {
        assertEquals(MoneyStyle.SERVER, this.lang.styleOf(Audience.empty()));
        assertEquals(MoneyStyle.SERVER, this.lang.styleOf((UUID) null));
        assertEquals(MoneyStyle.SHORT, this.lang.styleOf(player(SHORT)));
    }

    @Test
    void aBrokenBindingFallsBackToTheServersWay() {
        this.lang.viewers(id -> {
            throw new IllegalStateException("settings not ready");
        });
        assertEquals(MoneyStyle.SERVER, this.lang.styleOf(SHORT));
        this.lang.viewers(id -> null);
        assertEquals(MoneyStyle.SERVER, this.lang.styleOf(SHORT));
        this.lang.viewers(null);
        assertEquals(MoneyStyle.SERVER, this.lang.styleOf(SHORT));
    }

    @Test
    void aLangWithoutAMoneyFormatYetUsesTheDefaults() {
        Lang early = Fakes.lang();
        assertEquals("$1.23m", early.money(AMOUNT));
        assertEquals("$1,234,567", early.moneyExact(AMOUNT));
    }

    @ParameterizedTest
    @CsvSource({
        "shop.confirm.body, true",
        "economy.pay.confirm-body, true",
        "auction.buy.confirm-title, true",
        "sell.confirm.sell, true",
        "staff.clearchat.confirm, true",
        "confirm.title, true",
        "economy.settings.pay-confirm-above.label, false",
        "settings.sell_all_confirm.label, false",
        "core.confirmation.body, false",
        "sell.unconfirmed, false",
        "economy.pay.sent, false"
    })
    void confirmationsAreKnownByTheirPath(String path, boolean confirmation) {
        assertEquals(confirmation, MessageKey.ui(path).confirmation(), path);
        assertEquals(confirmation, MessageKey.confirmationPath(path), path);
        assertTrue(Lang.styleFor(MessageKey.ui(path), MoneyStyle.SHORT) == (confirmation ? MoneyStyle.FULL : MoneyStyle.SHORT));
    }

    @Test
    void placeholdersWriteMoneyForThePlayerTheyAreAskedForWhateverScopeTheThreadIsIn() {
        org.bukkit.OfflinePlayer full = (org.bukkit.OfflinePlayer) player(FULL);
        org.bukkit.OfflinePlayer brief = (org.bukkit.OfflinePlayer) player(SHORT);
        org.bukkit.OfflinePlayer usual = (org.bukkit.OfflinePlayer) player(DEFAULT);
        assertEquals("$1,234,567", this.lang.moneyFor(full, AMOUNT));
        assertEquals("$1.2m", this.lang.moneyFor(brief, AMOUNT));
        assertEquals("$1.23m", this.lang.moneyFor(usual, AMOUNT), "a player who never chose");
        assertEquals("$1.23m", this.lang.moneyFor(null, AMOUNT), "no player: the server's way");
        // A placeholder resolved while another player's text renders (a sidebar line, a hologram) is still its own.
        assertEquals("$1.2m", this.lang.viewing(FULL, () -> this.lang.moneyFor(brief, AMOUNT)));
        assertEquals("$1.23m", this.lang.viewing(SHORT, () -> this.lang.moneyFor(null, AMOUNT)));
    }

    /** A player with an id (only the id is read). */
    private static Audience player(UUID id) {
        return (Audience) java.lang.reflect.Proxy.newProxyInstance(LangMoneyTest.class.getClassLoader(),
            new Class<?>[] {org.bukkit.entity.Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> Fakes.defaultValue(method.getReturnType());
            });
    }
}
