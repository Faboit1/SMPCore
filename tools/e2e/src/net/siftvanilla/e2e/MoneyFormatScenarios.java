package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * End-to-end scenarios of the "Money format" setting (Display group, {@code money-format}: the server's way, in full
 * or short). Three players with the same balance read it three ways in chat, in a dialog, in a chest menu and in
 * placeholders; the same payment receipt and the same announcement read in each receiver's format whoever caused
 * them; confirmations show every digit whatever the payer chose.
 */
final class MoneyFormatScenarios {

    /** The title of the Display settings page. */
    static final String DISPLAY_PAGE = "Display settings";
    /** The setting's dialog input key. */
    static final String INPUT = "money_format";
    private static final long BALANCE = 1_234_567L;

    private MoneyFormatScenarios() {
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
        list.add(of("money-format-views", MoneyFormatScenarios::views));
        list.add(of("money-format-receipts", MoneyFormatScenarios::receipts));
        list.add(of("money-format-later", MoneyFormatScenarios::later));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code /settings <id> <value>} typed by the player, waiting until it is in effect (paced: the server kicks
     * clients that send commands faster than about one a second).
     */
    private static void choose(E2E e2e, Bot bot, String id, String value) {
        PlayerSetting<?> setting = e2e.services().settings().setting(id);
        e2e.expect(setting != null, id + " is a setting");
        UUID player = e2e.uuid(bot.name);
        e2e.sleep(1_000);
        bot.command("settings " + id + " " + value);
        e2e.eventually(() -> value.equals(e2e.services().settings().encoded(player, id)), "/settings " + id + " " + value
            + " is in effect for " + bot.name + " (now " + e2e.services().settings().encoded(player, id) + "; chat " + bot.chat()
            + ", action bar " + bot.actionBar() + ")");
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        Player player = e2e.player(name);
        String value = e2e.services().placeholders().resolve(player, placeholder);
        return value == null ? "" : value;
    }

    private static String itemName(ItemStack stack) {
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        return custom != null ? custom.getString() : stack.getHoverName().getString();
    }

    /** The name of the item in the open chest menu whose name starts with {@code prefix}, or "" when none does. */
    private static String screenItem(Bot bot, String prefix) {
        for (ItemStack stack : bot.screenItems().values()) {
            if (stack != null && !stack.isEmpty() && itemName(stack).startsWith(prefix)) {
                return itemName(stack);
            }
        }
        return "";
    }

    /** Rebuilds the money leaderboard now (it rebuilds on a timer), off the world threads like the timer. */
    private static void refreshLeaderboard(E2E e2e) {
        try {
            EconomyFeature feature = e2e.feature(EconomyFeature.class);
            java.lang.reflect.Method refresh = EconomyFeature.class.getDeclaredMethod("refreshTop");
            refresh.setAccessible(true);
            refresh.invoke(feature);
        } catch (ReflectiveOperationException e) {
            throw new E2E.Failure("could not rebuild the money leaderboard: " + e);
        }
    }

    /** The Display page that holds the money format (walking the pages), as the bot sees it. */
    private static Bot.SeenDialog displayPage(E2E e2e, Bot bot) {
        Bot.SeenDialog before = bot.dialog();
        bot.command("settings display");
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(DISPLAY_PAGE),
            bot.name + " sees " + DISPLAY_PAGE + ": " + (bot.dialog() == null ? "none" : bot.dialog().title()));
        for (int guard = 0; guard < 10; guard++) {
            Bot.SeenDialog page = bot.dialog();
            if (page.inputs().containsKey(INPUT)) {
                return page;
            }
            e2e.expect(page.button("Next page") != null, INPUT + " on a later page (last page: " + page.inputs().keySet() + ")");
            e2e.click(bot, "Next page", page.values());
        }
        throw new E2E.Failure("too many pages in " + DISPLAY_PAGE);
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * The setting in the Display group (dialog and command), then one balance of $1,234,567 read three ways: in chat
     * (/balance), in the money dialog, in the shop menu and in the placeholders (balance, balance_number,
     * baltop_value_1), while balance_exact keeps every digit for everyone.
     */
    static void views(E2E e2e) {
        String fullName = e2e.name("MfFull");
        String shortName = e2e.name("MfShort");
        String usualName = e2e.name("MfUsual");
        Bot full = e2e.bot(fullName);
        Bot brief = e2e.bot(shortName);
        Bot usual = e2e.bot(usualName);

        e2e.step("the Display page offers the three formats, each with a sample, starting on the server's way");
        Bot.SeenDialog page = displayPage(e2e, full);
        e2e.expect("choice".equals(page.inputs().get(INPUT)), "a choice: " + page.inputs());
        e2e.expect(List.of("server", "full", "short").equals(page.options().get(INPUT)), "options: " + page.options().get(INPUT));
        e2e.expect(List.of("The server's way ($1.23m)", "In full ($1,234,567)", "Short ($1.2m)").equals(page.optionLabels().get(INPUT)),
            "labels with samples: " + page.optionLabels().get(INPUT));
        e2e.expect("server".equals(page.choiceValue(INPUT)), "starts on the default: " + page.choiceValue(INPUT));
        full.closeScreen();

        e2e.step("one player picks 'in full' in the dialog, another types /settings money-format short");
        MoneyScenarios.editSettings(e2e, full, "display", DISPLAY_PAGE, Map.of(INPUT, "full"));
        e2e.eventually(() -> "full".equals(e2e.services().settings().encoded(e2e.uuid(fullName), "money-format")),
            "saved from the dialog: " + e2e.services().settings().encoded(e2e.uuid(fullName), "money-format"));
        choose(e2e, brief, "money-format", "short");
        e2e.expect(e2e.services().settings().encoded(e2e.uuid(usualName), "money-format").equals("server"), "the third keeps the default");

        e2e.step("the same balance in chat: /balance");
        for (String name : List.of(fullName, shortName, usualName)) {
            e2e.console("eco set " + name + " " + BALANCE);
            e2e.eventually(() -> e2e.money(name) == BALANCE, name + " has $1,234,567");
        }
        full.clearLogs();
        brief.clearLogs();
        usual.clearLogs();
        full.command("balance");
        brief.command("balance");
        usual.command("balance");
        e2e.eventually(() -> full.chatContains("You have $1,234,567 and"), "in full: " + full.chat());
        e2e.eventually(() -> brief.chatContains("You have $1.2m and"), "short: " + brief.chat());
        e2e.eventually(() -> usual.chatContains("You have $1.23m and"), "the server's way: " + usual.chat());

        e2e.step("the same balance on each player's sidebar (its balance line is the balance placeholder of the viewer)");
        e2e.eventually(() -> full.sidebarLines().stream().anyMatch(line -> line.contains("$1,234,567")), "in full: " + full.sidebarLines());
        e2e.eventually(() -> brief.sidebarLines().stream().anyMatch(line -> line.contains("$1.2m")), "short: " + brief.sidebarLines());
        e2e.eventually(() -> usual.sidebarLines().stream().anyMatch(line -> line.contains("$1.23m")), "server: " + usual.sidebarLines());

        e2e.step("the same balance in the money dialog (opened from the pause menu after a database read)");
        full.rawClick("siftcore:hub/money", null);
        brief.rawClick("siftcore:hub/money", null);
        Bot.SeenDialog fullMoney = e2e.dialog(full, "Money");
        Bot.SeenDialog shortMoney = e2e.dialog(brief, "Money");
        e2e.expect(fullMoney.bodyText().contains("$1,234,567"), "in full: " + fullMoney.body());
        e2e.expect(shortMoney.bodyText().contains("$1.2m") && !shortMoney.bodyText().contains("$1,234,567"), "short: " + shortMoney.body());
        full.closeScreen();
        brief.closeScreen();

        e2e.step("the same balance in a chest menu: the shop's balance item");
        e2e.sleep(1_000);
        full.command("shop");
        e2e.sleep(1_000);
        brief.command("shop");
        e2e.eventually(() -> full.screen() != null && full.screen().title().equals("Shop") && !screenItem(full, "Balance").isEmpty(),
            "the full player's shop");
        e2e.eventually(() -> brief.screen() != null && brief.screen().title().equals("Shop") && !screenItem(brief, "Balance").isEmpty(),
            "the short player's shop");
        e2e.expect(screenItem(full, "Balance").equals("Balance $1,234,567"), "in full: " + screenItem(full, "Balance"));
        e2e.expect(screenItem(brief, "Balance").equals("Balance $1.2m"), "short: " + screenItem(brief, "Balance"));
        full.closeScreen();
        brief.closeScreen();

        e2e.step("placeholders follow the player they are shown to; balance_exact keeps every digit");
        e2e.expect("$1,234,567".equals(placeholder(e2e, fullName, "balance")), "full: " + placeholder(e2e, fullName, "balance"));
        e2e.expect("$1.2m".equals(placeholder(e2e, shortName, "balance")), "short: " + placeholder(e2e, shortName, "balance"));
        e2e.expect("$1.23m".equals(placeholder(e2e, usualName, "balance")), "server: " + placeholder(e2e, usualName, "balance"));
        e2e.expect("1.2m".equals(placeholder(e2e, shortName, "balance_number")), "number: " + placeholder(e2e, shortName, "balance_number"));
        e2e.expect("1,234,567".equals(placeholder(e2e, fullName, "balance_number")), "number: " + placeholder(e2e, fullName, "balance_number"));
        for (String name : List.of(fullName, shortName, usualName)) {
            e2e.expect("$1,234,567".equals(placeholder(e2e, name, "balance_exact")), "exact for " + name + ": " + placeholder(e2e, name, "balance_exact"));
        }
        refreshLeaderboard(e2e);
        EconomyApi economy = e2e.feature(EconomyFeature.class).economy();
        List<EconomyApi.TopEntry> top = economy.top(Currency.MONEY, 1);
        e2e.expect(!top.isEmpty(), "someone is on the money leaderboard");
        long first = top.getFirst().value();
        MoneyFormat format = e2e.services().money().get();
        e2e.expect(format.formatExact(first).equals(placeholder(e2e, fullName, "baltop_value_1")),
            "the leaderboard in full: " + placeholder(e2e, fullName, "baltop_value_1") + " for " + first);
        e2e.expect(format.formatShort(first).equals(placeholder(e2e, shortName, "baltop_value_1")),
            "the leaderboard short: " + placeholder(e2e, shortName, "baltop_value_1") + " for " + first);
        e2e.expect(format.format(first).equals(e2e.services().placeholders().resolve(null, "baltop_value_1")),
            "without a viewer (holograms) the server's way");

        e2e.step("a dialog built by a dialog click (Richest players from the money page) and one built by /baltop");
        full.rawClick("siftcore:hub/money", null);
        e2e.dialog(full, "Money");
        e2e.click(full, "Richest players");
        Bot.SeenDialog fullTop = e2e.dialog(full, "Richest players");
        e2e.expect(fullTop.bodyText().contains("with $1,234,567."), "your place in full: " + fullTop.body());
        e2e.sleep(1_000);
        brief.command("baltop");
        Bot.SeenDialog shortTop = e2e.dialog(brief, "Richest players");
        e2e.expect(shortTop.bodyText().contains("with $1.2m."), "your place short: " + shortTop.body());
        full.closeScreen();
        brief.closeScreen();

        e2e.step("back to the server's way with /settings: /balance reads $1.23m again");
        choose(e2e, brief, "money-format", "server");
        brief.clearLogs();
        brief.command("balance");
        e2e.eventually(() -> brief.chatContains("You have $1.23m and"), "the server's way again: " + brief.chat());
    }

    /**
     * A payer who chose the short format pays two players: the confirmation shows every digit, the payer's own receipt
     * is short, and the same "paid you" receipt reads in full for one receiver and short for the other. Then the payer
     * places a bounty: the one announcement reads in each watcher's format.
     */
    static void receipts(E2E e2e) {
        String payerName = e2e.name("MfPayer");
        String fullName = e2e.name("MfGetFull");
        String shortName = e2e.name("MfGetShort");
        String usualName = e2e.name("MfWatch");
        String targetName = e2e.name("MfTarget");
        Bot payer = e2e.bot(payerName);
        Bot full = e2e.bot(fullName);
        Bot brief = e2e.bot(shortName);
        Bot usual = e2e.bot(usualName);
        e2e.bot(targetName);
        e2e.console("eco set " + payerName + " 1m");
        e2e.eventually(() -> e2e.money(payerName) == 1_000_000, "the payer has $1,000,000");
        choose(e2e, payer, "money-format", "short");
        choose(e2e, full, "money-format", "full");
        choose(e2e, brief, "money-format", "short");

        e2e.step("the confirmation shows every digit to a payer who reads short ($123,456; $126,544 left today)");
        payer.clearLogs();
        payer.command("pay " + fullName + " 123456");
        Bot.SeenDialog confirm = e2e.dialog(payer, "Confirm payment");
        e2e.expect(confirm.bodyText().contains("Send $123,456 to " + fullName + "?"), "the exact amount: " + confirm.body());
        e2e.expect(confirm.bodyText().contains("You can send $126,544 more today"), "the exact amount left: " + confirm.body());
        e2e.expect(!confirm.bodyText().contains("$123.4k") && !confirm.bodyText().contains("$126.5k"), "nothing short: " + confirm.body());
        full.clearLogs();
        e2e.click(payer, "Pay");
        e2e.eventually(() -> e2e.money(fullName) == 123_456, "paid");

        e2e.step("the payer's receipt is short; the receiver who chose 'in full' reads every digit");
        e2e.eventually(() -> payer.anyFeedbackContains("You paid " + fullName + " $123.4k."), "the payer's receipt: " + payer.chat());
        e2e.eventually(() -> full.anyFeedbackContains(payerName + " paid you $123,456."), "in full: " + full.chat() + " " + full.actionBar());

        e2e.step("the same receipt to a receiver who chose short");
        e2e.sleep(2_100);
        brief.clearLogs();
        payer.command("pay " + shortName + " 123456");
        Bot.SeenDialog second = e2e.dialog(payer, "Confirm payment");
        e2e.expect(second.bodyText().contains("Send $123,456 to " + shortName + "?") && second.bodyText().contains("$3,088"),
            "exact again: " + second.body());
        e2e.click(payer, "Pay");
        e2e.eventually(() -> e2e.money(shortName) == 123_456, "paid");
        e2e.eventually(() -> brief.anyFeedbackContains(payerName + " paid you $123.4k."), "short: " + brief.chat() + " " + brief.actionBar());

        e2e.step("one bounty announcement, placed by the short reader, reads in each watcher's format");
        full.clearLogs();
        brief.clearLogs();
        usual.clearLogs();
        payer.clearLogs();
        e2e.sleep(1_000);
        payer.command("bounty " + targetName + " 60k");
        e2e.eventually(() -> full.chatContains("$60,000 was put on " + targetName + "."), "in full: " + full.chat());
        e2e.eventually(() -> brief.chatContains("$60k was put on " + targetName + "."), "short: " + brief.chat());
        e2e.eventually(() -> usual.chatContains("$60,000 was put on " + targetName + "."), "the server's way: " + usual.chat());
        e2e.eventually(() -> payer.anyFeedbackContains("You put $60k on " + targetName + "."), "the sponsor, short: " + payer.chat());
    }

    // ------------------------------------------------------------------ screens built after a wait

    /** A one-reward crate paying $1,234,567, appended under crates: (the last section of the shipped file). */
    private static final String TEST_CRATE = """

          e2emoney:
            name: "Fortune"
            icon: gold_block
            rewards:
              fortune:
                money: 1234567
                weight: 1
                rarity: common
        """;

    private static Path file(E2E e2e, String name) {
        return e2e.services().plugin().getDataFolder().toPath().resolve(name);
    }

    /** Replaces the first {@code from} in a config file, reloads, and returns the original text to put back. */
    private static String edit(E2E e2e, String name, String from, String to) throws Exception {
        Path file = file(e2e, name);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        e2e.expect(original.contains(from), name + " has '" + from + "'");
        Files.writeString(file, original.replaceFirst(Pattern.quote(from), Matcher.quoteReplacement(to)), StandardCharsets.UTF_8);
        List<String> output = e2e.consoleOutput("sift reload");
        e2e.expect(output.stream().anyMatch(line -> line.contains("Reloaded")), "the reload of " + name + " worked: " + output);
        return original;
    }

    private static void restore(E2E e2e, String name, String original) throws Exception {
        Files.writeString(file(e2e, name), original, StandardCharsets.UTF_8);
        e2e.console("sift reload");
    }

    /** The newest chat line containing {@code text}. */
    private static Component chatLine(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.chatContains(text), bot.name + " gets a chat line with '" + text + "': " + bot.chat());
        List<Component> lines = bot.chatComponents();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).getString().contains(text)) {
                return lines.get(i);
            }
        }
        throw new E2E.Failure("no component for '" + text + "'");
    }

    /** The first part of a component tree whose own text is {@code text}, or null. */
    private static Component part(Component root, String text) {
        if (root.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents plain && plain.text().equals(text)) {
            return root;
        }
        for (Component sibling : root.getSiblings()) {
            Component found = part(sibling, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The hover card on {@code name} in the chat line that says {@code text}, as the bot sees it. */
    private static String card(E2E e2e, Bot bot, String name, String text) {
        Component line = chatLine(e2e, bot, text);
        Component part = part(line, name);
        e2e.expect(part != null && part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText, "a card on " + name + ": " + line);
        return ((HoverEvent.ShowText) part.getStyle().getHoverEvent()).value().getString();
    }

    /** Runs {@code action} and waits for a new dialog titled {@code title} (not the one shown before). */
    private static Bot.SeenDialog next(E2E e2e, Bot bot, String title, Runnable action) {
        Bot.SeenDialog before = bot.dialog();
        action.run();
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a new '" + title + "' dialog (last: " + (bot.dialog() == null ? "none" : bot.dialog().title()) + ")");
        return bot.dialog();
    }

    /** Waits until the bot's dialog body says {@code text} (a result shown once the server answered). */
    private static Bot.SeenDialog awaitBody(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains(text),
            bot.name + " sees a dialog saying '" + text + "' (last: " + (bot.dialog() == null ? "none" : bot.dialog().bodyText()) + ")");
        return bot.dialog();
    }

    /**
     * What is built for one player outside the click or command that asked for it, after a wait: the chat hover card
     * (rendered for each reader), {@code /stats} of a player who is offline (after a database read), a crate's money
     * reward (shown once the opening is stored) and the sale receipt. Each follows its reader's format. The Sell button
     * of a sale confirmation keeps every digit for a player who reads short, and so does the body. Then a server that
     * never writes money short: "In full" would change nothing, so the Display page leaves it out until it does again.
     */
    static void later(E2E e2e) throws Exception {
        String fullName = e2e.name("MfLFull");
        String shortName = e2e.name("MfLShort");
        String richName = e2e.name("MfLRich");
        Bot full = e2e.bot(fullName);
        Bot brief = e2e.bot(shortName);
        Bot rich = e2e.bot(richName);
        choose(e2e, full, "money-format", "full");
        choose(e2e, brief, "money-format", "short");
        MoneyFormat format = e2e.services().money().get();

        e2e.step("the hover card on a chat name shows the sender's balance in each reader's format");
        e2e.console("eco set " + richName + " " + BALANCE);
        e2e.eventually(() -> e2e.money(richName) == BALANCE, richName + " has $1,234,567");
        full.clearLogs();
        brief.clearLogs();
        e2e.sleep(1_500);
        rich.chat("money talks");
        String fullCard = card(e2e, full, richName, "money talks");
        String shortCard = card(e2e, brief, richName, "money talks");
        e2e.expect(fullCard.contains("Balance $1,234,567"), "in full: " + fullCard);
        e2e.expect(shortCard.contains("Balance $1.2m"), "short: " + shortCard);

        e2e.step("/stats of a player who is offline, shown after the database read");
        rich.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(richName) == null, richName + " left");
        e2e.sleep(1_000);
        Bot.SeenDialog fullStats = next(e2e, full, richName + "'s stats", () -> full.command("stats " + richName));
        e2e.expect(fullStats.bodyText().contains("Balance $1,234,567"), "in full: " + fullStats.body());
        e2e.sleep(1_000);
        Bot.SeenDialog shortStats = next(e2e, brief, richName + "'s stats", () -> brief.command("stats " + richName));
        e2e.expect(shortStats.bodyText().contains("Balance $1.2m"), "short: " + shortStats.body());
        full.closeScreen();
        brief.closeScreen();

        e2e.step("a crate's money reward, shown once the opening is stored, and its chat receipt in the same format");
        Path crates = file(e2e, "features/crates.yml");
        String shipped = Files.readString(crates, StandardCharsets.UTF_8);
        e2e.expect(shipped.stripTrailing().endsWith("display: \"a blaze spawner\""), "crates.yml ends with the shipped crates section");
        Files.writeString(crates, shipped.stripTrailing() + "\n" + TEST_CRATE, StandardCharsets.UTF_8);
        try {
            List<String> output = e2e.consoleOutput("sift reload");
            e2e.expect(output.stream().anyMatch(line -> line.contains("Reloaded")), "the reload with the test crate worked: " + output);
            CratesFeature cratesFeature = e2e.feature(CratesFeature.class);
            e2e.expect(cratesFeature.settings().crate("e2emoney") != null, "the test crate is live");
            for (Bot bot : List.of(full, brief)) {
                e2e.console("keys give " + bot.name + " e2emoney 1");
                e2e.eventually(() -> cratesFeature.keys().keys(e2e.uuid(bot.name), "e2emoney") == 1, bot.name + " has a key");
                bot.clearLogs();
                e2e.sleep(1_000);
                next(e2e, bot, "Crates", () -> bot.command("crates"));
                e2e.click(bot, "Open Fortune");
            }
            Bot.SeenDialog fullWin = awaitBody(e2e, full, "You won");
            Bot.SeenDialog shortWin = awaitBody(e2e, brief, "You won");
            e2e.expect(fullWin.bodyText().contains("You won $1,234,567"), "the result in full: " + fullWin.body());
            e2e.expect(shortWin.bodyText().contains("You won $1.2m"), "the result short: " + shortWin.body());
            e2e.eventually(() -> full.chatContains("You won $1,234,567 from the Fortune crate."), "the receipt in full: " + full.chat());
            e2e.eventually(() -> brief.chatContains("You won $1.2m from the Fortune crate."), "the receipt short: " + brief.chat());
            full.closeScreen();
            brief.closeScreen();
        } finally {
            Files.writeString(crates, shipped, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }

        e2e.step("the Sell button of a sale confirmation keeps every digit for a player who reads short");
        e2e.console("eco set " + shortName + " 0");
        e2e.eventually(() -> e2e.money(shortName) == 0, "no money yet");
        e2e.onPlayer(shortName, () -> {
            Player player = e2e.player(shortName);
            player.getInventory().clear();
            for (int slot = 9; slot < 14; slot++) {
                player.getInventory().setItem(slot, org.bukkit.inventory.ItemStack.of(Material.DIAMOND_BLOCK, 64));
            }
            return null;
        });
        e2e.sleep(1_000);
        brief.clearLogs();
        Bot.SeenDialog confirm = next(e2e, brief, "Sell everything", () -> brief.command("sell all"));
        Matcher asked = Pattern.compile("Sell 320 items for (\\$[0-9,]+)\\?").matcher(confirm.bodyText());
        e2e.expect(asked.find(), "every digit in the body: " + confirm.body());
        String exact = asked.group(1);
        e2e.expect(confirm.button("Sell for " + exact) != null, "every digit on the Sell button too: " + confirm.buttons());
        e2e.click(brief, "Sell for " + exact);
        e2e.eventually(() -> e2e.money(shortName) > 1_000_000, "sold for over a million (has " + e2e.money(shortName) + ")");
        long total = e2e.money(shortName);
        e2e.expect(exact.equals(format.formatExact(total)), "the button named the amount paid: " + exact + " for " + total);
        e2e.eventually(() -> brief.anyFeedbackContains(format.formatShort(total)), "the receipt short (" + format.formatShort(total)
            + "): " + brief.chat() + " " + brief.actionBar());

        e2e.step("balance follows the player; balance_server is the server's way for everyone");
        e2e.expect(format.formatShort(total).equals(placeholder(e2e, shortName, "balance")), "short: " + placeholder(e2e, shortName, "balance"));
        e2e.expect(format.format(total).equals(placeholder(e2e, shortName, "balance_server")),
            "the server's way: " + placeholder(e2e, shortName, "balance_server"));
        long fullMoney = e2e.money(fullName);
        e2e.expect(format.format(fullMoney).equals(placeholder(e2e, fullName, "balance_server")),
            "the server's way for the full reader too: " + placeholder(e2e, fullName, "balance_server"));

        e2e.step("a server that never writes money short: 'In full' changes nothing, so it is not offered");
        String config = edit(e2e, "config.yml", "compact-from: 1000000", "compact-from: 0");
        try {
            Bot.SeenDialog page = displayPage(e2e, brief);
            e2e.expect(List.of("server", "short").equals(page.options().get(INPUT)), "no 'in full': " + page.options().get(INPUT));
            e2e.expect(List.of("The server's way ($1,234,567)", "Short ($1.2m)").equals(page.optionLabels().get(INPUT)),
                "the samples: " + page.optionLabels().get(INPUT));
            brief.closeScreen();
            e2e.expect("server".equals(e2e.services().settings().encoded(e2e.uuid(fullName), "money-format")),
                "a stored 'in full' reads the server's way meanwhile: " + e2e.services().settings().encoded(e2e.uuid(fullName), "money-format"));
            full.clearLogs();
            e2e.sleep(1_000);
            full.command("balance");
            String everyDigit = "You have " + format.formatExact(fullMoney) + " and";
            e2e.eventually(() -> full.chatContains(everyDigit), "every digit, the server's way now: " + full.chat());
        } finally {
            restore(e2e, "config.yml", config);
        }
        e2e.sleep(1_000);
        Bot.SeenDialog back = displayPage(e2e, brief);
        e2e.expect(List.of("server", "full", "short").equals(back.options().get(INPUT)), "offered again: " + back.options().get(INPUT));
        brief.closeScreen();
        e2e.expect("full".equals(e2e.services().settings().encoded(e2e.uuid(fullName), "money-format")),
            "the stored choice was kept and applies again: " + e2e.services().settings().encoded(e2e.uuid(fullName), "money-format"));
    }
}
