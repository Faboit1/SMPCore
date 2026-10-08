package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
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

    static void sellBonus(E2E e2e) {
        String name = e2e.name("SellBonus");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        e2e.step("grant the legend sell tier");
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.addAttachment(e2e.services().plugin(), "siftcore.sell.multiplier.legend", true);
            player.addAttachment(e2e.services().plugin(), "siftcore.sell.multiplier.supporter", true);
            player.getInventory().setHeldItemSlot(0);
            player.getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND, 64));
            return null;
        });
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 38_400, "paid 25,600 x 1.5 = $38,400 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 64 diamond for $38,400 with your 1.5x bonus."), "a bonus receipt: " + bot.chat());
        e2e.step("the placeholder shows the multiplier");
        String value = e2e.onPlayer(name, () -> e2e.services().placeholders().resolve(e2e.player(name), "sell_multiplier"));
        e2e.expect("1.5".equals(value), "sell_multiplier is 1.5, got " + value);
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
        e2e.expect(bot.screenItems().get(23) == null, "the spawner category is hidden without spawner items");
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
        e2e.eventually(() -> bot.chatContains("64 didn't fit and are waiting in your claim box."), "the receipt mentions the claim box: " + bot.chat());
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
}
