package net.siftvanilla.e2e;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.api.event.SpawnerSellEvent;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.feature.boosters.BoostersFeature;
import net.siftvanilla.siftcore.feature.sell.Boosts;
import net.siftvanilla.siftcore.feature.sell.Pricing;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
import net.siftvanilla.siftcore.feature.sell.WorthService;
import net.siftvanilla.siftcore.feature.shop.ShopFeature;
import net.siftvanilla.siftcore.feature.shop.ShopValidator;
import net.siftvanilla.siftcore.feature.spawn.SpawnFeature;
import net.siftvanilla.siftcore.feature.spawners.SpawnersFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * Server sell boosters and the small perks that came with them: a booster raises every sale to the server by exactly
 * its percent (with the receipt saying so), boosters wait in line and never stack, revoking a store booster ends it or
 * takes it out of line, spawner storage sales are boosted and buy orders are not, the shop prices itself against the
 * largest booster allowed, the boss bar reaches the client, and a running booster survives a restart. Then /fly at
 * spawn, joining a full server and /purchases.
 * <p>
 * Every booster scenario starts and ends with no booster running, so the other scenarios see normal prices.
 */
final class BoostersScenarios {

    /** Raw slot of the storage menu's "Sell all" button (bottom row of a six-row chest). */
    private static final int STORAGE_SELL = 51;

    private BoostersScenarios() {
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
        list.add(of("boosters-sell", BoostersScenarios::sell));
        list.add(of("boosters-queue", BoostersScenarios::queue));
        list.add(of("boosters-store-revoke", BoostersScenarios::storeRevoke));
        list.add(of("boosters-spawner", BoostersScenarios::spawner));
        list.add(of("boosters-orders", BoostersScenarios::orders));
        list.add(of("boosters-shop-guard", BoostersScenarios::shopGuard));
        list.add(of("boosters-capped", BoostersScenarios::capped));
        list.add(of("boosters-persist-setup", BoostersScenarios::persistSetup));
        list.add(of("boosters-persist-check", BoostersScenarios::persistCheck));
        list.add(of("spawn-fly", BoostersScenarios::spawnFly));
        list.add(of("join-full", BoostersScenarios::joinFull));
        list.add(of("purchases", BoostersScenarios::purchases));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static ServerBoosters boosters(E2E e2e) {
        return e2e.feature(BoostersFeature.class).boosters();
    }

    private static WorthService worth(E2E e2e) {
        return e2e.feature(SellFeature.class).worth();
    }

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    /** A server-wide placeholder (the booster ones don't depend on the player). */
    private static String placeholder(E2E e2e, String name) {
        String value = e2e.services().placeholders().resolve(null, name);
        return value == null ? "" : value;
    }

    private static String money(long amount) {
        return String.format(Locale.ROOT, "$%,d", amount);
    }

    /** Runs a command as a console-like sender; the returned list keeps filling as late (async) replies arrive. */
    private static List<String> run(E2E e2e, String command) {
        List<String> lines = new CopyOnWriteArrayList<>();
        e2e.services().scheduler().global(() -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message ->
                    lines.add(PlainTextComponentSerializer.plainText().serialize(message))), command);
            } catch (RuntimeException e) {
                lines.add("command failed: " + e);
            }
        });
        return lines;
    }

    private static boolean contains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private static List<String> reply(E2E e2e, String command, String expected) {
        List<String> lines = run(e2e, command);
        e2e.eventually(() -> contains(lines, expected), "'" + command + "' answers '" + expected + "': " + lines);
        return lines;
    }

    /** Stops every running and waiting booster, so a scenario starts and ends with normal prices. */
    static void clearBoosters(E2E e2e) {
        for (int i = 0; i < 60; i++) {
            if ("false".equals(placeholder(e2e, "booster_active")) && "0".equals(placeholder(e2e, "booster_queue"))) {
                return;
            }
            e2e.console("sift booster stop");
        }
        throw new E2E.Failure("boosters are still running: " + e2e.consoleOutput("sift booster list"));
    }

    /** Empties the inventory and puts {@code item} in the selected first hotbar slot. */
    private static void hold(E2E e2e, String name, ItemStack item) {
        e2e.onPlayer(name, () -> {
            PlayerInventory inventory = e2e.player(name).getInventory();
            inventory.clear();
            inventory.setHeldItemSlot(0);
            inventory.setItem(0, item);
            return null;
        });
    }

    /**
     * A new player (no selling mastery yet) sells 10 diamonds with /sell hand; checks the exact payment and the
     * receipt ({@code receipt} is matched in chat).
     */
    private static void sellDiamonds(E2E e2e, String base, long expected, String receipt) {
        String name = e2e.name(base);
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        hold(e2e, name, ItemStack.of(Material.DIAMOND, 10));
        bot.clearLogs();
        bot.command("sell hand");
        e2e.eventually(() -> e2e.money(name) > 0, name + " was paid");
        e2e.sleep(300);
        e2e.expect(e2e.money(name) == expected, name + " was paid exactly " + money(expected) + " (got " + money(e2e.money(name)) + ")");
        e2e.eventually(() -> bot.chatContains(receipt), "the receipt says '" + receipt + "': " + bot.chat());
    }

    private static long diamondWorth(E2E e2e) {
        long unit = worth(e2e).unitPrice(ItemStack.of(Material.DIAMOND));
        e2e.expect(unit > 0, "diamonds sell (" + unit + ")");
        return unit;
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

    @FunctionalInterface
    private interface Rows<T> {
        T read(ResultSet rs) throws java.sql.SQLException;
    }

    // ------------------------------------------------------------------ boosters

    static void sell(E2E e2e) {
        clearBoosters(e2e);
        long unit = diamondWorth(e2e);
        String watcherName = e2e.name("BoostSee");
        Bot watcher = e2e.bot(watcherName);
        try {
            e2e.step("without a booster 10 diamonds sell for their worth");
            sellDiamonds(e2e, "BoostNone", unit * 10, "for " + money(unit * 10) + ".");
            e2e.expect(watcher.bossBar("sell booster") == null, "no booster bar");

            e2e.step("staff start a +10% booster: everyone is told and gets the boss bar");
            watcher.clearLogs();
            reply(e2e, "sift booster start 10 30m e2e sell test", "Started the +10% sell booster");
            e2e.eventually(() -> watcher.chatContains("A +10% sell booster started for 30 minutes."), "the announcement: " + watcher.chat());
            e2e.eventually(() -> watcher.chatContains("\"e2e sell test\""), "the reason follows: " + watcher.chat());
            e2e.eventually(() -> watcher.bossBar("+10% sell booster") != null, "the boss bar arrived: " + watcher.bossBars());
            Bot.SeenBossBar bar = watcher.bossBar("+10% sell booster");
            e2e.expect(bar.title().contains("left") && "GREEN".equals(bar.color()) && "PROGRESS".equals(bar.overlay())
                && bar.progress() > 0.9f && bar.progress() <= 1f, "a green progress bar with the time left: " + bar);
            int packets = watcher.bossBarPackets();
            e2e.eventually(() -> watcher.bossBarPackets() > packets, 5_000, "the bar counts down (updates keep arriving)");
            e2e.expect(boosters(e2e).percent() == 10, "the booster runs: +" + boosters(e2e).percent() + "%");
            e2e.expect("true".equals(placeholder(e2e, "booster_active")) && "10".equals(placeholder(e2e, "booster_percent"))
                && placeholder(e2e, "booster_time_left").startsWith("29m") && "0".equals(placeholder(e2e, "booster_queue")),
                "the placeholders: " + placeholder(e2e, "booster_active") + " " + placeholder(e2e, "booster_percent") + " "
                    + placeholder(e2e, "booster_time_left") + " " + placeholder(e2e, "booster_queue"));

            e2e.step("the same 10 diamonds now pay exactly 10% more, and the receipt says so");
            long boosted = unit * 10 * 110 / 100;
            sellDiamonds(e2e, "BoostSell", boosted, "for " + money(boosted) + " incl. +10% booster.");

            e2e.step("/worth names the booster");
            hold(e2e, watcherName, ItemStack.of(Material.DIAMOND, 10));
            watcher.clearLogs();
            watcher.command("worth");
            e2e.eventually(() -> watcher.chatContains("With the +10% sell booster you get " + money(boosted) + "."), "/worth: " + watcher.chat());
            hold(e2e, watcherName, null);

            e2e.step("/booster shows it, and its button hides and shows the bar");
            watcher.clearLogs();
            watcher.command("booster");
            Bot.SeenDialog dialog = e2e.dialog(watcher, "Sell booster");
            e2e.expect(dialog.bodyText().contains("+10% on everything sold to the server") && dialog.bodyText().contains("From the server")
                && dialog.bodyText().contains("Nothing is waiting in line."), "the dialog: " + dialog.body());
            e2e.click(watcher, "Hide the booster bar");
            e2e.eventually(() -> watcher.bossBar("sell booster") == null, "the bar is hidden: " + watcher.bossBars());
            e2e.eventually(() -> watcher.anyFeedbackContains("The booster bar is hidden."), "told: " + watcher.chat() + watcher.actionBar());
            e2e.dialog(watcher, "Sell booster");
            e2e.sleep(2_500);
            e2e.expect(watcher.bossBar("sell booster") == null, "it stays hidden while the bar refreshes");
            e2e.click(watcher, "Show the booster bar");
            e2e.eventually(() -> watcher.bossBar("+10% sell booster") != null, "the bar is back: " + watcher.bossBars());

            e2e.step("stopping it ends it for everyone");
            watcher.clearLogs();
            reply(e2e, "sift booster stop", "Stopped the +10% sell booster");
            e2e.eventually(() -> watcher.bossBar("sell booster") == null, "the bar is gone: " + watcher.bossBars());
            e2e.eventually(() -> watcher.chatContains("The +10% sell booster was ended early."), "the end is announced: " + watcher.chat());
            e2e.expect(boosters(e2e).percent() == 0 && "false".equals(placeholder(e2e, "booster_active"))
                && placeholder(e2e, "booster_time_left").isEmpty(), "no booster any more");
            sellDiamonds(e2e, "BoostAfter", unit * 10, "for " + money(unit * 10) + ".");
            reply(e2e, "sift audit booster.start", "booster.start");
        } finally {
            clearBoosters(e2e);
        }
    }

    static void queue(E2E e2e) {
        clearBoosters(e2e);
        long unit = diamondWorth(e2e);
        Bot watcher = e2e.bot(e2e.name("QueueSee"));
        try {
            e2e.step("a second booster waits for the first");
            watcher.clearLogs();
            reply(e2e, "sift booster start 10 1m first in line", "Started the +10% sell booster");
            List<String> second = reply(e2e, "sift booster start 20 1m second in line", "Queued the +20% sell booster");
            e2e.expect(contains(second, "(number 1 in line)"), "it is number 1 in line: " + second);
            e2e.eventually(() -> watcher.chatContains("A +20% sell booster for 1 minute is waiting in line (number 1)."),
                "the wait is announced: " + watcher.chat());

            e2e.step("they never stack: +10% now, not +30%");
            e2e.expect(boosters(e2e).percent() == 10 && "1".equals(placeholder(e2e, "booster_queue")),
                "+" + boosters(e2e).percent() + "% with " + placeholder(e2e, "booster_queue") + " waiting");
            sellDiamonds(e2e, "QueueA", unit * 10 * 110 / 100, "incl. +10% booster.");
            watcher.command("booster");
            Bot.SeenDialog dialog = e2e.dialog(watcher, "Sell booster");
            e2e.expect(dialog.bodyText().contains("1. +20% for 1m from the server"), "/booster lists the next one: " + dialog.body());

            e2e.step("when the first runs out the second starts by itself");
            e2e.eventually(() -> watcher.chatContains("The +10% sell booster has ended."), 75_000, "the first ended: " + watcher.chat());
            e2e.eventually(() -> boosters(e2e).percent() == 20, 5_000, "the second runs: +" + boosters(e2e).percent() + "%");
            e2e.eventually(() -> watcher.chatContains("A +20% sell booster started for 1 minute."), "its start is announced: " + watcher.chat());
            e2e.expect("0".equals(placeholder(e2e, "booster_queue")), "nothing waits any more");
            e2e.eventually(() -> watcher.bossBar("+20% sell booster") != null, "the bar shows the second: " + watcher.bossBars());
            sellDiamonds(e2e, "QueueB", unit * 10 * 120 / 100, "incl. +20% booster.");
        } finally {
            clearBoosters(e2e);
        }
    }

    static void storeRevoke(E2E e2e) {
        clearBoosters(e2e);
        String name = e2e.name("BoostBuy");
        Bot buyer = e2e.bot(name);
        String ref = "e2e-bst-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            e2e.step("a store booster starts at once and thanks the buyer by name");
            buyer.clearLogs();
            reply(e2e, "sift store booster " + name + " sell 15 30m " + ref + "-a", "Delivered a +15% sell booster for 30m to " + name);
            e2e.eventually(() -> buyer.chatContains(name + " started a +15% sell booster for 30 minutes. Thank you!"),
                "the announcement names the buyer: " + buyer.chat());
            e2e.eventually(() -> buyer.chatContains("Your store purchase arrived: a +15% sell booster for everyone online, running now"),
                "the buyer is told: " + buyer.chat());
            e2e.eventually(() -> buyer.bossBar("+15% sell booster from " + name) != null, "the bar names the buyer: " + buyer.bossBars());
            e2e.expect(name.equals(placeholder(e2e, "booster_by")), "%siftcore_booster_by%: " + placeholder(e2e, "booster_by"));
            reply(e2e, "sift store booster " + name + " sell 15 30m " + ref + "-a", "Already delivered");
            e2e.expect(boosters(e2e).percent() == 15 && "0".equals(placeholder(e2e, "booster_queue")), "a retried delivery starts nothing");

            e2e.step("a second purchase waits in line");
            buyer.clearLogs();
            reply(e2e, "sift store booster " + name + " sell 5 1h " + ref + "-b", "Delivered a +5% sell booster for 1h to " + name);
            e2e.eventually(() -> buyer.chatContains("It starts after the boosters before it (number 1 in line)."), "told it waits: " + buyer.chat());
            e2e.expect(boosters(e2e).percent() == 15 && "1".equals(placeholder(e2e, "booster_queue")), "it waits, nothing stacks");

            e2e.step("revoking the waiting one takes it out of line; the running one goes on");
            reply(e2e, "sift store revoke " + ref + "-b refund", "The booster was taken out of line before it started.");
            e2e.expect(boosters(e2e).percent() == 15 && "0".equals(placeholder(e2e, "booster_queue")), "only the running one is left");

            e2e.step("revoking the running one ends it at once, once");
            buyer.clearLogs();
            reply(e2e, "sift store revoke " + ref + "-a chargeback", "The running booster was ended");
            e2e.eventually(() -> boosters(e2e).percent() == 0 && "false".equals(placeholder(e2e, "booster_active")), "no booster runs");
            e2e.eventually(() -> buyer.bossBar("sell booster") == null, "the bar is gone: " + buyer.bossBars());
            e2e.eventually(() -> buyer.chatContains("The +15% sell booster was ended early."), "the early end is announced: " + buyer.chat());
            e2e.eventually(() -> buyer.chatContains("A store purchase was cancelled, so a +15% sell booster for 30m was taken back."),
                "the buyer is told: " + buyer.chat());
            reply(e2e, "sift store revoke " + ref + "-a chargeback", "was revoked before");
            reply(e2e, "sift store booster " + name + " sell 15 30m " + ref + "-a", "was revoked, so it can't be delivered again");
            e2e.sleep(500);
            e2e.expect(boosters(e2e).percent() == 0, "a revoked reference never starts again");
            reply(e2e, "sift store check " + ref + "-a", "revoked (chargeback, booster ended early)");

            e2e.step("refusals record nothing");
            reply(e2e, "sift store booster " + name + " sell 60 30m " + ref + "-c", "the percent must be from 1 to 50");
            reply(e2e, "sift store booster " + name + " sell 10 31d " + ref + "-c", "the length must be from 1m to 30d");
            reply(e2e, "sift store booster " + name + " sell 10 forever " + ref + "-c", "is not a length");
            reply(e2e, "sift store check " + ref + "-c", "Nothing was delivered under " + ref + "-c");
            reply(e2e, "sift audit store.booster " + name, ref + "-a");
        } finally {
            clearBoosters(e2e);
        }
    }

    // ------------------------------------------------------------------ spawners

    /** A cleared block next to the player that their thread owns, with stone below; as x y z. */
    private static int[] workSpot(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> {
            Player player = Bukkit.getPlayerExact(name);
            Block spot = player.getLocation().getBlock().getRelative(0, 0, 2);
            if (!Bukkit.isOwnedByCurrentRegion(spot.getLocation())) {
                throw new IllegalStateException("the work spot is not owned by " + name);
            }
            spot.getRelative(0, -1, 0).setType(Material.STONE, false);
            spot.setType(Material.AIR, false);
            spot.getRelative(0, 1, 0).setType(Material.AIR, false);
            return new int[] {spot.getX(), spot.getY(), spot.getZ()};
        });
    }

    private static long spawnerNumber(E2E e2e, String name, String placeholder) {
        String value = e2e.services().placeholders().resolve(e2e.player(name), placeholder);
        return value == null || value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private static void sneak(E2E e2e, Bot bot, boolean sneaking) {
        bot.sneak(sneaking);
        e2e.eventually(() -> e2e.onPlayer(bot.name, () -> e2e.player(bot.name).isSneaking()) == sneaking,
            bot.name + (sneaking ? " sneaks" : " stands up"));
    }

    private static String slotLore(Bot bot, int slot) {
        var stack = bot.screenItems().get(slot);
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (var line : lore.lines()) {
            text.append(line.getString()).append('\n');
        }
        return text.toString();
    }

    static void spawner(E2E e2e) {
        clearBoosters(e2e);
        String name = e2e.name("BoostSpwn");
        Bot bot = e2e.bot(name);
        e2e.console("eco set " + name + " 0");
        hold(e2e, name, null);
        e2e.step("a stack of 10 zombie spawners fills its storage");
        e2e.console("spawners give " + name + " zombie 10");
        ItemStack unit = e2e.feature(SpawnersFeature.class).items().create("zombie", 1).orElseThrow();
        e2e.eventually(() -> e2e.onPlayer(name, () -> {
            ItemStack held = e2e.player(name).getInventory().getItem(0);
            return held != null && held.isSimilar(unit) && held.getAmount() == 10;
        }), "10 zombie spawners in the first slot");
        int[] at = workSpot(e2e, name);
        bot.useItemOnTop(at[0], at[1] - 1, at[2]);
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).getWorld().getBlockAt(at[0], at[1], at[2]).getType())
            == Material.SPAWNER, "the spawner is placed");
        sneak(e2e, bot, true);
        bot.useItemOnTop(at[0], at[1], at[2]);
        e2e.eventually(() -> spawnerNumber(e2e, name, "spawners_stacked") == 10, "a stack of 10");
        long before = spawnerNumber(e2e, name, "spawners_stored");
        e2e.console("spawners cycle");
        e2e.eventually(() -> spawnerNumber(e2e, name, "spawners_stored") > before, "the loot cycle filled the storage");

        AtomicReference<SpawnerSellEvent> sale = new AtomicReference<>();
        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onSell(SpawnerSellEvent event) {
                if (event.player() != null && event.player().getName().equals(name)) {
                    sale.set(event);
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, harness());
        try {
            e2e.step("with a +10% booster running, Sell all pays exactly 10% more than the storage is worth");
            reply(e2e, "sift booster start 10 30m e2e spawner test", "Started the +10% sell booster");
            // Sneak and right click with an empty hand opens the storage.
            e2e.onPlayer(name, () -> {
                e2e.player(name).getInventory().setHeldItemSlot(8);
                e2e.player(name).getInventory().setItem(8, null);
                return null;
            });
            bot.useItemOnTop(at[0], at[1], at[2]);
            e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Zombie spawner"),
                "the storage opened (" + (bot.screen() == null ? "none" : bot.screen().title()) + ")");
            sneak(e2e, bot, false);
            e2e.eventually(() -> slotLore(bot, STORAGE_SELL).contains("Includes the +10% sell booster"),
                "the Sell all button names the booster: " + slotLore(bot, STORAGE_SELL));
            bot.clearLogs();
            bot.clickSlot(STORAGE_SELL);
            e2e.eventually(() -> sale.get() != null && e2e.money(name) > 0, "the storage sold");
            SpawnerSellEvent event = sale.get();
            BigDecimal base = BigDecimal.ZERO;
            for (Map.Entry<Material, Long> line : event.items().entrySet()) {
                base = base.add(BigDecimal.valueOf(worth(e2e).unitPrice(ItemStack.of(line.getKey()))).multiply(BigDecimal.valueOf(line.getValue())));
            }
            long expected = base.multiply(new BigDecimal("1.1")).setScale(0, RoundingMode.FLOOR).longValueExact();
            e2e.expect(base.signum() > 0, "the storage was worth something: " + event.items());
            e2e.expect(event.total() == expected, "paid exactly the worth " + base + " x 1.1 = " + money(expected) + " (sale total "
                + money(event.total()) + ")");
            e2e.eventually(() -> e2e.money(name) == expected, "the owner has " + money(expected) + " (has " + money(e2e.money(name)) + ")");
            e2e.expect(Math.abs(event.multiplier() - 1.1) < 1e-9, "the sale event's multiplier includes the booster: " + event.multiplier());
            e2e.eventually(() -> bot.chatContains("from the zombie spawner for " + money(expected) + " incl. +10% booster."),
                "the receipt names the booster: " + bot.chat());
        } finally {
            HandlerList.unregisterAll(listener);
            bot.closeScreen();
            clearBoosters(e2e);
        }
    }

    // ------------------------------------------------------------------ buy orders

    private static long latestOrder(E2E e2e, String owner) {
        return query(e2e, "SELECT COALESCE(MAX(id), 0) FROM orders WHERE owner = ?", rs -> rs.next() ? rs.getLong(1) : 0L,
            e2e.uuid(owner).toString());
    }

    /** One stored order as {@code state/quantity/filled/collected/price_each/escrow}. */
    private static String row(E2E e2e, long id) {
        return query(e2e, "SELECT state, quantity, filled, collected, price_each, escrow FROM orders WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) + "/" + rs.getInt(2) + "/" + rs.getInt(3) + "/" + rs.getInt(4) + "/" + rs.getLong(5)
                + "/" + rs.getLong(6) : "none", id);
    }

    static void orders(E2E e2e) {
        clearBoosters(e2e);
        String buyerName = e2e.name("BoostOrdB");
        String sellerName = e2e.name("BoostOrdS");
        Bot buyer = e2e.bot(buyerName);
        Bot seller = e2e.bot(sellerName);
        e2e.console("eco set " + buyerName + " 100000");
        e2e.console("eco set " + sellerName + " 0");
        List<Long> leftover = query(e2e, "SELECT id FROM orders WHERE state = 'ACTIVE' AND item_type = 'minecraft:iron_ingot'", rs -> {
            List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            return ids;
        });
        for (long old : leftover) {
            e2e.console("orders admin cancel " + old + " end of an earlier test");
        }
        long iron = worth(e2e).unitPrice(ItemStack.of(Material.IRON_INGOT));
        e2e.expect(iron > 0 && iron * 125 < 100 * 100, "an iron ingot sells for well under $100 even with +25% (" + iron + ")");
        long order = 0;
        try {
            reply(e2e, "sift booster start 25 30m e2e orders test", "Started the +25% sell booster");

            e2e.step("an order at $100 each is placed");
            long before = latestOrder(e2e, buyerName);
            buyer.clearLogs();
            e2e.sleep(700);
            buyer.command("orders create iron_ingot 20 100");
            e2e.dialog(buyer, "Place order");
            e2e.click(buyer, "Place order");
            e2e.eventually(() -> latestOrder(e2e, buyerName) > before, "the order is stored");
            long id = latestOrder(e2e, buyerName);
            order = id;
            e2e.eventually(() -> row(e2e, id).startsWith("ACTIVE/"), "order " + id + " is active");

            e2e.step("selling into the order pays the order's price, never boosted");
            hold(e2e, sellerName, ItemStack.of(Material.IRON_INGOT, 12));
            seller.clearLogs();
            e2e.sleep(700);
            seller.command("sell hand");
            e2e.eventually(() -> row(e2e, id).equals("ACTIVE/20/12/0/100/800"), "the order took all 12: " + row(e2e, id));
            e2e.eventually(() -> e2e.money(sellerName) == 12 * 100 - 24, "the seller got $1,176 after tax (" + money(e2e.money(sellerName)) + ")");
            e2e.sleep(500);
            e2e.expect(e2e.money(sellerName) == 12 * 100 - 24, "nothing on top of the order's price: " + money(e2e.money(sellerName)));
            e2e.eventually(() -> seller.chatContains("You sold"), "a receipt: " + seller.chat());
            e2e.expect(!seller.chatContains("booster"), "an order sale names no booster: " + seller.chat());

            e2e.step("in the same sale, what the server buys is boosted");
            long paid = e2e.money(sellerName);
            long serverFor24 = e2e.onPlayer(sellerName, () -> worth(e2e).priceFor(e2e.player(sellerName), ItemStack.of(Material.IRON_INGOT, 24)));
            e2e.expect(serverFor24 == 24 * iron * 125 / 100, "the server pays 24 ingots +25%: " + money(serverFor24) + " for a worth of "
                + money(24 * iron));
            hold(e2e, sellerName, ItemStack.of(Material.IRON_INGOT, 32));
            seller.clearLogs();
            e2e.sleep(700);
            seller.command("sell hand");
            e2e.eventually(() -> row(e2e, id).equals("FILLED/20/20/0/100/0"), "the last 8 filled the order: " + row(e2e, id));
            e2e.eventually(() -> e2e.money(sellerName) - paid == 8 * 100 - 16 + serverFor24,
                "8 to the order ($784) and 24 to the server (" + money(serverFor24) + "): got " + money(e2e.money(sellerName) - paid));
            e2e.eventually(() -> seller.chatContains("incl. +25% booster"), "the receipt names the booster for the server's part: " + seller.chat());
        } finally {
            if (order > 0 && row(e2e, order).startsWith("ACTIVE/")) {
                e2e.console("orders admin cancel " + order + " end of test");
            }
            clearBoosters(e2e);
        }
    }

    // ------------------------------------------------------------------ shop

    static void shopGuard(E2E e2e) throws Exception {
        clearBoosters(e2e);
        Path data = e2e.services().plugin().getDataFolder().toPath();
        Path shopFile = data.resolve("features/shop.yml");
        Path boostersFile = data.resolve("features/boosters.yml");
        String shopOriginal = Files.readString(shopFile, StandardCharsets.UTF_8);
        String boostersOriginal = Files.readString(boostersFile, StandardCharsets.UTF_8);
        String diamondLine = "diamond: {price: 1000, max: 256}";
        e2e.expect(shopOriginal.contains(diamondLine), "features/shop.yml sells diamonds for $1,000");
        e2e.expect(boostersOriginal.contains("max-percent: 25"), "features/boosters.yml allows +25%");
        Pricing pricing = worth(e2e).current();
        e2e.expect(pricing.highestBoost() == 25, "the shop prices against the +25% booster: " + pricing.highestBoost());
        long unit = diamondWorth(e2e);
        double best = pricing.highestMultiplier();
        // A price safe with a +5% booster but too low for +25%: halfway between the two lowest safe prices.
        long limit5 = ShopValidator.minimumPrice(unit, Boosts.guard(best, 5));
        long limit25 = ShopValidator.minimumPrice(unit, Boosts.guard(best, 25));
        long price = (limit5 + limit25) / 2;
        e2e.expect(limit5 < price && price < limit25, "a price between the two limits: " + limit5 + " < " + price + " < " + limit25);
        String cheapShop = shopOriginal.replace(diamondLine, "diamond: {price: " + price + ", max: 256}");
        ShopFeature shop = e2e.feature(ShopFeature.class);
        try {
            e2e.step("a diamond price that a +25% booster would let anyone sell back for a profit is refused");
            Files.writeString(shopFile, cheapShop, StandardCharsets.UTF_8);
            List<String> refused = reply(e2e, "sift reload", "Nothing was reloaded");
            e2e.eventually(() -> contains(refused, "+25% sell booster"), "the problem names the booster: " + refused);
            e2e.expect(shop.offers().price("minecraft:diamond").equals(OptionalLong.of(1000)), "the shop keeps $1,000: "
                + shop.offers().price("minecraft:diamond"));

            e2e.step("with boosters capped at +5% the same price is safe, and both files apply together");
            Files.writeString(boostersFile, boostersOriginal.replace("max-percent: 25", "max-percent: 5"), StandardCharsets.UTF_8);
            reply(e2e, "sift reload", "Reloaded");
            e2e.expect(shop.offers().price("minecraft:diamond").equals(OptionalLong.of(price)), "the shop sells diamonds for " + money(price)
                + ": " + shop.offers().price("minecraft:diamond"));
            e2e.expect(boosters(e2e).maxPercent() == 5 && worth(e2e).current().highestBoost() == 5, "boosters are capped at +5%");
            reply(e2e, "sift booster start 10 30m", "The percent must be from 1 to 5");

            e2e.step("raising the cap again while the shop is that cheap is refused");
            Files.writeString(boostersFile, boostersOriginal, StandardCharsets.UTF_8);
            List<String> raise = reply(e2e, "sift reload", "Nothing was reloaded");
            e2e.eventually(() -> contains(raise, "+25% sell booster"), "the problem names the booster: " + raise);
            e2e.expect(boosters(e2e).maxPercent() == 5, "the cap stays at +5%");
        } finally {
            Files.writeString(shopFile, shopOriginal, StandardCharsets.UTF_8);
            Files.writeString(boostersFile, boostersOriginal, StandardCharsets.UTF_8);
            reply(e2e, "sift reload", "Reloaded");
        }
        e2e.expect(shop.offers().price("minecraft:diamond").equals(OptionalLong.of(1000)) && boosters(e2e).maxPercent() == 25,
            "everything is back to normal");
    }

    // ------------------------------------------------------------------ a paid booster above the limit

    /**
     * A store booster bought for more than {@code sell.max-percent} (the owner lowered it) is still delivered, pays the
     * limit, and everything players see says what it pays; raising the limit lets it pay what was bought. In game,
     * staff may look store purchases up but never deliver or revoke them.
     */
    static void capped(E2E e2e) throws Exception {
        clearBoosters(e2e);
        Path boostersFile = e2e.services().plugin().getDataFolder().toPath().resolve("features/boosters.yml");
        String original = Files.readString(boostersFile, StandardCharsets.UTF_8);
        e2e.expect(original.contains("max-percent: 25"), "features/boosters.yml allows +25%");
        long unit = diamondWorth(e2e);
        String name = e2e.name("BoostCap");
        Bot buyer = e2e.bot(name);
        String ref = "e2e-bcap-" + UUID.randomUUID().toString().substring(0, 8);
        PermissionAttachment[] staff = new PermissionAttachment[1];
        try {
            e2e.step("with max-percent lowered to 10, a +15% store booster is still delivered and staff are told");
            Files.writeString(boostersFile, original.replace("max-percent: 25", "max-percent: 10"), StandardCharsets.UTF_8);
            reply(e2e, "sift reload", "Reloaded");
            e2e.expect(boosters(e2e).maxPercent() == 10, "boosters are capped at +10%");
            buyer.clearLogs();
            List<String> lines = reply(e2e, "sift store booster " + name + " sell 15 30m " + ref, "Delivered a +15% sell booster for 30m to " + name);
            e2e.eventually(() -> contains(lines, "It was bought for +15% but pays +10%"), "the console is told it is capped: " + lines);

            e2e.step("everything players see says +10%, what sales pay");
            e2e.eventually(() -> buyer.chatContains(name + " started a +10% sell booster for 30 minutes. Thank you!"),
                "the announcement says +10%: " + buyer.chat());
            e2e.eventually(() -> buyer.chatContains("Your store purchase arrived: a +10% sell booster for everyone online"),
                "the buyer is told +10%: " + buyer.chat());
            e2e.expect(!buyer.chatContains("+15% sell booster"), "nobody is told +15%: " + buyer.chat());
            e2e.eventually(() -> buyer.bossBar("+10% sell booster from " + name) != null, "the bar says +10%: " + buyer.bossBars());
            e2e.expect(boosters(e2e).percent() == 10 && "10".equals(placeholder(e2e, "booster_percent")),
                "+" + boosters(e2e).percent() + "%, placeholder " + placeholder(e2e, "booster_percent"));
            e2e.expect(boosters(e2e).status(ref).map(ServerBoosters.Status::percent).orElse(-1) == 10, "/purchases sees +10%");
            reply(e2e, "sift booster list", "(bought for +15%, capped by sell.max-percent)");
            sellDiamonds(e2e, "BoostCapS", unit * 10 * 110 / 100, "incl. +10% booster.");

            e2e.step("raising the limit again lets it pay what was bought");
            Files.writeString(boostersFile, original, StandardCharsets.UTF_8);
            reply(e2e, "sift reload", "Reloaded");
            e2e.eventually(() -> boosters(e2e).percent() == 15, "+15% now: " + boosters(e2e).percent());
            e2e.eventually(() -> buyer.bossBar("+15% sell booster from " + name) != null, "the bar follows: " + buyer.bossBars());

            e2e.step("in game, staff can look a purchase up but never deliver or revoke one");
            staff[0] = e2e.onPlayer(name, () -> {
                PermissionAttachment attachment = e2e.player(name).addAttachment(harness(), "siftcore.admin", true);
                attachment.setPermission("siftcore.admin.store", true);
                return attachment;
            });
            e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission("siftcore.admin.store")), "the permission is set");
            buyer.clearLogs();
            e2e.sleep(600);
            buyer.command("sift store check " + ref);
            e2e.eventually(() -> buyer.chatContains(ref + " gave a +15% sell booster for 30m to " + name),
                "the lookup works in game: " + buyer.chat());
            long before = e2e.money(name);
            buyer.command("sift store booster " + name + " sell 20 30m " + ref + "-ig");
            buyer.command("sift store money " + name + " 5000 " + ref + "-ig2");
            buyer.command("sift store revoke " + ref + " refund");
            e2e.sleep(2_000);
            reply(e2e, "sift store check " + ref + "-ig", "Nothing was delivered under " + ref + "-ig");
            reply(e2e, "sift store check " + ref + "-ig2", "Nothing was delivered under " + ref + "-ig2");
            e2e.expect(e2e.money(name) == before, "no money from an in-game store command");
            e2e.expect(boosters(e2e).status(ref).map(ServerBoosters.Status::running).orElse(false) && "0".equals(placeholder(e2e, "booster_queue")),
                "no in-game booster, and the in-game revoke changed nothing");
            reply(e2e, "sift store revoke " + ref + " refund", "The running booster was ended");
            e2e.eventually(() -> boosters(e2e).percent() == 0, "the console can revoke it");
        } finally {
            if (staff[0] != null) {
                e2e.onPlayer(name, () -> {
                    staff[0].remove();
                    return null;
                });
            }
            if (!Files.readString(boostersFile, StandardCharsets.UTF_8).equals(original)) {
                Files.writeString(boostersFile, original, StandardCharsets.UTF_8);
                reply(e2e, "sift reload", "Reloaded");
            }
            clearBoosters(e2e);
        }
        e2e.expect(boosters(e2e).maxPercent() == 25, "the limit is back to +25%");
    }

    // ------------------------------------------------------------------ restart

    private static Path persistFile() {
        return harness().getDataFolder().toPath().resolve("boosters-persist.txt");
    }

    /** Leaves a +3% store booster running for {@link #persistCheck} (after a restart, or right away). */
    static void persistSetup(E2E e2e) throws Exception {
        clearBoosters(e2e);
        String ref = "e2e-bpersist-" + UUID.randomUUID().toString().substring(0, 8);
        reply(e2e, "sift store booster console sell 3 2h " + ref, "Delivered a +3% sell booster for 2h to the server");
        e2e.eventually(() -> boosters(e2e).status(ref).map(ServerBoosters.Status::running).orElse(false), "it runs");
        e2e.sleep(1_500);
        Duration left = boosters(e2e).status(ref).orElseThrow().left();
        Files.createDirectories(persistFile().getParent());
        Files.writeString(persistFile(), ref + " " + System.currentTimeMillis() + " " + left.toMillis() + " "
            + ManagementFactory.getRuntimeMXBean().getStartTime(), StandardCharsets.UTF_8);
        e2e.log("a +3% booster (" + ref + ") runs with " + left.toSeconds() + "s left; restart and run boosters-persist-check");
    }

    /** Finds the booster {@link #persistSetup} left: still running, having lost only the time the server ran. */
    static void persistCheck(E2E e2e) throws Exception {
        e2e.expect(Files.exists(persistFile()), "a persisted booster exists (run boosters-persist-setup first)");
        String[] parts = Files.readString(persistFile(), StandardCharsets.UTF_8).strip().split(" ");
        String ref = parts[0];
        long at = Long.parseLong(parts[1]);
        long leftThen = Long.parseLong(parts[2]);
        long jvm = Long.parseLong(parts[3]);
        try {
            Optional<ServerBoosters.Status> status = boosters(e2e).status(ref);
            e2e.expect(status.isPresent() && status.get().running() && status.get().percent() == 3,
                "the +3% booster " + ref + " runs again: " + status);
            long lost = leftThen - status.get().left().toMillis();
            long wall = System.currentTimeMillis() - at;
            e2e.expect(lost >= -1_500, "its time never goes back (lost " + lost + " ms)");
            e2e.expect(lost <= wall + 1_500, "it lost at most the time that passed (" + lost + " of " + wall + " ms)");
            boolean restarted = ManagementFactory.getRuntimeMXBean().getStartTime() != jvm;
            if (restarted) {
                e2e.expect(lost < wall - 5_000, "its time only counted while the server ran: lost " + lost + " ms in " + wall
                    + " ms with a restart in between");
                e2e.log("restarted: the booster lost " + lost / 1000 + "s of the " + wall / 1000 + "s since the setup");
            } else {
                e2e.log("no restart since the setup: the booster lost " + lost / 1000 + "s of " + wall / 1000 + "s");
            }
            e2e.expect("3".equals(placeholder(e2e, "booster_percent")), "%siftcore_booster_percent% is 3");
            e2e.step("revoking it after the restart ends it");
            reply(e2e, "sift store revoke " + ref + " e2e", "The running booster was ended");
            e2e.eventually(() -> boosters(e2e).percent() == 0, "no booster runs");
        } finally {
            Files.deleteIfExists(persistFile());
            clearBoosters(e2e);
        }
    }

    // ------------------------------------------------------------------ /fly at spawn

    private static boolean allowFlight(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> e2e.player(name).getAllowFlight());
    }

    private static double health(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> e2e.player(name).getHealth());
    }

    private static void heal(E2E e2e, String name) {
        e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            player.setFallDistance(0);
            return null;
        });
    }

    private static void teleport(E2E e2e, Bot bot, Location to) {
        e2e.onPlayer(bot.name, () -> e2e.player(bot.name).teleportAsync(to));
        e2e.eventually(() -> Math.abs(bot.x() - to.getX()) < 0.5 && Math.abs(bot.y() - to.getY()) < 0.5 && Math.abs(bot.z() - to.getZ()) < 0.5,
            bot.name + " is at " + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ());
        e2e.sleep(300);
    }

    /** Falls straight down {@code blocks} blocks like a client in free fall, then lands. */
    private static void fall(E2E e2e, Bot bot, int blocks) {
        for (int i = 0; i < blocks; i++) {
            bot.moveBy(0, -1, 0, 0);
            e2e.sleep(60);
        }
        bot.move(0, 0);
        e2e.sleep(800);
    }

    /** Asks for /fly and waits for the answer. */
    private static void fly(E2E e2e, Bot bot, String answer) {
        bot.clearLogs();
        e2e.sleep(600);
        bot.command("fly");
        if (!Bot.await(() -> bot.anyFeedbackContains(answer), 10_000)) {
            throw new E2E.Failure("expected /fly answers '" + answer + "': " + bot.chat() + " " + bot.actionBar());
        }
    }

    static void spawnFly(E2E e2e) throws Exception {
        SpawnArea area = e2e.spawnArea();
        if (area == null) {
            e2e.log("SiftCore runs without the spawn feature; skipped");
            return;
        }
        String name = e2e.name("Flyer");
        Bot bot = e2e.botAtSpawn(name);
        Location start = e2e.onPlayer(name, () -> e2e.player(name).getLocation());
        e2e.expect(area.contains(start), "the bot arrived inside the protected spawn");
        PermissionAttachment[] attachment = new PermissionAttachment[1];
        try {
            e2e.step("without siftcore.spawn.fly there is no /fly");
            bot.clearLogs();
            bot.command("fly");
            e2e.sleep(1_200);
            e2e.expect(!allowFlight(e2e, name) && !bot.chatContains("Flight on"), "no flight without the permission: " + bot.chat());

            e2e.step("with it, /fly turns flying on and off inside spawn");
            attachment[0] = e2e.onPlayer(name, () -> e2e.player(name).addAttachment(harness(), "siftcore.spawn.fly", true));
            e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission("siftcore.spawn.fly")), "the permission is set");
            fly(e2e, bot, "Flight on.");
            e2e.expect(allowFlight(e2e, name), "the player may fly");
            fly(e2e, bot, "Flight off.");
            e2e.expect(!allowFlight(e2e, name), "flying is off again");

            e2e.step("refused in combat, and a combat tag ends it");
            e2e.console("combat tag " + name + " 60s");
            try {
                fly(e2e, bot, "You can't fly in combat.");
                e2e.expect(!allowFlight(e2e, name), "no flight in combat");
            } finally {
                e2e.console("combat untag " + name);
            }
            fly(e2e, bot, "Flight on.");
            bot.clearLogs();
            e2e.console("combat tag " + name + " 60s");
            try {
                e2e.eventually(() -> bot.anyFeedbackContains("You're in combat, so flight is off."), "combat ends flight: " + bot.chat());
                e2e.expect(!allowFlight(e2e, name), "flying is off");
            } finally {
                e2e.console("combat untag " + name);
            }

            e2e.step("losing the permission ends it");
            fly(e2e, bot, "Flight on.");
            bot.clearLogs();
            e2e.onPlayer(name, () -> {
                attachment[0].remove();
                return null;
            });
            e2e.eventually(() -> bot.anyFeedbackContains("Flight is off.") && !allowFlight(e2e, name), "flight ends without the permission: " + bot.chat());
            attachment[0] = e2e.onPlayer(name, () -> e2e.player(name).addAttachment(harness(), "siftcore.spawn.fly", true));

            e2e.step("above the height limit flight drops the player (it stays allowed lower down)");
            String spawnConfig = Files.readString(e2e.services().plugin().getDataFolder().toPath().resolve("features/spawn.yml"),
                StandardCharsets.UTF_8);
            e2e.expect(spawnConfig.contains("max-height: 48"), "features/spawn.yml lets players fly 48 blocks above the spawn point");
            Location spawnPoint = e2e.feature(SpawnFeature.class).location();
            Location tooHigh = start.clone();
            tooHigh.setY(spawnPoint.getY() + 48 + 6);
            e2e.expect(area.contains(tooHigh), "straight above the spawn is still inside the area (a flat radius)");
            fly(e2e, bot, "Flight on.");
            e2e.onPlayer(name, () -> {
                e2e.player(name).setFlying(true);
                return null;
            });
            bot.clearLogs();
            teleport(e2e, bot, tooHigh);
            e2e.eventually(() -> bot.anyFeedbackContains("You can't fly higher here."), "the limit is enforced: " + bot.chat() + " "
                + bot.actionBar());
            e2e.eventually(() -> !e2e.onPlayer(name, () -> e2e.player(name).isFlying()), "no longer flying up there");
            e2e.expect(allowFlight(e2e, name), "flight is still allowed lower down");
            teleport(e2e, bot, start);
            fly(e2e, bot, "Flight off.");

            e2e.step("/fly is refused above the height limit");
            teleport(e2e, bot, tooHigh);
            fly(e2e, bot, "You're too high to fly here.");
            e2e.expect(!allowFlight(e2e, name), "no flight up there");
            teleport(e2e, bot, start);

            e2e.step("flying out of spawn turns it off, and the landing doesn't hurt, however long the fall takes");
            heal(e2e, name);
            fly(e2e, bot, "Flight on.");
            Location up = start.clone().add(0, 12, 0);
            e2e.onPlayer(name, () -> {
                e2e.player(name).setFlying(true);
                return null;
            });
            teleport(e2e, bot, up);
            Location column = null;
            for (int distance = 4; distance <= 4_096 && column == null; distance += 2) {
                Location candidate = start.clone().add(distance / Math.sqrt(2), 0, distance / Math.sqrt(2));
                if (!area.contains(candidate) && !area.contains(candidate.clone().add(2, 0, 2))) {
                    column = candidate;
                }
            }
            e2e.expect(column != null, "open ground near the spawn");
            Location ground = e2e.ground(start.getWorld(), column.getBlockX(), column.getBlockZ(), start.getYaw());
            Location outside = ground.clone().add(0, 12, 0);
            bot.clearLogs();
            teleport(e2e, bot, outside);
            e2e.eventually(() -> bot.anyFeedbackContains("You left spawn, so flight is off. Your landing won't hurt."), "flight ends outside: " + bot.chat());
            e2e.expect(!allowFlight(e2e, name), "no flight outside spawn");
            double full = health(e2e, name);
            // Longer in the air than the old fixed 10 second protection lasted.
            e2e.sleep(12_000);
            fall(e2e, bot, 12);
            e2e.eventually(() -> Math.abs(bot.y() - ground.getY()) < 0.01, "the bot landed");
            e2e.expect(health(e2e, name) == full, "the 12 block fall 12 seconds later did no damage (health " + health(e2e, name)
                + " of " + full + ")");

            e2e.step("the same fall without the soft landing hurts (so the fall above was real)");
            teleport(e2e, bot, outside);
            fall(e2e, bot, 12);
            e2e.eventually(() -> health(e2e, name) < full, "the second fall hurt (health " + health(e2e, name) + " of " + full + ")");
            heal(e2e, name);

            e2e.step("outside spawn /fly is refused");
            fly(e2e, bot, "You can only fly inside the spawn area.");
            e2e.expect(!allowFlight(e2e, name), "no flight outside spawn");
        } finally {
            if (attachment[0] != null) {
                e2e.onPlayer(name, () -> {
                    attachment[0].remove();
                    return null;
                });
            }
        }
    }

    // ------------------------------------------------------------------ joining a full server

    private static void setMaxPlayers(E2E e2e, int max) {
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        e2e.services().scheduler().global(() -> {
            Bukkit.setMaxPlayers(max);
            done.complete(null);
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("could not set max players: " + e);
        }
    }

    static void joinFull(E2E e2e) {
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) {
            e2e.log("LuckPerms is not installed: nobody may join a full server, which the unit tests check; skipped");
            return;
        }
        String baronName = e2e.name("Baron");
        String staffName = e2e.name("Staffer");
        String regularName = e2e.name("NoRoom");
        UUID baronId = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(baronName);
        UUID staffId = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(staffName);
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger logger = e2e.services().plugin().getLogger();
        int max = Bukkit.getMaxPlayers();
        logger.addHandler(handler);
        try {
            e2e.console("lp user " + baronId + " permission set siftcore.join.full true");
            e2e.console("lp user " + staffId + " permission set siftcore.join.full.staff true");
            e2e.sleep(1_500);
            setMaxPlayers(e2e, Bukkit.getOnlinePlayers().size());

            e2e.step("a player without the node can't join the full server");
            Bot regular = new Bot(regularName);
            try {
                regular.connect(Bukkit.getPort());
                e2e.eventually(regular::disconnected, 15_000, regularName + " is turned away");
                e2e.expect(regular.disconnectReason().toLowerCase(Locale.ROOT).contains("full"), "the server is full: " + regular.disconnectReason());
                e2e.expect(Bukkit.getPlayerExact(regularName) == null, regularName + " is not online");
            } finally {
                regular.quit();
            }

            e2e.step("siftcore.join.full (read from LuckPerms at login) lets a player in, and it is logged");
            e2e.botAtSpawn(baronName);
            e2e.expect(Bukkit.getOnlinePlayers().size() > Bukkit.getMaxPlayers(), "more players online than the limit");
            e2e.eventually(() -> logged.stream().anyMatch(line -> line.contains("Let " + baronName + " join the full server")),
                "the join is logged: " + logged);
            e2e.expect(logged.stream().filter(line -> line.contains("Let " + baronName)).count() == 1, "logged once: " + logged);

            e2e.step("siftcore.join.full.staff does too");
            e2e.botAtSpawn(staffName);
            e2e.eventually(() -> logged.stream().anyMatch(line -> line.contains("Let " + staffName + " join the full server")
                && line.contains("siftcore.join.full.staff)")), "the staff join is logged with its node: " + logged);
        } finally {
            logger.removeHandler(handler);
            setMaxPlayers(e2e, max);
            e2e.console("lp user " + baronId + " permission unset siftcore.join.full");
            e2e.console("lp user " + staffId + " permission unset siftcore.join.full.staff");
        }
    }

    // ------------------------------------------------------------------ /purchases

    static void purchases(E2E e2e) {
        clearBoosters(e2e);
        String name = e2e.name("Buyer");
        String otherName = e2e.name("Other");
        Bot bot = e2e.bot(name);
        e2e.bot(otherName);
        String ref = "e2e-pur-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            e2e.step("a player sees their purchases: a booster that runs, money that was taken back");
            reply(e2e, "sift store money " + name + " 2500 " + ref + "-m", "Delivered $2,500 to " + name);
            reply(e2e, "sift store booster " + name + " sell 5 30m " + ref + "-b", "Delivered a +5% sell booster for 30m to " + name);
            reply(e2e, "sift store revoke " + ref + "-m refund", "Revoked " + ref + "-m");
            reply(e2e, "sift store money " + otherName + " 999 " + ref + "-o", "Delivered $999 to " + otherName);
            bot.clearLogs();
            e2e.sleep(600);
            bot.command("purchases");
            Bot.SeenDialog dialog = e2e.dialog(bot, "Your purchases");
            String body = dialog.bodyText();
            e2e.expect(body.contains("2 purchases, page 1 of 1"), "two purchases: " + dialog.body());
            e2e.expect(body.contains("a +5% sell booster for 30m") && body.contains("running now"), "the booster runs: " + dialog.body());
            e2e.expect(body.contains("$2,500") && body.contains("taken back (refund)"), "the money was taken back: " + dialog.body());
            e2e.expect(body.contains(ref.substring(0, 8)), "the reference (shortened): " + dialog.body());
            e2e.expect(!body.contains("$999"), "nobody else's purchases: " + dialog.body());
            e2e.expect(dialog.button("Next page") == null, "one page only");

            e2e.step("revoking the booster shows up too");
            reply(e2e, "sift store revoke " + ref + "-b refund", "The running booster was ended");
            e2e.sleep(600);
            bot.command("purchases");
            e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains("taken back (refund, booster ended early)"),
                "the booster was taken back: " + (bot.dialog() == null ? "none" : bot.dialog().body()));

            e2e.step("more than a page has Next and Previous");
            for (int i = 1; i <= 5; i++) {
                reply(e2e, "sift store shards " + name + " " + i + " " + ref + "-s" + i, "Delivered");
            }
            e2e.sleep(600);
            bot.clearLogs();
            bot.command("purchases");
            e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains("7 purchases, page 1 of 2"),
                "seven purchases on two pages: " + (bot.dialog() == null ? "none" : bot.dialog().body()));
            e2e.click(bot, "Next page");
            e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains("page 2 of 2") && bot.dialog().button("Previous page") != null,
                "page two: " + (bot.dialog() == null ? "none" : bot.dialog().body()));
            e2e.click(bot, "Previous page");
            e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains("page 1 of 2"), "back to page one");

            e2e.step("players can't look at someone else's purchases");
            int dialogs = bot.dialogs().size();
            bot.clearMessages();
            e2e.sleep(600);
            bot.command("purchases " + otherName);
            e2e.sleep(1_500);
            e2e.expect(bot.dialogs().stream().skip(dialogs).noneMatch(seen -> seen.title().contains(otherName)
                || seen.bodyText().contains("$999")), "no dialog with " + otherName + "'s purchases");

            e2e.step("staff (here the console) can");
            reply(e2e, "purchases " + otherName, "Store purchases of " + otherName + " (1)");
            reply(e2e, "purchases " + e2e.uuid(name), "Store purchases of " + name + " (7)");
        } finally {
            clearBoosters(e2e);
        }
    }
}
