package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.inventory.ContainerInput;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.feature.auction.AuctionFeature;
import net.siftvanilla.siftcore.feature.orders.DeliveryAlerts;
import net.siftvanilla.siftcore.feature.orders.OrdersFeature;
import net.siftvanilla.siftcore.feature.shop.ShopFeature;
import net.siftvanilla.siftcore.feature.shop.StartAmount;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The player settings of the shop, the auction house and buy orders (group Shop, auction &amp; orders, plus the order
 * announcement filter and opt-out) with real clients: choices changed in the {@code /settings} dialog, with the
 * {@code /settings <setting> <value>} command and through the API (the cause another plugin uses), each proving the
 * behaviour changes.
 * <p>
 * The market package owns the auction and orders scenario files, so these scenarios are registered from
 * {@link AuctionScenarios#all()} ({@link #withAuction()}: the auction and shop settings; the shop's other scenarios
 * live in the selling package's {@code SellShopScenarios}) and {@link OrdersScenarios#all()} ({@link #withOrders()}),
 * not from the shared {@code FeatureScenarios} list. The settings steps below are this file's own, so it depends on
 * no other package's scenario file.
 */
final class MarketScenarios {

    /** The title of the group's settings pages. */
    static final String MARKET_PAGE = "Shop, auction & orders settings";
    private static final String AUCTION = "Auction house";
    private static final String ORDERS = "Orders";

    private MarketScenarios() {
    }

    private record Named(String name, Body body, boolean ownAuctionHouse) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            if (this.ownAuctionHouse && AxAuctionsScenarios.running()) {
                e2e.log("AxAuctions runs, so SiftCore's own auction house is not in use; skipped");
                return;
            }
            this.body.run(e2e);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(E2E e2e) throws Exception;
    }

    /** The auction house's settings scenarios and the shop's (registered by {@link AuctionScenarios#all()}). */
    static List<Scenario> withAuction() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Named("market-shop-settings", MarketScenarios::shop, false));
        list.add(new Named("market-auction-settings", MarketScenarios::auction, true));
        list.add(new Named("market-auction-expiry-alerts", MarketScenarios::expiryAlerts, true));
        return list;
    }

    /** The buy orders' settings scenarios (registered by {@link OrdersScenarios#all()}). */
    static List<Scenario> withOrders() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Named("market-order-settings", MarketScenarios::orders, false));
        list.add(new Named("market-order-auto-collect", MarketScenarios::autoCollect, false));
        list.add(new Named("market-order-ending", MarketScenarios::ending, false));
        return list;
    }

    // ------------------------------------------------------------------ settings steps

    /** A player's UUID, also while they are offline (they joined before). */
    private static UUID known(E2E e2e, String name) {
        return e2e.services().directory().uuid(name).orElseThrow(() -> new E2E.Failure(name + " never joined"));
    }

    /** Changes a player's setting as another plugin would (the API cause); the change must go through. */
    static <T> void set(E2E e2e, String name, PlayerSetting<T> setting, T value) {
        SetResult result = e2e.services().settings().set(known(e2e, name), setting, value, Change.api("e2e"));
        e2e.expect(result.succeeded(), name + ": " + setting.id() + " = " + value + " went through: " + result);
    }

    /** A player's stored row for a setting, or null when none is stored (read after every queued write). */
    static String stored(E2E e2e, UUID player, String setting) {
        try {
            return e2e.services().database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE uuid = ? AND setting = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, setting);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading " + setting + " failed: " + e);
        }
    }

    /**
     * Changes a setting the way a player types it, {@code /settings <setting> <value>}, and waits until it is stored
     * ({@code stored}: the expected row, null for the default, which keeps no row) and the command confirmed it.
     */
    static void setByCommand(E2E e2e, Bot bot, String id, String value, String stored) {
        UUID player = e2e.uuid(bot.name);
        bot.clearMessages();
        command(e2e, bot, "settings " + id + " " + value);
        e2e.eventually(() -> Objects.equals(stored, stored(e2e, player, id)),
            "/settings " + id + " " + value + " stores " + stored + " (stored " + stored(e2e, player, id) + ", chat " + bot.chat()
                + ", action bar " + bot.actionBar() + ")");
        e2e.eventually(() -> bot.anyFeedbackContains(" set to ") || bot.anyFeedbackContains(" turned "),
            "the command confirms the change: " + bot.chat() + " " + bot.actionBar());
    }

    /** Waits for a dialog titled exactly {@code title}. */
    private static Bot.SeenDialog page(E2E e2e, Bot bot, String title) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().title().equals(title), bot.name + " sees '" + title + "': "
            + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** {@code /settings <group>} and waits for the freshly sent first page. */
    private static void openGroup(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog before = bot.dialog();
        command(e2e, bot, "settings " + group);
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a fresh '" + title + "': " + (bot.dialog() == null ? "none" : bot.dialog().title()));
    }

    /**
     * Opens a settings group and sets settings on it the way a player does, on their buttons ({@link SettingsSteps#edit});
     * a choice must offer the wanted option.
     */
    static void editSettings(E2E e2e, Bot bot, String group, String title, Map<String, Object> wanted) {
        bot.clearMessages();
        e2e.sleep(700);
        SettingsSteps.edit(e2e, bot, group, title, wanted);
    }

    /** Every setting of a group the player sees: a choice's option ids, or the kind ({@link SettingsSteps#inputs}). */
    static Map<String, List<String>> groupInputs(E2E e2e, Bot bot, String group, String title) {
        return SettingsSteps.inputs(e2e, bot, group, title);
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
            slots.forEach((slot, stack) -> inventory.setItem(slot, stack.clone()));
            return null;
        });
    }

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

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> lore(net.minecraft.world.item.ItemStack stack) {
        List<String> lines = new ArrayList<>();
        ItemLore lore = stack == null ? null : CraftItemStack.asBukkitCopy(stack).getData(DataComponentTypes.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(plain(line));
            }
        }
        return lines;
    }

    /** Entry slots (0-44) whose lore has a line containing {@code text}, in slot order. */
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

    /** The colour the first visible character of a component is drawn in (inherited from its parents). */
    private static TextColor firstColour(Component component, TextColor inherited) {
        TextColor colour = component.color() == null ? inherited : component.color();
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty()) {
            return colour;
        }
        for (Component child : component.children()) {
            TextColor found = firstColour(child, colour);
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
                if (text.startsWith("• ") && NamedTextColor.WHITE.equals(firstColour(line, null))) {
                    return text.substring(2);
                }
            }
        }
        return null;
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

    /** Clicks a slot of the open screen like a player (menus ignore clicks closer together than 75 ms). */
    private static void clickSlot(E2E e2e, Bot bot, int slot, int button) {
        e2e.sleep(200);
        bot.clickSlot(slot, button, ContainerInput.PICKUP);
    }

    private static Bot.SeenDialog clickForDialog(E2E e2e, Bot bot, int slot, int button, String title) {
        int seen = bot.dialogs().size();
        clickSlot(e2e, bot, slot, button);
        return newDialog(e2e, bot, seen, title);
    }

    /** Clicks a dialog button and waits for a new dialog with the title. */
    private static Bot.SeenDialog clickToDialog(E2E e2e, Bot bot, String label, Map<String, Object> values, String title) {
        int seen = bot.dialogs().size();
        e2e.expect(bot.clickButton(label, values), bot.name + " can click '" + label + "' in "
            + (bot.dialog() == null ? "no dialog" : bot.dialog().title() + " " + bot.dialog().buttons()));
        return newDialog(e2e, bot, seen, title);
    }

    private static void left(E2E e2e, Bot bot) {
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(bot.name) == null, bot.name + " left");
    }

    /** Runs {@code body} with text in a SiftCore config file replaced, then restores the file and reloads. */
    private static void withConfig(E2E e2e, String file, Map<String, String> replacements, Body body) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(file);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), file + " contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        Files.writeString(path, changed, StandardCharsets.UTF_8);
        e2e.expect(String.join(" ", e2e.consoleOutput("sift reload")).contains("Reloaded"), "the changed " + file + " reloads");
        try {
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    // ------------------------------------------------------------------ shop

    /** Opens the Blocks page from /shop and clicks stone; returns the new purchase dialog. */
    private static Bot.SeenDialog openStone(E2E e2e, Bot bot) {
        openMenu(e2e, bot, "shop", "Shop");
        Bot.Screen before = bot.screen();
        clickSlot(e2e, bot, 10, 0);
        awaitScreen(e2e, bot, before, "Blocks");
        int stone = -1;
        for (var entry : new TreeMap<>(bot.screenItems()).entrySet()) {
            if (entry.getKey() < 45 && CraftItemStack.asBukkitCopy(entry.getValue()).getType() == Material.STONE) {
                stone = entry.getKey();
                break;
            }
        }
        e2e.expect(stone >= 0, "stone is on the Blocks page");
        return clickForDialog(e2e, bot, stone, 0, "Buy stone");
    }

    /**
     * Shop settings: the group's inputs with the shared option ids, the buy window starting at one item (changed in
     * the dialog), at the last amount and with the receipt above the hotbar (both with {@code /settings}), at a full
     * inventory, the confirmation threshold (through the API), and the remembered sort order.
     */
    static void shop(E2E e2e) {
        String name = e2e.name("MkShop");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        inventory(e2e, name, Map.of());
        fund(e2e, name, 200_000);

        e2e.step("the Shop, auction & orders group offers the market settings with the shared option names");
        Map<String, List<String>> inputs = groupInputs(e2e, bot, "market", MARKET_PAGE);
        e2e.expect(List.of("server", "always", "10k", "100k", "1m", "never").equals(inputs.get("shop_confirm_above")), "confirm: " + inputs);
        e2e.expect(List.of("stack", "one", "last", "fill").equals(inputs.get("shop_default_amount")), "start amount: " + inputs);
        e2e.expect(List.of("chat", "actionbar").equals(inputs.get("shop_receipts")), "receipts: " + inputs);
        e2e.expect(List.of("chat", "actionbar", "off").equals(inputs.get("auction_sales")), "sale alerts: " + inputs);
        e2e.expect(List.of("chat", "actionbar", "complete", "off").equals(inputs.get("order_notices")), "delivery alerts: " + inputs);
        for (String key : List.of("auction_join_summary", "order_join_summary", "order_ending_alerts", "auction_price_warning",
            "order_auto_collect", "auction_expiry_alerts", "auction_hide_own")) {
            e2e.expect(inputs.containsKey(key), key + " is in the group: " + inputs.keySet());
        }
        e2e.expect(inputs.size() == 12, "twelve market settings: " + inputs.keySet());

        e2e.step("the buy window starts at one item after changing it in the dialog");
        editSettings(e2e, bot, "market", MARKET_PAGE, Map.of("shop_default_amount", "one"));
        e2e.eventually(() -> "one".equals(stored(e2e, id, "shop-default-amount")), "stored");
        bot.closeScreen();
        Bot.SeenDialog one = openStone(e2e, bot);
        e2e.expect(one.button("Buy 1 for $6") != null, "one item: " + one.buttons());
        Bot.SeenDialog ten = clickToDialog(e2e, bot, "Buy 1", Map.of("amount", 10, "exact", ""), "Buy stone");
        e2e.expect(ten.button("Buy 10 for $60") != null, "the new amount: " + ten.buttons());
        bot.clearMessages();
        e2e.click(bot, "Buy 10", Map.of("amount", 10, "exact", ""));
        e2e.eventually(() -> count(e2e, name, Material.STONE) == 10, "10 stone bought");
        e2e.eventually(() -> bot.chatContains("You bought 10 stone for $60."), "the receipt in chat (the default): " + bot.chat());

        e2e.step("/settings shop-default-amount last and /settings shop-receipts actionbar: the last amount, the receipt above the hotbar");
        bot.closeScreen();
        setByCommand(e2e, bot, "shop-default-amount", "last", "last");
        setByCommand(e2e, bot, "shop-receipts", "actionbar", "actionbar");
        Bot.SeenDialog last = openStone(e2e, bot);
        e2e.expect(last.button("Buy 10 for $60") != null, "starts at the last amount: " + last.buttons());
        bot.clearMessages();
        e2e.click(bot, "Buy 10", Map.of("amount", 10, "exact", ""));
        e2e.eventually(() -> count(e2e, name, Material.STONE) == 20, "10 more stone");
        e2e.eventually(() -> bot.actionBarContains("You bought 10 stone for $60."), "the receipt above the hotbar: " + bot.actionBar());
        e2e.sleep(500);
        e2e.expect(!bot.chatContains("You bought 10 stone"), "not in chat: " + bot.chat());

        e2e.step("a full inventory start: what fits (two empty slots and a stack of 20 make 172)");
        set(e2e, name, ShopFeature.DEFAULT_AMOUNT, StartAmount.FILL);
        Map<Integer, ItemStack> full = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            full.put(slot, ItemStack.of(Material.DIRT, 64));
        }
        full.put(0, ItemStack.of(Material.STONE, 20));
        full.remove(7);
        full.remove(8);
        inventory(e2e, name, full);
        bot.closeScreen();
        Bot.SeenDialog fill = openStone(e2e, bot);
        e2e.expect(fill.button("Buy 172 for $1,032") != null, "as many as fit: " + fill.buttons());
        inventory(e2e, name, Map.of());

        e2e.step("confirmation always (API): even $6 asks; never: $64,000 of diamonds does not");
        set(e2e, name, ShopFeature.DEFAULT_AMOUNT, StartAmount.ONE);
        set(e2e, name, ShopFeature.CONFIRM_ABOVE, ConfirmAbove.ALWAYS);
        bot.closeScreen();
        openStone(e2e, bot);
        Bot.SeenDialog confirm = clickToDialog(e2e, bot, "Buy 1", Map.of("amount", 1, "exact", ""), "Confirm purchase");
        e2e.expect(confirm.bodyText().contains("Buy 1 stone for $6?"), "the confirmation: " + confirm.body());
        long before = e2e.money(name);
        e2e.click(bot, "Buy");
        e2e.eventually(() -> e2e.money(name) == before - 6, "bought after confirming");
        set(e2e, name, ShopFeature.CONFIRM_ABOVE, ConfirmAbove.NEVER);
        set(e2e, name, ShopFeature.DEFAULT_AMOUNT, StartAmount.STACK);
        bot.closeScreen();
        openMenu(e2e, bot, "shop", "Shop");
        Bot.Screen shopScreen = bot.screen();
        clickSlot(e2e, bot, 16, 0);
        awaitScreen(e2e, bot, shopScreen, "Ores");
        Bot.SeenDialog diamonds = clickForDialog(e2e, bot, 9, 0, "Buy diamond");
        e2e.expect(diamonds.button("Buy 64 for $64,000") != null, "64 diamonds: " + diamonds.buttons());
        long rich = e2e.money(name);
        int dialogs = bot.dialogs().size();
        e2e.expect(bot.clickButton("Buy 64", Map.of("amount", 64, "exact", "")), "Buy can be clicked");
        e2e.eventually(() -> e2e.money(name) == rich - 64_000, "bought without a confirmation above the server's $50,000");
        e2e.expect(bot.dialogs().subList(dialogs, bot.dialogs().size()).stream().noneMatch(d -> d.title().contains("Confirm")),
            "no confirmation dialog: " + bot.dialogs());

        e2e.step("the sort order of shop pages is remembered");
        bot.closeScreen();
        openMenu(e2e, bot, "shop blocks", "Blocks");
        e2e.expect("Shop order".equals(selectedOption(bot, 47)), "shop order first: " + selectedOption(bot, 47));
        clickSlot(e2e, bot, 47, 0);
        e2e.eventually(() -> "Cheapest first".equals(selectedOption(bot, 47)), "cheapest now: " + selectedOption(bot, 47));
        bot.closeScreen();
        e2e.eventually(() -> "cheapest".equals(stored(e2e, id, "shop-sort")), "remembered when the page closed");
        openMenu(e2e, bot, "shop farming", "Farming");
        e2e.expect("Cheapest first".equals(selectedOption(bot, 47)), "another page opens sorted the same: " + selectedOption(bot, 47));
        bot.closeScreen();
    }

    // ------------------------------------------------------------------ auction

    private static long latestListing(E2E e2e, UUID seller) {
        return number(e2e, "SELECT COALESCE(MAX(id), 0) FROM auction_listings WHERE seller = ?", seller.toString());
    }

    private static String listingState(E2E e2e, long id) {
        return query(e2e, "SELECT state FROM auction_listings WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
    }

    /** /ah sell, then the confirmation; returns its body before confirming (when {@code list}) or cancelling. */
    private static String sellConfirm(E2E e2e, Bot bot, String args, boolean list) {
        UUID seller = e2e.uuid(bot.name);
        long before = latestListing(e2e, seller);
        int seen = bot.dialogs().size();
        command(e2e, bot, "ah sell " + args);
        Bot.SeenDialog confirm = newDialog(e2e, bot, seen, "List item");
        String body = confirm.bodyText();
        if (list) {
            e2e.click(bot, "List it");
            e2e.eventually(() -> latestListing(e2e, seller) > before, bot.name + " has a new listing");
            long id = latestListing(e2e, seller);
            e2e.eventually(() -> "ACTIVE".equals(listingState(e2e, id)), "listing " + id + " is active");
        } else {
            e2e.click(bot, "Cancel");
        }
        return body;
    }

    /** Opens /ah and clicks the seller's listing of {@code item}, up to the purchase dialog. */
    private static void openPurchase(E2E e2e, Bot buyer, String sellerName, Material item) {
        openMenu(e2e, buyer, "ah", AUCTION);
        e2e.eventually(() -> listingSlot(buyer, sellerName, item) >= 0, buyer.name + " sees " + sellerName + "'s " + item);
        clickForDialog(e2e, buyer, listingSlot(buyer, sellerName, item), 0, "Buy item");
    }

    /** The slot of the seller's listing of {@code item} on the open /ah page, or -1. */
    private static int listingSlot(Bot buyer, String sellerName, Material item) {
        for (int slot : slotsWith(buyer, "Seller " + sellerName)) {
            if (CraftItemStack.asBukkitCopy(buyer.screenItems().get(slot)).getType() == item) {
                return slot;
            }
        }
        return -1;
    }

    /** Confirms the open purchase dialog and waits until the listing is sold. */
    private static void confirmPurchase(E2E e2e, Bot buyer, long listing) {
        e2e.click(buyer, "Buy");
        e2e.eventually(() -> "SOLD".equals(listingState(e2e, listing)), "listing " + listing + " sold");
        buyer.closeScreen();
    }

    /** Opens /ah, clicks the seller's listing of {@code item} and confirms the purchase; waits until it is sold. */
    private static void buy(E2E e2e, Bot buyer, String sellerName, Material item, long listing) {
        openPurchase(e2e, buyer, sellerName, item);
        confirmPurchase(e2e, buyer, listing);
    }

    /**
     * Auction settings: sale alerts above the hotbar (changed in the dialog), the low price warning against /sell
     * and similar listings and turned off (API), hiding one's own listings ({@code /settings}), and the join summary
     * of what sold while away (not repeating a sale told live after the join), then without it (API, set while
     * offline).
     */
    static void auction(E2E e2e) {
        String sellerName = e2e.name("MkAhSell");
        String buyerName = e2e.name("MkAhBuy");
        String otherName = e2e.name("MkAhOther");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        Bot other = e2e.bot(otherName);
        UUID sellerId = e2e.uuid(sellerName);
        fund(e2e, buyerName, 100_000);
        fund(e2e, sellerName, 0);

        e2e.step("the seller wants sale alerts above the hotbar (dialog)");
        editSettings(e2e, seller, "market", MARKET_PAGE, Map.of("auction_sales", "actionbar"));
        e2e.eventually(() -> "actionbar".equals(stored(e2e, sellerId, "auction-sales")), "stored");

        e2e.step("listing 10 diamonds for $10 warns that /sell pays more; listing goes ahead anyway");
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.DIAMOND, 10)));
        String cheap = sellConfirm(e2e, seller, "10", true);
        e2e.expect(cheap.contains("That's less than /sell pays you for them ($4,000)."), "the warning: " + cheap);
        long first = latestListing(e2e, sellerId);

        e2e.step("a sale tells the seller above the hotbar, not in chat");
        seller.clearMessages();
        buy(e2e, buyer, sellerName, Material.DIAMOND, first);
        e2e.eventually(() -> seller.actionBarContains(buyerName + " bought your 10 Diamond for $10."), "above the hotbar: " + seller.actionBar());
        e2e.sleep(500);
        e2e.expect(!seller.chatContains("bought your 10 Diamond"), "not in chat: " + seller.chat());

        e2e.step("far below similar listings of others warns; with the warning off (API) nothing is said");
        // Echo shards: nothing else on the test server lists them, and /sell pays only $100 each.
        inventory(e2e, otherName, Map.of(0, ItemStack.of(Material.ECHO_SHARD, 1)));
        sellConfirm(e2e, other, "3000", true);
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.ECHO_SHARD, 5)));
        String low = sellConfirm(e2e, seller, "5000", false);
        e2e.expect(low.contains("That's far below similar listings: they start at $3,000 each, you ask $1,000 each."), "the warning: " + low);
        e2e.expect(!low.contains("/sell pays"), "more than /sell pays ($500): " + low);
        set(e2e, sellerName, AuctionFeature.PRICE_WARNING, false);
        String quiet = sellConfirm(e2e, seller, "5000", true);
        e2e.expect(!quiet.contains("far below") && !quiet.contains("/sell pays"), "no warning when it is off: " + quiet);
        long second = latestListing(e2e, sellerId);

        e2e.step("hiding one's own listings (/settings auction-hide-own on) leaves them out of /ah, not out of Your listings");
        openMenu(e2e, seller, "ah", AUCTION);
        e2e.expect(!slotsWith(seller, "Seller " + sellerName).isEmpty(), "the own listing shows by default");
        seller.closeScreen();
        setByCommand(e2e, seller, "auction-hide-own", "on", "true");
        openMenu(e2e, seller, "ah", AUCTION);
        slotWith(e2e, seller, "Seller " + otherName);
        e2e.expect(slotsWith(seller, "Seller " + sellerName).isEmpty(), "the own listing is hidden: " + seller.screenItems().keySet());
        seller.closeScreen();
        openMenu(e2e, seller, "ah listings", "Your listings");
        e2e.expect(!seller.screenItems().isEmpty() && seller.screenItems().keySet().stream().anyMatch(slot -> slot < 45),
            "still in Your listings");
        seller.closeScreen();

        e2e.step("a sale while the seller is away is summed up when they join; one right after the join is told live only");
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.DIAMOND, 3)));
        sellConfirm(e2e, seller, "6000", true);
        long afterJoin = latestListing(e2e, sellerId);
        left(e2e, seller);
        buy(e2e, buyer, sellerName, Material.ECHO_SHARD, second);
        openPurchase(e2e, buyer, sellerName, Material.DIAMOND);
        Bot back = e2e.botAtSpawn(sellerName);
        // Bought right after the join, before the summary runs (three seconds after it): told live above the hotbar,
        // and the summary must not tell it again as a sale "while you were away".
        confirmPurchase(e2e, buyer, afterJoin);
        e2e.eventually(() -> back.chatContains("While you were away, " + buyerName + " bought your 5 Echo Shard for $5,000. You got $4,750 after tax."),
            "the summary names the one sale while away: " + back.chat());
        e2e.eventually(() -> back.actionBarContains(buyerName + " bought your 3 Diamond for $6,000."), "the later sale live: " + back.actionBar());
        e2e.sleep(1_000);
        e2e.expect(back.chat().stream().noneMatch(line -> line.contains("3 Diamond")), "the later sale is not in the summary: " + back.chat());
        e2e.leaveSpawn(back);

        e2e.step("without the join summary (API, set while offline) the next sale waits silently in /ah history");
        inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.DIAMOND, 2)));
        sellConfirm(e2e, back, "4000", true);
        long third = latestListing(e2e, sellerId);
        left(e2e, back);
        set(e2e, sellerName, AuctionFeature.JOIN_SUMMARY, false);
        buy(e2e, buyer, sellerName, Material.DIAMOND, third);
        Bot again = e2e.bot(sellerName);
        e2e.sleep(5_000);
        e2e.expect(!again.chatContains("While you were away"), "no summary: " + again.chat());
        other.closeScreen();
    }

    /**
     * Expired listing alerts, with one-minute listings checked every five seconds: a seller who chose "off" with
     * {@code /settings auction-expiry-alerts off} hears nothing (the item still goes to their claim box), one who chose
     * "actionbar" the same way reads it above the hotbar and not in chat.
     */
    static void expiryAlerts(E2E e2e) throws Exception {
        withConfig(e2e, "features/auction.yml", Map.of("duration: 48h", "duration: 1m", "expiry-check: 30s", "expiry-check: 5s"), x -> {
            String quietName = e2e.name("MkExpOff");
            String barName = e2e.name("MkExpBar");
            Bot quiet = e2e.bot(quietName);
            Bot bar = e2e.bot(barName);
            UUID quietId = e2e.uuid(quietName);
            UUID barId = e2e.uuid(barName);

            e2e.step("/settings auction-expiry-alerts off for one seller and actionbar for the other");
            setByCommand(e2e, quiet, "auction-expiry-alerts", "off", "off");
            setByCommand(e2e, bar, "auction-expiry-alerts", "actionbar", "actionbar");

            e2e.step("both list a diamond for a minute");
            inventory(e2e, quietName, Map.of(0, ItemStack.of(Material.DIAMOND, 1)));
            inventory(e2e, barName, Map.of(0, ItemStack.of(Material.DIAMOND, 1)));
            sellConfirm(e2e, quiet, "1000", true);
            long quietListing = latestListing(e2e, quietId);
            sellConfirm(e2e, bar, "1000", true);
            long barListing = latestListing(e2e, barId);
            int quietBox = e2e.services().deliveries().count(quietId);
            quiet.clearMessages();
            bar.clearMessages();

            e2e.step("both expire: above the hotbar for one, nothing at all for the other");
            e2e.eventually(() -> "EXPIRED".equals(listingState(e2e, quietListing)) && "EXPIRED".equals(listingState(e2e, barListing)),
                100_000, "both expired within 100 seconds");
            e2e.eventually(() -> bar.actionBarContains("Your listing of 1 Diamond expired."), "above the hotbar: " + bar.actionBar());
            e2e.sleep(2_000);
            e2e.expect(!bar.chatContains("expired"), "not in chat: " + bar.chat());
            e2e.expect(!quiet.anyFeedbackContains("expired"), "off: nothing said (" + quiet.chat() + " " + quiet.actionBar() + ")");
            e2e.eventually(() -> e2e.services().deliveries().count(quietId) == quietBox + 1, "the item still went to the claim box");
        });
    }

    // ------------------------------------------------------------------ orders

    private static long latestOrder(E2E e2e, String owner) {
        return number(e2e, "SELECT COALESCE(MAX(id), 0) FROM orders WHERE owner = ?", e2e.uuid(owner).toString());
    }

    private static int[] filledCollected(E2E e2e, long id) {
        return query(e2e, "SELECT filled, collected FROM orders WHERE id = ?",
            rs -> rs.next() ? new int[] {rs.getInt(1), rs.getInt(2)} : new int[] {-1, -1}, id);
    }

    private static String state(E2E e2e, long id) {
        return query(e2e, "SELECT state FROM orders WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
    }

    /** Places an order with /orders create and its confirmation; returns the stored order's id. */
    private static long place(E2E e2e, Bot bot, String item, String quantity, String price) {
        long before = latestOrder(e2e, bot.name);
        int seen = bot.dialogs().size();
        command(e2e, bot, "orders create " + item + " " + quantity + " " + price);
        newDialog(e2e, bot, seen, "Place order");
        e2e.click(bot, "Place order");
        e2e.eventually(() -> latestOrder(e2e, bot.name) > before, bot.name + " has a new order");
        long id = latestOrder(e2e, bot.name);
        e2e.eventually(() -> bot.chatContains("is up."), bot.name + " is told the order is up: " + bot.chat());
        return id;
    }

    /**
     * Quick delivers {@code units} diamonds to the owner's order from the browser and waits until they count (the
     * owner must have only this one active diamond order).
     */
    private static void deliver(E2E e2e, Bot seller, String owner, long orderId, int units) {
        inventory(e2e, seller.name, Map.of(0, ItemStack.of(Material.DIAMOND, units)));
        int filled = filledCollected(e2e, orderId)[0];
        openMenu(e2e, seller, "orders diamond", ORDERS);
        int slot = slotWith(e2e, seller, "Ordered by " + owner);
        clickForDialog(e2e, seller, slot, 1, "Deliver");
        e2e.click(seller, "Deliver " + units);
        e2e.eventually(() -> filledCollected(e2e, orderId)[0] >= filled + units, "order " + orderId + " counts " + units + " more");
        seller.closeScreen();
    }

    /** Staff-cancels every active order of a player (the end of a step, or one left by an earlier run). */
    private static void cancelAll(E2E e2e, String owner) {
        cancelWhere(e2e, "owner = ?", e2e.services().directory().uuid(owner).map(UUID::toString).orElse(""));
    }

    private static void cancelWhere(E2E e2e, String where, Object param) {
        List<Long> ids = query(e2e, "SELECT id FROM orders WHERE state = 'ACTIVE' AND " + where, rs -> {
            List<Long> list = new ArrayList<>();
            while (rs.next()) {
                list.add(rs.getLong(1));
            }
            return list;
        }, param);
        for (long id : ids) {
            e2e.console("orders admin cancel " + id + " end of test");
        }
        for (long id : ids) {
            e2e.eventually(() -> !"ACTIVE".equals(state(e2e, id)), "order " + id + " is cancelled");
        }
    }

    /** No line containing {@code text} reached the bot in chat or above the hotbar. */
    private static void silent(E2E e2e, Bot bot, String... texts) {
        for (String text : texts) {
            e2e.expect(!bot.anyFeedbackContains(text), "nothing with '" + text + "' (chat " + bot.chat() + ", action bar " + bot.actionBar() + ")");
        }
    }

    /**
     * Order settings: delivery alerts "only when complete" (dialog), the announcement filter of a watcher and an
     * owner's opt-out (API), a sale into several orders above the hotbar as one line ({@code /settings}), and the join
     * summary turned off (API).
     */
    static void orders(E2E e2e) {
        String ownerName = e2e.name("MkOrdOwn");
        String sellerName = e2e.name("MkOrdSel");
        String bigName = e2e.name("MkOrdBig");
        String shyName = e2e.name("MkOrdShy");
        String allName = e2e.name("MkOrdAll");
        String pickyName = e2e.name("MkOrdPick");
        Bot owner = e2e.bot(ownerName);
        Bot seller = e2e.bot(sellerName);
        Bot big = e2e.bot(bigName);
        Bot shy = e2e.bot(shyName);
        Bot all = e2e.bot(allName);
        Bot picky = e2e.bot(pickyName);
        UUID ownerId = e2e.uuid(ownerName);
        fund(e2e, ownerName, 1_000_000);
        fund(e2e, sellerName, 0);
        try {
            e2e.step("the owner only wants to hear when an order is complete (dialog)");
            Map<String, List<String>> announcements = groupInputs(e2e, picky, "announcements", "Server announcements settings");
            e2e.expect(List.of("all", "10m", "100m", "off").equals(announcements.get("orders_announce")),
                "the filter without \"from $1m\" (the server announces from $1m): " + announcements);
            editSettings(e2e, owner, "market", MARKET_PAGE, Map.of("order_notices", "complete"));
            e2e.eventually(() -> "complete".equals(stored(e2e, ownerId, "order-notices")), "stored");
            owner.closeScreen();

            e2e.step("a delivery that does not complete the order says nothing; the one that does says so");
            long id = place(e2e, owner, "diamond", "20", "500");
            owner.clearMessages();
            deliver(e2e, seller, ownerName, id, 10);
            e2e.sleep(1_000);
            silent(e2e, owner, "delivered 10 Diamond");
            deliver(e2e, seller, ownerName, id, 10);
            e2e.eventually(() -> owner.chatContains("Your order for 20 Diamond is complete. Collect it in /orders."), "the completion: " + owner.chat());

            e2e.step("a big order is announced to the default, not to a $10m filter (API); an owner can opt out (API)");
            set(e2e, pickyName, OrdersFeature.ANNOUNCEMENTS, OrdersFeature.ANNOUNCEMENTS.decodeOrNull("10m"));
            set(e2e, shyName, OrdersFeature.ANNOUNCE_MINE, false);
            fund(e2e, bigName, 2_000_000);
            fund(e2e, shyName, 2_000_000);
            all.clearMessages();
            picky.clearMessages();
            place(e2e, big, "diamond", "2500", "500");
            e2e.eventually(() -> all.chatContains("New buy order: " + bigName), "announced: " + all.chat());
            e2e.sleep(500);
            e2e.expect(!picky.chatContains("New buy order: " + bigName), "a $1,250,000 order is under the $10m filter: " + picky.chat());
            place(e2e, shy, "diamond", "2500", "500");
            e2e.sleep(1_500);
            e2e.expect(!all.chatContains("New buy order: " + shyName), "the owner opted out: " + all.chat());
            cancelAll(e2e, bigName);
            cancelAll(e2e, shyName);

            e2e.step("above the hotbar (/settings order-notices actionbar): a sale into two orders that completes one is one line");
            if (sellRouting()) {
                setByCommand(e2e, owner, "order-notices", "actionbar", "actionbar");
                cancelAll(e2e, ownerName);
                // Iron orders of earlier, interrupted runs would take part of the sale.
                cancelWhere(e2e, "item_type = ?", "minecraft:iron_ingot");
                long high = place(e2e, owner, "iron_ingot", "10", "100");
                long next = place(e2e, owner, "iron_ingot", "10", "90");
                inventory(e2e, sellerName, Map.of(0, ItemStack.of(Material.IRON_INGOT, 15)));
                owner.clearMessages();
                command(e2e, seller, "sell hand");
                e2e.eventually(() -> "FILLED".equals(state(e2e, high)) && filledCollected(e2e, next)[0] == 5,
                    "10 to the first order (complete), 5 to the second");
                e2e.eventually(() -> owner.actionBarContains(sellerName + " sold 15 Iron Ingot to your orders. An order is complete."),
                    "one line above the hotbar: " + owner.actionBar());
                e2e.sleep(1_000);
                e2e.expect(owner.actionBar().stream().filter(line -> line.contains("Iron Ingot")).count() == 1,
                    "no second line replaced it: " + owner.actionBar());
                silent(e2e, owner, "Your order for 10 Iron Ingot is complete");
                e2e.expect(owner.chat().stream().noneMatch(line -> line.contains("Iron Ingot")), "nothing in chat: " + owner.chat());
                cancelAll(e2e, ownerName);
            } else {
                e2e.log("selling does not route to buy orders in this build; the sale step is skipped");
            }

            e2e.step("with the join summary off (API) an offline owner only hears about refunds");
            inventory(e2e, ownerName, Map.of());
            cancelAll(e2e, ownerName);
            long waiting = place(e2e, owner, "diamond", "10", "500");
            left(e2e, owner);
            set(e2e, ownerName, OrdersFeature.JOIN_SUMMARY, false);
            deliver(e2e, seller, ownerName, waiting, 5);
            e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM order_notices WHERE owner = ?", ownerId.toString()) > 0,
                "a notice waits for the owner");
            Bot back = e2e.bot(ownerName);
            e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM order_notices WHERE owner = ?", ownerId.toString()) == 0,
                "read and deleted on join");
            e2e.sleep(500);
            e2e.expect(!back.chatContains("While you were away") && !back.chatContains("wait for you in /orders"), "no summary: " + back.chat());
        } finally {
            cancelAll(e2e, ownerName);
            cancelAll(e2e, bigName);
            cancelAll(e2e, shyName);
        }
    }

    /** Whether selling routes items into buy orders in this build (the selling feature's order routing). */
    private static boolean sellRouting() {
        try {
            Class.forName("net.siftvanilla.siftcore.feature.sell.OrderRouting", false, MarketScenarios.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Auto-collect ({@code /settings order-auto-collect on}) and what the owner is told, by their delivery alerts:
     * "only when complete" says nothing for a partial delivery (no "Collected" line) and that the order is complete
     * and in the inventory for the last one; above the hotbar one line says the items went straight into the inventory,
     * or how many wait when not all fit; "never" (API) says nothing at all while the items still arrive.
     */
    static void autoCollect(E2E e2e) {
        String ownerName = e2e.name("MkAutoOwn");
        String sellerName = e2e.name("MkAutoSel");
        Bot owner = e2e.bot(ownerName);
        Bot seller = e2e.bot(sellerName);
        UUID ownerId = e2e.uuid(ownerName);
        fund(e2e, ownerName, 1_000_000);
        fund(e2e, sellerName, 0);
        try {
            e2e.step("/settings order-auto-collect on, and only completions (API)");
            setByCommand(e2e, owner, "order-auto-collect", "on", "true");
            set(e2e, ownerName, OrdersFeature.NOTIFICATIONS, DeliveryAlerts.COMPLETE);

            e2e.step("only when complete: a partial delivery goes into the inventory without a word");
            inventory(e2e, ownerName, Map.of());
            long id = place(e2e, owner, "diamond", "20", "500");
            owner.clearMessages();
            deliver(e2e, seller, ownerName, id, 10);
            e2e.eventually(() -> count(e2e, ownerName, Material.DIAMOND) == 10, "collected into the inventory: " + count(e2e, ownerName, Material.DIAMOND));
            e2e.eventually(() -> filledCollected(e2e, id)[1] == 10, "the order counts them collected");
            e2e.sleep(1_500);
            silent(e2e, owner, "Collected", "delivered", "inventory", "10 Diamond");

            e2e.step("the delivery that completes it says it is complete and in the inventory, still no Collected line");
            deliver(e2e, seller, ownerName, id, 10);
            e2e.eventually(() -> count(e2e, ownerName, Material.DIAMOND) == 20, "all 20 in the inventory");
            e2e.eventually(() -> owner.chatContains("Your order for 20 Diamond is complete and in your inventory."), "the completion: " + owner.chat());
            silent(e2e, owner, "Collected", "Collect it in /orders", "delivered");

            e2e.step("above the hotbar (API): one line, the items went straight into the inventory");
            set(e2e, ownerName, OrdersFeature.NOTIFICATIONS, DeliveryAlerts.ACTIONBAR);
            inventory(e2e, ownerName, Map.of());
            long second = place(e2e, owner, "diamond", "30", "500");
            owner.clearMessages();
            deliver(e2e, seller, ownerName, second, 12);
            e2e.eventually(() -> owner.actionBarContains(sellerName + " delivered 12 Diamond straight into your inventory."),
                "above the hotbar: " + owner.actionBar());
            e2e.eventually(() -> count(e2e, ownerName, Material.DIAMOND) == 12, "collected into the inventory");
            e2e.eventually(() -> filledCollected(e2e, second)[1] == 12, "the order counts them collected");
            e2e.sleep(1_000);
            silent(e2e, owner, "Collected", "to your order");
            e2e.expect(owner.chat().stream().noneMatch(line -> line.contains("Diamond")), "nothing in chat: " + owner.chat());

            e2e.step("when not all fit, the line says how many wait in the order");
            Map<Integer, ItemStack> full = new HashMap<>();
            for (int slot = 1; slot < 36; slot++) {
                full.put(slot, ItemStack.of(Material.DIRT, 64));
            }
            full.put(0, ItemStack.of(Material.DIAMOND, 60));
            inventory(e2e, ownerName, full);
            owner.clearMessages();
            deliver(e2e, seller, ownerName, second, 10);
            e2e.eventually(() -> owner.actionBarContains(sellerName + " delivered 10 Diamond. What fit went into your inventory; 6 wait in /orders."),
                "above the hotbar: " + owner.actionBar());
            e2e.eventually(() -> count(e2e, ownerName, Material.DIAMOND) == 64, "4 fit");
            e2e.eventually(() -> filledCollected(e2e, second)[0] == 22 && filledCollected(e2e, second)[1] == 16, "6 wait in the order");
            silent(e2e, owner, "Your inventory is full");

            e2e.step("never (API): the items still arrive, nothing is said");
            set(e2e, ownerName, OrdersFeature.NOTIFICATIONS, DeliveryAlerts.OFF);
            cancelAll(e2e, ownerName);
            inventory(e2e, ownerName, Map.of());
            long third = place(e2e, owner, "diamond", "10", "500");
            owner.clearMessages();
            deliver(e2e, seller, ownerName, third, 4);
            e2e.eventually(() -> count(e2e, ownerName, Material.DIAMOND) == 4, "collected into the inventory");
            e2e.sleep(1_500);
            silent(e2e, owner, "Collected", "delivered", "inventory", "4 Diamond");
            e2e.expect("true".equals(stored(e2e, ownerId, "order-auto-collect")), "auto-collect is still on");
        } finally {
            cancelAll(e2e, ownerName);
        }
    }

    /** Ending warnings: with one-minute orders warned 50s before the end, the default owner is warned, one who turned it off is not. */
    static void ending(E2E e2e) throws Exception {
        withConfig(e2e, "features/orders.yml", Map.of("duration: 7d", "duration: 1m", "expiry-warning: 12h", "expiry-warning: 50s",
            "expiry-check: 30s", "expiry-check: 5s"), x -> {
            String warnedName = e2e.name("MkEndOn");
            String quietName = e2e.name("MkEndOff");
            Bot warned = e2e.bot(warnedName);
            Bot quiet = e2e.bot(quietName);
            fund(e2e, warnedName, 100_000);
            fund(e2e, quietName, 100_000);
            try {
                e2e.step("the ending warning switch is offered and turned off with /settings order-ending-alerts off for one owner");
                Map<String, List<String>> inputs = groupInputs(e2e, quiet, "market", MARKET_PAGE);
                e2e.expect(inputs.containsKey("order_ending_alerts"), "offered while expiry-warning is on: " + inputs.keySet());
                quiet.closeScreen();
                setByCommand(e2e, quiet, "order-ending-alerts", "off", "false");
                place(e2e, warned, "diamond", "10", "100");
                place(e2e, quiet, "diamond", "10", "100");
                warned.clearMessages();
                quiet.clearMessages();
                e2e.eventually(() -> warned.chatContains("Your order for 10 Diamond ends in"), 40_000, "the warning: " + warned.chat());
                e2e.sleep(6_000);
                e2e.expect(!quiet.chatContains("ends in"), "no warning for the owner who turned it off: " + quiet.chat());
            } finally {
                cancelAll(e2e, warnedName);
                cancelAll(e2e, quietName);
            }
        });
    }
}
