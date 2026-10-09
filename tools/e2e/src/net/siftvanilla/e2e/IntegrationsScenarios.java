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
        reply(e2e, "sift store keys " + name + " " + crate + " 2 " + ref + "-k", "Delivered 2 " + crate + " keys");
        reply(e2e, "sift store keys " + name + " " + crate + " 2 " + ref + "-k", "Already delivered");
        e2e.expect(keys.keys(id, crate) == before + 2, "exactly two keys: " + keys.keys(id, crate));

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
        List<String> history = reply(e2e, "sift store history " + name, "Store deliveries of " + name + " (3)");
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
