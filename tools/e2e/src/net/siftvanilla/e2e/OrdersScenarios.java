package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.inventory.ContainerInput;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Buy orders end to end: placing orders with the command, the form and the item picker (with the typed input kept),
 * quick deliver straight from the inventory (with the changed-inventory re-check), the delivery menu with shulker
 * boxes and "Fill from inventory", raising the price and adding items, ordering again from the history, collecting
 * from every order at once, the history menus, the "while you were away" summary on join, staff cancelling with a
 * reason, enchanted book orders with the exact-book rule, combat blocking, and the escrow invariant after each.
 */
final class OrdersScenarios {

    private static final String BROWSER = "Orders";
    /** Raw slot of hotbar slot 0 below a six-row chest (hotbar slot n is 81 + n). */
    private static final int BELOW_HOTBAR = 81;

    private OrdersScenarios() {
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
        list.add(of("orders-create-command", OrdersScenarios::createCommand));
        list.add(of("orders-create-picker", OrdersScenarios::createPicker));
        list.add(of("orders-quick-deliver", OrdersScenarios::quickDeliver));
        list.add(of("orders-shulker-deliver", OrdersScenarios::shulkerDeliver));
        list.add(of("orders-fill-from-inventory", OrdersScenarios::fillFromInventory));
        list.add(of("orders-edit-raise-and-add", OrdersScenarios::editRaiseAndAdd));
        list.add(of("orders-order-again", OrdersScenarios::orderAgain));
        list.add(of("orders-collect-all", OrdersScenarios::collectAll));
        list.add(of("orders-history-views", OrdersScenarios::historyViews));
        list.add(of("orders-offline-notice-on-join", OrdersScenarios::offlineNotice));
        list.add(of("orders-staff-cancel-with-reason", OrdersScenarios::staffCancel));
        list.add(of("orders-book", OrdersScenarios::bookOrder));
        list.add(of("orders-spawner", OrdersScenarios::spawnerOrder));
        list.add(of("orders-browse", OrdersScenarios::browse));
        list.add(of("orders-combat-blocked", OrdersScenarios::combatBlocked));
        list.add(of("orders-sell-routing", OrdersScenarios::sellRouting));
        list.add(of("orders-delivery-death", OrdersScenarios::deliveryDeath));
        list.add(of("orders-collect-left-before-commit", OrdersScenarios::collectLeftBeforeCommit));
        list.add(of("orders-delivery-grid-copy", OrdersScenarios::deliveryGridCopy));
        list.add(of("orders-delivery-grid-crash", OrdersScenarios::deliveryGridCrash));
        list.add(of("orders-persist-setup", OrdersScenarios::persistSetup));
        list.add(of("orders-persist-check", OrdersScenarios::persistCheck));
        // The player settings of buy orders (MarketScenarios, the same package's file).
        list.addAll(MarketScenarios.withOrders());
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** Sends a command like a player types one, paced (the server kicks clients that send commands too fast). */
    private static void command(E2E e2e, Bot bot, String command) {
        e2e.sleep(700);
        bot.command(command);
    }

    private static void fund(E2E e2e, String name, long amount) {
        e2e.console("eco set " + name + " " + amount);
        e2e.eventually(() -> e2e.money(name) == amount, name + " has $" + amount);
    }

    /** Replaces the player's whole inventory: slot to stack. */
    private static void inventory(E2E e2e, String name, Map<Integer, ItemStack> slots) {
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.clear();
            inventory.setHeldItemSlot(0);
            slots.forEach((slot, stack) -> inventory.setItem(slot, stack == null ? null : stack.clone()));
            return null;
        });
    }

    private static ItemStack stack(Material material, int amount) {
        return ItemStack.of(material, amount);
    }

    /** A shulker box holding the given stacks at their slot positions. */
    private static ItemStack box(Map<Integer, ItemStack> contents) {
        int size = contents.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = contents.get(i);
            slots.add(stack == null ? ItemStack.empty() : stack.clone());
        }
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(slots));
        return box;
    }

    /** What a shulker box holds, slot by slot (empty stacks where nothing is). */
    private static List<ItemStack> contents(ItemStack box) {
        ItemContainerContents container = box == null ? null : box.getData(DataComponentTypes.CONTAINER);
        return container == null ? List.of() : container.contents();
    }

    private static ItemStack book(Map<Enchantment, Integer> enchantments, int repairCost) {
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.setData(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantments.itemEnchantments(enchantments));
        if (repairCost > 0) {
            book.setData(DataComponentTypes.REPAIR_COST, repairCost);
        }
        return book;
    }

    /** How many items of a material the player has in the hotbar and main inventory (not counting box contents). */
    private static int count(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            int total = 0;
            for (ItemStack stack : e2e.player(name).getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            return total;
        });
    }

    private static ItemStack slot(E2E e2e, String name, int slot) {
        return e2e.onPlayer(name, () -> {
            ItemStack stack = e2e.player(name).getInventory().getItem(slot);
            return stack == null ? null : stack.clone();
        });
    }

    /** The stacks in the open menu's top rows 1-5 (a delivery grid), as the server sees them. */
    private static int gridCount(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            Inventory top = e2e.player(name).getOpenInventory().getTopInventory();
            int total = 0;
            for (int i = 0; i < Math.min(45, top.getSize()); i++) {
                ItemStack stack = top.getItem(i);
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            return total;
        });
    }

    @FunctionalInterface
    private interface Rows<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** Runs a read against SiftCore's database after every queued write was committed. */
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
        return query(e2e, sql, rs -> rs.next() ? rs.getString(1) : null, params);
    }

    /** The newest order of a player, or 0. */
    private static long latestOrder(E2E e2e, String owner) {
        return number(e2e, "SELECT COALESCE(MAX(id), 0) FROM orders WHERE owner = ?", e2e.uuid(owner).toString());
    }

    /** One stored order as {@code state/quantity/filled/collected/price_each/escrow}. */
    private static String row(E2E e2e, long id) {
        return query(e2e, "SELECT state, quantity, filled, collected, price_each, escrow FROM orders WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) + "/" + rs.getInt(2) + "/" + rs.getInt(3) + "/" + rs.getInt(4) + "/" + rs.getLong(5)
                + "/" + rs.getLong(6) : "none", id);
    }

    /** The escrow account holds exactly what the open orders hold, the ledger is sound, and storage agrees. */
    private static void healthy(E2E e2e) throws Exception {
        Ledger.AuditReport report = e2e.services().ledger().audit().get(20, TimeUnit.SECONDS);
        e2e.expect(report.healthy(), "the ledger invariants hold: " + report.problems());
        long account = e2e.services().ledger().balance(SystemAccounts.ORDERS_ESCROW, Currency.MONEY);
        long stored = number(e2e, "SELECT COALESCE(SUM(escrow), 0) FROM orders WHERE state = 'ACTIVE'");
        e2e.expect(account == stored, "the orders escrow account (" + account + ") equals what open orders hold (" + stored + ")");
        List<String> check = e2e.consoleOutput("orders admin check");
        e2e.expect(check.stream().anyMatch(line -> line.contains("Orders are consistent")), "/orders admin check passes: " + check);
    }

    /** Cancels every active order of a player from the console, so later scenarios see a short browser. */
    private static void cancelAll(E2E e2e, String owner) {
        List<Long> ids = query(e2e, "SELECT id FROM orders WHERE owner = ? AND state = 'ACTIVE'", rs -> {
            List<Long> list = new ArrayList<>();
            while (rs.next()) {
                list.add(rs.getLong(1));
            }
            return list;
        }, e2e.uuid(owner).toString());
        for (long id : ids) {
            e2e.console("orders admin cancel " + id + " end of test");
        }
        e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM orders WHERE owner = ? AND state = 'ACTIVE'",
            e2e.uuid(owner).toString()) == 0, owner + " has no active orders left");
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> lore(net.minecraft.world.item.ItemStack stack) {
        List<String> lines = new ArrayList<>();
        if (stack == null) {
            return lines;
        }
        ItemLore lore = CraftItemStack.asBukkitCopy(stack).getData(DataComponentTypes.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(plain(line));
            }
        }
        return lines;
    }

    /** The colour the first visible character of a component is drawn in (inherited from its parents). */
    private static net.kyori.adventure.text.format.TextColor firstColour(Component component,
                                                                         net.kyori.adventure.text.format.TextColor inherited) {
        net.kyori.adventure.text.format.TextColor colour = component.color() == null ? inherited : component.color();
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty()) {
            return colour;
        }
        for (Component child : component.children()) {
            net.kyori.adventure.text.format.TextColor found = firstColour(child, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The option a cycle button (sort, filter) has selected: its one white bullet line. */
    private static String selectedOption(Bot bot, int slot) {
        net.minecraft.world.item.ItemStack stack = bot.screenItems().get(slot);
        ItemLore lore = stack == null ? null : CraftItemStack.asBukkitCopy(stack).getData(DataComponentTypes.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String text = plain(line);
                if (text.startsWith("• ") && net.kyori.adventure.text.format.NamedTextColor.WHITE.equals(firstColour(line, null))) {
                    return text.substring(2);
                }
            }
        }
        return null;
    }

    private static List<String> lore(Bot bot, int slot) {
        return lore(bot.screenItems().get(slot));
    }

    private static boolean loreHas(Bot bot, int slot, String text) {
        return lore(bot, slot).stream().anyMatch(line -> line.contains(text));
    }

    /** Entry slots (0-44) whose lore has lines containing every text, in slot order. */
    private static List<Integer> slotsWith(Bot bot, String... texts) {
        List<Integer> slots = new ArrayList<>();
        for (var entry : new TreeMap<>(bot.screenItems()).entrySet()) {
            if (entry.getKey() >= 45) {
                continue;
            }
            List<String> lore = lore(entry.getValue());
            boolean all = true;
            for (String text : texts) {
                all &= lore.stream().anyMatch(line -> line.contains(text));
            }
            if (all) {
                slots.add(entry.getKey());
            }
        }
        return slots;
    }

    private static int slotWith(E2E e2e, Bot bot, String... texts) {
        e2e.eventually(() -> !slotsWith(bot, texts).isEmpty(), bot.name + " sees an entry with " + List.of(texts));
        return slotsWith(bot, texts).getFirst();
    }

    /** The first entry slot (0-44) holding an item of the material, or -1. */
    private static int slotOf(Bot bot, Material material) {
        for (var entry : new TreeMap<>(bot.screenItems()).entrySet()) {
            if (entry.getKey() < 45 && CraftItemStack.asBukkitCopy(entry.getValue()).getType() == material) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private static void awaitScreen(E2E e2e, Bot bot, Bot.Screen before, String title) {
        e2e.eventually(() -> bot.screen() != null && bot.screen() != before && bot.screen().title().contains(title)
            && !bot.screenItems().isEmpty(), bot.name + " sees the screen '" + title + "' (now "
            + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(250);
    }

    private static void openMenu(E2E e2e, Bot bot, String command, String title) {
        Bot.Screen before = bot.screen();
        command(e2e, bot, command);
        awaitScreen(e2e, bot, before, title);
    }

    /** Waits for a dialog the bot had not received before. */
    private static Bot.SeenDialog newDialog(E2E e2e, Bot bot, int seenBefore, String title) {
        e2e.eventually(() -> bot.dialogs().size() > seenBefore && bot.dialogs().getLast().title().contains(title),
            bot.name + " receives the dialog '" + title + "' (last: " + (bot.dialogs().isEmpty() ? "none" : bot.dialogs().getLast().title()) + ")");
        return bot.dialogs().getLast();
    }

    /** What a real client sends for a form: every field as shown, with the given fields typed over. */
    private static Map<String, Object> typed(Bot.SeenDialog dialog, Map<String, Object> changes) {
        Map<String, Object> values = new HashMap<>(dialog.initial());
        values.putAll(changes);
        return values;
    }

    /**
     * Clicks a slot of the open screen like a player: menus ignore clicks closer together than the configured click
     * interval (75 ms), so every click waits a moment first.
     */
    private static void clickSlot(E2E e2e, Bot bot, int slot) {
        clickSlot(e2e, bot, slot, 0, ContainerInput.PICKUP);
    }

    private static void clickSlot(E2E e2e, Bot bot, int slot, int button, ContainerInput input) {
        e2e.sleep(200);
        bot.clickSlot(slot, button, input);
    }

    /** Clicks a slot and waits for a new dialog with the title. */
    private static Bot.SeenDialog clickForDialog(E2E e2e, Bot bot, int slot, int button, ContainerInput input, String title) {
        int seen = bot.dialogs().size();
        clickSlot(e2e, bot, slot, button, input);
        return newDialog(e2e, bot, seen, title);
    }

    /** Clicks a dialog button and waits for a new dialog with the title. */
    private static Bot.SeenDialog clickToDialog(E2E e2e, Bot bot, String label, Map<String, Object> values, String title) {
        int seen = bot.dialogs().size();
        e2e.expect(bot.clickButton(label, values), bot.name + " can click '" + label + "' in "
            + (bot.dialog() == null ? "no dialog" : bot.dialog().title() + " " + bot.dialog().buttons()));
        return newDialog(e2e, bot, seen, title);
    }

    /** Clicks a dialog button and waits for a chest screen with the title. */
    private static void clickToScreen(E2E e2e, Bot bot, String label, Map<String, Object> values, String title) {
        Bot.Screen before = bot.screen();
        e2e.expect(bot.clickButton(label, values), bot.name + " can click '" + label + "' in "
            + (bot.dialog() == null ? "no dialog" : bot.dialog().title() + " " + bot.dialog().buttons()));
        awaitScreen(e2e, bot, before, title);
    }

    /** Places an order with /orders create and its confirmation; returns the stored order's id. */
    private static long place(E2E e2e, Bot bot, String item, String quantity, String price) {
        long before = latestOrder(e2e, bot.name);
        bot.clearLogs();
        command(e2e, bot, "orders create " + item + " " + quantity + " " + price);
        e2e.dialog(bot, "Place order");
        e2e.click(bot, "Place order");
        e2e.eventually(() -> latestOrder(e2e, bot.name) > before, bot.name + " has a new order");
        long id = latestOrder(e2e, bot.name);
        e2e.eventually(() -> row(e2e, id).startsWith("ACTIVE/"), "order " + id + " is stored as active");
        e2e.eventually(() -> bot.chatContains("Your order for"), bot.name + " is told the order is up: " + bot.chat());
        return id;
    }

    /** Opens the browser searching for an item and returns the slot of the owner's order. */
    private static int browseTo(E2E e2e, Bot bot, String search, String owner) {
        openMenu(e2e, bot, "orders " + search, BROWSER);
        return slotWith(e2e, bot, "Ordered by " + owner);
    }

    /** Quick deliver from the browser: right click the owner's order, confirm, wait until the order counts it. */
    private static void quick(E2E e2e, Bot seller, String search, String owner, long orderId, int units, String button) {
        int slot = browseTo(e2e, seller, search, owner);
        clickForDialog(e2e, seller, slot, 1, ContainerInput.PICKUP, "Deliver");
        Bot.Screen before = seller.screen();
        e2e.click(seller, button);
        e2e.eventually(() -> {
            String[] parts = row(e2e, orderId).split("/");
            return parts.length > 2 && Integer.parseInt(parts[2]) >= units;
        }, "order " + orderId + " counts " + units + " delivered: " + row(e2e, orderId));
        awaitScreen(e2e, seller, before, BROWSER);
    }

    // ------------------------------------------------------------------ placing

    static void createCommand(E2E e2e) throws Exception {
        String name = e2e.name("OrdCmd");
        Bot bot = e2e.bot(name);
        fund(e2e, name, 100_000);

        e2e.step("/orders create with every argument goes straight to the confirmation");
        command(e2e, bot, "orders create diamond 2st 450");
        Bot.SeenDialog confirm = e2e.dialog(bot, "Place order");
        String body = confirm.bodyText();
        e2e.expect(body.contains("128 Diamond at $450 each"), "the resolved quantity, item and price: " + body);
        e2e.expect(body.contains("$57,600 is held now and paid out as items arrive."), "the money held: " + body);
        e2e.expect(body.contains("The order ends in 7d."), "the duration: " + body);
        e2e.expect(body.contains("The server pays $400 each for Diamond."), "what the server pays: " + body);
        e2e.expect(body.contains("Players get more from /sell, so few will deliver"),
            "the warning, since $450 less 2% tax is under $400 at the best rank bonus (1.5x): " + body);
        e2e.expect(e2e.money(name) == 100_000, "nothing held before confirming");

        e2e.step("Back from the confirmation places nothing");
        bot.clearMessages();
        e2e.click(bot, "Back");
        e2e.eventually(() -> bot.actionBarContains("Order not placed."), "told nothing happened: " + bot.actionBar());
        e2e.expect(latestOrder(e2e, name) == 0, "no order row");

        e2e.step("confirming holds the money in the orders escrow");
        long escrowBefore = e2e.services().ledger().balance(SystemAccounts.ORDERS_ESCROW, Currency.MONEY);
        long id = place(e2e, bot, "diamond", "2st", "450");
        e2e.eventually(() -> bot.chatContains("Your order for 128 Diamond is up. $57,600 is held for it."), "the receipt: " + bot.chat());
        e2e.expect(e2e.money(name) == 100_000 - 57_600, "$57,600 left the buyer: " + e2e.money(name));
        e2e.expect(e2e.services().ledger().balance(SystemAccounts.ORDERS_ESCROW, Currency.MONEY) == escrowBefore + 57_600,
            "the escrow account holds it");
        e2e.expect(row(e2e, id).equals("ACTIVE/128/0/0/450/57600"), "the stored order: " + row(e2e, id));
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM ledger WHERE ref = ? AND kind = 'order_escrow'", "order:" + id) == 2,
            "one transfer of kind order_escrow");

        e2e.step("refusals say exactly what is wrong");
        bot.clearMessages();
        command(e2e, bot, "orders create diamond 0 450");
        e2e.eventually(() -> bot.actionBarContains("The amount must be a whole number from 1 to 100,000"), "zero items: " + bot.actionBar());
        bot.clearMessages();
        command(e2e, bot, "orders create bedrock 1 10");
        e2e.eventually(() -> bot.actionBarContains("Bedrock can't be ordered."), "a blocked item: " + bot.actionBar());
        bot.clearMessages();
        command(e2e, bot, "orders create notanitem 1 10");
        e2e.eventually(() -> bot.actionBarContains("There is no item called notanitem."), "an unknown item: " + bot.actionBar());
        bot.clearMessages();
        command(e2e, bot, "orders create diamond 1 1e9");
        e2e.eventually(() -> bot.actionBarContains("is not an amount"), "an exponent price: " + bot.actionBar());
        bot.clearMessages();
        command(e2e, bot, "orders create enchanted_book 1 10");
        e2e.eventually(() -> bot.actionBarContains("Choose which Enchanted Book with Choose item."), "a variant family alone: " + bot.actionBar());
        bot.clearMessages();
        command(e2e, bot, "orders create diamond 1000 450");
        e2e.eventually(() -> bot.actionBarContains("You need $450,000 for that."), "not enough money: " + bot.actionBar());

        e2e.step("three active orders are the default limit");
        place(e2e, bot, "cobblestone", "10", "2");
        place(e2e, bot, "dirt", "10", "1");
        bot.clearMessages();
        command(e2e, bot, "orders create sand 10 1");
        e2e.eventually(() -> bot.actionBarContains("You already have 3 active orders, your most."), "the limit: " + bot.actionBar());
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM orders WHERE owner = ? AND state = 'ACTIVE'", e2e.uuid(name).toString()) == 3,
            "still three orders");
        cancelAll(e2e, name);
        e2e.eventually(() -> e2e.money(name) == 100_000, "every refund came back: " + e2e.money(name));
        healthy(e2e);
    }

    static void createPicker(E2E e2e) throws Exception {
        String name = e2e.name("OrdPick");
        Bot bot = e2e.bot(name);
        fund(e2e, name, 50_000);
        inventory(e2e, name, Map.of(0, stack(Material.DIAMOND, 1)));

        e2e.step("the New order button opens the form, filled in from the held item with a suggested price");
        openMenu(e2e, bot, "orders", BROWSER);
        e2e.expect(loreHas(bot, 50, "You have 0 of 3 active orders"), "the new order button: " + lore(bot, 50));
        Bot.SeenDialog form = clickForDialog(e2e, bot, 50, 0, ContainerInput.PICKUP, "New order");
        e2e.expect("diamond".equals(form.initial("item")), "the held item fills the item field: " + form.initial());
        e2e.expect("449".equals(form.initial("price")), "the suggested price is $400 x 1.1 / 0.98 rounded up: " + form.initial());
        e2e.expect(form.bodyText().contains("You have 0 of 3 active orders."), "the limit line: " + form.body());

        e2e.step("Choose item keeps what was typed, and the picker's back button returns to it");
        Bot.Screen before = bot.screen();
        e2e.expect(bot.clickButton("Choose item", typed(form, Map.of("item", "diamond", "quantity", "3 stacks", "price", "90"))),
            "can click Choose item");
        awaitScreen(e2e, bot, before, "Choose an item");
        int seen = bot.dialogs().size();
        clickSlot(e2e, bot, 46);
        Bot.SeenDialog back = newDialog(e2e, bot, seen, "New order");
        e2e.expect("3 stacks".equals(back.initial("quantity")) && "90".equals(back.initial("price")),
            "the typed quantity and price are kept: " + back.initial());

        e2e.step("search the picker and choose gold ingot");
        before = bot.screen();
        e2e.expect(bot.clickButton("Choose item", typed(back, Map.of())), "can click Choose item again");
        awaitScreen(e2e, bot, before, "Choose an item");
        Bot.SeenDialog search = clickForDialog(e2e, bot, 49, 0, ContainerInput.PICKUP, "Search");
        clickToScreen(e2e, bot, "Submit", typed(search, Map.of("query", "gold ingot")), "Choose an item");
        e2e.eventually(() -> slotOf(bot, Material.GOLD_INGOT) >= 0, "gold ingot is listed");
        int gold = slotOf(bot, Material.GOLD_INGOT);
        e2e.expect(loreHas(bot, gold, "Server pays $35 each"), "the picker shows what the server pays: " + lore(bot, gold));
        Bot.SeenDialog chosen = clickForDialog(e2e, bot, gold, 0, ContainerInput.PICKUP, "New order");
        e2e.expect("gold_ingot".equals(chosen.initial("item")), "the item is set: " + chosen.initial());
        e2e.expect("3 stacks".equals(chosen.initial("quantity")) && "90".equals(chosen.initial("price")),
            "the quantity and price are still what was typed: " + chosen.initial());
        e2e.expect(chosen.bodyText().contains("Gold Ingot"), "the chosen item is shown: " + chosen.body());

        e2e.step("Next shows the confirmation with the resolved quantity; placing it returns to the browser");
        Bot.SeenDialog confirm = clickToDialog(e2e, bot, "Next", typed(chosen, Map.of()), "Place order");
        e2e.expect(confirm.bodyText().contains("192 Gold Ingot at $90 each"), "3 stacks of gold: " + confirm.body());
        e2e.expect(confirm.bodyText().contains("$17,280 is held now"), "the total: " + confirm.body());
        e2e.expect(!confirm.bodyText().contains("Players get more from /sell"), "$90 less tax beats $35 at the best sell multiplier: " + confirm.body());

        e2e.step("Back on the confirmation puts the form in its place, without closing anything first");
        int cleared = bot.dialogsCleared();
        Bot.Screen browser = bot.screen();
        Bot.SeenDialog edit = clickToDialog(e2e, bot, "Back", Map.of(), "New order");
        e2e.expect(bot.dialogsCleared() == cleared, "no dialog close between the confirmation and the form");
        e2e.expect(bot.screen() != null && bot.screen() == browser, "the browser under the dialogs is still open");
        e2e.expect("gold_ingot".equals(edit.initial("item")) && "3 stacks".equals(edit.initial("quantity")),
            "the form keeps the order: " + edit.initial());
        confirm = clickToDialog(e2e, bot, "Next", typed(edit, Map.of()), "Place order");
        before = bot.screen();
        e2e.expect(bot.clickButton("Place order", Map.of()), "can place it");
        awaitScreen(e2e, bot, before, BROWSER);
        e2e.eventually(() -> latestOrder(e2e, name) > 0 && row(e2e, latestOrder(e2e, name)).equals("ACTIVE/192/0/0/90/17280"),
            "the order is stored: " + row(e2e, latestOrder(e2e, name)));
        e2e.expect(text(e2e, "SELECT item_type FROM orders WHERE id = ?", latestOrder(e2e, name)).equals("minecraft:gold_ingot"),
            "for gold ingots");
        e2e.eventually(() -> e2e.money(name) == 50_000 - 17_280, "the money is held");

        e2e.step("an invalid form comes back with the error and what was typed");
        Bot.SeenDialog again = clickForDialog(e2e, bot, 50, 0, ContainerInput.PICKUP, "New order");
        int seenErrors = bot.dialogs().size();
        e2e.expect(bot.clickButton("Next", typed(again, Map.of("item", "diamond", "quantity", "lots", "price", "100"))), "can submit");
        Bot.SeenDialog error = newDialog(e2e, bot, seenErrors, "New order");
        e2e.expect(error.bodyText().contains("The amount must be a whole number from 1 to 100,000"), "the error line: " + error.body());
        e2e.expect("lots".equals(error.initial("quantity")) && "100".equals(error.initial("price")), "the typed values: " + error.initial());
        bot.clickButton("Back", Map.of());
        cancelAll(e2e, name);
        healthy(e2e);
    }

    // ------------------------------------------------------------------ delivering

    static void quickDeliver(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdQBuy");
        String sellerName = e2e.name("OrdQSell");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 100_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "diamond", "100", "500");
        ItemStack renamed = stack(Material.DIAMOND, 3);
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Shiny"));
        inventory(e2e, sellerName, Map.of(0, stack(Material.DIAMOND, 40),
            1, box(Map.of(0, stack(Material.DIAMOND, 20), 4, stack(Material.DIRT, 5))), 2, renamed));

        e2e.step("the browser entry shows the price, the payout after tax, what the server pays and what the seller carries");
        int slot = browseTo(e2e, seller, "diamond", buyerName);
        List<String> entry = lore(seller, slot);
        e2e.expect(entry.contains("Price each $500"), "price: " + entry);
        e2e.expect(entry.contains("You get $490 each after 2% tax"), "net: " + entry);
        e2e.expect(entry.contains("Delivered 0 of 100"), "progress: " + entry);
        e2e.expect(entry.contains("Server pays $400 each"), "worth: " + entry);
        e2e.expect(entry.contains("You carry 60"), "plain diamonds including the box, not the renamed ones: " + entry);
        e2e.expect(entry.contains("Right click to deliver from your inventory"), "the quick hint: " + entry);

        e2e.step("right click shows what quick deliver does");
        Bot.SeenDialog dialog = clickForDialog(e2e, seller, slot, 1, ContainerInput.PICKUP, "Deliver Diamond");
        String body = dialog.bodyText();
        e2e.expect(body.contains("You carry 60 plain Diamond (20 in shulker boxes)"), "what is carried: " + body);
        e2e.expect(body.contains("Still wanted 100"), "what is wanted: " + body);
        e2e.expect(body.contains("You get $29,400 after $600 tax"), "the payout: " + body);
        e2e.expect(!body.contains("The server pays more"), "the order pays more than the server: " + body);
        e2e.expect(dialog.button("Deliver 60 for $29,400") != null && dialog.button("Open delivery menu") != null,
            "the buttons: " + dialog.buttons());

        e2e.step("a changed inventory is shown again before anything moves");
        e2e.onPlayer(sellerName, () -> {
            e2e.player(sellerName).getInventory().setItem(5, stack(Material.DIAMOND, 1));
            return null;
        });
        Bot.SeenDialog changed = clickToDialog(e2e, seller, "Deliver 60", Map.of(), "Deliver Diamond");
        e2e.expect(changed.bodyText().contains("That order or your inventory changed. Check, then deliver."), "the re-check: " + changed.body());
        e2e.expect(changed.button("Deliver 61 for $29,890") != null, "the fresh count: " + changed.buttons());
        e2e.expect(e2e.money(sellerName) == 0 && count(e2e, sellerName, Material.DIAMOND) == 44, "nothing moved yet");

        e2e.step("delivering takes the plain diamonds and the box's diamonds, and pays after tax");
        seller.clearMessages();
        buyer.clearMessages();
        Bot.Screen before = seller.screen();
        e2e.click(seller, "Deliver 61");
        e2e.eventually(() -> e2e.money(sellerName) == 29_890, "paid 61 x $500 less $610 tax: " + e2e.money(sellerName));
        e2e.eventually(() -> seller.chatContains("You delivered 61 Diamond and got $29,890 after $610 tax."), "the receipt: " + seller.chat());
        e2e.eventually(() -> buyer.chatContains(sellerName + " delivered 61 Diamond to your order."), "the owner is told: " + buyer.chat());
        awaitScreen(e2e, seller, before, BROWSER);
        e2e.expect(count(e2e, sellerName, Material.DIAMOND) == 3, "only the renamed diamonds stay: " + count(e2e, sellerName, Material.DIAMOND));
        ItemStack emptied = slot(e2e, sellerName, 1);
        List<ItemStack> left = contents(emptied);
        e2e.expect(emptied != null && emptied.getType() == Material.SHULKER_BOX, "the box stays in its slot");
        e2e.expect(left.size() == 5 && left.get(0).isEmpty() && left.get(4).getType() == Material.DIRT && left.get(4).getAmount() == 5,
            "the box keeps its dirt in place: " + left);
        e2e.expect(row(e2e, id).equals("ACTIVE/100/61/0/500/19500"), "the order counts 61: " + row(e2e, id));
        e2e.expect("quick".equals(text(e2e, "SELECT source FROM order_fills WHERE order_id = ?", id)), "a quick fill row");
        e2e.expect(number(e2e, "SELECT -SUM(delta) FROM ledger WHERE ref = ? AND kind = 'order_tax'", "order:" + id) == 610, "the tax was sunk");
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    static void shulkerDeliver(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdBoxBuy");
        String sellerName = e2e.name("OrdBoxSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "iron_ingot", "50", "30");
        inventory(e2e, sellerName, Map.of(0, box(Map.of(0, stack(Material.COBBLESTONE, 10), 1, stack(Material.IRON_INGOT, 30),
            2, stack(Material.IRON_INGOT, 5))), 1, stack(Material.IRON_INGOT, 10), 2, stack(Material.GOLD_INGOT, 4)));

        e2e.step("clicking the order opens its delivery menu with the item rule");
        int slot = browseTo(e2e, seller, "iron", buyerName);
        Bot.Screen before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Iron Ingot");
        e2e.expect(loreHas(seller, 48, "Only exact Iron Ingot count:"), "the rule: " + lore(seller, 48));
        e2e.expect(loreHas(seller, 50, "Put Iron Ingot in the slots above"), "an empty grid: " + lore(seller, 50));

        e2e.step("shift click the box, the loose ingots and gold into the grid");
        clickSlot(e2e, seller, BELOW_HOTBAR, 0, ContainerInput.QUICK_MOVE);
        clickSlot(e2e, seller, BELOW_HOTBAR + 1, 0, ContainerInput.QUICK_MOVE);
        clickSlot(e2e, seller, BELOW_HOTBAR + 2, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> loreHas(seller, 50, "Accepted 45 of 50 still wanted (35 from shulker boxes)")
            && loreHas(seller, 50, "Not accepted 4, given back when you close"), "the deliver button counts the box and not the gold: "
            + lore(seller, 50));
        e2e.expect(loreHas(seller, 50, "You get $1,323 after $27 tax"), "the payout: " + lore(seller, 50));

        e2e.step("Deliver takes the loose ingots first, then the box's ingots; the box and the gold come back");
        seller.clearMessages();
        before = seller.screen();
        clickSlot(e2e, seller, 50);
        e2e.eventually(() -> e2e.money(sellerName) == 1_323, "paid 45 x $30 less tax: " + e2e.money(sellerName));
        awaitScreen(e2e, seller, before, BROWSER);
        e2e.expect(row(e2e, id).equals("ACTIVE/50/45/0/30/150"), "the order counts 45: " + row(e2e, id));
        e2e.eventually(() -> count(e2e, sellerName, Material.GOLD_INGOT) == 4, "the gold came back");
        e2e.expect(count(e2e, sellerName, Material.IRON_INGOT) == 0, "every ingot went");
        ItemStack returned = e2e.onPlayer(sellerName, () -> {
            for (ItemStack stack : e2e.player(sellerName).getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == Material.SHULKER_BOX) {
                    return stack.clone();
                }
            }
            return null;
        });
        List<ItemStack> inside = contents(returned);
        e2e.expect(returned != null && !inside.isEmpty() && inside.get(0).getType() == Material.COBBLESTONE && inside.get(0).getAmount() == 10
            && inside.stream().noneMatch(stack -> stack.getType() == Material.IRON_INGOT), "the box came back with its cobblestone only: " + inside);
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    static void fillFromInventory(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdFillBuy");
        String sellerName = e2e.name("OrdFillSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "gold_ingot", "20", "50");
        inventory(e2e, sellerName, Map.of(0, stack(Material.GOLD_INGOT, 30), 3, box(Map.of(0, stack(Material.GOLD_INGOT, 5)))));

        e2e.step("Fill from inventory moves what the order still wants into the grid");
        int slot = browseTo(e2e, seller, "gold", buyerName);
        Bot.Screen before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Gold Ingot");
        e2e.expect(seller.screenItems().get(51) != null && CraftItemStack.asBukkitCopy(seller.screenItems().get(51)).getType() == Material.HOPPER,
            "the fill button is a hopper in slot 51");
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.GOLD_INGOT) == 20, "20 gold in the grid");
        e2e.expect(count(e2e, sellerName, Material.GOLD_INGOT) == 10, "10 stay in the inventory");
        e2e.eventually(() -> loreHas(seller, 50, "Accepted 20 of 20 still wanted (0 from shulker boxes)"), "the button: " + lore(seller, 50));

        e2e.step("closing the menu gives everything back");
        seller.closeScreen();
        e2e.eventually(() -> count(e2e, sellerName, Material.GOLD_INGOT) == 30, "the 30 gold are back");
        e2e.expect(e2e.money(sellerName) == 0, "nothing was paid");

        e2e.step("fill again and deliver: the order completes and its owner is told");
        slot = browseTo(e2e, seller, "gold", buyerName);
        before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Gold Ingot");
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.GOLD_INGOT) == 20, "20 gold in the grid");
        buyer.clearMessages();
        clickSlot(e2e, seller, 50);
        e2e.eventually(() -> e2e.money(sellerName) == 980, "paid 20 x $50 less $20 tax: " + e2e.money(sellerName) + " " + seller.actionBar()
            + " " + seller.chat() + " screen " + (seller.screen() == null ? "none" : seller.screen().title()));
        e2e.eventually(() -> row(e2e, id).equals("FILLED/20/20/0/50/0"), "the order is complete: " + row(e2e, id));
        e2e.eventually(() -> buyer.chatContains("Your order for 20 Gold Ingot is complete. Collect it in /orders."), "the owner: " + buyer.chat());
        e2e.expect(count(e2e, sellerName, Material.GOLD_INGOT) == 10, "10 gold stay with the seller");
        e2e.expect(contents(slot(e2e, sellerName, 3)).stream().anyMatch(stack -> stack.getType() == Material.GOLD_INGOT
            && stack.getAmount() == 5), "the box was never needed and kept its gold");

        e2e.step("the owner collects the delivered gold");
        inventory(e2e, buyerName, Map.of());
        openMenu(e2e, buyer, "orders mine", "Your orders");
        int own = slotWith(e2e, buyer, "Waiting for you 20");
        Bot.SeenDialog dialog = clickForDialog(e2e, buyer, own, 0, ContainerInput.PICKUP, "Your order");
        e2e.expect(dialog.bodyText().contains("Complete"), "the state: " + dialog.body());
        buyer.clearMessages();
        e2e.click(buyer, "Collect items");
        e2e.eventually(() -> count(e2e, buyerName, Material.GOLD_INGOT) == 20, "the owner has the gold");
        e2e.eventually(() -> row(e2e, id).equals("FILLED/20/20/20/50/0"), "collected in storage: " + row(e2e, id));
        healthy(e2e);
    }

    // ------------------------------------------------------------------ the owner's tools

    static void editRaiseAndAdd(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdEdBuy");
        String sellerName = e2e.name("OrdEdSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "cobblestone", "100", "5");
        inventory(e2e, sellerName, Map.of(0, stack(Material.COBBLESTONE, 20)));
        quick(e2e, seller, "cobblestone", buyerName, id, 20, "Deliver 20");
        e2e.expect(row(e2e, id).equals("ACTIVE/100/20/0/5/400"), "20 delivered: " + row(e2e, id));

        e2e.step("a delivery menu opened at the old price");
        inventory(e2e, sellerName, Map.of(0, stack(Material.COBBLESTONE, 10)));
        int slot = browseTo(e2e, seller, "cobblestone", buyerName);
        Bot.Screen before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Cobblestone");
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.COBBLESTONE) == 10, "10 cobblestone in the grid");

        e2e.step("the owner raises the price: lower prices are refused");
        openMenu(e2e, buyer, "orders mine", "Your orders");
        int own = slotWith(e2e, buyer, "Delivered 20 of 100");
        Bot.SeenDialog dialog = clickForDialog(e2e, buyer, own, 0, ContainerInput.PICKUP, "Your order");
        e2e.expect(dialog.button("Raise price") != null && dialog.button("Add more") != null, "the edit buttons: " + dialog.buttons());
        Bot.SeenDialog form = clickToDialog(e2e, buyer, "Raise price", Map.of(), "Change order");
        e2e.expect("5".equals(form.initial("price")) && "0".equals(form.initial("add")), "the form starts at the current terms: " + form.initial());
        Bot.SeenDialog lower = clickToDialog(e2e, buyer, "Next", typed(form, Map.of("price", "4")), "Change order");
        e2e.expect(lower.bodyText().contains("Prices can only go up. Cancel and place a new order instead."), "lower refused: " + lower.body());

        e2e.step("raise to $6 and add 50: hold (150 - 20) x $6 - $400 = $380 more");
        Bot.SeenDialog confirm = clickToDialog(e2e, buyer, "Next", typed(lower, Map.of("price", "6", "add", "50")), "Change order");
        e2e.expect(confirm.bodyText().contains("Hold $380 more for this order?"), "the extra: " + confirm.body());
        e2e.expect(confirm.bodyText().contains("150 Cobblestone at $6 each from now on."), "the new terms: " + confirm.body());
        buyer.clearMessages();
        clickToDialog(e2e, buyer, "Hold it", Map.of(), "Your order");
        e2e.eventually(() -> row(e2e, id).equals("ACTIVE/150/20/0/6/780"), "the stored order: " + row(e2e, id));
        e2e.eventually(() -> e2e.money(buyerName) == 10_000 - 500 - 380, "$380 more is held: " + e2e.money(buyerName));
        e2e.eventually(() -> buyer.chatContains("Your order is now 150 Cobblestone at $6 each. $380 more is held for it."), "told: " + buyer.chat());

        e2e.step("the open delivery menu at $5 is refused and keeps the items");
        seller.clearMessages();
        clickSlot(e2e, seller, 50);
        e2e.eventually(() -> seller.actionBarContains("That order changed. Open it again."), "the stale menu: " + seller.actionBar());
        e2e.expect(gridCount(e2e, sellerName, Material.COBBLESTONE) == 10, "the items are still in the grid");
        e2e.expect(row(e2e, id).equals("ACTIVE/150/20/0/6/780"), "nothing delivered");
        seller.closeScreen();
        e2e.eventually(() -> count(e2e, sellerName, Material.COBBLESTONE) == 10, "the cobblestone came back");
        e2e.expect(e2e.money(sellerName) == 98, "still only the first delivery paid: " + e2e.money(sellerName));
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    static void orderAgain(E2E e2e) throws Exception {
        String name = e2e.name("OrdAgain");
        Bot bot = e2e.bot(name);
        fund(e2e, name, 10_000);
        long id = place(e2e, bot, "emerald", "5", "100");

        e2e.step("the owner cancels: everything held comes back");
        openMenu(e2e, bot, "orders mine", "Your orders");
        int own = slotWith(e2e, bot, "Money held $500");
        clickForDialog(e2e, bot, own, 0, ContainerInput.PICKUP, "Your order");

        e2e.step("Details replaces the order's dialog after its reads, without closing it or the list under it");
        int cleared = bot.dialogsCleared();
        Bot.Screen list = bot.screen();
        Bot.SeenDialog details = clickToDialog(e2e, bot, "Details", Map.of(), "Order details");
        e2e.expect(details.bodyText().contains("Order #" + id), "the details of this order: " + details.body());
        e2e.expect(bot.dialogsCleared() == cleared, "no dialog close before the details");
        e2e.expect(bot.screen() != null && bot.screen() == list, "the list under the dialog is still open");
        clickToDialog(e2e, bot, "Back", Map.of(), "Your order");

        Bot.SeenDialog cancel = clickToDialog(e2e, bot, "Cancel order", Map.of(), "Cancel order");
        e2e.expect(cancel.bodyText().contains("$500 comes back to you."), "the refund: " + cancel.body());
        bot.clearMessages();
        e2e.click(bot, "Cancel order");
        e2e.eventually(() -> row(e2e, id).startsWith("CANCELLED/"), "cancelled: " + row(e2e, id));
        e2e.eventually(() -> e2e.money(name) == 10_000, "refunded: " + e2e.money(name));
        e2e.eventually(() -> bot.chatContains("You cancelled your order for 5 Emerald and $500 came back to you."), "told: " + bot.chat());
        e2e.expect(number(e2e, "SELECT refunded FROM orders WHERE id = ?", id) == 500, "the refund is stored");

        e2e.step("Past orders shows it, and clicking it orders the same again");
        openMenu(e2e, bot, "orders history", "Past orders");
        int past = slotWith(e2e, bot, "Cancelled", "Came back to you $500");
        e2e.expect(loreHas(bot, past, "Click to order again"), "the hint: " + lore(bot, past));
        Bot.SeenDialog confirm = clickForDialog(e2e, bot, past, 0, ContainerInput.PICKUP, "Place order");
        e2e.expect(confirm.bodyText().contains("5 Emerald at $100 each"), "the same terms: " + confirm.body());
        Bot.Screen before = bot.screen();
        e2e.expect(bot.clickButton("Place order", Map.of()), "can place it");
        awaitScreen(e2e, bot, before, "Past orders");
        e2e.eventually(() -> latestOrder(e2e, name) > id && row(e2e, latestOrder(e2e, name)).equals("ACTIVE/5/0/0/100/500"),
            "a new active order: " + row(e2e, latestOrder(e2e, name)));
        e2e.eventually(() -> e2e.money(name) == 9_500, "held again");
        cancelAll(e2e, name);
        healthy(e2e);
    }

    static void collectAll(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdColBuy");
        String sellerName = e2e.name("OrdColSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        long iron = place(e2e, buyer, "iron_ingot", "10", "30");
        long gold = place(e2e, buyer, "gold_ingot", "10", "50");
        inventory(e2e, sellerName, Map.of(0, stack(Material.IRON_INGOT, 10), 1, stack(Material.GOLD_INGOT, 6)));
        quick(e2e, seller, "iron", buyerName, iron, 10, "Deliver 10");
        quick(e2e, seller, "gold", buyerName, gold, 6, "Deliver 6");

        e2e.step("Collect all takes what fits from every order in one go");
        inventory(e2e, buyerName, Map.of());
        openMenu(e2e, buyer, "orders mine", "Your orders");
        e2e.expect(loreHas(buyer, 51, "Collect 16 items from 2 orders"), "the collect all button: " + lore(buyer, 51));
        buyer.clearMessages();
        clickSlot(e2e, buyer, 51);
        e2e.eventually(() -> count(e2e, buyerName, Material.IRON_INGOT) == 10 && count(e2e, buyerName, Material.GOLD_INGOT) == 6,
            "the owner has 10 iron and 6 gold");
        e2e.eventually(() -> buyer.actionBarContains("Collected 16 items from 2 orders."), "told: " + buyer.actionBar());
        e2e.expect(row(e2e, iron).equals("FILLED/10/10/10/30/0") && row(e2e, gold).equals("ACTIVE/10/6/6/50/200"),
            "both orders counted it: " + row(e2e, iron) + " " + row(e2e, gold));
        e2e.eventually(() -> loreHas(buyer, 51, "Nothing waiting"), "nothing left to collect: " + lore(buyer, 51));

        e2e.step("a full inventory sends the rest to the claim box");
        inventory(e2e, sellerName, Map.of(0, stack(Material.GOLD_INGOT, 4)));
        quick(e2e, seller, "gold", buyerName, gold, 10, "Deliver 4");
        Map<Integer, ItemStack> full = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            full.put(i, stack(Material.DIRT, 64));
        }
        inventory(e2e, buyerName, full);
        openMenu(e2e, buyer, "orders mine", "Your orders");
        int own = slotWith(e2e, buyer, "Waiting for you 4");
        clickForDialog(e2e, buyer, own, 0, ContainerInput.PICKUP, "Your order");
        buyer.clearMessages();
        e2e.click(buyer, "Send the rest to my claim box");
        e2e.eventually(() -> e2e.services().deliveries().of(e2e.uuid(buyerName)).stream()
            .mapToInt(delivery -> delivery.item().getType() == Material.GOLD_INGOT ? delivery.item().getAmount() : 0).sum() == 4,
            "4 gold wait in the claim box");
        e2e.eventually(() -> buyer.chatContains("4 Gold Ingot went to your claim box (/claims)."), "told: " + buyer.chat());
        e2e.eventually(() -> row(e2e, gold).equals("FILLED/10/10/10/50/0"), "the order is settled: " + row(e2e, gold));
        healthy(e2e);
    }

    static void historyViews(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdHisBuy");
        String sellerName = e2e.name("OrdHisSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "redstone", "10", "20");
        inventory(e2e, sellerName, Map.of(0, stack(Material.REDSTONE, 5)));
        quick(e2e, seller, "redstone", buyerName, id, 5, "Deliver 5");
        e2e.console("orders admin cancel " + id + " history test");
        e2e.eventually(() -> row(e2e, id).startsWith("CANCELLED/"), "cancelled");

        e2e.step("Past orders: state, deliveries, what was paid out and what came back");
        openMenu(e2e, buyer, "orders history", "Past orders");
        int past = slotWith(e2e, buyer, "Delivered 5 of 10");
        List<String> lore = lore(buyer, past);
        e2e.expect(lore.contains("Cancelled") && lore.contains("Paid out $100") && lore.contains("Came back to you $100")
            && lore.contains("Waiting for you 5") && lore.stream().anyMatch(line -> line.startsWith("Ended ")), "the history entry: " + lore);

        e2e.step("Your deliveries: the item, the earnings after tax, the buyer and the total");
        openMenu(e2e, seller, "orders deliveries", "Your deliveries");
        int delivery = slotWith(e2e, seller, "To " + buyerName);
        List<String> entry = lore(seller, delivery);
        e2e.expect(entry.contains("Delivered 5") && entry.contains("Earned $98 after tax"), "the delivery: " + entry);
        e2e.expect(loreHas(seller, 50, "You earned $98 from 1 deliveries"), "the header: " + lore(seller, 50));

        e2e.step("staff read anyone's history from the console");
        List<String> output = e2e.consoleOutput("orders admin history " + buyerName);
        e2e.expect(output.stream().anyMatch(line -> line.contains("Past orders of " + buyerName)), "the header: " + output);
        e2e.expect(output.stream().anyMatch(line -> line.contains("#" + id + " Cancelled, 5/10 Redstone Dust at $20, came back $100")),
            "the line: " + output);
        List<String> info = e2e.consoleOutput("orders admin info " + id);
        e2e.expect(info.stream().anyMatch(line -> line.contains("Order #" + id) && line.contains("delivered 5 of 10, collected 0")),
            "admin info of a closed order: " + info);
        List<String> list = e2e.consoleOutput("orders admin list " + buyerName);
        e2e.expect(list.stream().anyMatch(line -> line.contains("#" + id + " " + buyerName + " 5/10 Redstone Dust at $20, Cancelled")),
            "admin list by player: " + list);
        healthy(e2e);
    }

    static void offlineNotice(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdAwayBuy");
        String sellerName = e2e.name("OrdAwaySel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        UUID buyerId = e2e.uuid(buyerName);
        long id = place(e2e, buyer, "lapis_lazuli", "10", "40");
        long other = place(e2e, buyer, "quartz", "5", "20");

        e2e.step("the owner leaves; deliveries, completion and a staff cancel are kept as notices");
        buyer.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(buyerName) == null, buyerName + " left");
        inventory(e2e, sellerName, Map.of(0, stack(Material.LAPIS_LAZULI, 4)));
        quick(e2e, seller, "lapis", buyerName, id, 4, "Deliver 4");
        inventory(e2e, sellerName, Map.of(0, stack(Material.LAPIS_LAZULI, 6)));
        quick(e2e, seller, "lapis", buyerName, id, 10, "Deliver 6");
        e2e.console("orders admin cancel " + other + " duplicate order");
        e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM order_notices WHERE owner = ?", buyerId.toString()) == 3,
            "three notice rows: delivered (summed), complete and cancelled");
        e2e.expect(number(e2e, "SELECT units FROM order_notices WHERE owner = ? AND kind = 'delivered'", buyerId.toString()) == 10,
            "the deliveries add up");

        e2e.step("joining shows one summary, then the notices are gone");
        Bot back = e2e.bot(buyerName);
        e2e.eventually(() -> back.chatContains("While you were away: 10 items were delivered to your orders, 1 completed, "
            + "$100 came back from ended orders."), "the summary: " + back.chat());
        e2e.expect(back.chatContains("Staff cancelled your order for 5 Nether Quartz, $100 came back."), "the staff cancel line: " + back.chat());
        e2e.expect(back.chatContains("Reason: duplicate order"), "with its reason: " + back.chat());
        e2e.expect(back.chatContains("Your order for 10 Lapis Lazuli is complete"), "the complete line: " + back.chat());
        e2e.expect(back.chatContains("10 delivered items wait for you in /orders."), "the waiting reminder: " + back.chat());
        e2e.expect(back.chatContains("Open /orders"), "the link: " + back.chat());
        e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM order_notices WHERE owner = ?", buyerId.toString()) == 0, "the notices were deleted");
        e2e.expect(e2e.money(buyerName) == 10_000 - 400, "the cancelled order's $100 came back: " + e2e.money(buyerName));
        healthy(e2e);
    }

    // ------------------------------------------------------------------ staff

    static void staffCancel(E2E e2e) throws Exception {
        String ownerName = e2e.name("OrdModOwn");
        String sellerName = e2e.name("OrdModSel");
        String staffName = e2e.name("OrdModStf");
        Bot owner = e2e.bot(ownerName);
        Bot seller = e2e.bot(sellerName);
        Bot staff = e2e.bot(staffName);
        fund(e2e, ownerName, 10_000);
        long id = place(e2e, owner, "slime_ball", "10", "100");
        inventory(e2e, sellerName, Map.of(0, stack(Material.SLIME_BALL, 2)));
        quick(e2e, seller, "slime", ownerName, id, 2, "Deliver 2");

        e2e.console("op " + staffName);
        try {
            e2e.step("staff shift right click any order for the staff dialog");
            int slot = browseTo(e2e, staff, "slime", ownerName);
            e2e.expect(loreHas(staff, slot, "Shift right click for staff actions"), "the staff hint: " + lore(staff, slot));
            Bot.SeenDialog dialog = clickForDialog(e2e, staff, slot, 1, ContainerInput.QUICK_MOVE, "Order #" + id);
            String body = dialog.bodyText();
            e2e.expect(body.contains("Owner " + ownerName) && body.contains("Slimeball, delivered 2 of 10") && body.contains("Money held $800"),
                "the order: " + body);
            e2e.expect(body.contains(sellerName + " 2 for $200"), "the latest deliveries: " + body);
            e2e.expect(dialog.button("Open " + ownerName + "'s orders") != null, "the owner's orders button: " + dialog.buttons());

            e2e.step("cancelling needs a reason");
            Bot.SeenDialog reason = clickToDialog(e2e, staff, "Cancel and refund", Map.of(), "Cancel order #" + id);
            Bot.SeenDialog missing = clickToDialog(e2e, staff, "Next", typed(reason, Map.of("reason", "")), "Cancel order #" + id);
            e2e.expect(missing.bodyText().contains("Give a reason."), "a reason is required: " + missing.body());
            Bot.SeenDialog confirm = clickToDialog(e2e, staff, "Next", typed(missing, Map.of("reason", "price manipulation")), "Cancel order #" + id);
            e2e.expect(confirm.bodyText().contains("Cancel order #" + id + " of " + ownerName + " and refund $800?")
                && confirm.bodyText().contains("Reason: price manipulation"), "the confirmation: " + confirm.body());
            owner.clearMessages();
            staff.clearMessages();
            e2e.click(staff, "Cancel and refund");
            e2e.eventually(() -> row(e2e, id).startsWith("CANCELLED/"), "cancelled: " + row(e2e, id));
            e2e.eventually(() -> e2e.money(ownerName) == 10_000 - 200, "the owner got $800 back: " + e2e.money(ownerName));
            e2e.eventually(() -> owner.chatContains("Staff cancelled your order for 10 Slimeball and $800 came back to you. Reason: price manipulation"),
                "the owner is told with the reason: " + owner.chat());
            e2e.eventually(() -> staff.chatContains("Cancelled order #" + id + " of " + ownerName + ". $800 went back to them."),
                "staff is told: " + staff.chat());
            String audit = text(e2e, "SELECT details FROM audit_log WHERE action = 'orders.cancel' AND target = ? ORDER BY id DESC",
                e2e.uuid(ownerName).toString());
            e2e.expect(("#" + id + " refund $800: price manipulation").equals(audit), "the audit log: " + audit);
        } finally {
            e2e.console("deop " + staffName);
        }

        e2e.step("the delivered items stay collectable");
        inventory(e2e, ownerName, Map.of());
        openMenu(e2e, owner, "orders mine", "Your orders");
        int own = slotWith(e2e, owner, "Waiting for you 2");
        clickForDialog(e2e, owner, own, 0, ContainerInput.PICKUP, "Your order");
        e2e.click(owner, "Collect items");
        e2e.eventually(() -> count(e2e, ownerName, Material.SLIME_BALL) == 2, "the owner has the slimeballs");
        healthy(e2e);
    }

    // ------------------------------------------------------------------ variants

    static void bookOrder(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdBookBuy");
        String sellerName = e2e.name("OrdBookSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 20_000);
        fund(e2e, sellerName, 0);
        inventory(e2e, buyerName, Map.of());

        e2e.step("pick enchanted book, then Mending, in the form");
        command(e2e, buyer, "orders create");
        Bot.SeenDialog form = e2e.dialog(buyer, "New order");
        Bot.Screen before = buyer.screen();
        e2e.expect(buyer.clickButton("Choose item", typed(form, Map.of("quantity", "2", "price", "2k"))), "can choose");
        awaitScreen(e2e, buyer, before, "Choose an item");
        Bot.SeenDialog search = clickForDialog(e2e, buyer, 49, 0, ContainerInput.PICKUP, "Search");
        clickToScreen(e2e, buyer, "Submit", typed(search, Map.of("query", "enchanted book")), "Choose an item");
        e2e.eventually(() -> slotOf(buyer, Material.ENCHANTED_BOOK) >= 0, "the enchanted book family is listed");
        int family = slotOf(buyer, Material.ENCHANTED_BOOK);
        e2e.expect(loreHas(buyer, family, "Choose which one next"), "a family: " + lore(buyer, family));
        Bot.SeenDialog enchantments = clickForDialog(e2e, buyer, family, 0, ContainerInput.PICKUP, "Choose an enchantment");
        e2e.expect(enchantments.button("Mending") != null && enchantments.button("Sharpness") != null, "the list: " + enchantments.buttons());
        e2e.expect(enchantments.button("Curse of Vanishing") == null, "curses are not offered by default: " + enchantments.buttons());
        Bot.SeenDialog chosen = clickToDialog(e2e, buyer, "Mending", Map.of(), "New order");
        e2e.expect("Mending book".equals(chosen.initial("item")), "the form shows the book: " + chosen.initial());
        e2e.expect("2".equals(chosen.initial("quantity")) && "2k".equals(chosen.initial("price")), "typed values kept: " + chosen.initial());
        Bot.SeenDialog confirm = clickToDialog(e2e, buyer, "Next", typed(chosen, Map.of()), "Place order");
        e2e.expect(confirm.bodyText().contains("2 Mending book at $2,000 each"), "the book order: " + confirm.body());
        buyer.clearMessages();
        e2e.click(buyer, "Place order");
        e2e.eventually(() -> latestOrder(e2e, buyerName) > 0, "the order is stored");
        long id = latestOrder(e2e, buyerName);
        e2e.eventually(() -> "enchant:minecraft:mending:1".equals(text(e2e, "SELECT variant FROM orders WHERE id = ?", id)),
            "the variant is stored");

        e2e.step("a level is part of the order: /orders create sharpness_4_book");
        command(e2e, buyer, "orders create sharpness_4_book 1 500");
        Bot.SeenDialog sharp = e2e.dialog(buyer, "Place order");
        e2e.expect(sharp.bodyText().contains("1 Sharpness IV book at $500 each"), "the level: " + sharp.body());
        e2e.click(buyer, "Back");

        e2e.step("only the exact book is delivered: not one with a repair cost or an extra enchantment");
        ItemStack exact = book(Map.of(Enchantment.MENDING, 1), 0);
        ItemStack anvil = book(Map.of(Enchantment.MENDING, 1), 1);
        ItemStack extra = book(Map.of(Enchantment.MENDING, 1, Enchantment.UNBREAKING, 3), 0);
        inventory(e2e, sellerName, Map.of(0, anvil, 1, extra, 2, exact));
        int slot = browseTo(e2e, seller, "mending", buyerName);
        Bot.SeenDialog quick = clickForDialog(e2e, seller, slot, 1, ContainerInput.PICKUP, "Deliver Mending book");
        e2e.expect(quick.bodyText().contains("You carry 1 plain Mending book (0 in shulker boxes)"), "one exact book: " + quick.body());
        Bot.Screen browser = seller.screen();
        e2e.click(seller, "Deliver 1 for $1,960");
        e2e.eventually(() -> e2e.money(sellerName) == 1_960, "paid $2,000 less 2%: " + e2e.money(sellerName));
        awaitScreen(e2e, seller, browser, BROWSER);
        e2e.expect(slot(e2e, sellerName, 2) == null, "the exact book went");
        e2e.expect(anvil.equals(slot(e2e, sellerName, 0)) && extra.equals(slot(e2e, sellerName, 1)), "the other books stay untouched");

        e2e.step("the delivery menu names the rule and refuses the anvil book");
        slot = browseTo(e2e, seller, "mending", buyerName);
        before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Mending book");
        e2e.expect(loreHas(seller, 48, "no other enchantments, never used in an anvil."), "the book rule: " + lore(seller, 48));
        clickSlot(e2e, seller, BELOW_HOTBAR, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> loreHas(seller, 50, "Not accepted 1, given back when you close"), "refused: " + lore(seller, 50));
        seller.clearMessages();
        clickSlot(e2e, seller, 50);
        e2e.eventually(() -> seller.actionBarContains("Put Mending book in the empty slots first."), "nothing to deliver: " + seller.actionBar());
        seller.closeScreen();
        e2e.eventually(() -> anvil.equals(slot(e2e, sellerName, 0)), "the anvil book came back as it was");

        e2e.step("the buyer collects a plain Mending book");
        openMenu(e2e, buyer, "orders mine", "Your orders");
        int own = slotWith(e2e, buyer, "Waiting for you 1");
        clickForDialog(e2e, buyer, own, 0, ContainerInput.PICKUP, "Your order");
        e2e.click(buyer, "Collect items");
        e2e.eventually(() -> exact.isSimilar(slot(e2e, buyerName, 0)), "the buyer has a Mending book: " + slot(e2e, buyerName, 0));
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    // ------------------------------------------------------------------ across a restart

    /** Leaves an active, partly delivered nautilus shell order at $77 behind for {@link #persistCheck} (after a restart). */
    static void persistSetup(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdKeepBuy");
        String sellerName = e2e.name("OrdKeepSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 1_000);
        long id = place(e2e, buyer, "nautilus_shell", "10", "77");
        inventory(e2e, sellerName, Map.of(0, stack(Material.NAUTILUS_SHELL, 3)));
        quick(e2e, seller, "nautilus", buyerName, id, 3, "Deliver 3");
        e2e.expect(row(e2e, id).equals("ACTIVE/10/3/0/77/539"), "3 delivered, $539 held: " + row(e2e, id));
        e2e.log("left order #" + id + " of " + buyerName);
        healthy(e2e);
    }

    /** Finds what {@link #persistSetup} left (possibly before a restart), fills it, and the owner collects everything. */
    static void persistCheck(E2E e2e) throws Exception {
        long id = number(e2e, "SELECT COALESCE(MAX(id), 0) FROM orders WHERE state = 'ACTIVE' AND item_type = 'minecraft:nautilus_shell' "
            + "AND price_each = 77 AND filled = 3");
        e2e.expect(id > 0, "an active nautilus shell order with 3 delivered exists (run orders-persist-setup first)");
        String ownerId = text(e2e, "SELECT owner FROM orders WHERE id = ?", id);
        String ownerName = text(e2e, "SELECT name FROM players WHERE uuid = ?", ownerId);
        e2e.log("checking order #" + id + " of " + ownerName);

        e2e.step("the order is in memory exactly as stored");
        List<String> info = e2e.consoleOutput("orders admin info " + id);
        e2e.expect(info.stream().anyMatch(line -> line.contains("delivered 3 of 10, collected 0"))
            && info.stream().anyMatch(line -> line.contains("money held $539")), "the loaded order: " + info);

        e2e.step("it still takes deliveries while its owner is away");
        String sellerName = e2e.name("OrdHeirSel");
        Bot seller = e2e.bot(sellerName);
        inventory(e2e, sellerName, Map.of(0, stack(Material.NAUTILUS_SHELL, 7)));
        quick(e2e, seller, "nautilus", ownerName, id, 10, "Deliver 7");
        e2e.eventually(() -> row(e2e, id).equals("FILLED/10/10/0/77/0"), "complete: " + row(e2e, id));

        e2e.step("the owner comes back to the summary and collects all ten");
        Bot owner = e2e.bot(ownerName);
        e2e.eventually(() -> owner.chatContains("While you were away: 7 items were delivered to your orders, 1 completed."),
            "the summary: " + owner.chat());
        inventory(e2e, ownerName, Map.of());
        openMenu(e2e, owner, "orders mine", "Your orders");
        int own = slotWith(e2e, owner, "Waiting for you 10");
        clickForDialog(e2e, owner, own, 0, ContainerInput.PICKUP, "Your order");
        e2e.click(owner, "Collect items");
        e2e.eventually(() -> count(e2e, ownerName, Material.NAUTILUS_SHELL) == 10, "the owner has ten nautilus shells");
        e2e.eventually(() -> row(e2e, id).equals("FILLED/10/10/10/77/0"), "collected: " + row(e2e, id));
        healthy(e2e);
    }

    // ------------------------------------------------------------------ browsing and combat

    static void browse(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdBrBuy");
        String viewerName = e2e.name("OrdBrView");
        Bot buyer = e2e.bot(buyerName);
        Bot viewer = e2e.bot(viewerName);
        fund(e2e, buyerName, 50_000);
        place(e2e, buyer, "glowstone_dust", "100", "10");
        place(e2e, buyer, "glowstone", "10", "90");

        e2e.step("the sort lists five options and remembers the choice");
        openMenu(e2e, viewer, "orders glowstone", BROWSER);
        List<String> sort = lore(viewer, 47);
        e2e.expect(sort.containsAll(List.of("• Highest price each", "• Highest total", "• Most wanted", "• Newest", "• Ending soon")),
            "the sort options: " + sort);
        e2e.expect("Highest price each".equals(selectedOption(viewer, 47)), "sorted by price each by default: " + selectedOption(viewer, 47));
        List<Integer> byPrice = slotsWith(viewer, "Ordered by " + buyerName);
        e2e.expect(byPrice.size() == 2 && CraftItemStack.asBukkitCopy(viewer.screenItems().get(byPrice.getFirst())).getType() == Material.GLOWSTONE,
            "the $90 order first by price each");
        clickSlot(e2e, viewer, 47);
        e2e.eventually(() -> {
            List<Integer> slots = slotsWith(viewer, "Ordered by " + buyerName);
            return slots.size() == 2 && CraftItemStack.asBukkitCopy(viewer.screenItems().get(slots.getFirst())).getType() == Material.GLOWSTONE_DUST;
        }, "the $1,000 order first by total (" + selectedOption(viewer, 47) + ")");
        viewer.closeScreen();
        e2e.eventually(() -> "total".equals(e2e.services().settings().raw(e2e.uuid(viewerName), "orders_sort", "")), "the sort is remembered");
        openMenu(e2e, viewer, "orders glowstone", BROWSER);
        List<Integer> reopened = slotsWith(viewer, "Ordered by " + buyerName);
        e2e.expect(reopened.size() == 2 && CraftItemStack.asBukkitCopy(viewer.screenItems().get(reopened.getFirst())).getType()
            == Material.GLOWSTONE_DUST && "Highest total".equals(selectedOption(viewer, 47)), "still sorted by total after reopening: "
            + selectedOption(viewer, 47));

        e2e.step("the filter lists the categories with orderable items, and the search shows its query");
        List<String> filter = lore(viewer, 48);
        e2e.expect(filter.containsAll(List.of("• All", "• Blocks", "• Tools", "• Combat", "• Food", "• Potions", "• Books", "• Other")),
            "the categories: " + filter);
        boolean spawners = !e2e.feature(net.siftvanilla.siftcore.feature.spawners.SpawnersFeature.class).items().mobs().isEmpty();
        e2e.expect(filter.stream().anyMatch(line -> line.contains("Spawners")) == spawners,
            "a spawners filter exactly while the spawner feature provides spawners (" + spawners + "): " + filter);
        e2e.expect(loreHas(viewer, 49, "Showing results for glowstone"), "the search: " + lore(viewer, 49));
        e2e.expect("All".equals(selectedOption(viewer, 48)), "everything shown by default: " + selectedOption(viewer, 48));
        clickSlot(e2e, viewer, 48);
        e2e.eventually(() -> "Blocks".equals(selectedOption(viewer, 48)) && slotsWith(viewer, "Ordered by " + buyerName).size() == 1,
            "the blocks filter keeps glowstone (the block) only: " + selectedOption(viewer, 48) + " "
            + slotsWith(viewer, "Ordered by " + buyerName));

        e2e.step("the extras: new order, your orders, history");
        e2e.expect(viewer.screenItems().get(50) != null && viewer.screenItems().get(51) != null && viewer.screenItems().get(52) != null,
            "slots 50 to 52 are used");
        clickSlot(e2e, viewer, 48, 1, ContainerInput.PICKUP);
        e2e.eventually(() -> "All".equals(selectedOption(viewer, 48)) && slotsWith(viewer, "Ordered by " + buyerName).size() == 2,
            "a right click goes back to all: " + selectedOption(viewer, 48) + " " + slotsWith(viewer, "Ordered by " + buyerName));

        e2e.step("an order for an unstackable item shows one item, whatever it wants");
        place(e2e, buyer, "diamond_sword", "5", "900");
        openMenu(e2e, viewer, "orders diamond sword", BROWSER);
        int sword = slotWith(e2e, viewer, "Ordered by " + buyerName);
        e2e.expect(viewer.screenItems().get(sword).getCount() == 1 && loreHas(viewer, sword, "Delivered 0 of 5"),
            "one sword shown for an order of five: " + lore(viewer, sword));
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    static void combatBlocked(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdCbBuy");
        String fighterName = e2e.name("OrdCbFight");
        Bot buyer = e2e.bot(buyerName);
        Bot fighter = e2e.bot(fighterName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, fighterName, 10_000);
        long id = place(e2e, buyer, "bone", "10", "10");
        inventory(e2e, fighterName, Map.of(0, stack(Material.BONE, 10)));

        e2e.step("menus opened before the fight can't deliver once tagged");
        int slot = browseTo(e2e, fighter, "bone", buyerName);
        Bot.Screen before = fighter.screen();
        clickSlot(e2e, fighter, slot);
        awaitScreen(e2e, fighter, before, "Deliver Bone");
        clickSlot(e2e, fighter, 51);
        e2e.eventually(() -> gridCount(e2e, fighterName, Material.BONE) == 10, "the bones are in the grid");
        e2e.console("combat tag " + fighterName + " 60s");
        try {
            fighter.clearMessages();
            clickSlot(e2e, fighter, 50);
            e2e.eventually(() -> fighter.actionBarContains("You can't use orders in combat."), "deliver refused: " + fighter.actionBar());
            e2e.expect(row(e2e, id).equals("ACTIVE/10/0/0/10/100"), "nothing delivered");
            fighter.closeScreen();
            e2e.eventually(() -> count(e2e, fighterName, Material.BONE) == 10, "the bones came back");

            e2e.step("the browser, quick deliver and new orders are refused while tagged");
            fighter.clearMessages();
            command(e2e, fighter, "orders");
            e2e.eventually(() -> fighter.actionBarContains("You can't use orders in combat."), "browser refused: " + fighter.actionBar());
            fighter.clearMessages();
            command(e2e, fighter, "orders create bone 10 10");
            e2e.eventually(() -> fighter.actionBarContains("You can't use orders in combat."), "create refused: " + fighter.actionBar());
            e2e.expect(latestOrder(e2e, fighterName) == 0, "no order placed");
            fighter.clearMessages();
            command(e2e, fighter, "orders create");
            e2e.eventually(() -> fighter.actionBarContains("You can't use orders in combat."), "the form refused: " + fighter.actionBar());
        } finally {
            e2e.console("combat untag " + fighterName);
        }

        e2e.step("quick deliver from a browser opened before the tag is refused too");
        slot = browseTo(e2e, fighter, "bone", buyerName);
        e2e.console("combat tag " + fighterName + " 60s");
        try {
            fighter.clearMessages();
            clickSlot(e2e, fighter, slot, 1, ContainerInput.PICKUP);
            e2e.eventually(() -> fighter.actionBarContains("You can't use orders in combat."), "quick refused: " + fighter.actionBar());
            e2e.expect(count(e2e, fighterName, Material.BONE) == 10 && row(e2e, id).equals("ACTIVE/10/0/0/10/100"), "nothing moved");
        } finally {
            e2e.console("combat untag " + fighterName);
        }
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    // ------------------------------------------------------------------ spawner orders

    /**
     * Spawner orders through the spawner feature's items: picked from the list of mobs, delivered only with SiftCore
     * spawners of that mob (matched by the spawner feature's own item identity, never by name or a vanilla spawner),
     * and collected as SiftCore spawners.
     */
    static void spawnerOrder(E2E e2e) throws Exception {
        net.siftvanilla.siftcore.core.link.SpawnerItems spawners =
            e2e.feature(net.siftvanilla.siftcore.feature.spawners.SpawnersFeature.class).items();
        if (!spawners.mobs().contains("skeleton") || !spawners.mobs().contains("zombie")) {
            e2e.log("the spawner feature provides no skeleton and zombie spawners; skipped");
            return;
        }
        String buyerName = e2e.name("OrdSpBuy");
        String sellerName = e2e.name("OrdSpSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 2_000_000);
        fund(e2e, sellerName, 0);
        inventory(e2e, buyerName, Map.of());

        e2e.step("pick spawner, then Skeleton, in the form");
        command(e2e, buyer, "orders create");
        Bot.SeenDialog form = e2e.dialog(buyer, "New order");
        Bot.Screen before = buyer.screen();
        e2e.expect(buyer.clickButton("Choose item", typed(form, Map.of("quantity", "2", "price", "100k"))), "can choose");
        awaitScreen(e2e, buyer, before, "Choose an item");
        Bot.SeenDialog search = clickForDialog(e2e, buyer, 49, 0, ContainerInput.PICKUP, "Search");
        clickToScreen(e2e, buyer, "Submit", typed(search, Map.of("query", "spawner")), "Choose an item");
        e2e.eventually(() -> slotOf(buyer, Material.SPAWNER) >= 0, "the spawner family is listed");
        int family = slotOf(buyer, Material.SPAWNER);
        e2e.expect(loreHas(buyer, family, "Choose which one next"), "a family: " + lore(buyer, family));
        Bot.SeenDialog mobs = clickForDialog(e2e, buyer, family, 0, ContainerInput.PICKUP, "Choose a spawner");
        e2e.expect(mobs.button("Skeleton") != null && mobs.button("Zombie") != null, "the mobs: " + mobs.buttons());
        Bot.SeenDialog chosen = clickToDialog(e2e, buyer, "Skeleton", Map.of(), "New order");
        Bot.SeenDialog confirm = clickToDialog(e2e, buyer, "Next", typed(chosen, Map.of()), "Place order");
        e2e.expect(confirm.bodyText().contains("2 Skeleton spawner at $100,000 each"), "the spawner order: " + confirm.body());
        buyer.clearMessages();
        e2e.click(buyer, "Place order");
        e2e.eventually(() -> latestOrder(e2e, buyerName) > 0, "the order is stored");
        long id = latestOrder(e2e, buyerName);
        e2e.eventually(() -> "spawner:skeleton".equals(text(e2e, "SELECT variant FROM orders WHERE id = ?", id)), "the variant is stored");

        e2e.step("only SiftCore skeleton spawners count: not a vanilla spawner, not another mob's");
        ItemStack skeletons = spawners.create("skeleton", 3).orElseThrow();
        ItemStack zombie = spawners.create("zombie", 1).orElseThrow();
        ItemStack vanilla = ItemStack.of(Material.SPAWNER);
        ItemStack renamed = vanilla.clone();
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Skeleton spawner"));
        inventory(e2e, sellerName, Map.of(0, vanilla, 1, zombie, 2, renamed, 3, skeletons));
        int slot = browseTo(e2e, seller, "skeleton spawner", buyerName);
        Bot.SeenDialog quick = clickForDialog(e2e, seller, slot, 1, ContainerInput.PICKUP, "Deliver Skeleton spawner");
        e2e.expect(quick.bodyText().contains("You carry 3 plain Skeleton spawner (0 in shulker boxes)"), "three real ones: " + quick.body());
        Bot.Screen browser = seller.screen();
        e2e.click(seller, "Deliver 2 for $196,000");
        e2e.eventually(() -> e2e.money(sellerName) == 196_000, "paid 2 x $100,000 less 2%: " + e2e.money(sellerName));
        awaitScreen(e2e, seller, browser, BROWSER);
        e2e.eventually(() -> row(e2e, id).equals("FILLED/2/2/0/100000/0"), "complete: " + row(e2e, id));
        e2e.expect(vanilla.equals(slot(e2e, sellerName, 0)) && zombie.equals(slot(e2e, sellerName, 1))
            && renamed.equals(slot(e2e, sellerName, 2)), "the vanilla, zombie and renamed spawners stay untouched");
        ItemStack left = slot(e2e, sellerName, 3);
        e2e.expect(left != null && left.isSimilar(skeletons) && left.getAmount() == 1, "one skeleton spawner stays: " + left);

        e2e.step("the buyer collects SiftCore skeleton spawners");
        openMenu(e2e, buyer, "orders mine", "Your orders");
        int own = slotWith(e2e, buyer, "Waiting for you 2");
        clickForDialog(e2e, buyer, own, 0, ContainerInput.PICKUP, "Your order");
        e2e.click(buyer, "Collect items");
        e2e.eventually(() -> {
            ItemStack got = slot(e2e, buyerName, 0);
            return got != null && got.isSimilar(skeletons) && got.getAmount() == 2;
        }, "the buyer has two skeleton spawners: " + slot(e2e, buyerName, 0));
        e2e.eventually(() -> row(e2e, id).equals("FILLED/2/2/2/100000/0"), "collected: " + row(e2e, id));
        healthy(e2e);
    }

    // ------------------------------------------------------------------ dying with a delivery menu open

    /**
     * Dying with items in a delivery grid drops them with the rest of the death drops: nothing is lost into an inventory
     * that is about to be cleared, nothing is delivered and nothing goes to the claim box.
     */
    static void deliveryDeath(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdDieBuy");
        String sellerName = e2e.name("OrdDieSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "prismarine_crystals", "50", "20");
        inventory(e2e, sellerName, Map.of(0, stack(Material.PRISMARINE_CRYSTALS, 30)));
        int slot = browseTo(e2e, seller, "prismarine crystals", buyerName);
        Bot.Screen before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Prismarine Crystals");
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 30, "30 crystals in the grid");

        e2e.step("die with the delivery menu open: the grid drops like the inventory");
        int claimBefore = e2e.services().deliveries().count(e2e.uuid(sellerName));
        org.bukkit.Location at = e2e.onPlayer(sellerName, () -> e2e.player(sellerName).getLocation());
        int lyingBefore = onTheGround(e2e, at, Material.PRISMARINE_CRYSTALS);
        e2e.onPlayer(sellerName, () -> {
            e2e.player(sellerName).setHealth(0);
            return null;
        });
        e2e.eventually(() -> seller.deaths() > 0, "the seller died");
        e2e.sleep(1500);
        int total = onTheGround(e2e, at, Material.PRISMARINE_CRYSTALS) + count(e2e, sellerName, Material.PRISMARINE_CRYSTALS);
        e2e.expect(total - lyingBefore == 30, "exactly 30 crystals lie on the ground or are back in the inventory, found "
            + (total - lyingBefore));
        e2e.expect(e2e.services().deliveries().count(e2e.uuid(sellerName)) == claimBefore, "death did not move the grid to the claim box");
        e2e.expect(row(e2e, id).equals("ACTIVE/50/0/0/20/1000") && e2e.money(sellerName) == 0, "nothing was delivered: " + row(e2e, id));
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    /**
     * An owner who collects and leaves before the collect is stored gets the items back into the order once it is: they
     * used to stay counted as collected (and invisible) until the next server stop.
     */
    static void collectLeftBeforeCommit(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdLeftBuy");
        String sellerName = e2e.name("OrdLeftSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "iron_ingot", "10", "30");
        inventory(e2e, sellerName, Map.of(0, stack(Material.IRON_INGOT, 10)));
        quick(e2e, seller, "iron", buyerName, id, 10, "Deliver 10");
        e2e.expect(row(e2e, id).equals("FILLED/10/10/0/30/0"), "delivered, nothing collected: " + row(e2e, id));

        e2e.step("collect while storage is slow, and leave before the collect is stored");
        inventory(e2e, buyerName, Map.of());
        openMenu(e2e, buyer, "orders mine", "Your orders");
        int own = slotWith(e2e, buyer, "Waiting for you 10");
        clickForDialog(e2e, buyer, own, 0, ContainerInput.PICKUP, "Your order");
        java.util.concurrent.CompletableFuture<Object> stall = e2e.stallStorage(3_000);
        e2e.click(buyer, "Collect items");
        e2e.kick(buyer, "e2e: leaving before the collect is stored");
        e2e.expect(!stall.isDone(), "the collect was still waiting for storage when the owner left");
        stall.get(10, TimeUnit.SECONDS);

        e2e.step("the items go back into the order right away");
        e2e.eventually(() -> row(e2e, id).equals("FILLED/10/10/0/30/0"), "the order counts them as waiting again: " + row(e2e, id));
        Bot back = e2e.bot(buyerName);
        e2e.expect(count(e2e, buyerName, Material.IRON_INGOT) == 0, "nothing was handed out");
        e2e.step("and can be collected normally");
        inventory(e2e, buyerName, Map.of());
        openMenu(e2e, back, "orders mine", "Your orders");
        int again = slotWith(e2e, back, "Waiting for you 10");
        clickForDialog(e2e, back, again, 0, ContainerInput.PICKUP, "Your order");
        e2e.click(back, "Collect items");
        e2e.eventually(() -> count(e2e, buyerName, Material.IRON_INGOT) == 10, "the owner has the iron");
        e2e.eventually(() -> row(e2e, id).equals("FILLED/10/10/10/30/0"), "collected in storage: " + row(e2e, id));
        healthy(e2e);
    }

    /**
     * While items sit in a delivery grid, the player's own data holds a copy of them, saved together with the inventory:
     * a copy left by a crash comes back on the next join, and closing or delivering clears it.
     */
    static void deliveryGridCopy(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdCopyBuy");
        String sellerName = e2e.name("OrdCopySel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "prismarine_crystals", "40", "20");
        inventory(e2e, sellerName, Map.of(0, stack(Material.PRISMARINE_CRYSTALS, 25)));
        int slot = browseTo(e2e, seller, "prismarine crystals", buyerName);
        Bot.Screen before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Prismarine Crystals");
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25, "25 crystals in the grid");

        e2e.step("the player's data holds a copy of the grid while the menu is open");
        e2e.eventually(() -> e2e.gridCopyCount(sellerName, "delivery_grid", Material.PRISMARINE_CRYSTALS) == 25, "the copy holds the 25 crystals");
        e2e.step("closing gives the crystals back and clears the copy");
        seller.closeScreen();
        e2e.eventually(() -> count(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25, "the crystals are back");
        e2e.eventually(() -> e2e.gridCopy(sellerName, "delivery_grid").isEmpty(), "no copy after closing");

        e2e.step("delivering clears the copy too");
        slot = browseTo(e2e, seller, "prismarine crystals", buyerName);
        before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, "Deliver Prismarine Crystals");
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25, "25 crystals in the grid again");
        clickSlot(e2e, seller, 50);
        e2e.eventually(() -> row(e2e, id).startsWith("ACTIVE/40/25/"), "25 delivered: " + row(e2e, id));
        e2e.eventually(() -> e2e.gridCopy(sellerName, "delivery_grid").isEmpty(), "no copy after delivering");

        e2e.step("a copy left in the saved data by a crash comes back on the next join");
        UUID sellerId = e2e.uuid(sellerName);
        e2e.setGridCopy(sellerName, "delivery_grid", List.of(stack(Material.PRISMARINE_CRYSTALS, 7)));
        int crystals = count(e2e, sellerName, Material.PRISMARINE_CRYSTALS);
        e2e.savePlayer(sellerName);
        e2e.crashTo(seller, sellerId, e2e.savedPlayerFile(sellerId));
        Bot back = e2e.bot(sellerName);
        e2e.eventually(() -> count(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == crystals + 7, "the 7 crystals are back in the inventory");
        e2e.eventually(() -> back.chatContains("The items you left in a delivery menu when the server stopped are back in your inventory."),
            "told: " + back.chat());
        e2e.expect(e2e.gridCopy(sellerName, "delivery_grid").isEmpty(), "the copy is gone, so nothing comes back twice");
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    /** Opens the delivery menu of the owner's order for the item searched for. */
    private static void openDelivery(E2E e2e, Bot seller, String search, String owner, String title) {
        int slot = browseTo(e2e, seller, search, owner);
        Bot.Screen before = seller.screen();
        clickSlot(e2e, seller, slot);
        awaitScreen(e2e, seller, before, title);
    }

    /**
     * What a crash leaves on disk: the player file saved while items sit in a delivery grid gives them back once, and
     * the file saved when the grid gives them back (closing, or delivering with items the order does not take) holds
     * them once too. Before, closing saved the player with the items back in the inventory and still in the grid's copy,
     * so a crash before the next save handed them out twice.
     */
    static void deliveryGridCrash(E2E e2e) throws Exception {
        String buyerName = e2e.name("OrdCrashBuy");
        String sellerName = e2e.name("OrdCrashSel");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        UUID sellerId = e2e.uuid(sellerName);
        fund(e2e, buyerName, 10_000);
        fund(e2e, sellerName, 0);
        long id = place(e2e, buyer, "prismarine_crystals", "40", "20");
        String title = "Deliver Prismarine Crystals";

        e2e.step("a crash while crystals sit in the grid (after a save) gives them back once");
        inventory(e2e, sellerName, Map.of(0, stack(Material.PRISMARINE_CRYSTALS, 25)));
        openDelivery(e2e, seller, "prismarine crystals", buyerName, title);
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25, "25 crystals in the grid");
        e2e.eventually(() -> e2e.gridCopyCount(sellerName, "delivery_grid", Material.PRISMARINE_CRYSTALS) == 25, "the copy holds them");
        e2e.savePlayer(sellerName);
        e2e.crashTo(seller, sellerId, e2e.savedPlayerFile(sellerId));
        seller = e2e.bot(sellerName);
        e2e.sleep(500);
        e2e.expect(count(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25,
            "the 25 crystals came back once: " + count(e2e, sellerName, Material.PRISMARINE_CRYSTALS));

        e2e.step("a crash right after closing the menu keeps the crystals once");
        openDelivery(e2e, seller, "prismarine crystals", buyerName, title);
        clickSlot(e2e, seller, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25, "25 crystals in the grid again");
        e2e.eventually(() -> e2e.gridCopyCount(sellerName, "delivery_grid", Material.PRISMARINE_CRYSTALS) == 25, "the copy holds them");
        seller.closeScreen();
        e2e.eventually(() -> count(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25, "the crystals are back");
        e2e.crashTo(seller, sellerId, e2e.savedPlayerFile(sellerId));
        Bot back = e2e.bot(sellerName);
        e2e.sleep(500);
        e2e.expect(count(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 25,
            "still 25 crystals, not the grid's copy on top: " + count(e2e, sellerName, Material.PRISMARINE_CRYSTALS));
        e2e.expect(!back.chatContains("The items you left in a delivery menu"), "nothing was given back a second time: " + back.chat());

        e2e.step("a crash right after delivering keeps what the order did not take once");
        inventory(e2e, sellerName, Map.of(0, stack(Material.COBBLESTONE, 7), 1, stack(Material.PRISMARINE_CRYSTALS, 10)));
        openDelivery(e2e, back, "prismarine crystals", buyerName, title);
        clickSlot(e2e, back, BELOW_HOTBAR, 0, ContainerInput.QUICK_MOVE);
        clickSlot(e2e, back, 51);
        e2e.eventually(() -> gridCount(e2e, sellerName, Material.COBBLESTONE) == 7
            && gridCount(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 10, "7 cobblestone and 10 crystals in the grid");
        clickSlot(e2e, back, 50);
        e2e.eventually(() -> row(e2e, id).startsWith("ACTIVE/40/10/"), "10 delivered: " + row(e2e, id));
        e2e.eventually(() -> count(e2e, sellerName, Material.COBBLESTONE) == 7, "the cobblestone came back");
        e2e.crashTo(back, sellerId, e2e.savedPlayerFile(sellerId));
        e2e.bot(sellerName);
        e2e.sleep(500);
        e2e.expect(count(e2e, sellerName, Material.COBBLESTONE) == 7,
            "still 7 cobblestone: " + count(e2e, sellerName, Material.COBBLESTONE));
        e2e.expect(count(e2e, sellerName, Material.PRISMARINE_CRYSTALS) == 0, "the delivered crystals stay delivered");
        cancelAll(e2e, buyerName);
        healthy(e2e);
    }

    /** Items of a material lying within 16 blocks of {@code center}, counted on the region thread that owns it. */
    private static int onTheGround(E2E e2e, org.bukkit.Location center, Material material) {
        java.util.concurrent.CompletableFuture<Integer> result = new java.util.concurrent.CompletableFuture<>();
        Bukkit.getRegionScheduler().run(e2e.services().plugin(), center, task -> {
            int found = 0;
            for (org.bukkit.entity.Entity entity : center.getWorld().getNearbyEntities(center, 16, 16, 16)) {
                if (entity instanceof org.bukkit.entity.Item item && item.getItemStack().getType() == material) {
                    found += item.getItemStack().getAmount();
                }
            }
            result.complete(found);
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("could not count the items on the ground: " + e);
        }
    }

    // ------------------------------------------------------------------ selling routed to orders

    /**
     * Selling routes items to buy orders that pay more than the server (the sell feature's order routing through the
     * orders feature's OrderMarket, one transaction per sale): an order above what the server pays takes the units
     * first, an order at or below it takes nothing, and when the orders a sale was about to fill are filled by someone
     * else first, the sale retries and ends with the server only, telling the seller. Skipped on builds whose selling
     * does not route to orders.
     */
    static void sellRouting(E2E e2e) throws Exception {
        try {
            Class.forName("net.siftvanilla.siftcore.feature.sell.OrderRouting", false,
                net.siftvanilla.siftcore.feature.sell.SellFeature.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            e2e.log("selling does not route to buy orders in this build; skipped");
            return;
        }
        String buyerName = e2e.name("OrdRtBuy");
        String sellerName = e2e.name("OrdRtSel");
        String rivalName = e2e.name("OrdRtRiv");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        e2e.bot(rivalName);
        fund(e2e, buyerName, 100_000);
        fund(e2e, sellerName, 0);
        fund(e2e, rivalName, 0);
        // Iron orders left behind by an earlier, interrupted run would take part of these sales.
        List<Long> leftover = query(e2e, "SELECT id FROM orders WHERE state = 'ACTIVE' AND item_type = 'minecraft:iron_ingot'", rs -> {
            List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            return ids;
        });
        for (long old : leftover) {
            e2e.console("orders admin cancel " + old + " end of an earlier test");
        }
        e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM orders WHERE state = 'ACTIVE' AND item_type = 'minecraft:iron_ingot'") == 0,
            "no other iron orders are open");
        net.siftvanilla.siftcore.core.link.WorthLookup worth = e2e.feature(net.siftvanilla.siftcore.feature.sell.SellFeature.class).worth();
        long ironWorth = worth.unitPrice(ItemStack.of(Material.IRON_INGOT));
        e2e.expect(ironWorth > 0 && ironWorth < 50, "an iron ingot sells for a little (" + ironWorth + ")");

        e2e.step("an order paying more than the server takes the units first, and its owner is told once");
        long high = place(e2e, buyer, "iron_ingot", "20", "100");
        inventory(e2e, sellerName, Map.of(0, stack(Material.IRON_INGOT, 12)));
        buyer.clearMessages();
        command(e2e, seller, "sell hand");
        e2e.eventually(() -> row(e2e, high).equals("ACTIVE/20/12/0/100/800"), "the order took all 12: " + row(e2e, high));
        e2e.eventually(() -> e2e.money(sellerName) == 12 * 100 - 24, "the seller got $1,176 after tax ($" + e2e.money(sellerName) + ")");
        e2e.expect(count(e2e, sellerName, Material.IRON_INGOT) == 0, "the iron left the seller");
        e2e.expect("sell".equals(text(e2e, "SELECT source FROM order_fills WHERE order_id = ?", high)), "stored as a sale");
        e2e.eventually(() -> buyer.chatContains(sellerName + " sold 12 Iron Ingot to your order."), "the owner is told: " + buyer.chat());
        long before = e2e.money(sellerName);
        long serverFor24 = e2e.onPlayer(sellerName, () -> worth.priceFor(e2e.player(sellerName), ItemStack.of(Material.IRON_INGOT, 24)));
        inventory(e2e, sellerName, Map.of(0, stack(Material.IRON_INGOT, 32)));
        command(e2e, seller, "sell hand");
        e2e.eventually(() -> row(e2e, high).equals("FILLED/20/20/0/100/0"), "the last 8 completed it: " + row(e2e, high));
        // The server part is at least the rank price of 24 ingots (selling mastery may add to it).
        e2e.eventually(() -> e2e.money(sellerName) - before >= 8 * 100 - 16 + serverFor24
            && e2e.money(sellerName) - before < 8 * 100 - 16 + 2 * serverFor24,
            "8 to the order and 24 to the server ($" + (e2e.money(sellerName) - before) + ", the server part at least $" + serverFor24 + ")");
        e2e.expect(number(e2e, "SELECT COALESCE(SUM(paid - tax), 0) FROM order_fills WHERE order_id = ? AND seller = ?", high,
            e2e.uuid(sellerName).toString()) == 12 * 98 + 8 * 98, "the order paid $1,960 after tax in two sales");
        e2e.expect(count(e2e, sellerName, Material.IRON_INGOT) == 0, "everything sold");
        healthy(e2e);

        e2e.step("an order paying the server's price or less takes nothing");
        long low = place(e2e, buyer, "iron_ingot", "10", Long.toString(ironWorth));
        long beforeLow = e2e.money(sellerName);
        long serverFor10 = e2e.onPlayer(sellerName, () -> worth.priceFor(e2e.player(sellerName), ItemStack.of(Material.IRON_INGOT, 10)));
        inventory(e2e, sellerName, Map.of(0, stack(Material.IRON_INGOT, 10)));
        command(e2e, seller, "sell hand");
        e2e.eventually(() -> e2e.money(sellerName) - beforeLow >= serverFor10 && e2e.money(sellerName) - beforeLow < 2 * serverFor10,
            "the server bought all 10 ($" + (e2e.money(sellerName) - beforeLow) + ")");
        e2e.expect(count(e2e, sellerName, Material.IRON_INGOT) == 0, "the iron was sold");
        e2e.expect(row(e2e, low).startsWith("ACTIVE/10/0/"), "the order took nothing: " + row(e2e, low));
        cancelAll(e2e, buyerName);
        healthy(e2e);

        e2e.step("orders filled by someone else between planning and the sale: the sale ends with the server and says so");
        long first = place(e2e, buyer, "iron_ingot", "10", "100");
        long second = place(e2e, buyer, "iron_ingot", "10", "90");
        Racer racer = new Racer(e2e, e2e.uuid(sellerName), e2e.uuid(rivalName));
        org.bukkit.plugin.Plugin harness = Bukkit.getPluginManager().getPlugin("SiftE2E");
        Bukkit.getPluginManager().registerEvents(racer, harness);
        try {
            long beforeRace = e2e.money(sellerName);
            long serverRace = e2e.onPlayer(sellerName, () -> worth.priceFor(e2e.player(sellerName), ItemStack.of(Material.IRON_INGOT, 10)));
            inventory(e2e, sellerName, Map.of(0, stack(Material.IRON_INGOT, 10)));
            seller.clearMessages();
            command(e2e, seller, "sell hand");
            e2e.eventually(() -> seller.chatContains("Buy orders changed, so everything went to the server."),
                "the seller is told: " + seller.chat());
            e2e.eventually(() -> e2e.money(sellerName) - beforeRace >= serverRace && e2e.money(sellerName) - beforeRace < 2 * serverRace,
                "only the server paid ($" + (e2e.money(sellerName) - beforeRace) + ", the server part at least $" + serverRace + ")");
            e2e.expect(racer.raced.containsAll(List.of(first, second)), "both orders were taken by the rival first: " + racer.raced);
            e2e.expect(count(e2e, sellerName, Material.IRON_INGOT) == 0, "the iron was sold");
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(racer);
        }
        e2e.eventually(() -> row(e2e, first).startsWith("FILLED/10/10/") && row(e2e, second).startsWith("FILLED/10/10/"),
            "the rival completed both: " + row(e2e, first) + " " + row(e2e, second));
        e2e.eventually(() -> e2e.money(rivalName) == (10 * 100 - 20) + (10 * 90 - 18), "the rival was paid ($" + e2e.money(rivalName) + ")");
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM order_fills WHERE seller = ? AND order_id IN (?, ?)",
            e2e.uuid(sellerName).toString(), first, second) == 0, "the seller filled neither");
        healthy(e2e);

        e2e.step("the price list's Order it opens the new-order form with the item set, and Back returns");
        openMenu(e2e, buyer, "worth list iron ingot", "Prices");
        e2e.eventually(() -> slotOf(buyer, Material.IRON_INGOT) >= 0, "iron is listed");
        Bot.SeenDialog details = clickForDialog(e2e, buyer, slotOf(buyer, Material.IRON_INGOT), 0, ContainerInput.PICKUP, "Iron Ingot");
        Bot.SeenDialog form = clickToDialog(e2e, buyer, "Order it", Map.of(), "New order");
        e2e.expect("iron_ingot".equals(form.initial("item")), "the item is set: " + form.initial());
        e2e.expect(form.button("Back") != null, "the form goes back to the details: " + form.buttons());
        clickToDialog(e2e, buyer, "Back", typed(form, Map.of()), details.title());
    }

    /**
     * A deliverer who is always faster: the moment a sale's fill of an order is approved, it fills that order itself
     * through the orders engine (test-only reflection), so the sale's own transaction finds the order gone.
     */
    public static final class Racer implements org.bukkit.event.Listener {
        private final E2E e2e;
        private final UUID seller;
        private final UUID rival;
        final List<Long> raced = new java.util.concurrent.CopyOnWriteArrayList<>();

        Racer(E2E e2e, UUID seller, UUID rival) {
            this.e2e = e2e;
            this.seller = seller;
            this.rival = rival;
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onFill(net.siftvanilla.siftcore.api.event.OrderFillEvent event) {
            if (event.source() != net.siftvanilla.siftcore.api.event.OrderFillEvent.Source.SELL || !event.seller().equals(this.seller)
                || this.raced.contains(event.order())) {
                return;
            }
            try {
                Object feature = this.e2e.feature(net.siftvanilla.siftcore.feature.orders.OrdersFeature.class);
                java.lang.reflect.Field engineField = feature.getClass().getDeclaredField("engine");
                engineField.setAccessible(true);
                Object engine = engineField.get(feature);
                Class<?> sourceType = Class.forName("net.siftvanilla.siftcore.feature.orders.FillSource", true, feature.getClass().getClassLoader());
                Object menu = null;
                for (Object constant : sourceType.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals("MENU")) {
                        menu = constant;
                    }
                }
                java.lang.reflect.Method fill = engine.getClass().getDeclaredMethod("fill", UUID.class, long.class, int.class, long.class,
                    int.class, sourceType, String.class);
                fill.setAccessible(true);
                Object result = fill.invoke(engine, this.rival, event.order(), event.amount(), event.priceEach(), 200, menu, "e2e-racer");
                if (((net.siftvanilla.siftcore.api.economy.TransactionResult) result).success()) {
                    this.raced.add(event.order());
                } else {
                    this.e2e.log("the racer could not fill order " + event.order() + ": " + result);
                }
            } catch (ReflectiveOperationException e) {
                throw new E2E.Failure("the racer could not reach the orders engine: " + e);
            }
        }
    }
}
