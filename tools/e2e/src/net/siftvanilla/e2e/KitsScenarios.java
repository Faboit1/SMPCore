package net.siftvanilla.e2e;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.economy.Deliveries;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import net.siftvanilla.siftcore.feature.kits.KitsFeature;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Kits and rank perks end to end: the /kits dialog and a kit's dialog (status, contents, Claim, claim all, the hub
 * entry and the join reminder), /kit with its refusals, cooldowns and staff resets, the rank kits (prospector, baron,
 * tycoon) behind the nodes their LuckPerms groups grant, the claim box when the inventory is full and collecting from
 * /kits, double submits, combat (claims and every perk refused, perk screens closed), staff give/check/list from the
 * console, every perk command (crafting and anvil used for real, the other workstations, the ender chest, the trash,
 * the hat), looking into another player's ender chest, config reloads (a new kit, a kit of crate keys, ready
 * reminders, locked kits shown) and claims surviving a restart.
 */
final class KitsScenarios {

    private KitsScenarios() {
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
        list.add(of("kits-dialog", KitsScenarios::dialog));
        list.add(of("kits-claim-all", KitsScenarios::claimAll));
        list.add(of("kits-cooldown", KitsScenarios::cooldown));
        list.add(of("kits-ranks", KitsScenarios::ranks));
        list.add(of("kits-claim-box", KitsScenarios::claimBox));
        list.add(of("kits-double-submit", KitsScenarios::doubleSubmit));
        list.add(of("kits-combat", KitsScenarios::combat));
        list.add(of("kits-admin", KitsScenarios::admin));
        list.add(of("kits-perks", KitsScenarios::perks));
        list.add(of("kits-workstations", KitsScenarios::workstations));
        list.add(of("kits-ec-others", KitsScenarios::ecOthers));
        list.add(of("kits-config", KitsScenarios::config));
        list.add(of("kits-persist-setup", KitsScenarios::persistSetup));
        list.add(of("kits-persist-check", KitsScenarios::persistCheck));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** Waits for a condition; the failure message is built when it fails, so it shows what the bot really got. */
    private static void waitFor(E2E e2e, java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> what) {
        waitFor(e2e, condition, 10_000, what);
    }

    private static void waitFor(E2E e2e, java.util.function.BooleanSupplier condition, long millis,
                                java.util.function.Supplier<String> what) {
        if (!Bot.await(condition, millis)) {
            throw new E2E.Failure("expected " + what.get());
        }
    }

    /** Sends a command the way a player types one: paced, so the server's chat spam protection never kicks in. */
    private static void command(E2E e2e, Bot bot, String command) {
        e2e.sleep(700);
        bot.command(command);
    }

    private static void clear(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            return null;
        });
    }

    private static void put(E2E e2e, String name, int slot, ItemStack item) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setItem(slot, item);
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

    private static ItemStack find(E2E e2e, String name, Material material) {
        return e2e.onPlayer(name, () -> {
            for (ItemStack stack : e2e.player(name).getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == material) {
                    return stack.clone();
                }
            }
            return null;
        });
    }

    /** Fills every storage slot with full stacks of dirt. */
    private static void fill(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            var inventory = e2e.player(name).getInventory();
            for (int i = 0; i < 36; i++) {
                inventory.setItem(i, ItemStack.of(Material.DIRT, 64));
            }
            return null;
        });
    }

    /** Grants permission nodes for as long as the bot stays online. */
    private static void grant(E2E e2e, String name, String... nodes) {
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            for (String node : nodes) {
                player.addAttachment(e2e.services().plugin(), node, true);
            }
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

    private static long claimRows(E2E e2e, UUID uuid, String kit) {
        return number(e2e, "SELECT COUNT(*) FROM kit_claims WHERE uuid = ? AND kit = ?", uuid.toString(), kit);
    }

    private static long audits(E2E e2e, String action, UUID target) {
        return number(e2e, "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target = ?", action, target.toString());
    }

    /** Kit stacks waiting in the player's claim box. */
    private static int waiting(E2E e2e, UUID uuid) {
        int count = 0;
        for (Deliveries.Delivery delivery : e2e.services().deliveries().of(uuid)) {
            if ("kit".equals(delivery.source())) {
                count++;
            }
        }
        return count;
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(e2e.player(name), placeholder);
    }

    private static int keys(E2E e2e, UUID uuid, String crate) {
        return e2e.feature(CratesFeature.class).keys().keys(uuid, crate);
    }

    private static void ledgerHealthy(E2E e2e) throws Exception {
        Ledger.AuditReport report = e2e.services().ledger().audit().get(20, TimeUnit.SECONDS);
        e2e.expect(report.healthy(), "the ledger invariants hold: " + report.problems());
    }

    private static Bot.SeenDialog awaitBody(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains(text),
            bot.name + " sees a dialog saying '" + text + "' (last: " + (bot.dialog() == null ? "none" : bot.dialog().bodyText()) + ")");
        return bot.dialog();
    }

    private static void awaitScreen(E2E e2e, Bot bot, String type, String title) {
        e2e.eventually(() -> bot.screen() != null && bot.screen().type().equals(type) && (title == null || bot.screen().title().equals(title)),
            bot.name + " sees a " + type + " screen" + (title == null ? "" : " titled '" + title + "'") + " (now "
                + (bot.screen() == null ? "none" : bot.screen().type() + " '" + bot.screen().title() + "'") + ")");
        e2e.sleep(300);
    }

    private static void awaitNoScreen(E2E e2e, Bot bot) {
        e2e.eventually(() -> bot.screen() == null, bot.name + " has no screen open (now "
            + (bot.screen() == null ? "none" : bot.screen().type()) + ")");
    }

    /** Hits {@code victim} until the hit lands and they are in combat. */
    private static void fight(E2E e2e, Bot attacker, String victim) {
        Location at = e2e.onPlayer(attacker.name, () -> e2e.player(attacker.name).getLocation().clone());
        try {
            e2e.expect(Boolean.TRUE.equals(e2e.player(victim).teleportAsync(at).get(10, TimeUnit.SECONDS)), victim + " moved next to "
                + attacker.name);
        } catch (E2E.Failure failure) {
            throw failure;
        } catch (Exception e) {
            throw new E2E.Failure("moving " + victim + " failed: " + e);
        }
        e2e.sleep(400);
        long end = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < end) {
            attacker.attack(e2e.player(victim).getEntityId());
            if (Bot.await(() -> inCombat(e2e, victim), 700)) {
                return;
            }
        }
        throw new E2E.Failure(attacker.name + " could not land a hit on " + victim);
    }

    private static boolean inCombat(E2E e2e, String name) {
        return "true".equals(placeholder(e2e, name, "combat_tagged"));
    }

    // ------------------------------------------------------------------ the dialog

    static void dialog(E2E e2e) throws Exception {
        String name = e2e.name("KtDialog");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);

        e2e.step("a new player is told which kits are ready when they join");
        waitFor(e2e, () -> bot.chatContains("Kits ready to claim: Starter, Daily."), () -> "join reminder: " + bot.chat() + " / " + bot.actionBar() + " / " + bot.chat());

        e2e.step("/kits lists the kits a player has, with their status; rank kits are hidden");
        command(e2e, bot, "kits");
        Bot.SeenDialog list = e2e.dialog(bot, "Kits");
        e2e.expect(list.bodyText().contains("Starter: ready") && list.bodyText().contains("Daily: ready"), "both ready: " + list.body());
        e2e.expect(!list.bodyText().contains("Prospector") && list.button("Prospector") == null, "rank kits are hidden: " + list.body());
        e2e.expect(list.button("Claim 2 ready kits") != null, "a claim-all button for two ready kits: " + list.buttons());

        e2e.step("a kit's dialog shows what it gives and claims it");
        e2e.click(bot, "Starter");
        Bot.SeenDialog kit = e2e.dialog(bot, "Starter kit");
        e2e.expect(kit.bodyText().contains("Stone tools, leather armor and bread") && kit.bodyText().contains("One claim per player")
            && kit.bodyText().contains("Ready to claim"), "description, cooldown and status: " + kit.body());
        e2e.expect(kit.bodyText().contains("Stone Pickaxe") && kit.bodyText().contains("Leather Tunic") && kit.bodyText().contains("Bread"),
            "the items are listed: " + kit.body());
        e2e.click(bot, "Claim");
        waitFor(e2e, () -> bot.actionBarContains("You claimed the Starter kit."), () -> "claimed: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.eventually(() -> count(e2e, name, Material.STONE_PICKAXE) == 1 && count(e2e, name, Material.BREAD) == 16
            && count(e2e, name, Material.LEATHER_CHESTPLATE) == 1 && count(e2e, name, Material.STONE_SWORD) == 1, "the starter items arrived");
        e2e.expect(claimRows(e2e, uuid, "starter") == 1, "the claim is stored");
        e2e.expect(audits(e2e, "kits.claim", uuid) == 1, "the claim is audited");
        e2e.expect(waiting(e2e, uuid) == 0, "nothing waits in the claim box");

        e2e.step("the kit now reads claimed everywhere");
        e2e.eventually(() -> "claimed".equals(placeholder(e2e, name, "kit_starter")), "kit_starter: " + placeholder(e2e, name, "kit_starter"));
        e2e.expect("ready".equals(placeholder(e2e, name, "kit_daily")), "kit_daily is ready");
        e2e.expect("locked".equals(placeholder(e2e, name, "kit_tycoon")), "kit_tycoon is locked");
        e2e.expect("1".equals(placeholder(e2e, name, "kits_ready")), "one kit ready: " + placeholder(e2e, name, "kits_ready"));
        command(e2e, bot, "kits");
        Bot.SeenDialog after = e2e.dialog(bot, "Kits");
        e2e.eventually(() -> bot.dialog().bodyText().contains("Starter: claimed"), "starter claimed in the list: " + bot.dialog().body());
        e2e.expect(bot.dialog().button("ready kits") == null && bot.dialog().button("Claim Daily") != null,
            "with one ready kit the list claims it directly: " + bot.dialog().buttons());
        e2e.click(bot, "Starter");
        Bot.SeenDialog claimed = e2e.dialog(bot, "Starter kit");
        e2e.expect(claimed.bodyText().contains("You already claimed this kit.") && claimed.button("Claim") == null,
            "no claim button for a claimed kit: " + claimed.body() + " " + claimed.buttons());
        e2e.click(bot, "Back");
        e2e.dialog(bot, "Kits");
        bot.closeScreen();
        e2e.expect(after != null, "list shown");

        e2e.step("/kit starter again is refused");
        bot.clearLogs();
        command(e2e, bot, "kit starter");
        waitFor(e2e, () -> bot.actionBarContains("You already claimed the Starter kit."), () -> "refused: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(count(e2e, name, Material.STONE_PICKAXE) == 1, "no second pickaxe");

        e2e.step("the main menu has a Kits button that opens the kits with a way back");
        command(e2e, bot, "menu");
        e2e.dialog(bot, "SiftVanilla");
        e2e.click(bot, "Kits");
        Bot.SeenDialog fromHub = e2e.dialog(bot, "Kits");
        e2e.expect(fromHub.button("Back") != null, "a back button to the menu: " + fromHub.buttons());
        e2e.click(bot, "Back");
        e2e.dialog(bot, "SiftVanilla");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ claiming every ready kit

    static void claimAll(E2E e2e) throws Exception {
        String name = e2e.name("KtAll");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);
        grant(e2e, name, "siftcore.kit.baron");

        e2e.step("one button claims every ready kit");
        bot.clearLogs();
        command(e2e, bot, "kits");
        Bot.SeenDialog list = e2e.dialog(bot, "Kits");
        e2e.expect(list.button("Claim 3 ready kits") != null, "claim three: " + list.buttons());
        e2e.click(bot, "Claim 3 ready kits");
        waitFor(e2e, () -> bot.actionBarContains("You claimed 3 kits."), () -> "claimed all: " + bot.actionBar() + " / " + bot.chat());
        waitFor(e2e, () -> count(e2e, name, Material.STONE_PICKAXE) == 1 && count(e2e, name, Material.COOKED_BEEF) == 48
            && count(e2e, name, Material.TORCH) == 64 && count(e2e, name, Material.LANTERN) == 8 && count(e2e, name, Material.GLASS) == 64,
            () -> "the starter, daily and baron items arrived");
        e2e.expect(claimRows(e2e, uuid, "starter") == 1 && claimRows(e2e, uuid, "daily") == 1 && claimRows(e2e, uuid, "baron") == 1,
            "three claims stored");

        e2e.step("nothing is ready any more");
        command(e2e, bot, "kits");
        Bot.SeenDialog after = e2e.dialog(bot, "Kits");
        e2e.expect(after.button("ready kits") == null && after.bodyText().contains("Baron: in ") && after.bodyText().contains("Starter: claimed"),
            "all claimed: " + after.body());
        e2e.expect("0".equals(placeholder(e2e, name, "kits_ready")), "kits_ready is 0");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ cooldowns

    static void cooldown(E2E e2e) throws Exception {
        String name = e2e.name("KtCool");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);

        e2e.step("/kit daily claims straight away");
        command(e2e, bot, "kit daily");
        e2e.eventually(() -> count(e2e, name, Material.COOKED_BEEF) == 16 && count(e2e, name, Material.BAKED_POTATO) == 16
            && count(e2e, name, Material.APPLE) == 8, "the daily food arrived");
        waitFor(e2e, () -> bot.actionBarContains("You claimed the Daily kit."), () -> "claimed: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());

        e2e.step("claiming again says when it is ready");
        bot.clearLogs();
        command(e2e, bot, "kit daily");
        waitFor(e2e, () -> (bot.actionBarContains("The Daily kit is ready again in 1d.") || bot.actionBarContains("The Daily kit is ready again in 23h 59m.")), () -> "cooldown: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(count(e2e, name, Material.COOKED_BEEF) == 16, "nothing more was given");
        String status = placeholder(e2e, name, "kit_daily");
        e2e.expect((status.startsWith("in 23h 59m") || status.equals("in 1d")), "kit_daily counts down: " + status);

        e2e.step("unknown kits are named");
        bot.clearLogs();
        command(e2e, bot, "kit nosuchkit");
        waitFor(e2e, () -> bot.actionBarContains("There is no kit called nosuchkit."), () -> "unknown: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());

        e2e.step("staff reset one kit's cooldown from the console");
        List<String> reset = e2e.consoleOutput("kits reset " + name + " daily");
        e2e.expect(reset.stream().anyMatch(line -> line.contains("Reset " + name + "'s Daily kit.")), "reset: " + reset);
        e2e.expect(claimRows(e2e, uuid, "daily") == 0, "the stored claim is gone");
        e2e.expect(audits(e2e, "kits.reset", uuid) == 1, "the reset is audited");
        bot.clearLogs();
        command(e2e, bot, "kit daily");
        e2e.eventually(() -> count(e2e, name, Material.COOKED_BEEF) == 32, "claimed again after the reset");

        e2e.step("with one kit ready, the list claims it with one click");
        bot.clearLogs();
        command(e2e, bot, "kits");
        e2e.dialog(bot, "Kits");
        e2e.click(bot, "Claim Starter");
        waitFor(e2e, () -> bot.actionBarContains("You claimed the Starter kit."), () -> "claimed from the list: " + bot.actionBar());
        e2e.eventually(() -> count(e2e, name, Material.STONE_PICKAXE) == 1, "the starter items arrived");

        e2e.step("resetting every kit, then nothing left to reset");
        List<String> all = e2e.consoleOutput("kits reset " + name);
        e2e.expect(all.stream().anyMatch(line -> line.contains("Reset every kit of " + name + ".")), "reset all: " + all);
        List<String> none = e2e.consoleOutput("kits reset " + name);
        e2e.expect(none.stream().anyMatch(line -> line.contains(name + " has no kit to reset.")), "nothing to reset: " + none);
        e2e.expect("ready".equals(placeholder(e2e, name, "kit_daily")), "daily ready again");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ rank kits

    static void ranks(E2E e2e) throws Exception {
        String name = e2e.name("KtRank");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);

        e2e.step("a rank kit without the rank is refused");
        command(e2e, bot, "kit prospector");
        waitFor(e2e, () -> bot.actionBarContains("You haven't unlocked the Prospector kit."), () -> "locked: " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(count(e2e, name, Material.TORCH) == 0 && claimRows(e2e, uuid, "prospector") == 0, "nothing given or stored");

        e2e.step("with the rank's node (as the prospector group grants it) it is listed and claimed");
        grant(e2e, name, "siftcore.kit.prospector");
        command(e2e, bot, "kits");
        Bot.SeenDialog list = e2e.dialog(bot, "Kits");
        e2e.expect(list.bodyText().contains("Prospector: ready") && !list.bodyText().contains("Tycoon") && !list.bodyText().contains("Baron"),
            "prospector listed, higher ranks hidden: " + list.body());
        e2e.click(bot, "Prospector");
        Bot.SeenDialog kit = e2e.dialog(bot, "Prospector kit");
        e2e.expect(kit.bodyText().contains("Claim it every 1d") && kit.bodyText().contains("Torch") && kit.bodyText().contains("Oak Log")
            && !kit.bodyText().contains("Also gives"), "cooldown and supplies shown, no keys: " + kit.body());
        e2e.click(bot, "Claim");
        e2e.eventually(() -> count(e2e, name, Material.TORCH) == 32 && count(e2e, name, Material.OAK_LOG) == 32
            && count(e2e, name, Material.COOKED_BEEF) == 16, "the prospector supplies arrived");
        e2e.expect(claimRows(e2e, uuid, "prospector") == 1, "the claim is stored");

        e2e.step("higher rank kits stay locked");
        bot.clearLogs();
        command(e2e, bot, "kit tycoon");
        waitFor(e2e, () -> bot.actionBarContains("You haven't unlocked the Tycoon kit."), () -> "tycoon locked: " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(count(e2e, name, Material.NAME_TAG) == 0, "no tycoon items");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ the claim box

    static void claimBox(E2E e2e) throws Exception {
        String name = e2e.name("KtBox");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);

        e2e.step("a kit claimed with a full inventory waits in the claim box");
        fill(e2e, name);
        command(e2e, bot, "kit daily");
        waitFor(e2e, () -> bot.chatContains("Some of the Daily kit didn't fit."), () -> "told it waits: " + bot.chat() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(waiting(e2e, uuid) == 3, "three kit stacks wait: " + waiting(e2e, uuid));
        e2e.expect(claimRows(e2e, uuid, "daily") == 1, "the claim counts");

        e2e.step("/kits offers to collect them and refuses while there is no room");
        bot.clearLogs();
        command(e2e, bot, "kits");
        Bot.SeenDialog list = e2e.dialog(bot, "Kits");
        e2e.expect(list.bodyText().contains("Some kit items are waiting for room") && list.button("Collect waiting items") != null,
            "collect offered: " + list.body() + " " + list.buttons());
        e2e.click(bot, "Collect waiting items");
        waitFor(e2e, () -> bot.actionBarContains("Make room in your inventory first."), () -> "no room: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(waiting(e2e, uuid) == 3, "still waiting");

        e2e.step("with room for some, some are collected");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setItem(0, null);
            return null;
        });
        bot.clearLogs();
        command(e2e, bot, "kits");
        e2e.dialog(bot, "Kits");
        e2e.click(bot, "Collect waiting items");
        waitFor(e2e, () -> bot.actionBarContains("You collected some of your kit items."), () -> "partly: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(waiting(e2e, uuid) == 2 && count(e2e, name, Material.COOKED_BEEF) == 16, "the beef came, two stacks wait");

        e2e.step("a rejoin reminds them, and with room everything is collected");
        bot.quit();
        e2e.eventually(() -> org.bukkit.Bukkit.getPlayerExact(name) == null, name + " left");
        Bot again = e2e.bot(name);
        waitFor(e2e, () -> again.chatContains("Kit items are waiting for room in your inventory."), () -> "waiting reminder: " + again.chat() + " / " + again.actionBar() + " / " + again.chat());
        clear(e2e, name);
        again.clearLogs();
        command(e2e, again, "kits");
        e2e.dialog(again, "Kits");
        e2e.click(again, "Collect waiting items");
        waitFor(e2e, () -> again.actionBarContains("You collected your kit items."), () -> "collected: " + again.actionBar() + " / " + again.actionBar() + " / " + again.chat());
        e2e.expect(waiting(e2e, uuid) == 0 && count(e2e, name, Material.BAKED_POTATO) == 16 && count(e2e, name, Material.APPLE) == 8,
            "everything is in the inventory");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ double submits

    static void doubleSubmit(E2E e2e) throws Exception {
        String name = e2e.name("KtSpam");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);

        e2e.step("five /kit daily at once claim it once");
        e2e.sleep(700);
        for (int i = 0; i < 5; i++) {
            bot.command("kit daily");
        }
        e2e.eventually(() -> count(e2e, name, Material.COOKED_BEEF) == 16, "one claim arrived");
        e2e.sleep(1_500);
        e2e.expect(count(e2e, name, Material.COOKED_BEEF) == 16 && count(e2e, name, Material.APPLE) == 8, "and only one: "
            + count(e2e, name, Material.COOKED_BEEF));
        e2e.expect(audits(e2e, "kits.claim", uuid) == 1, "one claim audited");

        e2e.step("a replayed claim button does nothing");
        command(e2e, bot, "kits");
        e2e.dialog(bot, "Kits");
        e2e.click(bot, "Starter");
        Bot.SeenDialog kit = e2e.dialog(bot, "Starter kit");
        Bot.Button claim = kit.button("Claim");
        e2e.expect(claim != null, "a claim button");
        bot.rawClick(claim.actionId(), null);
        bot.rawClick(claim.actionId(), null);
        e2e.eventually(() -> count(e2e, name, Material.STONE_PICKAXE) == 1, "claimed once");
        e2e.sleep(1_500);
        e2e.expect(count(e2e, name, Material.STONE_PICKAXE) == 1 && claimRows(e2e, uuid, "starter") == 1, "exactly one starter kit");
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ combat

    static void combat(E2E e2e) throws Exception {
        String fighterName = e2e.name("KtFighter");
        String name = e2e.name("KtTagged");
        Bot fighter = e2e.bot(fighterName);
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);
        grant(e2e, name, "siftcore.perk.stonecutter", "siftcore.perk.trash", "siftcore.perk.hat");
        e2e.sleep(3_500);

        e2e.step("an open perk screen closes when its player gets into combat");
        command(e2e, bot, "stonecutter");
        awaitScreen(e2e, bot, "minecraft:stonecutter", null);
        bot.clearLogs();
        fight(e2e, fighter, name);
        awaitNoScreen(e2e, bot);
        waitFor(e2e, () -> bot.actionBarContains("Closed because you're in combat."), () -> "told why: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());

        e2e.step("in combat every perk is refused, the ones the combat feature lets through too");
        for (String perk : List.of("stonecutter", "trash", "hat")) {
            bot.clearLogs();
            command(e2e, bot, perk);
            waitFor(e2e, () -> bot.actionBarContains("You can't use that in combat."), () -> perk + " refused: " + bot.actionBar() + " / " + bot.chat());
            e2e.sleep(300);
            e2e.expect(bot.screen() == null, "no screen opened for " + perk);
        }

        e2e.step("kits can't be claimed in combat, from a command or a dialog");
        bot.clearLogs();
        command(e2e, bot, "kit daily");
        waitFor(e2e, () -> bot.actionBarContains("in combat"), () -> "/kit refused in combat: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        command(e2e, bot, "menu");
        e2e.dialog(bot, "SiftVanilla");
        e2e.click(bot, "Kits");
        e2e.dialog(bot, "Kits");
        e2e.click(bot, "Daily");
        e2e.dialog(bot, "Daily kit");
        e2e.click(bot, "Claim");
        awaitBody(e2e, bot, "You can't claim kits in combat.");
        e2e.expect(count(e2e, name, Material.COOKED_BEEF) == 0 && claimRows(e2e, uuid, "daily") == 0, "nothing claimed");

        e2e.step("once the combat tag is gone the kit is claimed");
        e2e.console("combat untag " + name);
        e2e.eventually(() -> !inCombat(e2e, name), "no longer in combat");
        bot.closeScreen();
        command(e2e, bot, "kit daily");
        e2e.eventually(() -> count(e2e, name, Material.COOKED_BEEF) == 16, "claimed after combat");
        e2e.expect(fighter != null, "fighter present");
    }

    // ------------------------------------------------------------------ staff

    static void admin(E2E e2e) throws Exception {
        String name = e2e.name("KtGift");
        String absentName = e2e.name("KtAbsent");
        Bot absentBot = e2e.bot(absentName);
        UUID absent = e2e.uuid(absentName);
        absentBot.quit();
        e2e.eventually(() -> org.bukkit.Bukkit.getPlayerExact(absentName) == null, absentName + " left");
        Bot bot = e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        clear(e2e, name);

        e2e.step("the console lists every kit");
        List<String> list = e2e.consoleOutput("kits");
        e2e.expect(list.stream().anyMatch(line -> line.startsWith("Kits (")), "header: " + list);
        e2e.expect(list.stream().anyMatch(line -> line.startsWith("Tycoon (tycoon): Claim it every 1d, 7 items, siftcore.kit.tycoon")),
            "tycoon line: " + list);
        e2e.expect(list.stream().anyMatch(line -> line.startsWith("Starter (starter): One claim per player, 9 items, everyone")),
            "starter line: " + list);

        e2e.step("staff give a kit the player doesn't have; their cooldown doesn't start");
        List<String> given = e2e.consoleOutput("kits give " + name + " tycoon");
        e2e.eventually(() -> count(e2e, name, Material.OAK_LOG) == 128 && count(e2e, name, Material.GLASS) == 128
            && count(e2e, name, Material.LANTERN) == 16 && count(e2e, name, Material.NAME_TAG) == 1, "the tycoon supplies arrived");
        waitFor(e2e, () -> bot.chatContains("Staff gave you the Tycoon kit."), () -> "told: " + bot.chat() + " / " + bot.actionBar());
        e2e.expect(claimRows(e2e, uuid, "tycoon") == 0, "no cooldown started");
        e2e.expect(audits(e2e, "kits.give", uuid) == 1, "the gift is audited");
        e2e.eventually(() -> e2e.consoleOutput("kits check " + name).stream().anyMatch(line -> line.equals("Tycoon (tycoon): locked")),
            "check shows tycoon locked for them");
        e2e.expect(given.stream().anyMatch(line -> line.contains("Gave " + name + " the Tycoon kit.")), "staff told: " + given);

        e2e.step("a gift to an offline player waits in their claim box");
        List<String> offline = e2e.consoleOutput("kits give " + absentName + " daily");
        e2e.eventually(() -> waiting(e2e, absent) == 3, "three stacks wait for " + absentName + ": " + waiting(e2e, absent));
        e2e.expect(offline.stream().anyMatch(line -> line.contains(absentName + " is offline.")) || waiting(e2e, absent) == 3,
            "told: " + offline);

        e2e.step("check shows each kit's status");
        command(e2e, bot, "kit daily");
        e2e.eventually(() -> count(e2e, name, Material.COOKED_BEEF) == 48 && count(e2e, name, Material.APPLE) == 8, "daily claimed");
        List<String> check = e2e.consoleOutput("kits check " + name);
        e2e.expect(check.contains("Kits of " + name) && check.contains("Starter (starter): ready")
            && check.stream().anyMatch(line -> (line.startsWith("Daily (daily): in 23h 59m") || line.equals("Daily (daily): in 1d"))), "check: " + check);
        List<String> unknown = e2e.consoleOutput("kits give " + name + " nosuchkit");
        e2e.expect(unknown.stream().anyMatch(line -> line.contains("There is no kit called nosuchkit.")), "unknown kit: " + unknown);
        ledgerHealthy(e2e);
    }

    // ------------------------------------------------------------------ perks

    static void perks(E2E e2e) throws Exception {
        String name = e2e.name("KtPerks");
        Bot bot = e2e.bot(name);
        clear(e2e, name);

        e2e.step("without the permission a perk command doesn't exist for the player");
        command(e2e, bot, "trash");
        e2e.sleep(1_000);
        e2e.expect(bot.screen() == null, "no trash without the perk");

        grant(e2e, name, "siftcore.perk.ec", "siftcore.perk.trash", "siftcore.perk.hat", "siftcore.perk.craft");

        e2e.step("/ec opens the player's own ender chest");
        put(e2e, name, 0, ItemStack.of(Material.DIAMOND, 3));
        command(e2e, bot, "ec");
        awaitScreen(e2e, bot, "minecraft:generic_9x3", null);
        bot.clickSlot(54);
        e2e.sleep(200);
        bot.clickSlot(4);
        e2e.eventually(() -> e2e.onPlayer(name, () -> {
            ItemStack stored = e2e.player(name).getEnderChest().getItem(4);
            return stored != null && stored.getType() == Material.DIAMOND && stored.getAmount() == 3;
        }), "the diamonds are in the ender chest");
        bot.closeScreen();
        e2e.sleep(300);
        command(e2e, bot, "enderchest");
        awaitScreen(e2e, bot, "minecraft:generic_9x3", null);
        e2e.eventually(() -> bot.screenItems().get(4) != null, "the ender chest shows them again");
        bot.closeScreen();
        e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "they left the inventory");

        e2e.step("/trash deletes what is put in it when it closes");
        put(e2e, name, 0, ItemStack.of(Material.DIRT, 64));
        put(e2e, name, 1, ItemStack.of(Material.COBBLESTONE, 10));
        command(e2e, bot, "trash");
        awaitScreen(e2e, bot, "minecraft:generic_9x4", "Trash: deleted when you close it");
        bot.shiftClick(63);
        e2e.sleep(200);
        bot.shiftClick(64);
        e2e.eventually(() -> count(e2e, name, Material.DIRT) == 0 && count(e2e, name, Material.COBBLESTONE) == 0, "moved into the trash");
        bot.clearLogs();
        bot.closeScreen();
        waitFor(e2e, () -> bot.actionBarContains("Deleted 74 items."), () -> "deleted: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.expect(count(e2e, name, Material.DIRT) == 0, "the dirt is gone for good");
        e2e.expect(audits(e2e, "perks.trash", e2e.uuid(name)) == 1, "the deletion is audited");

        e2e.step("/hat wears one of the held stack");
        put(e2e, name, 0, ItemStack.of(Material.CARVED_PUMPKIN, 3));
        bot.selectHotbar(0);
        bot.clearLogs();
        command(e2e, bot, "hat");
        waitFor(e2e, () -> bot.actionBarContains("You're wearing Carved Pumpkin."), () -> "worn: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.eventually(() -> e2e.onPlayer(name, () -> {
            var inventory = e2e.player(name).getInventory();
            return inventory.getHelmet() != null && inventory.getHelmet().getType() == Material.CARVED_PUMPKIN
                && inventory.getHelmet().getAmount() == 1 && inventory.getItemInMainHand().getAmount() == 2;
        }), "one pumpkin on the head, two in the hand");

        e2e.step("/hat with a single item swaps it with the current hat");
        put(e2e, name, 0, ItemStack.of(Material.WHITE_BANNER));
        command(e2e, bot, "hat");
        e2e.eventually(() -> e2e.onPlayer(name, () -> {
            var inventory = e2e.player(name).getInventory();
            return inventory.getHelmet().getType() == Material.WHITE_BANNER && inventory.getItemInMainHand().getType() == Material.CARVED_PUMPKIN;
        }), "the banner is worn and the pumpkin is in the hand");

        e2e.step("/hat refuses an empty hand and a cursed helmet");
        put(e2e, name, 0, null);
        bot.clearLogs();
        command(e2e, bot, "hat");
        waitFor(e2e, () -> bot.actionBarContains("Hold the item you want to wear."), () -> "empty hand: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());
        e2e.onPlayer(name, () -> {
            ItemStack cursed = ItemStack.of(Material.LEATHER_HELMET);
            cursed.addUnsafeEnchantment(Enchantment.BINDING_CURSE, 1);
            e2e.player(name).getInventory().setHelmet(cursed);
            e2e.player(name).getInventory().setItem(0, ItemStack.of(Material.DIRT));
            return null;
        });
        bot.clearLogs();
        command(e2e, bot, "hat");
        waitFor(e2e, () -> bot.actionBarContains("Your helmet can't be taken off."), () -> "cursed: " + bot.actionBar() + " / " + bot.actionBar() + " / " + bot.chat());

        e2e.step("the perks dialog opens a perk");
        clear(e2e, name);
        command(e2e, bot, "kits");
        e2e.dialog(bot, "Kits");
        e2e.click(bot, "Perks");
        Bot.SeenDialog perks = e2e.dialog(bot, "Perks");
        e2e.expect(perks.button("Ender chest") != null && perks.button("Crafting table") != null && perks.button("Trash") != null
            && perks.button("Hat") != null && perks.button("Anvil") == null, "only the granted perks: " + perks.buttons());
        bot.clickButton("Crafting table", Map.of());
        awaitScreen(e2e, bot, "minecraft:crafting", null);
        bot.closeScreen();
    }

    // ------------------------------------------------------------------ workstations

    static void workstations(E2E e2e) throws Exception {
        String name = e2e.name("KtWork");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        grant(e2e, name, "siftcore.perk.craft", "siftcore.perk.anvil", "siftcore.perk.stonecutter", "siftcore.perk.grindstone",
            "siftcore.perk.smithing", "siftcore.perk.loom", "siftcore.perk.cartography");

        e2e.step("/craft crafts like a crafting table");
        put(e2e, name, 0, ItemStack.of(Material.OAK_LOG, 2));
        command(e2e, bot, "craft");
        awaitScreen(e2e, bot, "minecraft:crafting", null);
        bot.rightClick(37);
        e2e.sleep(200);
        bot.clickSlot(1);
        e2e.eventually(() -> bot.screenItems().get(0) != null && "minecraft:oak_planks".equals(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.screenItems().get(0).getItem()).toString()), "planks to take");
        bot.shiftClick(0);
        e2e.eventually(() -> count(e2e, name, Material.OAK_PLANKS) == 4, "four planks crafted");

        e2e.step("what is left in the grid comes back when it closes");
        bot.clickSlot(37);
        e2e.sleep(200);
        bot.clickSlot(5);
        e2e.eventually(() -> count(e2e, name, Material.OAK_LOG) == 0, "the last log is in the grid");
        bot.closeScreen();
        e2e.eventually(() -> count(e2e, name, Material.OAK_LOG) == 1, "the log came back");

        e2e.step("/anvil renames for a level, like an anvil");
        clear(e2e, name);
        put(e2e, name, 0, ItemStack.of(Material.IRON_SWORD));
        e2e.onPlayer(name, () -> {
            e2e.player(name).setLevel(5);
            return null;
        });
        command(e2e, bot, "anvil");
        awaitScreen(e2e, bot, "minecraft:anvil", null);
        bot.clickSlot(30);
        e2e.sleep(200);
        bot.clickSlot(0);
        e2e.sleep(300);
        bot.renameInAnvil("Blade");
        e2e.eventually(() -> bot.screenItems().get(2) != null, "a renamed sword to take");
        bot.shiftClick(2);
        e2e.eventually(() -> {
            ItemStack sword = find(e2e, name, Material.IRON_SWORD);
            return sword != null && sword.getData(DataComponentTypes.CUSTOM_NAME) != null
                && "Blade".equals(PlainTextComponentSerializer.plainText().serialize(sword.getData(DataComponentTypes.CUSTOM_NAME)));
        }, "the sword is called Blade");
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getLevel()) == 4, "it cost one level");
        bot.closeScreen();

        e2e.step("the other workstations open their vanilla screens");
        String[][] stations = {{"stonecutter", "minecraft:stonecutter"}, {"grindstone", "minecraft:grindstone"},
            {"smithing", "minecraft:smithing"}, {"loom", "minecraft:loom"}, {"cartography", "minecraft:cartography_table"},
            {"workbench", "minecraft:crafting"}};
        for (String[] station : stations) {
            command(e2e, bot, station[0]);
            awaitScreen(e2e, bot, station[1], null);
            e2e.sleep(1_000);
            e2e.expect(bot.screen() != null && bot.screen().type().equals(station[1]), station[0] + " stays open");
            bot.closeScreen();
        }
    }

    // ------------------------------------------------------------------ others' ender chests

    static void ecOthers(E2E e2e) throws Exception {
        String staffName = e2e.name("KtStaff");
        String ownerName = e2e.name("KtOwner");
        Bot staff = e2e.bot(staffName);
        e2e.bot(ownerName);
        clear(e2e, staffName);
        e2e.onPlayer(ownerName, () -> {
            e2e.player(ownerName).getEnderChest().setItem(0, ItemStack.of(Material.EMERALD, 7));
            return null;
        });

        e2e.step("a player without the node can't look into others' ender chests");
        grant(e2e, staffName, "siftcore.perk.ec");
        command(e2e, staff, "ec " + ownerName);
        e2e.sleep(1_000);
        e2e.expect(staff.screen() == null, "nothing opened");

        e2e.step("staff with siftcore.perk.ec.others see a read-only copy");
        grant(e2e, staffName, "siftcore.perk.ec.others");
        command(e2e, staff, "ec " + ownerName);
        awaitScreen(e2e, staff, "minecraft:generic_9x3", ownerName + "'s ender chest");
        e2e.eventually(() -> staff.screenItems().get(0) != null, "the emeralds are shown");
        staff.clickSlot(0);
        e2e.sleep(300);
        staff.shiftClick(0);
        e2e.sleep(500);
        staff.closeScreen();
        e2e.sleep(300);
        e2e.expect(count(e2e, staffName, Material.EMERALD) == 0, "staff took nothing");
        e2e.expect(e2e.onPlayer(ownerName, () -> {
            ItemStack stored = e2e.player(ownerName).getEnderChest().getItem(0);
            return stored != null && stored.getAmount() == 7;
        }), "the owner's emeralds are untouched");
        e2e.expect(audits(e2e, "perks.ec.others", e2e.uuid(ownerName)) == 1, "the look is audited");
    }

    // ------------------------------------------------------------------ config reloads

    private static final String TEST_KITS = """
          e2equick:
            name: "Quick"
            everyone: true
            cooldown: 6s
            items:
              diamond: {amount: 2}
          e2ekeys:
            name: "Keyring"
            everyone: true
            cooldown: once
            keys:
              basic: 2
        """;

    private static Path configFile(E2E e2e) {
        return e2e.services().plugin().getDataFolder().toPath().resolve("features/kits.yml");
    }

    static void config(E2E e2e) throws Exception {
        Path file = configFile(e2e);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        e2e.expect(original.contains("\nkits:\n") && original.contains("locked-kits: hide"), "kits.yml is the shipped one");
        try {
            e2e.step("a reload adds kits and shows locked ones");
            String changed = original.replace("\nkits:\n", "\nkits:\n" + TEST_KITS).replace("locked-kits: hide", "locked-kits: show");
            Files.writeString(file, changed, StandardCharsets.UTF_8);
            List<String> output = e2e.consoleOutput("sift reload");
            e2e.expect(output.stream().anyMatch(line -> line.contains("Reloaded")), "reloaded: " + output);
            e2e.expect(e2e.feature(KitsFeature.class).settings().kit("e2equick") != null, "the new kit is live");
            e2e.eventually(() -> {
                var permission = org.bukkit.Bukkit.getPluginManager().getPermission("siftcore.kit.e2equick");
                return permission != null && permission.getDefault() == PermissionDefault.TRUE;
            }, "its permission is registered for everyone");

            String name = e2e.name("KtConfig");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            command(e2e, bot, "kits");
            Bot.SeenDialog list = e2e.dialog(bot, "Kits");
            e2e.expect(list.bodyText().contains("Quick: ready") && list.bodyText().contains("Prospector: locked"),
                "the new kit and locked rank kits: " + list.body());
            e2e.click(bot, "Tycoon");
            Bot.SeenDialog tycoon = e2e.dialog(bot, "Tycoon kit");
            e2e.expect(tycoon.bodyText().contains("You haven't unlocked this kit.") && tycoon.button("Claim") == null
                && tycoon.bodyText().contains("Name Tag"), "locked kits show their contents only: " + tycoon.body());
            bot.closeScreen();

            e2e.step("a short cooldown reminds the player when it ends");
            bot.clearLogs();
            command(e2e, bot, "kit e2equick");
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 2, "two diamonds");
            waitFor(e2e, () -> bot.chatContains("Your Quick kit is ready."), 15_000, () -> "ready reminder: " + bot.chat() + " / " + bot.actionBar() + " / " + bot.chat());
            command(e2e, bot, "kit e2equick");
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 4, "claimed again after its cooldown");

            e2e.step("a kit of only crate keys gives them");
            int basic = keys(e2e, uuid, "basic");
            bot.clearLogs();
            command(e2e, bot, "kit e2ekeys");
            e2e.eventually(() -> keys(e2e, uuid, "basic") == basic + 2, "two basic keys");
            waitFor(e2e, () -> bot.chatContains("The Keyring kit gave you 2 Basic keys."), () -> "told: " + bot.chat() + " / " + bot.actionBar() + " / " + bot.chat());
            e2e.expect(claimRows(e2e, uuid, "e2ekeys") == 1, "the claim is stored");

            e2e.step("a broken change is refused and the old kits stay");
            Files.writeString(file, changed.replace("cooldown: 6s", "cooldown: soon"), StandardCharsets.UTF_8);
            List<String> refused = e2e.consoleOutput("sift reload");
            e2e.expect(refused.stream().anyMatch(line -> line.contains("kits.e2equick.cooldown")), "the problem is named: " + refused);
            List<String> kits = e2e.consoleOutput("kits");
            e2e.expect(kits.stream().anyMatch(line -> line.startsWith("Quick (e2equick): Claim it every 6s")), "nothing changed: " + kits);
        } finally {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
        e2e.expect(e2e.feature(KitsFeature.class).settings().kit("e2equick") == null
            && !e2e.feature(KitsFeature.class).settings().showLocked(), "the shipped kits are back");
    }

    // ------------------------------------------------------------------ restarts

    static void persistSetup(E2E e2e) {
        String name = e2e.name("KtKeeper");
        Bot bot = e2e.bot(name);
        clear(e2e, name);
        command(e2e, bot, "kit starter");
        e2e.eventually(() -> count(e2e, name, Material.STONE_PICKAXE) == 1, "starter claimed");
        command(e2e, bot, "kit daily");
        e2e.eventually(() -> count(e2e, name, Material.COOKED_BEEF) == 16, "daily claimed");
        e2e.log("claimed the starter and daily kits as " + name);
    }

    /** Finds what {@link #persistSetup} left (possibly before a restart) and checks it survived. */
    static void persistCheck(E2E e2e) {
        String uuidText = query(e2e, "SELECT uuid FROM kit_claims WHERE kit = 'daily' AND uuid IN (SELECT uuid FROM kit_claims WHERE kit = 'starter') "
            + "ORDER BY last_claim DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null);
        e2e.expect(uuidText != null, "a player with starter and daily claims exists (run kits-persist-setup first)");
        UUID uuid = UUID.fromString(uuidText);
        String name = e2e.services().directory().name(uuid);
        e2e.log("checking " + name);
        List<String> check = e2e.consoleOutput("kits check " + name);
        e2e.expect(check.contains("Starter (starter): claimed"), "the starter kit is still claimed: " + check);
        e2e.expect(check.stream().anyMatch(line -> line.startsWith("Daily (daily): in ")), "the daily cooldown is still running: " + check);
    }
}
