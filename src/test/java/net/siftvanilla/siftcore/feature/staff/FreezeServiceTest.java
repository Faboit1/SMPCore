package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.TestSettings;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a frozen player can't do beyond moving and typing commands: plugin teleports, portals and opening containers or
 * menus.
 */
class FreezeServiceTest {

    private static final Logger LOGGER = Logger.getLogger("freeze-test");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private FreezeService freeze;
    private Fakes.FakePlayer suspect;
    private Fakes.FakePlayer bystander;

    @BeforeEach
    void setUp() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("staff.db"), 2), LOGGER);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        StaffStore store = new StaffStore(this.database, LOGGER);
        store.open();
        this.suspect = new Fakes.FakePlayer("Suspect");
        this.bystander = new Fakes.FakePlayer("Bystander");
        store.freeze(new StaffStore.Freeze(this.suspect.id, "console", "Console", 1L)).get();

        StaffSettings settings = StaffSettings.parse(new ConfigReader("features/staff.yml", Fakes.yaml("features/staff.yml")));
        Lang lang = Fakes.lang(List.of("lang/staff.yml"), StaffMessages.class);
        Messenger messenger = new Messenger(lang, new Sounds());
        this.freeze = new FreezeService(new Fakes.ImmediateScheduler(), store, new AuditLog(this.database), messenger, new StaffText(lang),
            new StaffNotices(messenger), null, TestSettings.of(settings), LOGGER);
        this.freeze.load();
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    private PlayerTeleportEvent teleport(Fakes.FakePlayer who, PlayerTeleportEvent.TeleportCause cause) {
        return teleport(who, cause, who.location.clone().add(300, 0, 300));
    }

    private PlayerTeleportEvent teleport(Fakes.FakePlayer who, PlayerTeleportEvent.TeleportCause cause, Location to) {
        PlayerTeleportEvent event = new PlayerTeleportEvent(who.player, who.location, to, cause);
        this.freeze.onTeleport(event);
        return event;
    }

    private InventoryOpenEvent open(Fakes.FakePlayer who) {
        InventoryView view = (InventoryView) Proxy.newProxyInstance(loader(), new Class<?>[] {InventoryView.class},
            (proxy, method, args) -> method.getName().equals("getPlayer") ? who.player : Fakes.defaultValue(method.getReturnType()));
        InventoryOpenEvent event = new InventoryOpenEvent(view);
        this.freeze.onInventoryOpen(event);
        return event;
    }

    private ClassLoader loader() {
        return getClass().getClassLoader();
    }

    @Test
    void theStatusAnswersForTheCore() {
        assertTrue(this.freeze.frozen(this.suspect.id));
        assertFalse(this.freeze.frozen(this.bystander.id));
    }

    @Test
    void pluginTeleportsDontMoveAFrozenPlayerButStaffCommandsDo() {
        assertTrue(teleport(this.suspect, PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled(), "a plugin teleport (spawn, home, TPA)");
        assertTrue(teleport(this.suspect, PlayerTeleportEvent.TeleportCause.ENDER_PEARL).isCancelled(), "a pearl");
        assertFalse(teleport(this.suspect, PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled(), "staff /tp still works");
        assertFalse(teleport(this.bystander, PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled(), "others are not affected");
        Location inPlace = this.suspect.location.clone();
        inPlace.setYaw(90f);
        assertFalse(teleport(this.suspect, PlayerTeleportEvent.TeleportCause.PLUGIN, inPlace).isCancelled(),
            "the server re-sending where they stand (how a reset move is applied) goes through");
    }

    @Test
    void portalsNeverCarryAFrozenPlayerAway() throws Exception {
        Location portal = this.suspect.location.clone();
        EntityPortalEnterEvent entering = new EntityPortalEnterEvent(this.suspect.player, portal, PortalType.NETHER);
        this.freeze.onPortalEnter(entering);
        assertTrue(entering.isCancelled(), "standing in a portal never starts the trip (nether, end, end exit, gateway)");
        EntityPortalEnterEvent bystanderEntering = new EntityPortalEnterEvent(this.bystander.player, portal, PortalType.ENDER);
        this.freeze.onPortalEnter(bystanderEntering);
        assertFalse(bystanderEntering.isCancelled(), "others use portals");

        PlayerPortalEvent paper = new PlayerPortalEvent(this.suspect.player, portal, new Location(Fakes.world("world_nether"), 0, 64, 0),
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        this.freeze.onPortal(paper);
        assertTrue(paper.isCancelled(), "PlayerPortalEvent has its own handler list, so it is refused on its own");

        assertTrue(canvasPortal(this.suspect.player).isCancelled(), "Canvas' portalToAsync event, the only one Canvas fires for portals");
        assertFalse(canvasPortal(this.bystander.player).isCancelled());
    }

    /** Canvas' {@code EntityPortalAsyncEvent} (on the test class path through canvas-api), run through the freeze's guard. */
    private Cancellable canvasPortal(Entity who) throws Exception {
        Class<? extends Event> type = Class.forName("io.canvasmc.canvas.event.EntityPortalAsyncEvent").asSubclass(Event.class);
        Event event = type.getConstructor(Entity.class, World.class, World.class, PortalType.class)
            .newInstance(who, Fakes.world("world"), Fakes.world("world_nether"), PortalType.NETHER);
        this.freeze.portalGuard(type).execute(this.freeze, event);
        return (Cancellable) event;
    }

    @Test
    void aFrozenPlayerOpensNoContainerOrMenu() {
        InventoryOpenEvent frozen = open(this.suspect);
        assertTrue(frozen.isCancelled());
        assertEquals(List.of("You can't do that while frozen."), this.suspect.said());
        assertFalse(open(this.bystander).isCancelled());
    }
}
