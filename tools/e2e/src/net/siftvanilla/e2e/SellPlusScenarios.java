package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.component.ItemLore;
import net.siftvanilla.siftcore.api.event.EconomyTransactionEvent;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/**
 * Scenarios for the sell and shop upgrades: confirming /sell all, /sell hand all, shulker box contents, combat,
 * the price list and item details, mastery, the menu's Add and Give back, top sellers and history, villager trade
 * marking, and the shop's search, quick amounts, buy again and right-click selling.
 */
final class SellPlusScenarios {

    /** Raw slot of player inventory slot 9 (first main slot) below a six-row chest. */
    private static final int BELOW_SIX_ROWS_MAIN = 54;

    private SellPlusScenarios() {
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
        list.add(of("sell-all-confirm", SellPlusScenarios::sellAllConfirm));
        list.add(of("sell-hand-all", SellPlusScenarios::sellHandAll));
        list.add(of("sell-shulker-hand", SellPlusScenarios::sellShulkerHand));
        list.add(of("sell-shulker-menu", SellPlusScenarios::sellShulkerMenu));
        list.add(of("sell-shulker-all", SellPlusScenarios::sellShulkerAll));
        list.add(of("sell-shulker-failure", SellPlusScenarios::sellShulkerFailure));
        list.add(of("sell-combat-blocked", SellPlusScenarios::sellCombatBlocked));
        list.add(of("shop-combat-blocked", SellPlusScenarios::shopCombatBlocked));
        list.add(of("worth-browser-open", SellPlusScenarios::worthBrowser));
        list.add(of("worth-details-sell", SellPlusScenarios::worthDetailsSell));
        list.add(of("mastery-credit-and-level", SellPlusScenarios::mastery));
        list.add(of("sell-mastery-from-menu", SellPlusScenarios::masteryFromMenu));
        list.add(of("sell-menu-add-and-giveback", SellPlusScenarios::menuAddAndGiveBack));
        list.add(of("sell-top-and-history", SellPlusScenarios::topAndHistory));
        list.add(of("sell-traded-refused", SellPlusScenarios::tradedRefused));
        list.add(of("sell-traded-block", SellPlusScenarios::tradedBlock));
        list.add(of("sell-traded-bucket", SellPlusScenarios::tradedBucket));
        list.add(of("sell-choose-items", SellPlusScenarios::chooseItems));
        list.add(of("shop-search", SellPlusScenarios::shopSearch));
        list.add(of("shop-quick-and-buy-again", SellPlusScenarios::shopQuickAndBuyAgain));
        list.add(of("shop-right-click-sell", SellPlusScenarios::shopRightClickSell));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static void setSlot(E2E e2e, String name, int slot, ItemStack item) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setItem(slot, item);
            return null;
        });
    }

    private static ItemStack getSlot(E2E e2e, String name, int slot) {
        return e2e.onPlayer(name, () -> {
            ItemStack stack = e2e.player(name).getInventory().getItem(slot);
            return stack == null ? ItemStack.empty() : stack.clone();
        });
    }

    private static void clear(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            e2e.player(name).getInventory().setHeldItemSlot(0);
            return null;
        });
    }

    /** Plain items of a material anywhere in the inventory (not inside containers). */
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

    /** A shulker box holding the given stacks at the given positions. */
    private static ItemStack box(Map<Integer, ItemStack> contents) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            list.add(ItemStack.empty());
        }
        contents.forEach(list::set);
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(list));
        return box;
    }

    /** What a box holds: the stack at each position (empty positions are empty stacks). */
    private static List<ItemStack> contents(ItemStack box) {
        ItemContainerContents contents = box.getData(DataComponentTypes.CONTAINER);
        return contents == null ? List.of() : contents.contents();
    }

    private static int inBox(ItemStack box, Material material) {
        int total = 0;
        for (ItemStack stack : contents(box)) {
            if (stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static String slotName(Bot bot, int slot) {
        var stack = bot.screenItems().get(slot);
        return stack == null ? "" : stack.getHoverName().getString();
    }

    private static String lore(Bot bot, int slot) {
        var stack = bot.screenItems().get(slot);
        if (stack == null) {
            return "";
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (net.minecraft.network.chat.Component line : lore.lines()) {
            text.append(line.getString()).append('\n');
        }
        return text.toString();
    }

    private static void openScreen(E2E e2e, Bot bot, String command, String title) {
        bot.command(command);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals(title),
            bot.name + " sees the '" + title + "' screen (now " + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(300);
    }

    private static void hold(E2E e2e, String name, ItemStack item) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setHeldItemSlot(0);
            e2e.player(name).getInventory().setItemInMainHand(item);
            return null;
        });
    }

    private static long worth(E2E e2e, Material material) {
        return e2e.feature(SellFeature.class).worth().unitPrice(ItemStack.of(material));
    }

    // ------------------------------------------------------------------ selling everything

    static void sellAllConfirm(E2E e2e) {
        String name = e2e.name("SellConf");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 64));
        setSlot(e2e, name, 10, ItemStack.of(Material.STICK, 5));
        e2e.step("a total above $10,000 asks first and shows what it sells");
        bot.clearLogs();
        bot.command("sell all");
        Bot.SeenDialog dialog = e2e.dialog(bot, "Sell everything");
        e2e.expect(dialog.bodyText().contains("Sell 64 items for $25,600?"), "the total in the dialog: " + dialog.body());
        e2e.expect(!dialog.bodyText().contains("Kept"), "only the question in the body: " + dialog.body());
        String sellTip = dialog.button("Sell for $25,600").tooltip();
        e2e.expect(sellTip != null && sellTip.contains("Kept: 5 items"), "the kept items, in the Sell tooltip: " + sellTip);
        e2e.expect(dialog.button("Choose items") != null, "a Choose items button: " + dialog.buttons());
        e2e.sleep(300);
        e2e.expect(e2e.money(name) == 0 && count(e2e, name, Material.DIAMOND) == 64, "nothing sold before confirming");
        e2e.click(bot, "Sell for $25,600");
        e2e.eventually(() -> e2e.money(name) == 25_600, "paid $25,600 after confirming (has " + e2e.money(name) + ")");
        e2e.expect(count(e2e, name, Material.STICK) == 5, "the sticks stay");

        e2e.step("a changed inventory shows the new total instead of selling");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 64));
        bot.clearLogs();
        bot.command("sell all");
        e2e.dialog(bot, "Sell everything");
        setSlot(e2e, name, 20, ItemStack.of(Material.DIAMOND, 10));
        e2e.click(bot, "Sell for $25,600");
        Bot.SeenDialog changed = e2e.dialog(bot, "Sell everything");
        e2e.expect(changed.bodyText().contains("Your inventory changed"), "a note about the change: " + changed.body());
        e2e.expect(changed.button("Sell for $29,600") != null, "the new total on the button: " + changed.buttons());
        e2e.sleep(300);
        e2e.expect(e2e.money(name) == 25_600, "nothing sold on the changed inventory: " + e2e.money(name));
        e2e.click(bot, "Sell for $29,600");
        e2e.eventually(() -> e2e.money(name) == 55_200, "paid $29,600 more (has " + e2e.money(name) + ")");
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "every diamond sold");

        e2e.step("with Ask before /sell all off, it sells right away");
        // the $55,200 sold so far reached mining mastery level 1: diamonds now sell for 1.05x (25,600 x 1.05)
        e2e.services().settings().setRaw(e2e.uuid(name), "sell_all_confirm", "false");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 64));
        bot.clearLogs();
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) == 55_200 + 26_880, "sold at once with the mastery bonus");
        e2e.expect(bot.dialogs().isEmpty(), "no confirmation: " + bot.dialogs());
    }

    static void sellHandAll(E2E e2e) {
        String name = e2e.name("HandAll");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        ItemStack renamed = ItemStack.of(Material.DIAMOND, 3);
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Shiny"));
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.setItem(0, ItemStack.of(Material.DIAMOND, 4));
            inventory.setItem(5, ItemStack.of(Material.DIAMOND, 6));
            inventory.setItem(20, ItemStack.of(Material.DIAMOND, 5));
            inventory.setItem(21, renamed);
            inventory.setItem(22, box(Map.of(0, ItemStack.of(Material.DIAMOND, 5), 4, ItemStack.of(Material.STICK, 2))));
            inventory.setItem(23, ItemStack.of(Material.IRON_INGOT, 10));
            inventory.setItemInOffHand(ItemStack.of(Material.DIAMOND, 7));
            return null;
        });
        e2e.step("/sell hand all sells every plain diamond in the hotbar, storage and boxes");
        bot.clearLogs();
        bot.command("sell hand all");
        e2e.eventually(() -> e2e.money(name) == 20 * 400, "paid 20 x $400 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 20 diamond for $8,000."), "a receipt: " + bot.chat());
        e2e.expect(getSlot(e2e, name, 21).getAmount() == 3, "the renamed diamonds stay");
        e2e.expect(e2e.onPlayer(name, () -> e2e.player(name).getInventory().getItemInOffHand().getAmount()) == 7, "the off hand stays");
        e2e.expect(count(e2e, name, Material.IRON_INGOT) == 10, "other items stay");
        ItemStack after = getSlot(e2e, name, 22);
        e2e.expect(inBox(after, Material.DIAMOND) == 0 && inBox(after, Material.STICK) == 2, "the box kept only its sticks");
        e2e.step("a renamed item in the hand is refused");
        hold(e2e, name, renamed);
        bot.clearLogs();
        bot.command("sell hand all");
        e2e.eventually(() -> bot.actionBarContains("Hold a plain diamond to sell all of them."), "refused: " + bot.actionBar());
        e2e.step("an empty hand is refused");
        hold(e2e, name, ItemStack.empty());
        bot.clearLogs();
        bot.command("sell hand all");
        e2e.eventually(() -> bot.actionBarContains("Hold the item you want to sell."), "empty hand: " + bot.actionBar());
    }

    // ------------------------------------------------------------------ shulker boxes

    static void sellShulkerHand(E2E e2e) {
        String name = e2e.name("BoxHand");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        ItemStack box = box(Map.of(0, ItemStack.of(Material.DIAMOND, 10), 5, ItemStack.of(Material.STICK, 7),
            26, ItemStack.of(Material.IRON_INGOT, 3)));
        hold(e2e, name, box);
        e2e.step("/worth on a box tells what is inside");
        bot.clearLogs();
        bot.command("worth");
        e2e.eventually(() -> bot.chatContains("What's inside sells for $4,075 (13 items)."), "the box's worth: " + bot.chat());
        e2e.step("/sell hand sells the contents and keeps the box with the rest");
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 4_075, "paid $4,075 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 13 items for $4,075."), "a receipt: " + bot.chat());
        ItemStack after = getSlot(e2e, name, 0);
        e2e.expect(after.getType() == Material.SHULKER_BOX, "the box is still in the hand");
        List<ItemStack> inside = contents(after);
        e2e.expect(inside.size() > 5 && inside.get(5).getType() == Material.STICK && inside.get(5).getAmount() == 7,
            "the sticks stayed at their place: " + inside);
        e2e.expect(inBox(after, Material.DIAMOND) == 0 && inBox(after, Material.IRON_INGOT) == 0, "the sellables left the box");
        e2e.step("a box with nothing sellable says so");
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> bot.actionBarContains("Nothing in that shulker box can be sold."), "box refusal: " + bot.actionBar());
        e2e.step("an empty plain box sells as an item");
        long boxWorth = worth(e2e, Material.SHULKER_BOX);
        e2e.expect(boxWorth > 0, "a plain shulker box has a price");
        hold(e2e, name, ItemStack.of(Material.SHULKER_BOX));
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 4_075 + boxWorth, "the empty box sold for $" + boxWorth + " (has " + e2e.money(name) + ")");
    }

    static void sellShulkerMenu(E2E e2e) {
        String name = e2e.name("BoxMenu");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        setSlot(e2e, name, 9, box(Map.of(3, ItemStack.of(Material.DIAMOND, 6), 8, ItemStack.of(Material.STICK, 4))));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $2,400"), "the box's contents in the total: " + slotName(bot, 48));
        e2e.sleep(300);
        bot.clearLogs();
        bot.clickSlot(50);
        e2e.eventually(() -> e2e.money(name) == 2_400, "paid $2,400 (has " + e2e.money(name) + ")");
        e2e.eventually(() -> slotName(bot, 48).contains("Total $0"), "nothing left to sell: " + slotName(bot, 48));
        e2e.expect(bot.screen() != null, "the menu stays open");
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.SHULKER_BOX) == 1, "the box came back");
        ItemStack back = e2e.onPlayer(name, () -> {
            for (ItemStack stack : e2e.player(name).getInventory().getContents()) {
                if (stack != null && stack.getType() == Material.SHULKER_BOX) {
                    return stack.clone();
                }
            }
            return ItemStack.empty();
        });
        e2e.expect(inBox(back, Material.STICK) == 4 && inBox(back, Material.DIAMOND) == 0, "the box kept its sticks only: " + contents(back));
    }

    static void sellShulkerAll(E2E e2e) {
        String name = e2e.name("BoxAll");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        setSlot(e2e, name, 9, box(Map.of(0, ItemStack.of(Material.DIAMOND, 10), 1, ItemStack.of(Material.STICK, 3))));
        setSlot(e2e, name, 10, ItemStack.of(Material.DIAMOND, 5));
        bot.clearLogs();
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) == 6_000, "paid 15 x $400 (has " + e2e.money(name) + ")");
        ItemStack after = getSlot(e2e, name, 9);
        e2e.expect(after.getType() == Material.SHULKER_BOX, "/sell all never sells the box itself");
        e2e.expect(inBox(after, Material.STICK) == 3 && inBox(after, Material.DIAMOND) == 0, "the box kept its sticks: " + contents(after));
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the loose diamonds sold too");
    }

    /** Cancels this player's sale transactions while installed (a stand-in for any failure after the items moved). */
    private static final class CancelSales implements Listener {
        private final UUID player;

        CancelSales(UUID player) {
            this.player = player;
        }

        @EventHandler
        public void onTransaction(EconomyTransactionEvent event) {
            if ("sell".equals(event.kind()) && this.player.toString().equals(event.actor())) {
                event.setCancelled(true);
            }
        }
    }

    static void sellShulkerFailure(E2E e2e) {
        String name = e2e.name("BoxFail");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        ItemStack box = box(Map.of(2, ItemStack.of(Material.DIAMOND, 12), 9, ItemStack.of(Material.STICK, 1),
            20, ItemStack.of(Material.GOLD_INGOT, 4)));
        hold(e2e, name, box);
        CancelSales cancel = new CancelSales(e2e.uuid(name));
        Bukkit.getPluginManager().registerEvents(cancel, e2e.services().plugin());
        try {
            e2e.step("a sale whose transaction fails puts the box back exactly as it was");
            bot.clearLogs();
            bot.command("sell hand");
            e2e.eventually(() -> bot.actionBarContains("Sale cancelled."), "the sale failed: " + bot.actionBar());
            e2e.sleep(300);
            ItemStack after = getSlot(e2e, name, 0);
            e2e.expect(after.equals(box), "the box is exactly the original: " + contents(after));
            e2e.expect(e2e.money(name) == 0, "no money: " + e2e.money(name));
            e2e.expect(e2e.services().deliveries().count(e2e.uuid(name)) == 0, "nothing went to the claim box");
        } finally {
            HandlerList.unregisterAll(cancel);
        }
        e2e.step("without the failure it sells");
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 12 * 400 + 4 * worth(e2e, Material.GOLD_INGOT), "paid for the contents: " + e2e.money(name));
    }

    // ------------------------------------------------------------------ combat

    static void sellCombatBlocked(E2E e2e) {
        String name = e2e.name("SellFight");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        hold(e2e, name, ItemStack.of(Material.DIAMOND, 10));
        e2e.console("combat tag " + name + " 60s");
        try {
            e2e.step("selling is refused while tagged");
            bot.clearLogs();
            bot.command("sell hand");
            e2e.eventually(() -> bot.actionBarContains("You can't sell in combat."), "refused in combat: " + bot.actionBar());
            bot.clearLogs();
            bot.command("sell all");
            e2e.eventually(() -> bot.actionBarContains("You can't sell in combat."), "/sell all refused: " + bot.actionBar());
            bot.command("sell");
            e2e.sleep(800);
            e2e.expect(bot.screen() == null, "no sell menu while tagged");
            e2e.expect(e2e.money(name) == 0 && count(e2e, name, Material.DIAMOND) == 10, "nothing sold");
        } finally {
            e2e.console("combat untag " + name);
        }
        e2e.step("the menu's Sell button checks again when pressed");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 5));
        openScreen(e2e, bot, "sell", "Sell items");
        bot.clickSlot(BELOW_SIX_ROWS_MAIN, 0, ContainerInput.QUICK_MOVE);
        e2e.eventually(() -> slotName(bot, 48).contains("Total $2,000"), "5 diamonds in the grid: " + slotName(bot, 48));
        e2e.console("combat tag " + name + " 60s");
        try {
            bot.clearLogs();
            e2e.sleep(300);
            bot.clickSlot(50);
            e2e.eventually(() -> bot.actionBarContains("You can't sell in combat."), "the button refused: " + bot.actionBar());
            e2e.expect(bot.screen() != null, "the menu stays open");
            e2e.expect(e2e.money(name) == 0, "nothing sold: " + e2e.money(name));
        } finally {
            e2e.console("combat untag " + name);
        }
        e2e.sleep(300);
        bot.clickSlot(50);
        e2e.eventually(() -> e2e.money(name) == 2_000, "sold after the tag ended (has " + e2e.money(name) + ")");
        bot.closeScreen();
    }

    static void shopCombatBlocked(E2E e2e) {
        String name = e2e.name("ShopFight");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 1000");
        openScreen(e2e, bot, "shop", "Shop");
        bot.clickSlot(10);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Blocks"), "the Blocks page");
        e2e.sleep(300);
        bot.clickSlot(0);
        e2e.dialog(bot, "Buy stone");
        e2e.step("a buy dialog opened before the fight refuses during it");
        e2e.console("combat tag " + name + " 60s");
        try {
            e2e.expect(bot.clickButton("Buy 64", Map.of("amount", 64, "exact", "")), "the Buy button can be clicked");
            e2e.eventually(() -> bot.actionBarContains("You can't use the shop in combat."), "refused: " + bot.actionBar()
                + " chat " + bot.chat() + " dialogs " + bot.dialogs().stream().map(Bot.SeenDialog::title).toList());
            e2e.sleep(300);
            e2e.expect(e2e.money(name) == 1000 && count(e2e, name, Material.STONE) == 0, "nothing bought");
            bot.closeScreen();
            bot.clearLogs();
            bot.command("shop");
            // the combat feature refuses /shop itself (its blocked commands); the shop refuses the hub button and dialogs
            e2e.eventually(() -> bot.actionBarContains("in combat"), "/shop refused");
            e2e.sleep(500);
            e2e.expect(bot.screen() == null, "no shop screen while tagged");
        } finally {
            e2e.console("combat untag " + name);
        }
    }

    // ------------------------------------------------------------------ prices

    static void worthBrowser(E2E e2e) {
        String name = e2e.name("Prices");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.step("/worth with an empty hand opens the price list");
        openScreen(e2e, bot, "worth", "Prices");
        e2e.expect(!bot.screenItems().isEmpty(), "entries are shown");
        bot.closeScreen();
        e2e.step("/worth list diamond filters it");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 10));
        openScreen(e2e, bot, "worth list diamond", "Prices");
        e2e.eventually(() -> slotName(bot, 0).equals("Diamond"), "diamond first: " + slotName(bot, 0));
        e2e.expect(slotName(bot, 1).toLowerCase().contains("diamond"), "other diamond items after it: " + slotName(bot, 1));
        String lore = lore(bot, 0);
        e2e.expect(lore.contains("Sells for $400 each"), "the price: " + lore);
        e2e.expect(lore.contains("Category Mining"), "the category: " + lore);
        e2e.expect(lore.contains("The shop sells it for $1,000"), "the shop price: " + lore);
        e2e.expect(lore.contains("You carry 10, worth $4,000"), "what the player carries: " + lore);
        bot.closeScreen();
        e2e.step("the main menu has a Prices button that opens it");
        bot.clearLogs();
        bot.command("menu");
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().button("Prices") != null, "a Prices button in the main menu");
        e2e.expect(bot.clickButton("Prices", Map.of()), "Prices can be clicked");
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Prices"), "the price list from the main menu");
        bot.closeScreen();
    }

    static void worthDetailsSell(E2E e2e) {
        String name = e2e.name("Details");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 10));
        openScreen(e2e, bot, "worth list diamond", "Prices");
        e2e.eventually(() -> slotName(bot, 0).equals("Diamond"), "diamond first: " + slotName(bot, 0));
        bot.clearLogs();
        bot.clickSlot(0);
        Bot.SeenDialog details = e2e.dialog(bot, "Diamond");
        e2e.expect(details.bodyText().contains("Sells for $400 each"), "the price: " + details.body());
        e2e.expect(!details.bodyText().contains("You carry") && !details.bodyText().contains("The shop sells"),
            "what the buttons say is not repeated above them: " + details.body());
        e2e.expect(details.button("Sell your 10 for $4,000") != null, "the count, on the Sell button: " + details.buttons());
        e2e.expect(details.button("Buy in the shop for $1,000") != null, "a shop button: " + details.buttons());
        e2e.expect(details.buttons().stream().filter(button -> !button.label().equals("Back")).allMatch(button -> button.tooltip() != null),
            "every action says what it does: " + details.buttons());
        e2e.click(bot, "Sell your 10 for $4,000");
        e2e.eventually(() -> e2e.money(name) == 4_000, "sold from the details (has " + e2e.money(name) + ")");
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds are gone");
    }

    // ------------------------------------------------------------------ mastery

    static void mastery(E2E e2e) {
        String name = e2e.name("Mastery");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), "mastery loaded at level 0");
        e2e.step("selling $50,000 of mining items reaches level 1");
        hold(e2e, name, ItemStack.of(Material.DIAMOND, 64));
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 25_600, "first stack paid (has " + e2e.money(name) + ")");
        hold(e2e, name, ItemStack.of(Material.DIAMOND, 61));
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 50_000, "second stack paid (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("Mining mastery is now level 1."), "a level-up message: " + bot.chat());
        e2e.expect(bot.chatContains("Mining items sell for 1.05x."), "the new rate: " + bot.chat());
        e2e.expect("1".equals(placeholder(e2e, name, "sell_mastery_mining")), "sell_mastery_mining is 1");
        e2e.expect("1.05".equals(placeholder(e2e, name, "sell_multiplier_mining")), "sell_multiplier_mining is 1.05");
        e2e.expect("1".equals(placeholder(e2e, name, "sell_multiplier")), "sell_multiplier stays rank only");
        e2e.step("the next sale pays the bonus");
        hold(e2e, name, ItemStack.of(Material.DIAMOND, 64));
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 50_000 + 26_880, "25,600 x 1.05 = $26,880 (has " + e2e.money(name) + ")");
        e2e.step("/sell mastery shows the level");
        bot.clearLogs();
        bot.command("sell mastery");
        Bot.SeenDialog dialog = e2e.dialog(bot, "Sell mastery");
        e2e.expect(dialog.body().isEmpty(), "buttons only: " + dialog.body());
        Bot.Button mining = dialog.button("Mining: level 1 of 5");
        e2e.expect(mining != null, "the mining button with its level: " + dialog.buttons());
        e2e.expect(mining.tooltip() != null && mining.tooltip().contains("Mining items sell for 1.05x")
            && mining.tooltip().contains("Level 2 $250,000"), "the rate and the ladder in its tooltip: " + mining.tooltip());
        e2e.click(bot, "Mining");
        Bot.SeenDialog detail = e2e.dialog(bot, "Mining mastery");
        e2e.expect(detail.bodyText().contains("Mining items sell for 1.05x") && detail.bodyText().contains("Level 1 of 5")
            && detail.bodyText().contains("to level 2"), "the rate and progress: " + detail.body());
        bot.clickButton("Back", Map.of());
        e2e.step("staff can look at and set it from the console");
        List<String> shown = e2e.consoleOutput("sell admin mastery " + name);
        e2e.expect(String.join("\n", shown).contains("Mining level 1, $75,600 sold"), "the admin view: " + shown);
        e2e.console("sell admin mastery " + name + " mining set 3");
        e2e.eventually(() -> "3".equals(placeholder(e2e, name, "sell_mastery_mining")), "set to level 3");
        e2e.console("sell admin mastery " + name + " mining reset");
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), "reset to 0");
        e2e.step("resetting a player clears every category, in storage too");
        e2e.console("sell admin mastery " + name + " mining set 2");
        e2e.console("sell admin mastery " + name + " farming set 1");
        e2e.eventually(() -> "2".equals(placeholder(e2e, name, "sell_mastery_mining"))
            && "1".equals(placeholder(e2e, name, "sell_mastery_farming")), "two categories set");
        e2e.console("sell admin mastery " + name + " reset");
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining"))
            && "0".equals(placeholder(e2e, name, "sell_mastery_farming")), "both reset");
        e2e.eventually(() -> masteryRows(e2e, e2e.uuid(name)) == 0, "no rows are left");
        e2e.eventually(() -> !e2e.services().audit().recent("sell.mastery", e2e.uuid(name).toString(), 5).join().isEmpty(),
            "the changes are audited");
    }

    /**
     * Mastery opened from the sell menu, over a filled grid: a category's Sell gives the grid back first, so the
     * confirmation counts those items too, and confirming sells them all (closing the now empty menu moves nothing
     * into the slots the sale takes from).
     */
    static void masteryFromMenu(E2E e2e) {
        String name = e2e.name("MastMenu");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), "mastery loaded at level 0");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 24));
        openScreen(e2e, bot, "sell", "Sell items");
        e2e.step("24 diamonds in the grid, 40 more in the inventory");
        bot.clickSlot(45);
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 0, "the diamonds moved into the grid");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 40));
        e2e.sleep(300);

        e2e.step("Mastery, Mining, Sell: the confirmation sells what the button showed, not the grid");
        bot.clearLogs();
        bot.clickSlot(52);
        e2e.dialog(bot, "Sell mastery");
        e2e.click(bot, "Mining");
        Bot.SeenDialog detail = e2e.dialog(bot, "Mining mastery");
        String sell = "Sell your Mining items (40 for $16,000)";
        e2e.expect(detail.button(sell) != null, "the inventory's diamonds on the button: " + detail.buttons());
        e2e.click(bot, sell);
        Bot.SeenDialog confirm = e2e.dialog(bot, "Sell Mining items");
        e2e.expect(confirm.bodyText().contains("Sell 40 items for $16,000?"), "only the inventory's diamonds: " + confirm.body());
        e2e.expect(count(e2e, name, Material.DIAMOND) == 40 && e2e.money(name) == 0, "nothing moved, nothing sold yet");

        e2e.step("confirming sells the 40 and shows the mastery details again; the menu keeps its grid");
        e2e.click(bot, "Sell for $16,000");
        e2e.eventually(() -> e2e.money(name) == 16_000, "paid $16,000 (has " + e2e.money(name) + ")");
        e2e.dialog(bot, "Mining mastery");
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the inventory's diamonds sold");
        e2e.expect(bot.screen() != null, "the sell menu is still open under the dialog");
        e2e.sleep(300);
        e2e.expect(!bot.anyFeedbackContains("didn't go through"), "no failed sale: " + bot.chat() + " / " + bot.actionBar());

        e2e.step("closing the menu gives the grid's 24 diamonds back");
        e2e.onPlayer(name, () -> {
            e2e.player(name).closeInventory();
            return null;
        });
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 24, "the grid came back: " + count(e2e, name, Material.DIAMOND));
    }

    /** Stored sell mastery rows of a player (after everything queued was written). */
    private static long masteryRows(E2E e2e, UUID player) {
        var database = e2e.services().database();
        database.flush();
        try {
            return database.read(c -> {
                try (var ps = c.prepareStatement("SELECT COUNT(*) FROM sell_mastery WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (var rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("query failed: " + e);
        }
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(e2e.player(name), placeholder);
    }

    // ------------------------------------------------------------------ the sell menu

    static void menuAddAndGiveBack(E2E e2e) {
        String name = e2e.name("MenuAdd");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        long sword = worth(e2e, Material.IRON_SWORD);
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.setItem(0, ItemStack.of(Material.DIAMOND_PICKAXE));
            inventory.setItem(9, ItemStack.of(Material.DIAMOND, 64));
            inventory.setItem(10, ItemStack.of(Material.STICK, 7));
            inventory.setItem(11, box(Map.of(0, ItemStack.of(Material.DIAMOND, 5), 1, ItemStack.of(Material.STICK, 2))));
            inventory.setItem(12, ItemStack.of(Material.IRON_SWORD));
            return null;
        });
        openScreen(e2e, bot, "sell", "Sell items");
        e2e.step("Add moves what can be sold into the grid");
        bot.clickSlot(45);
        long expected = 64 * 400 + 5 * 400 + sword;
        e2e.eventually(() -> slotName(bot, 48).contains("Total $" + String.format(java.util.Locale.ROOT, "%,d", expected)),
            "the total of the added items: " + slotName(bot, 48));
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds moved");
        e2e.expect(count(e2e, name, Material.STICK) == 7, "the sticks stay in the inventory");
        e2e.expect(count(e2e, name, Material.DIAMOND_PICKAXE) == 1, "the pickaxe in the hand stays");
        e2e.expect(count(e2e, name, Material.SHULKER_BOX) == 0 && count(e2e, name, Material.IRON_SWORD) == 0,
            "the box and the sword moved");
        String lore = lore(bot, 48);
        e2e.expect(lore.contains("diamond"), "the total lists the items: " + lore);
        e2e.step("Give back empties the grid and keeps the menu open");
        e2e.sleep(300);
        bot.clickSlot(46);
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 64, "the diamonds are back");
        e2e.expect(count(e2e, name, Material.SHULKER_BOX) == 1 && count(e2e, name, Material.IRON_SWORD) == 1, "everything is back");
        e2e.eventually(() -> slotName(bot, 48).contains("Total $0"), "the total is empty again: " + slotName(bot, 48));
        e2e.expect(bot.screen() != null, "the menu is still open");
        e2e.expect(e2e.money(name) == 0, "nothing was sold");
        bot.closeScreen();
    }

    // ------------------------------------------------------------------ top sellers and history

    static void topAndHistory(E2E e2e) {
        String name = e2e.name("TopHist");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        hold(e2e, name, ItemStack.of(Material.IRON_INGOT, 20));
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) == 500, "sold 20 iron (has " + e2e.money(name) + ")");
        e2e.step("/sell top shows the viewer's own place");
        bot.clearLogs();
        bot.command("sell top");
        Bot.SeenDialog top = e2e.dialog(bot, "Top sellers");
        e2e.expect(top.bodyText().contains("You are number ") && top.bodyText().contains("with $500."), "the viewer's line: " + top.body());
        bot.clickButton("Close", Map.of());
        e2e.step("/sell history lists the sale");
        openScreen(e2e, bot, "sell history", "Your sales");
        e2e.eventually(() -> slotName(bot, 0).contains("Sold for $500"), "the sale: " + slotName(bot, 0));
        e2e.expect(lore(bot, 0).contains("20 iron ingot"), "what was sold: " + lore(bot, 0));
        bot.closeScreen();
        e2e.expect(!e2e.services().placeholders().resolve(e2e.player(name), "sell_sold").isEmpty(), "sell_sold resolves");
        e2e.expect("$400".equals(e2e.services().placeholders().resolve(e2e.player(name), "worth_diamond")), "worth_diamond is $400");
    }

    // ------------------------------------------------------------------ villager trades

    static void tradedRefused(E2E e2e) throws Exception {
        String name = e2e.name("Traded");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        setSlot(e2e, name, 1, ItemStack.of(Material.DIRT, 10));
        e2e.step("a wandering trader that gives an emerald for a dirt block");
        Location at = e2e.onPlayer(name, () -> e2e.player(name).getLocation().add(1.5, 0, 0));
        AtomicReference<WanderingTrader> spawned = new AtomicReference<>();
        CompletableFuture<Integer> id = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(e2e.services().plugin(), at, task -> {
            WanderingTrader trader = at.getWorld().spawn(at, WanderingTrader.class, entity -> {
                entity.setAI(false);
                entity.setDespawnDelay(0);
                MerchantRecipe recipe = new MerchantRecipe(ItemStack.of(Material.EMERALD), 0, 100, false);
                recipe.addIngredient(ItemStack.of(Material.DIRT));
                entity.setRecipes(List.of(recipe));
            });
            spawned.set(trader);
            id.complete(trader.getEntityId());
        });
        try {
            int entityId = id.get(10, TimeUnit.SECONDS);
            e2e.sleep(500);
            bot.interact(entityId);
            e2e.eventually(() -> bot.screen() != null, "the trade screen opens");
            e2e.sleep(300);
            bot.selectTrade(0);
            e2e.sleep(500);
            // take one result onto the cursor; closing the screen puts it (and the unused dirt) back in the inventory
            bot.clickSlot(2);
            e2e.sleep(300);
            bot.closeScreen();
            e2e.eventually(() -> count(e2e, name, Material.EMERALD) == 1, "the bot traded for an emerald");
            e2e.step("the traded emerald carries the marker and can't be sold");
            NamespacedKey marker = NamespacedKey.fromString("siftcore:traded");
            int slot = e2e.onPlayer(name, () -> e2e.player(name).getInventory().first(Material.EMERALD));
            ItemStack emerald = getSlot(e2e, name, slot);
            e2e.expect(emerald.getPersistentDataContainer().has(marker, PersistentDataType.BYTE), "the emerald is marked");
            e2e.onPlayer(name, () -> {
                PlayerInventory inventory = e2e.player(name).getInventory();
                inventory.setItem(slot, null);
                inventory.setItem(0, emerald);
                inventory.setHeldItemSlot(0);
                return null;
            });
            bot.clearLogs();
            bot.command("sell hand");
            e2e.eventually(() -> bot.actionBarContains("Items from villager trades can't be sold."), "refused: " + bot.actionBar());
            e2e.onPlayer(name, () -> {
                e2e.player(name).getInventory().remove(Material.DIRT);
                return null;
            });
            bot.clearLogs();
            bot.command("sell all");
            e2e.eventually(() -> bot.actionBarContains("You have nothing that can be sold."), "/sell all skips it");
            e2e.expect(e2e.money(name) == 0 && count(e2e, name, Material.EMERALD) == 1, "nothing sold");
            e2e.step("a plain emerald still sells");
            setSlot(e2e, name, 0, ItemStack.of(Material.EMERALD));
            bot.command("sell hand");
            e2e.eventually(() -> e2e.money(name) == worth(e2e, Material.EMERALD), "a mined emerald sells (has " + e2e.money(name) + ")");
        } finally {
            WanderingTrader trader = spawned.get();
            if (trader != null) {
                trader.getScheduler().run(e2e.services().plugin(), task -> trader.remove(), null);
            }
        }
    }

    /** A free spot next to the player (stone below, air at and above it) in a region this thread owns. */
    private static int[] freeSpot(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> {
            Block feet = e2e.player(name).getLocation().getBlock();
            for (int[] offset : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                Block spot = feet.getRelative(offset[0], 0, offset[1]);
                if (Bukkit.isOwnedByCurrentRegion(spot.getLocation())) {
                    spot.getRelative(0, -1, 0).setType(Material.STONE, false);
                    spot.setType(Material.AIR, false);
                    spot.getRelative(0, 1, 0).setType(Material.AIR, false);
                    return new int[] {spot.getX(), spot.getY(), spot.getZ()};
                }
            }
            throw new IllegalStateException("no owned block next to " + name);
        });
    }

    /** Places the held block on the free spot (as the bot), breaks it as the player and returns what dropped there. */
    private static List<ItemStack> placeAndMine(E2E e2e, Bot bot, String name, int[] at, Material type) {
        bot.useItemOnTop(at[0], at[1] - 1, at[2]);
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getWorld().getBlockAt(at[0], at[1], at[2]).getType()) == type,
            "the bot placed " + type);
        boolean broke = e2e.onPlayer(name, () -> e2e.player(name).breakBlock(e2e.player(name).getWorld().getBlockAt(at[0], at[1], at[2])));
        e2e.expect(broke, "the player broke the block");
        List<ItemStack> drops = new ArrayList<>();
        e2e.eventually(() -> {
            drops.clear();
            drops.addAll(e2e.onPlayer(name, () -> {
                List<ItemStack> found = new ArrayList<>();
                Location center = new Location(e2e.player(name).getWorld(), at[0] + 0.5, at[1] + 0.5, at[2] + 0.5);
                for (Item item : center.getWorld().getNearbyEntitiesByType(Item.class, center, 2.0)) {
                    if (item.getItemStack().getType() == type) {
                        found.add(item.getItemStack().clone());
                        item.remove();
                    }
                }
                return found;
            }));
            return !drops.isEmpty();
        }, "the block dropped as an item");
        return drops;
    }

    static void tradedBlock(E2E e2e) {
        String name = e2e.name("TradeBlk");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        NamespacedKey marker = NamespacedKey.fromString("siftcore:traded");
        int[] at = freeSpot(e2e, name);

        e2e.step("a block bought from a villager, placed and mined again, is still marked");
        ItemStack bought = ItemStack.of(Material.WHITE_WOOL, 2);
        bought.editPersistentDataContainer(pdc -> pdc.set(marker, PersistentDataType.BYTE, (byte) 1));
        hold(e2e, name, bought);
        e2e.sleep(300);
        List<ItemStack> drops = placeAndMine(e2e, bot, name, at, Material.WHITE_WOOL);
        e2e.expect(drops.stream().allMatch(drop -> drop.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)),
            "every drop is marked: " + drops);
        e2e.step("so it can't be sold");
        hold(e2e, name, drops.getFirst());
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> bot.actionBarContains("Items from villager trades can't be sold."), "refused: " + bot.actionBar());
        e2e.expect(e2e.money(name) == 0, "nothing paid");

        e2e.step("a plain block placed at the same spot drops plain again and sells");
        hold(e2e, name, ItemStack.of(Material.WHITE_WOOL, 1));
        e2e.sleep(300);
        List<ItemStack> plain = placeAndMine(e2e, bot, name, at, Material.WHITE_WOOL);
        e2e.expect(plain.stream().noneMatch(drop -> drop.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)),
            "the drop is plain: " + plain);
        hold(e2e, name, plain.getFirst());
        bot.clearLogs();
        bot.command("sell hand");
        long wool = worth(e2e, Material.WHITE_WOOL);
        e2e.eventually(() -> e2e.money(name) == wool, "the plain block sold for $" + wool + " (has " + e2e.money(name) + ")");
    }

    private static ItemStack mainHand(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> e2e.player(name).getInventory().getItemInMainHand().clone());
    }

    static void tradedBucket(E2E e2e) {
        String name = e2e.name("TradeBkt");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        NamespacedKey marker = NamespacedKey.fromString("siftcore:traded");
        int[] feet = e2e.onPlayer(name, () -> {
            Block block = e2e.player(name).getLocation().getBlock();
            return new int[] {block.getX(), block.getY(), block.getZ()};
        });
        try {
            e2e.step("emptying a bucket of cod from a fisherman leaves a marked bucket and a marked fish");
            ItemStack cod = ItemStack.of(Material.COD_BUCKET);
            cod.editPersistentDataContainer(pdc -> pdc.set(marker, PersistentDataType.BYTE, (byte) 1));
            hold(e2e, name, cod);
            Location down = e2e.onPlayer(name, () -> {
                Location at = e2e.player(name).getLocation();
                at.setPitch(90f);
                return at;
            });
            e2e.onPlayer(name, () -> e2e.player(name).teleportAsync(down));
            e2e.sleep(800);
            bot.useItem();
            e2e.eventually(() -> mainHand(e2e, name).getType() == Material.BUCKET, "the bucket of cod was emptied: " + mainHand(e2e, name));
            e2e.expect(mainHand(e2e, name).getPersistentDataContainer().has(marker, PersistentDataType.BYTE), "the empty bucket is marked");
            e2e.eventually(() -> e2e.onPlayer(name, () -> {
                for (org.bukkit.entity.Cod fish : e2e.player(name).getLocation().getNearbyEntitiesByType(org.bukkit.entity.Cod.class, 3)) {
                    if (fish.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) {
                        return true;
                    }
                }
                return false;
            }), "the cod that came out is marked");

            e2e.step("so the bucket can't be sold");
            bot.clearLogs();
            bot.command("sell hand");
            e2e.eventually(() -> bot.actionBarContains("Items from villager trades can't be sold."), "refused: " + bot.actionBar());

            e2e.step("filling it with water gives a marked water bucket, and emptying that a marked bucket again");
            bot.useItem();
            e2e.eventually(() -> mainHand(e2e, name).getType() == Material.WATER_BUCKET, "filled with water: " + mainHand(e2e, name));
            e2e.expect(mainHand(e2e, name).getPersistentDataContainer().has(marker, PersistentDataType.BYTE), "the water bucket is marked");
            e2e.sleep(300);
            bot.useItem();
            e2e.eventually(() -> mainHand(e2e, name).getType() == Material.BUCKET, "emptied again: " + mainHand(e2e, name));
            e2e.expect(mainHand(e2e, name).getPersistentDataContainer().has(marker, PersistentDataType.BYTE), "still marked");
            e2e.expect(e2e.money(name) == 0, "nothing was paid");
        } finally {
            e2e.onPlayer(name, () -> {
                org.bukkit.World world = e2e.player(name).getWorld();
                for (org.bukkit.entity.Cod fish : e2e.player(name).getLocation().getNearbyEntitiesByType(org.bukkit.entity.Cod.class, 4)) {
                    fish.remove();
                }
                world.getBlockAt(feet[0], feet[1], feet[2]).setType(Material.AIR, false);
                return null;
            });
        }
    }

    static void chooseItems(E2E e2e) {
        String name = e2e.name("Choose");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.setItem(0, ItemStack.of(Material.DIAMOND, 64));
            inventory.setItem(9, ItemStack.of(Material.IRON_INGOT, 10));
            inventory.setItem(10, ItemStack.of(Material.DIAMOND, 32));
            inventory.setHeldItemSlot(0);
            return null;
        });
        e2e.step("/sell hand all above $10,000 asks first");
        bot.clearLogs();
        bot.command("sell hand all");
        Bot.SeenDialog dialog = e2e.dialog(bot, "Sell all diamond");
        e2e.expect(dialog.bodyText().contains("Sell 96 items for $38,400?"), "the total: " + dialog.body());
        e2e.step("Choose items opens the sell menu with only the diamonds in it");
        e2e.click(bot, "Choose items");
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Sell items"), "the sell menu opens");
        e2e.eventually(() -> slotName(bot, 48).contains("Total $38,400"), "the diamonds are in the grid: " + slotName(bot, 48));
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds left the inventory");
        e2e.expect(count(e2e, name, Material.IRON_INGOT) == 10, "the iron stays in the inventory");
        e2e.expect(e2e.money(name) == 0, "nothing sold yet");
        e2e.step("closing the menu gives them back unsold");
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 96, "the diamonds are back");
        e2e.expect(e2e.money(name) == 0, "nothing sold");
    }

    // ------------------------------------------------------------------ shop

    static void shopSearch(E2E e2e) {
        String name = e2e.name("ShopFind");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.step("/shop search finds items across categories");
        openScreen(e2e, bot, "shop search diamond", "Shop search");
        e2e.eventually(() -> slotName(bot, 0).toLowerCase().contains("diamond"), "a diamond entry first: " + slotName(bot, 0));
        e2e.expect(lore(bot, 0).contains("In Ores"), "the category is named: " + lore(bot, 0));
        bot.closeScreen();
        e2e.step("the shop screen has a search button");
        openScreen(e2e, bot, "shop", "Shop");
        e2e.expect(slotName(bot, 39).equals("Search"), "the search button left of the balance: " + slotName(bot, 39));
        bot.clickSlot(39);
        e2e.dialog(bot, "Search");
        e2e.click(bot, "Submit", Map.of("query", "stone"));
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Shop search"), "the results");
        e2e.eventually(() -> slotName(bot, 0).toLowerCase().contains("stone"), "stone found: " + slotName(bot, 0));
        bot.closeScreen();
    }

    static void shopQuickAndBuyAgain(E2E e2e) {
        String name = e2e.name("ShopQuick");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 100");
        openScreen(e2e, bot, "shop", "Shop");
        bot.clickSlot(10);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Blocks"), "the Blocks page");
        e2e.sleep(300);
        bot.clickSlot(0);
        Bot.SeenDialog dialog = e2e.dialog(bot, "Buy stone");
        Bot.Button buy = dialog.buttons().stream().filter(button -> button.label().startsWith("Buy ") && button.label().contains(" for $"))
            .findFirst().orElse(null);
        e2e.expect(buy != null && buy.tooltip() != null, "a Buy button with a tooltip: " + dialog.buttons());
        e2e.expect(buy.tooltip().contains("Sells back for $2 each"), "the sell-back price, in the Buy tooltip: " + buy.tooltip());
        e2e.expect(buy.tooltip().contains("You carry 0"), "how many the player has: " + buy.tooltip());
        e2e.expect(!dialog.bodyText().contains("Sells back") && !dialog.bodyText().contains("per purchase"),
            "the body has the price and balance only: " + dialog.body());
        e2e.expect(dialog.button("Max you can afford").tooltip() != null && dialog.button("Fill your inventory").tooltip() != null,
            "the quick buttons explain themselves: " + dialog.buttons());
        e2e.step("Max you can afford shows the new total first");
        e2e.click(bot, "Max you can afford", Map.of("amount", 64, "exact", ""));
        Bot.SeenDialog max = e2e.dialog(bot, "Buy stone");
        e2e.expect(max.button("Buy 16 for $96") != null, "16 for $96 with $100: " + max.buttons());
        e2e.expect(max.bodyText().contains("The amount changed"), "the change is pointed out: " + max.body());
        e2e.expect(e2e.money(name) == 100, "nothing bought yet");
        e2e.click(bot, "Buy 16", Map.of("amount", 16, "exact", ""));
        e2e.eventually(() -> e2e.money(name) == 4 && count(e2e, name, Material.STONE) == 16, "bought 16 stone");
        e2e.step("Fill your inventory sizes the purchase to the free space");
        e2e.console("eco set " + name + " 100000");
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            for (int slot = 0; slot < 36; slot++) {
                if (inventory.getItem(slot) == null || inventory.getItem(slot).isEmpty()) {
                    inventory.setItem(slot, ItemStack.of(Material.DIRT, 64));
                }
            }
            inventory.setItem(35, null);
            return null;
        });
        bot.closeScreen();
        openScreen(e2e, bot, "shop blocks", "Blocks");
        bot.clearLogs();
        bot.clickSlot(0);
        e2e.dialog(bot, "Buy stone");
        e2e.click(bot, "Fill your inventory", Map.of("amount", 64, "exact", ""));
        Bot.SeenDialog fill = e2e.dialog(bot, "Buy stone");
        // one empty slot (64) plus the 48 still free in the stack of 16
        e2e.expect(fill.button("Buy 112 for $672") != null, "112 fit: " + fill.buttons());
        bot.closeScreen();
        e2e.step("the shop screen offers to buy again");
        openScreen(e2e, bot, "shop", "Shop");
        e2e.eventually(() -> slotName(bot, 29).equals("Stone"), "stone in the Buy again row: " + slotName(bot, 29));
        e2e.expect(lore(bot, 29).contains("You last bought 16 for $96"), "the last amount: " + lore(bot, 29));
        bot.clearLogs();
        bot.clickSlot(29);
        Bot.SeenDialog again = e2e.dialog(bot, "Buy stone");
        e2e.expect(again.button("Buy 16 for $96") != null, "the dialog starts at the last amount: " + again.buttons());
    }

    static void shopRightClickSell(E2E e2e) {
        String name = e2e.name("ShopSell");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        e2e.console("eco set " + name + " 0");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 10));
        openScreen(e2e, bot, "shop ores", "Ores");
        String lore = lore(bot, 9);
        e2e.expect(lore.contains("Sells back for $400 each"), "the sell-back line: " + lore);
        e2e.expect(lore.contains("Right click to sell yours"), "the right-click hint: " + lore);
        e2e.step("a right click asks to sell the player's own");
        bot.clearLogs();
        bot.clickSlot(9, 1, ContainerInput.PICKUP);
        Bot.SeenDialog dialog = e2e.dialog(bot, "Sell all diamond");
        e2e.expect(dialog.bodyText().contains("Sell 10 items for $4,000?"), "the total: " + dialog.body());
        e2e.step("Cancel goes back to the shop page, which replaces the confirmation");
        Bot.Screen page = bot.screen();
        int cleared = bot.dialogsCleared();
        e2e.expect(bot.clickButton("Cancel", Map.of()), "can click Cancel in " + dialog.buttons());
        e2e.eventually(() -> bot.screen() != null && bot.screen() != page && bot.screen().title().equals("Ores"), "back on the Ores page");
        e2e.expect(bot.dialogsCleared() == cleared, "no dialog close before the page");
        e2e.expect(e2e.money(name) == 0, "nothing sold");
        e2e.sleep(300);
        // The bot keeps its last dialog when a container replaces it (a real client shows the container), so wait for
        // a new one rather than the title.
        int seen = bot.dialogs().size();
        bot.clickSlot(9, 1, ContainerInput.PICKUP);
        e2e.eventually(() -> bot.dialogs().size() > seen && bot.dialog().title().contains("Sell all diamond"), "the confirmation again");
        e2e.click(bot, "Sell for $4,000");
        e2e.eventually(() -> e2e.money(name) == 4_000, "sold from the shop (has " + e2e.money(name) + ")");
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "the diamonds are gone");
    }
}
