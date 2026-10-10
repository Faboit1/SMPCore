package net.siftvanilla.e2e;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.ContainerInput;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import net.siftvanilla.siftcore.feature.kits.KitPlayerSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Regression scenarios for the dupe audit's SiftCore findings (R6, R8, R11, R12, R15, R16), built from the auditors'
 * and verifiers' probes. Each one fails on the code before its fix.
 * <p>
 * Three are run by name only, never by {@code e2e run all} ({@link #byNameOnly}): {@code audit-ledger-revert-chain}
 * makes one database write fail on purpose, which the database health self-test then reports until the next start;
 * {@code audit-trash-stop} and {@code audit-trash-stop-check} run across a real stop: run the first, stop and start
 * the server, then run the second.
 */
final class AuditScenarios {

    /** Raw slot of the player's inventory slot 9 below a six-row chest. */
    private static final int BELOW_SIX_ROWS = 54;
    private static final String SELL_TITLE = "Sell items";
    private static final String AH_TITLE = "Auction house";
    private static final String ORDERS_TITLE = "Orders";
    static final String TRASH_STOP_BUTTON = "AudTrashBtn";
    static final String TRASH_STOP_CLASSIC = "AudTrashCls";

    private AuditScenarios() {
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
        list.add(of("audit-sell-click-spam", AuditScenarios::clickSpam));
        list.add(of("audit-sell-save-race", AuditScenarios::saveRace));
        list.add(of("audit-trash-crash", AuditScenarios::trashCrash));
        list.add(of("audit-pay-limit-rejoin", AuditScenarios::payLimitRejoin));
        list.add(of("audit-permission-revoked", AuditScenarios::permissionRevoked));
        return list;
    }

    /** Scenarios run by name only, not by {@code e2e run all} (see the class comment). */
    static List<Scenario> byNameOnly() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("audit-ledger-revert-chain", AuditScenarios::revertChain));
        list.add(of("audit-trash-stop", AuditScenarios::trashBeforeStop));
        list.add(of("audit-trash-stop-check", AuditScenarios::trashAfterStop));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static void command(E2E e2e, Bot bot, String command) {
        e2e.sleep(700);
        bot.command(command);
    }

    private static void fund(E2E e2e, String name, long amount) {
        e2e.console("eco set " + name + " " + amount);
        e2e.eventually(() -> e2e.money(name) == amount, name + " has $" + amount);
    }

    private static void inventory(E2E e2e, String name, Map<Integer, ItemStack> slots) {
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.clear();
            inventory.setHeldItemSlot(0);
            slots.forEach((slot, stack) -> inventory.setItem(slot, stack.clone()));
            return null;
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
            ItemStack cursor = e2e.player(name).getItemOnCursor();
            if (cursor != null && cursor.getType() == material) {
                total += cursor.getAmount();
            }
            return total;
        });
    }

    private static int gridCount(E2E e2e, String name, Material material, int slots) {
        return e2e.onPlayer(name, () -> {
            Inventory top = e2e.player(name).getOpenInventory().getTopInventory();
            if (top.getSize() < slots) {
                return 0;
            }
            int total = 0;
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = top.getItem(slot);
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            return total;
        });
    }

    private static void grant(E2E e2e, String name, String node, boolean value) {
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.addAttachment(e2e.services().plugin(), node, value);
            player.updateCommands();
            return null;
        });
        e2e.sleep(300);
    }

    @FunctionalInterface
    private interface Rows<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private static <T> T query(E2E e2e, String sql, Rows<T> reader, Object... params) {
        var database = e2e.services().database();
        database.flush();
        try {
            return database.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < params.length; i++) {
                        ps.setObject(i + 1, params[i]);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        return reader.read(rs);
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("query failed: " + sql + ": " + e);
        }
    }

    private static long number(E2E e2e, String sql, Object... params) {
        return query(e2e, sql, rs -> rs.next() ? rs.getLong(1) : 0L, params);
    }

    private static String text(E2E e2e, String sql, Object... params) {
        return query(e2e, sql, rs -> rs.next() ? rs.getString(1) : "none", params);
    }

    private static void sql(E2E e2e, String statement) throws Exception {
        e2e.services().database().write(c -> {
            try (var st = c.createStatement()) {
                st.execute(statement);
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
    }

    private static void ledgerHealthy(E2E e2e) throws Exception {
        Ledger.AuditReport report = e2e.services().ledger().audit().get(20, TimeUnit.SECONDS);
        e2e.expect(report.healthy(), "the ledger invariants hold: " + report.problems());
    }

    private static List<String> lore(net.minecraft.world.item.ItemStack stack) {
        ItemLore lore = CraftItemStack.asBukkitCopy(stack).getData(DataComponentTypes.LORE);
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(PlainTextComponentSerializer.plainText().serialize(line));
            }
        }
        return lines;
    }

    private static List<Integer> slotsWith(Bot bot, String text) {
        List<Integer> slots = new ArrayList<>();
        for (var entry : new TreeMap<>(bot.screenItems()).entrySet()) {
            if (entry.getKey() < 45 && lore(entry.getValue()).stream().anyMatch(line -> line.contains(text))) {
                slots.add(entry.getKey());
            }
        }
        return slots;
    }

    private static int slotWith(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> !slotsWith(bot, text).isEmpty(), bot.name + " sees an entry with '" + text + "'");
        return slotsWith(bot, text).getFirst();
    }

    private static void openMenu(E2E e2e, Bot bot, String command, String title) {
        Bot.Screen before = bot.screen();
        command(e2e, bot, command);
        e2e.eventually(() -> bot.screen() != null && bot.screen() != before && bot.screen().title().contains(title)
            && !bot.screenItems().isEmpty(), bot.name + " sees the screen '" + title + "' (now "
            + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(300);
    }

    private static void awaitType(E2E e2e, Bot bot, String type) {
        e2e.eventually(() -> bot.screen() != null && bot.screen().type().equals(type),
            bot.name + " sees a " + type + " screen (now " + (bot.screen() == null ? "none" : bot.screen().type()) + ")");
        e2e.sleep(300);
    }

    /** Lists what the seller holds on the auction house; returns the listing id once it is stored as active. */
    private static long list(E2E e2e, Bot seller, String price) {
        UUID id = e2e.uuid(seller.name);
        long before = number(e2e, "SELECT COALESCE(MAX(id), 0) FROM auction_listings WHERE seller = ?", id.toString());
        command(e2e, seller, "ah sell " + price);
        e2e.dialog(seller, "List item");
        e2e.click(seller, "List it");
        e2e.eventually(() -> number(e2e, "SELECT COALESCE(MAX(id), 0) FROM auction_listings WHERE seller = ? AND state = 'ACTIVE'",
            id.toString()) > before, seller.name + " has a new active listing");
        return number(e2e, "SELECT MAX(id) FROM auction_listings WHERE seller = ?", id.toString());
    }

    /** Opens the auction house and the buy confirmation of the seller's listing. */
    private static void openPurchase(E2E e2e, Bot buyer, String search, String sellerName) {
        openMenu(e2e, buyer, "ah search " + search, AH_TITLE);
        int slot = slotWith(e2e, buyer, "Seller " + sellerName);
        buyer.clickSlot(slot);
        e2e.dialog(buyer, "Buy item");
    }

    // ------------------------------------------------------------------ R6: a storage failure takes back what relied on it

    /**
     * A sale fails to store (an injected database error) after the seller already passed its proceeds on to a third
     * account. Before the fix the sale was undone alone: the listing reopened, the buyer got the money back, the third
     * account kept the proceeds and the seller went negative. Now the transfer that spent them is taken back too.
     */
    static void revertChain(E2E e2e) throws Exception {
        String sellerName = e2e.name("AudRvS");
        String buyerName = e2e.name("AudRvB");
        String thirdName = e2e.name("AudRvC");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        e2e.bot(thirdName);
        fund(e2e, sellerName, 0);
        fund(e2e, buyerName, 10_000);
        fund(e2e, thirdName, 0);
        UUID sellerId = e2e.uuid(sellerName);
        UUID thirdId = e2e.uuid(thirdName);
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.COBWEB, 1)));
        long listing = list(e2e, seller, "777");
        try {
            e2e.step("one storage failure: the sale of listing " + listing + " (stands in for a disk or lock error)");
            sql(e2e, "DROP TRIGGER IF EXISTS e2e_audit_fail");
            sql(e2e, "CREATE TRIGGER e2e_audit_fail BEFORE UPDATE ON auction_listings WHEN NEW.state = 'SOLD' AND OLD.id = " + listing
                + " BEGIN SELECT RAISE(ABORT, 'e2e injected failure'); END");
            openPurchase(e2e, buyer, "cobweb", sellerName);
            CompletableFuture<Object> stall = e2e.stallStorage(3_000);
            e2e.sleep(200);
            buyer.clickButton("Buy", Map.of());
            e2e.eventually(() -> e2e.money(sellerName) == 777, "the sale applied in memory (seller " + e2e.money(sellerName) + ")");

            e2e.step("the seller passes the proceeds on before the sale is stored");
            TransactionResult spent = e2e.services().ledger().execute(LedgerTx.builder().actor(sellerId)
                .transfer(sellerId, thirdId, Currency.MONEY, 777, "pay", null).build());
            e2e.expect(spent.success(), "the transfer applies in memory: " + spent.status());
            e2e.expect(e2e.money(thirdName) == 777, "the third account holds the proceeds for now");
            stall.get(20, TimeUnit.SECONDS);
            boolean transferFailed;
            try {
                spent.committed().get(20, TimeUnit.SECONDS);
                transferFailed = false;
            } catch (Exception e) {
                transferFailed = true;
            }
            e2e.services().database().flush();
            e2e.sleep(1_000);

            e2e.step("the sale and the transfer that relied on it are both taken back");
            String state = text(e2e, "SELECT state FROM auction_listings WHERE id = ?", listing);
            long storedSeller = number(e2e, "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE uuid = ? AND currency = 'money'",
                sellerId.toString());
            e2e.log("listing " + state + ", seller " + e2e.money(sellerName) + " (stored " + storedSeller + "), buyer " + e2e.money(buyerName)
                + ", third " + e2e.money(thirdName) + ", buyer cobweb " + count(e2e, buyerName, Material.COBWEB));
            e2e.expect(transferFailed, "the transfer of the unstored proceeds failed to store");
            e2e.expect("ACTIVE".equals(state), "the listing is active again: " + state);
            e2e.expect(e2e.money(sellerName) == 0 && storedSeller == 0, "the seller is back at 0, in memory and storage");
            e2e.expect(e2e.money(buyerName) == 10_000, "the buyer kept their money");
            e2e.expect(e2e.money(thirdName) == 0, "the third account no longer holds money that was never stored");
            e2e.expect(count(e2e, buyerName, Material.COBWEB) == 0, "the buyer got no item");
            ledgerHealthy(e2e);
        } finally {
            sql(e2e, "DROP TRIGGER IF EXISTS e2e_audit_fail");
            e2e.console("eco resume");
        }

        e2e.step("with storage healthy again the same listing sells once");
        e2e.sleep(1_000);
        openPurchase(e2e, buyer, "cobweb", sellerName);
        buyer.clickButton("Buy", Map.of());
        e2e.eventually(() -> e2e.money(sellerName) == 777 && e2e.money(buyerName) == 10_000 - 777, "the purchase went through");
        e2e.eventually(() -> "SOLD".equals(text(e2e, "SELECT state FROM auction_listings WHERE id = ?", listing)), "sold");
        e2e.eventually(() -> count(e2e, buyerName, Material.COBWEB) == 1, "the buyer got the cobweb");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ R8: shift-click spam from the inventory

    private static ItemStack box(Material material) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            list.add(ItemStack.of(material, 64));
        }
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(list));
        return box;
    }

    /**
     * Bursts of shift-clicks from the player's own inventory into a sell grid full of shulker boxes. Each click queued a
     * full redraw (a preview of every box) before the fix: 1,000 clicks held up the region for about 2 s in one tick.
     * Now they share one redraw a tick and a click that moves nothing redraws nothing.
     */
    static void clickSpam(E2E e2e) {
        String name = e2e.name("AudSpam");
        Bot bot = e2e.bot(name);
        inventory(e2e, name, Map.of(9, ItemStack.of(Material.EMERALD, 64)));
        openMenu(e2e, bot, "sell", SELL_TITLE);
        e2e.onPlayer(name, () -> {
            Inventory top = e2e.player(name).getOpenInventory().getTopInventory();
            for (int slot = 0; slot < 45; slot++) {
                top.setItem(slot, box(slot % 2 == 0 ? Material.DIAMOND : Material.IRON_INGOT));
            }
            return null;
        });
        ConcurrentLinkedQueue<Double> ticks = new ConcurrentLinkedQueue<>();
        Listener listener = new Listener() {
            @EventHandler
            public void onTick(ServerTickEndEvent event) {
                ticks.add(event.getTickDuration());
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, Bukkit.getPluginManager().getPlugin("SiftE2E"));
        try {
            e2e.sleep(1_500);
            for (int burst : new int[] {200, 1_000}) {
                e2e.step(burst + " shift-clicks from the inventory into the full grid");
                ticks.clear();
                for (int i = 0; i < burst; i++) {
                    bot.clickSlot(BELOW_SIX_ROWS, 0, ContainerInput.QUICK_MOVE);
                }
                e2e.sleep(5_000);
                double max = 0;
                for (double tick : ticks) {
                    max = Math.max(max, tick);
                }
                e2e.log(burst + " clicks: " + ticks.size() + " ticks, longest " + Math.round(max) + " ms");
                e2e.expect(max < 500, "no tick held up by the clicks (longest " + Math.round(max) + " ms; about 2,000 ms before the fix)");
            }
        } finally {
            HandlerList.unregisterAll(listener);
        }
        e2e.expect(bot.screen() != null && bot.screen().title().contains(SELL_TITLE), "the sell menu is still open");
        e2e.expect(count(e2e, name, Material.EMERALD) == 64, "nothing moved: the grid was full");
        e2e.expect(gridCount(e2e, name, Material.SHULKER_BOX, 45) == 45, "the grid still holds its 45 boxes");

        e2e.step("the menu still takes items once there is room");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getOpenInventory().getTopInventory().setItem(44, null);
            return null;
        });
        e2e.sleep(300);
        bot.clickSlot(BELOW_SIX_ROWS, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> gridCount(e2e, name, Material.EMERALD, 45) == 64 && count(e2e, name, Material.EMERALD) == 0,
            "the emeralds moved into the free slot");
        e2e.eventually(() -> e2e.gridCopyCount(name, "sell_grid", Material.EMERALD) == 64, "and into the grid's copy");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getOpenInventory().getTopInventory().clear();
            return null;
        });
        bot.closeScreen();
    }

    // ------------------------------------------------------------------ R11: a save in the tick after a click into the grid

    /** {inventory count, grid copy count} of a material in a saved player file; the copy under {@code siftcore:<key>}. */
    static int[] saved(byte[] bytes, Material material, String key) {
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            String id = material.getKey().toString();
            int inventory = 0;
            ListTag list = root.getList("Inventory").orElse(new ListTag());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompoundOrEmpty(i);
                if (id.equals(item.getStringOr("id", ""))) {
                    inventory += item.getIntOr("count", 1);
                }
            }
            int copy = 0;
            ListTag grid = root.getCompoundOrEmpty("BukkitValues").getList("siftcore:" + key).orElse(null);
            if (grid != null) {
                for (int i = 0; i < grid.size(); i++) {
                    Tag tag = grid.get(i);
                    if (tag instanceof ByteArrayTag bytesTag) {
                        ItemStack stack = ItemStack.deserializeBytes(bytesTag.getAsByteArray());
                        if (stack.getType() == material) {
                            copy += stack.getAmount();
                        }
                    }
                }
            }
            return new int[] {inventory, copy};
        } catch (Exception e) {
            throw new E2E.Failure("can't read the saved player file: " + e);
        }
    }

    /**
     * A command that saves the player (/sell hand) sent together with a click that moves items into the sell grid. The
     * grid's own copy is written a tick after the click, so the save used to find the moved items in neither the
     * inventory nor the copy (12 of 12 before the fix): a crash then lost them. Every save now writes the copy first.
     */
    static void saveRace(E2E e2e) {
        String name = e2e.name("AudRace");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        openMenu(e2e, bot, "sell", SELL_TITLE);
        String[] modes = {"command then shift-click", "shift-click then command", "command then number key"};
        byte[] lastFile = null;
        for (int trial = 0; trial < 6; trial++) {
            int mode = trial % 3;
            e2e.step("trial " + trial + ": " + modes[mode]);
            e2e.sleep(800);
            e2e.onPlayer(name, () -> {
                e2e.player(name).getOpenInventory().getTopInventory().clear();
                PlayerInventory inventory = e2e.player(name).getInventory();
                inventory.clear();
                inventory.setHeldItemSlot(0);
                inventory.setItem(0, ItemStack.of(Material.DIAMOND, 1));
                inventory.setItem(mode == 2 ? 1 : 9, ItemStack.of(Material.EMERALD, 30));
                return null;
            });
            e2e.sleep(300);
            e2e.savePlayer(name);
            e2e.sleep(400);
            switch (mode) {
                case 0 -> {
                    bot.command("sell hand");
                    bot.clickSlot(BELOW_SIX_ROWS, 0, ContainerInput.QUICK_MOVE);
                }
                case 1 -> {
                    bot.clickSlot(BELOW_SIX_ROWS, 0, ContainerInput.QUICK_MOVE);
                    bot.command("sell hand");
                }
                default -> {
                    bot.command("sell hand");
                    bot.numberKey(0, 1);
                }
            }
            e2e.eventually(() -> gridCount(e2e, name, Material.EMERALD, 45) == 30 && count(e2e, name, Material.DIAMOND) == 0,
                "the emeralds are in the grid and the diamond was sold");
            e2e.sleep(400);
            byte[] file = e2e.savedPlayerFile(uuid);
            int[] counts = saved(file, Material.EMERALD, "sell_grid");
            e2e.log(modes[mode] + ": saved inventory " + counts[0] + ", saved copy " + counts[1]);
            e2e.expect(counts[0] + counts[1] == 30, "the saved file holds the 30 emeralds once (inventory " + counts[0] + ", copy "
                + counts[1] + ")");
            lastFile = file;
        }

        e2e.step("a crash right after that save gives the emeralds back");
        e2e.crashTo(bot, uuid, lastFile);
        e2e.bot(name);
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "the 30 emeralds are back after the crash");
        e2e.eventually(() -> e2e.gridCopy(name, "sell_grid").isEmpty(), "and the copy is gone");
    }

    // ------------------------------------------------------------------ R12: trash bins at a crash and a stop

    private static void trashBin(E2E e2e, Bot bot, KitPlayerSettings.TrashMode mode) {
        ItemSettingsSteps.set(e2e, e2e.uuid(bot.name), KitPlayerSettings.TRASH_MODE, mode);
        e2e.sleep(500);
        command(e2e, bot, "trash");
        awaitType(e2e, bot, mode == KitPlayerSettings.TrashMode.DELETE_BUTTON ? "minecraft:generic_9x5" : "minecraft:generic_9x4");
    }

    /**
     * Items in an open trash bin were only ever in the bin: a crash lost them, protected or not, in either mode. The bin
     * now keeps a copy in the player's data like the sell grid; a deletion is saved at once, so a crash can't bring
     * deleted items back either.
     */
    static void trashCrash(E2E e2e) {
        String name = e2e.name("AudTrash");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        grant(e2e, name, "siftcore.perk.trash", true);

        e2e.step("a crash while 64 dirt sit in a Delete button bin gives them back");
        inventory(e2e, name, Map.of(0, ItemStack.of(Material.DIRT, 64)));
        trashBin(e2e, bot, KitPlayerSettings.TrashMode.DELETE_BUTTON);
        bot.shiftClick(72);
        e2e.eventually(() -> count(e2e, name, Material.DIRT) == 0, "the dirt is in the bin");
        e2e.eventually(() -> e2e.gridCopyCount(name, "trash_grid", Material.DIRT) == 64, "and in the bin's copy");
        e2e.savePlayer(name);
        byte[] crash = e2e.savedPlayerFile(uuid);
        int[] counts = saved(crash, Material.DIRT, "trash_grid");
        e2e.expect(counts[0] == 0 && counts[1] == 64, "the saved file holds the dirt in the copy (" + counts[0] + "/" + counts[1] + ")");
        e2e.crashTo(bot, uuid, crash);
        Bot back = e2e.bot(name);
        e2e.eventually(() -> count(e2e, name, Material.DIRT) == 64, "the 64 dirt are back");
        e2e.eventually(() -> back.chatContains("trash bin when the server stopped"), "told so: " + back.chat());
        e2e.expect(e2e.gridCopy(name, "trash_grid").isEmpty(), "the copy is gone");

        e2e.step("a delete-on-close bin is given back whole after a crash, its protected sword too");
        grant(e2e, name, "siftcore.perk.trash", true);
        ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        sword.addEnchantment(Enchantment.SHARPNESS, 5);
        inventory(e2e, name, Map.of(0, sword, 1, ItemStack.of(Material.DIRT, 32)));
        trashBin(e2e, back, KitPlayerSettings.TrashMode.DELETE_ON_CLOSE);
        back.shiftClick(63);
        back.shiftClick(64);
        e2e.eventually(() -> e2e.gridCopyCount(name, "trash_grid", Material.DIRT) == 32
            && e2e.gridCopyCount(name, "trash_grid", Material.DIAMOND_SWORD) == 1, "both are in the bin's copy");
        e2e.savePlayer(name);
        byte[] crash2 = e2e.savedPlayerFile(uuid);
        e2e.crashTo(back, uuid, crash2);
        Bot again = e2e.bot(name);
        e2e.eventually(() -> count(e2e, name, Material.DIRT) == 32 && count(e2e, name, Material.DIAMOND_SWORD) == 1,
            "nothing was deleted, so both came back");

        e2e.step("deleted items stay deleted after a crash");
        grant(e2e, name, "siftcore.perk.trash", true);
        inventory(e2e, name, Map.of(0, ItemStack.of(Material.DIRT, 64)));
        trashBin(e2e, again, KitPlayerSettings.TrashMode.DELETE_BUTTON);
        again.shiftClick(72);
        e2e.eventually(() -> e2e.gridCopyCount(name, "trash_grid", Material.DIRT) == 64, "the dirt is in the bin's copy");
        e2e.savePlayer(name);
        again.clearLogs();
        e2e.sleep(500);
        again.clickSlot(40);
        e2e.eventually(() -> again.actionBarContains("Deleted 64 items."), "deleted: " + again.actionBar());
        e2e.expect(e2e.gridCopy(name, "trash_grid").isEmpty(), "the copy went with the deleted items");
        byte[] afterDelete = e2e.savedPlayerFile(uuid);
        int[] left = saved(afterDelete, Material.DIRT, "trash_grid");
        e2e.expect(left[0] + left[1] == 0, "the player was saved without them (" + left[0] + "/" + left[1] + ")");
        again.closeScreen();
        e2e.crashTo(again, uuid, afterDelete);
        e2e.bot(name);
        e2e.sleep(1_500);
        e2e.expect(count(e2e, name, Material.DIRT) == 0, "the deleted dirt did not come back");
    }

    /**
     * Before a stop: a Delete button bin with 64 dirt, and a delete-on-close bin with a protected sword and 32 dirt, both
     * open, their players kept online. Stop the server now (the plugin is disabled before players are removed, with no
     * close event), start it again, and run {@code audit-trash-stop-check}.
     */
    static void trashBeforeStop(E2E e2e) {
        Bot button = e2e.bot(TRASH_STOP_BUTTON);
        Bot classic = e2e.bot(TRASH_STOP_CLASSIC);
        for (String name : List.of(TRASH_STOP_BUTTON, TRASH_STOP_CLASSIC)) {
            grant(e2e, name, "siftcore.perk.trash", true);
        }
        inventory(e2e, TRASH_STOP_BUTTON, Map.of(0, ItemStack.of(Material.DIRT, 64)));
        trashBin(e2e, button, KitPlayerSettings.TrashMode.DELETE_BUTTON);
        button.shiftClick(72);
        ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        sword.addEnchantment(Enchantment.SHARPNESS, 5);
        inventory(e2e, TRASH_STOP_CLASSIC, Map.of(0, sword, 1, ItemStack.of(Material.DIRT, 32)));
        ItemSettingsSteps.set(e2e, e2e.uuid(TRASH_STOP_CLASSIC), KitPlayerSettings.TRASH_PROTECT, KitPlayerSettings.TrashProtect.GEAR);
        trashBin(e2e, classic, KitPlayerSettings.TrashMode.DELETE_ON_CLOSE);
        classic.shiftClick(63);
        classic.shiftClick(64);
        e2e.eventually(() -> count(e2e, TRASH_STOP_BUTTON, Material.DIRT) == 0 && count(e2e, TRASH_STOP_CLASSIC, Material.DIRT) == 0
            && count(e2e, TRASH_STOP_CLASSIC, Material.DIAMOND_SWORD) == 0, "everything is in the bins");
        e2e.log("Both bins are open and full: stop the server now, start it again, then run audit-trash-stop-check");
        e2e.keep(button);
        e2e.keep(classic);
    }

    /** After the restart: the Delete button bin gave everything back, the other bin kept only the protected sword. */
    static void trashAfterStop(E2E e2e) {
        e2e.bot(TRASH_STOP_BUTTON);
        e2e.bot(TRASH_STOP_CLASSIC);
        e2e.sleep(1_500);
        e2e.expect(count(e2e, TRASH_STOP_BUTTON, Material.DIRT) == 64, "the Delete button bin's 64 dirt are back: "
            + count(e2e, TRASH_STOP_BUTTON, Material.DIRT));
        e2e.expect(count(e2e, TRASH_STOP_CLASSIC, Material.DIAMOND_SWORD) == 1, "the protected sword is back");
        e2e.expect(count(e2e, TRASH_STOP_CLASSIC, Material.DIRT) == 0, "the unprotected dirt was deleted as the bin closed");
        e2e.expect(e2e.gridCopy(TRASH_STOP_BUTTON, "trash_grid").isEmpty() && e2e.gridCopy(TRASH_STOP_CLASSIC, "trash_grid").isEmpty(),
            "no copy is left");
    }

    // ------------------------------------------------------------------ R15: the /pay daily total across a quit

    /**
     * The day's /pay total was dropped on quit and read back from stored rows on the next payment. With storage behind,
     * a quit and a rejoin reset it: 480k went out against a 250k limit. The total now stays for the day.
     */
    static void payLimitRejoin(E2E e2e) throws Exception {
        String payerName = e2e.name("AudPayP");
        String payeeName = e2e.name("AudPayR");
        Bot payer = e2e.bot(payerName);
        e2e.bot(payeeName);
        fund(e2e, payerName, 600_000);
        fund(e2e, payeeName, 0);
        e2e.sleep(700);
        e2e.step("storage falls behind; the payer sends 240k (the limit is 250k) and leaves at once");
        // Long enough to outlast the rejoin, whose login reads wait on storage too (about 25 s in all).
        CompletableFuture<Object> stall = e2e.stallStorage(55_000);
        try {
            e2e.sleep(300);
            payer.command("pay " + payeeName + " 240k");
            e2e.dialog(payer, "Confirm payment");
            e2e.click(payer, "Pay");
            e2e.eventually(() -> e2e.money(payerName) == 360_000, "the payment applied (" + e2e.money(payerName) + ")");
            e2e.kick(payer, "e2e: quit right after paying");
            Bot back = e2e.bot(payerName);
            e2e.expect(!stall.isDone(), "the payment is still waiting for storage");

            e2e.step("rejoined before the payment is stored, another 240k is refused");
            e2e.sleep(2_200);
            back.clearLogs();
            back.command("pay " + payeeName + " 240k");
            e2e.eventually(() -> back.anyFeedbackContains("more today"), "the daily limit refuses it: " + back.actionBar() + " " + back.chat());
            if (back.dialog() != null && back.dialog().title().contains("Confirm payment")) {
                back.clickButton("Pay", Map.of());
            }
            e2e.sleep(1_500);
            e2e.expect(e2e.money(payerName) == 360_000, "only the first payment went out: " + e2e.money(payerName));
        } finally {
            stall.get(90, TimeUnit.SECONDS);
        }
        e2e.services().database().flush();
        e2e.expect(e2e.money(payeeName) == 240_000, "the payee got 240k once");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ R16: permission gone while a confirmation is open

    /**
     * A confirmation stays clickable for a while: the auction purchase, the order delivery and the bounty placement now
     * check the permission again when it is clicked, like selling and placing orders already did.
     */
    static void permissionRevoked(E2E e2e) throws Exception {
        String sellerName = e2e.name("AudPrmS");
        String buyerName = e2e.name("AudPrmB");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        fund(e2e, sellerName, 0);
        fund(e2e, buyerName, 300_000);

        e2e.step("auction: siftcore.command.ah is revoked while the buy confirmation is open");
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.COBWEB, 1)));
        long listing = list(e2e, seller, "321");
        openPurchase(e2e, buyer, "cobweb", sellerName);
        PermissionAttachment[] ah = new PermissionAttachment[1];
        e2e.onPlayer(buyerName, () -> {
            ah[0] = e2e.player(buyerName).addAttachment(e2e.services().plugin(), "siftcore.command.ah", false);
            return null;
        });
        e2e.expect(!e2e.onPlayer(buyerName, () -> e2e.player(buyerName).hasPermission("siftcore.command.ah")), "the permission is gone");
        // (clearLogs would forget the dialog on screen: the refusal is looked for as it is, it can't be there before.)
        e2e.expect(buyer.clickButton("Buy", Map.of()), "the Buy button is still there to click");
        e2e.eventually(() -> buyer.anyFeedbackContains("You can't do that."), "refused: " + buyer.actionBar());
        e2e.sleep(500);
        e2e.expect(e2e.money(buyerName) == 300_000 && e2e.money(sellerName) == 0, "no money moved");
        e2e.expect("ACTIVE".equals(text(e2e, "SELECT state FROM auction_listings WHERE id = ?", listing)), "the listing is still for sale");
        e2e.onPlayer(buyerName, () -> {
            e2e.player(buyerName).removeAttachment(ah[0]);
            return null;
        });
        e2e.sleep(300);
        openPurchase(e2e, buyer, "cobweb", sellerName);
        buyer.clickButton("Buy", Map.of());
        e2e.eventually(() -> e2e.money(sellerName) == 321, "with the permission back the purchase goes through");

        e2e.step("orders: siftcore.command.orders is revoked while the delivery menu is open");
        String ownerName = buyerName;
        long before = number(e2e, "SELECT COALESCE(MAX(id), 0) FROM orders WHERE owner = ?", e2e.uuid(ownerName).toString());
        command(e2e, buyer, "orders create cobblestone 10 50");
        e2e.dialog(buyer, "Place order");
        e2e.click(buyer, "Place order");
        e2e.eventually(() -> number(e2e, "SELECT COALESCE(MAX(id), 0) FROM orders WHERE owner = ? AND state = 'ACTIVE'",
            e2e.uuid(ownerName).toString()) > before, "the order is up");
        long order = number(e2e, "SELECT MAX(id) FROM orders WHERE owner = ?", e2e.uuid(ownerName).toString());
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.COBBLESTONE, 10)));
        long sellerMoney = e2e.money(sellerName);
        openMenu(e2e, seller, "orders cobblestone", ORDERS_TITLE);
        int slot = slotWith(e2e, seller, "Ordered by " + ownerName);
        Bot.Screen browser = seller.screen();
        seller.clickSlot(slot);
        e2e.eventually(() -> seller.screen() != null && seller.screen() != browser && seller.screen().title().contains("Deliver"),
            "the delivery menu opens");
        e2e.sleep(400);
        seller.clickSlot(51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.COBBLESTONE, 45) == 10, "the cobblestone is in the grid");
        PermissionAttachment[] orders = new PermissionAttachment[1];
        e2e.onPlayer(sellerName, () -> {
            orders[0] = e2e.player(sellerName).addAttachment(e2e.services().plugin(), "siftcore.command.orders", false);
            return null;
        });
        seller.clearLogs();
        e2e.sleep(400);
        seller.clickSlot(50);
        e2e.eventually(() -> seller.anyFeedbackContains("You can't do that."), "refused: " + seller.actionBar());
        e2e.sleep(500);
        e2e.expect(number(e2e, "SELECT filled FROM orders WHERE id = ?", order) == 0, "nothing was delivered");
        e2e.expect(e2e.money(sellerName) == sellerMoney, "no money moved");
        seller.closeScreen();
        e2e.eventually(() -> count(e2e, sellerName, Material.COBBLESTONE) == 10, "the cobblestone went back to the seller");
        e2e.onPlayer(sellerName, () -> {
            e2e.player(sellerName).removeAttachment(orders[0]);
            return null;
        });
        e2e.console("orders admin cancel " + order + " end of e2e");

        e2e.step("bounties: siftcore.bounties.place is revoked while the bounty confirmation is open");
        long escrow = e2e.services().ledger().balance(SystemAccounts.BOUNTY_ESCROW, Currency.MONEY);
        long sponsorMoney = e2e.money(buyerName);
        buyer.clearLogs();
        command(e2e, buyer, "bounty " + sellerName + " 150k");
        e2e.dialog(buyer, "Confirm bounty");
        PermissionAttachment[] bounty = new PermissionAttachment[1];
        e2e.onPlayer(buyerName, () -> {
            bounty[0] = e2e.player(buyerName).addAttachment(e2e.services().plugin(), "siftcore.bounties.place", false);
            return null;
        });
        e2e.expect(buyer.clickButton("Place bounty", Map.of()), "the Place bounty button is still there to click");
        e2e.eventually(() -> buyer.anyFeedbackContains("You can't do that."), "refused: " + buyer.actionBar());
        e2e.sleep(500);
        e2e.expect(e2e.money(buyerName) == sponsorMoney, "no money moved");
        e2e.expect(e2e.services().ledger().balance(SystemAccounts.BOUNTY_ESCROW, Currency.MONEY) == escrow, "no bounty was placed");
        e2e.onPlayer(buyerName, () -> {
            e2e.player(buyerName).removeAttachment(bounty[0]);
            return null;
        });
        ledgerHealthy(e2e);
    }
}
