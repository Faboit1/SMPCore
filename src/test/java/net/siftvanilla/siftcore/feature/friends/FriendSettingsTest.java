package net.siftvanilla.siftcore.feature.friends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The friends settings in the shared registry: their ids and stored values (the same as before they were registered
 * settings, so old rows still read), their place in Friends &amp; teams, the favourites option that depends on config,
 * {@code /friend settings} going through the registry (locked, hidden, invalid), the list orders and who sees a
 * last-seen time.
 */
class FriendSettingsTest {

    private static final Logger LOGGER = Logger.getLogger("friends-settings-test");
    private static final UUID ALEX = UUID.randomUUID();

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;
    private final boolean[] favourites = {true};

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = FriendSettingsTest.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        FriendPrefs.register(this.settings, () -> this.favourites[0]);
        this.settings.load(ALEX).get();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private static Player player(UUID uuid, Set<String> nodes) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Alex";
                case "isOnline" -> true;
                case "hasPermission" -> args[0] instanceof String node && nodes.contains(node);
                case "hashCode" -> uuid.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player(" + uuid + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void idsAndStoredValuesStayTheSame() {
        assertEquals("friends-requests", FriendPrefs.REQUESTS.id());
        assertEquals(List.of("everyone", "known", "nobody"), FriendPrefs.REQUESTS.optionIds());
        assertEquals(Privacy.KNOWN, FriendPrefs.REQUESTS.decodeOrNull(" Known "), "old rows read leniently");
        assertNull(FriendPrefs.REQUESTS.decodeOrNull("sideways"));
        assertEquals("friends-join-alerts", FriendPrefs.JOIN_ALERTS.id());
        assertEquals(List.of("all", "favourites", "off"), FriendPrefs.JOIN_ALERTS.optionIds());
        assertEquals(FriendPrefs.JoinAlerts.OFF.id(), FriendPrefs.JOIN_ALERTS.option("favourites").unavailableAs(),
            "favourites only reads as off while favourites are turned off");
        assertEquals(List.of("status", "name", "last-seen", "oldest"), FriendPrefs.LIST_ORDER.optionIds());
        assertEquals("friends-leave-alerts", FriendPrefs.LEAVE_ALERTS.id());
        assertFalse(FriendPrefs.LEAVE_ALERTS.defaultOn());
        assertTrue(FriendPrefs.REQUEST_ALERTS.defaultOn() && FriendPrefs.ANNOUNCE.defaultOn() && FriendPrefs.JOIN_SUMMARY.defaultOn());
    }

    @Test
    void registeredInFriendsAndTeamsAtTheirCatalogPlaces() {
        Registry registry = this.settings.registry();
        Map<String, Integer> orders = Map.of("friends-requests", 1, "friends-join-alerts", 2, "friends-request-alerts", 4,
            "friends-announce", 7, "friends-join-summary", 8, "friends-leave-alerts", 9, "friends-list-order", 10);
        orders.forEach((id, order) -> {
            Registry.Entry<?> entry = registry.entry(id);
            assertEquals(SettingCategories.SOCIAL, entry.category(), id);
            assertEquals(order, entry.options().order(), id);
        });
        assertEquals(List.of("friends-requests", "friends-join-alerts", "friends-request-alerts", "friends-announce",
            "friends-join-summary", "friends-leave-alerts", "friends-list-order"),
            registry.in(SettingCategories.SOCIAL.id()).stream().map(Registry.Entry::id).toList(), "dialog order");
        assertFalse(registry.entry("friends-requests").placeholder(), "who-can settings stay out of placeholders");
        assertTrue(this.settings.hasReader(SharedSettings.SEEN_PRIVACY), "friend profiles read seen-privacy");
    }

    @Test
    void favouritesOnlyDependsOnTheConfig() throws Exception {
        UUID id = ALEX;
        this.settings.setRaw(id, FriendPrefs.JOIN_ALERTS.id(), "favourites");
        FriendPrefs prefs = new FriendPrefs(this.settings);
        assertEquals(FriendPrefs.JoinAlerts.FAVOURITES, prefs.joinAlerts(id));
        this.favourites[0] = false;
        assertEquals(FriendPrefs.JoinAlerts.OFF, prefs.joinAlerts(id), "limits.favourites 0: reads as what it does, off");
        Player alex = player(id, Set.of());
        assertEquals(List.of("all", "off"), prefs.values(alex, FriendPrefs.Key.JOIN_ALERTS), "not offered");
        assertEquals(SetResult.INVALID, prefs.set(alex, FriendPrefs.Key.JOIN_ALERTS, "favourites"));
        this.favourites[0] = true;
        assertEquals(FriendPrefs.JoinAlerts.FAVOURITES, prefs.joinAlerts(id), "the stored choice comes back with favourites");
    }

    @Test
    void friendSettingsCommandGoesThroughTheRegistry() {
        FriendPrefs prefs = new FriendPrefs(this.settings);
        Player alex = player(ALEX, Set.of());
        assertEquals("everyone", prefs.value(alex, FriendPrefs.Key.REQUESTS));
        assertEquals(SetResult.CHANGED, prefs.set(alex, FriendPrefs.Key.REQUESTS, " Nobody "));
        assertEquals(Privacy.NOBODY, prefs.privacy(ALEX));
        assertEquals(SetResult.UNCHANGED, prefs.set(alex, FriendPrefs.Key.REQUESTS, "nobody"));
        assertEquals(SetResult.INVALID, prefs.set(alex, FriendPrefs.Key.REQUESTS, "sideways"));
        assertEquals(List.of("on", "off"), prefs.values(alex, FriendPrefs.Key.LEAVE_ALERTS));
        assertEquals("off", prefs.value(alex, FriendPrefs.Key.LEAVE_ALERTS));
        assertEquals(SetResult.CHANGED, prefs.set(alex, FriendPrefs.Key.LEAVE_ALERTS, "on"));
        assertTrue(prefs.leaveAlerts(ALEX));
        assertEquals(SetResult.CHANGED, prefs.set(alex, FriendPrefs.Key.LIST_ORDER, "last-seen"));
        assertEquals(ListOrder.Sort.LAST_SEEN, prefs.listOrder(ALEX));
        assertEquals(SetResult.CHANGED, prefs.set(alex, FriendPrefs.Key.JOIN_SUMMARY, "off"));
        assertFalse(prefs.joinSummary(ALEX));

        this.settings.overrides(new Overrides(Map.of(), Map.of("friends-requests", "known"), Set.of()));
        assertEquals(SetResult.LOCKED, prefs.set(alex, FriendPrefs.Key.REQUESTS, "everyone"), "the server's lock refuses");
        assertEquals("known", prefs.value(alex, FriendPrefs.Key.REQUESTS));
        assertTrue(prefs.locked(FriendPrefs.Key.REQUESTS));

        this.settings.overrides(new Overrides(Map.of(), Map.of(), Set.of("friends-announce")));
        assertEquals(List.of(), prefs.values(alex, FriendPrefs.Key.ANNOUNCE), "a hidden setting takes no values");
        assertEquals(SetResult.NOT_ALLOWED, prefs.set(alex, FriendPrefs.Key.ANNOUNCE, "off"), "a hidden setting is refused");
        assertTrue(prefs.announce(ALEX), "and keeps the server's value");
    }

    @Test
    void listOrders() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<ListOrder.Row> rows = List.of(
            new ListOrder.Row(a, "alex", false, ListOrder.Status.OFFLINE, 5_000, 300),
            new ListOrder.Row(b, "Bea", true, ListOrder.Status.OFFLINE, 9_000, 100),
            new ListOrder.Row(c, "cara", false, ListOrder.Status.ONLINE, 0, 400),
            new ListOrder.Row(d, "Dan", false, ListOrder.Status.AFK, 0, 200));
        assertEquals(List.of(c, d, b, a), ids(ListOrder.sort(rows, ListOrder.Sort.STATUS)), "online by name, then favourites");
        assertEquals(List.of(a, b, c, d), ids(ListOrder.sort(rows, ListOrder.Sort.NAME)), "A to Z, ignoring case");
        assertEquals(List.of(c, d, b, a), ids(ListOrder.sort(rows, ListOrder.Sort.LAST_SEEN)), "online, then most recent");
        assertEquals(List.of(b, d, a, c), ids(ListOrder.sort(rows, ListOrder.Sort.OLDEST)), "longest friendships first");
        assertEquals(ids(ListOrder.sort(rows, ListOrder.Sort.STATUS)), ids(ListOrder.sort(rows)), "the default order");

        List<ListOrder.Row> hidden = ListOrder.hideSeen(rows, Set.of(b, c));
        assertEquals(0, hidden.get(1).lastSeen(), "an offline friend's hidden time is gone");
        assertEquals(rows.get(2), hidden.get(2), "online rows are left alone");
        assertEquals(List.of(c, d, a, b), ids(ListOrder.sort(hidden, ListOrder.Sort.LAST_SEEN)),
            "a hidden time sorts last, so the order doesn't give it away");
        assertEquals(rows, ListOrder.hideSeen(rows, Set.of()));
    }

    private static List<UUID> ids(List<ListOrder.Row> rows) {
        List<UUID> ids = new ArrayList<>();
        for (ListOrder.Row row : rows) {
            ids.add(row.id());
        }
        return ids;
    }

    @Test
    void whoSeesTheLastSeenTime() {
        assertFalse(SeenPrivacy.hides(Audience.EVERYONE, false, false, false));
        assertTrue(SeenPrivacy.hides(Audience.FRIENDS, false, false, false), "friends only, a stranger");
        assertFalse(SeenPrivacy.hides(Audience.FRIENDS, false, true, false), "friends only, a friend");
        assertTrue(SeenPrivacy.hides(Audience.NOBODY, false, true, false), "nobody, even friends");
        assertFalse(SeenPrivacy.hides(Audience.NOBODY, true, false, false), "never from yourself");
        assertFalse(SeenPrivacy.hides(Audience.NOBODY, false, false, true), "staff who look players up always see it");
    }

    @Test
    void storedSettingResolvesLikeTheRegistry() {
        StoredSetting<Privacy> plain = StoredSetting.codeDefault(FriendPrefs.REQUESTS);
        assertEquals(Privacy.EVERYONE, plain.resolve(null));
        assertEquals(Privacy.NOBODY, plain.resolve("NOBODY"));
        assertEquals(Privacy.EVERYONE, plain.resolve("garbage"));
        StoredSetting<Privacy> defaulted = new StoredSetting<>(null, Privacy.KNOWN, FriendPrefs.REQUESTS::decodeOrNull);
        assertEquals(Privacy.KNOWN, defaulted.resolve(null), "no row: the server's default");
        assertEquals(Privacy.EVERYONE, defaulted.resolve("everyone"), "a row wins over the default");
        StoredSetting<Privacy> forced = new StoredSetting<>(Privacy.NOBODY, Privacy.NOBODY, FriendPrefs.REQUESTS::decodeOrNull);
        assertEquals(Privacy.NOBODY, forced.resolve("everyone"), "the lock wins over a row");
    }

    @Test
    void profileInviteFollowsTheTargetsTeamInvites() {
        assertTrue(ProfileButtons.takesInvites("everyone", false));
        assertTrue(ProfileButtons.takesInvites("friends", true), "friends only, from a friend");
        assertFalse(ProfileButtons.takesInvites("friends", false), "friends only, from someone else");
        assertFalse(ProfileButtons.takesInvites("nobody", true), "nobody, not even friends");
        assertTrue(ProfileButtons.takesInvites(null, false), "no teams feature: the command decides");
    }
}
