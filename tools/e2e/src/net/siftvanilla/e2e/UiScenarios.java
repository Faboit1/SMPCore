package net.siftvanilla.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How dialogs behave after a click: menus stay on screen until the next one replaces them (no closing in between and
 * no waiting screen), a double click is answered once, searches show the waiting screen, and dialogs whose buttons
 * only close do so on the client at once.
 */
final class UiScenarios {

    private UiScenarios() {
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Scenario() {
            @Override
            public String name() {
                return "ui-dialog-flow";
            }

            @Override
            public void run(E2E e2e) {
                flow(e2e);
            }
        });
        list.add(new Scenario() {
            @Override
            public String name() {
                return "ui-pause-menu";
            }

            @Override
            public void run(E2E e2e) {
                pauseMenu(e2e);
            }
        });
        return list;
    }

    /** The client receives the SiftVanilla menu in the pause-screen and quick-action tags, and the menu works. */
    static void pauseMenu(E2E e2e) {
        Bot bot = e2e.bot(e2e.name("Pauser"));
        e2e.step("the client gets the menu dialog and both tags at login");
        e2e.log("dialog registry: " + bot.registry("minecraft:dialog"));
        e2e.expect(bot.registry("minecraft:dialog").contains("siftcore:hub"), "siftcore:hub is in the dialog registry: " + bot.registry("minecraft:dialog"));
        List<String> pause = bot.tag("minecraft:dialog", "minecraft:pause_screen_additions");
        e2e.expect(pause != null && pause.equals(List.of("siftcore:hub")),
            "the pause screen tag holds exactly the menu (one entry gives a direct button): " + pause);
        List<String> quick = bot.tag("minecraft:dialog", "minecraft:quick_actions");
        e2e.expect(quick != null && quick.contains("siftcore:hub"), "the quick actions tag holds the menu: " + quick);

        e2e.step("its buttons open the pages");
        bot.rawClick("siftcore:hub/money", null);
        e2e.dialog(bot, "Money");
    }

    static void flow(E2E e2e) {
        Bot bot = e2e.bot(e2e.name("Clicker"));

        e2e.step("the menu stays on screen until the next page replaces it");
        bot.clearLogs();
        bot.command("menu");
        Bot.SeenDialog menu = e2e.dialog(bot, "SiftVanilla");
        e2e.expect("none".equals(menu.after()), "the menu keeps its screen after a click: " + menu.after());
        int cleared = bot.dialogsCleared();
        e2e.click(bot, "Money");
        Bot.SeenDialog money = e2e.dialog(bot, "Money");
        e2e.expect(bot.dialogsCleared() == cleared, "the money page replaced the menu without a close in between");

        e2e.step("a double click is answered once, without an expired message");
        int shown = bot.dialogs().size();
        e2e.expect(bot.clickButton("Richest players", Map.of()), "can click Richest players in " + money.buttons());
        e2e.expect(bot.clickButton("Richest players", Map.of()), "can click it again before the answer arrives");
        e2e.dialog(bot, "Richest players");
        e2e.sleep(1_500);
        e2e.expect(bot.dialogs().size() == shown + 1, "exactly one new dialog: " + (bot.dialogs().size() - shown));
        e2e.expect(!bot.anyFeedbackContains("expired"), "no expired message: " + bot.chat() + " " + bot.actionBar());
        e2e.expect(bot.dialogsCleared() == cleared, "still nothing closed");

        e2e.step("a dialog whose buttons only close, closes on the client at once");
        bot.command("rules");
        Bot.SeenDialog rules = e2e.dialog(bot, "Rules");
        e2e.expect("close".equals(rules.after()), "the rules notice closes on click: " + rules.after());

        e2e.step("a search shows the waiting screen until the results arrive");
        bot.command("ah");
        e2e.eventually(() -> bot.screen() != null, "the auction house opened");
        bot.clickSlot(49);
        Bot.SeenDialog search = e2e.dialog(bot, "Search");
        e2e.expect("wait_for_response".equals(search.after()), "the search waits for the server: " + search.after());
    }
}
