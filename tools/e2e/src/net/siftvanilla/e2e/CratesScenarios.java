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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.inventory.ContainerInput;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.CrateOpenEvent;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.feature.crates.CratePlayerSettings;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Crates end to end: the /crates dialog (open, open another, refusals, the hub entry), every reward kind (items,
 * money with the win announcement, keys, commands), the claim box when the inventory is full, double submits and
 * command spam, the preview menu (chances, sorting, opening from it), staff commands from the console (give with a
 * reference, take, check, log, info) and the CrateKeys contract, the keyall (vanished staff left out, the countdown
 * and the scheduled run), crate blocks (right-click, left-click, quick open, protection) and keys and the schedule
 * surviving a restart.
 * <p>
 * Scenarios that need a predictable reward add small one-reward crates to features/crates.yml, reload, and put the
 * file back afterwards.
 */
final class CratesScenarios {

    private CratesScenarios() {
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
        list.add(of("crates-open", CratesScenarios::open));
        list.add(of("crates-rewards", CratesScenarios::rewards));
        list.add(of("crates-claim-box", CratesScenarios::claimBox));
        list.add(of("crates-double-submit", CratesScenarios::doubleSubmit));
        list.add(of("crates-preview", CratesScenarios::preview));
        list.add(of("crates-admin", CratesScenarios::admin));
        list.add(of("crates-keyall", CratesScenarios::keyall));
        list.add(of("crates-block", CratesScenarios::block));
        list.add(of("crates-combat", CratesScenarios::combat));
        list.add(of("crates-bulk", CratesScenarios::bulk));
        list.add(of("crates-command-stored", CratesScenarios::commandStored));
        list.add(of("crates-spawn-block", CratesScenarios::spawnBlock));
        list.add(of("crates-persist-setup", CratesScenarios::persistSetup));
        list.add(of("crates-persist-check", CratesScenarios::persistCheck));
        list.add(of("crates-settings", CratesScenarios::settings));
        return list;
    }

    // ------------------------------------------------------------------ test crates

    /** One-reward crates appended under crates: (the last section of the shipped file). */
    private static final String TEST_CRATES = """

          e2etest:
            name: "Test"
            icon: chest
            rewards:
              diamonds:
                item: diamond
                amount: 5
                weight: 1
                rarity: rare
                display: "5 diamonds"
          e2ecash:
            name: "Cash"
            icon: gold_block
            rewards:
              cash:
                money: 1234
                weight: 1
                rarity: epic
          e2ekeys:
            name: "Keyring"
            icon: tripwire_hook
            rewards:
              keys:
                keys: e2etest
                amount: 2
                weight: 1
                rarity: common
          e2ecmd:
            name: "Command"
            rewards:
              say:
                commands: ["say %player% won the command crate"]
                display: "a shout"
                icon: paper
                weight: 1
                rarity: common
        """;

    private static Path config(E2E e2e) {
        return e2e.services().plugin().getDataFolder().toPath().resolve("features/crates.yml");
    }

    /** Adds the test crates and reloads; returns the original file to put back. */
    private static String install(E2E e2e) throws Exception {
        Path file = config(e2e);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        e2e.expect(original.stripTrailing().endsWith("display: \"a blaze spawner\""), "crates.yml ends with the shipped crates section");
        Files.writeString(file, original.stripTrailing() + "\n" + TEST_CRATES, StandardCharsets.UTF_8);
        List<String> output = e2e.consoleOutput("sift reload");
        e2e.expect(output.stream().anyMatch(line -> line.contains("Reloaded")), "the reload with the test crates worked: " + output);
        e2e.expect(crates(e2e).settings().crate("e2etest") != null, "the test crate is live");
        return original;
    }

    private static void restore(E2E e2e, String original) throws Exception {
        Files.writeString(config(e2e), original, StandardCharsets.UTF_8);
        e2e.console("sift reload");
    }

    // ------------------------------------------------------------------ helpers

    private static CratesFeature crates(E2E e2e) {
        return e2e.feature(CratesFeature.class);
    }

    private static int keys(E2E e2e, String name, String crate) {
        return crates(e2e).keys().keys(e2e.uuid(name), crate);
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

    private static long logRows(E2E e2e, UUID player) {
        return number(e2e, "SELECT COUNT(*) FROM crate_log WHERE uuid = ?", player.toString());
    }

    private static long audits(E2E e2e, String action, UUID target) {
        return number(e2e, "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target = ?", action, target.toString());
    }

    private static void ledgerHealthy(E2E e2e) throws Exception {
        Ledger.AuditReport report = e2e.services().ledger().audit().get(20, TimeUnit.SECONDS);
        e2e.expect(report.healthy(), "the ledger invariants hold: " + report.problems());
    }

    /** The keys of these players in memory equal the stored rows. */
    private static void keysMatchStorage(E2E e2e, UUID... players) {
        for (UUID uuid : players) {
            Map<String, Integer> memory = new HashMap<>();
            for (String crate : crates(e2e).keys().crates()) {
                int count = crates(e2e).keys().keys(uuid, crate);
                if (count > 0) {
                    memory.put(crate, count);
                }
            }
            Map<String, Integer> stored = query(e2e, "SELECT crate, amount FROM crate_keys WHERE uuid = ?", rs -> {
                Map<String, Integer> rows = new HashMap<>();
                while (rs.next()) {
                    rows.put(rs.getString(1), rs.getInt(2));
                }
                return rows;
            }, uuid.toString());
            e2e.expect(memory.equals(stored), "keys in memory " + memory + " equal the stored " + stored);
        }
    }

    /**
     * Runs a console command with a capturing sender and waits until one of its answers contains {@code expected}
     * (staff commands answer once their change is stored, a moment after the command returns).
     */
    private static List<String> answer(E2E e2e, String command, String expected) {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(Bukkit.getPluginManager().getPlugin("SiftE2E"), () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message -> lines.add(plain(message))), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("command '" + command + "' failed: " + e);
        }
        e2e.eventually(() -> lines.stream().anyMatch(line -> line.contains(expected)), 5_000,
            "'" + command + "' answers '" + expected + "': " + lines);
        e2e.sleep(200);
        return List.copyOf(lines);
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

    private static String name(net.minecraft.world.item.ItemStack stack) {
        return stack == null ? "" : stack.getHoverName().getString();
    }

    private static int slotWith(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> slotsWith(bot, text) >= 0, bot.name + " sees an entry with '" + text + "'");
        return slotsWith(bot, text);
    }

    private static int slotsWith(Bot bot, String text) {
        for (var entry : new TreeMap<>(bot.screenItems()).entrySet()) {
            if (entry.getKey() < 45 && lore(entry.getValue()).stream().anyMatch(line -> line.contains(text))) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private static void awaitScreen(E2E e2e, Bot bot, Bot.Screen before, String title) {
        e2e.eventually(() -> bot.screen() != null && bot.screen() != before && bot.screen().title().equals(title)
            && !bot.screenItems().isEmpty(), bot.name + " sees the screen '" + title + "' (now "
            + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
        e2e.sleep(300);
    }

    private static Bot.SeenDialog awaitBody(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains(text),
            bot.name + " sees a dialog saying '" + text + "' (last: " + (bot.dialog() == null ? "none" : bot.dialog().bodyText()) + ")");
        return bot.dialog();
    }

    // ------------------------------------------------------------------ scenarios

    static void open(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrOpen");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            e2e.step("staff give two keys; the player is told");
            e2e.console("keys give " + name + " e2etest 2");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 2, "two Test keys");
            e2e.eventually(() -> bot.chatContains("You got 2 Test keys."), "the player is told: " + bot.chat());

            e2e.step("/crates lists every crate with the player's keys");
            bot.clearLogs();
            command(e2e, bot, "crates");
            Bot.SeenDialog list = e2e.dialog(bot, "Crates");
            String body = list.bodyText();
            e2e.expect(body.contains("Test crate") && body.contains("You have 2 keys"), "the test crate with 2 keys: " + body);
            e2e.expect(body.contains("Basic crate") && body.contains("You have no keys"), "the basic crate without keys: " + body);
            e2e.expect(body.contains("Next keyall in") && body.contains("Everyone online gets 1 Basic key"), "the keyall line: " + body);
            e2e.expect(list.button("Open Test") != null && list.button("Preview Test") != null && list.button("Open Legendary") != null,
                "Open and Preview for every crate: " + list.buttons());
            e2e.expect("wait_for_response".equals(list.after()), "Open keeps the client on its waiting screen until the result: "
                + list.after());

            e2e.step("Open spends one key and shows the reward once it is stored and handed over");
            e2e.click(bot, "Open Test");
            Bot.SeenDialog result = awaitBody(e2e, bot, "You won 5 diamonds");
            e2e.expect(result.title().equals("Test crate"), "the result is titled after the crate: " + result.title());
            e2e.expect("wait_for_response".equals(result.after()), "Open another waits for its result too: " + result.after());
            e2e.expect(result.bodyText().contains("Rare") && result.bodyText().contains("You have 1 key left."), "rarity and keys left: "
                + result.bodyText());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 5, "5 diamonds in the inventory");
            e2e.expect(keys(e2e, name, "e2etest") == 1, "one key left");
            e2e.eventually(() -> bot.chatContains("You won 5 diamonds from the Test crate."), "the receipt in chat: " + bot.chat());
            e2e.expect(logRows(e2e, uuid) == 1, "one crate log row");
            e2e.expect(query(e2e, "SELECT reward FROM crate_log WHERE uuid = ?", rs -> rs.next() ? rs.getString(1) : null,
                uuid.toString()).equals("diamonds"), "the log names the reward");
            e2e.eventually(() -> audits(e2e, "crates.reward", uuid) == 1, "a rare win is in the audit log");
            e2e.expect(e2e.services().deliveries().count(uuid) == 0, "nothing was left in the claim box");

            e2e.step("Open another uses the last key; then there is no Open another");
            e2e.click(bot, "Open another");
            Bot.SeenDialog last = awaitBody(e2e, bot, "That was your last key.");
            e2e.expect(last.button("Open another") == null, "no Open another without keys: " + last.buttons());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 10, "10 diamonds");
            e2e.expect(keys(e2e, name, "e2etest") == 0 && logRows(e2e, uuid) == 2, "no keys left, two log rows");

            e2e.step("Back returns to the list; opening without a key says so there and changes nothing");
            e2e.click(bot, "Back");
            e2e.dialog(bot, "Crates");
            e2e.click(bot, "Open Test");
            awaitBody(e2e, bot, "You have no Test keys.");
            e2e.expect(count(e2e, name, Material.DIAMOND) == 10 && logRows(e2e, uuid) == 2, "nothing changed");

            e2e.step("the main menu has a Crates button that opens the same list, with Back to the menu");
            bot.clearLogs();
            command(e2e, bot, "menu");
            e2e.dialog(bot, "SiftVanilla");
            e2e.click(bot, "Crates");
            e2e.dialog(bot, "Crates");
            e2e.expect(bot.dialog().button("Back") != null, "a Back button to the menu: " + bot.dialog().buttons());
            e2e.click(bot, "Back");
            e2e.dialog(bot, "SiftVanilla");
            ledgerHealthy(e2e);
        } finally {
            restore(e2e, original);
        }
    }

    static void rewards(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrWinner");
            String watcherName = e2e.name("CrWatch");
            Bot bot = e2e.bot(name);
            Bot watcher = e2e.bot(watcherName);
            UUID uuid = e2e.uuid(name);
            e2e.console("eco set " + name + " 0");
            e2e.console("keys give " + name + " e2ecash 2");
            e2e.console("keys give " + name + " e2ekeys 1");
            e2e.console("keys give " + name + " e2ecmd 1");

            e2e.step("money is paid as a crate_reward source and an epic win is announced to everyone else");
            bot.clearLogs();
            watcher.clearLogs();
            command(e2e, bot, "crates open e2ecash");
            e2e.eventually(() -> e2e.money(name) == 1_234, "paid $1,234 (has " + e2e.money(name) + ")");
            e2e.eventually(() -> bot.chatContains("You won $1,234 from the Cash crate."), "the receipt: " + bot.chat());
            e2e.eventually(() -> watcher.chatContains(name + " won $1,234 from the Cash crate."), "the announcement: " + watcher.chat());
            e2e.expect(!bot.chatContains(name + " won $1,234"), "the winner is not announced to themselves");
            var rows = e2e.services().ledger().history(uuid, 5, 0).get(10, TimeUnit.SECONDS);
            e2e.expect(!rows.isEmpty() && rows.getFirst().kind().equals("crate_reward") && rows.getFirst().delta() == 1_234
                && rows.getFirst().currency() == Currency.MONEY, "a crate_reward ledger row: " + rows);
            e2e.eventually(() -> audits(e2e, "crates.reward", uuid) == 1, "the epic win is audited");

            e2e.step("a keys reward adds keys of another crate");
            e2e.sleep(600);
            command(e2e, bot, "crates open e2ekeys");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 2, "two Test keys");
            e2e.expect(keys(e2e, name, "e2ekeys") == 0, "the Keyring key was spent");
            e2e.eventually(() -> bot.chatContains("You won 2 Test keys from the Keyring crate."), "the receipt: " + bot.chat());

            e2e.step("a command reward runs its console command for the winner");
            e2e.sleep(600);
            command(e2e, bot, "crates open e2ecmd");
            e2e.eventually(() -> bot.chatContains(name + " won the command crate"), "the command ran: " + bot.chat());
            e2e.eventually(() -> bot.chatContains("You won a shout from the Command crate."), "the receipt: " + bot.chat());
            e2e.expect(keys(e2e, name, "e2ecmd") == 0, "the key was spent");
            e2e.expect(logRows(e2e, uuid) == 3, "three openings logged");

            e2e.step("a player who turned crate wins off in the settings is not told");
            e2e.services().settings().set(e2e.uuid(watcherName), CratesFeature.WIN_ANNOUNCEMENTS, CratePlayerSettings.WinFilter.OFF,
                Change.api("e2e"));
            watcher.clearLogs();
            bot.clearLogs();
            e2e.sleep(600);
            command(e2e, bot, "crates open e2ecash");
            e2e.eventually(() -> e2e.money(name) == 2_468, "paid again (has " + e2e.money(name) + ")");
            e2e.eventually(() -> bot.chatContains("You won $1,234 from the Cash crate."), "the receipt: " + bot.chat());
            e2e.sleep(1_000);
            e2e.expect(!watcher.chatContains("won $1,234"), "no announcement with the setting off: " + watcher.chat());
            e2e.services().settings().set(e2e.uuid(watcherName), CratesFeature.WIN_ANNOUNCEMENTS, CratePlayerSettings.WinFilter.ALL,
                Change.api("e2e"));
            ledgerHealthy(e2e);
        } finally {
            restore(e2e, original);
        }
    }

    /**
     * A command reward is stored with its opening (key, log row) and runs from the console only once that is stored,
     * then its stored row is deleted. A server stop in between leaves the row, which runs at the next start (tested at
     * boot), instead of losing the reward.
     */
    static void commandStored(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrCmdSlow");
            Bot bot = e2e.bot(name);
            e2e.console("keys give " + name + " e2ecmd 1");
            e2e.eventually(() -> keys(e2e, name, "e2ecmd") == 1, "a Command key");
            e2e.step("open while storage is slow: the key is spent at once, the command waits for the commit");
            java.util.concurrent.CompletableFuture<Object> stall = e2e.stallStorage(4_000);
            bot.clearLogs();
            command(e2e, bot, "crates open e2ecmd");
            e2e.eventually(() -> keys(e2e, name, "e2ecmd") == 0, "the key is spent");
            e2e.sleep(300);
            e2e.expect(!stall.isDone(), "storage is still slow");
            e2e.expect(!bot.chatContains(name + " won the command crate"), "the command did not run before the opening was stored");
            stall.get(10, TimeUnit.SECONDS);
            e2e.eventually(() -> bot.chatContains(name + " won the command crate"), "the command ran once it was stored: " + bot.chat());
            e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM crate_commands WHERE uuid = ?", e2e.uuid(name).toString()) == 0,
                "its stored row is gone after it ran");
            e2e.sleep(1_000);
            long ran = bot.chat().stream().filter(line -> line.contains(name + " won the command crate")).count();
            e2e.expect(ran == 1, "it ran exactly once: " + ran);
            ledgerHealthy(e2e);
        } finally {
            restore(e2e, original);
        }
    }

    static void claimBox(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrFull");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            e2e.onPlayer(name, () -> {
                PlayerInventory inventory = e2e.player(name).getInventory();
                inventory.clear();
                for (int slot = 0; slot < 36; slot++) {
                    inventory.setItem(slot, ItemStack.of(Material.COBBLESTONE, 64));
                }
                return null;
            });
            e2e.console("keys give " + name + " e2etest 1");

            e2e.step("a reward that does not fit waits in the claim box");
            bot.clearLogs();
            command(e2e, bot, "crates open e2etest");
            e2e.eventually(() -> bot.chatContains("You won 5 diamonds from the Test crate. It didn't fit, so it's waiting in your claim box (/claims)."),
                "the receipt names the claim box: " + bot.chat());
            e2e.expect(count(e2e, name, Material.DIAMOND) == 0, "no diamonds in the full inventory");
            e2e.expect(e2e.services().deliveries().count(uuid) == 1, "one stack in the claim box");
            e2e.expect(e2e.services().deliveries().of(uuid).getFirst().source().equals("crate"), "from the crate source");
            e2e.expect(keys(e2e, name, "e2etest") == 0, "the key was spent");

            e2e.step("the shared claim box hands it out once there is room");
            clear(e2e, name);
            Bot.Screen before = bot.screen();
            command(e2e, bot, "ah claims");
            awaitScreen(e2e, bot, before, "Claim box");
            int slot = slotWith(e2e, bot, "From crate");
            bot.clickSlot(slot);
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 5, "the diamonds were claimed");
            e2e.expect(e2e.services().deliveries().count(uuid) == 0, "the claim box is empty");
            ledgerHealthy(e2e);
        } finally {
            restore(e2e, original);
        }
    }

    static void doubleSubmit(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrDouble");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            e2e.console("keys give " + name + " e2etest 1");

            e2e.step("two clicks on Open in the same dialog open one crate");
            command(e2e, bot, "crates");
            e2e.dialog(bot, "Crates");
            e2e.expect(bot.clickButton("Open Test", Map.of()), "first click");
            e2e.expect(bot.clickButton("Open Test", Map.of()), "second click with the same dialog");
            e2e.eventually(() -> logRows(e2e, uuid) == 1, "the first click opened the crate");
            e2e.sleep(1_500);
            e2e.expect(count(e2e, name, Material.DIAMOND) == 5, "exactly 5 diamonds: " + count(e2e, name, Material.DIAMOND));
            e2e.expect(logRows(e2e, uuid) == 1, "one opening logged");
            e2e.expect(keys(e2e, name, "e2etest") == 0, "the key was spent once");

            e2e.step("a replayed Open click from an old dialog does nothing");
            Bot.SeenDialog old = bot.dialogs().stream().filter(d -> d.title().equals("Crates")).findFirst().orElseThrow();
            bot.rawClick(old.button("Open Test").actionId(), old.button("Open Test").additions());
            e2e.sleep(1_000);
            e2e.expect(logRows(e2e, uuid) == 1, "still one opening");

            e2e.step("two /crates open commands in a row open one crate (cooldown)");
            e2e.console("keys give " + name + " e2etest 3");
            e2e.sleep(1_200);
            bot.clearLogs();
            bot.command("crates open e2etest");
            bot.command("crates open e2etest");
            e2e.eventually(() -> logRows(e2e, uuid) == 2, "the first opened");
            e2e.eventually(() -> bot.actionBarContains("Wait") || bot.actionBarContains("still opening"), "the second was refused: "
                + bot.actionBar());
            e2e.sleep(1_000);
            e2e.expect(logRows(e2e, uuid) == 2 && keys(e2e, name, "e2etest") == 2, "one key spent, two left");
            e2e.expect(count(e2e, name, Material.DIAMOND) == 10, "10 diamonds");
            keysMatchStorage(e2e, uuid);
        } finally {
            restore(e2e, original);
        }
    }

    static void preview(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrLook");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);

            e2e.step("the preview lists every reward with its chance, rarity and sell value");
            Bot.Screen before = bot.screen();
            command(e2e, bot, "crates preview basic");
            awaitScreen(e2e, bot, before, "Basic crate rewards");
            int entries = (int) bot.screenItems().keySet().stream().filter(slot -> slot < 45).count();
            e2e.expect(entries == 12, "12 rewards: " + entries);
            int iron = slotWith(e2e, bot, "Chance 18%");
            e2e.expect(lore(bot.screenItems().get(iron)).contains("Rarity Common"), "rarity line: " + lore(bot.screenItems().get(iron)));
            e2e.expect(lore(bot.screenItems().get(iron)).contains("Sells for $400"), "16 iron ingots sell for $400: "
                + lore(bot.screenItems().get(iron)));
            int key = slotWith(e2e, bot, "Opens the Rare crate");
            e2e.expect(name(bot.screenItems().get(key)).equals("1 Rare key"), "the key reward is named: " + name(bot.screenItems().get(key)));
            e2e.expect(lore(bot.screenItems().get(key)).contains("Chance 2%"), "2%: " + lore(bot.screenItems().get(key)));
            e2e.expect(name(bot.screenItems().get(50)).equals("No keys"), "no keys yet: " + name(bot.screenItems().get(50)));

            e2e.step("sorting: most likely first, then rarest first");
            bot.clickSlot(47);
            e2e.eventually(() -> lore(bot.screenItems().get(47)).contains("• Most likely first"), "sorted by likelihood");
            e2e.eventually(() -> lore(bot.screenItems().get(0)).contains("Chance 18%"), "the likeliest first");
            // Clicks closer together than the configured GUI click interval are dropped on purpose.
            e2e.sleep(400);
            bot.clickSlot(47);
            e2e.eventually(() -> lore(bot.screenItems().get(0)).contains("Chance 2%"), "the rarest first: " + lore(bot.screenItems().get(0)));

            e2e.step("with a key, the preview opens one");
            e2e.console("keys give " + name + " basic 1");
            bot.closeScreen();
            before = bot.screen();
            command(e2e, bot, "crates preview basic");
            awaitScreen(e2e, bot, before, "Basic crate rewards");
            e2e.eventually(() -> name(bot.screenItems().get(50)).equals("Open one")
                && lore(bot.screenItems().get(50)).contains("You have 1 Basic key"), "the open button: " + lore(bot.screenItems().get(50)));
            bot.clearLogs();
            bot.clickSlot(50);
            e2e.eventually(() -> logRows(e2e, uuid) == 1, "one opening logged");
            e2e.eventually(() -> bot.chatContains("from the Basic crate."), "the receipt: " + bot.chat());
            e2e.expect(keys(e2e, name, "basic") == 0, "the key was spent");
            e2e.eventually(() -> name(bot.screenItems().get(50)).equals("No keys"), "the button shows no keys after the redraw");

            e2e.step("Preview from the crates dialog has a back button to it");
            bot.closeScreen();
            command(e2e, bot, "crates");
            e2e.dialog(bot, "Crates");
            before = bot.screen();
            bot.clickButton("Preview Test", Map.of());
            awaitScreen(e2e, bot, before, "Test crate rewards");
            e2e.expect(lore(bot.screenItems().get(0)).contains("Chance 100%"), "a single reward has 100%: " + lore(bot.screenItems().get(0)));
            bot.clearLogs();
            bot.clickSlot(46);
            e2e.dialog(bot, "Crates");
        } finally {
            restore(e2e, original);
        }
    }

    static void admin(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrStaffed");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            String ref = "store-" + name;

            e2e.step("give with a reference is applied once");
            answer(e2e, "crates give " + name + " e2etest 3 " + ref, "Gave " + name + " 3 Test keys. They have 3 now.");
            e2e.expect(keys(e2e, name, "e2etest") == 3, "three keys");
            answer(e2e, "keys give " + name + " e2etest 3 " + ref, "The reference " + ref + " was used before, so nothing was given.");
            e2e.expect(keys(e2e, name, "e2etest") == 3, "still three keys");

            e2e.step("take refuses more than they have and takes what they have");
            answer(e2e, "crates take " + name + " e2etest 5", name + " only has 3 Test keys.");
            answer(e2e, "crates take " + name + " e2etest 2", "Took 2 Test keys from " + name + ". They have 1 left.");
            List<String> check = answer(e2e, "crates check " + name, "Keys of " + name);
            e2e.expect(check.contains("Test 1"), "check lists the keys: " + check);
            e2e.eventually(() -> audits(e2e, "crates.give", uuid) == 1 && audits(e2e, "crates.take", uuid) == 1, "give and take are audited");

            e2e.step("the log lists openings; info shows chances and values");
            command(e2e, bot, "crates open e2etest");
            e2e.eventually(() -> logRows(e2e, uuid) == 1, "opened");
            answer(e2e, "crates log " + name, "Test 5 diamonds");
            List<String> info = answer(e2e, "crates info e2etest", "100% 5 diamonds Rare");
            e2e.expect(info.stream().anyMatch(line -> line.contains("items that sell for $2,000")), "info values the items: " + info);

            e2e.step("placeholders");
            Player player = e2e.player(name);
            var placeholders = e2e.services().placeholders();
            e2e.expect("0".equals(placeholders.resolve(player, "keys_e2etest")), "keys_e2etest is 0");
            e2e.console("keys give " + name + " basic 4");
            e2e.eventually(() -> "4".equals(placeholders.resolve(player, "keys_basic")) && "4".equals(placeholders.resolve(player, "keys_total")),
                "keys_basic and keys_total are 4");
            String countdown = placeholders.resolve(player, "keyall_countdown");
            e2e.expect(countdown != null && countdown.matches("\\d+h( \\d+m)?|\\d+m( \\d+s)?|\\d+s"), "keyall_countdown: " + countdown);
            e2e.expect("1 Basic key".equals(placeholders.resolve(player, "keyall_reward")), "keyall_reward");

            e2e.step("the CrateKeys contract other features use");
            TransactionResult first = crates(e2e).keys().give(uuid, "rare", 2, "e2e", "shardshop-" + name);
            first.committed().get(10, TimeUnit.SECONDS);
            TransactionResult second = crates(e2e).keys().give(uuid, "rare", 2, "e2e", "shardshop-" + name);
            e2e.expect(first.success() && !second.success() && "duplicate".equals(second.reason()), "a reference applies once");
            e2e.expect(crates(e2e).keys().keys(uuid, "rare") == 2, "two rare keys");
            e2e.expect(crates(e2e).keys().crates().containsAll(List.of("basic", "rare", "epic", "legendary")), "every crate id");
            e2e.expect("unknown_crate".equals(crates(e2e).keys().give(uuid, "mythic", 1, "e2e", null).reason()), "unknown crate refused");

            e2e.step("refusals from the console");
            answer(e2e, "crates give NobodyHere basic 1", "Nobody called NobodyHere");
            answer(e2e, "crates give " + name + " mythic 1", "There is no crate called mythic.");
            keysMatchStorage(e2e, uuid);

            e2e.step("a player with unopened keys is reminded when they join");
            bot.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
            Bot back = e2e.bot(name);
            e2e.eventually(() -> back.chatContains("You have 6 keys to open."), "the join reminder: " + back.chat());
        } finally {
            restore(e2e, original);
        }
    }

    static void keyall(E2E e2e) throws Exception {
        String name = e2e.name("CrAll");
        String hiddenName = e2e.name("CrHidden");
        Bot bot = e2e.bot(name);
        Bot hidden = e2e.bot(hiddenName);
        e2e.console("vanish " + hiddenName);
        e2e.eventually(() -> e2e.feature(net.siftvanilla.siftcore.feature.staff.StaffFeature.class).vanish().vanished(e2e.uuid(hiddenName)),
            "the second bot is vanished");
        int basicBefore = keys(e2e, name, "basic");

        e2e.step("a keyall started by staff gives everyone online keys, but not vanished staff");
        bot.clearLogs();
        answer(e2e, "keyall rare 2", "Gave 2 Rare keys to everyone online.");
        e2e.eventually(() -> keys(e2e, name, "rare") == 2, "two rare keys");
        e2e.expect(keys(e2e, hiddenName, "rare") == 0, "the vanished bot got none");
        e2e.eventually(() -> bot.chatContains("Keyall: everyone online got 2 Rare keys."), "the broadcast: " + bot.chat());
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM crate_grants WHERE ref LIKE ? AND uuid = ?", "keyall:m%", e2e.uuid(name).toString()) == 1,
            "the grant carries a keyall reference");
        e2e.eventually(() -> number(e2e, "SELECT COUNT(*) FROM audit_log WHERE action = 'crates.keyall' AND target = 'rare'") >= 1,
            "the keyall is audited");

        e2e.step("players can see when the next keyall is");
        bot.clearLogs();
        command(e2e, bot, "keyall");
        e2e.eventually(() -> bot.chatContains("The next keyall is in") && bot.chatContains("Everyone online gets 1 Basic key."),
            "the next keyall: " + bot.chat());

        e2e.step("staff move the scheduled keyall close: the countdown speaks in chat, then the action bar, then everyone gets keys");
        bot.clearLogs();
        answer(e2e, "keyall in 65s", "The next keyall is in 1m 5s.");
        e2e.eventually(() -> bot.chatContains("Keyall in 1m. Everyone online gets 1 Basic key."), 15_000, "the 1m announcement: " + bot.chat());
        e2e.eventually(() -> bot.actionBarContains("Keyall in 10s"), 65_000, "the action bar countdown: " + bot.actionBar());
        e2e.eventually(() -> keys(e2e, name, "basic") == basicBefore + 1, 20_000, "the scheduled keyall gave a basic key");
        e2e.expect(keys(e2e, hiddenName, "basic") == 0, "not to the vanished bot");
        e2e.eventually(() -> bot.chatContains("Keyall: everyone online got 1 Basic key."), "the broadcast: " + bot.chat());
        long next = number(e2e, "SELECT next_run FROM crate_schedule WHERE id = 'keyall'");
        long ahead = next - System.currentTimeMillis();
        e2e.expect(ahead > 3 * 3_600_000L + 3_500_000L && ahead <= 4 * 3_600_000L, "the next keyall is stored 4h ahead: " + ahead);
        e2e.expect(number(e2e, "SELECT COUNT(*) FROM crate_grants WHERE ref LIKE ? AND uuid = ?", "keyall:s%", e2e.uuid(name).toString()) == 1,
            "the scheduled grant carries its run reference");
        hidden.quit();
        keysMatchStorage(e2e, e2e.uuid(name));
    }

    /** A block position next to the player that this thread owns, with stone below and air above. */
    private static int[] spot(E2E e2e, String name) {
        return spot(e2e, name, new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}});
    }

    /** A chest at the first of these offsets from the player that this thread owns, with stone below and air above. */
    private static int[] spot(E2E e2e, String name, int[][] offsets) {
        return e2e.onPlayer(name, () -> {
            Player player = Bukkit.getPlayerExact(name);
            Block feet = player.getLocation().getBlock();
            for (int[] offset : offsets) {
                Block spot = feet.getRelative(offset[0], 0, offset[1]);
                if (Bukkit.isOwnedByCurrentRegion(spot.getLocation()) && Bukkit.isOwnedByCurrentRegion(spot.getRelative(1, 0, 0).getLocation())) {
                    spot.getRelative(0, -1, 0).setType(Material.STONE, false);
                    spot.setType(Material.CHEST, false);
                    spot.getRelative(0, 1, 0).setType(Material.AIR, false);
                    return new int[] {spot.getX(), spot.getY(), spot.getZ()};
                }
            }
            throw new IllegalStateException("no owned block next to " + name);
        });
    }

    private static Material type(E2E e2e, String name, int x, int y, int z) {
        return e2e.onPlayer(name, () -> Bukkit.getPlayerExact(name).getWorld().getBlockAt(x, y, z).getType());
    }

    static void block(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrBlock");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            int[] at = spot(e2e, name);
            String world = e2e.onPlayer(name, () -> e2e.player(name).getWorld().getName());
            String position = world + " " + at[0] + " " + at[1] + " " + at[2];

            e2e.step("staff make a block a crate");
            answer(e2e, "crates block add e2etest " + position, position + " is now the Test crate.");
            e2e.expect(number(e2e, "SELECT COUNT(*) FROM crate_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
                world, at[0], at[1], at[2]) == 1, "the crate block is stored");
            answer(e2e, "crates block list", "Test at " + position + ", placed in game");
            answer(e2e, "crates block add basic " + position, "That block is already the Test crate.");
            e2e.console("keys give " + name + " e2etest 2");

            e2e.step("right-click shows the crate; Open opens it");
            bot.clearLogs();
            bot.useItemOnTop(at[0], at[1], at[2]);
            Bot.SeenDialog view = e2e.dialog(bot, "Test crate");
            e2e.expect(view.bodyText().contains("You have 2 keys") && view.button("Open") != null && view.button("Preview") != null,
                "the crate dialog: " + view.bodyText() + " " + view.buttons());
            e2e.expect(bot.screen() == null, "the chest itself did not open");
            e2e.click(bot, "Open");
            awaitBody(e2e, bot, "You won 5 diamonds");
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 5, "5 diamonds");

            e2e.step("left-click shows the rewards");
            bot.clearLogs();
            Bot.Screen before = bot.screen();
            e2e.sleep(400);
            bot.breakBlock(at[0], at[1], at[2]);
            awaitScreen(e2e, bot, before, "Test crate rewards");
            bot.closeScreen();

            e2e.step("sneak + right-click opens a key straight away");
            e2e.onPlayer(name, () -> {
                e2e.player(name).setSneaking(true);
                return null;
            });
            bot.clearLogs();
            e2e.sleep(1_200);
            bot.useItemOnTop(at[0], at[1], at[2]);
            e2e.eventually(() -> bot.chatContains("You won 5 diamonds from the Test crate."), "quick open: " + bot.chat());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 10, "10 diamonds");
            e2e.expect(logRows(e2e, uuid) == 2, "two openings");
            e2e.onPlayer(name, () -> {
                e2e.player(name).setSneaking(false);
                return null;
            });

            e2e.step("the crate block can't be broken, even in creative, or blown up");
            e2e.onPlayer(name, () -> {
                e2e.player(name).setGameMode(GameMode.CREATIVE);
                Bukkit.getPlayerExact(name).getWorld().getBlockAt(at[0] + 1, at[1], at[2]).setType(Material.DIRT, false);
                return null;
            });
            e2e.sleep(400);
            bot.breakBlock(at[0], at[1], at[2]);
            e2e.sleep(1_000);
            e2e.expect(type(e2e, name, at[0], at[1], at[2]) == Material.CHEST, "the chest is still there");
            boolean broke = e2e.onPlayer(name, () -> e2e.player(name).breakBlock(e2e.player(name).getWorld().getBlockAt(at[0], at[1], at[2])));
            e2e.expect(!broke && type(e2e, name, at[0], at[1], at[2]) == Material.CHEST, "breaking it as the player is cancelled");
            e2e.onPlayer(name, () -> {
                Location center = new Location(Bukkit.getPlayerExact(name).getWorld(), at[0] + 0.5, at[1] + 0.5, at[2] + 0.5);
                center.getWorld().createExplosion(center, 2f, false, true);
                return null;
            });
            e2e.eventually(() -> type(e2e, name, at[0] + 1, at[1], at[2]) == Material.AIR, "the explosion broke the dirt next to it");
            e2e.expect(type(e2e, name, at[0], at[1], at[2]) == Material.CHEST, "but not the crate");

            e2e.step("staff remove the crate block; it is an ordinary block again");
            answer(e2e, "crates block remove " + position, position + " is no longer a crate.");
            e2e.expect(number(e2e, "SELECT COUNT(*) FROM crate_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
                world, at[0], at[1], at[2]) == 0, "the row is gone");
            answer(e2e, "crates block list", "There are no crate blocks.");
            answer(e2e, "crates block remove " + position, "That block isn't a crate.");
            e2e.onPlayer(name, () -> {
                e2e.player(name).setGameMode(GameMode.SURVIVAL);
                return null;
            });
            ledgerHealthy(e2e);
        } finally {
            restore(e2e, original);
        }
    }

    /** Puts the victim where the attacker stands and punches until the hit lands (join protection drops early hits). */
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
        return "true".equals(e2e.services().placeholders().resolve(e2e.player(name), "combat_tagged"));
    }

    static void combat(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String fighterName = e2e.name("CrFighter");
            String name = e2e.name("CrTagged");
            Bot fighter = e2e.bot(fighterName);
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            e2e.console("keys give " + name + " e2etest 2");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 2, "two Test keys");
            e2e.sleep(3_500);

            e2e.step("a player in combat can't open a crate with the command");
            fight(e2e, fighter, name);
            bot.clearLogs();
            command(e2e, bot, "crates open e2etest");
            e2e.eventually(() -> bot.actionBarContains("You can't open crates in combat."), "refused in combat: " + bot.actionBar());
            e2e.expect(keys(e2e, name, "e2etest") == 2 && logRows(e2e, uuid) == 0, "no key spent, nothing logged");

            e2e.step("nor from the crates dialog, which says why");
            command(e2e, bot, "crates");
            e2e.dialog(bot, "Crates");
            e2e.click(bot, "Open Test");
            awaitBody(e2e, bot, "You can't open crates in combat.");
            e2e.expect(keys(e2e, name, "e2etest") == 2 && count(e2e, name, Material.DIAMOND) == 0, "still two keys and no diamonds");

            e2e.step("looking at the rewards still works in combat");
            Bot.Screen before = bot.screen();
            bot.clickButton("Preview Test", Map.of());
            awaitScreen(e2e, bot, before, "Test crate rewards");
            bot.closeScreen();

            e2e.step("once the combat tag is gone the crate opens");
            e2e.console("combat untag " + name);
            e2e.eventually(() -> !inCombat(e2e, name), "no longer in combat");
            bot.clearLogs();
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2etest");
            e2e.eventually(() -> bot.chatContains("You won 5 diamonds from the Test crate."), "opened after combat: " + bot.chat());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 5, "5 diamonds");
            e2e.expect(keys(e2e, name, "e2etest") == 1 && logRows(e2e, uuid) == 1, "one key spent, one opening logged");
            keysMatchStorage(e2e, uuid);
        } finally {
            restore(e2e, original);
        }
    }

    private static void fillInventory(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.clear();
            for (int slot = 0; slot < 36; slot++) {
                inventory.setItem(slot, ItemStack.of(Material.COBBLESTONE, 64));
            }
            return null;
        });
    }

    /** How many single-opening receipts of the test crate the bot got. */
    private static long receipts(Bot bot) {
        return bot.chat().stream().filter(line -> line.contains("You won 5 diamonds from the Test crate.")).count();
    }

    static void bulk(E2E e2e) throws Exception {
        String original = install(e2e);
        try {
            String name = e2e.name("CrBulk");
            Bot bot = e2e.bot(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            e2e.console("keys give " + name + " e2etest 13");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 13, "13 Test keys");

            e2e.step("after one opening the result offers Open 10 more (bulk-open caps it)");
            command(e2e, bot, "crates");
            e2e.dialog(bot, "Crates");
            e2e.click(bot, "Open Test");
            Bot.SeenDialog first = awaitBody(e2e, bot, "You won 5 diamonds");
            e2e.expect(first.button("Open 10 more") != null && first.button("Open another") != null, "both open buttons: " + first.buttons());

            e2e.step("Open 10 more opens ten keys one after another and shows what they won");
            e2e.eventually(() -> receipts(bot) == 1, "the single receipt: " + bot.chat());
            e2e.click(bot, "Open 10 more");
            Bot.SeenDialog batch = awaitBody(e2e, bot, "You opened 10 crates");
            e2e.expect(batch.bodyText().contains("5 diamonds, 10 times") && batch.bodyText().contains("You have 2 keys left."),
                "the batch result: " + batch.bodyText());
            e2e.expect(batch.button("Open 2 more") != null && batch.button("Open another") != null && batch.button("Preview") != null,
                "what can be done next: " + batch.buttons());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 55, "55 diamonds: " + count(e2e, name, Material.DIAMOND));
            e2e.eventually(() -> bot.chatContains("You opened 10 Test crates."), "one summary in chat: " + bot.chat());
            e2e.expect(bot.chatContains("5 diamonds, 10 times"), "the summary lists the rewards: " + bot.chat());
            e2e.expect(receipts(bot) == 1, "no receipt per opening: " + bot.chat());
            e2e.expect(keys(e2e, name, "e2etest") == 2 && logRows(e2e, uuid) == 11, "eleven keys spent, eleven openings logged");

            e2e.step("/crates open with an amount opens what is left and stops when the keys run out");
            bot.clearLogs();
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2etest 5");
            e2e.eventually(() -> bot.chatContains("You opened 2 Test crates."), "two opened: " + bot.chat());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 65, "65 diamonds");
            e2e.expect(keys(e2e, name, "e2etest") == 0 && logRows(e2e, uuid) == 13, "no keys left, thirteen openings");
            e2e.expect(!bot.actionBarContains("You have no Test keys."), "running out after some is not an error: " + bot.actionBar());

            e2e.step("more than bulk-open at once is refused before anything opens");
            e2e.console("keys give " + name + " e2etest 1");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 1, "a key");
            bot.clearLogs();
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2etest 11");
            e2e.eventually(() -> bot.actionBarContains("You can open at most 10 crates at once."), "refused: " + bot.actionBar());
            e2e.expect(keys(e2e, name, "e2etest") == 1 && logRows(e2e, uuid) == 13, "nothing opened");

            e2e.step("a full inventory sends a batch to the claim box, with one note");
            fillInventory(e2e, name);
            e2e.console("keys give " + name + " e2etest 2");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 3, "three keys");
            bot.clearLogs();
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2etest 3");
            e2e.eventually(() -> bot.chatContains("You opened 3 Test crates."), "three opened: " + bot.chat());
            e2e.expect(bot.chatContains("Some of it didn't fit, so it's waiting in your claim box (/claims)."), "the claim box note: " + bot.chat());
            e2e.expect(e2e.services().deliveries().count(uuid) == 3 && count(e2e, name, Material.DIAMOND) == 0, "three stacks wait in the claim box");

            e2e.step("right-clicking the preview's open button opens several");
            clear(e2e, name);
            e2e.console("keys give " + name + " e2etest 4");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 4, "four keys");
            Bot.Screen before = bot.screen();
            command(e2e, bot, "crates preview e2etest");
            awaitScreen(e2e, bot, before, "Test crate rewards");
            e2e.eventually(() -> lore(bot.screenItems().get(50)).contains("Right-click to open 4"), "the right-click line: "
                + lore(bot.screenItems().get(50)));
            bot.clearLogs();
            bot.clickSlot(50, 1, ContainerInput.PICKUP);
            e2e.eventually(() -> bot.chatContains("You opened 4 Test crates."), "four opened: " + bot.chat());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 20, "20 diamonds");
            e2e.expect(keys(e2e, name, "e2etest") == 0 && logRows(e2e, uuid) == 20, "every key spent, twenty openings");
            e2e.eventually(() -> name(bot.screenItems().get(50)).equals("No keys"), "the button shows no keys after the redraw");
            bot.closeScreen();
            keysMatchStorage(e2e, uuid);
            ledgerHealthy(e2e);
        } finally {
            restore(e2e, original);
        }
    }

    static void spawnBlock(E2E e2e) throws Exception {
        String original = install(e2e);
        String position = null;
        String name = e2e.name("CrSpawn");
        try {
            Bot bot = e2e.botAtSpawn(name);
            UUID uuid = e2e.uuid(name);
            clear(e2e, name);
            int[] crate = spot(e2e, name, new int[][] {{2, 0}, {-2, 0}});
            int[] plain = spot(e2e, name, new int[][] {{0, 2}, {0, -2}});
            String world = e2e.onPlayer(name, () -> e2e.player(name).getWorld().getName());
            var area = e2e.spawnArea();
            e2e.expect(area != null && area.contains(new Location(Bukkit.getWorld(world), crate[0] + 0.5, crate[1], crate[2] + 0.5))
                && area.contains(new Location(Bukkit.getWorld(world), plain[0] + 0.5, plain[1], plain[2] + 0.5)),
                "both chests are inside the protected spawn area");
            position = world + " " + crate[0] + " " + crate[1] + " " + crate[2];
            answer(e2e, "crates block add e2etest " + position, position + " is now the Test crate.");
            e2e.console("keys give " + name + " e2etest 1");

            e2e.step("an ordinary chest at spawn is still protected");
            bot.clearLogs();
            e2e.sleep(400);
            bot.useItemOnTop(plain[0], plain[1], plain[2]);
            e2e.eventually(() -> bot.actionBarContains("You can't use that at spawn."), "the spawn protection refuses it: " + bot.actionBar());
            e2e.expect(bot.screen() == null, "the chest did not open");

            e2e.step("a crate block at spawn shows the crate, without the spawn protection's refusal");
            bot.clearLogs();
            e2e.sleep(1_200);
            bot.useItemOnTop(crate[0], crate[1], crate[2]);
            Bot.SeenDialog view = e2e.dialog(bot, "Test crate");
            e2e.expect(view.bodyText().contains("You have 1 key"), "the crate dialog: " + view.bodyText());
            e2e.sleep(500);
            e2e.expect(!bot.actionBarContains("at spawn"), "no spawn refusal: " + bot.actionBar());
            e2e.expect(bot.screen() == null, "the chest itself did not open");
            e2e.click(bot, "Open");
            awaitBody(e2e, bot, "You won 5 diamonds");
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == 5, "5 diamonds");
            e2e.expect(logRows(e2e, uuid) == 1, "one opening logged");

            e2e.step("a frozen player can't use a crate block");
            e2e.console("keys give " + name + " e2etest 1");
            bot.clearLogs();
            e2e.console("freeze " + name);
            e2e.eventually(() -> bot.anyFeedbackContains("Staff froze you"), "frozen: " + bot.chat());
            bot.clearLogs();
            e2e.sleep(1_200);
            bot.useItemOnTop(crate[0], crate[1], crate[2]);
            e2e.eventually(() -> bot.anyFeedbackContains("You can't do that while frozen."), "the freeze refuses it: " + bot.actionBar());
            e2e.sleep(800);
            e2e.expect(bot.dialogs().isEmpty(), "no crate dialog while frozen: " + bot.dialogs());
            e2e.onPlayer(name, () -> {
                e2e.player(name).setSneaking(true);
                return null;
            });
            e2e.sleep(1_200);
            bot.useItemOnTop(crate[0], crate[1], crate[2]);
            e2e.sleep(1_500);
            e2e.expect(keys(e2e, name, "e2etest") == 1 && logRows(e2e, uuid) == 1, "a sneak click opened nothing either");
            e2e.onPlayer(name, () -> {
                e2e.player(name).setSneaking(false);
                return null;
            });
            bot.clearLogs();
            e2e.console("freeze " + name);
            e2e.eventually(() -> bot.anyFeedbackContains("You can move again."), "unfrozen: " + bot.chat());
            e2e.sleep(1_200);
            bot.useItemOnTop(crate[0], crate[1], crate[2]);
            e2e.dialog(bot, "Test crate");
        } finally {
            if (position != null) {
                e2e.console("crates block remove " + position);
            }
            restore(e2e, original);
        }
    }

    static void persistSetup(E2E e2e) {
        String name = e2e.name("CrKeeper");
        e2e.bot(name);
        UUID uuid = e2e.uuid(name);
        String ref = "e2e-persist-" + name;
        TransactionResult result = crates(e2e).keys().give(uuid, "rare", 3, "e2e", ref);
        e2e.expect(result.success(), "keys given");
        answer(e2e, "keyall in 3h", "The next keyall is in 3h.");
        e2e.log("gave " + name + " 3 rare keys with " + ref + " and moved the keyall to 3h from now");
    }

    /** Finds what {@link #persistSetup} left (possibly before a restart) and checks it survived. */
    static void persistCheck(E2E e2e) {
        String ref = query(e2e, "SELECT ref FROM crate_grants WHERE ref LIKE 'e2e-persist-%' ORDER BY ts DESC LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null);
        e2e.expect(ref != null, "a persist grant exists (run crates-persist-setup first)");
        UUID uuid = UUID.fromString(query(e2e, "SELECT uuid FROM crate_grants WHERE ref = ?", rs -> rs.next() ? rs.getString(1) : null, ref));
        e2e.log("checking " + ref);
        e2e.step("the keys and the grant reference are still there");
        e2e.expect(crates(e2e).keys().keys(uuid, "rare") == 3, "three rare keys: " + crates(e2e).keys().keys(uuid, "rare"));
        TransactionResult again = crates(e2e).keys().give(uuid, "rare", 3, "e2e", ref);
        e2e.expect("duplicate".equals(again.reason()), "the reference is still applied: " + again.status() + " " + again.reason());

        e2e.step("the keyall schedule kept its time");
        long next = number(e2e, "SELECT next_run FROM crate_schedule WHERE id = 'keyall'");
        long ahead = next - System.currentTimeMillis();
        e2e.expect(ahead > 0 && ahead <= 3 * 3_600_000L, "the stored keyall is within the 3h set before: " + ahead);
        String name = e2e.name("CrHeir");
        e2e.bot(name);
        String countdown = e2e.services().placeholders().resolve(e2e.player(name), "keyall_countdown");
        e2e.expect(countdown.startsWith("2h") || countdown.startsWith("3h") && !countdown.startsWith("3h 5"), "the live countdown follows it: "
            + countdown);
        answer(e2e, "keyall in 4h", "The next keyall is in 4h.");
    }

    // ------------------------------------------------------------------ player settings

    private static void sneaking(E2E e2e, String name, boolean sneaking) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).setSneaking(sneaking);
            return null;
        });
    }

    /**
     * The crate settings change what players get: the win receipt above the hotbar and 3 keys per bulk open (a choice
     * and a number, saved in the settings dialog), only the rarest wins announced, sneak + right-click opening several
     * keys or the crate window, the keyall countdown in chat only and the unopened key reminder off (choices and a
     * switch set through the API, as other plugins and the coming /settings command set them).
     */
    static void settings(E2E e2e) throws Exception {
        String original = install(e2e);
        String position = null;
        try {
            String name = e2e.name("CrSet");
            String watcherName = e2e.name("CrSetW");
            Bot bot = e2e.bot(name);
            Bot watcher = e2e.bot(watcherName);
            UUID uuid = e2e.uuid(name);
            UUID watcherId = e2e.uuid(watcherName);
            clear(e2e, name);

            e2e.step("the crate settings are in Crates & kits and Server announcements, with their options");
            Map<String, List<String>> inputs = ItemSettingsSteps.inputs(e2e, bot, "crates", "Crates & kits settings");
            e2e.expect(List.of("chat", "actionbar", "off").equals(inputs.get("crate_receipt")), "the receipt choice: " + inputs);
            e2e.expect(List.of("toggle").equals(inputs.get("crate_key_reminder")), "the key reminder switch: " + inputs);
            e2e.expect(List.of("both", "chat", "actionbar", "off").equals(inputs.get("keyall_countdown")), "the countdown: " + inputs);
            e2e.expect(List.of("one", "bulk", "off").equals(inputs.get("crate_quick_open")), "the quick open choice: " + inputs);
            e2e.expect(List.of("range").equals(inputs.get("crate_bulk_amount")), "the bulk amount slider: " + inputs);
            e2e.expect(!inputs.containsKey("trash_protect"), "no trash settings without the trash perk: " + inputs);
            Bot.RangeSeen slider = ItemSettingsSteps.pageWith(e2e, bot, "crates", "Crates & kits settings", "crate_bulk_amount")
                .range("crate_bulk_amount");
            e2e.expect(slider != null && slider.start() == 2f && slider.end() == 64f && slider.initial() == 10f
                && slider.label().contains("keys"), "the slider runs 2 to 64 keys from 10: " + slider);
            Map<String, List<String>> announcements = ItemSettingsSteps.inputs(e2e, bot, "announcements", "Server announcements settings");
            e2e.expect(List.of("all", "rarest", "off").equals(announcements.get("crate_wins")), "crate wins is a choice: " + announcements);

            e2e.step("the receipt above the hotbar and 3 keys per bulk open, saved in the settings dialog");
            ItemSettingsSteps.edit(e2e, bot, "crates", "Crates & kits settings", Map.of("crate_receipt", "actionbar", "crate_bulk_amount", 3f));
            ItemSettingsSteps.expectStored(e2e, uuid, "crate-receipt", "actionbar");
            ItemSettingsSteps.expectStored(e2e, uuid, "crate-bulk-amount", "3");

            e2e.step("an opening's receipt now shows above the hotbar, not in chat");
            e2e.console("keys give " + name + " e2etest 8");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 8, "eight Test keys");
            bot.clearLogs();
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2etest");
            e2e.eventually(() -> bot.actionBarContains("You won 5 diamonds from the Test crate."), "the receipt above the hotbar: "
                + bot.actionBar());
            e2e.sleep(500);
            e2e.expect(!bot.chatContains("You won 5 diamonds"), "no receipt in chat: " + bot.chat());

            e2e.step("the crate result offers Open 3 more, which opens three keys with one short receipt");
            bot.clearLogs();
            command(e2e, bot, "crates");
            e2e.dialog(bot, "Crates");
            e2e.click(bot, "Open Test");
            Bot.SeenDialog first = awaitBody(e2e, bot, "You won 5 diamonds");
            e2e.expect(first.button("Open 3 more") != null && first.button("Open 10 more") == null, "three per bulk open: " + first.buttons());
            bot.clearMessages();
            e2e.click(bot, "Open 3 more");
            awaitBody(e2e, bot, "You opened 3 crates");
            e2e.eventually(() -> bot.actionBarContains("You opened 3 Test crates. Best: 5 diamonds"), "the short receipt: " + bot.actionBar());
            e2e.expect(!bot.chatContains("You opened 3 Test crates."), "no list in chat: " + bot.chat());
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 3, "three keys left: " + keys(e2e, name, "e2etest"));

            e2e.step("a bulk opening a plugin stops after two: the short receipt stays above the hotbar, the reason goes to chat");
            StopThird stopper = new StopThird(uuid);
            Bukkit.getPluginManager().registerEvents(stopper, e2e.services().plugin());
            try {
                bot.clearLogs();
                e2e.sleep(1_200);
                command(e2e, bot, "crates open e2etest 3");
                e2e.eventually(() -> bot.chatContains("The Test crate didn't open."), "the reason in chat: " + bot.chat());
                e2e.expect(bot.actionBarContains("You opened 2 Test crates. Best: 5 diamonds"), "the short receipt above the hotbar: "
                    + bot.actionBar());
                e2e.expect(!bot.actionBarContains("didn't open"), "the reason did not replace the receipt: " + bot.actionBar());
                e2e.expect(keys(e2e, name, "e2etest") == 1, "two keys spent, the stopped one kept: " + keys(e2e, name, "e2etest"));
            } finally {
                HandlerList.unregisterAll(stopper);
            }
            e2e.console("keys give " + name + " e2etest 2");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 3, "three keys again");

            e2e.step("a player who wants only the rarest wins is not told about an epic win (set through the API)");
            e2e.console("eco set " + name + " 0");
            e2e.eventually(() -> e2e.money(name) == 0, "no money");
            ItemSettingsSteps.set(e2e, watcherId, CratesFeature.WIN_ANNOUNCEMENTS, CratePlayerSettings.WinFilter.RAREST);
            ItemSettingsSteps.expectStored(e2e, watcherId, "crate-wins", "rarest");
            e2e.console("keys give " + name + " e2ecash 2");
            e2e.eventually(() -> keys(e2e, name, "e2ecash") == 2, "two Cash keys");
            watcher.clearLogs();
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2ecash");
            e2e.eventually(() -> e2e.money(name) == 1_234, "paid (has " + e2e.money(name) + ")");
            e2e.sleep(1_000);
            e2e.expect(!watcher.chatContains("won $1,234"), "the epic win is not the rarest: " + watcher.chat());
            ItemSettingsSteps.set(e2e, watcherId, CratesFeature.WIN_ANNOUNCEMENTS, CratePlayerSettings.WinFilter.ALL);
            ItemSettingsSteps.expectStored(e2e, watcherId, "crate-wins", null);
            e2e.sleep(1_200);
            command(e2e, bot, "crates open e2ecash");
            e2e.eventually(() -> watcher.chatContains(name + " won $1,234 from the Cash crate."), "every win again: " + watcher.chat());

            e2e.step("sneak + right-click on a crate block opens the bulk amount at once (set through the API)");
            ItemSettingsSteps.set(e2e, uuid, CratePlayerSettings.QUICK_OPEN, CratePlayerSettings.QuickOpen.BULK);
            int[] at = spot(e2e, name);
            String world = e2e.onPlayer(name, () -> e2e.player(name).getWorld().getName());
            position = world + " " + at[0] + " " + at[1] + " " + at[2];
            answer(e2e, "crates block add e2etest " + position, position + " is now the Test crate.");
            int diamonds = count(e2e, name, Material.DIAMOND);
            sneaking(e2e, name, true);
            bot.clearLogs();
            e2e.sleep(1_200);
            bot.useItemOnTop(at[0], at[1], at[2]);
            e2e.eventually(() -> bot.actionBarContains("You opened 3 Test crates."), "three opened from the block: " + bot.actionBar());
            e2e.eventually(() -> count(e2e, name, Material.DIAMOND) == diamonds + 15, "15 more diamonds");
            e2e.expect(keys(e2e, name, "e2etest") == 0, "every key used");

            e2e.step("with Sneak + right-click set to the crate window it only shows the crate");
            ItemSettingsSteps.set(e2e, uuid, CratePlayerSettings.QUICK_OPEN, CratePlayerSettings.QuickOpen.OFF);
            e2e.console("keys give " + name + " e2etest 1");
            e2e.eventually(() -> keys(e2e, name, "e2etest") == 1, "a key");
            e2e.sleep(1_200);
            bot.useItemOnTop(at[0], at[1], at[2]);
            Bot.SeenDialog view = e2e.dialog(bot, "Test crate");
            e2e.expect(view.button("Open") != null, "the crate window: " + view.buttons());
            e2e.sleep(800);
            e2e.expect(keys(e2e, name, "e2etest") == 1, "nothing opened");
            sneaking(e2e, name, false);
            answer(e2e, "crates block remove " + position, position + " is no longer a crate.");
            position = null;

            e2e.step("the keyall countdown in chat only (set through the API): the chat line comes, the hotbar count doesn't");
            ItemSettingsSteps.set(e2e, uuid, CratePlayerSettings.KEYALL_COUNTDOWN, AlertStyle.CHAT);
            int basic = keys(e2e, name, "basic");
            bot.clearLogs();
            watcher.clearLogs();
            answer(e2e, "keyall in 65s", "The next keyall is in 1m 5s.");
            e2e.eventually(() -> bot.chatContains("Keyall in 1m. Everyone online gets 1 Basic key."), 15_000, "the 1m line: " + bot.chat());
            e2e.eventually(() -> watcher.actionBarContains("Keyall in 10s"), 65_000, "the watcher (both) counts down: " + watcher.actionBar());
            e2e.expect(watcher.chatContains("Keyall in 1m."), "the watcher got the chat line too: " + watcher.chat());
            e2e.eventually(() -> keys(e2e, name, "basic") == basic + 1, 20_000, "the keyall gave a basic key");
            e2e.expect(bot.actionBar().stream().noneMatch(line -> line.contains("Keyall in")), "no hotbar count for chat only: "
                + bot.actionBar());
            e2e.eventually(() -> bot.chatContains("Keyall: everyone online got 1 Basic key."), "the keyall line always shows: " + bot.chat());

            e2e.step("the unopened key reminder off (set through the API): no reminder on join, while the watcher gets one");
            ItemSettingsSteps.set(e2e, uuid, CratePlayerSettings.KEY_REMINDER, false);
            ItemSettingsSteps.expectStored(e2e, uuid, "crate-key-reminder", "false");
            bot.quit();
            watcher.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(name) == null && Bukkit.getPlayerExact(watcherName) == null, "both left");
            e2e.sleep(1_000);
            Bot back = e2e.botAtSpawn(name);
            Bot watcherBack = e2e.botAtSpawn(watcherName);
            e2e.eventually(() -> watcherBack.chatContains("You have 1 key to open."), 15_000, "the watcher's reminder: " + watcherBack.chat());
            e2e.sleep(1_500);
            e2e.expect(!back.chatContains("to open."), "no reminder with the setting off: " + back.chat());
            ledgerHealthy(e2e);
        } finally {
            if (position != null) {
                e2e.console("crates block remove " + position);
            }
            answer(e2e, "keyall in 4h", "The next keyall is in 4h.");
            restore(e2e, original);
        }
    }

    /** Cancels a player's third crate opening (a plugin stopping a bulk opening part way). */
    private static final class StopThird implements Listener {

        private final UUID player;
        private final AtomicInteger seen = new AtomicInteger();

        StopThird(UUID player) {
            this.player = player;
        }

        @EventHandler
        public void on(CrateOpenEvent event) {
            if (event.player().getUniqueId().equals(this.player) && this.seen.incrementAndGet() == 3) {
                event.setCancelled(true);
            }
        }
    }
}
