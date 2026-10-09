package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.siftvanilla.siftcore.api.economy.Currency;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Scenarios for selling (/sell, /sell hand, /sell all, /worth) and the shop (/shop). */
final class SellShopScenarios {

    /** Raw slot of player inventory slot 9 (first main slot) below a six-row chest. */
    private static final int BELOW_SIX_ROWS_MAIN = 54;
    /** Raw slot of hotbar slot 0 below a six-row chest. */
    private static final int BELOW_SIX_ROWS_HOTBAR = 81;

    private SellShopScenarios() {
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
        list.add(of("sell-hand", SellShopScenarios::sellHand));
        list.add(of("sell-refusals", SellShopScenarios::sellRefusals));
        list.add(of("sell-all", SellShopScenarios::sellAll));
        list.add(of("sell-bonus", SellShopScenarios::sellBonus));
        list.add(of("sell-menu", SellShopScenarios::sellMenu));
        list.add(of("sell-menu-quit", SellShopScenarios::sellMenuQuit));
        list.add(of("sell-menu-death", SellShopScenarios::sellMenuDeath));
        list.add(of("worth", SellShopScenarios::worth));
        list.add(of("shop-buy", SellShopScenarios::shopBuy));
        list.add(of("shop-amount", SellShopScenarios::shopAmount));
        list.add(of("shop-confirm", SellShopScenarios::shopConfirm));
        list.add(of("shop-refusals", SellShopScenarios::shopRefusals));
        list.add(of("shop-claim-box", SellShopScenarios::shopClaimBox));
        list.add(of("shop-double-submit", SellShopScenarios::shopDoubleSubmit));
        list.add(of("shop-left-before-commit", SellShopScenarios::shopLeftBeforeCommit));
        list.add(of("sell-mastery-back", SellShopScenarios::sellMasteryBack));
        list.add(of("sell-grid-copy", SellShopScenarios::sellGridCopy));
        list.add(of("sell-grid-crash", SellShopScenarios::sellGridCrash));
        list.add(of("sell-mastery-stays", SellShopScenarios::sellMasteryStays));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static void setSlot(E2E e2e, String name, int slot, ItemStack item) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setItem(slot, item);
            return null;
        });
    }

    private static void clear(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            return null;
        });
    }

    /** How many of a material the player holds anywhere in their inventory (incl. armor and off hand). */
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

    private static String slotName(Bot bot, int slot) {
        var stack = bot.screenItems().get(slot);
        return stack == null ? "" : stack.getHoverName().getString();
    }

    private static void openScreen(E2E e2e, Bot bot, String command, String title) {
        bot.command(command);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals(title),
            bot.name + " sees the '" + title + "' screen (now " + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(300);
    }

    // ------------------------------------------------------------------ selling

    static void sellHand(E2E e2e) {
        String name = e2e.name("SellHand");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        e2e.step("64 diamonds in the main hand sell for 64 x $400");
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.getInventory().setHeldItemSlot(0);
            player.getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND, 64));
            return null;
        });
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 25_600, "paid $25,600 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 64 diamond for $25,600."), "a receipt in chat: " + bot.chat());
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds are gone");
        e2e.step("the sale is in the ledger as a sell source");
        e2e.eventually(() -> {
            var rows = e2e.services().ledger().history(e2e.uuid(name), 5, 0).join();
            return !rows.isEmpty() && rows.getFirst().kind().equals("sell") && rows.getFirst().delta() == 25_600;
        }, "a 'sell' ledger row of +25,600");
    }

    static void sellRefusals(E2E e2e) {
        String name = e2e.name("SellNo");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        e2e.step("an empty hand sells nothing");
        clear(e2e, name);
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> bot.actionBarContains("Hold the item you want to sell."), "empty hand refused: " + bot.actionBar());
        e2e.step("a renamed diamond does not sell");
        ItemStack renamed = ItemStack.of(Material.DIAMOND, 5);
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Shiny"));
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setHeldItemSlot(0);
            e2e.player(name).getInventory().setItemInMainHand(renamed);
            return null;
        });
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> bot.actionBarContains("Renamed, enchanted or damaged items can't be sold."), "renamed refused: " + bot.actionBar());
        e2e.expect(count(e2e, name, Material.DIAMOND) == 5, "the renamed diamonds stay");
        e2e.step("an item worth less than a dollar does not sell");
        setSlot(e2e, name, 0, ItemStack.of(Material.STICK, 16));
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> bot.actionBarContains("You can't sell stick."), "stick refused: " + bot.actionBar());
        e2e.step("/sell all with nothing sellable");
        bot.clearLogs();
        bot.command("sell all");
        e2e.eventually(() -> bot.actionBarContains("You have nothing that can be sold."), "nothing to sell: " + bot.actionBar());
        e2e.expect(e2e.money(name) == 0, "no money moved: " + e2e.money(name));
        e2e.expect(count(e2e, name, Material.STICK) == 16, "the sticks stay");
    }

    static void sellAll(E2E e2e) {
        String name = e2e.name("SellAll");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        e2e.step("fill the inventory: sellables, a tool, junk, armor and the off hand");
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.clear();
            inventory.setItem(0, ItemStack.of(Material.IRON_INGOT, 32));
            inventory.setItem(5, ItemStack.of(Material.DIRT, 10));
            inventory.setItem(20, ItemStack.of(Material.IRON_INGOT, 8));
            inventory.setItem(2, ItemStack.of(Material.IRON_SWORD));
            inventory.setItem(3, ItemStack.of(Material.STICK, 5));
            inventory.setChestplate(ItemStack.of(Material.DIAMOND_CHESTPLATE));
            inventory.setItemInOffHand(ItemStack.of(Material.DIAMOND, 64));
            return null;
        });
        bot.clearLogs();
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) == 40 * 25 + 10, "paid 40 x $25 + 10 x $1 = $1,010 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 50 items for $1,010."), "a receipt for several kinds: " + bot.chat());
        e2e.expect(count(e2e, name, Material.IRON_INGOT) == 0 && count(e2e, name, Material.DIRT) == 0, "the sellables are gone");
        e2e.expect(count(e2e, name, Material.IRON_SWORD) == 1, "the unstackable sword stays");
        e2e.expect(count(e2e, name, Material.STICK) == 5, "the sticks stay");
        e2e.expect(count(e2e, name, Material.DIAMOND_CHESTPLATE) == 1, "the armor stays");
        e2e.expect(count(e2e, name, Material.DIAMOND) == 64, "the off hand stays");
    }

    /** Paid ranks never sell for more (store rules): old multiplier nodes pay the normal price. */
    static void sellBonus(E2E e2e) {
        String name = e2e.name("SellBonus");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        e2e.step("nodes of the removed sell tiers change nothing");
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.addAttachment(e2e.services().plugin(), "siftcore.sell.multiplier.legend", true);
            player.addAttachment(e2e.services().plugin(), "siftcore.sell.multiplier.tycoon", true);
            player.getInventory().setHeldItemSlot(0);
            player.getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND, 64));
            return null;
        });
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 25_600, "paid the normal $25,600 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 64 diamond for $25,600."), "a plain receipt: " + bot.chat());
        e2e.expect(!bot.chatContains("x bonus"), "no bonus mentioned: " + bot.chat());
        e2e.step("the placeholder shows no multiplier");
        String value = e2e.onPlayer(name, () -> e2e.services().placeholders().resolve(e2e.player(name), "sell_multiplier"));
        e2e.expect("1".equals(value), "sell_multiplier is 1, got " + value);
    }

    static void sellMenu(E2E e2e) {
        String name = e2e.name("SellMenu");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 64));
        setSlot(e2e, name, 0, ItemStack.of(Material.STICK, 7));
        openScreen(e2e, bot, "sell", "Sell items");
        e2e.expect(slotName(bot, 48).contains("Total $0"), "an empty total: " + slotName(bot, 48));
        e2e.step("shift-click diamonds and sticks into the grid");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.sleep(400);
        bot.clickSlot(BELOW_SIX_ROWS_HOTBAR, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $25,600"), "the live total: " + slotName(bot, 48));
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds left the inventory");
        e2e.step("Sell pays for the diamonds and keeps the sticks in the grid");
        bot.clearLogs();
        // Human pace: menus drop clicks closer together than the configured click interval.
        e2e.sleep(300);
        bot.clickSlot(50);
        e2e.eventually(() -> e2e.money(name) == 25_600, "paid $25,600 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 64 diamond for $25,600."), "a receipt: " + bot.chat());
        e2e.eventually(() -> slotName(bot, 48).contains("Total $0"), "the total resets: " + slotName(bot, 48));
        e2e.expect(bot.screen() != null, "the menu stays open");
        e2e.step("closing gives back what could not be sold, and sells nothing");
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.STICK) == 7, "the sticks are back");
        e2e.sleep(300);
        e2e.expect(e2e.money(name) == 25_600, "closing sold nothing");
        e2e.step("deposit and close without selling");
        setSlot(e2e, name, 9, ItemStack.of(Material.IRON_INGOT, 20));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $500"), "the total of 20 ingots: " + slotName(bot, 48));
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.IRON_INGOT) == 20, "the ingots are back");
        e2e.expect(e2e.money(name) == 25_600, "nothing was sold");
        e2e.step("the pause-menu route opens the sell menu");
        bot.rawClick("siftcore:hub/sell", null);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Sell items"), "the sell menu from the hub route");
        bot.closeScreen();
    }

    static void sellMenuQuit(E2E e2e) {
        String name = e2e.name("SellQuit");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.EMERALD, 30));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $7,500"), "30 emeralds in the grid: " + slotName(bot, 48));
        e2e.step("leave with the menu open, then come back");
        e2e.onPlayer(name, () -> {
            e2e.player(name).kick(Component.text("e2e: leaving with the sell menu open"));
            return null;
        });
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null && bot.disconnected(), "the bot left");
        e2e.sleep(500);
        e2e.bot(name);
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "the emeralds were given back and saved");
        e2e.expect(e2e.services().deliveries().count(e2e.uuid(name)) == 0, "nothing went to the claim box");
    }

    static void sellMenuDeath(E2E e2e) {
        String name = e2e.name("SellDeath");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.GOLD_INGOT, 40));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $1,400"), "40 gold in the grid: " + slotName(bot, 48));
        e2e.step("die with the menu open: the grid drops like the inventory");
        int claimBefore = e2e.services().deliveries().count(e2e.uuid(name));
        Location spawn = e2e.onPlayer(name, () -> e2e.player(name).getWorld().getSpawnLocation());
        // Gold already lying around (earlier runs on this world) is not part of this test.
        int lyingBefore = goldOnTheGround(e2e, spawn);
        e2e.onPlayer(name, () -> {
            e2e.player(name).setHealth(0);
            return null;
        });
        e2e.eventually(() -> bot.deaths() > 0, "the bot died");
        e2e.sleep(1500);
        int total = goldOnTheGround(e2e, spawn) + count(e2e, name, Material.GOLD_INGOT);
        e2e.expect(total - lyingBefore == 40, "exactly 40 more gold exists on the ground or back in the inventory, found "
            + (total - lyingBefore));
        e2e.expect(e2e.services().deliveries().count(e2e.uuid(name)) == claimBefore, "death did not move the grid to the claim box");
    }

    /** Gold ingots lying within 64 blocks of {@code center}, counted on the region thread that owns it. */
    private static int goldOnTheGround(E2E e2e, Location center) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(e2e.services().plugin(), center, task -> {
            int found = 0;
            for (Entity entity : center.getWorld().getNearbyEntities(center, 64, 64, 64)) {
                if (entity instanceof Item item && item.getItemStack().getType() == Material.GOLD_INGOT) {
                    found += item.getItemStack().getAmount();
                }
            }
            result.complete(found);
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("counting items near spawn failed: " + e);
        }
    }

    static void worth(E2E e2e) {
        String name = e2e.name("Worth");
        Bot bot = e2e.bot(name);
        bot.clearLogs();
        bot.command("worth diamond");
        e2e.eventually(() -> bot.chatContains("One diamond sells for $400."), "worth of a named item: " + bot.chat());
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setHeldItemSlot(0);
            e2e.player(name).getInventory().setItemInMainHand(ItemStack.of(Material.IRON_INGOT, 10));
            return null;
        });
        bot.clearLogs();
        bot.command("worth");
        e2e.eventually(() -> bot.chatContains("One iron ingot sells for $25, your 10 for $250."), "worth of the held stack: " + bot.chat());
        bot.clearLogs();
        bot.command("worth stick");
        e2e.eventually(() -> bot.chatContains("You can't sell stick, it's worth too little."), "worthless item: " + bot.chat());
    }

    // ------------------------------------------------------------------ shop

    /** Opens /shop, then the category in {@code slot}, then clicks the entry in {@code entrySlot}. */
    private static Bot.SeenDialog openEntry(E2E e2e, Bot bot, int slot, String category, int entrySlot, String dialogTitle) {
        openScreen(e2e, bot, "shop", "Shop");
        bot.clickSlot(slot);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals(category), "the " + category + " page");
        e2e.sleep(300);
        bot.clickSlot(entrySlot);
        return e2e.dialog(bot, dialogTitle);
    }

    static void shopBuy(E2E e2e) {
        String name = e2e.name("ShopBuy");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 1000");
        e2e.step("browse to stone and buy the default stack");
        bot.clearLogs();
        Bot.SeenDialog dialog = openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.expect(dialog.button("Buy 64 for $384") != null, "the buy button names amount and total: " + dialog.buttons());
        e2e.expect(dialog.bodyText().contains("$6 each"), "the unit price in the body: " + dialog.body());
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 1000 - 384, "charged $384 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> count(e2e, name, Material.STONE) == 64, "64 stone delivered");
        e2e.eventually(() -> bot.chatContains("You bought 64 stone for $384."), "a receipt: " + bot.chat());
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Blocks"), "back on the Blocks page");
        e2e.eventually(() -> {
            var rows = e2e.services().ledger().history(e2e.uuid(name), 5, 0).join();
            return !rows.isEmpty() && rows.getFirst().kind().equals("shop_buy") && rows.getFirst().delta() == -384
                && "blocks/stone".equals(rows.getFirst().ref());
        }, "a 'shop_buy' ledger row of -384 for blocks/stone");
        e2e.step("/shop <category> and the pause-menu route");
        bot.closeScreen();
        openScreen(e2e, bot, "shop farming", "Farming");
        bot.closeScreen();
        bot.clearLogs();
        bot.command("shop nowhere");
        e2e.eventually(() -> bot.actionBarContains("There's no shop category called nowhere."), "unknown category: " + bot.actionBar());
        bot.rawClick("siftcore:hub/shop", null);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Shop"), "the shop from the hub route");
        e2e.expect(bot.screenItems().get(23) != null, "the spawner category is shown now that the spawners feature makes spawner items");
        bot.closeScreen();
    }

    static void shopAmount(E2E e2e) {
        String name = e2e.name("ShopAmt");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 10000");
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.step("moving the slider shows the new total instead of buying");
        e2e.click(bot, "Buy 64", Map.of("amount", 10, "exact", ""));
        Bot.SeenDialog changed = e2e.dialog(bot, "Buy stone");
        e2e.expect(changed.button("Buy 10 for $60") != null, "the button now says 10 for $60: " + changed.buttons());
        e2e.expect(changed.bodyText().contains("The amount changed"), "a note about the change: " + changed.body());
        e2e.expect(e2e.money(name) == 10_000, "nothing bought yet");
        e2e.click(bot, "Buy 10", Map.of("amount", 10, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 10_000 - 60, "charged $60 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> count(e2e, name, Material.STONE) == 10, "10 stone");
        e2e.step("a typed amount wins over the slider");
        bot.closeScreen();
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", "1,000"));
        Bot.SeenDialog typed = e2e.dialog(bot, "Buy stone");
        e2e.expect(typed.button("Buy 1,000 for $6,000") != null, "the button says 1,000 for $6,000: " + typed.buttons());
        e2e.click(bot, "Buy 1,000", Map.of("amount", 1000, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 10_000 - 60 - 6_000, "charged $6,000 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> count(e2e, name, Material.STONE) == 1010, "1,010 stone");
        e2e.step("a typed amount that isn't a number is refused in place");
        bot.closeScreen();
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", "lots"));
        Bot.SeenDialog invalid = e2e.dialog(bot, "Buy stone");
        e2e.expect(invalid.bodyText().contains("Type a whole number from 1 to 2,304."), "an input error: " + invalid.body());
        e2e.expect(e2e.money(name) == 10_000 - 6_060, "nothing more bought");
    }

    static void shopConfirm(E2E e2e) {
        String name = e2e.name("ShopConf");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 100k");
        Bot.SeenDialog dialog = openEntry(e2e, bot, 16, "Ores", 9, "Buy diamond");
        e2e.expect(dialog.button("Buy 64 for $64,000") != null, "64 diamonds for $64,000: " + dialog.buttons());
        e2e.step("a large total asks first; going back buys nothing");
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", ""));
        Bot.SeenDialog confirm = e2e.dialog(bot, "Confirm purchase");
        e2e.expect(confirm.bodyText().contains("Buy 64 diamond for $64,000?"), "the exact purchase: " + confirm.body());
        e2e.expect(confirm.bodyText().contains("You'll have $36,000 left."), "the balance after: " + confirm.body());
        e2e.click(bot, "Back");
        e2e.dialog(bot, "Buy diamond");
        e2e.expect(e2e.money(name) == 100_000, "nothing charged after going back");
        e2e.step("confirming buys");
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", ""));
        e2e.dialog(bot, "Confirm purchase");
        e2e.click(bot, "Buy");
        e2e.eventually(() -> e2e.money(name) == 36_000, "charged $64,000 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 64, "64 diamonds delivered");
    }

    static void shopRefusals(E2E e2e) {
        String name = e2e.name("ShopPoor");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 100");
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.step("not enough money is explained in the dialog");
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", ""));
        Bot.SeenDialog poor = e2e.dialog(bot, "Buy stone");
        e2e.expect(poor.bodyText().contains("That costs $384 and you have $100."), "a balance error: " + poor.body());
        e2e.sleep(300);
        e2e.expect(e2e.money(name) == 100 && count(e2e, name, Material.STONE) == 0, "nothing moved");
        e2e.step("a smaller amount fits the budget");
        e2e.click(bot, "Buy 64", Map.of("amount", 16, "exact", ""));
        e2e.dialog(bot, "Buy stone");
        e2e.click(bot, "Buy 16", Map.of("amount", 16, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 4, "charged $96 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> count(e2e, name, Material.STONE) == 16, "16 stone");
    }

    static void shopClaimBox(E2E e2e) {
        String name = e2e.name("ShopFull");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 1000");
        e2e.step("a full inventory sends the purchase to the claim box");
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.clear();
            for (int slot = 0; slot < 36; slot++) {
                inventory.setItem(slot, ItemStack.of(Material.DIRT, 64));
            }
            return null;
        });
        int before = e2e.services().deliveries().count(e2e.uuid(name));
        bot.clearLogs();
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.click(bot, "Buy 64", Map.of("amount", 64, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 1000 - 384, "charged $384");
        e2e.eventually(() -> e2e.services().deliveries().count(e2e.uuid(name)) == before + 1, "one stack in the claim box");
        e2e.eventually(() -> bot.chatContains("64 didn't fit and are waiting in your claim box (/claims)."), "the receipt mentions the claim box: " + bot.chat());
        e2e.expect(count(e2e, name, Material.STONE) == 0, "nothing was forced into the full inventory");
    }

    static void shopDoubleSubmit(E2E e2e) {
        String name = e2e.name("ShopTwice");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 2000");
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.step("three instant clicks buy once");
        bot.clickButton("Buy 64", Map.of("amount", 64, "exact", ""));
        bot.clickButton("Buy 64", Map.of("amount", 64, "exact", ""));
        bot.clickButton("Buy 64", Map.of("amount", 64, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 2000 - 384, "charged once");
        e2e.sleep(1500);
        e2e.expect(e2e.money(name) == 2000 - 384, "replayed clicks bought nothing more: " + e2e.money(name));
        e2e.expect(count(e2e, name, Material.STONE) == 64, "exactly 64 stone");
    }

    // ------------------------------------------------------------------ durability

    /** Stone of a player waiting in the claim box (works while they are offline). */
    private static int stoneWaiting(E2E e2e, UUID uuid) {
        return e2e.services().deliveries().of(uuid).stream()
            .mapToInt(delivery -> delivery.item().getType() == Material.STONE ? delivery.item().getAmount() : 0).sum();
    }

    /**
     * A buyer who leaves between paying and the purchase being stored (a slow disk, a reconnect of the database) finds
     * what they paid for in the claim box. Before, the part meant for the inventory existed only in memory and was lost
     * when the player's scheduler refused the hand-over.
     */
    static void shopLeftBeforeCommit(E2E e2e) throws Exception {
        String name = e2e.name("ShopLeft");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 1000");
        int before = stoneWaiting(e2e, uuid);
        openEntry(e2e, bot, 10, "Blocks", 0, "Buy stone");
        e2e.step("buy while storage is slow, and leave before the purchase is stored");
        CompletableFuture<Object> stall = e2e.stallStorage(3_000);
        e2e.expect(bot.clickButton("Buy 64", Map.of("amount", 64, "exact", "")), "can click Buy 64");
        e2e.eventually(() -> e2e.services().ledger().balance(uuid, Currency.MONEY) == 1000 - 384, "charged at once in memory");
        e2e.kick(bot, "e2e: leaving before the purchase is stored");
        e2e.expect(!stall.isDone(), "the purchase was still waiting for storage when the buyer left");
        stall.get(10, TimeUnit.SECONDS);
        e2e.services().database().flush();
        e2e.step("the stone they paid for waits in the claim box");
        e2e.eventually(() -> stoneWaiting(e2e, uuid) == before + 64, "64 stone in the claim box (has " + (stoneWaiting(e2e, uuid) - before) + ")");
        e2e.sleep(500);
        e2e.bot(name);
        e2e.expect(e2e.money(name) == 1000 - 384, "charged once: " + e2e.money(name));
        e2e.expect(stoneWaiting(e2e, uuid) == before + 64, "still 64 in the claim box after rejoining");
        e2e.expect(count(e2e, name, Material.STONE) == 0, "nothing was handed out twice");
    }

    /** Items of a material in the grid of the sell menu the player has open, as the server sees it. */
    private static int gridCount(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            Inventory top = e2e.player(name).getOpenInventory().getTopInventory();
            int total = 0;
            for (int slot = 0; slot < Math.min(45, top.getSize()); slot++) {
                ItemStack stack = top.getItem(slot);
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            return total;
        });
    }

    /**
     * Mastery opened from the sell menu has Back (not Close), which returns to the same menu with its grid; selling a
     * category from there sells exactly what its button showed (the inventory), never the grid under the dialog.
     */
    static void sellMasteryBack(E2E e2e) {
        String name = e2e.name("SellMast");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 0");
        e2e.eventually(() -> "0".equals(e2e.onPlayer(name, () -> e2e.services().placeholders().resolve(e2e.player(name),
            "sell_mastery_mining"))), "mastery loaded");
        setSlot(e2e, name, 9, ItemStack.of(Material.EMERALD, 30));
        setSlot(e2e, name, 10, ItemStack.of(Material.DIAMOND, 10));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $7,500"), "30 emeralds in the grid: " + slotName(bot, 48));

        e2e.step("Mastery from the menu offers Back, and Back returns to the same menu, grid and all");
        e2e.sleep(300);
        bot.clickSlot(52);
        Bot.SeenDialog list = e2e.dialog(bot, "Sell mastery");
        e2e.expect(list.button("Back") != null && list.button("Close") == null, "Back instead of Close: " + list.buttons());
        e2e.expect(gridCount(e2e, name, Material.EMERALD) == 30, "the menu stays open under the dialog");
        Bot.Screen menu = bot.screen();
        e2e.expect(bot.clickButton("Back", Map.of()), "can click Back");
        e2e.eventually(() -> bot.screen() != null && bot.screen() != menu && bot.screen().title().equals("Sell items"),
            "the sell menu is shown again");
        e2e.eventually(() -> slotName(bot, 48).contains("Total $7,500"), "it still holds the 30 emeralds: " + slotName(bot, 48));
        e2e.expect(count(e2e, name, Material.EMERALD) == 0 && gridCount(e2e, name, Material.EMERALD) == 30,
            "the emeralds were never given back");

        e2e.step("a category's Sell button sells what it showed (the 10 diamonds), not the grid");
        e2e.sleep(300);
        int listsBefore = bot.dialogs().size();
        bot.clickSlot(52);
        e2e.eventually(() -> bot.dialogs().size() > listsBefore && bot.dialog() != null && bot.dialog().title().contains("Sell mastery"),
            "the mastery list again");
        e2e.click(bot, "Mining");
        Bot.SeenDialog detail = e2e.dialog(bot, "Mining mastery");
        e2e.expect(detail.button("Sell your Mining items (10 for $4,000)") != null, "the button shows the inventory only: " + detail.buttons());
        int shown = bot.dialogs().size();
        bot.clearMessages();
        e2e.expect(bot.clickButton("Sell your Mining items", Map.of()), "can sell");
        e2e.eventually(() -> e2e.money(name) == 4_000, "paid $4,000 for the diamonds (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.dialogs().size() > shown && bot.dialogs().getLast().title().contains("Mining mastery"),
            "the details again, with the new progress");
        e2e.sleep(500);
        e2e.expect(e2e.money(name) == 4_000, "nothing more was sold: " + e2e.money(name));
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds were sold");
        e2e.expect(gridCount(e2e, name, Material.EMERALD) == 30, "the emeralds are still in the grid");

        e2e.step("Back to the list, Back to the menu");
        int detailsBefore = bot.dialogs().size();
        bot.clickButton("Back", Map.of());
        e2e.eventually(() -> bot.dialogs().size() > detailsBefore && bot.dialog().title().contains("Sell mastery"), "the list again");
        Bot.Screen again = bot.screen();
        e2e.expect(bot.clickButton("Back", Map.of()), "can click Back");
        e2e.eventually(() -> bot.screen() != null && bot.screen() != again && bot.screen().title().equals("Sell items"),
            "the sell menu once more");
        e2e.eventually(() -> slotName(bot, 48).contains("Total $7,500"), "with the emeralds: " + slotName(bot, 48));
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "closing gives the emeralds back");
    }

    /**
     * While items sit in the sell grid, the player's own data holds a copy of them, saved together with the inventory, so
     * a crash with the menu open can't lose them: a copy left in the saved data is given back on the next join.
     */
    static void sellGridCopy(E2E e2e) {
        String name = e2e.name("SellCopy");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 0");
        setSlot(e2e, name, 9, ItemStack.of(Material.EMERALD, 30));
        e2e.expect(e2e.gridCopy(name, "sell_grid").isEmpty(), "no copy before");
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $7,500"), "30 emeralds in the grid: " + slotName(bot, 48));

        e2e.step("the player's data holds a copy of the grid while the menu is open");
        e2e.eventually(() -> e2e.gridCopyCount(name, "sell_grid", Material.EMERALD) == 30, "the copy holds the 30 emeralds");

        e2e.step("Give back empties the grid and the copy");
        e2e.sleep(300);
        bot.clickSlot(46);
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "the emeralds are back");
        e2e.eventually(() -> e2e.gridCopy(name, "sell_grid").isEmpty(), "the copy is gone");

        e2e.step("selling from the grid drops the sold items from the copy");
        setSlot(e2e, name, 0, null);
        setSlot(e2e, name, 9, ItemStack.of(Material.EMERALD, 30));
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            for (int slot = 0; slot < 36; slot++) {
                if (slot != 9) {
                    inventory.setItem(slot, null);
                }
            }
            inventory.setItem(10, ItemStack.of(Material.STICK, 5));
            return null;
        });
        e2e.sleep(300);
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.sleep(300);
        bot.clickSlot(BELOW_SIX_ROWS_MAIN + 1, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> e2e.gridCopyCount(name, "sell_grid", Material.STICK) == 5
            && e2e.gridCopyCount(name, "sell_grid", Material.EMERALD) == 30, "the copy holds both");
        e2e.sleep(300);
        bot.clickSlot(50);
        e2e.eventually(() -> e2e.money(name) == 7_500, "sold the emeralds (has " + e2e.money(name) + ")");
        e2e.eventually(() -> e2e.gridCopyCount(name, "sell_grid", Material.EMERALD) == 0
            && e2e.gridCopyCount(name, "sell_grid", Material.STICK) == 5, "the copy keeps only the unsold sticks");

        e2e.step("closing clears the copy");
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.STICK) == 5, "the sticks are back");
        e2e.eventually(() -> e2e.gridCopy(name, "sell_grid").isEmpty(), "no copy after closing");

        e2e.step("a copy left in the saved data by a crash comes back on the next join");
        // Saved with the copy, then a crash: leaving normally would drop a copy no open menu stands behind.
        UUID uuid = e2e.uuid(name);
        e2e.setGridCopy(name, "sell_grid", List.of(ItemStack.of(Material.GOLD_INGOT, 12), ItemStack.of(Material.IRON_INGOT, 3)));
        e2e.savePlayer(name);
        e2e.crashTo(bot, uuid, e2e.savedPlayerFile(uuid));
        Bot back = e2e.bot(name);
        e2e.eventually(() -> count(e2e, name, Material.GOLD_INGOT) == 12 && count(e2e, name, Material.IRON_INGOT) == 3,
            "the 12 gold and 3 iron are back in the inventory");
        e2e.eventually(() -> back.chatContains("The items you left in the sell menu when the server stopped are back in your inventory."),
            "told: " + back.chat());
        e2e.expect(e2e.gridCopy(name, "sell_grid").isEmpty(), "the copy is gone, so nothing comes back twice");
        e2e.kick(back, "e2e: once more");
        e2e.sleep(500);
        e2e.bot(name);
        e2e.sleep(500);
        e2e.expect(count(e2e, name, Material.GOLD_INGOT) == 12, "still 12 gold after joining again");
    }

    /** Leaves exactly {@code amount} emeralds in main inventory slot 9 (the first slot below the menu) and nothing else. */
    private static void onlyEmeralds(E2E e2e, String name, int amount) {
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.EMERALD, amount));
    }

    /** Opens /sell and moves the emeralds of slot 9 into the grid; waits until the copy holds them. */
    private static void emeraldsInGrid(E2E e2e, Bot bot, String name, int amount) {
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> gridCount(e2e, name, Material.EMERALD) == amount, amount + " emeralds in the grid");
        e2e.eventually(() -> e2e.gridCopyCount(name, "sell_grid", Material.EMERALD) == amount, "the copy holds them");
    }

    /**
     * What a crash leaves on disk: the player file saved while emeralds sit in the sell grid gives them back once, and
     * the file saved when the grid gives them back holds them once too, also when /sell replaced the menu in the same
     * moment it closed. Before, that last case left a stale copy next to the returned emeralds, so a crash (or simply
     * leaving before the new menu opened) handed them out twice.
     */
    static void sellGridCrash(E2E e2e) {
        String name = e2e.name("SellCrash");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        e2e.console("eco set " + name + " 0");

        e2e.step("a crash while emeralds sit in the grid (after a save) gives them back once");
        onlyEmeralds(e2e, name, 30);
        emeraldsInGrid(e2e, bot, name, 30);
        e2e.savePlayer(name);
        e2e.crashTo(bot, uuid, e2e.savedPlayerFile(uuid));
        bot = e2e.bot(name);
        e2e.sleep(500);
        e2e.expect(count(e2e, name, Material.EMERALD) == 30, "the 30 emeralds came back once: " + count(e2e, name, Material.EMERALD));

        e2e.step("a crash right after closing the menu keeps the emeralds once");
        onlyEmeralds(e2e, name, 30);
        emeraldsInGrid(e2e, bot, name, 30);
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "the emeralds are back");
        e2e.crashTo(bot, uuid, e2e.savedPlayerFile(uuid));
        bot = e2e.bot(name);
        e2e.sleep(500);
        e2e.expect(count(e2e, name, Material.EMERALD) == 30, "still 30 emeralds: " + count(e2e, name, Material.EMERALD));

        e2e.step("/sell, and the menu holding the emeralds closes before the new one is on screen; then a crash");
        onlyEmeralds(e2e, name, 30);
        emeraldsInGrid(e2e, bot, name, 30);
        e2e.sleep(300);
        // The command runs on the player's thread and puts the new menu on screen a tick later; the close arrives first.
        e2e.onPlayer(name, () -> e2e.player(name).performCommand("sell"));
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "the emeralds are back");
        e2e.sleep(300);
        e2e.expect(e2e.gridCopy(name, "sell_grid").isEmpty(), "no copy is left next to them: " + e2e.gridCopy(name, "sell_grid"));
        e2e.crashTo(bot, uuid, e2e.savedPlayerFile(uuid));
        Bot back = e2e.bot(name);
        e2e.sleep(500);
        e2e.expect(count(e2e, name, Material.EMERALD) == 30, "still 30 emeralds: " + count(e2e, name, Material.EMERALD));
        e2e.expect(!back.chatContains("The items you left in the sell menu"), "nothing was given back a second time: " + back.chat());
    }

    /**
     * The mastery dialogs over a sell menu never close it: a category sale that asks first returns to the details with
     * the menu (and its grid) still there, Cancel does the same, and the price list has a Back that leads to the details.
     */
    static void sellMasteryStays(E2E e2e) {
        String name = e2e.name("SellStay");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 0");
        e2e.eventually(() -> "0".equals(e2e.onPlayer(name, () -> e2e.services().placeholders().resolve(e2e.player(name),
            "sell_mastery_mining"))), "mastery loaded");
        setSlot(e2e, name, 9, ItemStack.of(Material.EMERALD, 30));
        setSlot(e2e, name, 10, ItemStack.of(Material.DIAMOND, 30));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> gridCount(e2e, name, Material.EMERALD) == 30, "30 emeralds in the grid");
        e2e.sleep(300);
        bot.clickSlot(52);
        e2e.dialog(bot, "Sell mastery");
        e2e.click(bot, "Mining");
        e2e.dialog(bot, "Mining mastery");

        e2e.step("selling $12,000 of diamonds asks first; Cancel returns to the details, the menu stays");
        int before = bot.dialogs().size();
        e2e.expect(bot.clickButton("Sell your Mining items (30 for $12,000)", Map.of()), "can sell the diamonds");
        e2e.eventually(() -> bot.dialogs().size() > before && bot.dialog().title().contains("Sell Mining items"), "the confirmation");
        int asked = bot.dialogs().size();
        e2e.expect(bot.clickButton("Cancel", Map.of()), "can cancel");
        e2e.eventually(() -> bot.dialogs().size() > asked && bot.dialog().title().contains("Mining mastery"), "the details again");
        e2e.sleep(300);
        e2e.expect(gridCount(e2e, name, Material.EMERALD) == 30 && count(e2e, name, Material.EMERALD) == 0,
            "the menu is still open with the emeralds in its grid");

        e2e.step("confirming sells the diamonds and returns to the details, the menu still there");
        int again = bot.dialogs().size();
        e2e.expect(bot.clickButton("Sell your Mining items (30 for $12,000)", Map.of()), "can sell the diamonds");
        e2e.eventually(() -> bot.dialogs().size() > again && bot.dialog().title().contains("Sell Mining items"), "the confirmation");
        int confirming = bot.dialogs().size();
        e2e.expect(bot.clickButton("Sell for $12,000", Map.of()), "can confirm");
        e2e.eventually(() -> e2e.money(name) == 12_000, "paid $12,000 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.dialogs().size() > confirming && bot.dialog().title().contains("Mining mastery"),
            "the details again, with the new progress");
        e2e.sleep(300);
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds were sold");
        e2e.expect(gridCount(e2e, name, Material.EMERALD) == 30 && count(e2e, name, Material.EMERALD) == 0,
            "the menu is still open with the emeralds in its grid");

        e2e.step("Prices opens the price list with a Back to the details");
        Bot.Screen menu = bot.screen();
        e2e.expect(bot.clickButton("Prices", Map.of()), "can open the prices");
        e2e.eventually(() -> bot.screen() != null && bot.screen() != menu && bot.screen().title().equals("Prices"), "the price list");
        e2e.eventually(() -> slotName(bot, 46).equals("Back"), "it has Back: " + slotName(bot, 46));
        e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 30, "the sell menu it replaced gave the emeralds back");
        int listed = bot.dialogs().size();
        bot.clickSlot(46);
        e2e.eventually(() -> bot.dialogs().size() > listed && bot.dialog().title().contains("Mining mastery"), "Back shows the details");

        e2e.step("and from there Back leads to a sell menu");
        int details = bot.dialogs().size();
        e2e.expect(bot.clickButton("Back", Map.of()), "can click Back");
        e2e.eventually(() -> bot.dialogs().size() > details && bot.dialog().title().contains("Sell mastery"), "the list");
        Bot.Screen prices = bot.screen();
        e2e.expect(bot.clickButton("Back", Map.of()), "can click Back");
        e2e.eventually(() -> bot.screen() != null && bot.screen() != prices && bot.screen().title().equals("Sell items"), "a sell menu");
        bot.closeScreen();
        e2e.sleep(300);
        e2e.expect(count(e2e, name, Material.EMERALD) == 30, "30 emeralds in the end: " + count(e2e, name, Material.EMERALD));
    }
}
