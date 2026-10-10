package net.siftvanilla.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Dialogs;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;

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
        list.add(new Scenario() {
            @Override
            public String name() {
                return "ui-foreign-menu";
            }

            @Override
            public void run(E2E e2e) {
                foreignMenu(e2e);
            }
        });
        list.add(new Scenario() {
            @Override
            public String name() {
                return "ui-hub-back";
            }

            @Override
            public void run(E2E e2e) {
                hubBack(e2e);
            }
        });
        return list;
    }

    private static Inventory foreign() {
        return Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text("Foreign menu"));
    }

    /**
     * A container another plugin opens from a click (AxAuctions' menu from the main menu's auction button) is not
     * closed by the dialog router after its grace, from a pause-menu route or from a dialog button; a click that shows
     * nothing still closes the dialog.
     */
    static void foreignMenu(E2E e2e) {
        String name = e2e.name("Foreign");
        Bot bot = e2e.bot(name);
        Dialogs dialogs = e2e.services().dialogs();
        dialogs.route("e2e/foreign", player -> player.openInventory(foreign()));

        e2e.step("a route that opens another plugin's menu leaves it open");
        bot.rawClick("siftcore:e2e/foreign", null);
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Foreign menu"), "the menu opened");
        Bot.Screen opened = bot.screen();
        e2e.sleep(1_500);
        e2e.expect(bot.screen() == opened, "still open after the router's grace");
        bot.closeScreen();

        e2e.step("a dialog button that opens another plugin's menu leaves it open");
        Templates templates = e2e.services().templates();
        e2e.onPlayer(name, () -> {
            dialogs.show(e2e.player(name), templates.list(net.kyori.adventure.text.Component.text("Foreign test"), List.of(),
                List.of(Button.of(net.kyori.adventure.text.Component.text("Open it"), s -> s.player().openInventory(foreign())),
                    Button.of(net.kyori.adventure.text.Component.text("Nothing"), s -> { })), 1, null));
            return null;
        });
        e2e.dialog(bot, "Foreign test");
        e2e.expect(bot.clickButton("Open it", Map.of()), "can click it");
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals("Foreign menu"), "the menu opened");
        Bot.Screen fromDialog = bot.screen();
        e2e.sleep(1_500);
        e2e.expect(bot.screen() == fromDialog, "still open after the router's grace");
        bot.closeScreen();

        e2e.step("a button that shows nothing still closes its dialog");
        e2e.onPlayer(name, () -> {
            dialogs.show(e2e.player(name), templates.list(net.kyori.adventure.text.Component.text("Foreign test"), List.of(),
                List.of(Button.of(net.kyori.adventure.text.Component.text("Nothing"), s -> { })), 1, null));
            return null;
        });
        e2e.dialog(bot, "Foreign test");
        int cleared = bot.dialogsCleared();
        e2e.expect(bot.clickButton("Nothing", Map.of()), "can click it");
        e2e.eventually(() -> bot.dialogsCleared() > cleared, "the router closed the dialog");
    }

    /** Pages opened from the main menu lead back to it (and the Money page's pages back to the Money page). */
    static void hubBack(E2E e2e) {
        Bot bot = e2e.bot(e2e.name("Backer"));

        e2e.step("Report a player has Back to the main menu");
        bot.command("menu");
        e2e.dialog(bot, "SiftVanilla");
        e2e.click(bot, "Report a player");
        Bot.SeenDialog form = e2e.dialog(bot, "Report a player");
        e2e.expect(form.button("Back") != null && form.button("Cancel") == null, "Back instead of Cancel: " + form.buttons());
        e2e.click(bot, "Back");
        e2e.dialog(bot, "SiftVanilla");

        e2e.step("the rules' button returns to the main menu");
        e2e.click(bot, "Rules");
        Bot.SeenDialog rules = e2e.dialog(bot, "Rules");
        e2e.expect("none".equals(rules.after()), "the rules stay until the menu replaces them: " + rules.after());
        e2e.click(bot, "I understand");
        e2e.dialog(bot, "SiftVanilla");

        e2e.step("/rules on its own still closes at once");
        e2e.sleep(700);
        bot.command("rules");
        Bot.SeenDialog closing = e2e.dialog(bot, "Rules");
        e2e.expect("close".equals(closing.after()), "the /rules notice closes: " + closing.after());
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
