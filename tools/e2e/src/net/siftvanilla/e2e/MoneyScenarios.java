package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * End-to-end scenarios of the money and selling settings (Money &amp; selling): payment alerts and their minimum changed
 * through the settings dialog, then with the {@code /settings <id> <value>} command: when {@code /pay} asks, who may
 * pay (online, offline and ignored), the summary of payments received while away, who sees a balance, hiding from the
 * money leaderboard and the top sellers, when {@code /sell all} asks, the hotbar and shulker boxes on {@code /sell all},
 * selling on closing the sell menu, and mastery level-ups (a title, one pop-up for several, never over the receipt).
 */
final class MoneyScenarios {

    /** The title of the Money &amp; selling settings page. */
    static final String MONEY_PAGE = "Money & selling settings";

    private MoneyScenarios() {
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
        list.add(of("money-settings-dialog", MoneyScenarios::settingsDialog));
        list.add(of("money-pay-settings", MoneyScenarios::paySettings));
        list.add(of("money-balance-privacy", MoneyScenarios::balancePrivacy));
        list.add(of("money-leaderboard-hidden", MoneyScenarios::leaderboardHidden));
        list.add(of("sell-all-settings", MoneyScenarios::sellAllSettings));
        list.add(of("sell-menu-close-setting", MoneyScenarios::sellMenuClose));
        list.add(of("sell-mastery-levelup-setting", MoneyScenarios::masteryLevelUp));
        list.add(of("sell-top-hidden", MoneyScenarios::sellTopHidden));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    /** A player's id by name, online or not. */
    private static UUID known(E2E e2e, String name) {
        return e2e.services().directory().uuid(name).orElseThrow(() -> new E2E.Failure(name + " never joined"));
    }

    private static long balance(E2E e2e, String name) {
        return e2e.services().ledger().balance(known(e2e, name), Currency.MONEY);
    }

    /**
     * Changes an online player's setting the way they type it, {@code /settings <id> <value>} (their permissions
     * apply), and waits until it is in effect. Paced: the server kicks clients that keep sending commands faster than
     * about one a second (vanilla chat spam protection), and scenarios send a command right after most changes.
     */
    private static void choose(E2E e2e, Bot bot, String id, String value) {
        PlayerSetting<?> setting = e2e.services().settings().setting(id);
        e2e.expect(setting != null, id + " is a setting");
        String expected = setting instanceof Toggle ? ("on".equals(value) ? "true" : "off".equals(value) ? "false" : value) : value;
        UUID player = e2e.uuid(bot.name);
        e2e.sleep(1_000);
        bot.command("settings " + id + " " + value);
        e2e.eventually(() -> expected.equals(e2e.services().settings().encoded(player, id)), "/settings " + id + " " + value
            + " is in effect for " + bot.name + " (now " + e2e.services().settings().encoded(player, id) + "; chat " + bot.chat()
            + ", action bar " + bot.actionBar() + ")");
    }

    /** Changes any player's setting as another plugin would (works while they are offline too). */
    private static <T> void set(E2E e2e, String name, PlayerSetting<T> setting, T value) {
        SetResult result = e2e.services().settings().set(known(e2e, name), setting, value, Change.api("e2e"));
        e2e.expect(result.succeeded(), name + ": " + setting.id() + " = " + value + " went through: " + result);
    }

    /** A player's stored row for a setting, or null when none is stored (read after every queued write). */
    private static String stored(E2E e2e, UUID player, String setting) {
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

    /** Waits for a dialog titled exactly {@code title}. */
    private static Bot.SeenDialog page(E2E e2e, Bot bot, String title) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().title().equals(title), bot.name + " sees '" + title + "': "
            + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** {@code /settings <group>} and waits for the freshly sent first page. */
    private static void openGroup(E2E e2e, Bot bot, String group, String title) {
        Bot.SeenDialog before = bot.dialog();
        bot.command("settings " + group);
        e2e.eventually(() -> bot.dialog() != before && bot.dialog() != null && bot.dialog().title().equals(title),
            bot.name + " sees a fresh '" + title + "': " + (bot.dialog() == null ? "none" : bot.dialog().title()));
    }

    /** Every input key of a settings group across its pages, with each choice's options (a toggle as "toggle"). */
    static Map<String, List<String>> groupInputs(E2E e2e, Bot bot, String group, String title) {
        openGroup(e2e, bot, group, title);
        Map<String, List<String>> inputs = new java.util.LinkedHashMap<>();
        for (int guard = 0; guard < 10; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            current.inputs().forEach((key, kind) -> inputs.put(key, current.options().getOrDefault(key, List.of(kind))));
            if (current.button("Next page") == null) {
                return inputs;
            }
            e2e.click(bot, "Next page", current.values());
        }
        throw new E2E.Failure("too many pages in " + title);
    }

    /**
     * Opens a settings group and changes inputs wherever they are: walks the pages with Next page (changes carried
     * along), checks each choice offers the wanted option, and saves on the page where the last one was found.
     */
    static void editSettings(E2E e2e, Bot bot, String group, String title, Map<String, Object> wanted) {
        bot.clearMessages();
        openGroup(e2e, bot, group, title);
        Set<String> left = new HashSet<>(wanted.keySet());
        for (int guard = 0; guard < 10; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            Map<String, Object> values = current.values();
            for (String key : List.copyOf(left)) {
                if (current.inputs().containsKey(key)) {
                    Object value = wanted.get(key);
                    if (value instanceof String option) {
                        e2e.expect(current.options().getOrDefault(key, List.of()).contains(option), key + " offers " + option + ": "
                            + current.options().get(key));
                    }
                    values.put(key, value);
                    left.remove(key);
                }
            }
            if (left.isEmpty()) {
                e2e.click(bot, "Save", values);
                return;
            }
            e2e.expect(current.button("Next page") != null, "inputs " + left + " on a later page of " + title + " (last page: "
                + current.inputs().keySet() + ")");
            e2e.click(bot, "Next page", values);
        }
        throw new E2E.Failure("too many pages in " + title);
    }

    private static void clear(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            e2e.player(name).getInventory().setHeldItemSlot(0);
            return null;
        });
    }

    private static void setSlot(E2E e2e, String name, int slot, ItemStack item) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setItem(slot, item);
            return null;
        });
    }

    private static ItemStack slot(E2E e2e, String name, int slot) {
        return e2e.onPlayer(name, () -> {
            ItemStack stack = e2e.player(name).getInventory().getItem(slot);
            return stack == null ? ItemStack.empty() : stack.clone();
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

    /** A shulker box with {@code amount} diamonds in its first slot. */
    private static ItemStack boxOfDiamonds(int amount) {
        List<ItemStack> list = new ArrayList<>();
        list.add(ItemStack.of(Material.DIAMOND, amount));
        for (int i = 1; i < 27; i++) {
            list.add(ItemStack.empty());
        }
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(list));
        return box;
    }

    private static int diamondsInBox(ItemStack box) {
        ItemContainerContents contents = box.getData(DataComponentTypes.CONTAINER);
        int total = 0;
        for (ItemStack stack : contents == null ? List.<ItemStack>of() : contents.contents()) {
            if (stack.getType() == Material.DIAMOND) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        Player player = e2e.player(name);
        String value = e2e.services().placeholders().resolve(player, placeholder);
        return value == null ? "" : value;
    }

    /** Waits until a new player's mastery totals are loaded (selling is refused until then). */
    private static void masteryLoaded(E2E e2e, String name) {
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), name + "'s mastery is loaded");
    }

    /** The /pay cooldown (2 seconds in the shipped config) has passed. */
    private static void cooldown(E2E e2e) {
        e2e.sleep(2_100);
    }

    /** Rebuilds the money leaderboard now (it rebuilds on a timer), off the world threads like the timer. */
    private static void refreshLeaderboard(E2E e2e) {
        try {
            EconomyFeature feature = e2e.feature(EconomyFeature.class);
            Method refresh = EconomyFeature.class.getDeclaredMethod("refreshTop");
            refresh.setAccessible(true);
            refresh.invoke(feature);
        } catch (ReflectiveOperationException e) {
            throw new E2E.Failure("could not rebuild the money leaderboard: " + e);
        }
    }

    /** The top sellers' last read: when it was made, and the names in its places. */
    private record TopRead(long at, List<String> names) {
    }

    private static Object field(Object owner, Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** The top sellers' current snapshot, read through the sell feature (the class is not public). */
    private static TopRead topSellers(E2E e2e) {
        try {
            Object top = field(e2e.feature(SellFeature.class), SellFeature.class, "top");
            Method snapshot = top.getClass().getDeclaredMethod("snapshot");
            snapshot.setAccessible(true);
            Object read = snapshot.invoke(top);
            Method at = read.getClass().getDeclaredMethod("at");
            at.setAccessible(true);
            Method places = read.getClass().getDeclaredMethod("top");
            places.setAccessible(true);
            List<String> names = new ArrayList<>();
            for (Object entry : (List<?>) places.invoke(read)) {
                Method name = entry.getClass().getDeclaredMethod("name");
                name.setAccessible(true);
                names.add((String) name.invoke(entry));
            }
            return new TopRead((long) at.invoke(read), names);
        } catch (ReflectiveOperationException e) {
            throw new E2E.Failure("could not read the top sellers: " + e);
        }
    }

    /** What a player sold in total as stored (read after every queued write), what the top sellers read. */
    private static long storedSold(E2E e2e, UUID player) {
        try {
            return e2e.services().database().write(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(sold), 0) FROM sell_mastery WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading what " + player + " sold failed: " + e);
        }
    }

    /** When the sell feature reads the top sellers again because someone changed hide-from-leaderboards. */
    private static java.util.concurrent.atomic.AtomicLong topDueAt(E2E e2e) {
        try {
            return (java.util.concurrent.atomic.AtomicLong) field(e2e.feature(SellFeature.class), SellFeature.class, "topDueAt");
        } catch (ReflectiveOperationException e) {
            throw new E2E.Failure("could not read whether the top sellers are due: " + e);
        }
    }

    /** Whether someone's change of hide-from-leaderboards made an upcoming check read the top sellers again. */
    private static boolean topDue(E2E e2e) {
        return topDueAt(e2e).get() > 0;
    }

    /**
     * Reads the top sellers now, off the world threads like the 30-second check (which only reads when they are due):
     * marks them due and runs the check until a read that started after this call has finished.
     */
    private static TopRead refreshTopSellers(E2E e2e) {
        try {
            SellFeature feature = e2e.feature(SellFeature.class);
            java.util.concurrent.atomic.AtomicLong due = topDueAt(e2e);
            Method check = SellFeature.class.getDeclaredMethod("refreshTop");
            check.setAccessible(true);
            for (int round = 0; round < 5; round++) {
                long before = topSellers(e2e).at();
                due.set(1);
                check.invoke(feature);
                e2e.eventually(() -> topSellers(e2e).at() != before, "the top sellers were read again");
                if (due.get() == 0) {
                    return topSellers(e2e);
                }
            }
            throw new E2E.Failure("the top sellers kept being read by the timer instead");
        } catch (ReflectiveOperationException e) {
            throw new E2E.Failure("could not read the top sellers: " + e);
        }
    }

    // ------------------------------------------------------------------ the dialog

    /**
     * The Money &amp; selling group offers the payment and selling choices with the shared option names; a receiver
     * moves payment alerts above the hotbar and skips alerts below $1,000 through the dialog: a $100 payment arrives
     * quietly, a $1,000 one shows above the hotbar and not in chat.
     */
    static void settingsDialog(E2E e2e) {
        String payerName = e2e.name("DlgPayer");
        String payeeName = e2e.name("DlgPayee");
        Bot payer = e2e.bot(payerName);
        Bot payee = e2e.bot(payeeName);
        e2e.console("eco set " + payerName + " 100k");
        e2e.eventually(() -> e2e.money(payerName) == 100_000, "the payer has $100,000");

        e2e.step("the group offers the money and selling choices with the shared option names");
        Map<String, List<String>> inputs = groupInputs(e2e, payee, "economy", MONEY_PAGE);
        e2e.expect(List.of("chat", "actionbar", "off").equals(inputs.get("pay_notifications")), "payment alerts: " + inputs);
        e2e.expect(List.of("server", "always", "1k", "10k", "100k").equals(inputs.get("pay_confirm_above")), "confirm: " + inputs);
        e2e.expect(List.of("everyone", "friends-team", "friends", "nobody").equals(inputs.get("pay_accept_from")), "who may pay: " + inputs);
        e2e.expect(List.of("any", "100", "1k", "10k", "100k").equals(inputs.get("pay_alert_minimum")), "minimum: " + inputs);
        e2e.expect(List.of("server", "always", "10k", "100k", "1m", "never").equals(inputs.get("sell_all_confirm")), "sell all: " + inputs);
        e2e.expect(List.of("server", "keep", "sell").equals(inputs.get("sell_all_hotbar")), "hotbar: " + inputs);
        e2e.expect(List.of("return", "sell").equals(inputs.get("sell_menu_close")), "menu close: " + inputs);
        e2e.expect(List.of("chat", "actionbar", "title", "off").equals(inputs.get("mastery_levelup")), "level-ups: " + inputs);
        e2e.expect(List.of("chat", "actionbar", "off").equals(inputs.get("sell_receipts")), "sale receipts: " + inputs);
        for (String key : List.of("pay_join_summary", "sell_all_shulkers")) {
            e2e.expect(List.of("toggle").equals(inputs.get(key)), key + " is a switch here: " + inputs);
        }
        e2e.expect(inputs.size() <= 15, "at most 15 settings in the group: " + inputs.keySet());

        e2e.step("the receiver picks the hotbar and a $1,000 minimum in the dialog");
        editSettings(e2e, payee, "economy", MONEY_PAGE, Map.of("pay_notifications", "actionbar", "pay_alert_minimum", "1k"));
        e2e.eventually(() -> payee.anyFeedbackContains("Saved 2 settings"), "saved: " + payee.chat() + " " + payee.actionBar());
        UUID payeeId = e2e.uuid(payeeName);
        e2e.eventually(() -> "actionbar".equals(stored(e2e, payeeId, "pay-notifications")) && "1k".equals(stored(e2e, payeeId, "pay-alert-minimum")),
            "both stored");

        e2e.step("a payment below the minimum arrives without an alert");
        long before = e2e.money(payeeName);
        payee.clearLogs();
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> e2e.money(payeeName) == before + 100, "the money arrived");
        e2e.sleep(1_000);
        e2e.expect(!payee.anyFeedbackContains("paid you"), "no alert below $1,000: " + payee.chat() + " " + payee.actionBar());

        e2e.step("a payment at the minimum shows above the hotbar, not in chat");
        cooldown(e2e);
        payee.clearLogs();
        payer.command("pay " + payeeName + " 1000");
        e2e.eventually(() -> payee.actionBarContains(payerName + " paid you $1,000."), "the alert above the hotbar: " + payee.actionBar());
        e2e.expect(!payee.chatContains("paid you"), "not in chat: " + payee.chat());

        e2e.step("back to chat through the dialog: the stored row goes (chat is the default)");
        editSettings(e2e, payee, "economy", MONEY_PAGE, Map.of("pay_notifications", "chat"));
        e2e.eventually(() -> stored(e2e, payeeId, "pay-notifications") == null, "the default deletes the row");
        cooldown(e2e);
        payee.clearLogs();
        payer.command("pay " + payeeName + " 2000");
        e2e.eventually(() -> payee.chatContains(payerName + " paid you $2,000."), "the alert in chat again: " + payee.chat());
    }

    // ------------------------------------------------------------------ paying

    /**
     * When /pay asks (a lower amount, always, back to the server's), who may pay (nobody refuses in chat and in the
     * form, also while the receiver is offline; everyone again), the summary of payments received while away (on and
     * off), and an ignored payer refused until unignored.
     */
    static void paySettings(E2E e2e) {
        String payerName = e2e.name("SetPayer");
        String payeeName = e2e.name("SetPayee");
        Bot payer = e2e.bot(payerName);
        Bot payee = e2e.bot(payeeName);
        e2e.console("eco set " + payerName + " 200k");
        e2e.eventually(() -> e2e.money(payerName) == 200_000, "the payer has $200,000");

        e2e.step("a player who wants to confirm from $1,000 is asked for $2,000 (the server asks from $100,000)");
        choose(e2e, payer, "pay-confirm-above", "1k");
        payer.clearLogs();
        payer.command("pay " + payeeName + " 2000");
        Bot.SeenDialog confirm = e2e.dialog(payer, "Confirm payment");
        e2e.expect(confirm.bodyText().contains("$2,000"), "the amount: " + confirm.body());
        e2e.expect("close".equals(confirm.after()), "Pay and Cancel finish here, so the dialog closes at once: " + confirm.after());
        e2e.click(payer, "Cancel");
        e2e.expect(balance(e2e, payeeName) == 0, "nothing moved");
        e2e.step("always asks even for $10; server default pays $2,000 at once");
        cooldown(e2e);
        choose(e2e, payer, "pay-confirm-above", "always");
        payer.command("pay " + payeeName + " 10");
        e2e.dialog(payer, "Confirm payment");
        e2e.click(payer, "Cancel");
        cooldown(e2e);
        choose(e2e, payer, "pay-confirm-above", "server");
        int dialogs = payer.dialogs().size();
        payer.command("pay " + payeeName + " 2000");
        e2e.eventually(() -> balance(e2e, payeeName) == 2_000, "paid without asking");
        e2e.expect(payer.dialogs().size() == dialogs, "no confirmation: " + payer.dialogs());

        e2e.step("a receiver who accepts payments from nobody: refused in chat and in the form, nothing moves");
        choose(e2e, payee, "pay-accept-from", "nobody");
        cooldown(e2e);
        payer.clearLogs();
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> payer.anyFeedbackContains(payeeName + " doesn't accept payments from you."), "refused: " + payer.actionBar());
        e2e.expect(balance(e2e, payeeName) == 2_000, "nothing moved");
        cooldown(e2e);
        payer.command("pay");
        e2e.dialog(payer, "Pay a player");
        int seen = payer.dialogs().size();
        e2e.click(payer, "Submit", Map.of("player", payeeName, "amount", "100"));
        e2e.eventually(() -> payer.dialogs().size() > seen, "the form comes back");
        Bot.SeenDialog form = payer.dialogs().getLast();
        e2e.expect(form.title().equals("Pay a player") && form.bodyText().contains("doesn't accept payments from you"),
            "the refusal in the form: " + form.body());
        payer.clickButton("Cancel", Map.of());

        e2e.step("the same while the receiver is offline (their choice is read from storage)");
        UUID payeeId = e2e.uuid(payeeName);
        payee.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(payeeName) == null, "the receiver left");
        cooldown(e2e);
        payer.clearLogs();
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> payer.anyFeedbackContains(payeeName + " doesn't accept payments from you."), "refused offline: " + payer.actionBar());
        e2e.expect(balance(e2e, payeeName) == 2_000, "nothing moved");

        e2e.step("everyone again (changed while offline), then two payments while away");
        set(e2e, payeeName, EconomyFeature.PAY_ACCEPT_FROM, net.siftvanilla.siftcore.core.player.options.Audience.EVERYONE);
        e2e.eventually(() -> stored(e2e, payeeId, "pay-accept-from") == null, "back to the default");
        cooldown(e2e);
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> balance(e2e, payeeName) == 2_100, "the first payment arrived");
        cooldown(e2e);
        payer.command("pay " + payeeName + " 200");
        e2e.eventually(() -> balance(e2e, payeeName) == 2_300, "the second payment arrived");

        e2e.step("joining again sums up who paid while away");
        Bot back = e2e.bot(payeeName);
        e2e.eventually(() -> back.chatContains("While you were away you received $300:"), "the summary: " + back.chat());
        e2e.eventually(() -> back.chatContains(payerName + " paid you $300 in 2 payments"), "the payer's line: " + back.chat());
        e2e.expect(!back.chatContains("$2,000"), "payments from before the last session are not counted again: " + back.chat());

        e2e.step("with the summary off, joining again says nothing");
        choose(e2e, back, "pay-join-summary", "off");
        back.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(payeeName) == null, "the receiver left again");
        cooldown(e2e);
        payer.command("pay " + payeeName + " 50");
        e2e.eventually(() -> balance(e2e, payeeName) == 2_350, "the payment arrived");
        Bot again = e2e.bot(payeeName);
        e2e.sleep(4_000);
        e2e.expect(!again.chatContains("While you were away"), "no summary: " + again.chat());
        choose(e2e, again, "pay-join-summary", "on");

        e2e.step("an ignored payer can't pay, whatever the receiver accepts; unignored they can again");
        again.command("ignore " + payerName);
        var ignores = e2e.services().relations();
        e2e.eventually(() -> ignores.ignores(payeeId, e2e.uuid(payerName)), "the receiver ignores the payer");
        cooldown(e2e);
        payer.clearLogs();
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> payer.anyFeedbackContains("doesn't accept payments from you"), "refused: " + payer.actionBar());
        e2e.expect(balance(e2e, payeeName) == 2_350, "nothing moved");
        again.command("unignore " + payerName);
        e2e.eventually(() -> !ignores.ignores(payeeId, e2e.uuid(payerName)), "not ignored");
        cooldown(e2e);
        again.clearLogs();
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> again.chatContains(payerName + " paid you $100."), "paid and told: " + again.chat());
    }

    // ------------------------------------------------------------------ balance privacy

    /**
     * /balance of a player who keeps their balance from everyone: refused for other players (online and offline),
     * shown to themselves, the console and staff with siftcore.admin.eco; shown again once they allow everyone.
     */
    static void balancePrivacy(E2E e2e) {
        String viewerName = e2e.name("BalViewer");
        String targetName = e2e.name("BalTarget");
        Bot viewer = e2e.bot(viewerName);
        Bot target = e2e.bot(targetName);
        e2e.console("eco set " + targetName + " 12345");
        e2e.eventually(() -> e2e.money(targetName) == 12_345, "the target has $12,345");

        e2e.step("everyone sees it by default");
        viewer.clearLogs();
        viewer.command("balance " + targetName);
        e2e.eventually(() -> viewer.chatContains(targetName + " has $12,345"), "shown: " + viewer.chat());

        e2e.step("nobody: other players are refused, the target, the console and staff still see it");
        choose(e2e, target, "balance-privacy", "nobody");
        viewer.clearLogs();
        viewer.command("balance " + targetName);
        e2e.eventually(() -> viewer.anyFeedbackContains(targetName + " keeps their balance private."), "refused: " + viewer.actionBar());
        e2e.expect(!viewer.chatContains(targetName + " has"), "no amount: " + viewer.chat());
        target.clearLogs();
        target.command("balance " + targetName);
        e2e.eventually(() -> target.chatContains(targetName + " has $12,345"), "the target sees their own: " + target.chat());
        e2e.expect(String.join(" ", e2e.consoleOutput("balance " + targetName)).contains("has $12,345"), "the console sees it");
        PermissionAttachment staff = e2e.onPlayer(viewerName, () -> e2e.player(viewerName).addAttachment(harness(), "siftcore.admin.eco", true));
        viewer.clearLogs();
        viewer.command("balance " + targetName);
        e2e.eventually(() -> viewer.chatContains(targetName + " has $12,345"), "staff see it: " + viewer.chat());
        e2e.onPlayer(viewerName, () -> {
            e2e.player(viewerName).removeAttachment(staff);
            return null;
        });

        e2e.step("offline, the target's choice is read from storage");
        target.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(targetName) == null, "the target left");
        viewer.clearLogs();
        viewer.command("balance " + targetName);
        e2e.eventually(() -> viewer.anyFeedbackContains(targetName + " keeps their balance private."), "refused offline: " + viewer.actionBar());
        set(e2e, targetName, SharedSettings.BALANCE_PRIVACY, net.siftvanilla.siftcore.core.player.options.Audience.EVERYONE);
        viewer.clearLogs();
        viewer.command("balance " + targetName);
        e2e.eventually(() -> viewer.chatContains(targetName + " has $12,345"), "shown again: " + viewer.chat());
    }

    // ------------------------------------------------------------------ the money leaderboard

    /** A player with siftcore.stats.hide who hides from leaderboards leaves the money leaderboard, and comes back. */
    static void leaderboardHidden(E2E e2e) {
        String name = e2e.name("BalHidden");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        e2e.console("eco set " + name + " 900m");
        e2e.eventually(() -> e2e.money(name) == 900_000_000, "rich");
        refreshLeaderboard(e2e);
        EconomyApi economy = e2e.feature(EconomyFeature.class).economy();
        e2e.expect(economy.top(Currency.MONEY, 100).stream().anyMatch(entry -> entry.account().equals(id)), "on the leaderboard");
        e2e.expect(!"0".equals(placeholder(e2e, name, "baltop_rank")), "a place: " + placeholder(e2e, name, "baltop_rank"));

        e2e.step("hidden: off the list, no place, and the leaderboard dialog leaves them out");
        PermissionAttachment hide = e2e.onPlayer(name, () -> e2e.player(name).addAttachment(harness(), SharedSettings.HIDE_FROM_LEADERBOARDS_NODE, true));
        choose(e2e, bot, "hide-from-leaderboards", "on");
        refreshLeaderboard(e2e);
        e2e.expect(economy.top(Currency.MONEY, 100).stream().noneMatch(entry -> entry.account().equals(id)), "not on the leaderboard");
        e2e.expect("0".equals(placeholder(e2e, name, "baltop_rank")), "no place: " + placeholder(e2e, name, "baltop_rank"));
        bot.clearLogs();
        bot.command("baltop");
        Bot.SeenDialog top = e2e.dialog(bot, "Richest players");
        e2e.expect(!top.bodyText().contains(name) && !top.bodyText().contains("You are number"), "left out: " + top.body());
        bot.clickButton("Close", Map.of());

        e2e.step("without the permission the choice doesn't count; shown again once turned off");
        e2e.onPlayer(name, () -> {
            e2e.player(name).removeAttachment(hide);
            return null;
        });
        refreshLeaderboard(e2e);
        e2e.expect(economy.top(Currency.MONEY, 100).stream().anyMatch(entry -> entry.account().equals(id)), "listed without the permission");
        set(e2e, name, SharedSettings.HIDE_FROM_LEADERBOARDS, false);
        refreshLeaderboard(e2e);
        e2e.expect(economy.top(Currency.MONEY, 100).stream().anyMatch(entry -> entry.account().equals(id)), "back on the leaderboard");
        e2e.console("eco set " + name + " 0");
        refreshLeaderboard(e2e);
    }

    // ------------------------------------------------------------------ selling

    /**
     * When /sell all asks (from $1m: $25,600 sells at once; always: one diamond asks), the hotbar kept or sold, and
     * shulker boxes left shut or opened.
     */
    static void sellAllSettings(E2E e2e) {
        String name = e2e.name("SellSets");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        masteryLoaded(e2e, name);

        e2e.step("asking from $1m: a $25,600 sale goes through at once");
        choose(e2e, bot, "sell_all_confirm", "1m");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 64));
        int dialogs = bot.dialogs().size();
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) == 25_600, "sold at once (has " + e2e.money(name) + ")");
        e2e.expect(bot.dialogs().size() == dialogs, "no confirmation: " + bot.dialogs());

        e2e.step("always: even one diamond asks first");
        choose(e2e, bot, "sell_all_confirm", "always");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 1));
        bot.command("sell all");
        e2e.dialog(bot, "Sell everything");
        e2e.click(bot, "Cancel");
        e2e.expect(count(e2e, name, Material.DIAMOND) == 1 && e2e.money(name) == 25_600, "nothing sold");
        choose(e2e, bot, "sell_all_confirm", "never");
        clear(e2e, name);

        e2e.step("keep the hotbar: /sell all sells the storage slots only");
        choose(e2e, bot, "sell-all-hotbar", "keep");
        setSlot(e2e, name, 1, ItemStack.of(Material.DIAMOND, 10));
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 10));
        long before = e2e.money(name);
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) > before, "sold");
        e2e.sleep(300);
        e2e.expect(slot(e2e, name, 1).getAmount() == 10 && slot(e2e, name, 9).isEmpty(), "the hotbar stays: "
            + slot(e2e, name, 1) + " / " + slot(e2e, name, 9));
        e2e.step("server default sells the hotbar too (the server doesn't skip it)");
        choose(e2e, bot, "sell-all-hotbar", "server");
        long kept = e2e.money(name);
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) > kept && count(e2e, name, Material.DIAMOND) == 0, "the hotbar sold too");

        e2e.step("shulker boxes left shut, then opened");
        choose(e2e, bot, "sell-all-shulkers", "off");
        setSlot(e2e, name, 10, boxOfDiamonds(5));
        setSlot(e2e, name, 11, ItemStack.of(Material.DIAMOND, 2));
        long beforeBox = e2e.money(name);
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) > beforeBox, "the loose diamonds sold");
        e2e.sleep(300);
        e2e.expect(diamondsInBox(slot(e2e, name, 10)) == 5, "the box keeps its diamonds: " + slot(e2e, name, 10));
        choose(e2e, bot, "sell-all-shulkers", "on");
        long beforeOpen = e2e.money(name);
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) > beforeOpen, "the box's diamonds sold");
        e2e.eventually(() -> diamondsInBox(slot(e2e, name, 10)) == 0, "the box is empty and stays: " + slot(e2e, name, 10));
        e2e.expect(slot(e2e, name, 10).getType() == Material.SHULKER_BOX, "never the box itself");
    }

    /**
     * Closing the sell menu sells what is in it for a player who chose so;
     * giving back stays the default; quitting with a filled menu always gives the items back.
     */
    static void sellMenuClose(E2E e2e) {
        String name = e2e.name("MenuClose");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        masteryLoaded(e2e, name);

        e2e.step("sell on close: closing the menu sells the grid");
        choose(e2e, bot, "sell-menu-close", "sell");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 10));
        openMenu(e2e, bot);
        bot.clickSlot(45);
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 0, "the diamonds are in the grid");
        bot.clearLogs();
        bot.closeScreen();
        e2e.eventually(() -> e2e.money(name) == 4_000, "the grid sold on close (has " + e2e.money(name) + ")");
        e2e.eventually(() -> bot.chatContains("You sold 10 diamond for $4,000."), "the receipt: " + bot.chat());
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "nothing came back");
        e2e.expect(e2e.gridCopyCount(name, "sell_grid", Material.DIAMOND) == 0, "no copy of the grid left");

        e2e.step("give back (the default): closing gives everything back");
        choose(e2e, bot, "sell-menu-close", "return");
        setSlot(e2e, name, 9, ItemStack.of(Material.DIAMOND, 10));
        openMenu(e2e, bot);
        bot.clickSlot(45);
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 0, "the diamonds are in the grid");
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 10, "the diamonds came back");
        e2e.expect(e2e.money(name) == 4_000, "nothing sold");

        e2e.step("sell on close, but quitting gives the items back");
        choose(e2e, bot, "sell-menu-close", "sell");
        openMenu(e2e, bot);
        bot.clickSlot(45);
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 0, "the diamonds are in the grid");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, "left");
        e2e.bot(name);
        e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 10, "the diamonds are back after quitting");
        e2e.expect(balance(e2e, name) == 4_000, "nothing sold by quitting");
    }

    private static void openMenu(E2E e2e, Bot bot) {
        bot.command("sell");
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Sell items"),
            bot.name + " sees the sell menu (now " + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(300);
    }

    /**
     * A mastery level-up as a title (not in chat) for a player who picked Title, and not at all for Off; one sale that
     * levels up two categories sends one count above the hotbar and both lines to chat; with the sale receipt above the
     * hotbar the level-up goes to chat and never replaces the sale total.
     */
    static void masteryLevelUp(E2E e2e) {
        String name = e2e.name("LevelUp");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        clear(e2e, name);
        masteryLoaded(e2e, name);

        e2e.step("as a title");
        choose(e2e, bot, "mastery-levelup", "title");
        bot.clearLogs();
        sellDiamonds(e2e, bot, name, 64);
        sellDiamonds(e2e, bot, name, 61);
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "sell_mastery_mining")), "level 1 reached");
        e2e.eventually(() -> bot.titles().stream().anyMatch(title -> title.contains("Mining mastery 1")), "the title: " + bot.titles());
        e2e.expect(!bot.chatContains("mastery is now level"), "not in chat: " + bot.chat());

        e2e.step("off: nothing at all");
        e2e.console("sell admin mastery " + name + " reset");
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), "reset to 0");
        choose(e2e, bot, "mastery-levelup", "off");
        bot.clearLogs();
        sellDiamonds(e2e, bot, name, 64);
        sellDiamonds(e2e, bot, name, 61);
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "sell_mastery_mining")), "level 1 reached again");
        e2e.sleep(1_000);
        e2e.expect(bot.titles().stream().noneMatch(title -> title.contains("mastery")) && !bot.anyFeedbackContains("mastery"),
            "no level-up line: " + bot.titles() + " " + bot.chat() + " " + bot.actionBar());

        e2e.step("above the hotbar, one sale levelling up two categories: one count there, both lines in chat");
        e2e.console("sell admin mastery " + name + " reset");
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), "reset to 0 again");
        choose(e2e, bot, "mastery-levelup", "actionbar");
        choose(e2e, bot, "sell_all_confirm", "never");
        clear(e2e, name);
        // $150,000 of mob drops and $50,000 of mining: both reach level 1 in the same sale.
        setSlot(e2e, name, 9, ItemStack.of(Material.NETHER_STAR, 1));
        setSlot(e2e, name, 10, ItemStack.of(Material.NETHERITE_SCRAP, 25));
        bot.clearLogs();
        long beforeBoth = e2e.money(name);
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) > beforeBoth, "sold");
        e2e.eventually(() -> "1".equals(placeholder(e2e, name, "sell_mastery_mining"))
            && "1".equals(placeholder(e2e, name, "sell_mastery_mob_drops")), "both categories at level 1");
        e2e.eventually(() -> bot.actionBarContains("2 sell masteries levelled up. See chat."), "one count above the hotbar: "
            + bot.actionBar());
        e2e.eventually(() -> bot.chatContains("Mining mastery is now level 1.") && bot.chatContains("Mob drops mastery is now level 1."),
            "both lines in chat: " + bot.chat());
        e2e.expect(bot.chatContains("You sold"), "the receipt stays in chat: " + bot.chat());
        e2e.expect(bot.actionBar().stream().noneMatch(line -> line.contains("mastery is now level")),
            "no single level-up line above the hotbar to replace the count: " + bot.actionBar());

        e2e.step("receipt and level-up both above the hotbar: the level-up goes to chat, the sale total stays");
        e2e.console("sell admin mastery " + name + " reset");
        e2e.eventually(() -> "0".equals(placeholder(e2e, name, "sell_mastery_mining")), "reset to 0 once more");
        choose(e2e, bot, "sell_receipts", "actionbar");
        clear(e2e, name);
        setSlot(e2e, name, 9, ItemStack.of(Material.NETHERITE_SCRAP, 25));
        bot.clearLogs();
        long beforeOne = e2e.money(name);
        bot.command("sell all");
        e2e.eventually(() -> e2e.money(name) > beforeOne, "sold");
        e2e.eventually(() -> bot.chatContains("Mining mastery is now level 1."), "the level-up in chat: " + bot.chat());
        e2e.eventually(() -> bot.actionBarContains("+$50,000"), "the sale total above the hotbar: " + bot.actionBar());
        e2e.sleep(500);
        e2e.expect(bot.actionBar().stream().noneMatch(line -> line.contains("mastery")), "nothing replaced the total: " + bot.actionBar());
        e2e.expect(!bot.chatContains("You sold"), "no chat receipt with receipts above the hotbar: " + bot.chat());
        choose(e2e, bot, "sell_receipts", "chat");
        choose(e2e, bot, "sell_all_confirm", "server");
        e2e.console("sell admin mastery " + name + " reset");
    }

    // ------------------------------------------------------------------ the top sellers

    /**
     * A player with siftcore.stats.hide who hides from leaderboards (through {@code /settings}) leaves the top sellers:
     * no place in {@code /sell top} or {@code sell_top_name_<n>}, the next seller moves up, and their own
     * {@code /sell top} says they are hidden; the change is picked up at the next check, and they come back.
     */
    static void sellTopHidden(E2E e2e) {
        String hiddenName = e2e.name("TopHide");
        String shownName = e2e.name("TopShow");
        Bot hidden = e2e.bot(hiddenName);
        Bot shown = e2e.bot(shownName);
        masteryLoaded(e2e, hiddenName);
        masteryLoaded(e2e, shownName);
        // Far above anything other scenarios sell: $75m and $50m at base value.
        for (String category : List.of("mining", "farming", "wood")) {
            e2e.console("sell admin mastery " + hiddenName + " " + category + " set 5");
        }
        for (String category : List.of("mining", "farming")) {
            e2e.console("sell admin mastery " + shownName + " " + category + " set 5");
        }
        UUID hiddenId = e2e.uuid(hiddenName);
        UUID shownId = e2e.uuid(shownName);
        e2e.eventually(() -> storedSold(e2e, hiddenId) == 75_000_000L && storedSold(e2e, shownId) == 50_000_000L,
            "both stored at the top levels: " + storedSold(e2e, hiddenId) + " / " + storedSold(e2e, shownId));

        e2e.step("both listed: the bigger seller first");
        TopRead read = refreshTopSellers(e2e);
        e2e.expect(read.names().size() >= 2 && read.names().get(0).equals(hiddenName) && read.names().get(1).equals(shownName),
            "the order: " + read.names());
        e2e.expect(hiddenName.equals(placeholder(e2e, shownName, "sell_top_name_1")), "sell_top_name_1: "
            + placeholder(e2e, shownName, "sell_top_name_1"));

        e2e.step("hidden through /settings: the change marks the top sellers due, and the next read leaves them out");
        PermissionAttachment hide = e2e.onPlayer(hiddenName,
            () -> e2e.player(hiddenName).addAttachment(harness(), SharedSettings.HIDE_FROM_LEADERBOARDS_NODE, true));
        long beforeChange = topSellers(e2e).at();
        choose(e2e, hidden, "hide-from-leaderboards", "on");
        e2e.expect(topDue(e2e) || topSellers(e2e).at() > beforeChange,
            "turning the switch on makes the top sellers due at the next check");
        read = refreshTopSellers(e2e);
        e2e.expect(!read.names().contains(hiddenName), "no place for the hidden seller: " + read.names());
        e2e.expect(!read.names().isEmpty() && read.names().get(0).equals(shownName), "the next seller moves up: " + read.names());
        e2e.expect(shownName.equals(placeholder(e2e, shownName, "sell_top_name_1")), "sell_top_name_1 moves up too: "
            + placeholder(e2e, shownName, "sell_top_name_1"));
        for (int place = 1; place <= 10; place++) {
            String named = placeholder(e2e, shownName, "sell_top_name_" + place);
            e2e.expect(!hiddenName.equals(named), "sell_top_name_" + place + " never names the hidden seller");
        }

        e2e.step("/sell top: the list leaves them out, they are told they have no place, the other has place 1");
        hidden.command("sell top");
        Bot.SeenDialog own = e2e.dialog(hidden, "Top sellers");
        e2e.expect(!own.bodyText().contains(hiddenName), "not listed: " + own.body());
        e2e.expect(own.bodyText().contains("You are hidden from leaderboards, so you have no place here."), "told: " + own.body());
        hidden.clickButton("Close", Map.of());
        shown.command("sell top");
        Bot.SeenDialog other = e2e.dialog(shown, "Top sellers");
        e2e.expect(other.bodyText().contains("1. " + shownName) && other.bodyText().contains("You: #1"), "place 1: " + other.body());
        e2e.expect(!other.bodyText().contains(hiddenName), "the hidden seller is not shown to others: " + other.body());
        shown.clickButton("Close", Map.of());

        e2e.step("shown again once turned off");
        choose(e2e, hidden, "hide-from-leaderboards", "off");
        read = refreshTopSellers(e2e);
        e2e.expect(!read.names().isEmpty() && read.names().get(0).equals(hiddenName), "back at place 1: " + read.names());
        e2e.onPlayer(hiddenName, () -> {
            e2e.player(hiddenName).removeAttachment(hide);
            return null;
        });
        e2e.console("sell admin mastery " + hiddenName + " reset");
        e2e.console("sell admin mastery " + shownName + " reset");
        refreshTopSellers(e2e);
    }

    private static void sellDiamonds(E2E e2e, Bot bot, String name, int amount) {
        long before = e2e.money(name);
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setHeldItemSlot(0);
            e2e.player(name).getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND, amount));
            return null;
        });
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) > before, amount + " diamonds sold");
    }
}
