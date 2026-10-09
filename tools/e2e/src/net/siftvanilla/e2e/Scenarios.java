package net.siftvanilla.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.bukkit.configuration.file.YamlConfiguration;

/** The scenario catalogue. Each scenario is independent and cleans up its bots. */
final class Scenarios {

    private Scenarios() {
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
        list.add(of("feedback", Scenarios::feedback));
        list.add(of("menu", Scenarios::menu));
        list.add(of("pay", Scenarios::pay));
        list.add(of("pay-form", Scenarios::payForm));
        list.add(of("pay-ignored", Scenarios::payIgnored));
        list.add(of("double-submit", Scenarios::doubleSubmit));
        list.add(of("forged-clicks", Scenarios::forgedClicks));
        list.add(of("baltop", Scenarios::baltop));
        list.add(of("extras", Scenarios::extras));
        list.add(of("command-cooldown", Scenarios::commandCooldown));
        list.addAll(FeatureScenarios.all());
        return list;
    }

    static void feedback(E2E e2e) {
        String name = e2e.name("Feedback");
        Bot a = e2e.bot(name);
        a.clearLogs();
        a.command("pay " + name + " 10");
        e2e.sleep(1500);
        e2e.log("chat=" + a.chat() + " actionbar=" + a.actionBar() + " titles=" + a.titles());
        e2e.expect(a.anyFeedbackContains("yourself"), "self-pay feedback");
    }

    static void menu(E2E e2e) {
        String MENUBOT = e2e.name("Menu");
        e2e.step("join");
        Bot a = e2e.bot(MENUBOT);
        e2e.step("open /menu");
        a.command("menu");
        Bot.SeenDialog menu = e2e.dialog(a, "SiftVanilla");
        e2e.expect(menu.button("Money") != null, "a Money button in " + menu.buttons());
        e2e.expect(menu.bodyText().contains("$"), "the balance in the body: " + menu.body());
        e2e.step("open the money page");
        e2e.click(a, "Money");
        Bot.SeenDialog money = e2e.dialog(a, "Money");
        e2e.expect(money.button("Pay a player") != null, "a pay button");
        e2e.step("back to the menu");
        e2e.click(a, "Back");
        e2e.dialog(a, "SiftVanilla");
        e2e.step("pause-menu route");
        a.rawClick("siftcore:hub/money", null);
        e2e.dialog(a, "Money");
    }

    static void pay(E2E e2e) {
        String PAYERBOT = e2e.name("Payer");
        String PAYEEBOT = e2e.name("Payee");
        Bot a = e2e.bot(PAYERBOT);
        Bot b = e2e.bot(PAYEEBOT);
        e2e.step("fund the payer");
        e2e.console("eco set " + PAYERBOT + " 500k");
        e2e.eventually(() -> e2e.money(PAYERBOT) == 500_000, "payer has $500,000");
        long before = e2e.money(PAYEEBOT);
        e2e.step("small payment goes through without confirmation");
        a.clearLogs();
        b.clearLogs();
        a.command("pay " + PAYEEBOT + " 100");
        e2e.eventually(() -> e2e.money(PAYEEBOT) == before + 100, "payee received $100");
        e2e.eventually(() -> a.chatContains("You paid " + PAYEEBOT + " $100."), "payer receipt in chat: " + a.chat());
        e2e.eventually(() -> b.chatContains(PAYERBOT + " paid you $100."), "payee notice in chat: " + b.chat());
        e2e.step("cancelling a confirmation moves nothing");
        long payer = e2e.money(PAYERBOT);
        e2e.sleep(2_100);
        a.command("pay " + PAYEEBOT + " 120k");
        e2e.dialog(a, "Confirm payment");
        e2e.click(a, "Cancel");
        e2e.sleep(500);
        e2e.expect(e2e.money(PAYERBOT) == payer, "payer unchanged after cancel");
        e2e.step("large payment asks for confirmation");
        e2e.sleep(2_100);
        a.command("pay " + PAYEEBOT + " 150k");
        Bot.SeenDialog confirm = e2e.dialog(a, "Confirm payment");
        e2e.expect(confirm.bodyText().contains("$150,000"), "exact amount in the confirmation: " + confirm.body());
        e2e.expect(e2e.money(PAYEEBOT) == before + 100, "nothing moved before confirming");
        e2e.click(a, "Pay");
        e2e.eventually(() -> e2e.money(PAYEEBOT) == before + 100 + 150_000, "payee received $150,000");
        payer = e2e.money(PAYERBOT);
        e2e.step("daily limit refuses more than the limit");
        a.clearLogs();
        e2e.sleep(2_100);
        a.command("pay " + PAYEEBOT + " 200k");
        e2e.eventually(() -> a.anyFeedbackContains("more today"), "a daily limit message: " + a.actionBar());
        final long afterLimit = payer;
        e2e.expect(e2e.money(PAYERBOT) == afterLimit, "nothing sent over the limit");
        e2e.step("cannot pay yourself, zero or garbage");
        a.clearLogs();
        e2e.sleep(2_100);
        a.command("pay " + PAYERBOT + " 10");
        e2e.eventually(() -> a.anyFeedbackContains("yourself"), "self payment refused");
        e2e.sleep(2_100);
        a.command("pay " + PAYEEBOT + " 0");
        e2e.eventually(() -> a.anyFeedbackContains("more than zero"), "zero refused");
        e2e.sleep(2_100);
        a.command("pay " + PAYEEBOT + " 1.2345k");
        e2e.eventually(() -> a.anyFeedbackContains("whole amount"), "fractional refused");
        e2e.expect(e2e.money(PAYERBOT) == afterLimit, "nothing moved by refused payments");
    }

    /** A payment from an ignored player arrives without the "paid you" notice. */
    static void payIgnored(E2E e2e) {
        String payerName = e2e.name("IgnPayer");
        String payeeName = e2e.name("IgnPayee");
        Bot payer = e2e.bot(payerName);
        Bot payee = e2e.bot(payeeName);
        var ignores = e2e.feature(net.siftvanilla.siftcore.feature.chat.ChatFeature.class).ignores();
        e2e.console("eco set " + payerName + " 10k");
        e2e.eventually(() -> e2e.money(payerName) == 10_000, "the payer has $10,000");
        payee.command("ignore " + payerName);
        e2e.eventually(() -> ignores.ignores(e2e.uuid(payeeName), e2e.uuid(payerName)), "the payee ignores the payer");

        e2e.step("the money arrives, the notice doesn't");
        long before = e2e.money(payeeName);
        payee.clearLogs();
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> e2e.money(payeeName) == before + 100, "the payee received $100");
        e2e.eventually(() -> payer.chatContains("You paid " + payeeName + " $100."), "the payer's receipt: " + payer.chat());
        e2e.sleep(1_000);
        e2e.expect(!payee.chatContains("paid you"), "no notice from an ignored player: " + payee.chat());

        e2e.step("once unignored, notices come back");
        payee.command("unignore " + payerName);
        e2e.eventually(() -> !ignores.ignores(e2e.uuid(payeeName), e2e.uuid(payerName)), "not ignored");
        e2e.sleep(2_100);
        payer.command("pay " + payeeName + " 100");
        e2e.eventually(() -> payee.chatContains(payerName + " paid you $100."), "the notice: " + payee.chat());
    }

    static void payForm(E2E e2e) {
        String FORMBOT = e2e.name("Former");
        String FORMTARGET = e2e.name("FormTgt");
        Bot a = e2e.bot(FORMBOT);
        e2e.bot(FORMTARGET);
        e2e.console("eco set " + FORMBOT + " 10k");
        e2e.eventually(() -> e2e.money(FORMBOT) == 10_000, "funded");
        e2e.step("invalid amount keeps typed values and shows an error");
        a.command("pay");
        e2e.dialog(a, "Pay a player");
        e2e.click(a, "Submit", Map.of("player", FORMTARGET, "amount", "abc"));
        Bot.SeenDialog retry = e2e.dialog(a, "Pay a player");
        e2e.expect(retry.bodyText().contains("not an amount"), "error in the body: " + retry.body());
        e2e.step("payment refusals come back in the form with what was typed, before anything closes");
        int cleared = a.dialogsCleared();
        int seen = a.dialogs().size();
        e2e.click(a, "Submit", Map.of("player", FORMBOT, "amount", "500"));
        e2e.eventually(() -> a.dialogs().size() > seen, "the form comes back");
        Bot.SeenDialog self = a.dialogs().getLast();
        e2e.expect(self.title().equals("Pay a player") && self.bodyText().contains("You can't do that to yourself."),
            "paying yourself is refused in the form: " + self.body());
        e2e.expect(FORMBOT.equals(self.initial("player")) && "500".equals(self.initial("amount")), "the typed values: " + self.initial());
        int seenMore = a.dialogs().size();
        e2e.click(a, "Submit", Map.of("player", FORMTARGET, "amount", "50k"));
        e2e.eventually(() -> a.dialogs().size() > seenMore, "the form comes back again");
        Bot.SeenDialog poor = a.dialogs().getLast();
        e2e.expect(poor.bodyText().contains("You need $50,000 for that."), "not enough money, in the form: " + poor.body());
        e2e.expect(a.dialogsCleared() == cleared, "the form never closed in between");
        e2e.expect(e2e.money(FORMTARGET) == 0 && e2e.money(FORMBOT) == 10_000, "nothing moved");

        e2e.step("valid submission pays");
        e2e.click(a, "Submit", Map.of("player", FORMTARGET, "amount", "1.5k"));
        e2e.eventually(() -> e2e.money(FORMTARGET) == 1_500, "target got $1,500");
        e2e.expect(e2e.money(FORMBOT) == 8_500, "payer has $8,500");

        e2e.step("from the Money page, the form and the leaderboard go back to it");
        e2e.sleep(500);
        a.command("menu");
        e2e.dialog(a, "SiftVanilla");
        e2e.click(a, "Money");
        e2e.dialog(a, "Money");
        e2e.click(a, "Pay a player");
        Bot.SeenDialog form = e2e.dialog(a, "Pay a player");
        e2e.expect(form.button("Back") != null && form.button("Cancel") == null, "Back instead of Cancel: " + form.buttons());
        e2e.click(a, "Back");
        e2e.dialog(a, "Money");
        e2e.click(a, "Richest players");
        Bot.SeenDialog top = e2e.dialog(a, "Richest players");
        e2e.expect(top.button("Back") != null && top.button("Close") == null, "Back instead of Close: " + top.buttons());
        e2e.click(a, "Back");
        e2e.dialog(a, "Money");
    }

    static void doubleSubmit(E2E e2e) {
        String DOUBLEBOT = e2e.name("Doubler");
        String DOUBLETARGET = e2e.name("DoubleTgt");
        Bot a = e2e.bot(DOUBLEBOT);
        e2e.bot(DOUBLETARGET);
        e2e.console("eco set " + DOUBLEBOT + " 300k");
        e2e.eventually(() -> e2e.money(DOUBLEBOT) == 300_000, "funded");
        a.command("pay " + DOUBLETARGET + " 200k");
        Bot.SeenDialog confirm = e2e.dialog(a, "Confirm payment");
        e2e.step("click confirm three times instantly");
        a.clickButton("Pay", Map.of());
        a.clickButton("Pay", Map.of());
        a.clickButton("Pay", Map.of());
        e2e.eventually(() -> e2e.money(DOUBLETARGET) == 200_000, "first click paid");
        e2e.sleep(1500);
        e2e.expect(e2e.money(DOUBLETARGET) == 200_000, "replayed clicks paid nothing extra: " + e2e.money(DOUBLETARGET));
        e2e.expect(confirm != null, "confirmation was shown");
    }

    static void forgedClicks(E2E e2e) {
        String FORGEBOT = e2e.name("Forger");
        String FORGEVICTIM = e2e.name("Victim");
        Bot a = e2e.bot(FORGEBOT);
        Bot victim = e2e.bot(FORGEVICTIM);
        e2e.console("eco set " + FORGEVICTIM + " 100k");
        e2e.eventually(() -> e2e.money(FORGEVICTIM) == 100_000, "victim funded");
        e2e.step("the victim opens a confirmation; the attacker replays its button id");
        victim.command("pay " + FORGEBOT + " 100k");
        Bot.SeenDialog confirm = e2e.dialog(victim, "Confirm payment");
        String id = confirm.button("Pay").actionId();
        a.rawClick(id, new CompoundTag());
        e2e.sleep(800);
        e2e.expect(e2e.money(FORGEVICTIM) == 100_000, "a token is bound to the player it was shown to");
        e2e.step("random and malformed ids are ignored");
        a.rawClick("siftcore:ui/zzzzzz/0", null);
        a.rawClick("siftcore:ui/notanumber/x", null);
        a.rawClick("siftcore:hub/does-not-exist", null);
        CompoundTag hostile = new CompoundTag();
        hostile.put("amount", StringTag.valueOf("999999999999"));
        a.rawClick("siftcore:ui/1/0", hostile);
        e2e.sleep(800);
        e2e.expect(!a.disconnected(), "the attacker stays connected (no server error)");
        e2e.expect(e2e.money(FORGEVICTIM) == 100_000, "nothing moved");
        e2e.step("the victim's own click still works once");
        e2e.click(victim, "Pay");
        e2e.eventually(() -> e2e.money(FORGEVICTIM) == 0, "victim paid once");
        e2e.step("replaying the consumed token does nothing");
        victim.rawClick(id, new CompoundTag());
        e2e.sleep(800);
        e2e.expect(e2e.money(FORGEBOT) == 100_000, "attacker received exactly $100,000: " + e2e.money(FORGEBOT));
    }

    static void extras(E2E e2e) {
        String name = e2e.name("Extra");
        String other = e2e.name("Other");
        Bot a = e2e.bot(name);
        e2e.bot(other);
        e2e.step("/rules opens the rules notice");
        a.command("rules");
        Bot.SeenDialog rules = e2e.dialog(a, "Rules");
        e2e.expect(rules.bodyText().contains("No cheats"), "rules text: " + rules.body());
        e2e.click(a, "I understand");
        e2e.eventually(() -> a.dialog() == null, "the notice closes");
        e2e.step("/help links to the menu");
        a.command("help");
        e2e.dialog(a, "Getting started");
        e2e.click(a, "Open the menu");
        e2e.dialog(a, "SiftVanilla");
        e2e.step("/ping and /seen");
        a.clearLogs();
        a.command("ping");
        e2e.eventually(() -> a.actionBarContains("Your ping is"), "ping on the action bar: " + a.actionBar());
        a.command("seen " + other);
        e2e.eventually(() -> a.chatContains(other + " is online now."), "seen online: " + a.chat());
    }

    /**
     * A cooldown in commands.yml holds back any SiftCore command, here /balance (which has no cooldown code of its
     * own), with its subcommands and aliases, and it applies with /sift reload.
     */
    static void commandCooldown(E2E e2e) throws Exception {
        String name = e2e.name("Cooler");
        Bot a = e2e.bot(name);
        Path file = e2e.services().plugin().getDataFolder().toPath().resolve("commands.yml");
        String original = Files.readString(file);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(original);
        yaml.set("commands.balance.cooldown", "30s");
        Files.writeString(file, yaml.saveToString());
        e2e.console("sift reload");
        try {
            e2e.step("the first /balance answers");
            a.clearLogs();
            a.command("balance");
            e2e.eventually(() -> a.chatContains("You have "), "the balance: " + a.chat());
            e2e.step("the next one, also through an alias, waits for the cooldown");
            e2e.sleep(1_500);
            a.clearLogs();
            a.command("bal");
            e2e.eventually(() -> a.actionBarContains("before doing that again"), "the cooldown message: " + a.actionBar());
            e2e.expect(!a.chatContains("You have "), "no balance this time: " + a.chat());
        } finally {
            Files.writeString(file, original);
            e2e.console("sift reload");
        }
        e2e.step("without the cooldown it answers again");
        e2e.sleep(1_500);
        a.clearLogs();
        a.command("balance");
        e2e.eventually(() -> a.chatContains("You have "), "the balance after the reload: " + a.chat());
    }

    static void baltop(E2E e2e) {
        String TOPBOT = e2e.name("Topper");
        Bot a = e2e.bot(TOPBOT);
        e2e.console("eco give " + TOPBOT + " 1b");
        e2e.step("leaderboard refresh");
        e2e.console("eco set " + TOPBOT + " 1b");
        a.command("baltop");
        Bot.SeenDialog top = e2e.dialog(a, "Richest players");
        e2e.expect(top.button("Back") != null || top.button("Close") != null, "a footer button");
    }
}
