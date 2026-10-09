package net.siftvanilla.e2e;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.siftvanilla.siftcore.SiftCore;
import net.siftvanilla.siftcore.SiftCorePlugin;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Dialogs;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Core behaviour every feature relies on: player-name arguments never give vanished staff away, dialogs waiting in
 * chat survive browsing menus (and the other way round) and the screens' time to live, finishing buttons close at
 * once, an expired click never leaves a waiting screen behind, a reconnect keeps the player's settings, and the
 * last-seen time of players online at shutdown is written.
 */
final class CoreScenarios {

    private CoreScenarios() {
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
        list.add(of("core-vanish-names", CoreScenarios::vanishNames));
        list.add(of("core-chat-dialogs", CoreScenarios::chatDialogs));
        list.add(of("core-dialog-closes", CoreScenarios::dialogCloses));
        list.add(of("core-expired-click", CoreScenarios::expiredClick));
        list.add(of("core-duplicate-login", CoreScenarios::duplicateLogin));
        list.add(of("core-last-seen", CoreScenarios::lastSeen));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What the server suggests to a player for a partly typed command (the same Brigadier tree and suggestion
     * providers a client's Tab asks), computed on the player's thread.
     */
    private static List<String> suggestions(E2E e2e, String name, String typed) {
        return e2e.onPlayer(name, () -> {
            CommandSourceStack source = ((CraftPlayer) e2e.player(name)).getHandle().createCommandSourceStack();
            CommandDispatcher<CommandSourceStack> dispatcher = MinecraftServer.getServer().getCommands().getDispatcher();
            ParseResults<CommandSourceStack> parse = dispatcher.parse(typed, source);
            try {
                List<String> texts = new ArrayList<>();
                for (Suggestion suggestion : dispatcher.getCompletionSuggestions(parse).get(5, TimeUnit.SECONDS).getList()) {
                    texts.add(suggestion.getText());
                }
                return texts;
            } catch (Exception e) {
                throw new E2E.Failure("no suggestions for '" + typed + "': " + e);
            }
        });
    }

    private static View notice(String title, String button, Button.Handler handler) {
        return new View(View.Kind.NOTICE, Component.text(title), List.of(), List.of(), List.of(Button.of(Component.text(button), handler)),
            null, 1, true);
    }

    private static void show(E2E e2e, String name, View view) {
        e2e.onPlayer(name, () -> {
            e2e.services().dialogs().show(e2e.player(name), view);
            return null;
        });
    }

    private static void openChest(E2E e2e, Bot bot, String title) {
        e2e.onPlayer(bot.name, () -> e2e.player(bot.name).openInventory(Bukkit.createInventory(null, 27, Component.text(title))));
        e2e.eventually(() -> bot.screen() != null && bot.screen().title().equals(title), "the chest '" + title + "' is open");
    }

    /** A message sent to the player {@code ticks} after now, on their thread: a marker in the packet order. */
    private static void markerLater(Services services, Player player, String text, long ticks) {
        services.scheduler().entityLater(player, () -> player.sendMessage(Component.text(text)), null, ticks);
    }

    // ------------------------------------------------------------------ vanish

    /**
     * Tab after /pay or /seen lists the online players the sender may see: a vanished moderator is left out for
     * players, is listed for staff who see vanished players, and comes back once visible. /tpa can't find them. The
     * vanish binding also hides a player the server has not hidden yet.
     */
    static void vanishNames(E2E e2e) {
        String MOD = e2e.name("CoreMod");
        String WATCHER = e2e.name("CoreWatch");
        String OTHER = e2e.name("CoreOther");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot watcher = e2e.bot(WATCHER);
        e2e.bot(OTHER);
        UUID modId = e2e.uuid(MOD);
        UUID otherId = e2e.uuid(OTHER);
        VanishStatus bound = e2e.services().commands().vanish();
        try {
            e2e.step("before vanishing, everyone is suggested");
            List<String> before = suggestions(e2e, WATCHER, "pay ");
            e2e.expect(before.contains(MOD) && before.contains(OTHER), "both online players after /pay: " + before);

            e2e.step("the moderator vanishes");
            mod.command("vanish");
            e2e.eventually(() -> mod.actionBarContains("You are vanished"), "vanished: " + mod.actionBar());
            e2e.eventually(() -> e2e.services().commands().vanish().vanished(modId), "the commands see the staff feature's vanish");
            e2e.eventually(() -> !e2e.onPlayer(WATCHER, () -> e2e.player(WATCHER).canSee(e2e.player(MOD))), "the server hides the moderator");

            e2e.step("Tab with nothing typed leaves the vanished moderator out for players");
            for (String command : List.of("pay ", "seen ", "balance ", "stats ", "tpa ", "msg ")) {
                List<String> names = suggestions(e2e, WATCHER, command);
                e2e.expect(!names.contains(MOD), "no vanished name after /" + command.strip() + ": " + names);
                e2e.expect(names.contains(OTHER), "the visible player after /" + command.strip() + ": " + names);
            }
            List<String> staff = suggestions(e2e, MOD, "pay ");
            e2e.expect(staff.contains(MOD) && staff.contains(WATCHER), "the moderator (who sees vanished staff) gets everyone: " + staff);

            e2e.step("with two letters typed the name only comes up as a known name, once");
            String prefix = MOD.substring(0, 4);
            List<String> known = suggestions(e2e, WATCHER, "seen " + prefix);
            e2e.expect(known.stream().filter(MOD::equals).count() == 1, "the known name once: " + known);

            e2e.step("/tpa can't find a vanished player");
            watcher.clearLogs();
            mod.clearLogs();
            watcher.command("tpa " + MOD);
            e2e.eventually(() -> watcher.anyFeedbackContains(MOD + " is not online."), "not online: " + watcher.actionBar() + " " + watcher.chat());
            e2e.sleep(500);
            e2e.expect(!mod.chatContains(WATCHER), "no request reached the moderator: " + mod.chat());

            e2e.step("the vanish binding hides a player the server has not hidden yet");
            e2e.services().commands().vanish(player -> player.equals(otherId) || bound.vanished(player));
            e2e.expect(e2e.onPlayer(WATCHER, () -> e2e.player(WATCHER).canSee(e2e.player(OTHER))), "the server still shows them");
            for (String command : List.of("pay ", "tpa ", "msg ")) {
                List<String> window = suggestions(e2e, WATCHER, command);
                e2e.expect(!window.contains(OTHER), "left out of Tab after /" + command.strip() + ": " + window);
                e2e.expect(!window.contains(MOD), "and the vanished moderator too: " + window);
            }
            e2e.expect(suggestions(e2e, MOD, "tpa ").contains(OTHER), "staff who see vanished players still get them after /tpa");
            watcher.clearLogs();
            watcher.command("tpa " + OTHER);
            e2e.eventually(() -> watcher.anyFeedbackContains(OTHER + " is not online."), "and not found: " + watcher.actionBar());
            e2e.services().commands().vanish(bound);

            e2e.step("once visible again the moderator is suggested again");
            mod.command("vanish");
            e2e.eventually(() -> !e2e.services().commands().vanish().vanished(modId), "visible again");
            e2e.eventually(() -> e2e.onPlayer(WATCHER, () -> e2e.player(WATCHER).canSee(e2e.player(MOD))), "the server shows them");
            List<String> after = suggestions(e2e, WATCHER, "pay ");
            e2e.expect(after.contains(MOD), "back in Tab: " + after);
        } finally {
            e2e.services().commands().vanish(bound);
            if (e2e.services().commands().vanish().vanished(modId) && Bukkit.getPlayerExact(MOD) != null) {
                mod.command("vanish");
                e2e.sleep(500);
            }
            e2e.console("deop " + MOD);
        }
    }

    // ------------------------------------------------------------------ dialogs

    /**
     * A teleport request's answer waits in chat while the player opens many menus, and still works; many dialogs
     * embedded in chat don't expire the dialog on screen either; and the answer outlives the screens' 15 minutes.
     */
    static void chatDialogs(E2E e2e) throws Exception {
        String ASKER = e2e.name("CoreAsk");
        String HOST = e2e.name("CoreHost");
        Bot asker = e2e.bot(ASKER);
        Bot host = e2e.bot(HOST);

        e2e.step("a teleport request arrives in chat");
        asker.command("tpa " + HOST);
        e2e.eventually(() -> host.chatContains(ASKER + " wants to teleport to you."), "the request in chat: " + host.chat());

        e2e.step("the host opens twelve menus before answering");
        for (int i = 0; i < 12; i++) {
            show(e2e, HOST, notice("Browse " + i, "OK", null));
        }
        e2e.dialog(host, "Browse 11");
        e2e.expect(e2e.services().dialogs().sessionCount() >= 9, "the sessions are there: " + e2e.services().dialogs().sessionCount());

        e2e.step("the answer from chat still works");
        host.clearMessages();
        e2e.expect(host.openChatDialog("wants to teleport to you"), "the chat message opens its dialog: " + host.chatDialogs());
        e2e.dialog(host, "Teleport request");
        e2e.click(host, "Accept");
        e2e.eventually(() -> asker.actionBarContains(HOST + " accepted your request"), "accepted: " + asker.actionBar());
        e2e.expect(!host.anyFeedbackContains("expired"), "no expired message: " + host.actionBar() + " " + host.chat());

        e2e.step("forty dialogs embedded in chat don't expire the dialog on screen");
        AtomicInteger clicked = new AtomicInteger();
        show(e2e, HOST, notice("Still here", "Count", submission -> clicked.incrementAndGet()));
        e2e.dialog(host, "Still here");
        e2e.onPlayer(HOST, () -> {
            Dialogs dialogs = e2e.services().dialogs();
            for (int i = 0; i < 40; i++) {
                dialogs.inline(e2e.player(HOST), notice("Request " + i, "Accept", null));
            }
            return null;
        });
        host.clearMessages();
        e2e.expect(host.clickButton("Count", Map.of()), "can click Count");
        e2e.eventually(() -> clicked.get() == 1, "the click on screen ran");
        e2e.sleep(500);
        e2e.expect(!host.anyFeedbackContains("expired"), "no expired message: " + host.actionBar() + " " + host.chat());

        e2e.step("twenty minutes on, a screen has expired but an answer waiting in chat has not");
        String LATE = e2e.name("CoreLate");
        Bot late = e2e.bot(LATE);
        host.clearLogs();
        late.command("tpa " + HOST);
        e2e.eventually(() -> host.chatContains(LATE + " wants to teleport to you."), "the request in chat: " + host.chat());
        show(e2e, HOST, notice("Old screen", "OK", submission -> { }));
        e2e.dialog(host, "Old screen");
        try (AutoCloseable later = shiftDialogClock(e2e, 20 * 60 * 1000L)) {
            host.clearMessages();
            e2e.expect(host.clickButton("OK", Map.of()), "can click OK on the old screen");
            e2e.eventually(() -> host.anyFeedbackContains("That menu expired"), "the 20-minute-old screen expired: " + host.actionBar());
            host.clearMessages();
            e2e.expect(host.openChatDialog(LATE + " wants to teleport to you"), "the chat message opens its dialog: " + host.chatDialogs());
            e2e.dialog(host, "Teleport request");
            e2e.click(host, "Accept");
            e2e.eventually(() -> late.actionBarContains(HOST + " accepted your request"), "accepted: " + late.actionBar());
            e2e.expect(!host.anyFeedbackContains("expired"), "no expired message: " + host.actionBar() + " " + host.chat());
        }
    }

    /**
     * Moves the dialog sessions' clock {@code millis} ahead until closed, as if that much time had passed for every
     * dialog already shown (the requests themselves keep their own time).
     */
    private static AutoCloseable shiftDialogClock(E2E e2e, long millis) throws Exception {
        Field sessionsField = Dialogs.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Object sessions = sessionsField.get(e2e.services().dialogs());
        Field clockField = sessions.getClass().getDeclaredField("clock");
        clockField.setAccessible(true);
        LongSupplier original = (LongSupplier) clockField.get(sessions);
        clockField.set(sessions, (LongSupplier) () -> original.getAsLong() + millis);
        return () -> clockField.set(sessions, original);
    }

    /**
     * A button that finishes something closes its dialog at once when its handler shows nothing, also in a dialog whose
     * other buttons lead on; a button that leads on keeps the dialog a moment for the next screen.
     */
    static void dialogCloses(E2E e2e) {
        String name = e2e.name("CoreClose");
        Bot bot = e2e.bot(name);
        Services services = e2e.services();
        Button.Handler finish = submission -> markerLater(services, submission.player(), "core-marker-finish", 3);
        Button.Handler next = submission -> markerLater(services, submission.player(), "core-marker-next", 3);
        View mixed = services.templates().list(Component.text("Mixed buttons"), List.of(),
            List.of(Button.of(Component.text("Finish"), finish).closes(), Button.of(Component.text("Next"), next)), 1, s -> { });

        e2e.step("a dialog with a closing button and one that leads on stays on screen after a click");
        show(e2e, name, mixed);
        Bot.SeenDialog seen = e2e.dialog(bot, "Mixed buttons");
        e2e.expect("none".equals(seen.after()), "rendered without a client-side close: " + seen.after());

        e2e.step("the closing button closes it before anything later the click sends");
        int cleared = bot.dialogsCleared();
        e2e.expect(bot.clickButton("Finish", Map.of()), "can click Finish");
        e2e.eventually(() -> bot.chatContains("core-marker-finish"), "the marker arrived: " + bot.chat());
        e2e.expect(bot.dialogsCleared() == cleared + 1, "closed at once, before the marker three ticks later");

        e2e.step("the button that leads on waits a moment for the next screen");
        show(e2e, name, mixed);
        e2e.dialog(bot, "Mixed buttons");
        int cleared2 = bot.dialogsCleared();
        e2e.expect(bot.clickButton("Next", Map.of()), "can click Next");
        e2e.eventually(() -> bot.chatContains("core-marker-next"), "the marker arrived: " + bot.chat());
        e2e.expect(bot.dialogsCleared() == cleared2, "still open three ticks later (the router's grace)");
        e2e.eventually(() -> bot.dialogsCleared() == cleared2 + 1, "closed after the grace, as nothing came");

        e2e.step("a dialog whose buttons all close does so on the client");
        show(e2e, name, mixed.closing());
        Bot.SeenDialog closing = e2e.dialog(bot, "Mixed buttons");
        e2e.expect("close".equals(closing.after()), "closes on the client: " + closing.after());
    }

    /**
     * A click on a dialog whose session is gone is told the menu expired and closes every screen (a waiting dialog
     * leaves the client on its waiting screen, which only a container close leaves); an old dialog clicked again
     * after its answer closes like that only when it was a waiting one.
     */
    static void expiredClick(E2E e2e) {
        String name = e2e.name("CoreExpire");
        Bot bot = e2e.bot(name);

        e2e.step("a waiting dialog whose session is gone");
        openChest(e2e, bot, "Under");
        show(e2e, name, notice("Search", "Search", s -> { }).waiting());
        Bot.SeenDialog search = e2e.dialog(bot, "Search");
        e2e.expect("wait_for_response".equals(search.after()), "the dialog waits: " + search.after());
        bot.clearLogs();
        int cleared = bot.dialogsCleared();
        long gone = ThreadLocalRandom.current().nextLong(1L << 40, Long.MAX_VALUE);
        bot.rawClick("siftcore:ui/" + Long.toString(gone, 36) + "/0", new CompoundTag());
        e2e.eventually(() -> bot.anyFeedbackContains("That menu expired"), "told it expired: " + bot.actionBar() + " " + bot.chat());
        e2e.eventually(() -> bot.dialogsCleared() > cleared, "the dialog closed");
        e2e.eventually(() -> bot.screen() == null, "the container close was sent too (it leaves a waiting screen)");

        e2e.step("an old waiting dialog clicked again after its answer");
        show(e2e, name, notice("Search again", "Search", s -> { }).waiting());
        Bot.SeenDialog again = e2e.dialog(bot, "Search again");
        String id = again.button("Search").actionId();
        int before = bot.dialogsCleared();
        e2e.expect(bot.clickButton("Search", Map.of()), "can click Search");
        e2e.eventually(() -> bot.dialogsCleared() > before, "answered (closed, nothing to show)");
        openChest(e2e, bot, "Under again");
        e2e.sleep(5_300);
        bot.clearLogs();
        bot.rawClick(id, new CompoundTag());
        e2e.eventually(() -> bot.anyFeedbackContains("That menu expired"), "told it expired: " + bot.actionBar());
        e2e.eventually(() -> bot.screen() == null, "the waiting screen is left too");

        e2e.step("an old dialog that stays on screen clicked again only closes the dialog");
        show(e2e, name, notice("Plain", "OK", s -> { }));
        Bot.SeenDialog plain = e2e.dialog(bot, "Plain");
        String plainId = plain.button("OK").actionId();
        int before2 = bot.dialogsCleared();
        e2e.expect(bot.clickButton("OK", Map.of()), "can click OK");
        e2e.eventually(() -> bot.dialogsCleared() > before2, "answered");
        openChest(e2e, bot, "Kept");
        e2e.sleep(5_300);
        bot.clearLogs();
        int before3 = bot.dialogsCleared();
        bot.rawClick(plainId, new CompoundTag());
        e2e.eventually(() -> bot.anyFeedbackContains("That menu expired"), "told it expired: " + bot.actionBar());
        e2e.eventually(() -> bot.dialogsCleared() > before3, "the dialog closed");
        e2e.sleep(800);
        e2e.expect(bot.screen() != null && bot.screen().title().equals("Kept"), "the screen under it stays: " + bot.screen());
        bot.closeScreen();
    }

    // ------------------------------------------------------------------ players

    /**
     * Logging in again while the old session is still on (after a client crash) keeps the player's settings: the new
     * login loads them before the old session quits, and that quit leaves them alone.
     */
    static void duplicateLogin(E2E e2e) {
        String name = e2e.name("CoreTwice");
        Bot first = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Services services = e2e.services();

        e2e.step("the player turns their sound volume down");
        services.settings().set(id, SharedSettings.SOUND_VOLUME, 40L, Change.command(name));
        e2e.expect(services.settings().number(id, SharedSettings.SOUND_VOLUME) == 40L, "40%");

        e2e.step("the same account logs in again while the first session is still on");
        Bot second = e2e.botAtSpawn(name);
        e2e.eventually(() -> first.disconnected(), "the first session was replaced: " + first.disconnectReason());
        e2e.eventually(() -> second.loaded(), "the new session is in");
        e2e.sleep(1_500);
        e2e.expect(services.settings().loaded(id), "the new session still has its settings after the old one quit");
        e2e.expect(services.settings().number(id, SharedSettings.SOUND_VOLUME) == 40L,
            "still 40%: " + services.settings().number(id, SharedSettings.SOUND_VOLUME));

        e2e.step("the new session's own quit forgets them");
        second.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, "left");
        e2e.eventually(() -> !services.settings().loaded(id), "forgotten after the quit");
        services.settings().set(id, SharedSettings.SOUND_VOLUME, 100L, Change.command(name));
    }

    /**
     * The players still online when the server stops get no quit event; SiftCore writes their last-seen time first
     * thing when it stops. Here that step runs on its own (the server keeps running) for a player who joined a while
     * ago.
     */
    static void lastSeen(E2E e2e) throws Exception {
        String name = e2e.name("CoreSeen");
        e2e.bot(name);
        UUID id = e2e.uuid(name);
        Services services = e2e.services();
        long joined = services.directory().get(id).orElseThrow().lastSeen();
        e2e.sleep(1_200);

        e2e.step("the shutdown step records everyone online");
        SiftCore core = ((SiftCorePlugin) Bukkit.getPluginManager().getPlugin("SiftCore")).core();
        Method record = SiftCore.class.getDeclaredMethod("recordOnlineSeen");
        record.setAccessible(true);
        long before = System.currentTimeMillis();
        record.invoke(core);
        long seen = services.directory().get(id).orElseThrow().lastSeen();
        e2e.expect(seen >= before && seen > joined, "last seen moved from the join (" + joined + ") to now (" + seen + ")");

        e2e.step("and stores it");
        services.database().flush();
        long stored = services.database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_seen FROM players WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }).get(10, TimeUnit.SECONDS);
        e2e.expect(stored == seen, "stored " + stored + ", expected " + seen);
    }
}
