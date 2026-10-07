package net.siftvanilla.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;

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
        list.add(of("double-submit", Scenarios::doubleSubmit));
        list.add(of("forged-clicks", Scenarios::forgedClicks));
        list.add(of("baltop", Scenarios::baltop));
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
        e2e.step("valid submission pays");
        e2e.click(a, "Submit", Map.of("player", FORMTARGET, "amount", "1.5k"));
        e2e.eventually(() -> e2e.money(FORMTARGET) == 1_500, "target got $1,500");
        e2e.expect(e2e.money(FORMBOT) == 8_500, "payer has $8,500");
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
