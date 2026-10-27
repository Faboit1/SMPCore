package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.component.ItemLore;
import net.siftvanilla.siftcore.core.item.ItemCategories;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.Plugin;

/**
 * AxAuctions 2.7.2 (a licensed, closed-source auction plugin) driven end to end by bots, black box: only its commands,
 * menus and YAML files are used. Every scenario skips when AxAuctions is not installed, so the normal suite is
 * unaffected. Money is read and set through whatever Vault economy is registered (the TEST-ONLY TestEco on test
 * servers, SiftCore's provider later), so the same scenarios check the real integration.
 *
 * <p>The menu titles, slots and texts are the SiftVanilla configuration in {@code server/plugins/AxAuctions/}; the
 * scenarios fail if that configuration is not applied.
 */
final class AxAuctionsScenarios {

    static final String MAIN = "Auction house";
    static final String MINE = "Your listings";
    static final String EXPIRED = "Expired listings";
    static final String CONFIRM_BUY = "Confirm purchase";
    static final String CONFIRM_LIST = "Confirm listing";
    static final String CATEGORIES = "Categories";
    static final String HISTORY = "History";
    static final String DELETED = "Deleted items";
    static final String CONTENTS = "Contents";

    /** Bottom row of the main menu (same layout as SiftCore's menus). */
    static final int SLOT_PREVIOUS = 45;
    static final int SLOT_BACK = 46;
    static final int SLOT_SORT = 47;
    static final int SLOT_CATEGORY = 48;
    static final int SLOT_SEARCH = 49;
    static final int SLOT_MINE = 50;
    static final int SLOT_EXPIRED = 51;
    static final int SLOT_NEXT = 53;
    /** Both confirmation menus (3 rows): confirm left of the item, cancel right of it. */
    static final int SLOT_CONFIRM = 11;
    static final int SLOT_ITEM = 13;
    static final int SLOT_CANCEL = 15;
    /** The category menu: all items, then one row of categories. */
    static final Map<String, Integer> CATEGORY_SLOTS = categorySlots();

    /** Money colour of the design system. */
    private static final int MONEY = 0x1AFF1A;
    private static final int WHITE = 0xFFFFFF;
    private static final int GRAY = 0xAAAAAA;

    private AxAuctionsScenarios() {
    }

    private static Map<String, Integer> categorySlots() {
        Map<String, Integer> slots = new LinkedHashMap<>();
        slots.put("all", 4);
        slots.put("blocks", 9);
        slots.put("tools", 10);
        slots.put("combat", 11);
        slots.put("food", 12);
        slots.put("potions", 13);
        slots.put("books", 14);
        slots.put("spawners", 15);
        slots.put("redstone", 16);
        slots.put("misc", 17);
        return slots;
    }

    private record Named(String name, Body body) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            if (!installed(e2e)) {
                return;
            }
            STYLE.clear();
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
        list.add(of("axauctions-boot", AxAuctionsScenarios::boot));
        list.add(of("axauctions-sell-buy", AxAuctionsScenarios::sellBuy));
        list.add(of("axauctions-sell-cancel", AxAuctionsScenarios::sellCancel));
        list.add(of("axauctions-sell-amount", AxAuctionsScenarios::sellAmount));
        list.add(of("axauctions-refusals", AxAuctionsScenarios::refusals));
        list.add(of("axauctions-price-input", AxAuctionsScenarios::priceInput));
        list.add(of("axauctions-fractions", AxAuctionsScenarios::fractions));
        list.add(of("axauctions-economy-failures", AxAuctionsScenarios::economyFailures));
        list.add(of("axauctions-limit", AxAuctionsScenarios::limit));
        list.add(of("axauctions-take-down", AxAuctionsScenarios::takeDown));
        list.add(of("axauctions-search", AxAuctionsScenarios::search));
        list.add(of("axauctions-categories", AxAuctionsScenarios::categories));
        list.add(of("axauctions-category-rules", AxAuctionsScenarios::categoryRules));
        list.add(of("axauctions-shulker", AxAuctionsScenarios::shulker));
        list.add(of("axauctions-history", AxAuctionsScenarios::history));
        list.add(of("axauctions-sell-gui-moves", AxAuctionsScenarios::sellGuiMoves));
        list.add(of("axauctions-buy-race", AxAuctionsScenarios::buyRace));
        list.add(of("axauctions-tour", AxAuctionsScenarios::tour));
        list.add(of("axauctions-expiry", AxAuctionsScenarios::expiry));
        list.add(of("axauctions-deletion-time", AxAuctionsScenarios::deletionTime));
        return list;
    }

    // ------------------------------------------------------------------ setup

    private static Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin("AxAuctions");
    }

    /**
     * The share of the price AxAuctions takes from the seller, from the installed {@code currencies.yml} (SiftVanilla
     * ships {@code tax: 0}; the money checks follow whatever is set).
     */
    private static double taxRate() {
        YamlConfiguration currencies = YamlConfiguration.loadConfiguration(new File(plugin().getDataFolder(), "currencies.yml"));
        return currencies.getDouble("currencies.Vault.tax", 0) / 100.0;
    }

    static boolean installed(E2E e2e) {
        if (!running()) {
            e2e.log("AxAuctions is not installed or did not start; skipped");
            return false;
        }
        return true;
    }

    /** Whether AxAuctions runs, which makes it the server's auction house (SiftCore's own /ah is then turned off). */
    static boolean running() {
        Plugin ax = plugin();
        return ax != null && ax.isEnabled();
    }

    /** Default listing slots come from LuckPerms (axauctions.limit.3 on the default group), as on the live server. */
    private static void defaultLimit(E2E e2e) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            e2e.console("lp group default permission set axauctions.limit.3 true");
            e2e.sleep(500);
        }
    }

    private static void grantLimit(E2E e2e, String name, int slots) {
        e2e.console("lp user " + name + " permission set axauctions.limit." + slots + " true");
        e2e.sleep(1_000);
    }

    /** A bot that has joined with the default listing limit in place. */
    private static Bot join(E2E e2e, String base) {
        return e2e.bot(e2e.name(base));
    }

    /**
     * Sends a command the way a player types one. Paced, because the server kicks clients that keep sending commands
     * faster than about one a second (vanilla chat spam protection).
     */
    private static void command(E2E e2e, Bot bot, String command) {
        e2e.sleep(700);
        bot.command(command);
    }

    // ------------------------------------------------------------------ money (any Vault economy)

    /** The registered Vault (legacy) economy, used through reflection so the harness needs no Vault on its classpath. */
    static final class Money {
        private final Object economy;
        private final Method getBalance;
        private final Method withdraw;
        private final Method deposit;
        final String providerName;

        private Money(Object economy, Class<?> type) throws ReflectiveOperationException {
            this.economy = economy;
            this.getBalance = type.getMethod("getBalance", OfflinePlayer.class);
            this.withdraw = type.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            this.deposit = type.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            this.providerName = String.valueOf(type.getMethod("getName").invoke(economy));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static Money find(E2E e2e) {
            for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
                if (service.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    var registration = Bukkit.getServicesManager().getRegistration((Class) service);
                    if (registration != null) {
                        try {
                            return new Money(registration.getProvider(), service);
                        } catch (ReflectiveOperationException e) {
                            throw new E2E.Failure("the Vault economy can't be used: " + e);
                        }
                    }
                }
            }
            throw new E2E.Failure("no Vault economy is registered");
        }

        double balance(OfflinePlayer player) {
            try {
                return (double) this.getBalance.invoke(this.economy, player);
            } catch (ReflectiveOperationException e) {
                throw new E2E.Failure("getBalance failed: " + e);
            }
        }

        void set(OfflinePlayer player, double amount) {
            try {
                double current = balance(player);
                if (current > 0) {
                    this.withdraw.invoke(this.economy, player, current);
                }
                if (amount > 0) {
                    this.deposit.invoke(this.economy, player, amount);
                }
            } catch (ReflectiveOperationException e) {
                throw new E2E.Failure("setting a balance failed: " + e);
            }
            if (Math.abs(balance(player) - amount) > 1e-9) {
                throw new E2E.Failure("balance of " + player.getName() + " is " + balance(player) + " after setting " + amount);
            }
        }
    }

    /** The sum of every TestEco balance, or NaN when TestEco is not the economy. */
    private static double testEcoTotal(E2E e2e) {
        if (Bukkit.getPluginManager().getPlugin("TestEco") == null) {
            return Double.NaN;
        }
        for (String line : e2e.consoleOutput("testeco total")) {
            if (line.startsWith("TESTECO TOTAL ")) {
                return Double.parseDouble(line.substring("TESTECO TOTAL ".length()).trim());
            }
        }
        throw new E2E.Failure("testeco total printed nothing");
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    // ------------------------------------------------------------------ items and inventories

    private static void hold(E2E e2e, String name, ItemStack stack) {
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.getInventory().clear();
            player.getInventory().setHeldItemSlot(0);
            player.getInventory().setItem(0, stack == null ? null : stack.clone());
            return null;
        });
    }

    private static int count(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            int total = 0;
            Player player = e2e.player(name);
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            ItemStack cursor = player.getItemOnCursor();
            if (cursor.getType() == material) {
                total += cursor.getAmount();
            }
            return total;
        });
    }

    /** Items of this type lying on the ground within 8 blocks of the player. */
    private static int dropped(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            int total = 0;
            for (var entity : e2e.player(name).getNearbyEntities(8, 8, 8)) {
                if (entity instanceof Item item && item.getItemStack().getType() == material) {
                    total += item.getItemStack().getAmount();
                }
            }
            return total;
        });
    }

    private static ItemStack named(Material material, String name) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text(name)));
        return stack;
    }

    // ------------------------------------------------------------------ screens

    private static Bot.Screen awaitScreen(E2E e2e, Bot bot, String title) {
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals(title),
            bot.name + " sees the '" + title + "' menu");
        return bot.screen();
    }

    /** Waits for a new screen (not {@code before}) with the title. */
    private static Bot.Screen awaitNewScreen(E2E e2e, Bot bot, Bot.Screen before, String title) {
        e2e.eventually(() -> bot.screen() != null && bot.screen() != before && bot.screen().title().equals(title),
            bot.name + " sees a new '" + title + "' menu (now: " + describe(bot.screen()) + ")");
        return bot.screen();
    }

    private static String describe(Bot.Screen screen) {
        return screen == null ? "none" : "'" + screen.title() + "'";
    }

    /** Runs a command and waits for a new screen with the title. */
    private static Bot.Screen open(E2E e2e, Bot bot, String command, String title) {
        Bot.Screen before = bot.screen();
        command(e2e, bot, command);
        return awaitNewScreen(e2e, bot, before, title);
    }

    /** Clicks a slot and waits for a new screen with the title. */
    private static Bot.Screen clickTo(E2E e2e, Bot bot, int slot, String title) {
        Bot.Screen before = bot.screen();
        e2e.sleep(250);
        bot.clickSlot(slot);
        return awaitNewScreen(e2e, bot, before, title);
    }

    private static int topSize(Bot.Screen screen) {
        String type = screen == null ? "" : screen.type();
        int at = type.indexOf("generic_9x");
        if (at >= 0) {
            return 9 * (type.charAt(at + "generic_9x".length()) - '0');
        }
        return 27;
    }

    /** The items of the menu part of the open screen, by slot. */
    private static Map<Integer, net.minecraft.world.item.ItemStack> top(Bot bot) {
        int size = topSize(bot.screen());
        Map<Integer, net.minecraft.world.item.ItemStack> items = new TreeMap<>();
        bot.screenItems().forEach((slot, stack) -> {
            if (slot < size) {
                items.put(slot, stack);
            }
        });
        return items;
    }

    static String id(net.minecraft.world.item.ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    static String name(net.minecraft.world.item.ItemStack stack) {
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            return custom.getString();
        }
        return stack.getHoverName().getString();
    }

    static List<String> lore(net.minecraft.world.item.ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
        }
        return lines;
    }

    /** The first menu slot holding an item of this id whose lore has a line equal to {@code line} (null: any). */
    private static Integer slot(Bot bot, String itemId, String line) {
        for (var entry : top(bot).entrySet()) {
            var stack = entry.getValue();
            if (id(stack).equals(itemId) && (line == null || lore(stack).contains(line))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static int awaitSlot(E2E e2e, Bot bot, String itemId, String line) {
        e2e.eventually(() -> slot(bot, itemId, line) != null, bot.name + " sees " + itemId + (line == null ? "" : " with '" + line + "'")
            + " in " + describe(bot.screen()));
        return slot(bot, itemId, line);
    }

    /** Item ids of one seller's listings in the open menu. */
    private static List<String> listingsOf(Bot bot, String seller) {
        List<String> ids = new ArrayList<>();
        for (var entry : top(bot).entrySet()) {
            if (lore(entry.getValue()).contains("Seller " + seller)) {
                ids.add(id(entry.getValue()));
            }
        }
        return ids;
    }

    /** Logs every item of the open menu with its name and lore (for reviews of the look). */
    private static void dump(E2E e2e, Bot bot, String label) {
        Bot.Screen screen = bot.screen();
        e2e.log("[" + label + "] screen " + describe(screen) + " type " + (screen == null ? "-" : screen.type()));
        for (var entry : top(bot).entrySet()) {
            var stack = entry.getValue();
            e2e.log("  slot " + entry.getKey() + ": " + id(stack) + " x" + stack.getCount() + " '" + name(stack) + "' "
                + String.join(" | ", lore(stack)));
        }
    }

    // ------------------------------------------------------------------ design system checks

    /** Every problem found by the style checks of one scenario. */
    private static final List<String> STYLE = new CopyOnWriteArrayList<>();

    /** Checks text against the design system: white, gray or money green only, no bold, money always green. */
    private static void lint(String where, Component text, Style base, boolean noItalic, List<String> problems) {
        if (text == null) {
            return;
        }
        text.visit((style, segment) -> {
            if (segment.isBlank()) {
                return Optional.empty();
            }
            TextColor color = style.getColor();
            int rgb = color == null ? WHITE : color.getValue();
            if (rgb != WHITE && rgb != GRAY && rgb != MONEY) {
                problems.add(where + ": '" + segment + "' has colour " + String.format("#%06X", rgb));
            }
            if (style.isBold() || style.isUnderlined() || style.isStrikethrough() || style.isObfuscated()) {
                problems.add(where + ": '" + segment + "' is bold, underlined, struck or obfuscated");
            }
            if (noItalic && style.isItalic()) {
                problems.add(where + ": '" + segment + "' is italic");
            }
            if (segment.contains("$") && rgb != MONEY) {
                problems.add(where + ": money '" + segment + "' is not " + String.format("#%06X", MONEY));
            }
            if (rgb == MONEY && !segment.matches("\\$[0-9,]+.*")) {
                problems.add(where + ": '" + segment + "' is money green but not an amount");
            }
            if (segment.matches(".*\\s[.,!?:;]$") || segment.contains("  ")) {
                problems.add(where + ": '" + segment + "' has stray spaces");
            }
            return Optional.empty();
        }, base);
    }

    private static final Style NAME_BASE = Style.EMPTY.withItalic(true);
    private static final Style LORE_BASE = Style.EMPTY.withItalic(true).withColor(ChatFormatting.DARK_PURPLE);
    private static final Set<String> OUR_LINES = Set.of("Price", "Seller", "Ends in", "Click", "Your listing", "Deleted",
        "Buyer", "Sold", "Right click", "Shift click");

    /** Checks a menu: its title (plain) and every button; for listed items only the lines this config adds. */
    private static List<String> lintScreen(Bot bot, String label) {
        List<String> problems = new ArrayList<>();
        Bot.Screen screen = bot.screen();
        if (screen == null) {
            return problems;
        }
        screen.titleComponent().visit((style, segment) -> {
            if (!segment.isBlank() && (style.getColor() != null || style.isBold())) {
                problems.add(label + " title: '" + segment + "' is styled");
            }
            return Optional.empty();
        }, Style.EMPTY);
        for (var entry : top(bot).entrySet()) {
            var stack = entry.getValue();
            String where = label + " slot " + entry.getKey();
            if (label.equals(CONTENTS) && entry.getKey() >= 4 && entry.getKey() < 36 && entry.getKey() != 40) {
                continue; // the previewed container and its contents are the seller's items
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            Component custom = stack.get(DataComponents.CUSTOM_NAME);
            if (custom != null && !isListing(stack)) {
                lint(where + " name", custom, NAME_BASE, true, problems);
            }
            if (lore != null) {
                for (Component line : lore.lines()) {
                    String plain = line.getString();
                    if (isListing(stack) && OUR_LINES.stream().noneMatch(plain::startsWith)) {
                        continue;
                    }
                    lint(where + " lore", line, LORE_BASE, true, problems);
                }
            }
        }
        STYLE.addAll(problems);
        return problems;
    }

    /** A listed item shows its own name; buttons are the only items this config names. */
    private static boolean isListing(net.minecraft.world.item.ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore != null && lore.lines().stream().anyMatch(line -> line.getString().startsWith("Price "));
    }

    /** Vanilla chat (advancements are announced on test servers; the live server turns that off). */
    private static final List<String> VANILLA_CHAT = List.of("has made the advancement", "has reached the goal",
        "has completed the challenge");

    private static List<String> lintChat(Bot bot, String label) {
        List<String> problems = new ArrayList<>();
        for (Component line : bot.chatComponents()) {
            if (VANILLA_CHAT.stream().anyMatch(line.getString()::contains)) {
                continue;
            }
            lint(label + " chat '" + line.getString() + "'", line, Style.EMPTY, false, problems);
        }
        STYLE.addAll(problems);
        return problems;
    }

    // ------------------------------------------------------------------ flows

    /**
     * Lists what the bot holds with {@code /ah sell <args>}: the confirmation menu shows the price, then the bot
     * confirms. Returns the confirmation menu's item lore.
     */
    private static List<String> sell(E2E e2e, Bot bot, String args, String expectPrice) {
        bot.clearLogs();
        open(e2e, bot, "ah sell " + args, CONFIRM_LIST);
        e2e.eventually(() -> top(bot).containsKey(SLOT_ITEM) && top(bot).containsKey(SLOT_CONFIRM), "the listing confirmation is filled");
        List<String> lore = lore(top(bot).get(SLOT_ITEM));
        e2e.expect(lore.contains("Price " + expectPrice), "the confirmation shows 'Price " + expectPrice + "': " + lore);
        lintScreen(bot, CONFIRM_LIST);
        e2e.sleep(250);
        bot.clickSlot(SLOT_CONFIRM);
        e2e.eventually(() -> bot.chatContains("Listed on the auction house"), bot.name + " is told the item was listed (chat " + bot.chat() + ")");
        lintChat(bot, "listed");
        return lore;
    }

    /** Opens /ah, finds the seller's listing of this item, clicks it and confirms the purchase. */
    private static void buy(E2E e2e, Bot buyer, String itemId, String seller) {
        open(e2e, buyer, "ah", MAIN);
        int slot = awaitSlot(e2e, buyer, itemId, "Seller " + seller);
        buyer.clearLogs();
        clickTo(e2e, buyer, slot, CONFIRM_BUY);
        e2e.eventually(() -> top(buyer).containsKey(SLOT_CONFIRM), "the purchase confirmation is filled");
        lintScreen(buyer, CONFIRM_BUY);
        e2e.sleep(250);
        buyer.clickSlot(SLOT_CONFIRM);
    }

    /** Opens the seller's listings and takes every one down, so scenarios leave the auction house as they found it. */
    private static void takeDownAll(E2E e2e, Bot bot) {
        open(e2e, bot, "ah", MAIN);
        clickTo(e2e, bot, SLOT_MINE, MINE);
        for (int round = 0; round < 20; round++) {
            e2e.sleep(1_000);
            Integer slot = null;
            for (var entry : top(bot).entrySet()) {
                if (entry.getKey() < 45 && isListing(entry.getValue())) {
                    slot = entry.getKey();
                    break;
                }
            }
            if (slot == null) {
                return;
            }
            bot.clickSlot(slot);
            e2e.sleep(500);
            if (bot.screen() == null || !bot.screen().title().equals(MINE)) {
                open(e2e, bot, "ah", MAIN);
                clickTo(e2e, bot, SLOT_MINE, MINE);
            }
        }
    }

    private static String papi(E2E e2e, String player, String placeholder) {
        List<String> out = e2e.consoleOutput("papi parse " + player + " " + placeholder);
        return out.isEmpty() ? "" : out.getLast().trim();
    }

    // ------------------------------------------------------------------ scenarios

    /** The plugin is up, hooked to Vault, owns /ah and its placeholders work. */
    static void boot(E2E e2e) {
        defaultLimit(e2e);
        Plugin ax = plugin();
        e2e.step("plugin and economy");
        e2e.log("AxAuctions " + ax.getPluginMeta().getVersion());
        Money money = Money.find(e2e);
        e2e.log("Vault economy: " + money.providerName);
        e2e.step("command owner");
        Command ah = Bukkit.getCommandMap().getCommand("ah");
        String owner = ah instanceof PluginIdentifiableCommand identifiable ? identifiable.getPlugin().getName() : String.valueOf(ah);
        e2e.log("/ah is " + (ah == null ? "missing" : ah.getClass().getName()) + " owned by " + owner);
        Bot bot = join(e2e, "AxBoot");
        e2e.step("/ah opens the AxAuctions main menu");
        open(e2e, bot, "ah", MAIN);
        e2e.eventually(() -> top(bot).containsKey(SLOT_EXPIRED), "the menu buttons load");
        e2e.expect(name(top(bot).get(SLOT_EXPIRED)).equals("Expired listings"),
            "/ah is AxAuctions (slot 51 is 'Expired listings', SiftCore's own /ah has its claim box there): "
                + name(top(bot).get(SLOT_EXPIRED)) + ". Turn SiftCore's ah command off in plugins/SiftCore/commands.yml");
        dump(e2e, bot, "main");
        e2e.expect(lintScreen(bot, MAIN).isEmpty(), "the main menu follows the design system: " + STYLE);
        e2e.step("placeholders");
        String limit = papi(e2e, bot.name, "%axauctions_sell_limit%");
        String used = papi(e2e, bot.name, "%axauctions_sell_count%");
        e2e.log("sell_limit=" + limit + " sell_count=" + used + " total_active=" + papi(e2e, bot.name, "%axauctions_total_active_count%"));
        e2e.expect(limit.equals("3"), "%axauctions_sell_limit% is 3 by default: '" + limit + "'");
        e2e.expect(used.equals("0"), "%axauctions_sell_count% is 0: '" + used + "'");
    }

    /** List with /ah sell, buy through the menus, money (less the tax, none as shipped) and the item move once. */
    static void sellBuy(E2E e2e) {
        defaultLimit(e2e);
        Money money = Money.find(e2e);
        Bot seller = join(e2e, "AxSeller");
        Bot buyer = join(e2e, "AxBuyer");
        Player sellerPlayer = e2e.player(seller.name);
        Player buyerPlayer = e2e.player(buyer.name);
        money.set(sellerPlayer, 0);
        money.set(buyerPlayer, 1_000);
        double totalBefore = testEcoTotal(e2e);

        e2e.step("list 16 diamonds for $100");
        hold(e2e, seller.name, ItemStack.of(Material.DIAMOND, 16));
        sell(e2e, seller, "100", "$100");
        e2e.eventually(() -> count(e2e, seller.name, Material.DIAMOND) == 0, "the diamonds left the seller's inventory");
        e2e.expect(seller.chatContains("Listed on the auction house for $100."), "the listing message: " + seller.chat());

        e2e.step("the buyer sees the listing");
        open(e2e, buyer, "ah", MAIN);
        int slot = awaitSlot(e2e, buyer, "diamond", "Seller " + seller.name);
        List<String> lore = lore(top(buyer).get(slot));
        e2e.log("listing lore: " + lore);
        e2e.expect(lore.contains("Price $100"), "the price line: " + lore);
        e2e.expect(lore.stream().anyMatch(line -> line.startsWith("Ends in ")), "the time left: " + lore);
        e2e.expect(lore.contains("Click to buy"), "the buy hint: " + lore);
        e2e.expect(top(buyer).get(slot).getCount() == 16, "the listing shows 16 diamonds");
        e2e.expect(lintScreen(buyer, MAIN).isEmpty(), "the listing follows the design system: " + STYLE);

        e2e.step("buy it");
        buyer.clearLogs();
        seller.clearLogs();
        clickTo(e2e, buyer, slot, CONFIRM_BUY);
        e2e.eventually(() -> top(buyer).containsKey(SLOT_CONFIRM) && top(buyer).containsKey(SLOT_ITEM), "the purchase confirmation is filled");
        dump(e2e, buyer, "confirm purchase");
        e2e.expect(lintScreen(buyer, CONFIRM_BUY).isEmpty(), "the confirmation follows the design system: " + STYLE);
        e2e.sleep(250);
        buyer.clickSlot(SLOT_CONFIRM);
        e2e.eventually(() -> count(e2e, buyer.name, Material.DIAMOND) == 16, "the buyer has the 16 diamonds");
        e2e.eventually(() -> near(money.balance(buyerPlayer), 900), "the buyer paid $100 (" + money.balance(buyerPlayer) + ")");
        double tax = 100 * taxRate();
        e2e.eventually(() -> near(money.balance(sellerPlayer), 100 - tax), "the seller got $100 less the tax of $" + tax + " ("
            + money.balance(sellerPlayer) + ")");
        e2e.eventually(() -> buyer.chatContains("You bought"), "the buyer's receipt (chat " + buyer.chat() + ")");
        e2e.eventually(() -> seller.chatContains("bought your"), "the seller is told (chat " + seller.chat() + ")");
        e2e.log("buyer chat: " + buyer.chat() + " seller chat: " + seller.chat());
        lintChat(buyer, "buyer");
        lintChat(seller, "seller");

        e2e.step("money is conserved: the only change is the tax (none as shipped)");
        double totalAfter = testEcoTotal(e2e);
        if (!Double.isNaN(totalBefore)) {
            e2e.expect(near(totalBefore - totalAfter, tax), "all balances fell by exactly the tax of $" + tax + ": " + totalBefore + " -> "
                + totalAfter);
        }
        e2e.expect(near(money.balance(buyerPlayer) + money.balance(sellerPlayer), 1_000 - tax), "buyer + seller = 1000 - tax");

        e2e.step("the listing is gone");
        e2e.sleep(1_000);
        open(e2e, buyer, "ah", MAIN);
        e2e.sleep(1_500);
        e2e.expect(slot(buyer, "diamond", "Seller " + seller.name) == null, "the sold listing is no longer shown");
        e2e.expect(papi(e2e, seller.name, "%axauctions_sell_count%").equals("0"), "the seller has no listing left");
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /** Cancelling the listing confirmation lists nothing and keeps the item in hand. */
    static void sellCancel(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxNoList");
        hold(e2e, seller.name, ItemStack.of(Material.EMERALD, 5));
        open(e2e, seller, "ah sell 50", CONFIRM_LIST);
        e2e.eventually(() -> top(seller).containsKey(SLOT_CANCEL), "the cancel button loads");
        dump(e2e, seller, "confirm listing");
        e2e.sleep(250);
        seller.clickSlot(SLOT_CANCEL);
        e2e.sleep(1_500);
        e2e.log("after cancel: screen " + describe(seller.screen()) + " chat " + seller.chat());
        e2e.expect(count(e2e, seller.name, Material.EMERALD) == 5, "the emeralds are still in the inventory");
        e2e.expect(papi(e2e, seller.name, "%axauctions_sell_count%").equals("0"), "nothing was listed");
    }

    /** /ah sell <price> <amount> lists part of the stack. */
    static void sellAmount(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxPart");
        hold(e2e, seller.name, ItemStack.of(Material.IRON_INGOT, 16));
        sell(e2e, seller, "40 5", "$40");
        e2e.eventually(() -> count(e2e, seller.name, Material.IRON_INGOT) == 11, "11 iron ingots are left (" + count(e2e, seller.name, Material.IRON_INGOT) + ")");
        open(e2e, seller, "ah", MAIN);
        clickTo(e2e, seller, SLOT_MINE, MINE);
        int slot = awaitSlot(e2e, seller, "iron_ingot", "Price $40");
        e2e.expect(top(seller).get(slot).getCount() == 5, "the listing holds 5 ingots");
        dump(e2e, seller, "my items");
        e2e.expect(lintScreen(seller, MINE).isEmpty(), "your listings follow the design system: " + STYLE);
        takeDownAll(e2e, seller);
        e2e.eventually(() -> count(e2e, seller.name, Material.IRON_INGOT) == 16, "all 16 ingots are back");
    }

    /**
     * Tries /ah sell with these arguments. If the listing confirmation opens, it is confirmed (a refusal may only
     * come then). Returns what happened, for the log.
     */
    private static String attemptSell(E2E e2e, Bot bot, String args) {
        bot.clearLogs();
        Bot.Screen before = bot.screen();
        command(e2e, bot, "ah sell " + args);
        e2e.sleep(1_500);
        StringBuilder outcome = new StringBuilder();
        if (bot.screen() != null && bot.screen() != before && bot.screen().title().equals(CONFIRM_LIST)) {
            e2e.eventually(() -> top(bot).containsKey(SLOT_ITEM), "the confirmation loads");
            outcome.append("confirmation ").append(lore(top(bot).get(SLOT_ITEM))).append(", confirmed: ");
            bot.clickSlot(SLOT_CONFIRM);
            e2e.sleep(1_500);
            bot.closeScreen();
        }
        outcome.append("chat ").append(bot.chat());
        return outcome.toString();
    }

    /** Prices out of range, bad numbers, blacklisted items and empty hands are refused with SiftVanilla text. */
    static void refusals(E2E e2e) {
        defaultLimit(e2e);
        Bot bot = join(e2e, "AxRefuse");
        record Case(String args, Material item, String expect) {
        }
        List<Case> cases = List.of(
            new Case("0", Material.DIRT, "The price must be at least $1."),
            new Case("0.5", Material.DIRT, "The price must be at least $1."),
            new Case("-5", Material.DIRT, "The price must be at least $1."),
            new Case("10000000001", Material.DIRT, "The price can be at most $10,000,000,000."),
            new Case("abc", Material.DIRT, "That price isn't a number."),
            new Case("NaN", Material.DIRT, "That price isn't a number."),
            new Case("Infinity", Material.DIRT, "That price isn't a number."),
            new Case("10", Material.BEDROCK, "That item can't be sold on the auction house."),
            new Case("10", Material.ZOMBIE_SPAWN_EGG, "That item can't be sold on the auction house."),
            new Case("10", null, "Hold the item you want to sell."));
        List<String> report = new ArrayList<>();
        for (Case c : cases) {
            e2e.step("/ah sell " + c.args() + " holding " + c.item());
            hold(e2e, bot.name, c.item() == null ? null : ItemStack.of(c.item(), 1));
            String outcome = attemptSell(e2e, bot, c.args());
            report.add(c.args() + "/" + c.item() + " -> " + outcome);
            e2e.log(c.args() + "/" + c.item() + " -> " + outcome);
            lintChat(bot, "refusal " + c.args());
            String listed = papi(e2e, bot.name, "%axauctions_sell_count%");
            if (!listed.equals("0")) {
                takeDownAll(e2e, bot);
                bot.closeScreen();
            }
            e2e.expect(listed.equals("0"), "/ah sell " + c.args() + " with " + c.item() + " lists nothing: " + outcome);
            if (c.expect() != null) {
                e2e.expect(bot.chatContains(c.expect()), "'" + c.expect() + "' for /ah sell " + c.args() + ": " + outcome);
            }
        }
        e2e.log("refusals: " + String.join("; ", report));
        e2e.expect(papi(e2e, bot.name, "%axauctions_sell_count%").equals("0"), "nothing was listed");
        e2e.expect(STYLE.isEmpty(), "every refusal follows the design system: " + STYLE);
    }

    /**
     * How /ah sell reads prices that are not plain whole numbers (logged, not judged: the confirmation shows the price
     * that will be used). Each accepted one is taken down again.
     */
    static void priceInput(E2E e2e) {
        defaultLimit(e2e);
        Bot bot = join(e2e, "AxPrices");
        List<String> report = new ArrayList<>();
        for (String args : List.of("1e3", "1k", "2.5m", "10,000", "100abc", "0x10", "10.5", "99.999", "1_000", " 50")) {
            e2e.step("/ah sell " + args);
            hold(e2e, bot.name, ItemStack.of(Material.DIRT, 1));
            String outcome = attemptSell(e2e, bot, args);
            String listed = papi(e2e, bot.name, "%axauctions_sell_count%");
            report.add("'" + args + "' -> " + outcome + " listed=" + listed);
            e2e.log("'" + args + "' -> " + outcome + " listed=" + listed);
            if (!listed.equals("0")) {
                takeDownAll(e2e, bot);
                bot.closeScreen();
            }
        }
        e2e.log("price input: " + String.join(" || ", report));
    }

    /**
     * Fractional money: with a tax, a whole-dollar price that is not a multiple of $20 leaves the seller a fraction
     * (at 5%), and AxAuctions accepts decimal prices. Records exactly what the Vault economy is asked to move.
     */
    static void fractions(E2E e2e) {
        defaultLimit(e2e);
        Money money = Money.find(e2e);
        Bot seller = join(e2e, "AxFracS");
        Bot buyer = join(e2e, "AxFracB");
        for (String price : List.of("10", "10.5", "33")) {
            e2e.step("sell and buy at " + price);
            money.set(e2e.player(seller.name), 0);
            money.set(e2e.player(buyer.name), 100);
            double before = testEcoTotal(e2e);
            hold(e2e, seller.name, ItemStack.of(Material.CLAY_BALL, 1));
            bot(e2e, seller, price);
            buy(e2e, buyer, "clay_ball", seller.name);
            e2e.eventually(() -> count(e2e, buyer.name, Material.CLAY_BALL) == 1, "the buyer got the clay ball");
            e2e.sleep(1_000);
            double paid = 100 - money.balance(e2e.player(buyer.name));
            double got = money.balance(e2e.player(seller.name));
            double after = testEcoTotal(e2e);
            e2e.log("price " + price + ": buyer paid " + paid + ", seller got " + got + ", tax " + (paid - got)
                + ", total " + before + " -> " + after + "; seller chat " + seller.chat() + " buyer chat " + buyer.chat());
            e2e.expect(near(paid - got, paid * taxRate()), "the tax is " + (100 * taxRate()) + "% of what the buyer paid");
            if (!Double.isNaN(before)) {
                e2e.expect(near(before - after, paid - got), "money changed by exactly the tax");
            }
            seller.clearLogs();
            buyer.clearLogs();
            hold(e2e, buyer.name, null);
        }
    }

    /** Lists the held item at this price through the confirmation (no price check). */
    private static void bot(E2E e2e, Bot seller, String price) {
        seller.clearLogs();
        open(e2e, seller, "ah sell " + price, CONFIRM_LIST);
        e2e.eventually(() -> top(seller).containsKey(SLOT_CONFIRM), "the confirmation loads");
        e2e.sleep(250);
        seller.clickSlot(SLOT_CONFIRM);
        e2e.eventually(() -> seller.chatContains("Listed on the auction house"), "listed at " + price + " (chat " + seller.chat() + ")");
    }

    /**
     * The economy refuses a withdrawal or a deposit (TestEco can be told to): a refused withdrawal must not hand
     * over the item; a refused deposit to the seller is recorded to see what AxAuctions does.
     */
    static void economyFailures(E2E e2e) {
        if (Bukkit.getPluginManager().getPlugin("TestEco") == null) {
            e2e.log("needs the TEST-ONLY TestEco economy; skipped");
            return;
        }
        defaultLimit(e2e);
        Money money = Money.find(e2e);
        Bot seller = join(e2e, "AxFailS");
        Bot buyer = join(e2e, "AxFailB");
        try {
            e2e.step("the buyer's withdrawal is refused");
            money.set(e2e.player(seller.name), 0);
            money.set(e2e.player(buyer.name), 500);
            hold(e2e, seller.name, ItemStack.of(Material.EMERALD_BLOCK, 2));
            bot(e2e, seller, "200");
            double before = testEcoTotal(e2e);
            e2e.console("testeco fail withdraw " + buyer.name + " 1");
            buy(e2e, buyer, "emerald_block", seller.name);
            e2e.sleep(3_000);
            int got = count(e2e, buyer.name, Material.EMERALD_BLOCK);
            String listed = papi(e2e, seller.name, "%axauctions_sell_count%");
            e2e.log("refused withdrawal: buyer items " + got + ", buyer $" + money.balance(e2e.player(buyer.name)) + ", seller $"
                + money.balance(e2e.player(seller.name)) + ", seller listings " + listed + ", buyer chat " + buyer.chat());
            e2e.expect(got == 0, "no item without payment");
            e2e.expect(near(money.balance(e2e.player(seller.name)), 0), "the seller is not paid for a failed payment");
            e2e.expect(near(before, testEcoTotal(e2e)), "no money moved");
            e2e.expect(listed.equals("1"), "the listing is still up");
            lintChat(buyer, "withdraw refused");

            e2e.step("the seller's deposit is refused");
            buyer.closeScreen();
            buyer.clearLogs();
            e2e.console("testeco reset");
            e2e.console("testeco fail deposit " + seller.name + " 1");
            before = testEcoTotal(e2e);
            buy(e2e, buyer, "emerald_block", seller.name);
            e2e.sleep(3_000);
            int items = count(e2e, buyer.name, Material.EMERALD_BLOCK);
            double after = testEcoTotal(e2e);
            e2e.log("refused deposit: buyer items " + items + ", buyer $" + money.balance(e2e.player(buyer.name)) + ", seller $"
                + money.balance(e2e.player(seller.name)) + ", total " + before + " -> " + after + ", listings "
                + papi(e2e, seller.name, "%axauctions_sell_count%") + ", buyer chat " + buyer.chat() + ", seller chat " + seller.chat());
        } finally {
            e2e.console("testeco reset");
        }
    }

    /** Three listing slots by default; the fourth is refused until a rank permission raises the limit. */
    static void limit(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxLimit");
        Material[] items = {Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.JUNGLE_LOG};
        for (int i = 0; i < 3; i++) {
            e2e.step("listing " + (i + 1));
            hold(e2e, seller.name, ItemStack.of(items[i], 1));
            sell(e2e, seller, "10", "$10");
        }
        e2e.expect(papi(e2e, seller.name, "%axauctions_sell_count%").equals("3"), "3 listings are up");
        e2e.step("the fourth is refused");
        hold(e2e, seller.name, ItemStack.of(items[3], 1));
        String outcome = attemptSell(e2e, seller, "10");
        e2e.log("fourth listing: " + outcome);
        e2e.expect(seller.chatContains("All 3 of your listing slots are in use."), "the limit message: " + outcome);
        e2e.expect(papi(e2e, seller.name, "%axauctions_sell_count%").equals("3"), "still 3 listings");
        e2e.expect(count(e2e, seller.name, items[3]) == 1, "the fourth log stays in the inventory");
        lintChat(seller, "limit");
        e2e.step("a rank with 5 slots can list it");
        grantLimit(e2e, seller.name, 5);
        e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_sell_limit%").equals("5"), "the limit placeholder shows 5");
        sell(e2e, seller, "10", "$10");
        e2e.expect(papi(e2e, seller.name, "%axauctions_sell_count%").equals("4"), "4 listings are up");
        e2e.step("clean up");
        takeDownAll(e2e, seller);
        e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_sell_count%").equals("0"), "every listing was taken down");
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /** Taking a listing down from your listings gives the item back. */
    static void takeDown(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxTakeDn");
        Bot other = join(e2e, "AxWatch");
        hold(e2e, seller.name, ItemStack.of(Material.GOLD_INGOT, 7));
        sell(e2e, seller, "70", "$70");
        e2e.step("your listings show it with the take-down hint");
        open(e2e, seller, "ah", MAIN);
        clickTo(e2e, seller, SLOT_MINE, MINE);
        int slot = awaitSlot(e2e, seller, "gold_ingot", "Price $70");
        List<String> lore = lore(top(seller).get(slot));
        e2e.expect(lore.contains("Your listing. Click to take it down."), "the take-down hint: " + lore);
        dump(e2e, seller, "your listings");
        e2e.step("take it down");
        seller.clearLogs();
        e2e.sleep(250);
        seller.clickSlot(slot);
        e2e.eventually(() -> count(e2e, seller.name, Material.GOLD_INGOT) == 7, "the 7 gold ingots are back");
        e2e.eventually(() -> seller.chatContains("Listing taken down."), "the take-down message (chat " + seller.chat() + ")");
        lintChat(seller, "take down");
        e2e.step("nobody can buy it any more");
        open(e2e, other, "ah", MAIN);
        e2e.sleep(1_500);
        e2e.expect(slot(other, "gold_ingot", "Seller " + seller.name) == null, "the listing is gone for other players");
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /** Search by name through the sign input and with /ah search <text>. */
    static void search(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxSearchS");
        Bot buyer = join(e2e, "AxSearchB");
        String marker = "Glimmer" + seller.name.substring(seller.name.length() - 3);
        hold(e2e, seller.name, named(Material.IRON_SWORD, marker + " blade"));
        sell(e2e, seller, "25", "$25");
        hold(e2e, seller.name, ItemStack.of(Material.COBBLESTONE, 32));
        sell(e2e, seller, "15", "$15");

        e2e.step("search button opens the sign input");
        open(e2e, buyer, "ah", MAIN);
        awaitSlot(e2e, buyer, "cobblestone", "Seller " + seller.name);
        buyer.clearLogs();
        e2e.sleep(250);
        buyer.clickSlot(SLOT_SEARCH);
        e2e.eventually(() -> buyer.signEditor() != null, "a sign editor opens for the search text");
        Bot.Screen before = buyer.screen();
        e2e.sleep(300);
        buyer.signDone(marker.toLowerCase(Locale.ROOT));
        awaitNewScreen(e2e, buyer, before, MAIN);
        e2e.eventually(() -> listingsOf(buyer, seller.name).equals(List.of("iron_sword")),
            "only the named sword matches (" + listingsOf(buyer, seller.name) + ")");
        Integer searchButton = SLOT_SEARCH;
        e2e.log("search button: " + lore(top(buyer).get(searchButton)));
        e2e.expect(lore(top(buyer).get(searchButton)).contains("Current search " + marker.toLowerCase(Locale.ROOT)),
            "the search button shows the search");
        e2e.step("shift click clears the search");
        e2e.sleep(250);
        buyer.shiftClick(SLOT_SEARCH);
        e2e.eventually(() -> listingsOf(buyer, seller.name).containsAll(List.of("iron_sword", "cobblestone")), "both listings show again ("
            + listingsOf(buyer, seller.name) + ")");

        e2e.step("/ah search <text>");
        buyer.closeScreen();
        open(e2e, buyer, "ah search cobblestone", MAIN);
        e2e.eventually(() -> listingsOf(buyer, seller.name).equals(List.of("cobblestone")), "only cobblestone matches ("
            + listingsOf(buyer, seller.name) + ")");
        buyer.shiftClick(SLOT_SEARCH);
        e2e.sleep(500);
        e2e.step("clean up");
        takeDownAll(e2e, seller);
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /** One listing per category; each category shows exactly its own item. */
    static void categories(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxCatS");
        Bot buyer = join(e2e, "AxCatB");
        grantLimit(e2e, seller.name, 15);
        Map<String, ItemStack> items = new LinkedHashMap<>();
        items.put("blocks", ItemStack.of(Material.OAK_PLANKS, 8));
        items.put("tools", ItemStack.of(Material.IRON_PICKAXE));
        items.put("combat", ItemStack.of(Material.DIAMOND_SWORD));
        items.put("food", ItemStack.of(Material.BREAD, 4));
        items.put("potions", ItemStack.of(Material.POTION));
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(Enchantment.SHARPNESS, 5, true));
        items.put("books", book);
        items.put("spawners", ItemStack.of(Material.SPAWNER));
        items.put("redstone", ItemStack.of(Material.REPEATER, 2));
        items.put("misc", ItemStack.of(Material.STICK, 3));
        for (var entry : items.entrySet()) {
            e2e.step("list " + entry.getKey());
            hold(e2e, seller.name, entry.getValue());
            sell(e2e, seller, "5", "$5");
        }
        open(e2e, buyer, "ah", MAIN);
        awaitSlot(e2e, buyer, "stick", "Seller " + seller.name);
        for (var entry : items.entrySet()) {
            String category = entry.getKey();
            String expected = BuiltInRegistries.ITEM.getKey(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(entry.getValue()).getItem()).getPath();
            e2e.step("category " + category);
            clickTo(e2e, buyer, SLOT_CATEGORY, CATEGORIES);
            if (category.equals("blocks")) {
                dump(e2e, buyer, "categories");
                e2e.expect(lintScreen(buyer, CATEGORIES).isEmpty(), "the category menu follows the design system: " + STYLE);
            }
            e2e.eventually(() -> top(buyer).containsKey(CATEGORY_SLOTS.get(category)), "the category buttons load");
            clickTo(e2e, buyer, CATEGORY_SLOTS.get(category), MAIN);
            e2e.eventually(() -> listingsOf(buyer, seller.name).equals(List.of(expected)),
                category + " shows only " + expected + " (" + listingsOf(buyer, seller.name) + ")");
            e2e.expect(lore(top(buyer).get(SLOT_CATEGORY)).stream().anyMatch(line -> line.startsWith("Showing ")),
                "the category button says what is shown: " + lore(top(buyer).get(SLOT_CATEGORY)));
            e2e.log(category + ": " + lore(top(buyer).get(SLOT_CATEGORY)));
        }
        e2e.step("all items");
        clickTo(e2e, buyer, SLOT_CATEGORY, CATEGORIES);
        e2e.eventually(() -> top(buyer).containsKey(CATEGORY_SLOTS.get("all")), "the category buttons load");
        clickTo(e2e, buyer, CATEGORY_SLOTS.get("all"), MAIN);
        e2e.eventually(() -> listingsOf(buyer, seller.name).size() == items.size(), "all items shows every listing ("
            + listingsOf(buyer, seller.name) + ")");
        e2e.step("clean up");
        takeDownAll(e2e, seller);
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /**
     * categories.yml puts every sellable item in exactly one category, the same one SiftCore's own auction house uses,
     * plus redstone (see {@link #redstone}) for items SiftCore calls blocks or misc. Writes the expected
     * classification to plugins/SiftE2E/axauctions-categories.tsv, from which categories.yml is generated.
     */
    @SuppressWarnings("deprecation")
    static void categoryRules(E2E e2e) throws Exception {
        Plugin ax = plugin();
        YamlConfiguration categories = YamlConfiguration.loadConfiguration(new File(ax.getDataFolder(), "categories.yml"));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(ax.getDataFolder(), "config.yml"));
        e2e.expect(categories.getBoolean("enabled"), "categories are enabled");
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        for (String key : categories.getKeys(false)) {
            if (!categories.isConfigurationSection(key)) {
                continue;
            }
            List<String> list = new ArrayList<>();
            for (Map<?, ?> entry : categories.getMapList(key + ".items")) {
                Object material = entry.get("material");
                if (material != null) {
                    list.add(material.toString());
                }
            }
            patterns.put(key, list);
        }
        e2e.expect(patterns.keySet().equals(Set.copyOf(CATEGORY_SLOTS.keySet())) && patterns.size() == CATEGORY_SLOTS.size(),
            "the categories are exactly " + CATEGORY_SLOTS.keySet() + ": " + patterns.keySet());
        List<String> blacklist = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("blacklist-items")) {
            Object material = entry.get("material");
            if (material != null) {
                blacklist.add(material.toString());
            }
        }
        Map<String, Tag<Material>> tags = new LinkedHashMap<>();
        tags.put("swords", Tag.ITEMS_SWORDS);
        tags.put("spears", Tag.ITEMS_SPEARS);
        tags.put("axes", Tag.ITEMS_AXES);
        tags.put("pickaxes", Tag.ITEMS_PICKAXES);
        tags.put("shovels", Tag.ITEMS_SHOVELS);
        tags.put("hoes", Tag.ITEMS_HOES);
        tags.put("head_armor", Tag.ITEMS_HEAD_ARMOR);
        tags.put("chest_armor", Tag.ITEMS_CHEST_ARMOR);
        tags.put("leg_armor", Tag.ITEMS_LEG_ARMOR);
        tags.put("foot_armor", Tag.ITEMS_FOOT_ARMOR);
        tags.put("arrows", Tag.ITEMS_ARROWS);
        tags.put("compasses", Tag.ITEMS_COMPASSES);
        tags.put("bundles", Tag.ITEMS_BUNDLES);
        tags.put("boats", Tag.ITEMS_BOATS);
        tags.put("chest_boats", Tag.ITEMS_CHEST_BOATS);
        StringBuilder tsv = new StringBuilder();
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isItem() || material.isAir()) {
                continue;
            }
            String name = material.name();
            if (blacklist.stream().anyMatch(pattern -> matches(pattern, name))) {
                continue;
            }
            Set<String> member = new HashSet<>();
            tags.forEach((tagName, tag) -> {
                if (tag.isTagged(material)) {
                    member.add(tagName);
                }
            });
            boolean food = material.isEdible() || ItemStack.of(material).hasData(DataComponentTypes.FOOD);
            ItemCategory category = ItemCategories.classify(new ItemCategories.Traits(material.getKey().asString(), member, material.isBlock(), food));
            String expected = category.id();
            if ((category == ItemCategory.BLOCKS || category == ItemCategory.MISC) && redstone(name)) {
                expected = "redstone";
            }
            tsv.append(name).append('\t').append(expected).append('\n');
            Set<String> got = new HashSet<>();
            patterns.forEach((key, list) -> {
                if (!key.equals("all") && list.stream().anyMatch(pattern -> matches(pattern, name))) {
                    got.add(key);
                }
            });
            if (patterns.get("all").stream().noneMatch(pattern -> matches(pattern, name))) {
                mismatches.add(name + " is not in all");
            }
            if (!got.equals(Set.of(expected))) {
                mismatches.add(name + " expected " + expected + " got " + got);
            }
            checked++;
        }
        Path out = Path.of("plugins", "SiftE2E", "axauctions-categories.tsv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, tsv.toString(), StandardCharsets.UTF_8);
        e2e.log("checked " + checked + " item types; expected classification written to " + out);
        e2e.expect(mismatches.isEmpty(), mismatches.size() + " item types are in the wrong categories, e.g. "
            + mismatches.subList(0, Math.min(15, mismatches.size())));
    }

    /** AxAuctions' pattern rules: exact, {@code text*}, {@code *text}, {@code *text*}; case-sensitive. */
    static boolean matches(String pattern, String value) {
        boolean start = pattern.startsWith("*");
        boolean end = pattern.endsWith("*") && pattern.length() > 1;
        if (pattern.equals("*")) {
            return true;
        }
        String core = pattern.substring(start ? 1 : 0, pattern.length() - (end ? 1 : 0));
        if (start && end) {
            return value.contains(core);
        }
        if (start) {
            return value.endsWith(core);
        }
        if (end) {
            return value.startsWith(core);
        }
        return value.equals(core);
    }

    /** Redstone components that SiftCore files under blocks or misc. */
    private static final Set<String> REDSTONE_ITEMS = Set.of("REDSTONE", "REDSTONE_BLOCK", "REDSTONE_TORCH", "REDSTONE_LAMP",
        "REPEATER", "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON", "DISPENSER", "DROPPER", "HOPPER", "CRAFTER", "LEVER",
        "TRIPWIRE_HOOK", "DAYLIGHT_DETECTOR", "TARGET", "NOTE_BLOCK", "TNT", "TRAPPED_CHEST", "SCULK_SENSOR",
        "CALIBRATED_SCULK_SENSOR", "SLIME_BLOCK", "HONEY_BLOCK", "IRON_DOOR", "IRON_TRAPDOOR");
    private static final List<String> REDSTONE_PATTERNS = List.of("*_BUTTON", "*_PRESSURE_PLATE", "*RAIL", "*COPPER_BULB",
        "*LIGHTNING_ROD");

    /**
     * The redstone category: power sources, wires, components and the blocks that exist for redstone (buttons,
     * pressure plates, rails, iron doors, copper bulbs, lightning rods, slime and honey). Minecarts stay tools.
     */
    static boolean redstone(String material) {
        return REDSTONE_ITEMS.contains(material) || REDSTONE_PATTERNS.stream().anyMatch(pattern -> matches(pattern, material));
    }

    /** A shulker box with contents can be previewed (right click) before buying. */
    static void shulker(E2E e2e) {
        defaultLimit(e2e);
        Bot seller = join(e2e, "AxShulkS");
        Bot buyer = join(e2e, "AxShulkB");
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(List.of(
            ItemStack.of(Material.DIAMOND, 5), named(Material.GOLDEN_APPLE, "Lunch"), ItemStack.of(Material.TORCH, 64))));
        hold(e2e, seller.name, box);
        sell(e2e, seller, "300", "$300");
        open(e2e, buyer, "ah", MAIN);
        int slot = awaitSlot(e2e, buyer, "shulker_box", "Seller " + seller.name);
        List<String> lore = lore(top(buyer).get(slot));
        e2e.log("shulker listing lore: " + lore);
        e2e.expect(lore.contains("Right click to see inside"), "the preview hint: " + lore);
        e2e.step("right click previews the contents");
        Bot.Screen before = buyer.screen();
        e2e.sleep(250);
        buyer.rightClick(slot);
        awaitNewScreen(e2e, buyer, before, CONTENTS);
        e2e.eventually(() -> top(buyer).values().stream().anyMatch(stack -> id(stack).equals("torch")), "the contents load");
        dump(e2e, buyer, "contents");
        Map<String, Integer> inside = new TreeMap<>();
        top(buyer).forEach((s, stack) -> {
            if (s >= 9 && s < 36) {
                inside.merge(id(stack), stack.getCount(), Integer::sum);
            }
        });
        e2e.expect(inside.equals(Map.of("diamond", 5, "golden_apple", 1, "torch", 64)), "the preview shows the contents in rows 2-4: " + inside);
        e2e.expect(lintScreen(buyer, CONTENTS).isEmpty(), "the preview follows the design system: " + STYLE);
        e2e.step("back");
        clickTo(e2e, buyer, 40, MAIN);
        e2e.step("clean up");
        takeDownAll(e2e, seller);
        e2e.eventually(() -> count(e2e, seller.name, Material.SHULKER_BOX) == 1, "the shulker box is back");
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /** A sale shows up in both players' history. */
    static void history(E2E e2e) {
        defaultLimit(e2e);
        Money money = Money.find(e2e);
        Bot seller = join(e2e, "AxHistS");
        Bot buyer = join(e2e, "AxHistB");
        money.set(e2e.player(buyer.name), 500);
        hold(e2e, seller.name, ItemStack.of(Material.LAPIS_LAZULI, 20));
        sell(e2e, seller, "60", "$60");
        buy(e2e, buyer, "lapis_lazuli", seller.name);
        e2e.eventually(() -> count(e2e, buyer.name, Material.LAPIS_LAZULI) == 20, "the buyer has the lapis");
        for (Bot bot : List.of(buyer, seller)) {
            e2e.step(bot.name + " opens /ah history");
            bot.closeScreen();
            open(e2e, bot, "ah history", HISTORY);
            int slot = awaitSlot(e2e, bot, "lapis_lazuli", "Buyer " + buyer.name);
            List<String> lore = lore(top(bot).get(slot));
            e2e.expect(lore.contains("Seller " + seller.name) && lore.contains("Price $60") && lore.stream().anyMatch(line -> line.startsWith("Sold ")),
                "the history entry: " + lore);
            dump(e2e, bot, "history");
            e2e.expect(lintScreen(bot, HISTORY).isEmpty(), "the history follows the design system: " + STYLE);
        }
    }

    /**
     * While the listing confirmation is open the item is still in the seller's hand. Moving it away (number key,
     * shift click, off hand, dropping it) must never end with the item both listed and kept.
     */
    static void sellGuiMoves(E2E e2e) {
        defaultLimit(e2e);
        record Move(String label, java.util.function.BiConsumer<Bot, Integer> action) {
        }
        // Raw slots in a 3-row menu: the menu has 27, then the 27 main inventory slots, then the hotbar.
        int hotbar0 = 27 + 27;
        List<Move> moves = List.of(
            new Move("number key 2 on the held slot", (bot, size) -> bot.numberKey(hotbar0, 1)),
            new Move("shift click the held slot", (bot, size) -> bot.shiftClick(hotbar0)),
            new Move("pick the held stack up", (bot, size) -> bot.clickSlot(hotbar0)),
            new Move("swap to the off hand", (bot, size) -> bot.swapHands()),
            new Move("drop one of the held stack", (bot, size) -> bot.dropHeld(false)),
            new Move("drop the held stack", (bot, size) -> bot.dropHeld(true)),
            new Move("select another hotbar slot", (bot, size) -> bot.selectHotbar(4)));
        List<String> results = new ArrayList<>();
        int index = 0;
        for (Move move : moves) {
            index++;
            Bot bot = join(e2e, "AxMove" + index);
            e2e.step(move.label());
            hold(e2e, bot.name, ItemStack.of(Material.DIAMOND, 10));
            int groundBefore = dropped(e2e, bot.name, Material.DIAMOND);
            bot.clearLogs();
            open(e2e, bot, "ah sell 100", CONFIRM_LIST);
            e2e.eventually(() -> top(bot).containsKey(SLOT_CONFIRM), "the confirmation loads");
            move.action().accept(bot, 27);
            e2e.sleep(800);
            int inInventory = count(e2e, bot.name, Material.DIAMOND);
            if (bot.screen() != null && bot.screen().title().equals(CONFIRM_LIST)) {
                bot.clickSlot(SLOT_CONFIRM);
            }
            e2e.sleep(2_000);
            int kept = count(e2e, bot.name, Material.DIAMOND);
            int onGround = dropped(e2e, bot.name, Material.DIAMOND) - groundBefore;
            String listed = papi(e2e, bot.name, "%axauctions_sell_count%");
            int listedDiamonds = 0;
            if (!listed.equals("0")) {
                open(e2e, bot, "ah", MAIN);
                clickTo(e2e, bot, SLOT_MINE, MINE);
                e2e.eventually(() -> slot(bot, "diamond", null) != null, "the listing shows in your listings");
                for (var stack : top(bot).values()) {
                    if (id(stack).equals("diamond") && isListing(stack)) {
                        listedDiamonds += stack.getCount();
                    }
                }
            }
            String result = move.label() + ": after the move " + inInventory + " in inventory; at the end kept=" + kept
                + " ground=" + onGround + " listed=" + listedDiamonds + " (listings " + listed + ") chat=" + bot.chat();
            results.add(result);
            e2e.log(result);
            e2e.expect(kept + onGround + listedDiamonds == 10, "no diamonds appear or vanish: " + result);
            if (listedDiamonds > 0) {
                takeDownAll(e2e, bot);
            }
            bot.quit();
        }
        e2e.log("sell confirmation moves: " + String.join(" || ", results));
    }

    /** Two buyers confirm the same listing at the same moment: one gets it, the other is refused, money adds up. */
    static void buyRace(E2E e2e) {
        defaultLimit(e2e);
        Money money = Money.find(e2e);
        Bot seller = join(e2e, "AxRaceS");
        Bot a = join(e2e, "AxRaceA");
        Bot b = join(e2e, "AxRaceB");
        money.set(e2e.player(seller.name), 0);
        money.set(e2e.player(a.name), 1_000);
        money.set(e2e.player(b.name), 1_000);
        double totalBefore = testEcoTotal(e2e);
        hold(e2e, seller.name, ItemStack.of(Material.NETHERITE_INGOT, 1));
        sell(e2e, seller, "400", "$400");
        for (Bot buyer : List.of(a, b)) {
            open(e2e, buyer, "ah", MAIN);
            int slot = awaitSlot(e2e, buyer, "netherite_ingot", "Seller " + seller.name);
            clickTo(e2e, buyer, slot, CONFIRM_BUY);
            e2e.eventually(() -> top(buyer).containsKey(SLOT_CONFIRM), "the confirmation loads");
            buyer.clearLogs();
        }
        e2e.step("both confirm at once");
        a.clickSlot(SLOT_CONFIRM);
        b.clickSlot(SLOT_CONFIRM);
        e2e.sleep(5_000);
        int got = count(e2e, a.name, Material.NETHERITE_INGOT) + count(e2e, b.name, Material.NETHERITE_INGOT);
        double paid = (1_000 - money.balance(e2e.player(a.name))) + (1_000 - money.balance(e2e.player(b.name)));
        double earned = money.balance(e2e.player(seller.name));
        e2e.log("race: ingots=" + got + " paid=" + paid + " seller=" + earned + " a=" + a.chat() + " b=" + b.chat());
        e2e.expect(got == 1, "exactly one buyer got the ingot (" + got + ")");
        e2e.expect(near(paid, 400), "exactly one buyer paid $400 (" + paid + ")");
        double tax = 400 * taxRate();
        e2e.expect(near(earned, 400 - tax), "the seller was paid once, $400 less the tax of $" + tax + " (" + earned + ")");
        double totalAfter = testEcoTotal(e2e);
        if (!Double.isNaN(totalBefore)) {
            e2e.expect(near(totalBefore - totalAfter, tax), "money fell by exactly the tax: " + totalBefore + " -> " + totalAfter);
        }
        lintChat(a, "race a");
        lintChat(b, "race b");
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }

    /** Opens every menu a player can reach and checks it against the design system. */
    static void tour(E2E e2e) {
        defaultLimit(e2e);
        Bot bot = join(e2e, "AxTour");
        hold(e2e, bot.name, ItemStack.of(Material.COPPER_INGOT, 3));
        sell(e2e, bot, "12", "$12");
        Map<String, Integer> fromMain = new LinkedHashMap<>();
        fromMain.put(MINE, SLOT_MINE);
        fromMain.put(EXPIRED, SLOT_EXPIRED);
        fromMain.put(CATEGORIES, SLOT_CATEGORY);
        for (var entry : fromMain.entrySet()) {
            e2e.step(entry.getKey());
            open(e2e, bot, "ah", MAIN);
            e2e.eventually(() -> top(bot).containsKey(entry.getValue()), "the buttons load");
            dump(e2e, bot, MAIN);
            lintScreen(bot, MAIN);
            clickTo(e2e, bot, entry.getValue(), entry.getKey());
            e2e.sleep(1_000);
            dump(e2e, bot, entry.getKey());
            lintScreen(bot, entry.getKey());
            if (!entry.getKey().equals(CATEGORIES)) {
                // Every menu with listings has the category selector (and sort) like the main menu.
                var category = top(bot).get(SLOT_CATEGORY);
                e2e.expect(category != null && name(category).equals("Category")
                        && lore(category).stream().anyMatch(line -> line.startsWith("Showing ")),
                    entry.getKey() + " has the category button in slot " + SLOT_CATEGORY);
                e2e.expect(top(bot).get(SLOT_SORT) != null && name(top(bot).get(SLOT_SORT)).equals("Sort"),
                    entry.getKey() + " has the sort button in slot " + SLOT_SORT);
            }
        }
        e2e.step("history and deleted items from your listings");
        open(e2e, bot, "ah", MAIN);
        clickTo(e2e, bot, SLOT_MINE, MINE);
        e2e.eventually(() -> top(bot).containsKey(51), "the history button loads");
        clickTo(e2e, bot, 51, HISTORY);
        e2e.sleep(1_000);
        dump(e2e, bot, HISTORY);
        lintScreen(bot, HISTORY);
        open(e2e, bot, "ah", MAIN);
        clickTo(e2e, bot, SLOT_MINE, MINE);
        e2e.eventually(() -> top(bot).containsKey(52), "the deleted items button loads");
        clickTo(e2e, bot, 52, DELETED);
        e2e.sleep(1_000);
        dump(e2e, bot, DELETED);
        lintScreen(bot, DELETED);
        e2e.step("help");
        bot.closeScreen();
        bot.clearLogs();
        command(e2e, bot, "ah help");
        e2e.sleep(1_500);
        e2e.log("help: " + bot.chat());
        lintChat(bot, "help");
        e2e.step("back button opens SiftCore's main menu");
        open(e2e, bot, "ah", MAIN);
        e2e.eventually(() -> top(bot).containsKey(SLOT_BACK), "the back button loads");
        int dialogs = bot.dialogs().size();
        e2e.sleep(500);
        bot.clickSlot(SLOT_BACK);
        e2e.eventually(() -> bot.dialogs().size() > dialogs, "a SiftCore dialog opens (dialogs " + bot.dialogs() + ")");
        e2e.log("back opened: " + bot.dialogs().getLast().title());
        e2e.step("the main menu's Auction house button opens AxAuctions");
        e2e.dialog(bot, "SiftVanilla");
        Bot.Screen beforeHub = bot.screen();
        e2e.sleep(250);
        e2e.expect(bot.clickButton("Auction house", Map.of()), "the main menu has an Auction house button");
        awaitNewScreen(e2e, bot, beforeHub, MAIN);
        e2e.eventually(() -> top(bot).containsKey(SLOT_EXPIRED), "the AxAuctions buttons load");
        e2e.expect(name(top(bot).get(SLOT_EXPIRED)).equals("Expired listings"), "the button opened AxAuctions, not SiftCore's own menu");
        takeDownAll(e2e, bot);
        e2e.expect(STYLE.isEmpty(), "every menu follows the design system: " + STYLE);
    }

    /**
     * How long expired listings can be kept before deletion: lists with a 5 s expiry and each deletion time, then
     * reads the "Deleted in" line (a time that overflows would show as already due). Also records whether expired
     * listings still take listing slots. The config is restored afterwards.
     */
    static void deletionTime(E2E e2e) throws Exception {
        defaultLimit(e2e);
        Path config = plugin().getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(config, StandardCharsets.UTF_8);
        Bot seller = join(e2e, "AxKeep");
        List<String> report = new ArrayList<>();
        try {
            for (String seconds : List.of("2592000", "31536000", "315360000", "-1")) {
                e2e.step("deletion time " + seconds);
                Files.writeString(config, original.replaceFirst("(?m)^item-expire-time: .*$", "item-expire-time: 5")
                    .replaceFirst("(?m)^item-deletion-time: .*$", "item-deletion-time: " + seconds), StandardCharsets.UTF_8);
                e2e.console("ahadmin reload");
                e2e.sleep(1_000);
                seller.closeScreen();
                hold(e2e, seller.name, ItemStack.of(Material.FLINT, 2));
                sell(e2e, seller, "3", "$3");
                e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_expired_count%").equals("1"), 120_000, "the flint expired");
                String slots = papi(e2e, seller.name, "%axauctions_sell_count%") + " sell_count, "
                    + papi(e2e, seller.name, "%axauctions_purchasable_count%") + " purchasable";
                open(e2e, seller, "ah", MAIN);
                clickTo(e2e, seller, SLOT_EXPIRED, EXPIRED);
                int slot = awaitSlot(e2e, seller, "flint", "Price $3");
                String deleted = lore(top(seller).get(slot)).stream().filter(line -> line.startsWith("Deleted in")).findFirst().orElse("none");
                e2e.sleep(3_000);
                String later = papi(e2e, seller.name, "%axauctions_expired_count%");
                report.add(seconds + " s: '" + deleted + "', still expired after 3 s: " + later + ", " + slots);
                e2e.log(report.getLast());
                if (later.equals("1")) {
                    open(e2e, seller, "ah", MAIN);
                    clickTo(e2e, seller, SLOT_EXPIRED, EXPIRED);
                    int again = awaitSlot(e2e, seller, "flint", "Price $3");
                    e2e.sleep(250);
                    seller.clickSlot(again);
                    e2e.eventually(() -> count(e2e, seller.name, Material.FLINT) == 2, "the flint is back");
                }
            }
        } finally {
            Files.writeString(config, original, StandardCharsets.UTF_8);
            e2e.console("ahadmin reload");
        }
        e2e.log("deletion times: " + String.join(" || ", report));
    }

    /**
     * Expiry with a short test time: the listing moves to expired listings and can be taken back; with a short
     * deletion time it then moves to deleted items. The config is restored afterwards.
     */
    static void expiry(E2E e2e) throws Exception {
        defaultLimit(e2e);
        Path config = plugin().getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(config, StandardCharsets.UTF_8);
        Bot seller = join(e2e, "AxExpire");
        try {
            e2e.step("short expiry (20 s)");
            Files.writeString(config, original.replaceFirst("(?m)^item-expire-time: .*$", "item-expire-time: 20"), StandardCharsets.UTF_8);
            e2e.console("ahadmin reload");
            e2e.sleep(1_000);
            hold(e2e, seller.name, ItemStack.of(Material.AMETHYST_SHARD, 9));
            sell(e2e, seller, "9", "$9");
            open(e2e, seller, "ah", MAIN);
            clickTo(e2e, seller, SLOT_MINE, MINE);
            int slot = awaitSlot(e2e, seller, "amethyst_shard", "Price $9");
            e2e.log("before expiry: " + lore(top(seller).get(slot)));
            seller.clearLogs();
            e2e.step("it expires");
            long start = System.currentTimeMillis();
            e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_expired_count%").equals("1"), 120_000,
                "the listing expired (expired_count " + papi(e2e, seller.name, "%axauctions_expired_count%") + ")");
            e2e.log("expired after " + (System.currentTimeMillis() - start) / 1000 + " s; chat " + seller.chat());
            e2e.eventually(() -> seller.chatContains("expired"), 30_000, "the seller is told it expired (chat " + seller.chat() + ")");
            lintChat(seller, "expired");
            e2e.step("expired listings show it; click takes it back");
            seller.closeScreen();
            open(e2e, seller, "ah", MAIN);
            clickTo(e2e, seller, SLOT_EXPIRED, EXPIRED);
            int expiredSlot = awaitSlot(e2e, seller, "amethyst_shard", "Price $9");
            List<String> lore = lore(top(seller).get(expiredSlot));
            e2e.log("expired lore: " + lore);
            e2e.expect(lore.stream().anyMatch(line -> line.startsWith("Deleted in ")) && lore.contains("Click to take it back"),
                "the expired listing explains itself: " + lore);
            dump(e2e, seller, EXPIRED);
            lintScreen(seller, EXPIRED);
            e2e.sleep(250);
            seller.clickSlot(expiredSlot);
            e2e.eventually(() -> count(e2e, seller.name, Material.AMETHYST_SHARD) == 9, "the 9 shards are back");
            e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_expired_count%").equals("0"), "nothing is waiting any more");

            e2e.step("short deletion (expiry 10 s, deletion 20 s)");
            Files.writeString(config, original.replaceFirst("(?m)^item-expire-time: .*$", "item-expire-time: 10")
                .replaceFirst("(?m)^item-deletion-time: .*$", "item-deletion-time: 20"), StandardCharsets.UTF_8);
            e2e.console("ahadmin reload");
            e2e.sleep(1_000);
            seller.closeScreen();
            hold(e2e, seller.name, ItemStack.of(Material.QUARTZ, 4));
            sell(e2e, seller, "4", "$4");
            seller.clearLogs();
            e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_expired_count%").equals("1"), 120_000, "the quartz expired");
            e2e.eventually(() -> papi(e2e, seller.name, "%axauctions_expired_count%").equals("0"), 120_000, "the quartz was deleted");
            e2e.eventually(() -> seller.chatContains("deleted"), 30_000, "the seller is told it was deleted (chat " + seller.chat() + ")");
            lintChat(seller, "deleted");
            open(e2e, seller, "ah deleted", DELETED);
            int deletedSlot = awaitSlot(e2e, seller, "quartz", "Price $4");
            e2e.log("deleted lore: " + lore(top(seller).get(deletedSlot)));
            dump(e2e, seller, DELETED);
            lintScreen(seller, DELETED);
            e2e.step("a player can't take a deleted item");
            e2e.sleep(250);
            seller.clickSlot(deletedSlot);
            e2e.sleep(1_500);
            e2e.expect(count(e2e, seller.name, Material.QUARTZ) == 0, "the deleted quartz stays deleted for the player");
        } finally {
            Files.writeString(config, original, StandardCharsets.UTF_8);
            e2e.console("ahadmin reload");
        }
        e2e.expect(STYLE.isEmpty(), "every text follows the design system: " + STYLE);
    }
}
