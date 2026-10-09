package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.teleport.Teleports;
import net.siftvanilla.siftcore.feature.homes.BareHome;
import net.siftvanilla.siftcore.feature.homes.HomesFeature;
import net.siftvanilla.siftcore.feature.rtp.RtpFeature;
import net.siftvanilla.siftcore.feature.spawn.SpawnFeature;
import net.siftvanilla.siftcore.feature.tpa.TpaFeature;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * End-to-end scenarios of homes, teleport requests, random teleport and spawn (arrival, /spawn, /setspawn, respawn
 * and the protected spawn area).
 */
final class TeleportScenarios {

    private TeleportScenarios() {
    }

    private record Named(String name, Body body) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            this.body.run(e2e);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(E2E e2e) throws Exception;
    }

    private static Scenario of(String name, Body body) {
        return new Named(name, body);
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("spawn-arrival", TeleportScenarios::arrival));
        list.add(of("spawn-protection", TeleportScenarios::protection));
        list.add(of("spawn-command", TeleportScenarios::spawnCommand));
        list.add(of("homes-flow", TeleportScenarios::homes));
        list.add(of("homes-staff", TeleportScenarios::homesStaff));
        list.add(of("homes-safety", TeleportScenarios::homesSafety));
        list.add(of("tpa-flow", TeleportScenarios::tpaFlow));
        list.add(of("tpa-switches", TeleportScenarios::tpaSwitches));
        list.add(of("tpa-combat", TeleportScenarios::tpaCombat));
        list.add(of("tpa-combat-warmup", TeleportScenarios::tpaCombatWarmup));
        list.add(of("homes-combat", TeleportScenarios::homesCombat));
        list.add(of("rtp-flow", TeleportScenarios::rtpFlow));
        list.add(of("rtp-limits", TeleportScenarios::rtpLimits));
        list.add(of("tpa-settings", TeleportScenarios::tpaSettings));
        list.add(of("homes-settings", TeleportScenarios::homesSettings));
        list.add(of("teleport-display", TeleportScenarios::teleportDisplay));
        list.add(of("rtp-settings", TeleportScenarios::rtpSettings));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Location location(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> e2e.player(name).getLocation());
    }

    private static Location spawn(E2E e2e) {
        Location spawn = e2e.feature(SpawnFeature.class).location();
        e2e.expect(spawn != null, "a spawn point");
        return spawn;
    }

    private static SpawnArea area(E2E e2e) {
        SpawnArea area = e2e.spawnArea();
        e2e.expect(area != null, "the spawn feature runs");
        return area;
    }

    private static boolean near(Location a, Location b, double distance) {
        return a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= distance * distance;
    }

    private static double flatDistance(Location location, double x, double z) {
        double dx = location.getX() - x;
        double dz = location.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Moves a bot onto the ground {@code dx}, {@code dz} blocks from where it stands; returns where it now stands. */
    private static Location shift(E2E e2e, Bot bot, int dx, int dz) {
        Location here = location(e2e, bot.name);
        Location target = e2e.ground(here.getWorld(), here.getBlockX() + dx, here.getBlockZ() + dz, here.getYaw());
        moveTo(e2e, bot, target);
        return target;
    }

    /** Steps up into the air, which counts as moving to another block (cancels a warmup) and can't suffocate. */
    private static void stepUp(Bot bot) {
        bot.moveBy(0, 1.2, 0, 0f);
    }

    /**
     * Runs a command as a console-like sender and waits until its output contains {@code expected} (commands that
     * answer later, after storage or a search). Returns everything the command said.
     */
    private static List<String> output(E2E e2e, String command, String expected, long millis) {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(e2e.services().plugin(), () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message -> lines.add(PlainTextComponentSerializer.plainText()
                    .serialize(message))), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("command '" + command + "' failed: " + e);
        }
        e2e.eventually(() -> lines.stream().anyMatch(line -> line.contains(expected)), millis,
            "'" + command + "' answers with '" + expected + "': " + lines);
        return List.copyOf(lines);
    }

    /** Teleports a bot (server side) and waits until both the server and the bot's client have it there. */
    private static void moveTo(E2E e2e, Bot bot, Location target) {
        e2e.player(bot.name).teleportAsync(target);
        e2e.eventually(() -> near(location(e2e, bot.name), target, 0.3)
            && Math.abs(bot.x() - target.getX()) < 0.3 && Math.abs(bot.z() - target.getZ()) < 0.3,
            bot.name + " was moved to " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ());
        e2e.sleep(250);
    }

    private static String placeholder(E2E e2e, String player, String name) {
        String value = e2e.services().placeholders().resolve(e2e.player(player), name);
        return value == null ? "" : value;
    }

    private static void op(E2E e2e, String name, boolean on) {
        e2e.console((on ? "op " : "deop ") + name);
        e2e.eventually(() -> e2e.player(name).isOp() == on, name + (on ? " is op" : " is no longer op"));
    }

    private static int homeRows(E2E e2e, UUID player) throws Exception {
        return e2e.services().database().read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM homes WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /** A spot next to the player on its own region thread, set to {@code type} with air above it; returns x y z. */
    private static int[] blockNextTo(E2E e2e, String name, int dx, int dz, Material type) {
        return e2e.onPlayer(name, () -> {
            Block feet = e2e.player(name).getLocation().getBlock();
            Block spot = feet.getRelative(dx, 0, dz);
            if (!Bukkit.isOwnedByCurrentRegion(spot.getLocation())) {
                throw new IllegalStateException("the block next to " + name + " belongs to another region");
            }
            spot.getRelative(0, -1, 0).setType(Material.STONE, false);
            spot.setType(type, false);
            spot.getRelative(0, 1, 0).setType(Material.AIR, false);
            spot.getRelative(0, 2, 0).setType(Material.AIR, false);
            return new int[] {spot.getX(), spot.getY(), spot.getZ()};
        });
    }

    private static boolean open(E2E e2e, String name, int[] at) {
        return e2e.onPlayer(name, () -> e2e.player(name).getWorld().getBlockAt(at[0], at[1], at[2]).getBlockData() instanceof Openable open
            && open.isOpen());
    }

    private static Material type(E2E e2e, String name, int[] at) {
        return e2e.onPlayer(name, () -> e2e.player(name).getWorld().getBlockAt(at[0], at[1], at[2]).getType());
    }

    private static double health(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> e2e.player(name).getHealth());
    }

    /** The title of the teleport settings page. */
    private static final String TELEPORT_PAGE = "Teleports & homes settings";

    private static PlayerSettings settings(E2E e2e) {
        return e2e.services().settings();
    }

    /**
     * Changes a setting the way a player types it, {@code /settings <id> <value>}, and waits until the player reads the
     * typed value (the command's own answer is in the failure message when it doesn't).
     */
    private static void setting(E2E e2e, Bot bot, String id, String value) {
        UUID player = e2e.uuid(bot.name);
        PlayerSetting<?> setting = settings(e2e).setting(id);
        e2e.expect(setting != null, id + " is a setting");
        Object wanted = PlayerSettings.parse(setting, value, null);
        e2e.expect(wanted != null, value + " is a value of " + id);
        bot.clearLogs();
        bot.command("settings " + id + " " + value);
        e2e.eventually(() -> wanted.equals(settings(e2e).get(player, setting)),
            "/settings " + id + " " + value + " changes it for " + bot.name + " (chat " + bot.chat() + ", action bar " + bot.actionBar() + ")");
    }

    /**
     * Opens the teleport settings with /settings teleport and changes inputs wherever they are: walks the pages with Next
     * page (changes carried along), sets each key on the page that shows it, and saves on the page with the last one.
     */
    private static void editTeleportSettings(E2E e2e, Bot bot, Map<String, Object> wanted) {
        bot.clearLogs();
        bot.command("settings teleport");
        Set<String> left = new HashSet<>(wanted.keySet());
        for (int guard = 0; guard < 10; guard++) {
            Bot.SeenDialog current = e2e.dialog(bot, TELEPORT_PAGE);
            Map<String, Object> values = current.values();
            for (String key : List.copyOf(left)) {
                if (current.inputs().containsKey(key)) {
                    e2e.expect(!(wanted.get(key) instanceof String option) || current.options().getOrDefault(key, List.of()).contains(option),
                        key + " offers " + wanted.get(key) + ": " + current.options().get(key));
                    values.put(key, wanted.get(key));
                    left.remove(key);
                }
            }
            if (left.isEmpty()) {
                e2e.click(bot, "Save", values);
                return;
            }
            e2e.expect(current.button("Next page") != null, "inputs " + left + " on a later page (last page: " + current.inputs().keySet() + ")");
            e2e.click(bot, "Next page", values);
        }
        throw new E2E.Failure("too many pages of teleport settings");
    }

    /** Makes two players friends through the staff tool and waits until the friends feature knows it. */
    private static void befriend(E2E e2e, String a, String b) {
        e2e.console("sift friends add " + a + " " + b);
        e2e.eventually(() -> e2e.services().relations().areFriends(e2e.uuid(a), e2e.uuid(b)), a + " and " + b + " are friends");
    }

    /** Generates (once) every chunk in the square around a block centre, so random teleport finds land there. */
    private static void generate(E2E e2e, World world, int centerX, int centerZ, int radius) throws Exception {
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = (centerX - radius) >> 4; cx <= (centerX + radius) >> 4; cx++) {
            for (int cz = (centerZ - radius) >> 4; cz <= (centerZ + radius) >> 4; cz++) {
                futures.add(world.getChunkAtAsync(cx, cz, true));
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(240, TimeUnit.SECONDS);
        e2e.log("generated " + futures.size() + " chunks around " + centerX + " " + centerZ + " in " + world.getName());
    }

    /** Whether any chunk of the square around a block centre exists on disk or in memory. */
    private static boolean anyGenerated(World world, int centerX, int centerZ, int radius) {
        for (int cx = (centerX - radius) >> 4; cx <= (centerX + radius) >> 4; cx++) {
            for (int cz = (centerZ - radius) >> 4; cz <= (centerZ + radius) >> 4; cz++) {
                if (world.isChunkGenerated(cx, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ spawn

    /** A brand-new player starts at spawn, inside the protected area, with a welcome; a returning one gets none. */
    static void arrival(E2E e2e) {
        String name = e2e.name("Arrival");
        e2e.step("a new player arrives at the spawn point");
        Bot bot = e2e.botAtSpawn(name);
        Location spawn = spawn(e2e);
        Location at = location(e2e, name);
        e2e.expect(near(at, spawn, 1.5), name + " joined at the spawn point " + spawn + ", not " + at);
        e2e.expect(area(e2e).contains(at), "the spawn point lies in the protected area");
        e2e.step("a short welcome title and a chat line pointing to the menu");
        e2e.eventually(() -> bot.titles().stream().anyMatch(title -> title.contains("Welcome to SiftVanilla")), "a welcome title: " + bot.titles());
        e2e.eventually(() -> bot.chatContains("Everything is in the menu"), "a chat line about the menu: " + bot.chat());

        e2e.step("a returning player gets no welcome");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        Bot again = e2e.botAtSpawn(name);
        e2e.sleep(2_000);
        e2e.expect(again.titles().stream().noneMatch(title -> title.contains("Welcome")), "no title the second time: " + again.titles());
        e2e.expect(!again.chatContains("Everything is in the menu"), "no welcome line the second time: " + again.chat());
    }

    /** Nobody builds, uses containers, fights or gets hurt inside spawn; doors work; mobs don't spawn naturally. */
    static void protection(E2E e2e) {
        String guardName = e2e.name("Guard");
        String visitorName = e2e.name("Visitor");
        Bot guard = e2e.botAtSpawn(guardName);
        Bot visitorBot = e2e.botAtSpawn(visitorName);
        SpawnArea area = area(e2e);
        e2e.expect(area.contains(location(e2e, guardName)), "the guard stands in spawn");
        e2e.onPlayer(guardName, () -> {
            Player player = e2e.player(guardName);
            player.getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            player.getInventory().setItem(0, new ItemStack(Material.DIRT, 16));
            player.getInventory().setHeldItemSlot(0);
            return null;
        });

        e2e.step("breaking a block is refused");
        int[] dirt = blockNextTo(e2e, guardName, 2, 0, Material.DIRT);
        guard.clearLogs();
        guard.breakBlock(dirt[0], dirt[1], dirt[2]);
        e2e.eventually(() -> guard.actionBarContains("You can't build at spawn"), "a build message: " + guard.actionBar());
        e2e.expect(type(e2e, guardName, dirt) == Material.DIRT, "the block is still there");

        e2e.step("placing a block is refused");
        e2e.sleep(1_100);
        guard.clearLogs();
        guard.useItemOnTop(dirt[0], dirt[1], dirt[2]);
        e2e.sleep(1_000);
        int[] above = {dirt[0], dirt[1] + 1, dirt[2]};
        e2e.expect(type(e2e, guardName, above) == Material.AIR, "nothing was placed: " + type(e2e, guardName, above));

        e2e.step("containers can't be opened; trapdoors (allowed) still work");
        int[] chest = blockNextTo(e2e, guardName, -2, 0, Material.CHEST);
        e2e.sleep(1_100);
        guard.clearLogs();
        guard.useItemOnTop(chest[0], chest[1], chest[2]);
        e2e.eventually(() -> guard.actionBarContains("You can't use that at spawn"), "a use message: " + guard.actionBar());
        e2e.expect(guard.screen() == null, "no chest screen opened");
        int[] trapdoor = blockNextTo(e2e, guardName, 0, 2, Material.OAK_TRAPDOOR);
        guard.useItemOnTop(trapdoor[0], trapdoor[1], trapdoor[2]);
        e2e.eventually(() -> open(e2e, guardName, trapdoor), "the trapdoor opened");

        e2e.step("no fighting and no damage of any kind");
        Player visitor = e2e.player(visitorName);
        double before = health(e2e, guardName);
        e2e.sleep(1_100);
        e2e.onPlayer(guardName, () -> {
            e2e.player(guardName).damage(4.0, visitor);
            return null;
        });
        e2e.expect(health(e2e, guardName) == before, "a hit did nothing: " + before + " -> " + health(e2e, guardName));
        e2e.eventually(() -> visitorBot.actionBarContains("There is no fighting at spawn"), "the attacker is told: " + visitorBot.actionBar());
        e2e.onPlayer(guardName, () -> {
            e2e.player(guardName).damage(4.0);
            return null;
        });
        e2e.expect(health(e2e, guardName) == before, "plain damage did nothing: " + health(e2e, guardName));

        e2e.step("an explosion breaks nothing inside spawn");
        int[] stone = blockNextTo(e2e, guardName, 0, -2, Material.STONE);
        e2e.onPlayer(guardName, () -> e2e.player(guardName).getWorld().createExplosion(stone[0] + 0.5, stone[1] + 0.5, stone[2] + 0.5, 3f, false, true));
        e2e.sleep(500);
        e2e.expect(type(e2e, guardName, stone) == Material.STONE, "the stone survived: " + type(e2e, guardName, stone));
        e2e.expect(health(e2e, guardName) == before, "the explosion did not hurt: " + health(e2e, guardName));

        e2e.step("mobs don't spawn naturally; commands and eggs still can");
        boolean[] spawned = e2e.onPlayer(guardName, () -> {
            Location at = e2e.player(guardName).getLocation().add(3, 0, 3);
            Entity natural = at.getWorld().spawnEntity(at, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.NATURAL);
            Entity custom = at.getWorld().spawnEntity(at, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM);
            boolean[] result = {natural.isValid(), custom.isValid()};
            natural.remove();
            custom.remove();
            return result;
        });
        e2e.expect(!spawned[0], "a natural zombie was stopped");
        e2e.expect(spawned[1], "a zombie spawned by a command was not stopped");

        e2e.step("builders with the bypass permission can build");
        op(e2e, guardName, true);
        try {
            guard.breakBlock(dirt[0], dirt[1], dirt[2]);
            e2e.eventually(() -> type(e2e, guardName, dirt) == Material.AIR, "the builder broke the block");
        } finally {
            op(e2e, guardName, false);
        }
    }

    /** /spawn with its warmup, moving cancels it, staff and console sends, respawning at spawn and /setspawn. */
    static void spawnCommand(E2E e2e) throws Exception {
        String name = e2e.name("Spawner");
        Bot bot = e2e.bot(name);
        Location spawn = spawn(e2e);
        SpawnArea area = area(e2e);
        e2e.expect(!area.contains(location(e2e, name)), "the bot starts outside spawn");

        e2e.step("/spawn teleports after the warmup");
        bot.clearLogs();
        long start = System.currentTimeMillis();
        bot.command("spawn");
        e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "a warmup countdown: " + bot.actionBar());
        e2e.eventually(() -> near(location(e2e, name), spawn, 1.0), 15_000, name + " is at spawn");
        long took = System.currentTimeMillis() - start;
        e2e.expect(took >= 2_500, "the teleport waited for the 3s warmup, took " + took + " ms");

        e2e.step("moving cancels the warmup");
        e2e.leaveSpawn(bot);
        Location away = location(e2e, name);
        bot.clearLogs();
        bot.command("spawn");
        e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "a warmup countdown");
        stepUp(bot);
        e2e.eventually(() -> bot.actionBarContains("Teleport cancelled because you moved"), "cancelled: " + bot.actionBar());
        e2e.sleep(3_500);
        e2e.expect(!area.contains(location(e2e, name)), "still outside spawn");
        e2e.expect(near(location(e2e, name), away, 3), "near where the bot was");

        e2e.step("the console sends a player to spawn at once");
        bot.clearLogs();
        output(e2e, "spawn " + name, "Sent " + name + " to spawn.", 10_000);
        e2e.eventually(() -> near(location(e2e, name), spawn, 1.0), name + " was sent to spawn");
        e2e.eventually(() -> bot.actionBarContains("A staff member sent you to spawn"), "told why: " + bot.actionBar());

        e2e.step("dying without a bed respawns at spawn");
        e2e.leaveSpawn(bot);
        int deaths = bot.deaths();
        e2e.onPlayer(name, () -> {
            e2e.player(name).setHealth(0);
            return null;
        });
        e2e.eventually(() -> bot.deaths() > deaths, "the bot died");
        e2e.eventually(() -> !e2e.player(name).isDead() && near(location(e2e, name), spawn, 1.5), 15_000,
            "respawned at the spawn point, at " + location(e2e, name));

        e2e.step("/setspawn moves the spawn and the protected area; the console restores it");
        Path data = e2e.services().plugin().getDataFolder().toPath().resolve("data/spawn.yml");
        String restore = String.format(Locale.ROOT, "setspawn %s %.3f %.3f %.3f %.1f %.1f", spawn.getWorld().getName(), spawn.getX(),
            spawn.getY(), spawn.getZ(), wrap(spawn.getYaw()), spawn.getPitch());
        e2e.leaveSpawn(bot);
        Location here = location(e2e, name);
        op(e2e, name, true);
        try {
            bot.clearLogs();
            bot.command("setspawn");
            e2e.eventually(() -> bot.chatContains("Spawn set to " + here.getWorld().getName() + " " + here.getBlockX()),
                "a confirmation in chat: " + bot.chat());
            e2e.eventually(() -> near(e2e.feature(SpawnFeature.class).location(), here, 0.01), "the spawn moved");
            e2e.expect(area.contains(here), "the protected area moved with it");
            e2e.eventually(() -> Files.exists(data) && read(data).contains(String.valueOf(here.getBlockX())), "data/spawn.yml was saved");
            e2e.step("a spawn outside the world border is refused");
            List<String> outside = e2e.consoleOutput("setspawn " + here.getWorld().getName() + " 6000 80 0");
            e2e.expect(outside.stream().anyMatch(line -> line.contains("outside the world border")), "refused: " + outside);
            e2e.expect(near(e2e.feature(SpawnFeature.class).location(), here, 0.01), "the spawn did not move");
        } finally {
            op(e2e, name, false);
            List<String> restored = e2e.consoleOutput(restore);
            e2e.log("restore: " + restored);
        }
        e2e.eventually(() -> near(e2e.feature(SpawnFeature.class).location(), spawn, 0.01), "the spawn is back");
    }

    private static float wrap(float yaw) {
        float wrapped = yaw % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ homes

    static void homes(E2E e2e) throws Exception {
        String name = e2e.name("Homer");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("no homes yet");
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> bot.actionBarContains("You have no homes yet"), "no homes: " + bot.actionBar());

        e2e.step("/sethome sets 'home' and counts against the limit of 2");
        Location first = location(e2e, name);
        bot.command("sethome");
        e2e.eventually(() -> bot.actionBarContains("Home home set. You have 1 of 2."), "home set: " + bot.actionBar());
        e2e.expect("1".equals(placeholder(e2e, name, "homes_count")), "homes_count is 1");
        e2e.expect("2".equals(placeholder(e2e, name, "homes_limit")), "homes_limit is 2");

        e2e.step("a second named home; names are not case sensitive");
        Location second = shift(e2e, bot, 8, 0);
        bot.command("sethome Base");
        e2e.eventually(() -> bot.actionBarContains("Home base set. You have 2 of 2."), "base set: " + bot.actionBar());

        e2e.step("the limit stops a third home, moving an existing one still works");
        bot.clearLogs();
        bot.command("sethome third");
        e2e.eventually(() -> bot.actionBarContains("You have 2 of 2 homes"), "the limit: " + bot.actionBar());
        bot.clearLogs();
        bot.command("sethome BASE");
        Bot.SeenDialog move = e2e.dialog(bot, "Move home");
        e2e.expect(move.bodyText().contains("Home base already exists") && move.bodyText().contains("Now: " + second.getWorld().getName() + " "
            + second.getBlockX() + ", "), "moving an existing home asks first, with where it is: " + move.body());
        e2e.click(bot, "Move it here");
        e2e.eventually(() -> bot.actionBarContains("Home base moved here"), "moved: " + bot.actionBar());
        bot.clearLogs();
        bot.command("sethome bad.name");
        e2e.eventually(() -> bot.actionBarContains("Home names are 1 to 16 letters"), "name rules: " + bot.actionBar());

        e2e.step("no homes inside the protected spawn");
        moveTo(e2e, bot, spawn(e2e));
        bot.clearLogs();
        bot.command("sethome spawnhome");
        e2e.eventually(() -> bot.actionBarContains("You can't set a home at spawn"), "refused at spawn: " + bot.actionBar());
        e2e.expect(homeRows(e2e, id) == 2, "two rows stored");

        e2e.step("/home with two homes lists them; a button teleports after the warmup");
        bot.clearLogs();
        bot.command("home");
        Bot.SeenDialog list = e2e.dialog(bot, "Homes");
        e2e.expect(list.bodyText().contains("2 of 2 homes") && list.bodyText().contains("base") && list.bodyText().contains("home"),
            "both homes in the body: " + list.body());
        e2e.expect(list.button("Set a home here") != null && list.button("Delete") != null, "set and delete buttons: " + list.buttons());
        long start = System.currentTimeMillis();
        e2e.click(bot, "base");
        e2e.eventually(() -> near(location(e2e, name), second, 0.5), 15_000, "at base");
        e2e.expect(System.currentTimeMillis() - start >= 2_500, "the warmup was respected");
        e2e.eventually(() -> bot.actionBarContains("Welcome to base"), "welcome: " + bot.actionBar());

        e2e.step("the cooldown between home teleports");
        bot.clearLogs();
        bot.command("home home");
        e2e.eventually(() -> bot.actionBarContains("before doing that again"), "a cooldown: " + bot.actionBar());
        e2e.sleep(5_200);

        e2e.step("moving cancels a home teleport");
        bot.clearLogs();
        bot.command("home home");
        e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "a warmup");
        stepUp(bot);
        e2e.eventually(() -> bot.actionBarContains("Teleport cancelled because you moved"), "cancelled: " + bot.actionBar());
        e2e.sleep(3_500);
        e2e.expect(!near(location(e2e, name), first, 2), "still away from home");

        e2e.step("/delhome asks first; cancelling keeps the home");
        bot.clearLogs();
        bot.command("delhome base");
        Bot.SeenDialog confirm = e2e.dialog(bot, "Delete home");
        e2e.expect("confirm".equals(confirm.type()) && confirm.bodyText().contains("Delete home base?"), "a confirmation: " + confirm.body());
        e2e.click(bot, "Cancel");
        e2e.sleep(500);
        e2e.expect("2".equals(placeholder(e2e, name, "homes_count")), "still two homes");
        bot.clearLogs();
        bot.command("delhome base");
        e2e.dialog(bot, "Delete home");
        e2e.click(bot, "Delete");
        e2e.eventually(() -> bot.actionBarContains("Home base deleted"), "deleted: " + bot.actionBar());
        e2e.expect("1".equals(placeholder(e2e, name, "homes_count")), "one home left");
        e2e.eventually(() -> {
            try {
                return homeRows(e2e, id) == 1;
            } catch (Exception e) {
                return false;
            }
        }, "one row stored");

        e2e.step("/home with one home goes straight there");
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> near(location(e2e, name), first, 0.5), 15_000, "at home");

        e2e.step("homes come back after a relog");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        Bot again = e2e.bot(name);
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "the home is loaded at login");
        again.command("homes");
        Bot.SeenDialog relogged = e2e.dialog(again, "Homes");
        e2e.expect(relogged.button("home") != null, "the home button after the relog: " + relogged.buttons());

        e2e.step("the menu sets a home through a form");
        again.clearLogs();
        again.command("menu");
        e2e.dialog(again, "SiftVanilla");
        e2e.click(again, "Homes");
        e2e.dialog(again, "Homes");
        e2e.click(again, "Set a home here");
        Bot.SeenDialog form = e2e.dialog(again, "Set a home");
        e2e.expect(form.inputs().containsKey("name"), "a name input: " + form.inputs());
        e2e.click(again, "Set home", Map.of("name", "bad name"));
        Bot.SeenDialog retry = e2e.dialog(again, "Set a home");
        e2e.expect(retry.bodyText().contains("Home names are 1 to 16"), "the rule in the form: " + retry.body());
        e2e.click(again, "Set home", Map.of("name", "Cabin"));
        Bot.SeenDialog after = e2e.dialog(again, "Homes");
        e2e.expect(after.button("cabin") != null, "cabin in the list: " + after.buttons());
        e2e.expect("2".equals(placeholder(e2e, name, "homes_count")), "two homes again");
    }

    /** Staff and the console can list and delete anyone's homes, online or not. */
    static void homesStaff(E2E e2e) throws Exception {
        String name = e2e.name("HomeOwner");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        bot.command("sethome cottage");
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "a home was set");
        Location at = location(e2e, name);

        e2e.step("the console lists the homes");
        List<String> listed = output(e2e, "homes " + name, "cottage", 10_000);
        e2e.expect(listed.stream().anyMatch(line -> line.contains("Homes of " + name)), "a header: " + listed);
        e2e.expect(listed.stream().anyMatch(line -> line.contains("cottage") && line.contains(at.getBlockX() + ", " + at.getBlockY())),
            "the home with its position: " + listed);

        e2e.step("the console deletes a home of an offline player");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        List<String> deleted = output(e2e, "homes " + name + " delete cottage", "Deleted home cottage of " + name, 10_000);
        e2e.eventually(() -> {
            try {
                return homeRows(e2e, id) == 0;
            } catch (Exception e) {
                return false;
            }
        }, "the row is gone");
        e2e.log("delete: " + deleted);
        output(e2e, "homes " + name, name + " has no homes.", 10_000);

        e2e.step("a staff member looking at the homes and teleporting to one is audited");
        String staffName = e2e.name("HomeStaff");
        Bot owner = e2e.bot(name);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        try {
            owner.command("sethome cabin");
            e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "a home was set");
            Location cabin = location(e2e, name);
            shift(e2e, staff, 12, 0);
            staff.command("homes " + name);
            e2e.dialog(staff, "Homes of " + name);
            e2e.eventually(() -> audited(e2e, "homes.view", id), "homes.view is in the audit log");
            e2e.click(staff, "cabin");
            e2e.eventually(() -> location(e2e, staffName).distance(cabin) < 1.5, 15_000, "the staff member is at the home");
            e2e.eventually(() -> audited(e2e, "homes.teleport", id), "homes.teleport is in the audit log");
        } finally {
            e2e.console("deop " + staffName);
        }
    }

    private static boolean audited(E2E e2e, String action, UUID target) {
        try {
            return !e2e.services().audit().recent(action, target.toString(), 5).get(5, TimeUnit.SECONDS).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Teleport requests can't be sent or accepted in combat, from a command, the chat dialog or the menu form. */
    static void tpaCombat(E2E e2e) {
        String fighterName = e2e.name("Fighter");
        String allyName = e2e.name("Ally");
        Bot fighter = e2e.bot(fighterName);
        Bot ally = e2e.bot(allyName);
        Location allyAt = location(e2e, allyName);

        e2e.step("a request reaches the fighter, who is then put in combat");
        ally.command("tpa " + fighterName);
        e2e.eventually(() -> fighter.chatContains(allyName + " wants to teleport to you"), "the request: " + fighter.chat());
        e2e.console("combat tag " + fighterName + " 60s");

        e2e.step("accepting from the chat dialog in combat is refused and the ally stays");
        fighter.clearMessages();
        e2e.expect(fighter.openChatDialog("wants to teleport to you"), "the request's dialog opens from chat");
        e2e.click(fighter, "Accept");
        e2e.eventually(() -> fighter.anyFeedbackContains("You can't use teleport requests in combat."), "refused: " + fighter.actionBar());
        e2e.sleep(4_000);
        e2e.expect(location(e2e, allyName).distance(allyAt) < 1.0, "the ally was not pulled into the fight");

        e2e.step("the fighter can't send a request from the menu form either");
        ally.clearLogs();
        fighter.command("menu");
        e2e.dialog(fighter, "SiftVanilla");
        e2e.click(fighter, "Teleport to a player");
        e2e.dialog(fighter, "Teleport request");
        e2e.click(fighter, "Send request", Map.of("player", allyName, "direction", "here"));
        e2e.eventually(() -> fighter.anyFeedbackContains("You can't use teleport requests in combat."), "refused: " + fighter.actionBar());
        e2e.sleep(800);
        e2e.expect(!ally.chatContains("wants you to teleport"), "the ally got no request: " + ally.chat());

        e2e.step("accepting a request while its sender is in combat is refused; it waits");
        e2e.console("combat untag " + fighterName);
        e2e.sleep(2_500);
        fighter.command("tpa " + allyName);
        e2e.eventually(() -> ally.chatContains(fighterName + " wants to teleport to you"), "the fighter's request: " + ally.chat());
        e2e.console("combat tag " + fighterName + " 60s");
        ally.clearLogs();
        ally.command("tpaccept " + fighterName);
        e2e.eventually(() -> ally.anyFeedbackContains(fighterName + " is in combat."), "the sender is in combat: " + ally.actionBar());

        e2e.step("after the fight, the waiting requests can be accepted");
        e2e.console("combat untag " + fighterName);
        fighter.clearLogs();
        fighter.command("tpaccept " + allyName);
        e2e.eventually(() -> fighter.anyFeedbackContains("Accepted " + allyName), "accepted after the fight: " + fighter.chat() + fighter.actionBar());
    }

    /**
     * A request accepted before any fight: when the player who stays put is attacked during the warmup, nobody is
     * delivered into that fight, whichever way the request went (/tpa or /tpahere).
     */
    static void tpaCombatWarmup(E2E e2e) {
        String stayName = e2e.name("Stayer");
        String comeName = e2e.name("Comer");
        Bot stayer = e2e.bot(stayName);
        Bot comer = e2e.bot(comeName);
        shift(e2e, stayer, 10, 0);
        Location comerAt = location(e2e, comeName);

        e2e.step("/tpa: the target accepts, then is attacked during the warmup; the sender stays");
        comer.command("tpa " + stayName);
        e2e.eventually(() -> stayer.chatContains(comeName + " wants to teleport to you"), "the request: " + stayer.chat());
        comer.clearLogs();
        stayer.clearLogs();
        stayer.command("tpaccept");
        e2e.eventually(() -> comer.actionBarContains(stayName + " accepted your request"), "accepted: " + comer.actionBar());
        e2e.console("combat tag " + stayName + " 60s");
        e2e.eventually(() -> comer.anyFeedbackContains(stayName + " is in combat now."), 10_000, "the sender is told: " + comer.actionBar());
        e2e.eventually(() -> stayer.anyFeedbackContains(comeName + " didn't teleport."), "the target is told: " + stayer.actionBar());
        e2e.sleep(1_000);
        e2e.expect(location(e2e, comeName).distance(comerAt) < 1.0, "the sender was not delivered into the fight");

        e2e.step("/tpahere: the target accepts, then the sender is attacked during the warmup; the target stays");
        e2e.console("combat untag " + stayName);
        stayer.command("tpahere " + comeName);
        e2e.eventually(() -> comer.chatContains(stayName + " wants you to teleport to them"), "the request: " + comer.chat());
        comer.clearLogs();
        comer.command("tpaccept");
        e2e.dialog(comer, "Teleport request");
        e2e.click(comer, "Accept");
        e2e.eventually(() -> comer.anyFeedbackContains("Accepted " + stayName), "accepted: " + comer.chat() + comer.actionBar());
        e2e.console("combat tag " + stayName + " 60s");
        e2e.eventually(() -> comer.anyFeedbackContains(stayName + " is in combat now."), 10_000, "refused on arrival: " + comer.actionBar());
        e2e.sleep(1_000);
        e2e.expect(location(e2e, comeName).distance(comerAt) < 1.0, "the target was not pulled into the sender's fight");

        e2e.step("without a fight the same request goes through");
        e2e.console("combat untag " + stayName);
        comer.command("tpa " + stayName);
        e2e.eventually(() -> stayer.chatContains(comeName + " wants to teleport to you"), "the request: " + stayer.chat());
        stayer.command("tpaccept");
        e2e.eventually(() -> near(location(e2e, comeName), location(e2e, stayName), 1.0), 15_000, "the sender arrived");
    }

    /** Setting a home is refused in combat, also from the homes dialog's form. */
    static void homesCombat(E2E e2e) {
        String name = e2e.name("HomeFight");
        Bot bot = e2e.bot(name);
        bot.command("homes");
        e2e.dialog(bot, "Homes");
        // /homes and /sethome are refused in combat; the dialog that was already open is the way around that.
        e2e.console("combat tag " + name + " 60s");
        e2e.click(bot, "Set a home here");
        e2e.dialog(bot, "Set a home");
        e2e.click(bot, "Set home", Map.of("name", "fort"));
        Bot.SeenDialog refused = e2e.dialog(bot, "Set a home");
        e2e.expect(refused.bodyText().contains("You can't set a home in combat."), "refused in the form: " + refused.body());
        e2e.expect("0".equals(placeholder(e2e, name, "homes_count")), "no home was set");
        e2e.console("combat untag " + name);
        e2e.click(bot, "Set home", Map.of("name", "fort"));
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "set once the fight is over");
    }

    /** A home someone walled in or flooded with lava asks first; the player decides. */
    static void homesSafety(E2E e2e) {
        String name = e2e.name("Careful");
        Bot bot = e2e.bot(name);
        Location home = location(e2e, name);
        bot.command("sethome");
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "a home was set");
        int[] head = {home.getBlockX(), home.getBlockY() + 1, home.getBlockZ()};
        shift(e2e, bot, 6, 0);

        e2e.step("a block where the head would be: the player is asked and can cancel");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getWorld().getBlockAt(head[0], head[1], head[2]).setType(Material.STONE, false);
            return null;
        });
        Location away = location(e2e, name);
        bot.clearLogs();
        bot.command("home");
        Bot.SeenDialog unsafe = e2e.dialog(bot, "Unsafe home", 15_000);
        e2e.expect(unsafe.bodyText().contains("Home home doesn't look safe: a block fills the space."), "why: " + unsafe.body());
        e2e.click(bot, "Cancel");
        e2e.sleep(500);
        e2e.expect(near(location(e2e, name), away, 0.5), "the player stayed");

        e2e.step("lava is named, and 'Teleport anyway' goes after a new warmup");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getWorld().getBlockAt(head[0], head[1], head[2]).setType(Material.AIR, false);
            e2e.player(name).getWorld().getBlockAt(head[0], head[1] - 1, head[2]).setType(Material.LAVA, false);
            return null;
        });
        e2e.sleep(5_200);
        bot.clearLogs();
        bot.command("home");
        Bot.SeenDialog lava = e2e.dialog(bot, "Unsafe home", 15_000);
        e2e.expect(lava.bodyText().contains("there is lava"), "lava: " + lava.body());
        e2e.onPlayer(name, () -> {
            e2e.player(name).getWorld().getBlockAt(head[0], head[1] - 1, head[2]).setType(Material.AIR, false);
            return null;
        });
        e2e.click(bot, "Teleport anyway");
        e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "a new warmup: " + bot.actionBar());
        e2e.eventually(() -> near(location(e2e, name), home, 0.5), 15_000, "at home after confirming");

        e2e.step("a clear home teleports without asking");
        shift(e2e, bot, 6, 0);
        e2e.sleep(5_200);
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> near(location(e2e, name), home, 0.5), 15_000, "at home");
        e2e.expect(bot.dialogs().stream().noneMatch(dialog -> dialog.title().contains("Unsafe")), "no question: " + bot.dialogs());
    }

    // ------------------------------------------------------------------ teleport requests

    static void tpaFlow(E2E e2e) throws Exception {
        String askerName = e2e.name("Asker");
        String hostName = e2e.name("Host");
        String thirdName = e2e.name("Third");
        Bot asker = e2e.bot(askerName);
        Bot host = e2e.bot(hostName);
        Bot third = e2e.bot(thirdName);
        shift(e2e, host, 10, 0);

        e2e.step("/tpa sends a request the target can answer from chat");
        asker.clearLogs();
        host.clearLogs();
        asker.command("tpa " + hostName);
        e2e.eventually(() -> asker.actionBarContains("Request sent to " + hostName), "sent: " + asker.actionBar());
        e2e.eventually(() -> host.chatContains(askerName + " wants to teleport to you."), "the request in chat: " + host.chat());
        e2e.expect("1".equals(placeholder(e2e, hostName, "tpa_requests")), "tpa_requests is 1");
        e2e.expect(host.openChatDialog("wants to teleport to you"), "the chat message opens a dialog: " + host.chatDialogs());
        Bot.SeenDialog answer = e2e.dialog(host, "Teleport request");
        e2e.expect(answer.bodyText().contains(askerName + " wants to teleport to you."), "who asks: " + answer.body());
        long start = System.currentTimeMillis();
        e2e.click(host, "Accept");
        e2e.eventually(() -> asker.actionBarContains(hostName + " accepted your request"), "accepted: " + asker.actionBar());
        e2e.eventually(() -> near(location(e2e, askerName), location(e2e, hostName), 1.0), 15_000, "the asker went to the host");
        e2e.expect(System.currentTimeMillis() - start >= 2_500, "the warmup applied to the one who moved");
        e2e.expect("0".equals(placeholder(e2e, hostName, "tpa_requests")), "nothing waiting any more");

        e2e.step("/tpahere moves the target, who accepts with /tpaccept and confirms being pulled");
        shift(e2e, host, 0, 10);
        asker.clearLogs();
        host.command("tpahere " + askerName);
        e2e.eventually(() -> asker.chatContains(hostName + " wants you to teleport to them."), "the request: " + asker.chat());
        asker.command("tpaccept");
        Bot.SeenDialog pulled = e2e.dialog(asker, "Teleport request");
        e2e.expect(pulled.bodyText().contains("Accepting teleports you to " + hostName), "asked once more: " + pulled.body());
        e2e.click(asker, "Accept");
        e2e.eventually(() -> near(location(e2e, askerName), location(e2e, hostName), 1.0), 15_000, "the asker came over");

        e2e.step("/tpdeny tells the sender");
        third.clearLogs();
        third.command("tpa " + hostName);
        e2e.eventually(() -> host.chatContains(thirdName + " wants to teleport to you."), "the third request");
        host.command("tpdeny");
        e2e.eventually(() -> third.actionBarContains(hostName + " denied your request"), "denied: " + third.actionBar());

        e2e.step("several requests wait at once; /tpaccept asks which one");
        shift(e2e, host, 10, 0);
        e2e.sleep(5_200);
        asker.command("tpa " + hostName);
        third.command("tpa " + hostName);
        e2e.eventually(() -> "2".equals(placeholder(e2e, hostName, "tpa_requests")), "two requests wait");
        host.clearLogs();
        host.command("tpaccept");
        Bot.SeenDialog choice = e2e.dialog(host, "Teleport requests");
        e2e.expect(choice.button(askerName) != null && choice.button(thirdName) != null, "one button per sender: " + choice.buttons());
        e2e.click(host, thirdName);
        e2e.eventually(() -> near(location(e2e, thirdName), location(e2e, hostName), 1.0), 15_000, "the third player came");
        e2e.expect("1".equals(placeholder(e2e, hostName, "tpa_requests")), "the other request still waits");

        e2e.step("/tpacancel withdraws it");
        asker.clearLogs();
        host.clearLogs();
        asker.command("tpacancel");
        e2e.eventually(() -> asker.actionBarContains("Request to " + hostName + " cancelled"), "cancelled: " + asker.actionBar());
        e2e.eventually(() -> host.actionBarContains(askerName + " cancelled their teleport request"), "the host is told: " + host.actionBar());
        host.clearLogs();
        host.command("tpaccept");
        e2e.eventually(() -> host.actionBarContains("You have no teleport requests"), "nothing to accept: " + host.actionBar());

        e2e.step("moving during the warmup cancels and tells the other player");
        shift(e2e, host, 0, 10);
        e2e.sleep(5_200);
        asker.clearLogs();
        host.clearLogs();
        asker.command("tpa " + hostName);
        e2e.eventually(() -> host.chatContains(askerName + " wants to teleport to you."), "a new request");
        host.command("tpaccept " + askerName);
        e2e.eventually(() -> asker.actionBarContains("Teleporting in"), "the warmup started");
        stepUp(asker);
        e2e.eventually(() -> asker.actionBarContains("Teleport cancelled because you moved"), "cancelled: " + asker.actionBar());
        e2e.eventually(() -> host.actionBarContains(askerName + " didn't teleport"), "the host is told: " + host.actionBar());
        e2e.expect(!near(location(e2e, askerName), location(e2e, hostName), 3), "the asker stayed");
    }

    static void tpaSwitches(E2E e2e) throws Exception {
        String senderName = e2e.name("Sender");
        String targetName = e2e.name("Target");
        Bot sender = e2e.bot(senderName);
        Bot target = e2e.bot(targetName);
        shift(e2e, target, 10, 0);

        e2e.step("no requests to yourself");
        sender.clearLogs();
        sender.command("tpa " + senderName);
        e2e.eventually(() -> sender.actionBarContains("You can't do that to yourself"), "self: " + sender.actionBar());

        e2e.step("/tpatoggle declines requests");
        target.clearLogs();
        target.command("tpatoggle");
        e2e.eventually(() -> target.actionBarContains("Teleport requests to you are now declined"), "off: " + target.actionBar());
        sender.clearLogs();
        sender.command("tpa " + targetName);
        e2e.eventually(() -> sender.actionBarContains(targetName + " isn't taking teleport requests"), "refused: " + sender.actionBar());
        target.command("tpatoggle");
        e2e.eventually(() -> target.actionBarContains("Players can send you teleport requests again"), "on again: " + target.actionBar());
        target.clearLogs();
        target.command("tpatoggle friends");
        e2e.eventually(() -> target.actionBarContains("Friends can now teleport to you without asking."), "friends on: " + target.actionBar());
        target.command("tpatoggle friends");
        e2e.eventually(() -> target.actionBarContains("Friends have to ask before teleporting to you again."), "friends off: " + target.actionBar());

        e2e.step("vanished staff can't be asked");
        op(e2e, targetName, true);
        try {
            target.command("vanish");
            e2e.sleep(1_000);
            sender.clearLogs();
            sender.command("tpa " + targetName);
            e2e.eventually(() -> sender.actionBarContains(targetName + " is not online"), "hidden: " + sender.actionBar());
            target.command("vanish");
            e2e.sleep(1_000);
        } finally {
            op(e2e, targetName, false);
        }

        e2e.step("staff with the bypass go straight there");
        op(e2e, senderName, true);
        try {
            sender.clearLogs();
            sender.command("tpa " + targetName);
            e2e.eventually(() -> sender.actionBarContains("Teleporting to " + targetName), "instant: " + sender.actionBar());
            e2e.eventually(() -> near(location(e2e, senderName), location(e2e, targetName), 1.0), "the staff member is there");
        } finally {
            op(e2e, senderName, false);
        }

        e2e.step("the menu form sends a request");
        shift(e2e, target, 0, 10);
        sender.clearLogs();
        target.clearLogs();
        sender.command("menu");
        e2e.dialog(sender, "SiftVanilla");
        e2e.click(sender, "Teleport to a player");
        Bot.SeenDialog form = e2e.dialog(sender, "Teleport request");
        e2e.expect(form.inputs().containsKey("player") && form.inputs().containsKey("direction"), "player and direction: " + form.inputs());
        e2e.click(sender, "Send request", Map.of("player", "Nobody_" + e2e.name("x"), "direction", "to"));
        Bot.SeenDialog retry = e2e.dialog(sender, "Teleport request");
        e2e.expect(retry.bodyText().contains("is not online"), "an unknown player: " + retry.body());
        e2e.click(sender, "Send request", Map.of("player", targetName, "direction", "here"));
        e2e.eventually(() -> target.chatContains(senderName + " wants you to teleport to them."), "the request: " + target.chat());
        target.command("tpdeny");

        e2e.step("requests expire on their own");
        Path config = e2e.services().plugin().getDataFolder().toPath().resolve("features/tpa.yml");
        String original = Files.readString(config);
        e2e.expect(original.contains("expire-after: 60s"), "the default expiry");
        try {
            Files.writeString(config, original.replace("expire-after: 60s", "expire-after: 10s"));
            e2e.console("sift reload");
            e2e.sleep(5_200);
            sender.clearLogs();
            target.clearLogs();
            sender.command("tpa " + targetName);
            e2e.eventually(() -> sender.actionBarContains("It expires in 10s"), "a 10s request: " + sender.actionBar());
            e2e.eventually(() -> sender.actionBarContains("Your request to " + targetName + " expired"), 15_000,
                "expired: " + sender.actionBar());
            target.command("tpaccept");
            e2e.eventually(() -> target.actionBarContains("You have no teleport requests"), "nothing left: " + target.actionBar());
        } finally {
            Files.writeString(config, original);
            e2e.console("sift reload");
        }
    }

    // ------------------------------------------------------------------ random teleport

    private static final String RING = "e2e-ring";

    /** Test regions: a ring around the spawn, a small nether ring, and a ring where no chunk exists. */
    private static String testRegions(Location spawn) {
        return String.format(Locale.ROOT, """

              %s:
                name: "Test ring"
                enabled: true
                world: %s
                permission: ""
                cost: 1000
                cooldown: 30s
                center-x: %d
                center-z: %d
                min-radius: 80
                max-radius: 112
              nether-test:
                name: "Nether test"
                enabled: true
                world: world_nether
                permission: ""
                cost: 0
                cooldown: 0s
                center-x: 0
                center-z: 0
                min-radius: 16
                max-radius: 64
              far-test:
                name: "Far test"
                enabled: true
                world: %s
                permission: ""
                cost: 500
                cooldown: 0s
                center-x: 4000
                center-z: 4000
                min-radius: 0
                max-radius: 40
            """, RING, spawn.getWorld().getName(), spawn.getBlockX(), spawn.getBlockZ(), spawn.getWorld().getName());
    }

    /** Pays only for a teleport that happened, lands safely inside the ring, cooldowns, the nether, staff sends. */
    static void rtpFlow(E2E e2e) throws Exception {
        String name = e2e.name("Roamer");
        Bot bot = e2e.bot(name);
        Location spawn = spawn(e2e);
        Path config = e2e.services().plugin().getDataFolder().toPath().resolve("features/rtp.yml");
        String original = Files.readString(config);
        try {
            e2e.step("test regions are added and their land generated");
            Files.writeString(config, original.stripTrailing() + "\n" + testRegions(spawn));
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(reload.stream().anyMatch(line -> line.startsWith("Reloaded")), "the test regions load: " + reload);
            generate(e2e, spawn.getWorld(), spawn.getBlockX(), spawn.getBlockZ(), 120);
            generate(e2e, Bukkit.getWorld("world_nether"), 0, 0, 72);
            e2e.console("eco set " + name + " 5000");
            e2e.eventually(() -> e2e.money(name) == 5_000, "funded");

            e2e.step("/rtp lists the regions with their costs");
            bot.clearLogs();
            bot.command("rtp");
            Bot.SeenDialog menu = e2e.dialog(bot, "Random teleport");
            e2e.expect(menu.button("Overworld") != null && menu.button("Nether") != null && menu.button("End") != null
                && menu.button("Test ring") != null, "the regions: " + menu.buttons());
            e2e.expect(menu.bodyText().contains("$1,000") && menu.bodyText().contains("$2,500"), "the costs: " + menu.body());

            e2e.step("the chosen region: warmup, search, pay once, land safely in the ring");
            e2e.click(bot, "Test ring");
            e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "the warmup");
            e2e.expect(e2e.money(name) == 5_000, "nothing charged during the warmup");
            e2e.eventually(() -> {
                double distance = flatDistance(location(e2e, name), spawn.getBlockX(), spawn.getBlockZ());
                return distance >= 79 && distance <= 113;
            }, 40_000, "landed in the ring, at " + location(e2e, name));
            e2e.eventually(() -> e2e.money(name) == 4_000, "charged $1,000 once: " + e2e.money(name));
            e2e.eventually(() -> bot.actionBarContains("Welcome to Test ring") && bot.actionBarContains("Paid $1,000"),
                "a landing message: " + bot.actionBar());
            Location landed = location(e2e, name);
            e2e.expect(!area(e2e).contains(landed), "not inside spawn");
            String ground = e2e.onPlayer(name, () -> {
                Block feet = e2e.player(name).getLocation().getBlock();
                return feet.getRelative(0, -1, 0).getType() + "/" + feet.getType() + "/" + feet.getRelative(0, 1, 0).getType();
            });
            e2e.expect(safe(e2e, name), "safe ground with room to stand (below/feet/head = " + ground + ")");

            e2e.step("the region's cooldown");
            bot.clearLogs();
            bot.command("rtp " + RING);
            e2e.eventually(() -> bot.actionBarContains("You can random teleport to Test ring again in"), "a cooldown: " + bot.actionBar());
            e2e.expect(e2e.money(name) == 4_000, "nothing charged");

            e2e.step("the nether: a cave floor below the roof");
            bot.clearLogs();
            bot.command("rtp nether-test");
            e2e.eventually(() -> location(e2e, name).getWorld().getName().equals("world_nether"), 40_000, "in the nether");
            Location nether = location(e2e, name);
            e2e.expect(nether.getY() >= 32 && nether.getY() <= 121, "below the bedrock roof: " + nether);
            e2e.expect(safe(e2e, name), "safe ground in the nether");
            e2e.expect(e2e.money(name) == 4_000, "the nether test region is free");

            e2e.step("the console sends a player at once, free and without cooldown");
            output(e2e, "rtp " + RING + " " + name, "Sent " + name + " to Test ring at", 40_000);
            e2e.eventually(() -> {
                Location at = location(e2e, name);
                double distance = flatDistance(at, spawn.getBlockX(), spawn.getBlockZ());
                return at.getWorld().equals(spawn.getWorld()) && distance >= 79 && distance <= 113;
            }, 40_000, "sent into the ring");
            e2e.expect(e2e.money(name) == 4_000, "staff sends are free");
        } finally {
            Files.writeString(config, original);
            e2e.console("sift reload");
        }
    }

    /** Solid ground below, room for feet and head, no liquid. Runs on the player's thread. */
    private static boolean safe(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> {
            Block feet = e2e.player(name).getLocation().getBlock();
            Block below = feet.getRelative(0, -1, 0);
            Block head = feet.getRelative(0, 1, 0);
            return below.getType().isSolid() && !below.isLiquid() && feet.isPassable() && !feet.isLiquid()
                && head.isPassable() && !head.isLiquid() && below.getType() != Material.MAGMA_BLOCK;
        });
    }

    /** Not enough money, no land (nothing charged, nothing generated) and a ring past the border refused on reload. */
    static void rtpLimits(E2E e2e) throws Exception {
        String poorName = e2e.name("Pauper");
        String farName = e2e.name("Farer");
        Bot poor = e2e.bot(poorName);
        Bot far = e2e.bot(farName);
        Location spawn = spawn(e2e);
        Path config = e2e.services().plugin().getDataFolder().toPath().resolve("features/rtp.yml");
        String original = Files.readString(config);
        try {
            Files.writeString(config, original.stripTrailing() + "\n" + testRegions(spawn));
            e2e.console("sift reload");

            e2e.step("not enough money: refused before the warmup");
            e2e.console("eco set " + poorName + " 500");
            e2e.eventually(() -> e2e.money(poorName) == 500, "funded with $500");
            poor.clearLogs();
            poor.command("rtp " + RING);
            e2e.eventually(() -> poor.actionBarContains("You need $1,000 for that"), "too poor: " + poor.actionBar());
            e2e.sleep(1_000);
            e2e.expect(!poor.actionBarContains("Teleporting in"), "no warmup started");
            e2e.expect(e2e.money(poorName) == 500, "nothing charged");

            e2e.step("a ring without generated land: nothing charged, nothing generated");
            World world = spawn.getWorld();
            e2e.expect(!anyGenerated(world, 4000, 4000, 40), "the far ring has no chunks yet");
            e2e.console("eco set " + farName + " 5000");
            e2e.eventually(() -> e2e.money(farName) == 5_000, "funded");
            Location before = location(e2e, farName);
            far.clearLogs();
            far.command("rtp far-test");
            Bot.SeenDialog price = e2e.dialog(far, "Confirm random teleport");
            e2e.expect(price.bodyText().contains("A random teleport to Far test costs $500"), "the price first: " + price.body());
            e2e.click(far, "Teleport");
            e2e.eventually(() -> far.actionBarContains("No safe spot found this time. Nothing was charged."), 40_000,
                "no spot: " + far.actionBar());
            e2e.expect(e2e.money(farName) == 5_000, "nothing charged: " + e2e.money(farName));
            e2e.expect(near(location(e2e, farName), before, 1.0), "the player stayed");
            e2e.expect(!anyGenerated(world, 4000, 4000, 40), "the search generated no terrain");

            e2e.step("a ring past the world border is refused with the exact limit");
            Files.writeString(config, original.replace("max-radius: 4800", "max-radius: 5000"));
            List<String> refused = e2e.consoleOutput("sift reload");
            e2e.expect(refused.stream().anyMatch(line -> line.startsWith("Nothing was reloaded")), "the reload is refused: " + refused);
            e2e.expect(refused.stream().anyMatch(line -> line.contains("regions.overworld.max-radius") && line.contains("lower it to 4,968")),
                "a precise problem: " + refused);

            e2e.step("shrinking a world border under a ring is refused too");
            Files.writeString(config, original);
            Path spawnConfig = e2e.services().plugin().getDataFolder().toPath().resolve("features/spawn.yml");
            String spawnOriginal = Files.readString(spawnConfig);
            e2e.expect(spawnOriginal.contains("size: 10000"), "the default overworld border");
            try {
                Files.writeString(spawnConfig, spawnOriginal.replace("size: 10000", "size: 9000"));
                List<String> shrunk = e2e.consoleOutput("sift reload");
                e2e.expect(shrunk.stream().anyMatch(line -> line.contains("regions.overworld.max-radius") && line.contains("lower it to 4,468")),
                    "the ring no longer fits the planned border: " + shrunk);
                e2e.expect(Math.round(spawn.getWorld().getWorldBorder().getSize()) == 10_000, "the live border was not touched");
            } finally {
                Files.writeString(spawnConfig, spawnOriginal);
            }
        } finally {
            Files.writeString(config, original);
            List<String> restored = e2e.consoleOutput("sift reload");
            e2e.expect(restored.stream().anyMatch(line -> line.startsWith("Reloaded")), "the original file loads again: " + restored);
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * The teleport request settings: who can send requests (a choice saved in the settings dialog: friends only), who
     * can ask the player over (typed as /settings tpahere-requests nobody), the pop-up (held back while the player
     * clicks in their own inventory), the confirmation before being pulled (also when the /tpahere is picked in the
     * window of several requests), /tpatoggle under a server lock written the old way, and /tpatoggle friends with a
     * choice, and with an unknown word while the server hides the setting.
     */
    static void tpaSettings(E2E e2e) throws Exception {
        String hostName = e2e.name("TsHost");
        String palName = e2e.name("TsPal");
        String strangerName = e2e.name("TsStranger");
        Bot host = e2e.bot(hostName);
        Bot pal = e2e.bot(palName);
        Bot stranger = e2e.bot(strangerName);
        UUID hostId = e2e.uuid(hostName);
        shift(e2e, host, 10, 0);
        e2e.expect(e2e.services().relations().friendsAvailable(), "the test server runs the friends feature");
        befriend(e2e, hostName, palName);

        e2e.step("the settings dialog: Teleport requests from friends");
        editTeleportSettings(e2e, host, Map.of("tpa_requests", "friends"));
        e2e.eventually(() -> host.anyFeedbackContains("Teleport requests from set to Friends"), "saved: " + host.actionBar() + host.chat());
        e2e.eventually(() -> settings(e2e).get(hostId, TpaFeature.REQUESTS) == Audience.FRIENDS, "stored as friends");

        e2e.step("a stranger is refused, a friend's request arrives");
        stranger.clearLogs();
        stranger.command("tpa " + hostName);
        e2e.eventually(() -> stranger.anyFeedbackContains(hostName + " isn't taking teleport requests"), "refused: " + stranger.actionBar());
        e2e.expect(!host.chatContains(strangerName + " wants"), "the host never hears of it: " + host.chat());
        host.clearLogs();
        pal.command("tpa " + hostName);
        e2e.eventually(() -> host.chatContains(palName + " wants to teleport to you."), "the friend's request: " + host.chat());
        e2e.expect(host.dialogs().stream().noneMatch(dialog -> dialog.title().contains("Teleport request")), "no pop-up by default");
        host.command("tpdeny");

        e2e.step("/settings: Pull requests from nobody refuses a friend's /tpahere, their /tpa still works");
        setting(e2e, host, "tpahere-requests", "nobody");
        e2e.sleep(5_200);
        pal.clearLogs();
        pal.command("tpahere " + hostName);
        e2e.eventually(() -> pal.anyFeedbackContains(hostName + " isn't taking requests to come to you"), "refused: " + pal.actionBar());

        e2e.step("the pop-up waits while the player is clicking in their own inventory");
        setting(e2e, host, "tpa-popup", "on");
        e2e.sleep(5_200);
        host.clearLogs();
        host.clickSlot(9);
        e2e.sleep(300);
        pal.command("tpa " + hostName);
        e2e.eventually(() -> host.chatContains(palName + " wants to teleport to you."), "the request: " + host.chat());
        e2e.sleep(1_000);
        e2e.expect(host.dialogs().stream().noneMatch(dialog -> dialog.title().contains("Teleport request")),
            "no pop-up over the inventory being sorted: " + host.dialogs());
        host.command("tpdeny");
        e2e.eventually(() -> pal.anyFeedbackContains(hostName + " denied your request"), "cleared: " + pal.actionBar());

        e2e.step("a request opens the answer window when the player wants a pop-up");
        e2e.sleep(5_200);
        host.clearLogs();
        pal.command("tpa " + hostName);
        Bot.SeenDialog popped = e2e.dialog(host, "Teleport request");
        e2e.expect(popped.bodyText().contains(palName + " wants to teleport to you."), "the request's window: " + popped.body());
        e2e.expect(host.chatContains(palName + " wants to teleport to you."), "the chat line stays: " + host.chat());
        e2e.click(host, "Accept");
        e2e.eventually(() -> near(location(e2e, palName), location(e2e, hostName), 1.0), 15_000, "the friend came over");

        e2e.step("being pulled asks once more (the default); Deny there answers the request");
        setting(e2e, host, "tpa-popup", "off");
        setting(e2e, host, "tpahere-requests", "everyone");
        shift(e2e, pal, 12, 0);
        e2e.sleep(5_200);
        host.clearLogs();
        pal.clearLogs();
        pal.command("tpahere " + hostName);
        e2e.eventually(() -> host.chatContains(palName + " wants you to teleport to them."), "the pull request: " + host.chat());
        host.command("tpaccept");
        Bot.SeenDialog again = e2e.dialog(host, "Teleport request");
        e2e.expect(again.bodyText().contains("Accepting teleports you to " + palName), "asked once more: " + again.body());
        e2e.click(host, "Deny");
        e2e.eventually(() -> pal.anyFeedbackContains(hostName + " denied your request"), "denied: " + pal.actionBar());

        e2e.step("two requests waiting: picking the /tpahere sender in the window asks once more");
        String mateName = e2e.name("TsMate");
        Bot mate = e2e.bot(mateName);
        befriend(e2e, hostName, mateName);
        shift(e2e, mate, -12, 0);
        e2e.sleep(5_200);
        host.clearLogs();
        pal.clearLogs();
        mate.command("tpa " + hostName);
        pal.command("tpahere " + hostName);
        e2e.eventually(() -> host.chatContains(mateName + " wants to teleport to you.")
            && host.chatContains(palName + " wants you to teleport to them."), "both requests: " + host.chat());
        host.command("tpaccept");
        Bot.SeenDialog several = e2e.dialog(host, "Teleport requests");
        e2e.expect(several.button(palName) != null && several.button(mateName) != null, "one button per sender: " + several.buttons());
        Location hostBefore = location(e2e, hostName);
        e2e.click(host, palName);
        e2e.eventually(() -> host.dialog() != null && host.dialog().title().equals("Teleport request"), "the request's own window: "
            + host.dialogs());
        e2e.expect(host.dialog().bodyText().contains("Accepting teleports you to " + palName), "asked once more: " + host.dialog().body());
        e2e.sleep(1_000);
        e2e.expect(near(location(e2e, hostName), hostBefore, 0.5) && !host.actionBarContains("Teleporting in"),
            "not pulled before saying yes: " + host.actionBar());
        e2e.click(host, "Deny");
        e2e.eventually(() -> pal.anyFeedbackContains(hostName + " denied your request"), "denied: " + pal.actionBar());

        e2e.step("picking the /tpa sender in the window accepts at once and closes it");
        e2e.sleep(5_200);
        host.clearLogs();
        pal.command("tpahere " + hostName);
        e2e.eventually(() -> host.chatContains(palName + " wants you to teleport to them."), "the pull again: " + host.chat());
        host.command("tpaccept");
        Bot.SeenDialog again2 = e2e.dialog(host, "Teleport requests");
        e2e.click(host, mateName);
        e2e.eventually(() -> host.anyFeedbackContains("Accepted " + mateName), "accepted at once: " + host.actionBar() + host.chat());
        e2e.expect(host.dialog() == null || !host.dialog().title().startsWith("Teleport request"), "no second question: " + host.dialog());
        e2e.eventually(() -> near(location(e2e, mateName), location(e2e, hostName), 1.0), 15_000, "the friend came over");
        e2e.expect(again2.button(palName) != null, "the pull was listed too: " + again2.buttons());
        host.command("tpdeny");
        e2e.eventually(() -> pal.anyFeedbackContains(hostName + " denied your request"), "the pull cleared: " + pal.actionBar());

        e2e.step("with Confirm before being pulled off, /tpaccept moves at once");
        setting(e2e, host, "tpaccept-confirm-here", "off");
        e2e.sleep(5_200);
        host.clearLogs();
        pal.command("tpahere " + hostName);
        e2e.eventually(() -> host.chatContains(palName + " wants you to teleport to them."), "the pull request: " + host.chat());
        host.command("tpaccept");
        e2e.eventually(() -> host.anyFeedbackContains("Accepted " + palName), "accepted at once: " + host.actionBar() + host.chat());
        e2e.eventually(() -> near(location(e2e, hostName), location(e2e, palName), 1.0), 15_000, "the host was pulled over");
        e2e.expect(host.dialogs().stream().noneMatch(dialog -> dialog.title().contains("Teleport request")), "no window: " + host.dialogs());

        e2e.step("/tpatoggle says when the server locked the setting (a lock written as the old switch)");
        Path file = e2e.services().plugin().getDataFolder().toPath().resolve("features/settings.yml");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        try {
            Files.writeString(file, original.stripTrailing() + "\nlocked:\n  tpa-requests: false\n", StandardCharsets.UTF_8);
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the lock loads: " + reload);
            e2e.expect(settings(e2e).get(hostId, TpaFeature.REQUESTS) == Audience.NOBODY, "locked off reads as nobody");
            host.clearLogs();
            host.command("tpatoggle");
            e2e.eventually(() -> host.anyFeedbackContains("Teleport requests from is set by the server."), "fixed: " + host.actionBar());
            stranger.clearLogs();
            e2e.sleep(5_200);
            stranger.command("tpa " + hostName);
            e2e.eventually(() -> stranger.anyFeedbackContains(hostName + " isn't taking teleport requests"), "locked: " + stranger.actionBar());
        } finally {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
        e2e.expect(settings(e2e).get(hostId, TpaFeature.REQUESTS) == Audience.FRIENDS, "the player's own choice is back");
        host.clearLogs();
        host.command("tpatoggle");
        e2e.eventually(() -> host.anyFeedbackContains("Teleport requests to you are now declined"), "off: " + host.actionBar());
        host.command("tpatoggle");
        e2e.eventually(() -> host.anyFeedbackContains("Players can send you teleport requests again"), "on: " + host.actionBar());
        e2e.expect(settings(e2e).get(hostId, TpaFeature.REQUESTS) == Audience.EVERYONE, "back on is everyone");

        e2e.step("/tpatoggle friends with a choice: friends and teammates come without asking");
        host.clearLogs();
        host.command("tpatoggle friends friends-team");
        e2e.eventually(() -> host.anyFeedbackContains("Accepting /tpa without asking from: Friends and teammates."), "set: " + host.actionBar());
        e2e.expect(settings(e2e).get(hostId, SharedSettings.FRIENDS_TPA) == AutoAccept.FRIENDS_TEAM, "stored");
        shift(e2e, pal, 12, 0);
        e2e.sleep(5_200);
        pal.clearLogs();
        pal.command("tpa " + hostName);
        e2e.eventually(() -> pal.anyFeedbackContains(hostName + " lets friends come without asking."), "came without asking: " + pal.actionBar());
        e2e.eventually(() -> near(location(e2e, palName), location(e2e, hostName), 1.0), 15_000, "the friend arrived");
        host.clearLogs();
        host.command("tpatoggle friends");
        e2e.eventually(() -> host.anyFeedbackContains("Friends have to ask before teleporting to you again."), "off: " + host.actionBar());
        host.command("tpatoggle friends sometimes");
        e2e.eventually(() -> host.anyFeedbackContains("Use one of these: nobody,"), "the choices: " + host.actionBar());

        e2e.step("/tpatoggle friends with an unknown word while the server hides the setting: set by the server");
        // The bundled file already has "hidden: []": replace it (a second hidden: key would not be valid YAML).
        String hiding = original.contains("hidden: []") ? original.replace("hidden: []", "hidden: [friends-tpa]")
            : original.stripTrailing() + "\nhidden: [friends-tpa]\n";
        Files.writeString(file, hiding, StandardCharsets.UTF_8);
        try {
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the hide loads: " + reload);
            host.clearLogs();
            host.command("tpatoggle friends sometimes");
            e2e.eventually(() -> host.anyFeedbackContains("Auto-accept /tpa from is set by the server."), "fixed: " + host.actionBar());
            e2e.expect(!host.anyFeedbackContains("Use one of these"), "no empty list: " + host.actionBar() + host.chat());
        } finally {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /**
     * The homes settings: "/home with no name" and "Confirm moving a home" saved in the settings dialog, the home named
     * 'home' typed with /settings, and streamer mode (hide-coordinates) in the homes list and the delete window.
     */
    static void homesSettings(E2E e2e) {
        String name = e2e.name("HsOwner");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        bot.command("sethome");
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "a home was set");

        e2e.step("the settings dialog: /home always opens the list, moving a home doesn't ask");
        editTeleportSettings(e2e, bot, Map.of("homes_bare_command", "list", "homes_confirm_overwrite", false));
        e2e.eventually(() -> settings(e2e).get(id, HomesFeature.BARE_COMMAND) == BareHome.LIST
            && !settings(e2e).get(id, HomesFeature.CONFIRM_OVERWRITE), "both saved");
        bot.clearLogs();
        bot.command("home");
        Bot.SeenDialog list = e2e.dialog(bot, "Homes");
        e2e.expect(list.button("home") != null, "the list, although there is only one home: " + list.buttons());
        e2e.click(bot, "Close");
        Location moved = shift(e2e, bot, 5, 0);
        bot.clearLogs();
        bot.command("sethome");
        e2e.eventually(() -> bot.actionBarContains("Home home moved here"), "moved without asking: " + bot.actionBar());
        e2e.expect(bot.dialogs().stream().noneMatch(dialog -> dialog.title().contains("Move home")), "no question: " + bot.dialogs());

        e2e.step("/settings: /home goes to the home named home");
        setting(e2e, bot, "homes-bare-command", "default-home");
        shift(e2e, bot, 6, 0);
        bot.command("sethome other");
        e2e.eventually(() -> "2".equals(placeholder(e2e, name, "homes_count")), "a second home");
        shift(e2e, bot, 6, 0);
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> near(location(e2e, name), moved, 0.5), 15_000, "at the home named home");
        e2e.expect(bot.dialogs().stream().noneMatch(dialog -> dialog.title().equals("Homes")), "no list: " + bot.dialogs());

        e2e.step("streamer mode leaves the coordinates out of the list and the delete window");
        setting(e2e, bot, "hide-coordinates", "on");
        String position = moved.getBlockX() + ", " + moved.getBlockY() + ", " + moved.getBlockZ();
        bot.clearLogs();
        bot.command("homes");
        Bot.SeenDialog hidden = e2e.dialog(bot, "Homes");
        e2e.expect(hidden.bodyText().contains("home " + moved.getWorld().getName()) && !hidden.bodyText().contains(position),
            "no coordinates: " + hidden.body());
        bot.clearLogs();
        bot.command("delhome home");
        Bot.SeenDialog delete = e2e.dialog(bot, "Delete home");
        e2e.expect(!delete.bodyText().contains(position), "no coordinates when deleting: " + delete.body());
        e2e.click(bot, "Cancel");
        setting(e2e, bot, "hide-coordinates", "off");
        bot.clearLogs();
        bot.command("homes");
        Bot.SeenDialog shown = e2e.dialog(bot, "Homes");
        e2e.expect(shown.bodyText().contains(position), "the coordinates are back: " + shown.body());
    }

    /**
     * "Teleport countdown": one chat line instead of the countdown above the hotbar (saved in the settings dialog), a
     * title (typed with /settings), and off, where only the cancel message shows.
     */
    static void teleportDisplay(E2E e2e) {
        String name = e2e.name("TdWalker");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Location home = location(e2e, name);
        bot.command("sethome");
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "homes_count")), "a home was set");

        e2e.step("the settings dialog: the countdown and arrival in chat");
        editTeleportSettings(e2e, bot, Map.of("teleport_display", "chat"));
        e2e.eventually(() -> settings(e2e).get(id, Teleports.DISPLAY) == AlertStyle.CHAT, "saved as chat");
        shift(e2e, bot, 6, 0);
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> near(location(e2e, name), home, 0.5), 15_000, "at home");
        e2e.eventually(() -> bot.chatContains("Welcome to home."), "the arrival in chat: " + bot.chat());
        long countdown = bot.chat().stream().filter(line -> line.contains("Teleporting in")).count();
        e2e.expect(countdown == 1 && bot.chatContains("Teleporting in 3s"), "one countdown line in chat: " + bot.chat());
        e2e.expect(!bot.actionBarContains("Teleporting in") && !bot.actionBarContains("Welcome to home"), "nothing above the hotbar: "
            + bot.actionBar());

        e2e.step("/settings: as a title");
        setting(e2e, bot, "teleport-display", "title");
        shift(e2e, bot, 6, 0);
        e2e.sleep(5_200);
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> near(location(e2e, name), home, 0.5), 15_000, "at home");
        e2e.eventually(() -> bot.titles().stream().anyMatch(title -> title.contains("Welcome to home.")), "the arrival title: " + bot.titles());
        e2e.expect(bot.titles().stream().anyMatch(title -> title.contains("Teleporting in")), "the countdown title: " + bot.titles());
        e2e.expect(!bot.anyFeedbackContains("Teleporting in"), "nowhere else: " + bot.chat() + bot.actionBar());

        e2e.step("as a title, moving cancels: the cancel line shows and the countdown stops");
        shift(e2e, bot, 6, 0);
        e2e.sleep(5_200);
        bot.clearLogs();
        bot.command("home");
        e2e.eventually(() -> bot.titles().stream().anyMatch(title -> title.contains("Teleporting in")), "the countdown title: "
            + bot.titles());
        stepUp(bot);
        e2e.eventually(() -> bot.anyFeedbackContains("Teleport cancelled because you moved."), "the cancel line: " + bot.actionBar());
        int titlesSeen = bot.titles().size();
        e2e.sleep(2_500);
        e2e.expect(bot.titles().size() == titlesSeen, "no countdown title after the cancel: " + bot.titles());

        e2e.step("off: no countdown, but the cancel message shows");
        setting(e2e, bot, "teleport-display", "off");
        shift(e2e, bot, 6, 0);
        e2e.sleep(5_200);
        bot.clearLogs();
        bot.command("home");
        e2e.sleep(1_500);
        stepUp(bot);
        e2e.eventually(() -> bot.anyFeedbackContains("Teleport cancelled because you moved."), "the cancel message: " + bot.actionBar());
        e2e.expect(!bot.anyFeedbackContains("Teleporting in") && bot.titles().isEmpty(), "no countdown anywhere: " + bot.chat()
            + bot.actionBar() + bot.titles());
        setting(e2e, bot, "teleport-display", "actionbar");
    }

    /**
     * Random teleport settings: the price confirmation (asked by default, Cancel charges nothing; turned off in the
     * settings dialog), "/rtp with no region: last region" typed with /settings (the picker while that region cools down),
     * and streamer mode in the landing line.
     */
    static void rtpSettings(E2E e2e) throws Exception {
        String name = e2e.name("RsRoamer");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Location spawn = spawn(e2e);
        Path config = e2e.services().plugin().getDataFolder().toPath().resolve("features/rtp.yml");
        String original = Files.readString(config);
        try {
            Files.writeString(config, original.stripTrailing() + "\n" + testRegions(spawn));
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(reload.stream().anyMatch(line -> line.startsWith("Reloaded")), "the test regions load: " + reload);
            generate(e2e, spawn.getWorld(), spawn.getBlockX(), spawn.getBlockZ(), 120);
            generate(e2e, Bukkit.getWorld("world_nether"), 0, 0, 72);
            e2e.console("eco set " + name + " 5000");
            e2e.eventually(() -> e2e.money(name) == 5_000, "funded");

            e2e.step("a typed paid /rtp shows the price first; Cancel charges nothing");
            bot.clearLogs();
            bot.command("rtp " + RING);
            Bot.SeenDialog price = e2e.dialog(bot, "Confirm random teleport");
            e2e.expect(price.bodyText().contains("A random teleport to Test ring costs $1,000"), "the price: " + price.body());
            e2e.click(bot, "Cancel");
            e2e.sleep(1_000);
            e2e.expect(!bot.actionBarContains("Teleporting in") && e2e.money(name) == 5_000, "nothing started or charged");

            e2e.step("the settings dialog: paid random teleports start without asking");
            editTeleportSettings(e2e, bot, Map.of("rtp_confirm_cost", false));
            e2e.eventually(() -> !settings(e2e).get(id, RtpFeature.CONFIRM_COST), "saved");
            bot.clearLogs();
            bot.command("rtp " + RING);
            e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "the warmup at once: " + bot.actionBar());
            e2e.expect(bot.dialogs().stream().noneMatch(dialog -> dialog.title().contains("Confirm")), "no question: " + bot.dialogs());
            e2e.eventually(() -> e2e.money(name) == 4_000, 40_000, "charged once: " + e2e.money(name));
            e2e.eventually(() -> bot.actionBarContains("Welcome to Test ring at "), "landed with the position: " + bot.actionBar());

            e2e.step("/settings: /rtp goes to the last region, or the picker while it cools down");
            setting(e2e, bot, "rtp-default", "last");
            e2e.eventually(() -> RING.equals(settings(e2e).raw(id, "rtp_last", null)), "the last region is remembered");
            bot.clearLogs();
            bot.command("rtp");
            Bot.SeenDialog picker = e2e.dialog(bot, "Random teleport");
            e2e.expect(picker.button("Test ring") != null && !picker.title().contains("Confirm"), "the picker: " + picker.buttons());
            e2e.click(bot, "Close");

            e2e.step("streamer mode: the landing line without the position");
            setting(e2e, bot, "hide-coordinates", "on");
            bot.clearLogs();
            bot.command("rtp nether-test");
            e2e.eventually(() -> location(e2e, name).getWorld().getName().equals("world_nether"), 40_000, "in the nether");
            e2e.eventually(() -> bot.actionBarContains("Welcome to Nether test."), "landed: " + bot.actionBar());
            e2e.expect(!bot.actionBarContains("Nether test at "), "no position: " + bot.actionBar());
            e2e.eventually(() -> "nether-test".equals(settings(e2e).raw(id, "rtp_last", null)), "the new last region");

            e2e.step("/rtp with no region now goes straight to the nether test");
            Location before = location(e2e, name);
            bot.clearLogs();
            bot.command("rtp");
            e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "straight into the warmup: " + bot.actionBar());
            e2e.expect(bot.dialogs().stream().noneMatch(dialog -> dialog.title().equals("Random teleport")), "no picker: " + bot.dialogs());
            e2e.eventually(() -> bot.actionBarContains("Welcome to Nether test.") && !near(location(e2e, name), before, 2), 40_000,
                "a new spot in the nether: " + location(e2e, name));
            setting(e2e, bot, "hide-coordinates", "off");
        } finally {
            Files.writeString(config, original);
            e2e.console("sift reload");
        }
    }
}
