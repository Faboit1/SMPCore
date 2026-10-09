package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.config.TestSettings;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staff hierarchy: no punishing, kicking, freezing or vanishing staff of the same or a higher weight, or the
 * owner; the console and the owner are never refused; players who aren't staff can always be punished.
 */
class StaffHierarchyTest {

    private static final Logger LOGGER = Logger.getLogger("hierarchy-test");
    private static final StaffSettings.Hierarchy ON = new StaffSettings.Hierarchy(true, 100);
    private static final StaffRanks.Rank PLAYER = StaffRanks.Rank.NONE;
    private static final StaffRanks.Rank HELPER = new StaffRanks.Rank(false, 100);
    private static final StaffRanks.Rank MOD = new StaffRanks.Rank(false, 200);
    private static final StaffRanks.Rank ADMIN = new StaffRanks.Rank(false, 300);
    private static final StaffRanks.Rank OWNER = new StaffRanks.Rank(true, 0);

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private Setting<StaffSettings> settings;
    private StaffHierarchy hierarchy;
    private final Map<UUID, StaffRanks.Rank> online = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<StaffRanks.Rank>> stored = new ConcurrentHashMap<>();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private Fakes.FakePlayer mod;

    @BeforeEach
    void setUp() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("staff.db"), 2), LOGGER);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = TestSettings.of(StaffSettings.parse(new ConfigReader("features/staff.yml", Fakes.yaml("features/staff.yml"))));
        Lang lang = Fakes.lang(List.of("lang/staff.yml"), StaffMessages.class);
        StaffRanks ranks = new StaffRanks() {
            @Override
            public Rank online(Player player, int minWeight) {
                return StaffHierarchyTest.this.online.get(player.getUniqueId());
            }

            @Override
            public CompletableFuture<Rank> any(UUID player, int minWeight) {
                StaffRanks.Rank now = StaffHierarchyTest.this.online.get(player);
                return now != null ? CompletableFuture.completedFuture(now)
                    : StaffHierarchyTest.this.stored.getOrDefault(player, CompletableFuture.completedFuture(Rank.NONE));
            }
        };
        this.hierarchy = new StaffHierarchy(new Fakes.ImmediateScheduler(), new Messenger(lang, new Sounds()), new AuditLog(this.database),
            this.settings, () -> ranks, this.players::get, LOGGER);
        this.mod = join("Mod", MOD);
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    private Fakes.FakePlayer join(String name, StaffRanks.Rank rank) {
        Fakes.FakePlayer player = new Fakes.FakePlayer(name);
        this.online.put(player.id, rank);
        this.players.put(player.id, player.player);
        return player;
    }

    private int guard(CommandSender sender, UUID target) {
        AtomicInteger ran = new AtomicInteger();
        this.hierarchy.guard(sender, target, "Target", "ban", ran::incrementAndGet);
        return ran.get();
    }

    @Test
    void theRules() {
        assertEquals(StaffHierarchy.Verdict.ALLOWED, StaffHierarchy.decide(ON, MOD, PLAYER), "players can always be punished");
        assertEquals(StaffHierarchy.Verdict.ALLOWED, StaffHierarchy.decide(ON, MOD, HELPER), "a lower staff member");
        assertEquals(StaffHierarchy.Verdict.REFUSED, StaffHierarchy.decide(ON, MOD, MOD), "the same weight");
        assertEquals(StaffHierarchy.Verdict.REFUSED, StaffHierarchy.decide(ON, MOD, ADMIN), "a higher weight");
        assertEquals(StaffHierarchy.Verdict.REFUSED, StaffHierarchy.decide(ON, ADMIN, OWNER), "nobody but an owner touches the owner");
        assertEquals(StaffHierarchy.Verdict.REFUSED, StaffHierarchy.decide(ON, PLAYER, HELPER), "a non-staff holder of a staff node");
        assertEquals(StaffHierarchy.Verdict.ALLOWED, StaffHierarchy.decide(ON, OWNER, ADMIN), "the owner is exempt");
        assertEquals(StaffHierarchy.Verdict.ALLOWED, StaffHierarchy.decide(ON, OWNER, OWNER), "owners can act on each other");
        assertEquals(StaffHierarchy.Verdict.ALLOWED, StaffHierarchy.decide(new StaffSettings.Hierarchy(false, 100), MOD, OWNER),
            "switched off");
    }

    @Test
    void aStaffWildcardDoesNotMakeAnOwner() {
        // LuckPerms resolves siftcore.staff.* to every node under siftcore.staff. (a common grant for a staff group).
        Player moderator = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> method.getName().equals("hasPermission") && args[0] instanceof String node
                ? node.startsWith("siftcore.staff.") : Fakes.defaultValue(method.getReturnType()));
        assertTrue(moderator.hasPermission(StaffNodes.BAN), "the wildcard grants the staff tools");
        assertFalse(StaffRanks.PERMISSIONS.online(moderator, 100).owner(), "but not owner status");
        assertFalse(StaffNodes.HIERARCHY_OWNER.startsWith("siftcore.staff."), StaffNodes.HIERARCHY_OWNER);
    }

    @Test
    void aModeratorCantBanTheAdminButCanBanAPlayer() {
        Fakes.FakePlayer admin = join("Admin", ADMIN);
        Fakes.FakePlayer player = join("Player", PLAYER);
        assertEquals(0, guard(this.mod.player, admin.id), "refused");
        assertTrue(this.mod.said().contains("You can't do that to Target. Their staff rank is the same as or higher than yours."),
            this.mod.said().toString());
        assertEquals(1, guard(this.mod.player, player.id));
    }

    @Test
    void theConsoleAndTheOwnerAreNeverRefused() {
        Fakes.FakePlayer owner = join("Owner", OWNER);
        CommandSender console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> Fakes.defaultValue(method.getReturnType()));
        assertEquals(1, guard(console, owner.id), "the console");
        Fakes.FakePlayer coOwner = join("CoOwner", OWNER);
        assertEquals(1, guard(owner.player, coOwner.id), "an owner");
        assertEquals(0, guard(this.mod.player, owner.id), "a moderator can't ban the owner");
    }

    @Test
    void staffCanAlwaysActOnThemselves() {
        assertEquals(1, guard(this.mod.player, this.mod.id), "/vanish <own name> toggles your own vanish");
        assertTrue(this.hierarchy.allowsNow(this.mod.player, this.mod.player));
    }

    @Test
    void anOfflineTargetIsLookedUpFirst() {
        UUID offlineAdmin = UUID.randomUUID();
        CompletableFuture<StaffRanks.Rank> lookup = new CompletableFuture<>();
        this.stored.put(offlineAdmin, lookup);
        AtomicInteger ran = new AtomicInteger();
        this.hierarchy.guard(this.mod.player, offlineAdmin, "Target", "ban", ran::incrementAndGet);
        assertEquals(0, ran.get(), "nothing happens before the rank is known");
        lookup.complete(ADMIN);
        assertEquals(0, ran.get(), "the offline admin is protected too");

        UUID offlinePlayer = UUID.randomUUID();
        CompletableFuture<StaffRanks.Rank> later = new CompletableFuture<>();
        this.stored.put(offlinePlayer, later);
        this.hierarchy.guard(this.mod.player, offlinePlayer, "Target", "ban", ran::incrementAndGet);
        later.complete(PLAYER);
        assertEquals(1, ran.get(), "an offline player is banned once looked up");
    }

    @Test
    void anUnknownRankDoesNothing() {
        UUID unknown = UUID.randomUUID();
        this.stored.put(unknown, CompletableFuture.failedFuture(new IllegalStateException("LuckPerms storage is down")));
        assertEquals(0, guard(this.mod.player, unknown));
        assertTrue(this.mod.said().contains("Couldn't check Target's staff rank, so nothing was done. Try again."), this.mod.said().toString());
    }

    @Test
    void refusalsAreAudited() throws Exception {
        Fakes.FakePlayer admin = join("Admin", ADMIN);
        guard(this.mod.player, admin.id);
        List<AuditLog.Entry> rows = new AuditLog(this.database).recent("staff.hierarchy.refused", admin.id.toString(), 5).get(5, TimeUnit.SECONDS);
        assertEquals(1, rows.size());
    }

    @Test
    void theInventoryViewEditsFollowTheHierarchy() {
        Fakes.FakePlayer admin = join("Admin", ADMIN);
        Fakes.FakePlayer player = join("Player", PLAYER);
        assertFalse(this.hierarchy.allowsNow(this.mod.player, admin.player));
        assertTrue(this.hierarchy.allowsNow(this.mod.player, player.player));
        assertTrue(this.hierarchy.allowsNow(admin.player, this.mod.player));
    }

    @Test
    void itCanBeSwitchedOff() {
        YamlConfiguration yaml = Fakes.yaml("features/staff.yml");
        yaml.set("hierarchy.enabled", false);
        TestSettings.reload(this.settings, StaffSettings.parse(new ConfigReader("features/staff.yml", yaml)));
        Fakes.FakePlayer owner = join("Owner", OWNER);
        assertEquals(1, guard(this.mod.player, owner.id));
    }

    @Test
    void theBundledConfigTurnsItOnForStaffGroupsOf100AndMore() {
        assertEquals(new StaffSettings.Hierarchy(true, 100), this.settings.get().hierarchy());
        YamlConfiguration old = Fakes.yaml("features/staff.yml");
        old.set("hierarchy", null);
        ConfigReader reader = new ConfigReader("features/staff.yml", old);
        assertEquals(StaffSettings.Hierarchy.DEFAULT, StaffSettings.parse(reader).hierarchy(), "an older staff.yml keeps it on");
        assertEquals(List.of(), reader.problems());
    }
}
