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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ContainerInput;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.economy.Ledger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The auction house end to end: listing with /ah sell and through the menu's sell form, buying with money and item
 * moving together (no tax as shipped, and a 5% tax set for one scenario), taking listings down, the claim box (auto-claim, full inventories, claim all, the join
 * reminder), refusals, a two-buyer race, sorting, filtering and searching, history, staff removal, the hub entry,
 * expiry through the real timer, and listings and claim box items surviving a restart.
 */
final class AuctionScenarios {

    private static final String TITLE = "Auction house";

    private AuctionScenarios() {
    }

    private record Named(String name, Body body) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            if (AxAuctionsScenarios.running()) {
                // AxAuctions is the auction house then: /ah and the main menu button open it (AxAuctionsScenarios).
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

    private static Scenario of(String name, Body body) {
        return new Named(name, body);
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("auction-sell-buy", AuctionScenarios::sellAndBuy));
        list.add(of("auction-sell-buy-taxed", AuctionScenarios::sellAndBuyTaxed));
        list.add(of("auction-sell-form", AuctionScenarios::sellForm));
        list.add(of("auction-take-down", AuctionScenarios::takeDown));
        list.add(of("auction-claim-box", AuctionScenarios::claimBox));
        list.add(of("auction-refusals", AuctionScenarios::refusals));
        list.add(of("auction-double-submit", AuctionScenarios::doubleSubmit));
        list.add(of("auction-buy-race", AuctionScenarios::buyRace));
        list.add(of("auction-browse", AuctionScenarios::browse));
        list.add(of("auction-history", AuctionScenarios::history));
        list.add(of("auction-staff", AuctionScenarios::staff));
        list.add(of("auction-hub", AuctionScenarios::hub));
        list.add(of("auction-claims-command", AuctionScenarios::claimsCommand));
        list.add(of("auction-persist-setup", AuctionScenarios::persistSetup));
        list.add(of("auction-persist-check", AuctionScenarios::persistCheck));
        list.add(of("auction-expiry", AuctionScenarios::expiry));
        // The player settings of the auction house and the shop (MarketScenarios, the same package's file).
        list.addAll(MarketScenarios.withAuction());
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Sends a command the way a player types one. Paced, because the server kicks clients that keep sending commands
     * faster than about one a second (vanilla chat spam protection).
     */
    private static void command(E2E e2e, Bot bot, String command) {
        e2e.sleep(700);
        bot.command(command);
    }

    /** Clears the player's inventory and puts {@code stack} in the selected first hotbar slot. */
    private static void hold(E2E e2e, String name, ItemStack stack) {
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.getInventory().clear();
            player.getInventory().setHeldItemSlot(0);
            player.getInventory().setItem(0, stack == null ? null : stack.clone());
            return null;
        });
    }

    private static void hold(E2E e2e, String name, Material material, int amount) {
        hold(e2e, name, ItemStack.of(material, amount));
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

    private static void fund(E2E e2e, String name, long amount) {
        e2e.console("eco set " + name + " " + amount);
        e2e.eventually(() -> e2e.money(name) == amount, name + " has $" + amount);
    }

    @FunctionalInterface
    private interface Rows<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** Runs a read against SiftCore's database, optionally after every queued write was committed. */
    private static <T> T query(E2E e2e, boolean flush, String sql, Rows<T> reader, Object... params) {
        var database = e2e.services().database();
        if (flush) {
            database.flush();
        }
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
        return query(e2e, true, sql, rs -> rs.next() ? rs.getLong(1) : 0L, params);
    }

    private static long latestListing(E2E e2e, UUID seller) {
        return number(e2e, "SELECT COALESCE(MAX(id), 0) FROM auction_listings WHERE seller = ?", seller.toString());
    }

    private static String state(E2E e2e, long id) {
        return query(e2e, true, "SELECT state FROM auction_listings WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
    }

    private static int claimBox(E2E e2e, UUID owner) {
        return e2e.services().deliveries().count(owner);
    }

    private static void ledgerHealthy(E2E e2e) throws Exception {
        Ledger.AuditReport report = e2e.services().ledger().audit().get(20, TimeUnit.SECONDS);
        e2e.expect(report.healthy(), "the ledger invariants hold: " + report.problems());
    }

    /** Lists what the bot holds with /ah sell and confirms; returns the new listing's id once it is stored. */
    private static long sell(E2E e2e, Bot bot, String price, int amount) {
        UUID seller = e2e.uuid(bot.name);
        long before = latestListing(e2e, seller);
        bot.clearLogs();
        command(e2e, bot, "ah sell " + price + (amount > 0 ? " " + amount : ""));
        e2e.dialog(bot, "List item");
        e2e.click(bot, "List it");
        e2e.eventually(() -> latestListing(e2e, seller) > before, bot.name + " has a new listing");
        long id = latestListing(e2e, seller);
        e2e.eventually(() -> "ACTIVE".equals(state(e2e, id)), "listing " + id + " is stored as active");
        return id;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> lore(net.minecraft.world.item.ItemStack stack) {
        ItemLore lore = CraftItemStack.asBukkitCopy(stack).getData(DataComponentTypes.LORE);
        List<String> lines = new ArrayList<>();
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

    /** The item types of one seller's listings in the open menu, in slot order. */
    private static List<Material> listingsOf(Bot bot, String seller) {
        List<Material> types = new ArrayList<>();
        for (var entry : new TreeMap<>(bot.screenItems()).entrySet()) {
            if (entry.getKey() < 45 && lore(entry.getValue()).contains("Seller " + seller)) {
                types.add(CraftItemStack.asBukkitCopy(entry.getValue()).getType());
            }
        }
        return types;
    }

    /** Runs a command and waits for a new chest screen with the title. */
    private static void openMenu(E2E e2e, Bot bot, String command, String title) {
        Bot.Screen before = bot.screen();
        command(e2e, bot, command);
        awaitScreen(e2e, bot, before, title);
    }

    private static void awaitScreen(E2E e2e, Bot bot, Bot.Screen before, String title) {
        e2e.eventually(() -> bot.screen() != null && bot.screen() != before && bot.screen().title().contains(title)
            && !bot.screenItems().isEmpty(), bot.name + " sees the screen '" + title + "'");
    }

    /** Opens /ah, clicks the seller's listing and confirms the purchase. */
    private static void buy(E2E e2e, Bot buyer, String sellerName) {
        openMenu(e2e, buyer, "ah", TITLE);
        int slot = slotWith(e2e, buyer, "Seller " + sellerName);
        buyer.clearLogs();
        buyer.clickSlot(slot);
        e2e.dialog(buyer, "Buy item");
        e2e.click(buyer, "Buy");
    }

    /** Waits for a dialog the bot had not received before (bots keep the last dialog until it is cleared). */
    private static Bot.SeenDialog newDialog(E2E e2e, Bot bot, int seenBefore, String title) {
        e2e.eventually(() -> bot.dialogs().size() > seenBefore && bot.dialogs().getLast().title().contains(title),
            bot.name + " receives the dialog '" + title + "'");
        return bot.dialogs().getLast();
    }

    // ------------------------------------------------------------------ scenarios

    /** Selling and buying as shipped: no tax, so nothing mentions one and the seller gets the whole price. */
    static void sellAndBuy(E2E e2e) throws Exception {
        sellAndBuy(e2e, false);
    }

    /** The same with a 5% tax set in features/auction.yml for the scenario: the tax is shown, taken and sunk. */
    static void sellAndBuyTaxed(E2E e2e) throws Exception {
        withConfig(e2e, "features/auction.yml", "tax: 0", "tax: 5", x -> sellAndBuy(x, true));
    }

    /** Runs {@code body} with one line of a config file changed (and reloaded), then puts it back. */
    private static void withConfig(E2E e2e, String file, String from, String to, Body body) throws Exception {
        Path path = e2e.services().plugin().getDataFolder().toPath().resolve(file);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        e2e.expect(original.contains(from), file + " has '" + from + "'");
        try {
            Files.writeString(path, original.replace(from, to), StandardCharsets.UTF_8);
            e2e.expect(String.join(" ", e2e.consoleOutput("sift reload")).contains("Reloaded"), "the changed " + file + " reloads");
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    private static void sellAndBuy(E2E e2e, boolean taxed) throws Exception {
        String sellerName = e2e.name(taxed ? "AhTaxSell" : "AhSeller");
        String buyerName = e2e.name(taxed ? "AhTaxBuy" : "AhBuyer");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        fund(e2e, buyerName, 5_000);
        hold(e2e, sellerName, Material.DIAMOND, 16);

        e2e.step("/ah sell shows the item and price" + (taxed ? " with the tax" : ", no tax") + "; the duration is in the tooltip");
        seller.clearLogs();
        command(e2e, seller, "ah sell 1000 10");
        Bot.SeenDialog confirm = e2e.dialog(seller, "List item");
        String body = confirm.bodyText();
        e2e.expect(body.contains("List 10 Diamond for $1,000?"), "the item and price: " + body);
        if (taxed) {
            e2e.expect(body.contains("Tax when it sells $50 (5%), you get $950"), "the tax and proceeds: " + body);
        } else {
            e2e.expect(!body.toLowerCase().contains("tax"), "no tax anywhere: " + body);
        }
        String tooltip = confirm.button("List it").tooltip();
        e2e.expect(tooltip != null && tooltip.contains("Put it up for 2d.") && tooltip.contains("waits in your claim box"),
            "the duration in the List it tooltip: " + tooltip);
        e2e.expect(tooltip.contains("Listing slots used 1 of 3"), "the slots in the tooltip: " + tooltip);
        e2e.expect(count(e2e, sellerName, Material.DIAMOND) == 16, "nothing taken before confirming");

        e2e.step("confirming takes the items and lists them");
        e2e.click(seller, "List it");
        e2e.eventually(() -> count(e2e, sellerName, Material.DIAMOND) == 6, "10 diamonds left the inventory");
        e2e.eventually(() -> seller.actionBarContains("Listed 10 Diamond for $1,000."), "listed feedback: " + seller.actionBar());
        long id = latestListing(e2e, e2e.uuid(sellerName));
        e2e.expect(id > 0 && "ACTIVE".equals(state(e2e, id)), "an active listing row");

        e2e.step("the buyer sees it in /ah with price, seller and time left");
        openMenu(e2e, buyer, "ah", TITLE);
        int slot = slotWith(e2e, buyer, "Seller " + sellerName);
        List<String> lore = lore(buyer.screenItems().get(slot));
        e2e.expect(lore.contains("Price $1,000"), "price lore: " + lore);
        e2e.expect(lore.stream().anyMatch(line -> line.startsWith("Ends in 1d")), "time left lore: " + lore);
        e2e.expect(lore.contains("Click to buy"), "buy hint: " + lore);

        e2e.step("buying asks first, then moves money" + (taxed ? ", tax" : "") + " and the item together");
        buyer.clearLogs();
        seller.clearLogs();
        buyer.clickSlot(slot);
        Bot.SeenDialog purchase = e2e.dialog(buyer, "Buy item");
        e2e.expect(purchase.bodyText().contains("Buy 10 Diamond from " + sellerName + " for $1,000?")
            && purchase.bodyText().contains("You have ") && purchase.bodyText().contains("$5,000"), "purchase details: " + purchase.body());
        String buyTip = purchase.button("Buy").tooltip();
        e2e.expect(buyTip != null && buyTip.contains("Pay the seller now.") && buyTip.contains("The listing ends in 1d"),
            "the time left in the Buy tooltip: " + buyTip);
        e2e.expect(e2e.money(buyerName) == 5_000, "nothing paid before confirming");
        e2e.click(buyer, "Buy");
        long earned = taxed ? 950 : 1_000;
        e2e.eventually(() -> e2e.money(buyerName) == 4_000, "the buyer paid $1,000: " + e2e.money(buyerName));
        e2e.eventually(() -> e2e.money(sellerName) == earned, "the seller got $" + earned + ": " + e2e.money(sellerName));
        e2e.eventually(() -> count(e2e, buyerName, Material.DIAMOND) == 10, "the diamonds went straight into the buyer's inventory");
        e2e.eventually(() -> buyer.chatContains("You bought 10 Diamond from " + sellerName + " for $1,000."), "receipt: " + buyer.chat());
        if (taxed) {
            e2e.eventually(() -> seller.chatContains(buyerName + " bought your 10 Diamond for $1,000. You got $950 after tax."),
                "seller notice: " + seller.chat());
        } else {
            e2e.eventually(() -> seller.chatContains(buyerName + " bought your 10 Diamond for $1,000."), "seller notice: " + seller.chat());
            e2e.expect(seller.chat().stream().noneMatch(line -> line.contains("tax")), "no word about tax: " + seller.chat());
        }
        e2e.expect("SOLD".equals(state(e2e, id)), "the row is SOLD");
        String stored = query(e2e, true, "SELECT buyer, tax, closed_at FROM auction_listings WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) + "/" + rs.getLong(2) + "/" + (rs.getLong(3) > 0) : "", id);
        e2e.expect(stored.equals(e2e.uuid(buyerName) + "/" + (taxed ? 50 : 0) + "/true"), "buyer, tax and close time stored: " + stored);
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM deliveries WHERE ref = ? AND claimed IS NOT NULL", "listing:" + id) == 1,
            "the delivery was created and claimed");
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM ledger WHERE ref = ? AND kind = 'ah_sale'", "listing:" + id) == 2,
            "the sale is one transfer");
        e2e.expect(number(e2e, "SELECT COALESCE(-SUM(delta), 0) FROM ledger WHERE ref = ? AND kind = 'ah_tax'", "listing:" + id)
            == (taxed ? 50 : 0), taxed ? "the tax was sunk" : "no tax row");

        e2e.step("the menu came back without the sold listing");
        e2e.eventually(() -> buyer.screen() != null && buyer.screen().title().contains(TITLE)
            && slotsWith(buyer, "Seller " + sellerName).isEmpty(), "the listing is gone from the menu");
        ledgerHealthy(e2e);
    }

    static void sellForm(E2E e2e) {
        String sellerName = e2e.name("AhForm");
        Bot seller = e2e.bot(sellerName);
        hold(e2e, sellerName, Material.IRON_INGOT, 32);
        openMenu(e2e, seller, "ah", TITLE);

        e2e.step("the sell button opens the form for the held item");
        seller.clearLogs();
        seller.clickSlot(52);
        Bot.SeenDialog form = e2e.dialog(seller, "Sell an item");
        e2e.expect(form.inputs().containsKey("price") && "range".equals(form.inputs().get("amount")), "price and amount inputs: " + form.inputs());
        e2e.expect(form.body().isEmpty(), "the held item and the inputs, no text above them: " + form.body());
        e2e.expect(form.button("Next").tooltip() != null, "Next says what it does in its tooltip");

        e2e.step("an invalid price shows an error and keeps the form");
        e2e.click(seller, "Next", Map.of("price", "abc", "amount", "16"));
        Bot.SeenDialog retry = e2e.dialog(seller, "Sell an item");
        e2e.expect(retry.bodyText().contains("not an amount"), "the error: " + retry.body());

        e2e.step("a valid price leads to the confirmation, then lists and goes back to the auction house");
        e2e.click(seller, "Next", Map.of("price", "2k", "amount", "16"));
        Bot.SeenDialog confirm = e2e.dialog(seller, "List item");
        e2e.expect(confirm.bodyText().contains("List 16 Iron Ingot for $2,000?"), "the confirmation: " + confirm.body());
        Bot.Screen before = seller.screen();
        e2e.click(seller, "List it");
        e2e.eventually(() -> count(e2e, sellerName, Material.IRON_INGOT) == 16, "16 ingots listed, 16 kept");
        awaitScreen(e2e, seller, before, TITLE);
        long id = latestListing(e2e, e2e.uuid(sellerName));
        String row = query(e2e, true, "SELECT state, amount, price, category FROM auction_listings WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) + "/" + rs.getInt(2) + "/" + rs.getLong(3) + "/" + rs.getString(4) : "", id);
        e2e.expect(row.equals("ACTIVE/16/2000/misc"), "the stored listing: " + row);
        e2e.eventually(() -> !slotsWith(seller, "Your listing. Click to take it down.").isEmpty(),
            "the new listing shows up in the open menu once it is stored");
        e2e.console("ah admin remove " + id);
        e2e.eventually(() -> "CANCELLED".equals(state(e2e, id)), "cleaned up");
    }

    static void takeDown(E2E e2e) {
        String sellerName = e2e.name("AhTaker");
        Bot seller = e2e.bot(sellerName);
        UUID uuid = e2e.uuid(sellerName);
        hold(e2e, sellerName, Material.DIAMOND_SWORD, 1);
        long id = sell(e2e, seller, "500", 0);
        e2e.expect(count(e2e, sellerName, Material.DIAMOND_SWORD) == 0, "the sword is listed");

        e2e.step("keeping it up changes nothing");
        openMenu(e2e, seller, "ah listings", "Your listings");
        seller.clickSlot(slotWith(e2e, seller, "Click to take it down"));
        Bot.SeenDialog keep = e2e.dialog(seller, "Take down listing");
        e2e.expect(keep.bodyText().equals("Take down your 1 Diamond Sword listed for $500?"), "the question only: " + keep.body());
        e2e.expect("The item comes back to you.".equals(keep.button("Take it down").tooltip()), "what happens, in the tooltip: "
            + keep.button("Take it down").tooltip());
        Bot.Screen before = seller.screen();
        e2e.click(seller, "Keep it up");
        awaitScreen(e2e, seller, before, "Your listings");
        e2e.expect("ACTIVE".equals(state(e2e, id)), "still listed");

        e2e.step("taking it down returns the item to the inventory");
        seller.clearLogs();
        seller.clickSlot(slotWith(e2e, seller, "Click to take it down"));
        e2e.dialog(seller, "Take down listing");
        e2e.click(seller, "Take it down");
        e2e.eventually(() -> count(e2e, sellerName, Material.DIAMOND_SWORD) == 1, "the sword is back");
        e2e.eventually(() -> seller.actionBarContains("Listing taken down. The item is back in your inventory."),
            "feedback: " + seller.actionBar());
        e2e.expect("CANCELLED".equals(state(e2e, id)), "the row is CANCELLED");
        e2e.expect(claimBox(e2e, uuid) == 0, "nothing waits in the claim box");
        e2e.eventually(() -> seller.screen() != null && seller.screen().title().contains("Your listings")
            && slotsWith(seller, "Click to take it down").isEmpty(), "the listings menu is empty again");
    }

    static void claimBox(E2E e2e) throws Exception {
        String sellerName = e2e.name("AhBoxSell");
        String buyerName = e2e.name("AhBoxBuy");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        UUID buyerId = e2e.uuid(buyerName);
        fund(e2e, buyerName, 10_000);
        hold(e2e, sellerName, Material.DIAMOND, 10);
        sell(e2e, seller, "1000", 0);

        e2e.step("a purchase that does not fit waits in the claim box");
        e2e.onPlayer(buyerName, () -> {
            Player player = e2e.player(buyerName);
            for (int i = 0; i < 36; i++) {
                player.getInventory().setItem(i, ItemStack.of(Material.STONE, 64));
            }
            return null;
        });
        buy(e2e, buyer, sellerName);
        e2e.eventually(() -> buyer.chatContains("You bought 10 Diamond from " + sellerName + " for $1,000. It's waiting in your claim box (/claims)."),
            "claim box receipt: " + buyer.chat());
        e2e.eventually(() -> claimBox(e2e, buyerId) == 1, "one stack in the claim box");
        e2e.expect(count(e2e, buyerName, Material.DIAMOND) == 0, "nothing was dropped or forced into the inventory");

        e2e.step("players are reminded when they join");
        e2e.console("kick " + buyerName);
        e2e.eventually(() -> Bukkit.getPlayerExact(buyerName) == null, buyerName + " left");
        Bot back = e2e.bot(buyerName);
        e2e.eventually(() -> back.chatContains("You have 1 waiting in your claim box. Click to open it, or use /claims."), "join reminder: " + back.chat());

        e2e.step("claiming needs room");
        openMenu(e2e, back, "ah claims", "Claim box");
        int slot = slotWith(e2e, back, "From the auction house");
        back.clearLogs();
        back.clickSlot(slot);
        e2e.eventually(() -> back.actionBarContains("Your inventory is full."), "full inventory: " + back.actionBar());
        e2e.expect(claimBox(e2e, buyerId) == 1, "still in the claim box");
        e2e.onPlayer(buyerName, () -> {
            e2e.player(buyerName).getInventory().setItem(0, null);
            return null;
        });
        e2e.sleep(300);
        back.clearLogs();
        back.clickSlot(slotWith(e2e, back, "From the auction house"));
        e2e.eventually(() -> count(e2e, buyerName, Material.DIAMOND) == 10, "the diamonds were claimed");
        e2e.eventually(() -> back.actionBarContains("Claimed 10 Diamond."), "claim feedback: " + back.actionBar());
        e2e.expect(claimBox(e2e, buyerId) == 0, "the claim box is empty");

        e2e.step("claim all takes items from every source that fit");
        for (ItemStack item : List.of(ItemStack.of(Material.GOLD_INGOT, 5), ItemStack.of(Material.EMERALD, 3))) {
            TransactionResult result = e2e.services().deliveries().give(buyerId, "e2e_test", null, item, "console");
            e2e.expect(result.success(), "a delivery was added: " + result);
            result.committed().get(10, TimeUnit.SECONDS);
        }
        e2e.onPlayer(buyerName, () -> {
            e2e.player(buyerName).getInventory().setItem(1, null);
            e2e.player(buyerName).getInventory().setItem(2, null);
            return null;
        });
        back.closeScreen();
        openMenu(e2e, back, "ah claims", "Claim box");
        e2e.expect(slotsWith(back, "From e2e test").size() == 2, "deliveries from other sources are listed: " + slotsWith(back, "From"));
        e2e.expect(lore(back.screenItems().get(50)).contains("Items waiting 2"), "the claim all button: " + lore(back.screenItems().get(50)));
        back.clearLogs();
        back.clickSlot(50);
        e2e.eventually(() -> count(e2e, buyerName, Material.GOLD_INGOT) == 5 && count(e2e, buyerName, Material.EMERALD) == 3,
            "both stacks claimed");
        e2e.eventually(() -> back.actionBarContains("Claimed 2 stacks."), "claim all feedback: " + back.actionBar());
        e2e.expect(claimBox(e2e, buyerId) == 0, "the claim box is empty");
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM deliveries WHERE owner = ? AND claimed IS NULL", buyerId.toString()) == 0,
            "every delivery is marked claimed in storage");

        e2e.step("an outdated claim box menu cannot claim an item twice");
        TransactionResult extra = e2e.services().deliveries().give(buyerId, "e2e_test", null, ItemStack.of(Material.LAPIS_LAZULI, 7), "console");
        extra.committed().get(10, TimeUnit.SECONDS);
        back.closeScreen();
        openMenu(e2e, back, "ah claims", "Claim box");
        int stale = slotWith(e2e, back, "From e2e test");
        long deliveryId = e2e.services().deliveries().of(buyerId).getFirst().id();
        java.util.concurrent.CompletableFuture<Integer> elsewhere = new java.util.concurrent.CompletableFuture<>();
        e2e.services().deliveries().claim(buyerId, List.of(deliveryId), "console", items -> elsewhere.complete(items.size()),
            () -> elsewhere.complete(-1));
        e2e.expect(elsewhere.get(10, TimeUnit.SECONDS) == 1, "claimed behind the menu's back");
        back.clearLogs();
        back.clickSlot(stale);
        e2e.eventually(() -> slotsWith(back, "From e2e test").isEmpty(), "the menu redraws without the claimed item");
        e2e.sleep(500);
        e2e.expect(count(e2e, buyerName, Material.LAPIS_LAZULI) == 0, "the item was not handed out a second time");
        e2e.expect(!back.actionBarContains("Something went wrong"), "no error for an outdated menu: " + back.actionBar());
        e2e.expect(back.actionBarContains("Your claim box is empty."), "the claim box is empty: " + back.actionBar());
        ledgerHealthy(e2e);
    }

    static void refusals(E2E e2e) {
        String name = e2e.name("AhRefuse");
        String poorName = e2e.name("AhPoor");
        Bot bot = e2e.bot(name);
        Bot poor = e2e.bot(poorName);
        UUID uuid = e2e.uuid(name);

        e2e.step("nothing in hand");
        hold(e2e, name, null);
        bot.clearLogs();
        command(e2e, bot, "ah sell 100");
        e2e.eventually(() -> bot.actionBarContains("Hold the item you want to sell."), "empty hand: " + bot.actionBar());

        e2e.step("blacklisted items");
        for (Material material : List.of(Material.BARRIER, Material.ZOMBIE_SPAWN_EGG, Material.COMMAND_BLOCK)) {
            hold(e2e, name, material, 1);
            bot.clearLogs();
            command(e2e, bot, "ah sell 100");
            e2e.eventually(() -> bot.actionBarContains("That item can't be sold on the auction house."), material + " refused: " + bot.actionBar());
        }

        e2e.step("items carrying too much data");
        ItemStack heavy = ItemStack.of(Material.STICK);
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            lines.add(Component.text("x".repeat(600)));
        }
        heavy.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        hold(e2e, name, heavy);
        bot.clearLogs();
        command(e2e, bot, "ah sell 100");
        e2e.eventually(() -> bot.actionBarContains("That item holds too much data to be sold on the auction house."),
            "oversized item refused: " + bot.actionBar());

        e2e.step("prices and amounts outside the limits");
        hold(e2e, name, Material.DIRT, 64);
        bot.clearLogs();
        command(e2e, bot, "ah sell 10");
        e2e.eventually(() -> bot.actionBarContains("The price for 64 Dirt must be from $64 to $10,000,000,000."), "too cheap: " + bot.actionBar());
        bot.clearLogs();
        command(e2e, bot, "ah sell 20b");
        e2e.eventually(() -> bot.actionBarContains("must be from $64 to $10,000,000,000."), "too expensive: " + bot.actionBar());
        bot.clearLogs();
        command(e2e, bot, "ah sell abc");
        e2e.eventually(() -> bot.actionBarContains("abc is not an amount."), "garbage price: " + bot.actionBar());
        // Exponent notation is a few characters for a number with a hundred million digits: refused at once.
        long started = System.nanoTime();
        bot.clearLogs();
        command(e2e, bot, "ah sell 1e100000000");
        e2e.eventually(() -> bot.actionBarContains("The most you can use is"), "huge exponent refused: " + bot.actionBar());
        bot.clearLogs();
        command(e2e, bot, "ah sell 1e-100000000");
        e2e.eventually(() -> bot.actionBarContains("1e-100000000 is not a whole amount."), "tiny exponent refused: " + bot.actionBar());
        long tookMillis = (System.nanoTime() - started) / 1_000_000;
        e2e.expect(tookMillis < 5_000, "exponent prices answered in " + tookMillis + " ms");
        bot.clearLogs();
        command(e2e, bot, "ah sell 0");
        e2e.eventually(() -> bot.actionBarContains("The amount has to be more than zero."), "zero price: " + bot.actionBar());
        bot.clearLogs();
        command(e2e, bot, "ah sell 100 65");
        e2e.eventually(() -> bot.actionBarContains("You can sell 1 to 64 of that."), "too many: " + bot.actionBar());

        e2e.step("creative mode");
        e2e.onPlayer(name, () -> {
            e2e.player(name).setGameMode(GameMode.CREATIVE);
            return null;
        });
        bot.clearLogs();
        command(e2e, bot, "ah sell 100");
        e2e.eventually(() -> bot.actionBarContains("You can't sell items in creative mode."), "creative refused: " + bot.actionBar());
        e2e.onPlayer(name, () -> {
            e2e.player(name).setGameMode(GameMode.SURVIVAL);
            return null;
        });
        e2e.expect(count(e2e, name, Material.DIRT) == 64, "nothing was taken by refused listings");
        e2e.expect(latestListing(e2e, uuid) == 0, "no listing was created");

        e2e.step("listing slots");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(sell(e2e, bot, "100", 1));
        }
        bot.clearLogs();
        command(e2e, bot, "ah sell 100 1");
        e2e.eventually(() -> bot.actionBarContains("All 3 of your listing slots are in use."), "slots full: " + bot.actionBar());
        e2e.expect(count(e2e, name, Material.DIRT) == 61, "only the three listings took dirt");

        e2e.step("clicking your own listing offers to take it down instead of buying it");
        openMenu(e2e, bot, "ah", TITLE);
        int own = slotWith(e2e, bot, "Seller " + name);
        e2e.expect(lore(bot.screenItems().get(own)).contains("Your listing. Click to take it down."), "own listing hint");
        bot.clickSlot(own);
        e2e.dialog(bot, "Take down listing");
        e2e.click(bot, "Keep it up");

        e2e.step("buying without the money changes nothing");
        openMenu(e2e, poor, "ah", TITLE);
        poor.clearLogs();
        poor.clickSlot(slotWith(e2e, poor, "Seller " + name));
        e2e.dialog(poor, "Buy item");
        e2e.click(poor, "Buy");
        e2e.eventually(() -> poor.actionBarContains("You need $100 for that."), "not enough money: " + poor.actionBar());
        for (long id : ids) {
            e2e.expect("ACTIVE".equals(state(e2e, id)), "listing " + id + " is still up");
        }
        e2e.expect(count(e2e, poorName, Material.DIRT) == 0, "nothing was handed out");

        for (long id : ids) {
            e2e.console("ah admin remove " + id);
        }
        e2e.eventually(() -> claimBox(e2e, uuid) == 3, "the removed listings went to the claim box");
    }

    static void doubleSubmit(E2E e2e) {
        String sellerName = e2e.name("AhTwice");
        String buyerName = e2e.name("AhTwiceB");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        fund(e2e, buyerName, 1_000);
        hold(e2e, sellerName, Material.DIAMOND, 5);

        e2e.step("clicking the listing confirmation three times lists once");
        seller.clearLogs();
        command(e2e, seller, "ah sell 100 1");
        e2e.dialog(seller, "List item");
        seller.clickButton("List it", Map.of());
        seller.clickButton("List it", Map.of());
        seller.clickButton("List it", Map.of());
        e2e.eventually(() -> count(e2e, sellerName, Material.DIAMOND) == 4, "one diamond listed");
        e2e.sleep(1_000);
        e2e.expect(count(e2e, sellerName, Material.DIAMOND) == 4, "replays listed nothing more");
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM auction_listings WHERE seller = ?", e2e.uuid(sellerName).toString()) == 1,
            "exactly one listing");

        e2e.step("clicking buy three times buys once and replaying the token does nothing");
        openMenu(e2e, buyer, "ah", TITLE);
        buyer.clickSlot(slotWith(e2e, buyer, "Seller " + sellerName));
        Bot.SeenDialog confirm = e2e.dialog(buyer, "Buy item");
        String actionId = confirm.button("Buy").actionId();
        buyer.clickButton("Buy", Map.of());
        buyer.clickButton("Buy", Map.of());
        buyer.clickButton("Buy", Map.of());
        e2e.eventually(() -> count(e2e, buyerName, Material.DIAMOND) == 1, "bought once");
        buyer.rawClick(actionId, new CompoundTag());
        e2e.sleep(1_000);
        e2e.expect(e2e.money(buyerName) == 900, "paid exactly once: " + e2e.money(buyerName));
        e2e.expect(count(e2e, buyerName, Material.DIAMOND) == 1, "received exactly one diamond");
        e2e.expect(e2e.money(sellerName) == 100, "the seller was paid once, in full (no tax): " + e2e.money(sellerName));
    }

    static void buyRace(E2E e2e) throws Exception {
        String sellerName = e2e.name("AhRaceS");
        String firstName = e2e.name("AhRaceA");
        String secondName = e2e.name("AhRaceB");
        Bot seller = e2e.bot(sellerName);
        Bot first = e2e.bot(firstName);
        Bot second = e2e.bot(secondName);
        fund(e2e, firstName, 1_000);
        fund(e2e, secondName, 1_000);
        hold(e2e, sellerName, Material.NETHERITE_INGOT, 1);
        long id = sell(e2e, seller, "100", 0);

        e2e.step("two buyers confirm the same listing at the same moment");
        for (Bot bot : List.of(first, second)) {
            openMenu(e2e, bot, "ah", TITLE);
            bot.clearLogs();
            bot.clickSlot(slotWith(e2e, bot, "Seller " + sellerName));
            e2e.dialog(bot, "Buy item");
        }
        first.clickButton("Buy", Map.of());
        second.clickButton("Buy", Map.of());
        e2e.eventually(() -> count(e2e, firstName, Material.NETHERITE_INGOT) + count(e2e, secondName, Material.NETHERITE_INGOT) == 1,
            "one buyer got the ingot");
        e2e.sleep(1_000);
        boolean firstWon = count(e2e, firstName, Material.NETHERITE_INGOT) == 1;
        Bot loser = firstWon ? second : first;
        String winnerName = firstWon ? firstName : secondName;
        String loserName = firstWon ? secondName : firstName;
        e2e.expect(count(e2e, firstName, Material.NETHERITE_INGOT) + count(e2e, secondName, Material.NETHERITE_INGOT) == 1,
            "exactly one ingot exists");
        e2e.expect(e2e.money(winnerName) == 900 && e2e.money(loserName) == 1_000, "only the winner paid: "
            + e2e.money(winnerName) + " / " + e2e.money(loserName));
        e2e.expect(e2e.money(sellerName) == 100, "the seller was paid once, in full (no tax): " + e2e.money(sellerName));
        e2e.eventually(() -> loser.actionBarContains("That listing is gone."), "the other buyer is told: " + loser.actionBar());
        e2e.expect("SOLD".equals(state(e2e, id)), "the row is SOLD once");
        ledgerHealthy(e2e);
    }

    static void browse(E2E e2e) {
        String sellerName = e2e.name("AhShop");
        String viewerName = e2e.name("AhLook");
        Bot seller = e2e.bot(sellerName);
        Bot viewer = e2e.bot(viewerName);
        List<Long> ids = new ArrayList<>();
        hold(e2e, sellerName, Material.COBBLESTONE, 1);
        ids.add(sell(e2e, seller, "300", 0));
        e2e.sleep(50);
        // A sword whose seller hid its enchantments from the tooltip.
        ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
        sword.setData(DataComponentTypes.TOOLTIP_DISPLAY, io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.ENCHANTMENTS).build());
        hold(e2e, sellerName, sword);
        ids.add(sell(e2e, seller, "100", 0));
        e2e.sleep(50);
        hold(e2e, sellerName, Material.BREAD, 1);
        ids.add(sell(e2e, seller, "200", 0));

        e2e.step("newest first by default");
        openMenu(e2e, viewer, "ah", TITLE);
        List<Material> newest = List.of(Material.BREAD, Material.DIAMOND_SWORD, Material.COBBLESTONE);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(newest), "newest first: " + listingsOf(viewer, sellerName));

        e2e.step("buyers see what the seller hid from the tooltip");
        ItemStack shown = null;
        for (var entry : viewer.screenItems().entrySet()) {
            ItemStack item = CraftItemStack.asBukkitCopy(entry.getValue());
            if (entry.getKey() < 45 && item.getType() == Material.DIAMOND_SWORD && lore(entry.getValue()).contains("Seller " + sellerName)) {
                shown = item;
            }
        }
        e2e.expect(shown != null, "the sword is shown");
        var display = shown.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        e2e.expect(display == null || !display.hiddenComponents().contains(DataComponentTypes.ENCHANTMENTS),
            "the enchantments are not hidden in the menu: " + display);
        e2e.expect(shown.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS) == 5, "the real enchantment is shown");

        e2e.step("sort by lowest and highest price");
        viewer.clickSlot(47);
        e2e.sleep(400);
        viewer.clickSlot(47);
        List<Material> cheapest = List.of(Material.DIAMOND_SWORD, Material.BREAD, Material.COBBLESTONE);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(cheapest), "lowest price first: " + listingsOf(viewer, sellerName));
        e2e.expect(lore(viewer.screenItems().get(47)).contains("• Lowest price"), "the sort button lists the options: "
            + lore(viewer.screenItems().get(47)));
        e2e.sleep(400);
        viewer.clickSlot(47);
        List<Material> dearest = List.of(Material.COBBLESTONE, Material.BREAD, Material.DIAMOND_SWORD);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(dearest), "highest price first: " + listingsOf(viewer, sellerName));

        e2e.step("filter by category");
        e2e.sleep(400);
        viewer.clickSlot(48);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(List.of(Material.COBBLESTONE)), "blocks only: " + listingsOf(viewer, sellerName));
        e2e.sleep(400);
        viewer.clickSlot(48);
        e2e.eventually(() -> listingsOf(viewer, sellerName).isEmpty(), "no tools: " + listingsOf(viewer, sellerName));
        e2e.sleep(400);
        viewer.clickSlot(48);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(List.of(Material.DIAMOND_SWORD)), "combat only: " + listingsOf(viewer, sellerName));
        e2e.sleep(400);
        viewer.clickSlot(48);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(List.of(Material.BREAD)), "food only: " + listingsOf(viewer, sellerName));
        for (int i = 0; i < 4; i++) {
            e2e.sleep(400);
            viewer.clickSlot(48, 1, ContainerInput.PICKUP);
        }
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(dearest), "right clicks went back to all items: " + listingsOf(viewer, sellerName));

        e2e.step("search by name, item id and enchantment");
        openMenu(e2e, viewer, "ah search sword", TITLE);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(List.of(Material.DIAMOND_SWORD)), "only the sword: " + listingsOf(viewer, sellerName));
        e2e.expect(lore(viewer.screenItems().get(49)).stream().anyMatch(line -> line.contains("Showing results for sword")),
            "the search button shows the query: " + lore(viewer.screenItems().get(49)));
        e2e.sleep(400);
        viewer.clickSlot(49, 1, ContainerInput.PICKUP);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(dearest), "right click cleared the search: " + listingsOf(viewer, sellerName));
        openMenu(e2e, viewer, "ah search sharpness", TITLE);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(List.of(Material.DIAMOND_SWORD)), "found by enchantment: "
            + listingsOf(viewer, sellerName));
        openMenu(e2e, viewer, "ah search cobblestone", TITLE);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(List.of(Material.COBBLESTONE)), "found by item name: "
            + listingsOf(viewer, sellerName));
        e2e.sleep(400);
        viewer.clickSlot(49, 1, ContainerInput.PICKUP);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(dearest), "search cleared: " + listingsOf(viewer, sellerName));

        e2e.step("the sort order is remembered");
        viewer.closeScreen();
        e2e.sleep(300);
        openMenu(e2e, viewer, "ah", TITLE);
        e2e.eventually(() -> listingsOf(viewer, sellerName).equals(dearest), "still highest price first: " + listingsOf(viewer, sellerName));

        for (long id : ids) {
            e2e.console("ah admin remove " + id);
        }
        UUID sellerId = e2e.uuid(sellerName);
        e2e.eventually(() -> claimBox(e2e, sellerId) == 3, "cleaned up");
        boolean stillHidden = e2e.services().deliveries().of(sellerId).stream()
            .map(delivery -> delivery.item())
            .filter(item -> item.getType() == Material.DIAMOND_SWORD)
            .anyMatch(item -> item.hasData(DataComponentTypes.TOOLTIP_DISPLAY)
                && item.getData(DataComponentTypes.TOOLTIP_DISPLAY).hiddenComponents().contains(DataComponentTypes.ENCHANTMENTS));
        e2e.expect(stillHidden, "only the shown copy was changed: the seller's real item comes back exactly as it was");
    }

    static void history(E2E e2e) {
        String sellerName = e2e.name("AhPast");
        String buyerName = e2e.name("AhPastB");
        Bot seller = e2e.bot(sellerName);
        Bot buyer = e2e.bot(buyerName);
        fund(e2e, buyerName, 1_000);

        e2e.step("an empty history");
        seller.clearLogs();
        command(e2e, seller, "ah history");
        Bot.SeenDialog empty = e2e.dialog(seller, "Auction history");
        e2e.expect(empty.bodyText().contains("You haven't sold or bought anything yet."), "empty history: " + empty.body());
        e2e.click(seller, "Close");

        e2e.step("a sale shows up for both players");
        hold(e2e, sellerName, Material.EMERALD, 1);
        sell(e2e, seller, "400", 0);
        buy(e2e, buyer, sellerName);
        e2e.eventually(() -> count(e2e, buyerName, Material.EMERALD) == 1, "bought");
        seller.clearLogs();
        command(e2e, seller, "ah history");
        Bot.SeenDialog sold = e2e.dialog(seller, "Auction history");
        e2e.expect(sold.bodyText().contains("Your last 20 sales and purchases"), "the header names the cap: " + sold.body());
        e2e.expect(sold.bodyText().contains("Sold 1 Emerald to " + buyerName + " for $400"), "the sale: " + sold.body());
        e2e.click(seller, "Close");
        buyer.closeScreen();
        buyer.clearLogs();
        command(e2e, buyer, "ah history");
        Bot.SeenDialog bought = e2e.dialog(buyer, "Auction history");
        e2e.expect(bought.bodyText().contains("Bought 1 Emerald from " + sellerName + " for $400"), "the purchase: " + bought.body());
        e2e.click(buyer, "Close");

        e2e.step("the history button in your listings goes back to the menu");
        openMenu(e2e, seller, "ah listings", "Your listings");
        int seen = seller.dialogs().size();
        seller.clickSlot(50);
        newDialog(e2e, seller, seen, "Auction history");
        Bot.Screen before = seller.screen();
        e2e.expect(seller.clickButton("Back", Map.of()), "a back button");
        awaitScreen(e2e, seller, before, "Your listings");
    }

    static void staff(E2E e2e) {
        String sellerName = e2e.name("AhRuled");
        String staffName = e2e.name("AhStaff");
        Bot seller = e2e.bot(sellerName);
        Bot staff = e2e.bot(staffName);
        UUID sellerId = e2e.uuid(sellerName);
        hold(e2e, sellerName, Material.DIAMOND, 2);
        long first = sell(e2e, seller, "100", 1);
        long second = sell(e2e, seller, "100", 1);

        e2e.step("console tools");
        e2e.console("ah admin info");
        e2e.console("ah admin list " + sellerName);
        e2e.console("ah admin remove " + first);
        e2e.eventually(() -> "CANCELLED".equals(state(e2e, first)), "removed from the console");
        e2e.eventually(() -> claimBox(e2e, sellerId) == 1, "the item went to the seller's claim box");
        e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM audit_log WHERE action = 'auction.remove' AND target = ?",
            Long.toString(first)) == 1, "the removal is audited");

        e2e.step("staff shift right click a listing to remove it");
        e2e.console("op " + staffName);
        try {
            openMenu(e2e, staff, "ah", TITLE);
            int slot = slotWith(e2e, staff, "Seller " + sellerName);
            e2e.expect(lore(staff.screenItems().get(slot)).stream().anyMatch(line -> line.contains("Shift right click to remove it.")),
                "the staff hint: " + lore(staff.screenItems().get(slot)));
            staff.clickSlot(slot, 1, ContainerInput.QUICK_MOVE);
            Bot.SeenDialog confirm = e2e.dialog(staff, "Remove listing");
            e2e.expect(confirm.bodyText().contains("Remove " + sellerName + "'s 1 Diamond listed for $100?"), "the confirmation: " + confirm.body());
            e2e.expect("The item goes to their claim box.".equals(confirm.button("Remove").tooltip()),
                "where it goes, in the tooltip: " + confirm.button("Remove").tooltip());
            e2e.click(staff, "Remove");
            e2e.eventually(() -> "CANCELLED".equals(state(e2e, second)), "removed by staff");
            e2e.eventually(() -> claimBox(e2e, sellerId) == 2, "the item went to the seller's claim box");
        } finally {
            e2e.console("deop " + staffName);
        }

        e2e.step("the seller can take both back");
        openMenu(e2e, seller, "ah claims", "Claim box");
        seller.clickSlot(50);
        e2e.eventually(() -> count(e2e, sellerName, Material.DIAMOND) == 2, "both diamonds are back");
    }

    static void hub(E2E e2e) {
        String name = e2e.name("AhHub");
        Bot bot = e2e.bot(name);
        e2e.step("the main menu has an auction house button");
        command(e2e, bot, "menu");
        Bot.SeenDialog menu = e2e.dialog(bot, "SiftVanilla");
        e2e.expect(menu.button("Auction house") != null, "an Auction house button in " + menu.buttons());
        Bot.Screen before = bot.screen();
        e2e.expect(bot.clickButton("Auction house", Map.of()), "can click it");
        awaitScreen(e2e, bot, before, TITLE);

        e2e.step("back goes to the main menu");
        int seen = bot.dialogs().size();
        bot.clickSlot(46);
        newDialog(e2e, bot, seen, "SiftVanilla");

        e2e.step("the pause menu route opens it too");
        bot.closeScreen();
        Bot.Screen closed = bot.screen();
        bot.rawClick("siftcore:hub/auction", null);
        awaitScreen(e2e, bot, closed, TITLE);
    }

    /**
     * The claim box has its own command and main menu button, independent of /ah (which AxAuctions takes over): items
     * from any feature can be claimed there, its Back goes to the main menu, and the join reminder runs /claims.
     */
    static void claimsCommand(E2E e2e) throws Exception {
        String name = e2e.name("AhClaims");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        e2e.expect(claimLabels(e2e).containsAll(List.of("claims", "claimbox")),
            "/claims and /claimbox are registered on their own: " + claimLabels(e2e));
        TransactionResult given = e2e.services().deliveries().give(id, "shop", null, ItemStack.of(Material.EMERALD, 7), "console");
        e2e.expect(given.success(), "a shop delivery was added: " + given);
        given.committed().get(10, TimeUnit.SECONDS);

        e2e.step("the main menu has a claim box button that opens it");
        command(e2e, bot, "menu");
        Bot.SeenDialog menu = e2e.dialog(bot, "SiftVanilla");
        e2e.expect(menu.button("Claim box") != null, "a Claim box button in " + menu.buttons());
        Bot.Screen before = bot.screen();
        e2e.expect(bot.clickButton("Claim box", Map.of()), "can click it");
        awaitScreen(e2e, bot, before, "Claim box");
        slotWith(e2e, bot, "From shop");

        e2e.step("its back button returns to the main menu");
        int seen = bot.dialogs().size();
        bot.clickSlot(46);
        newDialog(e2e, bot, seen, "SiftVanilla");

        e2e.step("/claims opens it and the item can be claimed");
        bot.closeScreen();
        openMenu(e2e, bot, "claims", "Claim box");
        bot.clearLogs();
        bot.clickSlot(slotWith(e2e, bot, "From shop"));
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 7, "the emeralds were claimed");
        e2e.eventually(() -> claimBox(e2e, id) == 0, "the claim box is empty");

        e2e.step("the join reminder opens it with /claims");
        TransactionResult again = e2e.services().deliveries().give(id, "orders", null, ItemStack.of(Material.GOLD_INGOT, 2), "console");
        again.committed().get(10, TimeUnit.SECONDS);
        bot.closeScreen();
        e2e.console("kick " + name);
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        Bot back = e2e.bot(name);
        e2e.eventually(() -> back.chatContains("You have 1 waiting in your claim box."), "join reminder: " + back.chat());
        e2e.expect(back.chatComponents().stream().anyMatch(line -> runsCommand(line, "/claims")),
            "the reminder's click runs /claims: " + back.chatComponents());
        openMenu(e2e, back, "claimbox", "Claim box");
        slotWith(e2e, back, "From orders");
    }

    private static List<String> claimLabels(E2E e2e) {
        List<String> labels = new ArrayList<>();
        for (var command : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
            if (command.getKey().equals("claims") || command.getKey().equals("claimbox")) {
                labels.add(command.getKey());
            }
        }
        return labels;
    }

    /** Whether the chat line (or a part of it) runs this command when clicked. */
    private static boolean runsCommand(net.minecraft.network.chat.Component line, String command) {
        if (line.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run
            && run.command().equals(command)) {
            return true;
        }
        for (net.minecraft.network.chat.Component part : line.getSiblings()) {
            if (runsCommand(part, command)) {
                return true;
            }
        }
        return false;
    }

    /** Leaves an active listing and an unclaimed claim box item behind for {@link #persistCheck} (after a restart). */
    static void persistSetup(E2E e2e) {
        String sellerName = e2e.name("AhKeeper");
        Bot seller = e2e.bot(sellerName);
        ItemStack marker = ItemStack.of(Material.GOLDEN_APPLE, 2);
        marker.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Persist marker"));
        hold(e2e, sellerName, marker);
        long kept = sell(e2e, seller, "777", 0);
        ItemStack tag = ItemStack.of(Material.NAME_TAG, 1);
        tag.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Persist claim"));
        hold(e2e, sellerName, tag);
        long returned = sell(e2e, seller, "50", 0);
        e2e.console("ah admin remove " + returned);
        e2e.eventually(() -> claimBox(e2e, e2e.uuid(sellerName)) == 1, "a claim box item waits");
        e2e.log("left listing #" + kept + " and a claim box item for " + sellerName);
    }

    /** Finds what {@link #persistSetup} left (possibly before a restart), buys the listing and claims the item. */
    static void persistCheck(E2E e2e) {
        long id = number(e2e, "SELECT COALESCE(MAX(id), 0) FROM auction_listings WHERE state = 'ACTIVE' AND search_name LIKE ?",
            "persist marker%");
        e2e.expect(id > 0, "an active persist marker listing exists (run auction-persist-setup first)");
        String sellerId = query(e2e, true, "SELECT seller FROM auction_listings WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        String sellerName = query(e2e, true, "SELECT name FROM players WHERE uuid = ?", rs -> rs.next() ? rs.getString(1) : null, sellerId);
        e2e.log("checking listing #" + id + " of " + sellerName);

        e2e.step("the listing is live: it can be found and bought");
        String buyerName = e2e.name("AhHeir");
        Bot buyer = e2e.bot(buyerName);
        fund(e2e, buyerName, 1_000);
        openMenu(e2e, buyer, "ah search persist marker", TITLE);
        int slot = slotWith(e2e, buyer, "Seller " + sellerName);
        buyer.clickSlot(slot);
        Bot.SeenDialog confirm = e2e.dialog(buyer, "Buy item");
        e2e.expect(confirm.bodyText().contains("Buy 2 Persist marker from " + sellerName + " for $777?"), "the persisted item, seller and price: "
            + confirm.body());
        e2e.click(buyer, "Buy");
        e2e.eventually(() -> count(e2e, buyerName, Material.GOLDEN_APPLE) == 2, "the golden apples arrived");
        e2e.expect("SOLD".equals(state(e2e, id)), "sold");
        String name = e2e.onPlayer(buyerName, () -> {
            for (ItemStack stack : e2e.player(buyerName).getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == Material.GOLDEN_APPLE) {
                    return plain(stack.effectiveName());
                }
            }
            return "";
        });
        e2e.expect(name.contains("Persist marker"), "the item kept its name through storage: " + name);

        e2e.step("the seller's claim box item is still there");
        Bot seller = e2e.bot(sellerName);
        e2e.eventually(() -> seller.chatContains("waiting in your claim box"), "join reminder: " + seller.chat());
        hold(e2e, sellerName, null);
        openMenu(e2e, seller, "ah claims", "Claim box");
        seller.clickSlot(slotWith(e2e, seller, "From the auction house"));
        e2e.eventually(() -> count(e2e, sellerName, Material.NAME_TAG) == 1, "the name tag was claimed");
    }

    /** Shortens the listing time through the config, waits for the real expiry timer, and puts the config back. */
    static void expiry(E2E e2e) throws Exception {
        Path file = e2e.services().plugin().getDataFolder().toPath().resolve("features/auction.yml");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        String quick = original.replace("duration: 48h", "duration: 1m").replace("expiry-check: 30s", "expiry-check: 5s");
        e2e.expect(!quick.equals(original), "features/auction.yml has the default duration and expiry check");
        String sellerName = e2e.name("AhExpire");
        Bot seller = e2e.bot(sellerName);
        UUID sellerId = e2e.uuid(sellerName);
        try {
            Files.writeString(file, quick, StandardCharsets.UTF_8);
            e2e.console("sift reload");
            hold(e2e, sellerName, Material.DIAMOND, 3);
            long id = sell(e2e, seller, "300", 0);
            e2e.expect(query(e2e, true, "SELECT expires - created FROM auction_listings WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, id) == 60_000, "a one minute listing");

            e2e.step("the expiry timer returns it to the claim box");
            seller.clearLogs();
            e2e.eventually(() -> "EXPIRED".equals(query(e2e, false, "SELECT state FROM auction_listings WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id)), 100_000, "expired within 100 seconds");
            e2e.eventually(() -> seller.chatContains("Your listing of 3 Diamond expired. It's waiting in your claim box (/claims)."),
                "the seller is told: " + seller.chat());
            e2e.expect(claimBox(e2e, sellerId) == 1, "one stack in the claim box");

            e2e.step("and it can be claimed");
            openMenu(e2e, seller, "ah claims", "Claim box");
            seller.clickSlot(slotWith(e2e, seller, "From the auction house"));
            e2e.eventually(() -> count(e2e, sellerName, Material.DIAMOND) == 3, "the diamonds are back");
        } finally {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }
}
