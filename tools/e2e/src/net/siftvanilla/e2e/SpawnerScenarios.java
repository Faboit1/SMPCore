package net.siftvanilla.e2e;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.feature.spawners.SpawnerPlayerSettings;
import net.siftvanilla.siftcore.feature.spawners.SpawnersFeature;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

/**
 * Spawners end to end: buying one in the shop, placing, stacking, refusals, virtual loot, the storage menu (take,
 * collect XP, sell all), picking up with silk touch, access for strangers and team members, /spawners and its
 * dialogs (with the team list), activation (vanished and far players keep nothing going), storages closed in combat,
 * staff pickups (a vanished moderator stays unnamed), protection from explosions, the refund of a spawner whose
 * block an admin tool removed, and spawner rewards in crate previews.
 */
final class SpawnerScenarios {

    /** Raw slots of the storage menu's bottom row. */
    private static final int SLOT_BACK = 46;
    private static final int SLOT_XP = 50;
    private static final int SLOT_SELL = 51;
    private static final int SLOT_INFO = 52;

    private SpawnerScenarios() {
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
        list.add(of("spawners-place-stack", SpawnerScenarios::placeAndStack));
        list.add(of("spawners-storage", SpawnerScenarios::storage));
        list.add(of("spawners-pickup", SpawnerScenarios::pickUp));
        list.add(of("spawners-access", SpawnerScenarios::access));
        list.add(of("spawners-dialog", SpawnerScenarios::dialog));
        list.add(of("spawners-shop", SpawnerScenarios::shop));
        list.add(of("spawners-protection", SpawnerScenarios::protection));
        list.add(of("spawners-activation", SpawnerScenarios::activation));
        list.add(of("spawners-combat", SpawnerScenarios::combat));
        list.add(of("spawners-staff", SpawnerScenarios::staff));
        list.add(of("spawners-refund", SpawnerScenarios::refund));
        list.add(of("spawners-crates", SpawnerScenarios::crates));
        list.add(of("spawners-persist", SpawnerScenarios::persist));
        list.add(of("spawners-left-before-commit", SpawnerScenarios::leftBeforeCommit));
        list.add(of("spawners-settings", SpawnerScenarios::settings));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static SpawnerItems items(E2E e2e) {
        return e2e.feature(SpawnersFeature.class).items();
    }

    private static String placeholder(E2E e2e, String player, String name) {
        String value = e2e.services().placeholders().resolve(e2e.player(player), name);
        return value == null ? "" : value;
    }

    private static long number(E2E e2e, String player, String name) {
        return Long.parseLong(placeholder(e2e, player, name));
    }

    /** SiftCore spawner items of a mob in the player's inventory. */
    private static int spawnerItems(E2E e2e, String name, String mob) {
        ItemStack unit = items(e2e).create(mob, 1).orElseThrow();
        return e2e.onPlayer(name, () -> {
            int total = 0;
            for (ItemStack stack : e2e.player(name).getInventory().getContents()) {
                if (stack != null && stack.isSimilar(unit)) {
                    total += stack.getAmount();
                }
            }
            return total;
        });
    }

    private static int count(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            int total = 0;
            for (ItemStack stack : e2e.player(name).getInventory().getContents()) {
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            return total;
        });
    }

    private static void clear(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            return null;
        });
    }

    /** Puts {@code item} in hotbar slot {@code slot} and selects it. */
    private static void hold(E2E e2e, String name, int slot, ItemStack item) {
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.setItem(slot, item);
            inventory.setHeldItemSlot(slot);
            return null;
        });
    }

    /** Selects a hotbar slot. */
    private static void select(E2E e2e, String name, int slot) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setHeldItemSlot(slot);
            return null;
        });
    }

    /** A block next to the player that their thread owns, cleared, with stone below; as x y z. */
    private static int[] workSpot(E2E e2e, String name, int dx, int dz) {
        return e2e.onPlayer(name, () -> {
            Player player = Bukkit.getPlayerExact(name);
            Block spot = player.getLocation().getBlock().getRelative(dx, 0, dz);
            if (!Bukkit.isOwnedByCurrentRegion(spot.getLocation())) {
                throw new IllegalStateException("the work spot is not owned by " + name);
            }
            spot.getRelative(0, -1, 0).setType(Material.STONE, false);
            spot.setType(Material.AIR, false);
            spot.getRelative(0, 1, 0).setType(Material.AIR, false);
            return new int[] {spot.getX(), spot.getY(), spot.getZ()};
        });
    }

    private static Material blockType(E2E e2e, String name, int[] at) {
        return e2e.onPlayer(name, () -> Bukkit.getPlayerExact(name).getWorld().getBlockAt(at[0], at[1], at[2]).getType());
    }

    /** Places the held spawner at the spot (on the stone below it) and waits until it is recorded. */
    private static void place(E2E e2e, Bot bot, int[] at, int expectedCount) {
        bot.useItemOnTop(at[0], at[1] - 1, at[2]);
        e2e.eventually(() -> blockType(e2e, bot.name, at) == Material.SPAWNER, bot.name + " placed a spawner at " + at[0] + " " + at[1] + " " + at[2]);
        e2e.eventually(() -> number(e2e, bot.name, "spawners_count") == expectedCount, bot.name + " owns " + expectedCount + " spawners");
    }

    /** Right-clicks the spawner block itself with whatever the bot holds. */
    private static void use(Bot bot, int[] at) {
        bot.useItemOnTop(at[0], at[1], at[2]);
    }

    private static void sneak(E2E e2e, Bot bot, boolean sneaking) {
        bot.sneak(sneaking);
        e2e.eventually(() -> e2e.onPlayer(bot.name, () -> e2e.player(bot.name).isSneaking()) == sneaking,
            bot.name + (sneaking ? " sneaks" : " stands up"));
    }

    /** Opens the storage by sneaking and right-clicking with an empty hand; waits for the screen. */
    private static void openStorage(E2E e2e, Bot bot, int[] at, String title) {
        select(e2e, bot.name, 8);
        hold(e2e, bot.name, 8, null);
        sneak(e2e, bot, true);
        use(bot, at);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals(title),
            bot.name + " sees the '" + title + "' screen (now " + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        sneak(e2e, bot, false);
        e2e.sleep(300);
    }

    private static String slotName(Bot bot, int slot) {
        var stack = bot.screenItems().get(slot);
        return stack == null || stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    private static String slotLore(Bot bot, int slot) {
        var stack = bot.screenItems().get(slot);
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (var line : lore.lines()) {
            text.append(line.getString()).append('\n');
        }
        return text.toString();
    }

    /** Runs a loot cycle in every loaded chunk with spawners and waits until the player's storages hold something. */
    private static void cycle(E2E e2e, String owner) {
        long before = number(e2e, owner, "spawners_stored");
        e2e.console("spawners cycle");
        e2e.eventually(() -> number(e2e, owner, "spawners_stored") > before, "the loot cycle filled " + owner + "'s storage");
    }

    /** Reads the spawner block state on the player's thread: [type, spawn count, required range]. */
    private static Object[] blockState(E2E e2e, String name, int[] at) {
        return e2e.onPlayer(name, () -> {
            Block block = Bukkit.getPlayerExact(name).getWorld().getBlockAt(at[0], at[1], at[2]);
            if (!(block.getState(true) instanceof CreatureSpawner spawner)) {
                return new Object[] {null, -1, -1};
            }
            return new Object[] {spawner.getSpawnedType(), spawner.getSpawnCount(), spawner.getRequiredPlayerRange()};
        });
    }

    private static long storedSpawners(E2E e2e, String owner) {
        String uuid = e2e.uuid(owner).toString();
        try {
            e2e.services().database().write(c -> null).get(10, TimeUnit.SECONDS);
            return e2e.services().database().read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(stack), 0) FROM spawners WHERE owner = ?")) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("could not read the spawners table: " + e);
        }
    }

    // ------------------------------------------------------------------ scenarios

    static void placeAndStack(E2E e2e) {
        String name = e2e.name("SpPlace");
        Bot bot = e2e.bot(name);
        clear(e2e, name);

        e2e.step("staff give 70 zombie spawners from the console");
        bot.clearLogs();
        e2e.console("spawners give " + name + " zombie 70");
        e2e.eventually(() -> spawnerItems(e2e, name, "zombie") == 70, "70 zombie spawner items (has " + spawnerItems(e2e, name, "zombie") + ")");
        e2e.eventually(() -> bot.chatContains("You received 70 zombie spawners."), "told in chat: " + bot.chat());
        ItemStack unit = items(e2e).create("zombie", 1).orElseThrow();
        e2e.expect(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(unit.effectiveName()).equals("Zombie spawner"), "the item is called Zombie spawner");

        e2e.step("placing one makes a managed spawner that never spawns mobs");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setHeldItemSlot(0);
            return null;
        });
        int[] at = workSpot(e2e, name, 2, 0);
        bot.clearLogs();
        place(e2e, bot, at, 1);
        e2e.eventually(() -> bot.actionBarContains("Placed your zombie spawner."), "placed: " + bot.actionBar());
        e2e.expect(number(e2e, name, "spawners_stacked") == 1, "a stack of one");
        Object[] state = blockState(e2e, name, at);
        e2e.expect(state[0] == EntityType.ZOMBIE, "the block shows a zombie: " + state[0]);
        e2e.expect(Integer.valueOf(0).equals(state[1]), "the block never spawns (spawn count " + state[1] + ")");
        e2e.expect(Integer.valueOf(32).equals(state[2]), "it spins within the activation radius (" + state[2] + ")");
        e2e.eventually(() -> storedSpawners(e2e, name) == 1, "the spawner is stored");

        e2e.step("right click adds one spawner to the stack");
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 2, "stack of two (" + placeholder(e2e, name, "spawners_stacked") + ")");
        e2e.eventually(() -> bot.actionBarContains("Added 1 to the zombie stack, now 2 of 1,000."), "stacked: " + bot.actionBar());
        e2e.expect(spawnerItems(e2e, name, "zombie") == 68, "two items used (" + spawnerItems(e2e, name, "zombie") + " left)");

        e2e.step("sneak and right click adds the whole held stack");
        int held = e2e.onPlayer(name, () -> e2e.player(name).getInventory().getItemInMainHand().getAmount());
        sneak(e2e, bot, true);
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 2 + held, "stack of " + (2 + held) + " ("
            + placeholder(e2e, name, "spawners_stacked") + ")");
        sneak(e2e, bot, false);
        e2e.expect(e2e.onPlayer(name, () -> e2e.player(name).getInventory().getItemInMainHand().isEmpty()), "the hand is empty");
        e2e.eventually(() -> storedSpawners(e2e, name) == 2 + held, "the stack is stored");

        e2e.step("another mob's spawner does not stack here");
        hold(e2e, name, 7, items(e2e).create("skeleton", 1).orElseThrow());
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> bot.actionBarContains("Only zombie spawners stack here."), "wrong mob refused: " + bot.actionBar());
        e2e.expect(number(e2e, name, "spawners_stacked") == 2 + held, "the stack did not change");
        e2e.expect(blockType(e2e, name, new int[] {at[0], at[1] + 1, at[2]}) == Material.AIR, "nothing was placed on top");

        e2e.step("spawn eggs can't change the spawner");
        hold(e2e, name, 6, ItemStack.of(Material.SKELETON_SPAWN_EGG));
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> bot.actionBarContains("Spawn eggs don't change this spawner."), "egg refused: " + bot.actionBar());
        e2e.expect(blockState(e2e, name, at)[0] == EntityType.ZOMBIE, "still a zombie spawner");
        e2e.expect(count(e2e, name, Material.SKELETON_SPAWN_EGG) == 1, "the egg was not used");

        e2e.step("the stack cap holds at 1,000");
        e2e.console("spawners give " + name + " zombie 1000");
        e2e.eventually(() -> spawnerItems(e2e, name, "zombie") >= 1000, "1,000 more items");
        sneak(e2e, bot, true);
        for (int i = 0; i < 20 && number(e2e, name, "spawners_stacked") < 1000; i++) {
            int slot = e2e.onPlayer(name, () -> {
                PlayerInventory inventory = e2e.player(name).getInventory();
                for (int s = 0; s < 36; s++) {
                    ItemStack stack = inventory.getItem(s);
                    if (stack != null && stack.isSimilar(unit)) {
                        inventory.setHeldItemSlot(s < 9 ? s : 0);
                        if (s >= 9) {
                            ItemStack first = inventory.getItem(0);
                            inventory.setItem(0, stack);
                            inventory.setItem(s, first);
                        }
                        return s;
                    }
                }
                return -1;
            });
            e2e.expect(slot >= 0, "still holding zombie spawners");
            long before = number(e2e, name, "spawners_stacked");
            use(bot, at);
            e2e.eventually(() -> number(e2e, name, "spawners_stacked") > before, "the stack grew past " + before);
        }
        e2e.expect(number(e2e, name, "spawners_stacked") == 1000, "exactly 1,000 stacked (" + placeholder(e2e, name, "spawners_stacked") + ")");
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> bot.actionBarContains("This stack is full at 1,000 spawners."), "full: " + bot.actionBar());
        sneak(e2e, bot, false);
        e2e.expect(number(e2e, name, "spawners_stacked") == 1000, "still 1,000");
        e2e.expect(spawnerItems(e2e, name, "zombie") == 70, "1,070 given, 1,000 placed or stacked: 70 left ("
            + spawnerItems(e2e, name, "zombie") + " left)");
    }

    static void storage(E2E e2e) {
        String name = e2e.name("SpStore");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 0");
        e2e.console("spawners give " + name + " zombie 10");
        e2e.eventually(() -> spawnerItems(e2e, name, "zombie") == 10, "10 zombie spawners");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, 0, 2);
        place(e2e, bot, at, 1);
        sneak(e2e, bot, true);
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 10, "a stack of 10");
        sneak(e2e, bot, false);

        e2e.step("a loot cycle with the owner nearby fills the storage with drops and XP");
        cycle(e2e, name);
        long xp = number(e2e, name, "spawners_xp");
        e2e.expect(xp == 10 * 2 * 5, "10 spawners x 2 kills x 5 XP = 100 XP (has " + xp + ")");
        long stored = number(e2e, name, "spawners_stored");
        e2e.expect(stored > 0 && stored <= 10 * 9 * 64, "loot within the storage capacity: " + stored);

        e2e.step("sneak and right click with an empty hand opens the storage");
        openStorage(e2e, bot, at, "Zombie spawner");
        e2e.expect(slotName(bot, 0).equalsIgnoreCase("Rotten Flesh"), "rotten flesh first (most stored): '" + slotName(bot, 0) + "'");
        e2e.expect(slotLore(bot, 0).contains("Stored"), "the amount in the lore: " + slotLore(bot, 0));
        e2e.expect(slotName(bot, SLOT_XP).equals("Collect XP"), "the XP button: " + slotName(bot, SLOT_XP));
        e2e.expect(slotName(bot, SLOT_SELL).equals("Sell all"), "the sell button: " + slotName(bot, SLOT_SELL));
        e2e.expect(slotName(bot, SLOT_INFO).equals("Zombie spawner"), "the info icon: " + slotName(bot, SLOT_INFO));
        e2e.expect(slotLore(bot, SLOT_INFO).contains("Stack 10 of 1,000") && slotLore(bot, SLOT_INFO).contains("Owner " + name),
            "the info lore: " + slotLore(bot, SLOT_INFO));

        e2e.step("left click takes one stack into the inventory");
        int flesh = count(e2e, name, Material.ROTTEN_FLESH);
        long beforeTake = number(e2e, name, "spawners_stored");
        bot.clearLogs();
        bot.clickSlot(0);
        e2e.eventually(() -> count(e2e, name, Material.ROTTEN_FLESH) > flesh, "rotten flesh arrived");
        int took = count(e2e, name, Material.ROTTEN_FLESH) - flesh;
        e2e.expect(took > 0 && took <= 64, "at most a stack: " + took);
        e2e.eventually(() -> number(e2e, name, "spawners_stored") == beforeTake - took, "the storage lost exactly what was taken");
        e2e.eventually(() -> bot.actionBarContains("Took " + took), "told: " + bot.actionBar());

        e2e.step("collect XP gives the stored XP");
        int expBefore = e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience());
        bot.clearLogs();
        bot.clickSlot(SLOT_XP);
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) == expBefore + 100,
            "100 XP collected (total " + e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) + ")");
        e2e.eventually(() -> number(e2e, name, "spawners_xp") == 0, "no XP left in the spawner");
        e2e.eventually(() -> bot.actionBarContains("Collected 100 XP."), "told: " + bot.actionBar());
        e2e.sleep(400);
        bot.clickSlot(SLOT_XP);
        e2e.sleep(600);
        e2e.expect(e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) == expBefore + 100, "a second click gives nothing");

        e2e.step("sell all pays the storage's worth as spawner_sell");
        // 20 zombie kills drop at most 40 rotten flesh, and about one cycle in five drops nothing else: the stack
        // taken above then emptied the storage, so another cycle refills it before selling.
        if (number(e2e, name, "spawners_stored") == 0) {
            cycle(e2e, name);
        }
        bot.clearLogs();
        bot.clickSlot(SLOT_SELL);
        e2e.eventually(() -> e2e.money(name) > 0, "paid for the loot");
        e2e.eventually(() -> number(e2e, name, "spawners_stored") == 0, "the storage is empty (zombie drops all sell)");
        e2e.eventually(() -> bot.chatContains("from the zombie spawner for $" ), "a receipt: " + bot.chat());
        e2e.eventually(() -> {
            var rows = e2e.services().ledger().history(e2e.uuid(name), 5, 0).join();
            return !rows.isEmpty() && rows.getFirst().kind().equals("spawner_sell") && rows.getFirst().delta() == e2e.money(name)
                && rows.getFirst().ref() != null && rows.getFirst().ref().startsWith("spawner:");
        }, "a spawner_sell ledger row for the whole payment");
        long paid = e2e.money(name);
        e2e.sleep(400);
        bot.clearLogs();
        bot.clickSlot(SLOT_SELL);
        e2e.eventually(() -> bot.actionBarContains("Nothing in this spawner sells."), "an empty storage sells nothing: " + bot.actionBar());
        e2e.expect(e2e.money(name) == paid, "no second payment");

        e2e.step("the stored storage matches memory");
        e2e.eventually(() -> {
            try {
                return e2e.services().database().read(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM spawner_items i JOIN spawners s ON s.id = i.spawner_id "
                        + "WHERE s.owner = ?")) {
                        ps.setString(1, e2e.uuid(name).toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() && rs.getLong(1) == 0;
                        }
                    }
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                return false;
            }
        }, "no item rows are left in the database after selling");
        bot.closeScreen();
    }

    static void pickUp(E2E e2e) {
        String name = e2e.name("SpPick");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("spawners give " + name + " skeleton 5");
        e2e.eventually(() -> spawnerItems(e2e, name, "skeleton") == 5, "5 skeleton spawners");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, 2, 2);
        place(e2e, bot, at, 1);
        sneak(e2e, bot, true);
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 5, "a stack of 5");
        sneak(e2e, bot, false);
        cycle(e2e, name);
        long xp = number(e2e, name, "spawners_xp");
        long stored = number(e2e, name, "spawners_stored");
        int deliveriesBefore = e2e.services().deliveries().count(e2e.uuid(name));
        e2e.onPlayer(name, () -> {
            e2e.player(name).getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            return null;
        });

        e2e.step("without silk touch the spawner stays");
        hold(e2e, name, 0, ItemStack.of(Material.DIAMOND_PICKAXE));
        bot.clearLogs();
        bot.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> bot.actionBarContains("Use a silk touch pickaxe to pick up spawners."), "silk touch needed: " + bot.actionBar());
        e2e.sleep(500);
        e2e.expect(blockType(e2e, name, at) == Material.SPAWNER, "the spawner is still there");
        e2e.expect(number(e2e, name, "spawners_stacked") == 5, "still 5 stacked");

        e2e.step("with silk touch the stack comes back as items, the storage goes to the claim box, the XP to the player");
        ItemStack silk = ItemStack.of(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(Enchantment.SILK_TOUCH, 1);
        hold(e2e, name, 0, silk);
        int expBefore = e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience());
        bot.clearLogs();
        bot.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> blockType(e2e, name, at) == Material.AIR, "the spawner was picked up");
        e2e.eventually(() -> spawnerItems(e2e, name, "skeleton") == 5, "5 skeleton spawner items back (" + spawnerItems(e2e, name, "skeleton") + ")");
        e2e.eventually(() -> bot.actionBarContains("Picked up 5 skeleton spawners."), "told: " + bot.actionBar());
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) == expBefore + xp, "the " + xp + " stored XP was given");
        e2e.eventually(() -> e2e.services().deliveries().count(e2e.uuid(name)) > deliveriesBefore, "the stored loot is in the claim box");
        e2e.eventually(() -> bot.chatContains("Its " + String.format(java.util.Locale.ROOT, "%,d", stored) + " stored items are waiting in your claim box (/claims)."), "claim box note: " + bot.chat());
        e2e.expect(number(e2e, name, "spawners_count") == 0, "no spawners left");
        e2e.eventually(() -> storedSpawners(e2e, name) == 0, "the spawner row is deleted");
        e2e.expect(count(e2e, name, Material.SPAWNER) == 5, "no vanilla spawner item dropped or given");
        List<String> listed = e2e.consoleOutput("spawners list " + name);
        e2e.expect(listed.stream().anyMatch(line -> line.contains(name + " has no spawners.")), "the console list is empty: " + listed);
    }

    /** Items of a player waiting in the claim box from spawners (works while they are offline). */
    private static int claimBoxItems(E2E e2e, UUID uuid, java.util.function.Predicate<ItemStack> which) {
        return e2e.services().deliveries().of(uuid).stream()
            .filter(delivery -> "spawner".equals(delivery.source()) && which.test(delivery.item()))
            .mapToInt(delivery -> delivery.item().getAmount()).sum();
    }

    /**
     * Taking items and picking a spawner up while storage is slow, then leaving before the change is stored: the items
     * wait in the claim box and the stored XP in the player's XP box, paid out when they join again. The XP used to be
     * destroyed with the spawner.
     */
    static void leftBeforeCommit(E2E e2e) throws Exception {
        String name = e2e.name("SpLeft");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);
        e2e.console("spawners give " + name + " skeleton 5");
        e2e.eventually(() -> spawnerItems(e2e, name, "skeleton") == 5, "5 skeleton spawners");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, -2, 2);
        place(e2e, bot, at, 1);
        sneak(e2e, bot, true);
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 5, "a stack of 5");
        sneak(e2e, bot, false);
        cycle(e2e, name);
        long xp = number(e2e, name, "spawners_xp");
        e2e.expect(xp > 0, "the spawner holds XP: " + xp);

        e2e.step("take a stack while storage is slow, and leave before it is stored");
        openStorage(e2e, bot, at, "Skeleton spawner");
        long stored = number(e2e, name, "spawners_stored");
        int waitingBefore = claimBoxItems(e2e, uuid, item -> item.getType() != Material.SPAWNER);
        java.util.concurrent.CompletableFuture<Object> stall = e2e.stallStorage(3_000);
        bot.clickSlot(0);
        e2e.eventually(() -> number(e2e, name, "spawners_stored") < stored, "taken out of the storage at once");
        long took = stored - number(e2e, name, "spawners_stored");
        e2e.kick(bot, "e2e: leaving before the take is stored");
        e2e.expect(!stall.isDone(), "the take was still waiting for storage");
        stall.get(10, TimeUnit.SECONDS);
        e2e.eventually(() -> claimBoxItems(e2e, uuid, item -> item.getType() != Material.SPAWNER) - waitingBefore == took,
            "the " + took + " taken items wait in the claim box");

        e2e.step("pick the spawner up while storage is slow, and leave before it is stored");
        bot = e2e.bot(name);
        clear(e2e, name);
        e2e.onPlayer(name, () -> {
            e2e.player(name).getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            return null;
        });
        ItemStack silk = ItemStack.of(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(Enchantment.SILK_TOUCH, 1);
        hold(e2e, name, 0, silk);
        int expBefore = e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience());
        ItemStack skeleton = items(e2e).create("skeleton", 1).orElseThrow();
        int spawnersBefore = claimBoxItems(e2e, uuid, item -> item.isSimilar(skeleton));
        // The bot may stand far from the spawner after rejoining: walk it back next to the block.
        Location next = e2e.onPlayer(name, () -> new Location(e2e.player(name).getWorld(), at[0] + 0.5, at[1], at[2] - 1.5));
        e2e.player(name).teleportAsync(next);
        Bot picker = bot;
        e2e.eventually(() -> Math.abs(picker.x() - next.getX()) < 0.6 && Math.abs(picker.z() - next.getZ()) < 0.6, "back at the spawner");
        e2e.sleep(300);
        stall = e2e.stallStorage(3_000);
        bot.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> blockType(e2e, name, at) == Material.AIR, "the spawner was picked up");
        e2e.kick(bot, "e2e: leaving before the pickup is stored");
        e2e.expect(!stall.isDone(), "the pickup was still waiting for storage");
        stall.get(10, TimeUnit.SECONDS);
        e2e.eventually(() -> claimBoxItems(e2e, uuid, item -> item.isSimilar(skeleton)) - spawnersBefore == 5,
            "the 5 spawner items wait in the claim box");

        e2e.step("joining again pays out the stored XP");
        e2e.sleep(500);
        Bot back = e2e.bot(name);
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) == expBefore + xp,
            "the " + xp + " stored XP was paid on joining (total " + e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) + ")");
        e2e.eventually(() -> back.chatContains("You got " + String.format(java.util.Locale.ROOT, "%,d", xp)
            + " XP that your spawners kept for you while you were away."), "told: " + back.chat());
        e2e.eventually(() -> storedSpawners(e2e, name) == 0, "the spawner row is deleted");
        e2e.step("it is paid once");
        e2e.kick(back, "e2e: once more");
        e2e.sleep(500);
        e2e.bot(name);
        e2e.sleep(1_000);
        e2e.expect(e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) == expBefore + xp, "no second payout");
    }

    static void access(E2E e2e) {
        String ownerName = e2e.name("SpOwner");
        String otherName = e2e.name("SpOther");
        Bot owner = e2e.bot(ownerName);
        Bot other = e2e.bot(otherName);
        clear(e2e, ownerName);
        clear(e2e, otherName);
        e2e.console("spawners give " + ownerName + " pig 1");
        e2e.eventually(() -> spawnerItems(e2e, ownerName, "pig") == 1, "a pig spawner");
        select(e2e, ownerName, 0);
        int[] at = workSpot(e2e, ownerName, 2, -2);
        place(e2e, owner, at, 1);

        e2e.step("a stranger can't stack, open or pick up the spawner");
        bring(e2e, other, ownerName);
        e2e.console("spawners give " + otherName + " pig 2");
        e2e.eventually(() -> spawnerItems(e2e, otherName, "pig") == 2, "the stranger has pig spawners");
        select(e2e, otherName, 0);
        other.clearLogs();
        use(other, at);
        e2e.eventually(() -> other.actionBarContains("This spawner belongs to " + ownerName + "."), "not theirs: " + other.actionBar());
        e2e.expect(number(e2e, ownerName, "spawners_stacked") == 1, "the stack did not change");
        openStorageRefused(e2e, other, at, ownerName);
        ItemStack silk = ItemStack.of(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(Enchantment.SILK_TOUCH, 1);
        hold(e2e, otherName, 0, silk);
        e2e.onPlayer(otherName, () -> {
            e2e.player(otherName).getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            return null;
        });
        other.clearLogs();
        other.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> other.actionBarContains("This spawner belongs to " + ownerName + "."), "can't pick it up: " + other.actionBar());
        e2e.sleep(400);
        e2e.expect(blockType(e2e, ownerName, at) == Material.SPAWNER, "the spawner stays");

        e2e.step("after joining the owner's team the same player can use it");
        String team = e2e.name("SpTeam");
        e2e.console("eco set " + ownerName + " 50k");
        e2e.eventually(() -> e2e.money(ownerName) == 50_000, "owner funded");
        owner.clearLogs();
        owner.command("team create " + team);
        e2e.dialog(owner, "Start a team");
        e2e.click(owner, "Start team");
        e2e.eventually(() -> team.equals(placeholder(e2e, ownerName, "team_name")), "the owner has a team");
        owner.command("team invite " + otherName);
        e2e.eventually(() -> owner.actionBarContains("Invited " + otherName), "invited: " + owner.actionBar());
        other.command("team join " + team);
        e2e.eventually(() -> team.equals(placeholder(e2e, otherName, "team_name")), "the other player joined");
        hold(e2e, otherName, 0, items(e2e).create("pig", 2).orElseThrow());
        other.clearLogs();
        use(other, at);
        // Spawners added to someone else's stack become theirs: the first click asks (Confirm stacking onto others'
        // spawners is on by default), a separate second click gives.
        e2e.eventually(() -> other.actionBarContains("Click again to add 1 to " + ownerName + "'s pig stack."),
            "asked to confirm: " + other.actionBar());
        e2e.sleep(600);
        e2e.expect(number(e2e, ownerName, "spawners_stacked") == 1, "nothing stacked on the first click");
        use(other, at);
        e2e.eventually(() -> number(e2e, ownerName, "spawners_stacked") == 2, "a team member stacked it");
        e2e.eventually(() -> other.actionBarContains("Added 1 to " + ownerName + "'s pig stack, now 2 of 1,000."),
            "told whose stack it is: " + other.actionBar());
        openStorage(e2e, other, at, "Pig spawner");
        e2e.expect(slotLore(other, SLOT_INFO).contains("Owner " + ownerName), "the owner is shown: " + slotLore(other, SLOT_INFO));
        other.closeScreen();
        e2e.expect(number(e2e, otherName, "spawners_count") == 0, "the spawner still belongs to the owner");

        e2e.step("/spawners lists the teammate's spawner under Team spawners");
        other.clearLogs();
        other.command("spawners");
        Bot.SeenDialog own = e2e.dialog(other, "Your spawners");
        e2e.expect(own.bodyText().contains("You don't have any spawners yet."), "no spawners of their own: " + own.body());
        e2e.expect(own.button("Team spawners") != null, "a Team spawners button: " + own.buttons());
        e2e.click(other, "Team spawners");
        Bot.SeenDialog shared = e2e.dialog(other, "Team spawners");
        e2e.expect(shared.bodyText().contains("Teammates' spawners 1, stacked 2"), "the team summary: " + shared.body());
        e2e.expect(shared.bodyText().contains("Pig x2 of " + ownerName), "the owner's pig spawner: " + shared.body());
        e2e.click(other, "Pig x2");
        Bot.SeenDialog details = e2e.dialog(other, "Pig spawner");
        e2e.expect(details.bodyText().contains("Owner " + ownerName), "its details: " + details.body());
        other.clearLogs();

        e2e.step("the owner sees the switch too, and their own spawner first");
        owner.clearLogs();
        owner.command("spawners");
        Bot.SeenDialog ownerList = e2e.dialog(owner, "Your spawners");
        e2e.expect(ownerList.bodyText().contains("Pig x2") && ownerList.button("Team spawners") != null, "the owner's list: "
            + ownerList.body() + " " + ownerList.buttons());
        e2e.click(owner, "Team spawners");
        Bot.SeenDialog none = e2e.dialog(owner, "Team spawners");
        e2e.expect(none.bodyText().contains("Your teammates don't have any spawners yet."), "the teammate has none: " + none.body());
        owner.clearLogs();
    }

    /** Teleports a bot onto another player (players spawn up to ten blocks apart) and waits until it arrived. */
    private static void bring(E2E e2e, Bot bot, String to) {
        Location target = e2e.onPlayer(to, () -> e2e.player(to).getLocation());
        e2e.player(bot.name).teleportAsync(target);
        e2e.eventually(() -> Math.abs(bot.x() - target.getX()) < 0.5 && Math.abs(bot.z() - target.getZ()) < 0.5
            && e2e.onPlayer(bot.name, () -> e2e.player(bot.name).getLocation().distanceSquared(target) < 0.5),
            bot.name + " stands next to " + to);
        e2e.sleep(300);
    }

    private static void openStorageRefused(E2E e2e, Bot bot, int[] at, String ownerName) {
        select(e2e, bot.name, 8);
        hold(e2e, bot.name, 8, null);
        sneak(e2e, bot, true);
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> bot.actionBarContains("This spawner belongs to " + ownerName + "."), "can't open it: " + bot.actionBar());
        e2e.sleep(300);
        e2e.expect(bot.screen() == null, "no storage screen opened");
        sneak(e2e, bot, false);
        select(e2e, bot.name, 0);
    }

    static void dialog(E2E e2e) {
        String name = e2e.name("SpDialog");
        Bot bot = e2e.bot(name);
        clear(e2e, name);

        e2e.step("with no spawners the dialog says how to get one");
        bot.clearLogs();
        bot.command("spawners");
        Bot.SeenDialog empty = e2e.dialog(bot, "Your spawners");
        e2e.expect(empty.bodyText().contains("You don't have any spawners yet."), "the empty text: " + empty.body());

        e2e.step("two spawners are listed with details");
        int[] first = workSpot(e2e, name, -2, 2);
        int[] second = workSpot(e2e, name, 3, 2);
        clear(e2e, name);
        e2e.console("spawners give " + name + " blaze 1");
        e2e.eventually(() -> spawnerItems(e2e, name, "blaze") == 1, "a blaze spawner");
        select(e2e, name, 0);
        place(e2e, bot, first, 1);
        e2e.console("spawners give " + name + " cow 3");
        e2e.eventually(() -> spawnerItems(e2e, name, "cow") == 3, "three cow spawners");
        select(e2e, name, 0);
        place(e2e, bot, second, 2);
        sneak(e2e, bot, true);
        use(bot, second);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 4, "cow stack of 3 (" + placeholder(e2e, name, "spawners_stacked") + ")");
        sneak(e2e, bot, false);

        bot.clearLogs();
        bot.command("spawners");
        Bot.SeenDialog list = e2e.dialog(bot, "Your spawners");
        e2e.expect(list.bodyText().contains("Spawners placed 2, stacked 4"), "the summary: " + list.body());
        e2e.expect(list.button("Blaze x1") != null && list.button("Cow x3") != null, "a button per spawner: " + list.buttons());
        e2e.click(bot, "Cow x3");
        Bot.SeenDialog details = e2e.dialog(bot, "Cow spawner");
        String body = details.bodyText();
        e2e.expect(body.contains("Owner " + name), "the owner: " + body);
        e2e.expect(body.contains("At " + second[0] + ", " + second[1] + ", " + second[2]), "the location: " + body);
        e2e.expect(body.contains("of 5,184 items"), "the capacity of 3 cows (3 x 27 slots x 64): " + body);
        e2e.expect(body.contains("Waiting for a player within 32 blocks") || body.contains("Making loot"), "a status line: " + body);

        e2e.step("open storage from the dialog, and back again");
        e2e.expect(bot.clickButton("Open storage", Map.of()), "the details have an Open storage button: " + details.buttons());
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Cow spawner"), "the cow storage opened from the dialog");
        e2e.sleep(300);
        bot.clearLogs();
        bot.clickSlot(SLOT_BACK);
        e2e.dialog(bot, "Cow spawner");
        e2e.click(bot, "Back");
        e2e.dialog(bot, "Your spawners");

        e2e.step("the pause-menu route opens the list");
        bot.clearLogs();
        bot.rawClick("siftcore:hub/spawners", null);
        e2e.dialog(bot, "Your spawners");

        e2e.step("the console list shows both");
        List<String> lines = e2e.consoleOutput("spawners list " + name);
        e2e.expect(lines.stream().anyMatch(line -> line.contains(name + " has 2 spawners, 4 stacked.")), "header: " + lines);
        e2e.expect(lines.stream().anyMatch(line -> line.contains("Cow x3")) && lines.stream().anyMatch(line -> line.contains("Blaze x1")),
            "a line per spawner: " + lines);
    }

    static void shop(E2E e2e) {
        String name = e2e.name("SpShop");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 100k");

        e2e.step("the shop sells zombie spawners made by the spawners feature");
        bot.command("shop");
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Shop"), "the shop opened");
        e2e.sleep(300);
        e2e.expect(slotName(bot, 23).contains("Spawners"), "the spawner category is offered: '" + slotName(bot, 23) + "'");
        bot.clickSlot(23);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Spawners"), "the spawners page");
        e2e.sleep(300);
        bot.clickSlot(0);
        Bot.SeenDialog buy = e2e.dialog(bot, "Buy zombie spawner");
        e2e.expect(buy.button("Buy 1 for $60,000") != null, "one for $60,000: " + buy.buttons());
        e2e.click(bot, "Buy 1", Map.of("amount", 1, "exact", ""));
        e2e.dialog(bot, "Confirm purchase");
        e2e.click(bot, "Buy");
        e2e.eventually(() -> e2e.money(name) == 40_000, "charged $60,000 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> spawnerItems(e2e, name, "zombie") == 1, "a SiftCore zombie spawner item arrived");
        bot.closeScreen();

        e2e.step("the bought spawner places as a managed spawner");
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            int slot = inventory.first(Material.SPAWNER);
            ItemStack stack = inventory.getItem(slot);
            inventory.setItem(slot, null);
            inventory.setItem(0, stack);
            inventory.setHeldItemSlot(0);
            return null;
        });
        int[] at = workSpot(e2e, name, 1, 3);
        place(e2e, bot, at, 1);
        e2e.expect(blockState(e2e, name, at)[0] == EntityType.ZOMBIE, "a zombie spawner");
    }

    /**
     * Leaves a spawner with loot that only the write-behind has (no transaction wrote it), for the restart check:
     * the boot test compares {@code spawners list SpKeep_<run>} before a stop and after the next start.
     */
    static void persist(E2E e2e) {
        String name = e2e.name("SpKeep");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("spawners give " + name + " iron_golem 3");
        e2e.eventually(() -> spawnerItems(e2e, name, "iron_golem") == 3, "three iron golem spawners");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, 0, 3);
        place(e2e, bot, at, 1);
        sneak(e2e, bot, true);
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 3, "a stack of 3");
        sneak(e2e, bot, false);
        e2e.step("loot made by a cycle waits in memory for the write-behind");
        cycle(e2e, name);
        long stored = number(e2e, name, "spawners_stored");
        e2e.expect(stored >= 3 * 3 * 3, "3 golems x 3 kills x at least 3 iron: " + stored);
        e2e.log("persist check: " + name + " keeps " + stored + " stored items in a stack of 3 iron golems");
    }

    /** Moves a bot to the ground of another column and waits until its client is there. */
    private static void moveTo(E2E e2e, Bot bot, int x, int z) {
        Location here = e2e.onPlayer(bot.name, () -> e2e.player(bot.name).getLocation());
        Location target = e2e.ground(here.getWorld(), x, z, here.getYaw());
        e2e.player(bot.name).teleportAsync(target);
        e2e.eventually(() -> Math.abs(bot.x() - target.getX()) < 0.5 && Math.abs(bot.z() - target.getZ()) < 0.5
            && e2e.onPlayer(bot.name, () -> e2e.player(bot.name).getLocation().distanceSquared(target) < 0.5),
            bot.name + " stands at " + x + " " + z);
        e2e.sleep(300);
    }

    /** Runs a loot cycle and checks that the player's spawners made nothing. */
    private static void cycleMakesNothing(E2E e2e, String owner, String why) {
        e2e.console("spawners cycle");
        e2e.sleep(1_500);
        e2e.expect(number(e2e, owner, "spawners_stored") == 0 && number(e2e, owner, "spawners_xp") == 0, why + " (stored "
            + placeholder(e2e, owner, "spawners_stored") + ", XP " + placeholder(e2e, owner, "spawners_xp") + ")");
    }

    /** Spawners make loot only with a counted player near: not for a vanished one, not from afar. */
    static void activation(E2E e2e) {
        String name = e2e.name("SpNear");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("spawners give " + name + " cow 1");
        e2e.eventually(() -> spawnerItems(e2e, name, "cow") == 1, "a cow spawner");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, -2, 3);
        place(e2e, bot, at, 1);

        e2e.step("a vanished player keeps no spawner going");
        e2e.console("op " + name);
        bot.command("vanish");
        UUID uuid = e2e.uuid(name);
        e2e.eventually(() -> e2e.feature(StaffFeature.class).vanish().vanished(uuid), name + " is vanished");
        cycleMakesNothing(e2e, name, "no loot while the only player near is vanished");
        bot.command("vanish");
        e2e.eventually(() -> !e2e.feature(StaffFeature.class).vanish().vanished(uuid), name + " is visible again");
        e2e.console("deop " + name);

        e2e.step("40 blocks away the chunk stays loaded but the spawner waits");
        Location home = e2e.onPlayer(name, () -> e2e.player(name).getLocation());
        moveTo(e2e, bot, home.getBlockX() + 40, home.getBlockZ());
        cycleMakesNothing(e2e, name, "no loot with nobody within 32 blocks");
        bot.clearLogs();
        bot.command("spawners");
        e2e.dialog(bot, "Your spawners");
        e2e.click(bot, "Cow x1");
        Bot.SeenDialog idle = e2e.dialog(bot, "Cow spawner");
        e2e.expect(idle.bodyText().contains("Waiting for a player within 32 blocks."), "the idle status: " + idle.body());

        e2e.step("back within range, the next cycle makes loot and XP");
        moveTo(e2e, bot, home.getBlockX(), home.getBlockZ());
        cycle(e2e, name);
        e2e.expect(number(e2e, name, "spawners_xp") == 2 * 2, "one cow spawner x 2 kills x 2 XP (" + placeholder(e2e, name, "spawners_xp") + ")");
        bot.clearLogs();
        bot.command("spawners");
        e2e.dialog(bot, "Your spawners");
        e2e.click(bot, "Cow x1");
        Bot.SeenDialog active = e2e.dialog(bot, "Cow spawner");
        e2e.expect(active.bodyText().contains("Making loot right now."), "the active status: " + active.body());
        bot.clearLogs();
    }

    /** Storages stay closed in combat, from the block and from /spawners; stacking still works. */
    static void combat(E2E e2e) {
        String name = e2e.name("SpFight");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("spawners give " + name + " pig 2");
        e2e.eventually(() -> spawnerItems(e2e, name, "pig") == 2, "two pig spawners");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, 3, -2);
        place(e2e, bot, at, 1);
        cycle(e2e, name);

        e2e.step("in combat the storage does not open");
        e2e.console("combat tag " + name + " 30s");
        e2e.eventually(() -> "true".equals(placeholder(e2e, name, "combat_tagged")), name + " is in combat");
        select(e2e, name, 8);
        hold(e2e, name, 8, null);
        sneak(e2e, bot, true);
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> bot.actionBarContains("You can't open spawners in combat."), "refused in combat: " + bot.actionBar());
        e2e.sleep(400);
        e2e.expect(bot.screen() == null, "no storage screen");
        sneak(e2e, bot, false);

        e2e.step("nor from /spawners");
        bot.clearLogs();
        bot.command("spawners");
        e2e.dialog(bot, "Your spawners");
        e2e.click(bot, "Pig x1");
        e2e.dialog(bot, "Pig spawner");
        e2e.expect(bot.clickButton("Open storage", Map.of()), "an Open storage button");
        e2e.eventually(() -> bot.actionBarContains("You can't open spawners in combat."), "refused from the dialog: " + bot.actionBar());
        e2e.sleep(400);
        e2e.expect(bot.screen() == null, "no storage screen from the dialog");

        e2e.step("stacking still works in combat");
        select(e2e, name, 0);
        bot.clearLogs();
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 2, "stacked in combat");

        e2e.step("once the combat ends it opens");
        e2e.console("combat untag " + name);
        e2e.eventually(() -> !"true".equals(placeholder(e2e, name, "combat_tagged")), name + " is out of combat");
        openStorage(e2e, bot, at, "Pig spawner");
        bot.closeScreen();
    }

    /** Staff with the bypass pick up anyone's spawner without silk touch; a vanished one stays unnamed to the owner. */
    static void staff(E2E e2e) {
        String ownerName = e2e.name("SpLoser");
        String modName = e2e.name("SpMod");
        Bot owner = e2e.bot(ownerName);
        Bot mod = e2e.bot(modName);
        clear(e2e, ownerName);
        clear(e2e, modName);
        e2e.console("spawners give " + ownerName + " sheep 2");
        e2e.eventually(() -> spawnerItems(e2e, ownerName, "sheep") == 2, "two sheep spawners");
        select(e2e, ownerName, 0);
        int[] at = workSpot(e2e, ownerName, -1, 3);
        place(e2e, owner, at, 1);
        use(owner, at);
        e2e.eventually(() -> number(e2e, ownerName, "spawners_stacked") == 2, "a stack of 2");
        cycle(e2e, ownerName);
        long stored = number(e2e, ownerName, "spawners_stored");
        int claimsBefore = e2e.services().deliveries().count(e2e.uuid(ownerName));

        e2e.step("a vanished moderator picks it up without silk touch");
        bring(e2e, mod, ownerName);
        e2e.console("op " + modName);
        mod.command("vanish");
        UUID modId = e2e.uuid(modName);
        e2e.eventually(() -> e2e.feature(StaffFeature.class).vanish().vanished(modId), modName + " is vanished");
        hold(e2e, modName, 0, ItemStack.of(Material.DIAMOND_PICKAXE));
        e2e.onPlayer(modName, () -> {
            e2e.player(modName).getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            return null;
        });
        owner.clearLogs();
        mod.clearLogs();
        mod.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> blockType(e2e, ownerName, at) == Material.AIR, "the spawner was picked up");
        e2e.eventually(() -> spawnerItems(e2e, modName, "sheep") == 2, "the moderator got the 2 spawners");
        e2e.eventually(() -> e2e.services().deliveries().count(e2e.uuid(ownerName)) > claimsBefore, "the loot went to the owner's claim box");
        e2e.eventually(() -> mod.chatContains("Its " + String.format(java.util.Locale.ROOT, "%,d", stored) + " stored items went to "
            + ownerName + "'s claim box."), "the moderator is told where the loot went: " + mod.chat());

        e2e.step("the owner learns that staff did it, not who");
        e2e.eventually(() -> owner.chatContains("Staff picked up your sheep spawner stack of 2."), "the owner's notice: " + owner.chat());
        e2e.expect(!owner.chatContains(modName), "the vanished moderator is not named: " + owner.chat());
        e2e.expect(number(e2e, ownerName, "spawners_count") == 0, "the owner has no spawner left");
        mod.command("vanish");
        e2e.eventually(() -> !e2e.feature(StaffFeature.class).vanish().vanished(modId), modName + " is visible again");
        e2e.console("deop " + modName);
    }

    /** A spawner whose block an admin tool removed is refunded to its owner's claim box by the next loot cycle. */
    static void refund(E2E e2e) {
        String name = e2e.name("SpLost");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        UUID uuid = e2e.uuid(name);
        e2e.console("spawners give " + name + " spider 4");
        e2e.eventually(() -> spawnerItems(e2e, name, "spider") == 4, "four spider spawners");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, 3, 3);
        place(e2e, bot, at, 1);
        sneak(e2e, bot, true);
        use(bot, at);
        e2e.eventually(() -> number(e2e, name, "spawners_stacked") == 4, "a stack of 4");
        sneak(e2e, bot, false);
        cycle(e2e, name);
        long stored = number(e2e, name, "spawners_stored");
        long xp = number(e2e, name, "spawners_xp");
        int claimsBefore = e2e.services().deliveries().count(uuid);
        // Out of reach of the XP orbs the destroyed block drops, with its chunk still loaded.
        moveTo(e2e, bot, at[0] + 40, at[2]);
        int expBefore = e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience());

        e2e.step("an admin tool removes the block (nothing listens to block destroy events)");
        e2e.console("setblock " + at[0] + " " + at[1] + " " + at[2] + " minecraft:air destroy");
        e2e.eventually(() -> blockType(e2e, name, at) == Material.AIR, "the spawner block is gone");

        e2e.step("the next cycle refunds the stack and the stored loot to the owner's claim box, the XP to the owner");
        bot.clearLogs();
        e2e.console("spawners cycle");
        e2e.eventually(() -> number(e2e, name, "spawners_count") == 0, "the record is gone");
        e2e.eventually(() -> storedSpawners(e2e, name) == 0, "the spawner row is deleted");
        e2e.eventually(() -> e2e.services().deliveries().count(uuid) > claimsBefore, "the refund is in the claim box");
        ItemStack unit = items(e2e).create("spider", 1).orElseThrow();
        long spawners = 0;
        long loot = 0;
        for (var delivery : e2e.services().deliveries().of(uuid)) {
            if (delivery.item().isSimilar(unit)) {
                spawners += delivery.item().getAmount();
            } else if (delivery.ref() != null && delivery.ref().startsWith("spawner:")) {
                loot += delivery.item().getAmount();
            }
        }
        e2e.expect(spawners == 4, "4 SiftCore spider spawners refunded (" + spawners + ")");
        e2e.expect(loot == stored, "the " + stored + " stored items refunded (" + loot + ")");
        e2e.expect(count(e2e, name, Material.SPAWNER) == 0, "nothing was handed out into the inventory");
        e2e.eventually(() -> bot.chatContains("Your spider spawner stack of 4 at " + at[0] + ", " + at[1] + ", " + at[2]
            + " in world is gone.") && bot.chatContains("Its spawners and " + String.format(java.util.Locale.ROOT, "%,d", stored)
            + " stored items are waiting in your claim box (/claims)."), "the owner is told: " + bot.chat());
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getTotalExperience()) == expBefore + xp,
            "the " + xp + " stored XP went to the owner");
        e2e.eventually(() -> bot.chatContains("You got its " + String.format(java.util.Locale.ROOT, "%,d", xp) + " stored XP."),
            "told about the XP: " + bot.chat());
    }

    /** Crate previews show spawner rewards as SiftCore spawner items made by this feature. */
    static void crates(E2E e2e) {
        String name = e2e.name("SpCrate");
        Bot bot = e2e.bot(name);

        e2e.step("the legendary crate preview lists blaze and skeleton spawners");
        bot.command("crates preview legendary");
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Legendary crate rewards"),
            "the legendary preview (now " + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(300);
        List<String> names = new ArrayList<>();
        for (int slot = 0; slot < 45; slot++) {
            names.add(slotName(bot, slot));
        }
        e2e.expect(names.contains("Blaze spawner"), "a Blaze spawner reward: " + names);
        e2e.expect(names.contains("Skeleton spawner"), "a Skeleton spawner reward: " + names);
        bot.closeScreen();
    }

    static void protection(E2E e2e) {
        String name = e2e.name("SpBoom");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("spawners give " + name + " creeper 1");
        e2e.eventually(() -> spawnerItems(e2e, name, "creeper") == 1, "a creeper spawner");
        select(e2e, name, 0);
        int[] at = workSpot(e2e, name, 3, 0);
        place(e2e, bot, at, 1);
        int[] beside = {at[0], at[1], at[2] + 1};

        e2e.step("TNT next to the spawner does not destroy it");
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.setGameMode(org.bukkit.GameMode.CREATIVE);
            Block side = player.getWorld().getBlockAt(beside[0], beside[1], beside[2]);
            side.setType(Material.AIR, false);
            TNTPrimed tnt = player.getWorld().spawn(new Location(player.getWorld(), beside[0] + 0.5, beside[1], beside[2] + 0.5), TNTPrimed.class);
            tnt.setFuseTicks(1);
            return null;
        });
        e2e.sleep(1_500);
        e2e.onPlayer(name, () -> {
            e2e.player(name).setGameMode(org.bukkit.GameMode.SURVIVAL);
            return null;
        });
        e2e.expect(blockType(e2e, name, at) == Material.SPAWNER, "the spawner survived the explosion");
        e2e.expect(number(e2e, name, "spawners_count") == 1, "still recorded");
        e2e.expect(blockState(e2e, name, at)[1].equals(0), "still never spawns");
    }

    // ------------------------------------------------------------------ player settings

    /** The damage of the boots the player wears (-1 when none). */
    private static int bootsDamage(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> {
            ItemStack worn = e2e.player(name).getInventory().getBoots();
            return worn != null && worn.getItemMeta() instanceof Damageable damage ? damage.getDamage() : -1;
        });
    }

    /** Opens the storage with a plain right-click (the owner's Open storage setting) and clicks Collect XP. */
    private static void collectXp(E2E e2e, Bot bot, int[] at) {
        select(e2e, bot.name, 8);
        hold(e2e, bot.name, 8, null);
        e2e.sleep(400);
        use(bot, at);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().endsWith("spawner"), "the storage opened: "
            + (bot.screen() == null ? "none" : bot.screen().title()));
        e2e.sleep(300);
        bot.clickSlot(SLOT_XP);
        e2e.eventually(() -> number(e2e, bot.name, "spawners_xp") == 0, "the XP was collected");
    }

    /** Runs {@code body} with spawners.yml changed by exact replacements, then restores it (both reloaded). */
    private static void withSpawnersFile(E2E e2e, Map<String, String> replacements, Runnable body) throws Exception {
        java.nio.file.Path path = e2e.services().plugin().getDataFolder().toPath().resolve("features/spawners.yml");
        String original = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), "spawners.yml contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        java.nio.file.Files.writeString(path, changed, java.nio.charset.StandardCharsets.UTF_8);
        try {
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed spawners.yml reloads: " + reload);
            body.run();
        } finally {
            java.nio.file.Files.writeString(path, original, java.nio.charset.StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /**
     * The spawner settings change how spawners behave for each player: a plain right-click adding the whole stack and
     * every teammate action told (choices saved in the settings dialog), storage opening with a plain right-click, the
     * sale receipt above the hotbar, the second click before giving spawners away and turning it off, collected XP
     * going to levels or repairing mending gear first, the owner's pickup selling the storage and the full storage alert,
     * including one held back by the 5 minutes (choices and a switch set through the API, as other plugins and the
     * coming /settings command set them).
     */
    static void settings(E2E e2e) throws Exception {
        String ownerName = e2e.name("SpSet");
        String mateName = e2e.name("SpMate");
        Bot owner = e2e.bot(ownerName);
        Bot mate = e2e.bot(mateName);
        UUID ownerId = e2e.uuid(ownerName);
        UUID mateId = e2e.uuid(mateName);
        clear(e2e, ownerName);
        clear(e2e, mateName);
        e2e.console("eco set " + ownerName + " 50k");
        e2e.eventually(() -> e2e.money(ownerName) == 50_000, "the owner is funded");

        e2e.step("the spawner settings are in Spawners, with their options");
        Map<String, List<String>> inputs = ItemSettingsSteps.inputs(e2e, owner, "spawners", "Spawners settings");
        e2e.expect(List.of("server", "sneak-right-click", "right-click").equals(inputs.get("spawner_open_click")), "open: " + inputs);
        e2e.expect(List.of("chat", "actionbar", "off").equals(inputs.get("spawner_full_alert")), "full alert: " + inputs);
        e2e.expect(List.of("one", "whole-hand").equals(inputs.get("spawner_stack_click")), "stack click: " + inputs);
        e2e.expect(List.of("server", "repair-first", "levels-only").equals(inputs.get("spawner_xp_mending")), "XP: " + inputs);
        e2e.expect(List.of("server", "claim-box", "sell").equals(inputs.get("spawner_pickup_storage")), "pickup: " + inputs);
        e2e.expect(List.of("pickups", "all", "off").equals(inputs.get("spawner_team_notices")), "team notices: " + inputs);
        e2e.expect(List.of("toggle").equals(inputs.get("spawner_confirm_give")), "confirm give: " + inputs);

        e2e.step("a plain right-click adds the whole stack in hand and teammates' use is told (saved in the settings dialog)");
        ItemSettingsSteps.edit(e2e, owner, "spawners", "Spawners settings",
            Map.of("spawner_stack_click", "whole-hand", "spawner_team_notices", "all"));
        ItemSettingsSteps.expectStored(e2e, ownerId, "spawner-stack-click", "whole-hand");
        ItemSettingsSteps.expectStored(e2e, ownerId, "spawner-team-notices", "all");
        e2e.console("spawners give " + ownerName + " zombie 6");
        e2e.eventually(() -> spawnerItems(e2e, ownerName, "zombie") == 6, "6 zombie spawners");
        select(e2e, ownerName, 0);
        int[] at = workSpot(e2e, ownerName, 2, 0);
        place(e2e, owner, at, 1);
        owner.clearLogs();
        use(owner, at);
        e2e.eventually(() -> number(e2e, ownerName, "spawners_stacked") == 6, "the whole hand was added without sneaking");
        e2e.eventually(() -> owner.actionBarContains("Added 5 to the zombie stack, now 6 of 1,000."), "stacked: " + owner.actionBar());

        e2e.step("a plain right-click with an empty hand opens the storage (set through the API)");
        ItemSettingsSteps.set(e2e, ownerId, SpawnerPlayerSettings.OPEN_CLICK, SpawnerPlayerSettings.OpenClick.RIGHT_CLICK);
        select(e2e, ownerName, 8);
        hold(e2e, ownerName, 8, null);
        e2e.expect(!e2e.onPlayer(ownerName, () -> e2e.player(ownerName).isSneaking()), "not sneaking");
        use(owner, at);
        e2e.eventually(() -> owner.screen() != null && owner.screen().title().equals("Zombie spawner"), "the storage opened: "
            + (owner.screen() == null ? "none" : owner.screen().title()));
        owner.closeScreen();

        e2e.step("selling the storage shows only the total above the hotbar (sale receipts set through the API)");
        ItemSettingsSteps.set(e2e, ownerId, SharedSettings.SELL_RECEIPTS, AlertStyle.ACTIONBAR);
        cycle(e2e, ownerName);
        long money = e2e.money(ownerName);
        e2e.sleep(400);
        use(owner, at);
        e2e.eventually(() -> owner.screen() != null && owner.screen().title().equals("Zombie spawner"), "the storage opened again");
        e2e.sleep(300);
        owner.clearLogs();
        owner.clickSlot(SLOT_SELL);
        e2e.eventually(() -> e2e.money(ownerName) > money, "paid for the loot");
        e2e.eventually(() -> owner.actionBar().stream().anyMatch(line -> line.startsWith("Sold ") && line.contains(" items for $")),
            "the total above the hotbar: " + owner.actionBar());
        e2e.sleep(400);
        e2e.expect(!owner.chatContains("from the zombie spawner for"), "no chat receipt: " + owner.chat());
        owner.closeScreen();

        e2e.step("a teammate's first click with spawners only asks; a separate second click gives and the owner is told");
        String team = e2e.name("SpSetTeam");
        owner.command("team create " + team);
        e2e.dialog(owner, "Start a team");
        e2e.click(owner, "Start team");
        e2e.eventually(() -> team.equals(placeholder(e2e, ownerName, "team_name")), "the owner has a team");
        owner.command("team invite " + mateName);
        e2e.eventually(() -> owner.actionBarContains("Invited " + mateName), "invited: " + owner.actionBar());
        mate.command("team join " + team);
        e2e.eventually(() -> team.equals(placeholder(e2e, mateName, "team_name")), "the teammate joined");
        bring(e2e, mate, ownerName);
        hold(e2e, mateName, 0, items(e2e).create("zombie", 3).orElseThrow());
        mate.clearLogs();
        owner.clearLogs();
        use(mate, at);
        e2e.eventually(() -> mate.actionBarContains("Click again to add 1 to " + ownerName + "'s zombie stack."), "asked: " + mate.actionBar());
        e2e.sleep(700);
        e2e.expect(number(e2e, ownerName, "spawners_stacked") == 6, "nothing added on the first click");
        use(mate, at);
        e2e.eventually(() -> number(e2e, ownerName, "spawners_stacked") == 7, "the second click added one");
        e2e.eventually(() -> owner.chatContains(mateName + " added 1 to your zombie stack, now 7."), "the owner is told: " + owner.chat());

        e2e.step("with the confirmation off a teammate's click gives at once (set through the API)");
        ItemSettingsSteps.set(e2e, mateId, SpawnerPlayerSettings.CONFIRM_GIVE, false);
        e2e.sleep(600);
        use(mate, at);
        e2e.eventually(() -> number(e2e, ownerName, "spawners_stacked") == 8, "one more at once");

        e2e.step("collected XP only adds levels with XP and mending on levels only (set through the API)");
        ItemStack boots = ItemStack.of(Material.DIAMOND_BOOTS);
        boots.addEnchantment(Enchantment.MENDING, 1);
        boots.editMeta(Damageable.class, meta -> meta.setDamage(300));
        e2e.onPlayer(ownerName, () -> {
            e2e.player(ownerName).getInventory().setBoots(boots);
            return null;
        });
        e2e.expect(bootsDamage(e2e, ownerName) == 300, "damaged mending boots worn");
        ItemSettingsSteps.set(e2e, ownerId, SpawnerPlayerSettings.XP_MENDING, SpawnerPlayerSettings.XpMending.LEVELS_ONLY);
        cycle(e2e, ownerName);
        long levelsXp = number(e2e, ownerName, "spawners_xp");
        e2e.expect(levelsXp > 0, "the spawner holds XP: " + levelsXp);
        int levelsBefore = e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getTotalExperience());
        collectXp(e2e, owner, at);
        e2e.eventually(() -> e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getTotalExperience()) == levelsBefore + levelsXp,
            "all " + levelsXp + " XP went to levels (total " + e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getTotalExperience()) + ")");
        e2e.expect(bootsDamage(e2e, ownerName) == 300, "the boots were not repaired: " + bootsDamage(e2e, ownerName));
        owner.closeScreen();

        e2e.step("on repair first the collected XP repairs the mending boots before anything goes to levels (set through the API)");
        ItemSettingsSteps.set(e2e, ownerId, SpawnerPlayerSettings.XP_MENDING, SpawnerPlayerSettings.XpMending.REPAIR_FIRST);
        cycle(e2e, ownerName);
        long repairXp = number(e2e, ownerName, "spawners_xp");
        e2e.expect(repairXp > 0, "the spawner holds XP again: " + repairXp);
        int repairBefore = e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getTotalExperience());
        collectXp(e2e, owner, at);
        e2e.eventually(() -> bootsDamage(e2e, ownerName) < 300, "the boots were repaired: " + bootsDamage(e2e, ownerName));
        e2e.sleep(300);
        int repairGain = e2e.onPlayer(ownerName, () -> e2e.player(ownerName).getTotalExperience()) - repairBefore;
        e2e.expect(repairGain < repairXp, "part of the " + repairXp + " XP went into the repair (levels got " + repairGain + ")");
        owner.closeScreen();
        e2e.onPlayer(ownerName, () -> {
            e2e.player(ownerName).getInventory().setBoots(null);
            return null;
        });

        e2e.step("the owner picking up their own spawner sells the storage (set through the API)");
        ItemSettingsSteps.set(e2e, ownerId, SpawnerPlayerSettings.PICKUP_STORAGE, SpawnerPlayerSettings.PickupStorage.SELL);
        cycle(e2e, ownerName);
        long beforePickup = e2e.money(ownerName);
        ItemStack silk = ItemStack.of(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(Enchantment.SILK_TOUCH, 1);
        hold(e2e, ownerName, 0, silk);
        e2e.onPlayer(ownerName, () -> {
            e2e.player(ownerName).getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            return null;
        });
        owner.clearLogs();
        owner.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> blockType(e2e, ownerName, at) == Material.AIR, "picked up");
        e2e.eventually(() -> owner.chat().stream().anyMatch(line -> line.startsWith("Its ") && line.contains("stored items sold for $")),
            "the storage was sold: " + owner.chat());
        e2e.eventually(() -> e2e.money(ownerName) > beforePickup, "paid for the storage");
        e2e.eventually(() -> spawnerItems(e2e, ownerName, "zombie") == 8, "the 8 spawners came back");

        e2e.step("a storage that fills up is told above the hotbar; a player with the alert off is not told");
        ItemSettingsSteps.set(e2e, mateId, SpawnerPlayerSettings.FULL_ALERT, AlertStyle.OFF);
        withSpawnersFile(e2e, Map.of("  zombie:\n    name: \"Zombie\"\n    kills-per-cycle: 2.0\n",
            "  zombie:\n    name: \"Zombie\"\n    slots: 1\n    kills-per-cycle: 100\n"), () -> {
            hold(e2e, ownerName, 0, items(e2e).create("zombie", 1).orElseThrow());
            int[] full = workSpot(e2e, ownerName, -2, 0);
            place(e2e, owner, full, 1);
            hold(e2e, mateName, 0, items(e2e).create("zombie", 1).orElseThrow());
            int[] mates = workSpot(e2e, mateName, 0, -2);
            place(e2e, mate, mates, 1);
            owner.clearLogs();
            mate.clearLogs();
            e2e.console("spawners cycle");
            e2e.eventually(() -> owner.actionBarContains("Your zombie spawner is full."), 15_000, "the owner's alert: " + owner.actionBar());
            e2e.sleep(1_500);
            e2e.expect(!mate.anyFeedbackContains("is full"), "no alert with it off: " + mate.chat() + " " + mate.actionBar());

            e2e.step("a storage that fills within the 5 minutes is told once they have passed, not lost");
            hold(e2e, ownerName, 0, items(e2e).create("zombie", 1).orElseThrow());
            int[] later = workSpot(e2e, ownerName, -2, 2);
            place(e2e, owner, later, 2);
            owner.clearLogs();
            e2e.console("spawners cycle");
            e2e.sleep(2_500);
            e2e.expect(!owner.anyFeedbackContains("is full"), "held back by the 5 minutes: " + owner.actionBar());
            e2e.services().cooldowns().clear(ownerId, "spawners:full");
            e2e.console("spawners cycle");
            e2e.eventually(() -> owner.actionBarContains("Your zombie spawner is full."), 15_000,
                "the held-back alert once the 5 minutes passed: " + owner.actionBar());
            owner.clearLogs();
            e2e.services().cooldowns().clear(ownerId, "spawners:full");
            e2e.console("spawners cycle");
            e2e.sleep(2_500);
            e2e.expect(!owner.anyFeedbackContains("is full"), "both still full but already told: " + owner.actionBar());
        });
    }
}
