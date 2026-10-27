package net.siftvanilla.siftcore.feature.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Request;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TpaRequestsTest {

    private static final Duration MINUTE = Duration.ofSeconds(60);
    private final UUID alex = new UUID(0, 1);
    private final UUID blair = new UUID(0, 2);
    private final UUID casey = new UUID(0, 3);
    private final UUID drew = new UUID(0, 4);
    private final long[] now = {10_000};
    private final TpaRequests store = new TpaRequests(() -> this.now[0]);

    @Test
    void severalSendersCanWaitForOneTarget() {
        this.store.add(this.alex, this.casey, Kind.TO_TARGET, MINUTE);
        this.now[0] += 1_000;
        this.store.add(this.blair, this.casey, Kind.TO_SENDER, MINUTE);
        List<Request> incoming = this.store.incoming(this.casey);
        assertEquals(List.of(this.alex, this.blair), incoming.stream().map(Request::sender).toList(), "oldest first");
        assertEquals(Kind.TO_SENDER, incoming.get(1).kind());
        assertEquals(1, this.store.outgoing(this.alex).size());
        assertTrue(this.store.incoming(this.alex).isEmpty());
    }

    @Test
    void moverAndDestinationFollowTheKind() {
        Request tpa = this.store.add(this.alex, this.blair, Kind.TO_TARGET, MINUTE).request();
        assertEquals(this.alex, tpa.mover());
        assertEquals(this.blair, tpa.destination());
        Request here = this.store.add(this.casey, this.blair, Kind.TO_SENDER, MINUTE).request();
        assertEquals(this.blair, here.mover());
        assertEquals(this.casey, here.destination());
    }

    @Test
    void aNewRequestFromTheSameSenderReplacesTheOld() {
        Request first = this.store.add(this.alex, this.casey, Kind.TO_TARGET, MINUTE).request();
        TpaRequests.Added second = this.store.add(this.alex, this.casey, Kind.TO_SENDER, MINUTE);
        assertEquals(first, second.replaced());
        assertNotEquals(first.id(), second.request().id());
        assertEquals(List.of(second.request()), this.store.incoming(this.casey));
        assertTrue(this.store.take(this.casey, this.alex, first.id()).isEmpty(), "an answer to the old request can't take the new one");
        assertEquals(second.request(), this.store.take(this.casey, this.alex, second.request().id()).orElseThrow());
        assertTrue(this.store.take(this.casey, this.alex).isEmpty(), "taken once only");
    }

    @Test
    void eachRequestExpiresOnItsOwn() {
        this.store.add(this.alex, this.casey, Kind.TO_TARGET, MINUTE);
        this.now[0] += 30_000;
        this.store.add(this.blair, this.casey, Kind.TO_TARGET, MINUTE);
        this.now[0] += 30_000;
        assertEquals(List.of(this.blair), this.store.incoming(this.casey).stream().map(Request::sender).toList(), "alex's request is 60s old");
        assertTrue(this.store.take(this.casey, this.alex).isEmpty(), "an expired request can't be accepted");
        this.now[0] += 29_999;
        assertEquals(1, this.store.incoming(this.casey).size());
        this.now[0] += 1;
        assertTrue(this.store.incoming(this.casey).isEmpty());
        List<Request> expired = this.store.expire();
        assertEquals(List.of(this.blair), expired.stream().map(Request::sender).toList(), "alex's was already removed by the take");
        assertEquals(0, this.store.size());
        assertTrue(this.store.expire().isEmpty());
    }

    @Test
    void expiredRequestsAreNotReportedAsReplaced() {
        this.store.add(this.alex, this.casey, Kind.TO_TARGET, MINUTE);
        this.now[0] += 61_000;
        assertNull(this.store.add(this.alex, this.casey, Kind.TO_TARGET, MINUTE).replaced());
    }

    @Test
    void leavingRemovesEveryRequestOfThatPlayer() {
        this.store.add(this.alex, this.casey, Kind.TO_TARGET, MINUTE);
        this.store.add(this.casey, this.drew, Kind.TO_TARGET, MINUTE);
        this.store.add(this.blair, this.drew, Kind.TO_TARGET, MINUTE);
        assertEquals(2, this.store.removeAll(this.casey).size());
        assertEquals(List.of(this.blair), this.store.incoming(this.drew).stream().map(Request::sender).toList());
        assertEquals(1, this.store.size());
    }

    @Test
    void cancellingAllOutgoing() {
        this.store.add(this.alex, this.blair, Kind.TO_TARGET, MINUTE);
        this.store.add(this.alex, this.casey, Kind.TO_SENDER, MINUTE);
        this.store.add(this.drew, this.casey, Kind.TO_TARGET, MINUTE);
        List<Request> cancelled = this.store.cancelAll(this.alex);
        assertEquals(List.of(this.blair, this.casey), cancelled.stream().map(Request::target).toList());
        assertTrue(this.store.outgoing(this.alex).isEmpty());
        assertEquals(1, this.store.size());
        assertTrue(this.store.cancelAll(this.alex).isEmpty());
    }

    @Test
    void timeLeftAndSelfRequests() {
        Request request = this.store.add(this.alex, this.blair, Kind.TO_TARGET, MINUTE).request();
        this.now[0] += 15_000;
        assertEquals(Duration.ofSeconds(45), request.left(this.now[0]));
        this.now[0] += 100_000;
        assertEquals(Duration.ZERO, request.left(this.now[0]));
        assertThrows(IllegalArgumentException.class, () -> this.store.add(this.alex, this.alex, Kind.TO_TARGET, MINUTE));
    }

    @Test
    void manyTargetsStayIndependent() {
        for (int i = 0; i < 100; i++) {
            this.store.add(new UUID(1, i), this.drew, Kind.TO_TARGET, MINUTE);
        }
        assertEquals(100, this.store.incoming(this.drew).size());
        assertTrue(this.store.take(this.drew, new UUID(1, 50)).isPresent());
        assertEquals(99, this.store.incoming(this.drew).size());
    }

    // ------------------------------------------------------------------ shipped files

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = TpaRequestsTest.class.getClassLoader().getResourceAsStream(resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void shippedConfigAndTextLoadCleanly() throws Exception {
        ConfigReader reader = new ConfigReader("features/tpa.yml", yaml("features/tpa.yml"));
        TpaSettings settings = TpaSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(MINUTE, settings.expireAfter());
        assertEquals(Duration.ofSeconds(3), settings.warmup());
        assertEquals(Duration.ofSeconds(5), settings.requestCooldown());

        Icons icons = new Icons(Icons.readIndex(TpaRequestsTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(TpaMessages.class);
        YamlConfiguration langYaml = yaml("lang/tpa.yml");
        assertEquals(List.of(), lang.load(langYaml, langYaml, "lang/tpa.yml"));
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("tpa.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
    }
}
