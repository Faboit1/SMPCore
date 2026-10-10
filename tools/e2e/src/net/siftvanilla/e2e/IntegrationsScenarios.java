package net.siftvanilla.e2e;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.api.SiftCoreApi;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.event.StoreDeliveryEvent;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import net.siftvanilla.siftcore.feature.integrations.IntegrationsFeature;
import net.siftvanilla.siftcore.integration.floodgate.FormPlan;
import net.siftvanilla.siftcore.ui.dialog.FormBridge;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The integrations and admin tools: store delivery (exactly once per reference, from the console), LuckPerms rank
 * grants and labels (when LuckPerms is installed), the placeholders through PlaceholderAPI and the public API,
 * backups, exports, the audit log and the generated docs, and the Bedrock form path: a stand-in form bridge answers
 * for one bot exactly as Floodgate forms would, so a whole menu and payment flow runs through {@link FormPlan}.
 */
final class IntegrationsScenarios {

    private IntegrationsScenarios() {
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

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Named("store-delivery", IntegrationsScenarios::storeDelivery));
        list.add(new Named("store-rank", IntegrationsScenarios::storeRank));
        list.add(new Named("placeholders-api", IntegrationsScenarios::placeholdersAndApi));
        list.add(new Named("admin-tools", IntegrationsScenarios::adminTools));
        list.add(new Named("bedrock-forms", IntegrationsScenarios::bedrockForms));
        list.add(new Named("show-my-rank", IntegrationsScenarios::showMyRank));
        list.add(new Named("tab-config", IntegrationsScenarios::tabConfig));
        return list;
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

    // ------------------------------------------------------------------ store

    static void storeDelivery(E2E e2e) {
        String name = e2e.name("Buyer");
        Bot buyer = e2e.bot(name);
        UUID id = e2e.uuid(name);
        String ref = "e2e-" + UUID.randomUUID().toString().substring(0, 8);

        e2e.step("money arrives once, and the buyer is told");
        buyer.clearLogs();
        reply(e2e, "sift store money " + name + " 12500 " + ref + "-m", "Delivered $12,500 to " + name);
        e2e.eventually(() -> e2e.money(name) == 12_500, "the buyer has $12,500: " + e2e.money(name));
        e2e.eventually(() -> buyer.chatContains("Your store purchase arrived: $12,500"), "the buyer is told in chat: " + buyer.chat());
        reply(e2e, "sift store money " + name + " 12500 " + ref + "-m", "Already delivered");
        e2e.sleep(500);
        e2e.expect(e2e.money(name) == 12_500, "a retried delivery pays nothing: " + e2e.money(name));

        e2e.step("shards and crate keys, each once");
        reply(e2e, "sift store shards " + name + " 300 " + ref + "-s", "Delivered 300 shards");
        e2e.eventually(() -> e2e.shards(name) == 300, "300 shards: " + e2e.shards(name));
        var keys = e2e.feature(CratesFeature.class).keys();
        String crate = keys.crates().iterator().next();
        int before = keys.keys(id, crate);
        String two = PlainTextComponentSerializer.plainText().serialize(keys.keysText(crate, 2));
        String one = PlainTextComponentSerializer.plainText().serialize(keys.keysText(crate, 1));
        e2e.expect(one.startsWith("1 ") && one.endsWith(" key") && two.startsWith("2 ") && two.endsWith(" keys"),
            "keys read like the crates' text, with a singular: '" + one + "', '" + two + "'");
        buyer.clearLogs();
        reply(e2e, "sift store keys " + name + " " + crate + " 2 " + ref + "-k", "Delivered " + two);
        e2e.eventually(() -> buyer.chatContains("Your store purchase arrived: " + two + "."), "the buyer is told: " + buyer.chat());
        reply(e2e, "sift store keys " + name + " " + crate + " 2 " + ref + "-k", "Already delivered");
        e2e.expect(keys.keys(id, crate) == before + 2, "exactly two keys: " + keys.keys(id, crate));
        buyer.clearLogs();
        reply(e2e, "sift store keys " + name + " " + crate + " 1 " + ref + "-k1", "Delivered " + one);
        e2e.eventually(() -> buyer.chatContains("Your store purchase arrived: " + one + "."), "one key, singular: " + buyer.chat());

        e2e.step("by account id, for buyers who are not online");
        String offline = UUID.randomUUID().toString();
        reply(e2e, "sift store money " + offline + " 500 " + ref + "-o", "Delivered $500");
        e2e.eventually(() -> e2e.services().ledger().balance(UUID.fromString(offline), Currency.MONEY) == 500, "paid by account id");

        e2e.step("refusals change nothing");
        reply(e2e, "sift store money " + name + " 999m " + ref + "-big", "more than one delivery may give");
        reply(e2e, "sift store keys " + name + " no_such_crate 1 " + ref + "-bad", "no such crate");
        reply(e2e, "sift store money NoSuchBuyer1 5 " + ref + "-who", "Nobody called NoSuchBuyer1 has joined");
        e2e.expect(e2e.money(name) == 12_500, "money unchanged: " + e2e.money(name));

        e2e.step("a plugin can cancel a delivery, which records nothing");
        Listener blocker = new Listener() {
            @EventHandler
            public void onDelivery(StoreDeliveryEvent event) {
                if (event.ref().endsWith("-blocked")) {
                    event.setCancelled(true);
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(blocker, e2e.services().plugin());
        try {
            reply(e2e, "sift store money " + name + " 100 " + ref + "-blocked", "another plugin cancelled it");
            reply(e2e, "sift store check " + ref + "-blocked", "Nothing was delivered under " + ref + "-blocked");
        } finally {
            HandlerList.unregisterAll(blocker);
        }
        e2e.expect(e2e.money(name) == 12_500, "nothing paid while cancelled: " + e2e.money(name));

        e2e.step("lookups and the audit log");
        reply(e2e, "sift store check " + ref + "-m", ref + "-m gave $12,500 to " + name);
        List<String> history = reply(e2e, "sift store history " + name, "Store deliveries of " + name + " (4)");
        e2e.eventually(() -> contains(history, ref + "-k"), "the history lists the keys: " + history);
        reply(e2e, "sift audit store.money " + name, "store.money " + name + " ref " + ref + "-m");
        reply(e2e, "sift audit store.failed " + name, "too_much");

        e2e.step("players can't deliver to themselves");
        buyer.clearLogs();
        buyer.command("sift store money " + name + " 1000000 " + ref + "-self");
        e2e.sleep(1_500);
        e2e.expect(e2e.money(name) == 12_500, "no money from a player's own store command: " + e2e.money(name));

        e2e.step("a chargeback takes the money back, once, and the reference stays used");
        buyer.clearLogs();
        reply(e2e, "sift store revoke " + ref + "-m chargeback", "Revoked " + ref + "-m: $12,500 from " + name + ". Took back $12,500.");
        e2e.eventually(() -> e2e.money(name) == 0, "the money is gone: " + e2e.money(name));
        e2e.eventually(() -> buyer.chatContains("was taken back"), "the buyer is told: " + buyer.chat());
        reply(e2e, "sift store revoke " + ref + "-m chargeback", "was revoked before");
        reply(e2e, "sift store money " + name + " 12500 " + ref + "-m", "was revoked, so it can't be delivered again");
        e2e.sleep(500);
        e2e.expect(e2e.money(name) == 0, "nothing paid again: " + e2e.money(name));
        reply(e2e, "sift store check " + ref + "-m", "revoked (chargeback)");
        reply(e2e, "sift store revoke " + ref + "-k refund", "Keys can't be taken back from here");
        reply(e2e, "sift store revoke no-such-" + ref + " refund", "Nothing was delivered under");
    }

    static void storeRank(E2E e2e) {
        String name = e2e.name("Ranked");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        String ref = "e2e-r-" + UUID.randomUUID().toString().substring(0, 8);
        IntegrationsFeature integrations = e2e.feature(IntegrationsFeature.class);
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) {
            e2e.step("without LuckPerms a rank is refused and nothing is recorded");
            reply(e2e, "sift store rank " + name + " prospector 30d " + ref, "LuckPerms is not installed");
            reply(e2e, "sift store check " + ref, "Nothing was delivered under " + ref);
            e2e.expect(integrations.ranks().label(id).isEmpty(), "no label without LuckPerms");
            e2e.expect("default".equals(integrations.ranks().group(id)), "the default group without LuckPerms");
            e2e.expect(integrations.ranks().component(e2e.player(name)).equals(Component.empty()), "no coloured label without LuckPerms");
            return;
        }
        e2e.step("set up the prospector rank in LuckPerms like the live server");
        e2e.console("lp creategroup prospector");
        e2e.console("lp group prospector setweight 10");
        e2e.console("lp group prospector setdisplayname Prospector");
        e2e.console("lp group prospector meta set siftcore-rank &#5FA8FF&lProspector");
        e2e.console("lp group prospector meta set siftcore-rank-color #5FA8FF");
        e2e.sleep(1_000);

        e2e.step("a timed rank is granted once");
        bot.clearLogs();
        reply(e2e, "sift store rank " + name + " prospector 30d " + ref, "Delivered Prospector for 30d to " + name);
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission("group.prospector")),
            "the buyer is in the prospector group");
        e2e.eventually(() -> bot.chatContains("you are Prospector for 30d"), "the buyer is told: " + bot.chat());
        e2e.eventually(() -> "Prospector".equals(integrations.ranks().label(id)), "the label is the meta value without colours: '"
            + integrations.ranks().label(id) + "'");
        e2e.eventually(() -> "prospector".equals(integrations.ranks().group(id)), "the primary group: " + integrations.ranks().group(id));
        Component coloured = integrations.ranks().component(e2e.player(name));
        e2e.expect(coloured.color() != null && coloured.color().value() == 0x5FA8FF, "the coloured label uses the rank colour: " + coloured);
        e2e.expect("#5FA8FF".equals(SiftCoreApi.get().placeholders().resolve(e2e.player(name), "rank_color").orElse("")),
            "%siftcore_rank_color%: " + SiftCoreApi.get().placeholders().resolve(e2e.player(name), "rank_color"));
        reply(e2e, "sift store rank " + name + " prospector 30d " + ref, "Already delivered");

        e2e.step("staff groups can't be bought");
        reply(e2e, "sift store rank " + name + " admin permanent " + ref + "-x", "can't be granted by the store");
        e2e.expect(!e2e.onPlayer(name, () -> e2e.player(name).hasPermission("group.admin")), "no admin group");

        e2e.step("the rank placeholder follows LuckPerms");
        e2e.eventually(() -> "Prospector".equals(SiftCoreApi.get().placeholders().resolve(e2e.player(name), "rank").orElse("")),
            "%siftcore_rank% is Prospector");

        e2e.step("a refund takes the rank back, once");
        bot.clearLogs();
        reply(e2e, "sift store revoke " + ref + " refund", "Their Prospector rank was taken away");
        e2e.eventually(() -> !e2e.onPlayer(name, () -> e2e.player(name).hasPermission("group.prospector")), "the group is gone");
        e2e.eventually(() -> integrations.ranks().label(id).isEmpty(), "the label clears: '" + integrations.ranks().label(id) + "'");
        e2e.eventually(() -> bot.chatContains("was taken back"), "the buyer is told: " + bot.chat());
        reply(e2e, "sift store revoke " + ref + " refund", "was revoked before");
        reply(e2e, "sift store rank " + name + " prospector 30d " + ref, "was revoked, so it can't be delivered again");
        e2e.expect(!e2e.onPlayer(name, () -> e2e.player(name).hasPermission("group.prospector")), "still no rank");
        reply(e2e, "sift audit store.revoke " + name, "store.revoke " + name + " ref " + ref + " (refund)");
    }

    // ------------------------------------------------------------------ placeholders and API

    static void placeholdersAndApi(E2E e2e) throws Exception {
        String name = e2e.name("Holder");
        e2e.bot(name);
        UUID id = e2e.uuid(name);
        reply(e2e, "sift store money " + name + " 4321 e2e-p-" + UUID.randomUUID().toString().substring(0, 8), "Delivered");
        e2e.eventually(() -> e2e.money(name) == 4_321, "paid");

        e2e.step("the public API is in the services manager");
        SiftCoreApi api = SiftCoreApi.get();
        e2e.expect(api == e2e.services().plugin().getServer().getServicesManager().load(SiftCoreApi.class), "one registered API");
        e2e.expect(api.economy().balance(id, Currency.MONEY) == 4_321, "the API reads the balance");
        e2e.expect(!api.combat().tagged(id) && api.combat().remaining(id).isZero() && api.combat().lastAttacker(id).isEmpty(),
            "not in combat");
        OfflinePlayer player = Bukkit.getOfflinePlayer(id);
        e2e.expect("$4,321".equals(api.placeholders().resolve(player, "balance").orElse(null)),
            "balance placeholder: " + api.placeholders().resolve(player, "balance"));
        e2e.expect("Money: $4,321 (%siftcore_nope%)".equals(api.placeholders().apply(player, "Money: %siftcore_balance% (%siftcore_nope%)")),
            "apply replaces known names only: " + api.placeholders().apply(player, "Money: %siftcore_balance% (%siftcore_nope%)"));
        e2e.expect(api.placeholders().available().containsKey("rank"), "the rank placeholder is listed");
        e2e.expect(!api.version().isEmpty(), "a version");

        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            e2e.log("PlaceholderAPI is not installed; the expansion was checked by the self-test only");
            return;
        }
        e2e.step("PlaceholderAPI answers %siftcore_...% from SiftCore");
        Class<?> type = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
        Method set = type.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        String text = (String) set.invoke(null, player, "%siftcore_balance% %siftcore_shards% %siftcore_rank_group% %siftcore_baltop_name_1%");
        String expected = "$4,321 0 " + api.ranks().group(id) + " " + api.placeholders().resolve(null, "baltop_name_1").orElse("-");
        e2e.expect(expected.equals(text), "PlaceholderAPI: '" + text + "' expected '" + expected + "'");
        Method registered = type.getMethod("isRegistered", String.class);
        e2e.expect((boolean) registered.invoke(null, "siftcore"), "the siftcore expansion is registered");
        e2e.step("the same values answer as %siftvanilla_...%");
        e2e.expect((boolean) registered.invoke(null, "siftvanilla"), "the siftvanilla alias is registered");
        String aliased = (String) set.invoke(null, player, "%siftvanilla_balance% %siftvanilla_shards%");
        e2e.expect("$4,321 0".equals(aliased), "the alias: '" + aliased + "'");
    }

    // ------------------------------------------------------------------ show my rank

    private static String placeholder(E2E e2e, String player, String name) {
        return SiftCoreApi.get().placeholders().resolve(e2e.player(player), name).orElse("?");
    }

    /** The first of {@code lines} that contains {@code text}, or "". */
    private static String line(List<String> lines, String text) {
        return lines.stream().filter(l -> l.contains(text)).findFirst().orElse("");
    }

    /** The newest chat line the bot got that contains {@code text}, as plain text. */
    private static String chatLine(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.chatContains(text), bot.name + " gets a line with '" + text + "': " + bot.chat());
        List<String> lines = bot.chat();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(text)) {
                return lines.get(i);
            }
        }
        return "";
    }

    /**
     * TAB with the live config (plugins/TAB copied from the server): a joining player gets TAB's sidebar with every line
     * filled from SiftCore (a player without a team reads None, the keyall line never a bare "-") and the server address
     * last, the address in the tab list footer, and TAB logs no error while building them. One bad
     * placeholder-output-replacement key (TAB reads a key with "-" as a number range) stopped the whole sidebar, address
     * included, from showing on join. Nothing to check where TAB is not installed.
     */
    static void tabConfig(E2E e2e) throws Exception {
        Plugin tab = Bukkit.getPluginManager().getPlugin("TAB");
        if (tab == null || !tab.isEnabled()) {
            e2e.step("TAB is not installed here, nothing to check");
            return;
        }
        Path errors = tab.getDataFolder().toPath().resolve("errors.log");
        long before = Files.exists(errors) ? Files.size(errors) : 0;
        Bot bot = e2e.bot(e2e.name("TabView"));

        e2e.step("TAB's sidebar shows every line, the address last");
        e2e.eventually(() -> bot.sidebarLines().stream().anyMatch(l -> l.contains("siftvanilla.com")), 15_000,
            "TAB's sidebar with the address");
        e2e.eventually(() -> line(bot.sidebarLines(), "Keyall:").length() > 0, "the keyall line");
        List<String> lines = bot.sidebarLines();
        e2e.log("sidebar: " + lines);
        e2e.expect(lines.get(lines.size() - 1).contains("siftvanilla.com"), "the address is the last line: " + lines);
        String title = bot.displayed("sidebar").displayName().getString();
        e2e.expect(title.contains("SIFTVANILLA"), "the title: " + title);
        for (String label : List.of("Money:", "Shards:", "Kills:", "Deaths:", "Team:", "Keyall:", "Online:")) {
            String value = line(lines, label);
            value = value.substring(value.indexOf(label) + label.length()).trim();
            e2e.expect(!value.isEmpty() && !value.contains("%"), label + " is filled: '" + value + "' in " + lines);
        }
        e2e.expect(line(lines, "Team:").endsWith("Team: None"), "no team reads None: " + line(lines, "Team:"));
        String keyall = line(lines, "Keyall:");
        e2e.expect(keyall.endsWith("Keyall: Off") || keyall.matches(".*Keyall: (\\d+[dhms] ?)+"), "the keyall countdown or Off: " + keyall);

        e2e.step("the tab list footer names the address too");
        e2e.eventually(() -> bot.tabFooter() != null && bot.tabFooter().contains("siftvanilla.com"), "the footer: " + bot.tabFooter());
        e2e.expect(bot.tabHeader() != null && bot.tabHeader().contains("players online"), "the header: " + bot.tabHeader());

        e2e.step("TAB logged no error");
        e2e.sleep(1_000);
        String added = Files.exists(errors) && Files.size(errors) > before
            ? Files.readString(errors).substring((int) before) : "";
        e2e.expect(added.isEmpty(), "TAB's errors.log grew:\n" + added.lines().limit(4).reduce("", (x, y) -> x + y + "\n"));
    }

    /**
     * Show my rank (Privacy settings): a ranked player with siftcore.settings.hide-rank turns it off in the dialog and
     * their rank leaves chat, the rank placeholders and the public API at once while their group stays; without the
     * node they read the default (shown). Without LuckPerms (or with its hook turned off) the switch is not offered and
     * a stored choice applies nowhere, the scoreboard included. On a server where SiftCore leaves the tab list to another
     * plugin (TAB, as live) that plugin builds the tab names, so the scoreboard's tab name checks are skipped; what such a
     * plugin can read from SiftCore (the rank placeholders) is checked either way.
     */
    static void showMyRank(E2E e2e) throws Exception {
        String name = e2e.name("Hider");
        String watcherName = e2e.name("HiderSee");
        Bot bot = e2e.bot(name);
        Bot watcher = e2e.bot(watcherName);
        UUID id = e2e.uuid(name);
        IntegrationsFeature integrations = e2e.feature(IntegrationsFeature.class);
        var entry = e2e.services().settings().registry().entry("show-my-rank");
        e2e.expect(entry != null && entry.category().id().equals("privacy"), "registered in Privacy: " + entry);
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) {
            e2e.step("without LuckPerms there are no rank labels, so the switch is not offered");
            e2e.expect(!entry.offered(), "not offered without LuckPerms");
            return;
        }
        String group = "e2erank" + Long.toString(System.nanoTime() % 1_000, 36);
        e2e.step("a rank group with a label, the player in it and the hide-rank node");
        e2e.console("lp creategroup " + group);
        // The heaviest parent is the primary group (LuckPerms' parents-by-weight), like a bought rank above default.
        e2e.console("lp group " + group + " setweight 100");
        e2e.console("lp group " + group + " meta set siftcore-rank Knight");
        e2e.console("lp user " + name + " parent add " + group);
        e2e.console("lp user " + name + " permission set " + "siftcore.settings.hide-rank true");
        try {
            e2e.eventually(() -> "Knight".equals(integrations.ranks().label(id)) && group.equals(integrations.ranks().group(id)),
                "the label and primary group: '" + integrations.ranks().label(id) + "' " + integrations.ranks().group(id));
            e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission("siftcore.settings.hide-rank")), "the node arrived");
            e2e.expect(entry.offered(), "offered while LuckPerms is connected");
            e2e.expect("Knight".equals(placeholder(e2e, name, "rank")), "%siftcore_rank%: " + placeholder(e2e, name, "rank"));
            e2e.expect(group.equals(placeholder(e2e, name, "rank_group")), "%siftcore_rank_group%: " + placeholder(e2e, name, "rank_group"));
            watcher.clearLogs();
            e2e.sleep(1_400);
            bot.chat("hello with a rank");
            String ranked = chatLine(e2e, watcher, "hello with a rank");
            e2e.expect(ranked.contains("Knight"), "the chat line shows the rank: " + ranked);
            String tabOwner = tabListOwner(e2e);
            if (tabOwner != null) {
                e2e.step("the tab list is left to " + tabOwner + ", which builds the names: the scoreboard's tab name checks are skipped");
            }
            e2e.consoleOutput("sidebar refresh");
            tabName(e2e, watcher, id, tabOwner, "Knight " + name, "the tab list shows the rank");

            e2e.step("Show my rank sits in the Privacy settings, on by default");
            AfkStaffSettingSteps.openGroup(e2e, bot, "privacy", "Privacy settings");
            Bot.SeenDialog page = AfkStaffSettingSteps.form(e2e, bot);
            e2e.expect("toggle".equals(page.inputs().get("show_my_rank")), "the switch: " + page.inputs());
            e2e.expect(Boolean.TRUE.equals(page.toggleValue("show_my_rank")), "on by default");
            e2e.expect(page.button("Show my rank: ON") != null, "its button: " + page.buttons());

            e2e.step("turning it off hides the rank in chat, the placeholders and the API at once; the group stays");
            Map<String, Object> values = page.values();
            values.put("show_my_rank", false);
            bot.clearMessages();
            SettingsSteps.applyChanged(e2e, bot, page, values);
            e2e.expect(bot.dialog().button("Show my rank: OFF") != null, "the button shows OFF: " + bot.dialog().buttons());
            e2e.eventually(() -> integrations.ranks().label(id).isEmpty(), "no label: '" + integrations.ranks().label(id) + "'");
            e2e.expect(placeholder(e2e, name, "rank").isEmpty(), "%siftcore_rank% is empty: " + placeholder(e2e, name, "rank"));
            e2e.expect("default".equals(placeholder(e2e, name, "rank_group")), "%siftcore_rank_group% reads as a member: "
                + placeholder(e2e, name, "rank_group"));
            e2e.expect(placeholder(e2e, name, "rank_color").isEmpty(), "%siftcore_rank_color% is empty");
            e2e.expect(SiftCoreApi.get().ranks().label(id).isEmpty(), "the public API has no label either");
            e2e.expect(group.equals(integrations.ranks().group(id)), "the real group stays for code: " + integrations.ranks().group(id));
            e2e.expect("false".equals(AfkStaffSettingSteps.stored(e2e, id, "show-my-rank")), "the choice is stored");
            watcher.clearLogs();
            e2e.sleep(1_400);
            bot.chat("hello without a rank");
            String plain = chatLine(e2e, watcher, "hello without a rank");
            e2e.expect(!plain.contains("Knight"), "the chat line has no rank: " + plain);
            tabName(e2e, watcher, id, tabOwner, name, "the scoreboard's tab name has no rank either");

            e2e.step("without the node the switch is gone and the rank shows again");
            e2e.console("lp user " + name + " permission unset siftcore.settings.hide-rank");
            e2e.eventually(() -> !e2e.onPlayer(name, () -> e2e.player(name).hasPermission("siftcore.settings.hide-rank")), "the node is gone");
            e2e.eventually(() -> "Knight".equals(integrations.ranks().label(id)), "the label is back: '" + integrations.ranks().label(id) + "'");
            e2e.expect(!e2e.onPlayer(name, () -> e2e.services().settings().visible(entry, e2e.player(name)::hasPermission)),
                "the switch is not offered to them");
            e2e.expect("false".equals(AfkStaffSettingSteps.stored(e2e, id, "show-my-rank")), "their choice is kept for when the node comes back");

            e2e.step("with the node back the stored choice applies again");
            e2e.console("lp user " + name + " permission set siftcore.settings.hide-rank true");
            e2e.eventually(() -> integrations.ranks().label(id).isEmpty(), "hidden again with the node");

            e2e.step("with the LuckPerms hook turned off the switch is gone, and the scoreboard shows the rank it reads from "
                + "group permissions again instead of keeping the stored choice nobody can undo");
            e2e.console("lp creategroup prospector");
            e2e.console("lp user " + name + " parent add prospector");
            e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission("group.prospector")), "in the prospector group");
            e2e.consoleOutput("sidebar refresh");
            tabName(e2e, watcher, id, tabOwner, name, "still hidden while the switch is offered");
            withFile(e2e, "features/integrations.yml", Map.of(LUCKPERMS_ON, LUCKPERMS_ON.replace("enabled: true", "enabled: false")), () -> {
                e2e.eventually(() -> !entry.offered(), "not offered while the LuckPerms hook is off");
                e2e.expect(integrations.ranks().label(id).isEmpty(), "chat has no rank labels at all without the hook");
                e2e.consoleOutput("sidebar refresh");
                tabName(e2e, watcher, id, tabOwner, "Prospector " + name,
                    "the scoreboard's rank from group.prospector shows, the stored off is not applied");
                e2e.expect("false".equals(AfkStaffSettingSteps.stored(e2e, id, "show-my-rank")), "the choice itself is kept");
            });
            e2e.eventually(entry::offered, "offered again with the hook back");
            e2e.eventually(() -> integrations.ranks().label(id).isEmpty(), "and the kept choice hides the rank again");
            e2e.consoleOutput("sidebar refresh");
            tabName(e2e, watcher, id, tabOwner, name, "the scoreboard hides it again too");

            e2e.step("on again deletes the row");
            AfkStaffSettingSteps.set(e2e, name, IntegrationsFeature.SHOW_MY_RANK, true);
            e2e.eventually(() -> "Knight".equals(integrations.ranks().label(id)), "shown again");
            e2e.eventually(() -> AfkStaffSettingSteps.stored(e2e, id, "show-my-rank") == null, "no row for the default");
        } finally {
            e2e.console("lp user " + name + " permission unset siftcore.settings.hide-rank");
            e2e.console("lp user " + name + " parent remove " + group);
            e2e.console("lp user " + name + " parent remove prospector");
            e2e.console("lp deletegroup " + group);
        }
    }

    /** The plugin SiftCore's scoreboard left the tab list to ({@code /sidebar status}), or null when it shows it itself. */
    static String tabListOwner(E2E e2e) {
        java.util.regex.Pattern owner = java.util.regex.Pattern.compile("tab list \\(([^)]+)\\)");
        for (String line : e2e.consoleOutput("sidebar status")) {
            java.util.regex.Matcher found = owner.matcher(line);
            if (found.find()) {
                return found.group(1);
            }
        }
        return null;
    }

    /**
     * Waits for the watcher's tab name of a player to read {@code expected} while SiftCore's scoreboard shows the tab
     * list; nothing to check when another plugin has it ({@code owner}, TAB as live): that plugin builds the names, from
     * its own config.
     */
    private static void tabName(E2E e2e, Bot watcher, UUID id, String owner, String expected, String what) {
        if (owner != null) {
            return;
        }
        e2e.eventually(() -> watcher.listName(id) != null && expected.equals(watcher.listName(id).getString()),
            what + ": " + watcher.listName(id));
    }

    /** The lines in features/integrations.yml that turn the LuckPerms hook on (the shipped text). */
    private static final String LUCKPERMS_ON = "Store rank delivery also needs LuckPerms.\n  enabled: true";

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    /** Edits a SiftCore file, reloads, runs the step and always restores the file and reloads again. */
    private static void withFile(E2E e2e, String file, Map<String, String> replacements, Step step) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(file);
        String original = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), file + " contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        Files.writeString(path, changed, java.nio.charset.StandardCharsets.UTF_8);
        try {
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed " + file + " reloads: " + reload);
            step.run();
        } finally {
            Files.writeString(path, original, java.nio.charset.StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    // ------------------------------------------------------------------ admin tools

    static void adminTools(E2E e2e) throws Exception {
        String name = e2e.name("Audited");
        Bot bot = e2e.bot(name);
        reply(e2e, "sift store money " + name + " 777 e2e-a-" + UUID.randomUUID().toString().substring(0, 8), "Delivered");
        Path data = e2e.services().plugin().getDataFolder().toPath();

        e2e.step("a backup holds everything committed so far");
        List<String> backup = reply(e2e, "sift backup", "Backup saved as");
        String file = backup.stream().filter(line -> line.contains("Backup saved as")).findFirst().orElseThrow()
            .replaceAll(".*Backup saved as \\S*/(siftcore-[^ ]+\\.db).*", "$1");
        Path copy = data.resolve("backups").resolve(file);
        e2e.expect(Files.size(copy) > 0, "the backup file exists: " + copy);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + copy);
             var statement = connection.prepareStatement("SELECT balance FROM accounts WHERE uuid = ? AND currency = 'money'")) {
            statement.setString(1, e2e.uuid(name).toString());
            try (var rows = statement.executeQuery()) {
                e2e.expect(rows.next() && rows.getLong(1) == 777, "the backup has the balance paid just before it");
            }
        }
        reply(e2e, "sift backup list", file);

        e2e.step("exports are CSV files with every balance");
        reply(e2e, "sift export", "Exported");
        e2e.eventually(() -> {
            try (Stream<Path> files = Files.list(data.resolve("exports"))) {
                return files.filter(path -> path.getFileName().toString().startsWith("balances-")).anyMatch(path -> {
                    try {
                        return Files.readString(path).contains(e2e.uuid(name) + "," + name + ",player,money,777");
                    } catch (java.io.IOException e) {
                        return false;
                    }
                });
            } catch (java.io.IOException e) {
                return false;
            }
        }, "a balances export lists " + name);

        e2e.step("registries and generated docs");
        reply(e2e, "sift permissions admin.store", "siftcore.admin.store");
        reply(e2e, "sift placeholders balance_raw", "%siftcore_balance_raw%");
        reply(e2e, "sift docs", "Wrote permissions.md");
        e2e.eventually(() -> {
            try {
                return Files.readString(data.resolve("docs/placeholders.md")).contains("`%siftcore_rank%`")
                    && Files.readString(data.resolve("docs/permissions.md")).contains("`siftcore.admin.store`");
            } catch (java.io.IOException e) {
                return false;
            }
        }, "the docs list the registries");
        reply(e2e, "sift integrations", "Public API: active");

        e2e.step("the audit log shows the admin actions, filtered");
        reply(e2e, "sift audit admin.backup", "admin.backup");
        reply(e2e, "sift audit admin.docs", "admin.docs");

        e2e.step("players without the permission can't use the tools");
        bot.clearLogs();
        long before;
        try (Stream<Path> files = Files.list(data.resolve("backups"))) {
            before = files.count();
        }
        bot.command("sift backup");
        e2e.sleep(1_500);
        try (Stream<Path> files = Files.list(data.resolve("backups"))) {
            e2e.expect(files.count() == before, "no backup from a player without the permission");
        }
    }

    // ------------------------------------------------------------------ Bedrock forms

    /** A form shown to the stand-in Bedrock client: the plan, and how to answer it. */
    private record Shown(FormPlan plan, FormBridge.Response response) {
    }

    private static final FormPlan.Renderer PLAIN = new FormPlan.Renderer() {
        @Override
        public String text(Component component) {
            return PlainTextComponentSerializer.plainText().serialize(component);
        }

        @Override
        public String item(net.siftvanilla.siftcore.ui.dialog.Body.Item item) {
            return PlainTextComponentSerializer.plainText().serialize(item.item().effectiveName());
        }

        @Override
        public String closeLabel() {
            return "Close";
        }

        @Override
        public String actionLabel() {
            return "Action";
        }
    };

    private static Shown next(E2E e2e, BlockingQueue<Shown> forms, String title) throws InterruptedException {
        Shown shown = forms.poll(15, TimeUnit.SECONDS);
        if (shown == null) {
            throw new E2E.Failure("no form titled '" + title + "' was shown");
        }
        e2e.expect(shown.plan().title().contains(title), "the form '" + shown.plan().title() + "' is '" + title + "'");
        return shown;
    }

    private static void answer(Shown shown, FormPlan.Answer answer) {
        shown.response().answer(answer.button(), answer.values());
    }

    private static int index(List<String> labels, String contains) {
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).toLowerCase().contains(contains.toLowerCase())) {
                return i;
            }
        }
        throw new E2E.Failure("no button '" + contains + "' in " + labels);
    }

    static void bedrockForms(E2E e2e) throws Exception {
        String payerName = e2e.name("Bedrock");
        String payeeName = e2e.name("Java");
        Bot payer = e2e.bot(payerName);
        Bot payee = e2e.bot(payeeName);
        UUID payerId = e2e.uuid(payerName);
        reply(e2e, "sift store money " + payerName + " 200000 e2e-b-" + UUID.randomUUID().toString().substring(0, 8), "Delivered");
        e2e.eventually(() -> e2e.money(payerName) == 200_000, "the Bedrock player has $200,000");

        var dialogs = e2e.services().dialogs();
        FormBridge previous = e2e.feature(IntegrationsFeature.class).bedrockBridge();
        BlockingQueue<Shown> forms = new LinkedBlockingQueue<>();
        FormBridge standIn = new FormBridge() {
            @Override
            public boolean handles(Player player) {
                return player.getUniqueId().equals(payerId) || (previous != null && previous.handles(player));
            }

            @Override
            public void show(Player player, View view, Response response) {
                if (!player.getUniqueId().equals(payerId)) {
                    previous.show(player, view, response);
                    return;
                }
                forms.add(new Shown(FormPlan.of(view, PLAIN), response));
            }
        };
        dialogs.bedrock(standIn);
        try {
            int javaDialogs = payer.dialogs().size();

            e2e.step("the menu is a simple form; its buttons lead on and back");
            payer.command("menu");
            Shown menu = next(e2e, forms, "SiftVanilla");
            e2e.expect(menu.plan().type() == FormPlan.Type.SIMPLE, "the menu is a simple form: " + menu.plan().type());
            answer(menu, menu.plan().clicked(index(menu.plan().buttons(), "Money")).orElseThrow());
            Shown money = next(e2e, forms, "Money");
            e2e.expect(money.plan().content().contains("$200,000"), "the money page shows the balance: " + money.plan().content());
            answer(money, money.plan().clicked(money.plan().buttons().size() - 1).orElseThrow());
            Shown back = next(e2e, forms, "SiftVanilla");
            answer(back, back.plan().clicked(index(back.plan().buttons(), "Money")).orElseThrow());
            money = next(e2e, forms, "Money");

            e2e.step("the pay form is a custom form, its confirmation a modal form");
            answer(money, money.plan().clicked(index(money.plan().buttons(), "Pay")).orElseThrow());
            Shown form = next(e2e, forms, "Pay");
            e2e.expect(form.plan().type() == FormPlan.Type.CUSTOM, "the pay form is a custom form: " + form.plan().type());
            List<Object> values = new ArrayList<>();
            for (FormPlan.Field field : form.plan().fields()) {
                values.add(switch (field) {
                    case FormPlan.Field.Text text -> text.key().equals("player") ? payeeName : "150000";
                    default -> null;
                });
            }
            answer(form, form.plan().submitted(values).orElseThrow());
            Shown confirm = next(e2e, forms, "");
            e2e.expect(confirm.plan().type() == FormPlan.Type.MODAL, "a confirmation is a modal form: " + confirm.plan().type());
            e2e.expect(confirm.plan().content().contains(payeeName), "the confirmation names the payee: " + confirm.plan().content());
            answer(confirm, confirm.plan().clicked(0).orElseThrow());
            e2e.eventually(() -> e2e.money(payerName) == 50_000 && e2e.money(payeeName) == 150_000,
                "the payment went through: " + e2e.money(payerName) + " / " + e2e.money(payeeName));
            e2e.eventually(() -> payee.chatContains("150,000") || payee.anyFeedbackContains("150,000"), "the payee is told");

            e2e.step("an answer is used once");
            answer(confirm, confirm.plan().clicked(0).orElseThrow());
            e2e.sleep(1_000);
            e2e.expect(e2e.money(payerName) == 50_000, "a repeated form answer pays nothing: " + e2e.money(payerName));

            e2e.step("invalid answers are re-asked with an error, not run");
            payer.command("pay");
            Shown again = next(e2e, forms, "Pay");
            List<Object> bad = new ArrayList<>();
            for (FormPlan.Field field : again.plan().fields()) {
                bad.add(field instanceof FormPlan.Field.Text text ? (text.key().equals("player") ? payeeName : "lots") : null);
            }
            answer(again, again.plan().submitted(bad).orElseThrow());
            Shown error = next(e2e, forms, "Pay");
            e2e.expect(error.plan().fields().stream().anyMatch(field -> field instanceof FormPlan.Field.Label label
                && label.label().contains("lots")), "the error names the bad amount: " + error.plan().fields());
            e2e.expect(e2e.money(payerName) == 50_000, "nothing paid");
            Map<String, Object> closed = error.plan().closed().map(FormPlan.Answer::values).orElse(Map.of());
            e2e.expect(closed.containsKey("amount"), "closing a form answers its cancel button with the shown values: " + closed);
            answer(error, error.plan().closed().orElseThrow());

            e2e.expect(payer.dialogs().size() == javaDialogs, "the Bedrock player got forms only, no Java dialogs");
        } finally {
            dialogs.bedrock(previous);
        }
    }
}
