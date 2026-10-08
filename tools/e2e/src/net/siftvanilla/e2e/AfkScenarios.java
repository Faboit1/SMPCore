package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.feature.afk.AfkFeature;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * AFK detection, the AFK kick, the AFK zone (rewards, one account per connection, the daily limit) and the shard
 * shop, with real clients. Every scenario shortens the timings in features/afk.yml for its run and restores the file
 * afterwards. All bots connect from 127.0.0.1, so they share one connection for the AFK zone's alt rule.
 */
final class AfkScenarios {

    private static final String AFK = "features/afk.yml";

    private AfkScenarios() {
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
        list.add(of("afk-detect", AfkScenarios::detect));
        list.add(of("afk-manual", AfkScenarios::manual));
        list.add(of("afk-kick", AfkScenarios::kick));
        list.add(of("afk-zone", AfkScenarios::zone));
        list.add(of("afk-zone-cap", AfkScenarios::zoneCap));
        list.add(of("shard-shop", AfkScenarios::shardShop));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static AfkFeature afk(E2E e2e) {
        return e2e.feature(AfkFeature.class);
    }

    private static boolean isAfk(E2E e2e, String name) {
        return afk(e2e).status().afk(e2e.uuid(name));
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        String value = e2e.services().placeholders().resolve(e2e.player(name), placeholder);
        return value == null ? "" : value;
    }

    /** Runs {@code body} with text in features/afk.yml replaced, then restores the file and reloads. */
    private static void withAfkConfig(E2E e2e, Map<String, String> replacements, Body body) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(AFK);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), AFK + " contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        Files.writeString(path, changed, StandardCharsets.UTF_8);
        try {
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed " + AFK + " reloads: " + reload);
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /** Turns the bot's view twice (a real look around). */
    private static void lookAround(Bot bot, float first) {
        bot.move(0, first);
        bot.move(0, -first * 1.7f);
    }

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

    /** Sets the AFK zone to a small box on open ground a few blocks from the bot, and returns its centre column. */
    private static Location zoneNear(E2E e2e, String name) {
        Location here = e2e.onPlayer(name, () -> e2e.player(name).getLocation());
        Location ground = e2e.ground(here.getWorld(), here.getBlockX() + 8, here.getBlockZ(), 0f);
        int x = ground.getBlockX();
        int y = ground.getBlockY();
        int z = ground.getBlockZ();
        List<String> out = e2e.consoleOutput("afkzone set " + here.getWorld().getName() + " " + (x - 2) + " " + (y - 3) + " " + (z - 2)
            + " " + (x + 2) + " " + (y + 5) + " " + (z + 2));
        e2e.expect(String.join(" ", out).contains("The AFK zone is now"), "the zone is set: " + out);
        return ground;
    }

    // ------------------------------------------------------------------ detection

    static void detect(E2E e2e) throws Exception {
        withAfkConfig(e2e, Map.of("afk-after: 5m", "afk-after: 10s", "after: 30m", "after: 10m"), unused -> {
            String name = e2e.name("AfkIdle");
            Bot bot = e2e.bot(name);
            UUID id = e2e.uuid(name);
            e2e.step("standing still makes the player AFK");
            bot.clearLogs();
            e2e.expect(!isAfk(e2e, name), "not AFK right after joining");
            e2e.expect(placeholder(e2e, name, "afk_status").isEmpty(), "afk_status is empty while active");
            e2e.eventually(() -> isAfk(e2e, name), 16_000, "AFK after 10s without activity");
            e2e.eventually(() -> bot.actionBarContains("You are now AFK."), "the AFK notice on the action bar: " + bot.actionBar());
            e2e.expect(placeholder(e2e, name, "afk_status").equals("AFK"), "afk_status says AFK");
            e2e.expect(!placeholder(e2e, name, "afk_time").isEmpty(), "afk_time has a value");
            e2e.expect(Integer.parseInt(placeholder(e2e, name, "afk_count")) >= 1, "afk_count counts the player");
            List<String> list = e2e.consoleOutput("afk list");
            e2e.expect(String.join(" ", list).contains(name), "/afk list names the player: " + list);

            e2e.step("looking around brings the player back");
            bot.clearLogs();
            lookAround(bot, 30f);
            e2e.eventually(() -> !isAfk(e2e, name), "back after looking around");
            e2e.eventually(() -> bot.actionBarContains("Welcome back."), "the welcome back notice: " + bot.actionBar());
            e2e.expect(placeholder(e2e, name, "afk_status").isEmpty(), "afk_status is empty again");

            e2e.step("a jump macro does not keep the player active");
            long start = System.currentTimeMillis();
            while (!isAfk(e2e, name) && System.currentTimeMillis() - start < 16_000) {
                bot.moveBy(0, 0.6, 0, 0);
                e2e.sleep(150);
                bot.moveBy(0, -0.6, 0, 0);
                e2e.sleep(150);
            }
            e2e.expect(isAfk(e2e, name), "AFK although the bot kept jumping in place");
            e2e.expect(System.currentTimeMillis() - start >= 8_000, "not before the AFK time");

            e2e.step("chatting brings the player back; repeating the same line does not keep them");
            bot.chat("anyone around?");
            e2e.eventually(() -> !isAfk(e2e, name), "back after chatting");
            start = System.currentTimeMillis();
            while (!isAfk(e2e, name) && System.currentTimeMillis() - start < 16_000) {
                e2e.sleep(2_500);
                bot.chat("anyone around?");
            }
            e2e.expect(isAfk(e2e, name), "AFK although the same line was sent again and again");

            e2e.step("the same command over and over does not keep the player active either");
            bot.command("shards");
            e2e.eventually(() -> !isAfk(e2e, name), "a new command counts");
            start = System.currentTimeMillis();
            while (!isAfk(e2e, name) && System.currentTimeMillis() - start < 16_000) {
                e2e.sleep(2_500);
                bot.command("shards");
            }
            e2e.expect(isAfk(e2e, name), "AFK although /shards was repeated");

            e2e.step("pacing back and forth does not keep the player active");
            lookAround(bot, 25f);
            e2e.eventually(() -> !isAfk(e2e, name), "back after looking around");
            start = System.currentTimeMillis();
            while (!isAfk(e2e, name) && System.currentTimeMillis() - start < 18_000) {
                bot.move(1.0, 0);
                e2e.sleep(200);
                bot.move(-1.0, 0);
                e2e.sleep(200);
            }
            e2e.expect(isAfk(e2e, name), "AFK although the bot paced back and forth");
            e2e.expect(afk(e2e).status().afk(id), "the AfkStatus link agrees");
        });
    }

    static void manual(E2E e2e) throws Exception {
        String name = e2e.name("AfkCmd");
        Bot bot = e2e.bot(name);
        e2e.step("/afk marks the player AFK at once");
        bot.clearLogs();
        bot.command("afk");
        e2e.eventually(() -> isAfk(e2e, name), "AFK right after /afk");
        e2e.eventually(() -> bot.actionBarContains("You are now AFK. Move around or type to come back."), "the notice: " + bot.actionBar());
        e2e.step("moving during the short grace keeps the AFK status");
        lookAround(bot, 40f);
        e2e.sleep(600);
        e2e.expect(isAfk(e2e, name), "still AFK within the grace");
        e2e.step("after the grace, moving comes back");
        e2e.sleep(3_000);
        bot.clearLogs();
        lookAround(bot, -35f);
        e2e.eventually(() -> !isAfk(e2e, name), "back after the grace");
        e2e.eventually(() -> bot.actionBarContains("Welcome back."), "welcome back: " + bot.actionBar());
        e2e.step("/afk while AFK comes back at once");
        bot.command("afk");
        e2e.eventually(() -> isAfk(e2e, name), "AFK again");
        e2e.sleep(300);
        bot.command("afk");
        e2e.eventually(() -> !isAfk(e2e, name), "/afk again ends it, grace or not");
    }

    static void kick(E2E e2e) throws Exception {
        withAfkConfig(e2e, Map.of("afk-after: 5m", "afk-after: 10s", "after: 30m", "after: 10s", "warn-before: 1m", "warn-before: 5s"), unused -> {
            String name = e2e.name("AfkKick");
            String staff = e2e.name("AfkStaff");
            Bot bot = e2e.bot(name);
            Bot op = e2e.bot(staff);
            e2e.console("op " + staff);
            try {
                e2e.step("an AFK player is warned, then kicked");
                bot.clearLogs();
                e2e.eventually(() -> isAfk(e2e, name), 16_000, "AFK first");
                e2e.eventually(() -> bot.chatContains("You'll be kicked for being AFK in"), 12_000, "a warning in chat: " + bot.chat());
                e2e.eventually(() -> bot.disconnected(), 12_000, "kicked");
                e2e.expect(bot.disconnectReason().contains("You were AFK for too long"), "the kick reason: " + bot.disconnectReason());
                e2e.step("players with siftcore.afk.bypass-kick stay");
                e2e.expect(isAfk(e2e, staff), "the operator is AFK too");
                e2e.sleep(3_000);
                e2e.expect(!op.disconnected() && Bukkit.getPlayerExact(staff) != null, "but never kicked");
            } finally {
                e2e.console("deop " + staff);
            }
        });
    }

    // ------------------------------------------------------------------ the zone

    static void zone(E2E e2e) throws Exception {
        withAfkConfig(e2e, Map.of("interval: 60s", "interval: 5s", "status-every: 2s", "status-every: 1s", "afk-after: 5m", "afk-after: 10s",
            "after: 30m", "after: 10s", "warn-before: 1m", "warn-before: 3s"), unused -> {
            String first = e2e.name("ZoneA");
            String second = e2e.name("ZoneB");
            Bot a = e2e.bot(first);
            Bot b = e2e.bot(second);
            // The second account waits outside the zone for a while: the kick bypass keeps it online meanwhile.
            e2e.console("op " + second);
            try {
                zoneNear(e2e, first);
                AfkFeature afk = afk(e2e);
                e2e.step("/afkzone teleports into the zone after the warmup");
                a.clearLogs();
                a.command("afkzone");
                e2e.eventually(() -> afk.zone().inside(e2e.uuid(first)), 12_000, "inside the zone");
                e2e.eventually(() -> a.actionBarContains("AFK zone"), "the zone on the action bar: " + a.actionBar());
                e2e.step("shards every interval of continuous presence");
                e2e.eventually(() -> e2e.shards(first) >= 1, 10_000, "a first shard");
                e2e.eventually(() -> e2e.shards(first) >= 2, 8_000, "a second shard");
                e2e.eventually(() -> a.actionBarContains("next shard in"), 5_000, "the countdown: " + a.actionBar());
                String next = placeholder(e2e, first, "afk_zone_next");
                e2e.expect(next.matches("\\d+"), "afk_zone_next is a number of seconds: '" + next + "'");
                e2e.expect(Integer.parseInt(placeholder(e2e, first, "afk_zone_today")) >= 2, "afk_zone_today counts");
                var rows = e2e.services().ledger().history(e2e.uuid(first), 3, 0).join();
                e2e.expect(!rows.isEmpty() && rows.getFirst().kind().equals("afk_reward"), "an afk_reward ledger row: " + rows);
                List<String> info = e2e.consoleOutput("afkzone info");
                e2e.expect(String.join(" ", info).contains("Set in game"), "/afkzone info: " + info);

                e2e.step("only one account per connection earns");
                b.clearLogs();
                b.command("afkzone");
                e2e.eventually(() -> afk.zone().inside(e2e.uuid(second)), 12_000, "the second account is inside");
                e2e.eventually(() -> b.actionBarContains("another account on your connection"), 6_000, "told why: " + b.actionBar());
                long before = e2e.shards(second);
                e2e.sleep(9_000);
                e2e.expect(e2e.shards(second) == before, "the second account earned nothing while the first was inside");
                e2e.expect(e2e.shards(first) >= 4, "the first kept earning");

                e2e.step("resting AFK in the zone is never kicked");
                e2e.expect(isAfk(e2e, first), "the first account is AFK by now");
                e2e.expect(!a.disconnected(), "and still online after more than the kick time");

                e2e.step("the waiting account takes over when the first leaves");
                a.quit();
                e2e.eventually(() -> Bukkit.getPlayerExact(first) == null, "the first account left");
                e2e.eventually(() -> e2e.shards(second) > before, 10_000, "the second account earns now");

                e2e.step("leaving the zone");
                b.clearLogs();
                Location out = e2e.onPlayer(second, () -> e2e.player(second).getLocation().add(12, 0, 0));
                Location ground = e2e.ground(out.getWorld(), out.getBlockX(), out.getBlockZ(), 0f);
                e2e.player(second).teleportAsync(ground);
                e2e.eventually(() -> !afk.zone().inside(e2e.uuid(second)), 6_000, "outside the zone");
                e2e.eventually(() -> b.actionBarContains("You left the AFK zone."), "told so: " + b.actionBar());
                e2e.expect(placeholder(e2e, second, "afk_zone_next").isEmpty(), "no countdown outside");
            } finally {
                e2e.console("deop " + second);
                e2e.console("afkzone reset");
            }
        });
    }

    static void zoneCap(E2E e2e) throws Exception {
        withAfkConfig(e2e, Map.of("interval: 60s", "interval: 5s", "daily-cap: 0", "daily-cap: 2"), unused -> {
            String name = e2e.name("ZoneCap");
            Bot bot = e2e.bot(name);
            try {
                zoneNear(e2e, name);
                bot.command("afkzone");
                e2e.eventually(() -> afk(e2e).zone().inside(e2e.uuid(name)), 12_000, "inside the zone");
                e2e.step("the daily limit stops the rewards");
                e2e.eventually(() -> e2e.shards(name) == 2, 16_000, "two shards");
                e2e.eventually(() -> bot.chatContains("You earned today's AFK zone limit of 2 shards."), "told in chat: " + bot.chat());
                e2e.sleep(7_000);
                e2e.expect(e2e.shards(name) == 2, "no more after the limit (has " + e2e.shards(name) + ")");
                e2e.eventually(() -> bot.actionBarContains("today's limit of 2 shards reached"), 4_000, "the status says so: " + bot.actionBar());
                e2e.step("the shards page shows today's progress");
                bot.command("menu");
                e2e.dialog(bot, "SiftVanilla");
                e2e.click(bot, "Shards");
                Bot.SeenDialog page = e2e.dialog(bot, "Shards");
                e2e.expect(page.bodyText().contains("Earned there today 2 of 2"), "today's progress: " + page.body());
                e2e.expect(page.bodyText().contains("You are in the AFK zone now."), "where the player is: " + page.body());
                e2e.expect(page.button("Go to the AFK zone") == null, "no teleport button inside the zone: " + page.buttons());
            } finally {
                e2e.console("afkzone reset");
            }
        });
    }

    // ------------------------------------------------------------------ the shard shop

    static void shardShop(E2E e2e) throws Exception {
        String name = e2e.name("ShardShop");
        Bot bot = e2e.bot(name);
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            return null;
        });
        e2e.step("staff give shards; the player is told");
        bot.clearLogs();
        List<String> given = e2e.consoleOutput("shards give " + name + " 1000");
        e2e.expect(String.join(" ", given).contains("Gave " + name + " 1,000 shards"), "the staff receipt: " + given);
        e2e.eventually(() -> bot.chatContains("You received 1,000 shards."), "the player is told: " + bot.chat());
        bot.command("shards");
        e2e.eventually(() -> bot.chatContains("You have 1,000 shards."), "/shards: " + bot.chat());

        e2e.step("the shop lists the crate key and item offers");
        bot.command("shardshop");
        Bot.SeenDialog shop = e2e.dialog(bot, "Shard shop");
        e2e.expect(shop.button("Basic key, 50 shards") != null, "the basic key offer: " + shop.buttons());
        e2e.expect(shop.button("Legendary key, 1,500 shards") != null, "the legendary key offer: " + shop.buttons());
        e2e.expect(shop.button("Totem of Undying, 250 shards") != null, "the totem offer: " + shop.buttons());
        e2e.expect(shop.button("32x Bottle o' Enchanting, 40 shards") != null, "an offer of 32 items says so: " + shop.buttons());
        e2e.expect(shop.bodyText().contains("You have 1,000 shards"), "the balance: " + shop.body());

        e2e.step("the amount slider shows the new total first, then a confirmation for big purchases");
        e2e.click(bot, "Totem of Undying");
        Bot.SeenDialog offer = e2e.dialog(bot, "Buy Totem of Undying");
        e2e.expect(offer.button("Buy 1 for 250 shards") != null, "the buy button names amount and total: " + offer.buttons());
        e2e.click(bot, "Buy 1", Map.of("amount", 2));
        Bot.SeenDialog changed = e2e.dialog(bot, "Buy Totem of Undying");
        e2e.expect(changed.button("Buy 2 for 500 shards") != null, "now 2 for 500: " + changed.buttons());
        e2e.expect(changed.bodyText().contains("The amount changed"), "a note: " + changed.body());
        e2e.expect(e2e.shards(name) == 1000, "nothing bought yet");
        e2e.click(bot, "Buy 2", Map.of("amount", 2));
        Bot.SeenDialog confirm = e2e.dialog(bot, "Confirm purchase");
        e2e.expect(confirm.bodyText().contains("You'll have 500 shards left."), "what is left: " + confirm.body());
        e2e.click(bot, "Buy");
        e2e.eventually(() -> e2e.shards(name) == 500, "charged 500 shards (has " + e2e.shards(name) + ")");
        e2e.eventually(() -> count(e2e, name, Material.TOTEM_OF_UNDYING) == 2, "two totems");
        e2e.eventually(() -> e2e.services().deliveries().count(e2e.uuid(name)) == 0, "nothing left in the claim box");
        e2e.eventually(() -> bot.chatContains("You bought 2x Totem of Undying for 500 shards."), "a receipt: " + bot.chat());
        e2e.eventually(() -> {
            var rows = e2e.services().ledger().history(e2e.uuid(name), 3, 0).join();
            return !rows.isEmpty() && rows.getFirst().kind().equals("shard_shop") && rows.getFirst().delta() == -500;
        }, "a shard_shop ledger row of -500");

        e2e.step("not enough shards is refused in place");
        bot.command("shardshop");
        e2e.dialog(bot, "Shard shop");
        e2e.click(bot, "Shulker Box");
        e2e.dialog(bot, "Buy Shulker Box");
        e2e.click(bot, "Buy 1", Map.of("amount", 2));
        e2e.click(bot, "Buy 2", Map.of("amount", 2));
        Bot.SeenDialog refused = e2e.dialog(bot, "Buy Shulker Box");
        e2e.expect(refused.bodyText().contains("That costs 600 shards and you have 500."), "the refusal: " + refused.body());
        e2e.expect(e2e.shards(name) == 500, "nothing charged");
        bot.command("menu");
        e2e.dialog(bot, "SiftVanilla");

        e2e.step("items that don't fit go to the claim box");
        e2e.onPlayer(name, () -> {
            var inventory = e2e.player(name).getInventory();
            for (int slot = 0; slot < 36; slot++) {
                inventory.setItem(slot, new ItemStack(Material.STONE, 64));
            }
            return null;
        });
        int claims = e2e.services().deliveries().count(e2e.uuid(name));
        bot.clearLogs();
        bot.command("shardshop");
        e2e.dialog(bot, "Shard shop");
        e2e.click(bot, "Bottle o' Enchanting");
        e2e.dialog(bot, "Buy Bottle o' Enchanting");
        e2e.click(bot, "Buy 1", Map.of("amount", 1));
        e2e.eventually(() -> e2e.shards(name) == 460, "charged 40 (has " + e2e.shards(name) + ")");
        e2e.eventually(() -> e2e.services().deliveries().count(e2e.uuid(name)) == claims + 1, "one stack of 32 in the claim box");
        e2e.eventually(() -> bot.chatContains("didn't fit and are waiting in your claim box"), "told so: " + bot.chat());

        e2e.step("a double click buys once");
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().clear();
            return null;
        });
        bot.command("shardshop");
        e2e.dialog(bot, "Shard shop");
        e2e.click(bot, "Golden Apple");
        e2e.dialog(bot, "Buy Golden Apple");
        e2e.expect(bot.clickButton("Buy 1", Map.of("amount", 1)), "first click");
        e2e.expect(bot.clickButton("Buy 1", Map.of("amount", 1)), "second click on the same dialog");
        e2e.eventually(() -> e2e.shards(name) == 340, "charged 120 once (has " + e2e.shards(name) + ")");
        e2e.sleep(1_000);
        e2e.expect(e2e.shards(name) == 340, "not twice");
        e2e.eventually(() -> count(e2e, name, Material.GOLDEN_APPLE) == 8, "eight golden apples");

        e2e.step("staff take, set and look up shards from the console");
        e2e.expect(String.join(" ", e2e.consoleOutput("shards take " + name + " 40")).contains("Took 40 shards"), "take");
        e2e.expect(e2e.shards(name) == 300, "300 left");
        e2e.expect(String.join(" ", e2e.consoleOutput("shards set " + name + " 1234")).contains("1,234"), "set");
        e2e.expect(e2e.shards(name) == 1234, "set to 1,234");
        e2e.expect(String.join(" ", e2e.consoleOutput("shards take " + name + " 99999")).contains("has only 1,234 shards"), "not enough");
        e2e.expect(String.join(" ", e2e.consoleOutput("shards " + name)).contains(name + " has 1,234 shards"), "look up");
        e2e.expect(String.join(" ", e2e.consoleOutput("shards pending")).contains("Key purchases waiting (0)"), "no waiting purchases");

        e2e.step("the shards page of the main menu leads to the shop");
        bot.command("menu");
        e2e.dialog(bot, "SiftVanilla");
        e2e.click(bot, "Shards");
        Bot.SeenDialog page = e2e.dialog(bot, "Shards");
        e2e.expect(page.bodyText().contains("1,234 shards"), "the balance: " + page.body());
        e2e.expect(page.bodyText().contains("AFK zone"), "what the AFK zone pays: " + page.body());
        e2e.expect(page.button("Go to the AFK zone") != null, "a way to the zone: " + page.buttons());
        e2e.click(bot, "Shard shop");
        e2e.dialog(bot, "Shard shop");
        e2e.click(bot, "Back");
        e2e.dialog(bot, "Shards");

        e2e.step("crate keys bought with shards come from the crates feature");
        CrateKeys keys = e2e.feature(CratesFeature.class).keys();
        UUID id = e2e.uuid(name);
        int keysBefore = keys.keys(id, "basic");
        bot.clearLogs();
        bot.command("shardshop");
        e2e.dialog(bot, "Shard shop");
        e2e.click(bot, "Basic key");
        Bot.SeenDialog keyOffer = e2e.dialog(bot, "Buy Basic key");
        e2e.expect(keyOffer.bodyText().contains("Keys you have for this crate " + keysBefore), "the keys the player has: " + keyOffer.body());
        e2e.click(bot, "Buy 1", Map.of("amount", 2));
        e2e.dialog(bot, "Buy Basic key");
        e2e.click(bot, "Buy 2 for 100 shards", Map.of("amount", 2));
        e2e.eventually(() -> keys.keys(id, "basic") == keysBefore + 2, "two basic keys (has " + keys.keys(id, "basic") + ")");
        e2e.eventually(() -> e2e.shards(name) == 1134, "charged 100 shards (has " + e2e.shards(name) + ")");
        e2e.eventually(() -> bot.chatContains("You bought 2x Basic key for 100 shards."), "a receipt: " + bot.chat());
        e2e.eventually(() -> String.join(" ", e2e.consoleOutput("shards pending")).contains("Key purchases waiting (0)"),
            "the purchase is finished");

        e2e.step("keys the crates feature refuses are refunded");
        int room = 1_000_000 - keys.keys(id, "basic");
        e2e.expect(keys.give(id, "basic", room - 1, "e2e", null).success(), "the player holds one key below the limit");
        bot.clearLogs();
        bot.command("shardshop");
        e2e.dialog(bot, "Shard shop");
        e2e.click(bot, "Basic key");
        e2e.dialog(bot, "Buy Basic key");
        e2e.click(bot, "Buy 1", Map.of("amount", 2));
        e2e.dialog(bot, "Buy Basic key");
        e2e.click(bot, "Buy 2 for 100 shards", Map.of("amount", 2));
        e2e.eventually(() -> bot.chatContains("couldn't be given, so your 100 shards were refunded"), "told about the refund: " + bot.chat());
        e2e.expect(e2e.shards(name) == 1134, "the shards are back (has " + e2e.shards(name) + ")");
        e2e.expect(keys.keys(id, "basic") == 999_999, "no keys were given");
        e2e.eventually(() -> {
            var rows = e2e.services().ledger().history(id, 3, 0).join();
            return !rows.isEmpty() && rows.getFirst().kind().equals("shard_refund") && rows.getFirst().delta() == 100;
        }, "a shard_refund ledger row of +100");
        e2e.eventually(() -> String.join(" ", e2e.consoleOutput("shards pending")).contains("Key purchases waiting (0)"),
            "nothing left waiting");
    }
}
