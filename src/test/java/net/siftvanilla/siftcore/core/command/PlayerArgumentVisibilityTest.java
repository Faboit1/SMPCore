package net.siftvanilla.siftcore.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Player-name arguments never give a vanished staff member away: pressing Tab after {@code /pay } lists exactly the
 * online players the sender may see, and a vanished one only ever comes up as an ordinary known name once two letters
 * are typed (like anyone who ever joined). The vanish binding also covers the moment before the server's hide list
 * catches up, for the static {@code onlinePlayer} suggestions (/tpa, /msg, /team invite) as well.
 */
class PlayerArgumentVisibilityTest {

    private static final Logger LOGGER = Logger.getLogger("visibility-test");

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerDirectory directory;
    private CommandSupport support;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    /** An online player whose server-side hide list and permissions the test sets. */
    private static final class Online {
        final UUID id;
        final String name;
        final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
        final Set<String> permissions = ConcurrentHashMap.newKeySet();
        final Player player;

        Online(String name) {
            this.id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            this.name = name;
            this.player = (Player) Proxy.newProxyInstance(PlayerArgumentVisibilityTest.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> this.id;
                    case "getName" -> this.name;
                    case "canSee" -> !this.hidden.contains(((Player) args[0]).getUniqueId());
                    case "hasPermission" -> args[0] instanceof String node && this.permissions.contains(node);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Online[" + this.name + "]";
                    default -> Fakes.defaultValue(method.getReturnType());
                });
        }
    }

    private final Online viewer = new Online("Viewer");
    private final Online moderator = new Online("ModMira");
    private final Online miner = new Online("Miner");
    private final Online admin = new Online("Admin");

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.directory = new PlayerDirectory(this.database, new byte[16]);
        for (Online online : List.of(this.viewer, this.moderator, this.miner, this.admin)) {
            this.directory.recordJoin(online.id, online.name, null);
        }
        this.directory.recordJoin(UUID.randomUUID(), "Mojo", null);
        this.support = new CommandSupport(null, this.directory, new Cooldowns(), null, null);
        this.support.vanish(this.vanished::contains);
        this.admin.permissions.add(CommandSupport.SEE_VANISHED);
    }

    @AfterEach
    void close() {
        this.support.vanish(VanishStatus.NONE);
        this.database.close();
    }

    /** The moderator vanishes: the staff feature records it and hides them from everyone without the see node. */
    private void vanishModerator() {
        this.vanished.add(this.moderator.id);
        this.viewer.hidden.add(this.moderator.id);
        this.miner.hidden.add(this.moderator.id);
    }

    private List<Player> everyone() {
        return List.of(this.viewer.player, this.moderator.player, this.miner.player, this.admin.player);
    }

    private List<String> tab(CommandSender sender, String typed) {
        return this.support.knownNames(sender, typed, everyone());
    }

    /** What the static onlinePlayer argument (/tpa, /msg, /team invite) suggests. */
    private List<String> onlineTab(CommandSender sender, String typed) {
        return CommandSupport.onlineNames(sender, typed, everyone());
    }

    @Test
    void tabWithNothingTypedListsOnlyWhoTheSenderSees() {
        vanishModerator();
        assertEquals(List.of("Viewer", "Miner", "Admin"), tab(this.viewer.player, ""), "no vanished name among the online ones");
        assertEquals(List.of("Viewer", "ModMira", "Miner", "Admin"), tab(this.admin.player, ""), "staff who see vanished players get them");
        assertEquals(List.of("ModMira", "Viewer", "Miner", "Admin"), this.support.knownNames(this.moderator.player, "",
            List.of(this.moderator.player, this.viewer.player, this.miner.player, this.admin.player)), "the vanished player sees themself");
        CommandSender console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> Fakes.defaultValue(method.getReturnType()));
        assertEquals(4, tab(console, "").size(), "the console sees everyone");
    }

    @Test
    void aVanishedNameOnlyComesUpAsAKnownNameOnceTwoLettersAreTyped() {
        vanishModerator();
        assertEquals(List.of("Miner"), tab(this.viewer.player, "m"), "one letter: online players only");
        List<String> two = tab(this.viewer.player, "mo");
        assertTrue(two.contains("ModMira") && two.contains("Mojo"), "two letters: every known name, online or not: " + two);
        assertEquals(two.size(), Set.copyOf(two).size(), "each name once: " + two);
        List<String> visible = tab(this.viewer.player, "mi");
        assertEquals(List.of("Miner"), visible, "a visible online name is not repeated from the directory");
    }

    @Test
    void theVanishBindingCoversTheMomentBeforeTheServerHidesThem() {
        this.vanished.add(this.moderator.id);
        assertTrue(this.viewer.player.canSee(this.moderator.player), "the server does not hide them yet");
        assertFalse(this.support.canSee(this.viewer.player, this.moderator.player));
        assertFalse(tab(this.viewer.player, "").contains("ModMira"));
        assertTrue(this.support.canSee(this.admin.player, this.moderator.player));
    }

    @Test
    void playersHiddenByTheServerStayHiddenWithoutAVanish() {
        this.viewer.hidden.add(this.miner.id);
        assertFalse(this.support.canSee(this.viewer.player, this.miner.player));
        assertFalse(tab(this.viewer.player, "").contains("Miner"));
    }

    @Test
    void theRule() {
        assertTrue(CommandSupport.visible(true, false, false), "visible and not vanished");
        assertFalse(CommandSupport.visible(false, false, false), "hidden by the server");
        assertFalse(CommandSupport.visible(true, true, false), "vanished, before the hide reached the server");
        assertTrue(CommandSupport.visible(true, true, true), "vanished, seen by staff");
        assertFalse(CommandSupport.visible(false, true, true), "hidden by the server even from staff");
    }

    @Test
    void suggestionsAreCapped() {
        List<Player> crowd = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Online online = new Online("Player" + i);
            crowd.add(online.player);
        }
        assertEquals(CommandSupport.MAX_SUGGESTIONS, this.support.knownNames(this.viewer.player, "", crowd).size());
    }

    @Test
    void withoutABindingOnlyTheServerHideListCounts() {
        CommandSupport unbound = new CommandSupport(null, this.directory, new Cooldowns(), null, null);
        this.vanished.add(this.moderator.id);
        assertTrue(unbound.canSee(this.viewer.player, this.moderator.player));
        this.viewer.hidden.add(this.moderator.id);
        assertFalse(unbound.canSee(this.viewer.player, this.moderator.player));
    }

    @Test
    void onlinePlayerSuggestionsAskTheVanishBindingBeforeTheServerHidesThem() {
        this.vanished.add(this.moderator.id);
        assertTrue(this.viewer.player.canSee(this.moderator.player), "the server does not hide them yet");
        assertEquals(List.of("Viewer", "Miner", "Admin"), onlineTab(this.viewer.player, ""), "/tpa Tab leaves the vanished moderator out");
        assertEquals(List.of("Miner"), onlineTab(this.viewer.player, "m"), "and with letters typed");
        assertEquals(List.of("Viewer", "ModMira", "Miner", "Admin"), onlineTab(this.admin.player, ""), "staff who see vanished players get them");
        assertTrue(onlineTab(this.moderator.player, "mod").contains("ModMira"), "the vanished player sees themself");
        CommandSender console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> Fakes.defaultValue(method.getReturnType()));
        assertEquals(4, onlineTab(console, "").size(), "the console sees everyone");
    }

    @Test
    void onlinePlayerSuggestionsFollowTheServerHideListAndTheLatestBinding() {
        this.viewer.hidden.add(this.miner.id);
        assertFalse(onlineTab(this.viewer.player, "").contains("Miner"), "hidden by the server, no vanish");
        this.viewer.hidden.clear();
        this.vanished.add(this.moderator.id);
        this.support.vanish(VanishStatus.NONE);
        assertTrue(onlineTab(this.viewer.player, "").contains("ModMira"), "without a binding only the server's hide list counts");
        this.support.vanish(this.vanished::contains);
        assertFalse(onlineTab(this.viewer.player, "").contains("ModMira"), "binding the vanish binds the static suggestions too");
        new CommandSupport(null, this.directory, new Cooldowns(), null, null);
        assertFalse(onlineTab(this.viewer.player, "").contains("ModMira"), "a new, unbound CommandSupport does not unbind them");
    }
}
