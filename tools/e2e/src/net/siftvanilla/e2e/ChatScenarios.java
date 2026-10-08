package net.siftvanilla.e2e;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.WrittenBookContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.feature.chat.ChatFeature;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * End-to-end scenarios of public chat (format, hover card, [item], mentions, anti-spam, the filter, chat lock and
 * slow mode), private messages (/msg, /r, social spy, /msgtoggle), ignore lists (/ignore and its dialogs, and the
 * teleport requests they block) and the settings dialog.
 */
final class ChatScenarios {

    /** Anti-spam allows one message a second by default; scenarios leave room for network and thread jitter. */
    private static final long CHAT_GAP = 1_400;

    private ChatScenarios() {
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
        list.add(of("chat-format", ChatScenarios::format));
        list.add(of("chat-ignore", ChatScenarios::ignore));
        list.add(of("chat-msg", ChatScenarios::privateMessages));
        list.add(of("chat-spam", ChatScenarios::spam));
        list.add(of("chat-links", ChatScenarios::links));
        list.add(of("chat-mention", ChatScenarios::mentions));
        list.add(of("chat-admin", ChatScenarios::admin));
        list.add(of("settings-dialog", ChatScenarios::settings));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** Says something in public chat after the anti-spam gap. */
    private static void say(E2E e2e, Bot bot, String message) {
        e2e.sleep(CHAT_GAP);
        bot.chat(message);
    }

    /** Runs a private message command after the anti-spam gap (private messages share its one-second gap). */
    private static void pm(E2E e2e, Bot bot, String command) {
        e2e.sleep(CHAT_GAP);
        bot.command(command);
    }

    private static boolean saw(Bot bot, String text) {
        return bot.anyFeedbackContains(text);
    }

    private static void expectSaw(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> saw(bot, text), bot.name + " sees '" + text + "' (chat " + bot.chat() + ", action bar " + bot.actionBar() + ")");
    }

    /** The newest chat component whose text contains {@code text}. */
    private static Component line(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.chatContains(text), bot.name + " gets a chat line with '" + text + "': " + bot.chat());
        List<Component> lines = bot.chatComponents();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).getString().contains(text)) {
                return lines.get(i);
            }
        }
        throw new E2E.Failure("no component for '" + text + "'");
    }

    /** The first part of a component tree whose own text equals {@code text}, or null. */
    private static Component part(Component root, String text) {
        if (root.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents plain && plain.text().equals(text)) {
            return root;
        }
        for (Component sibling : root.getSiblings()) {
            Component found = part(sibling, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first part of a component tree with a show_item hover, or null. */
    private static HoverEvent.ShowItem itemHover(Component root) {
        if (root.getStyle().getHoverEvent() instanceof HoverEvent.ShowItem item) {
            return item;
        }
        for (Component sibling : root.getSiblings()) {
            HoverEvent.ShowItem found = itemHover(sibling);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ChatFeature chat(E2E e2e) {
        return e2e.feature(ChatFeature.class);
    }

    private static void hold(E2E e2e, String name, ItemStack item) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).getInventory().setItemInMainHand(item);
            return null;
        });
    }

    /** The values a settings page shows, keyed like its inputs (what a client sends back when nothing is touched). */
    private static Map<String, Object> inputs(Bot.SeenDialog dialog) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, String> input : dialog.inputs().entrySet()) {
            values.put(input.getKey(), dialog.toggleValue(input.getKey()));
        }
        return values;
    }

    /** Waits for the Chat group's settings page. */
    private static Bot.SeenDialog chatGroup(E2E e2e, Bot bot) {
        Bot.SeenDialog dialog = e2e.dialog(bot, "Chat settings");
        e2e.expect(dialog.title().equals("Chat settings"), "the chat group: " + dialog.title());
        return dialog;
    }

    /** Waits for the list of settings groups (titled just Settings). */
    private static Bot.SeenDialog settingsList(E2E e2e, Bot bot) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().title().equals("Settings"), bot.name + " sees the settings list: "
            + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** Runs {@code body} with one config file changed, then restores it (both reloaded). */
    private static void withConfig(E2E e2e, String file, String from, String to, Body body) throws Exception {
        java.nio.file.Path path = org.bukkit.Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(file);
        String original = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        e2e.expect(original.contains(from), file + " contains '" + from + "'");
        java.nio.file.Files.writeString(path, original.replace(from, to), java.nio.charset.StandardCharsets.UTF_8);
        List<String> reload = e2e.consoleOutput("sift reload");
        e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed " + file + " reloads: " + reload);
        try {
            body.run(e2e);
        } finally {
            java.nio.file.Files.writeString(path, original, java.nio.charset.StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(e2e.player(name), placeholder);
    }

    private static boolean setting(E2E e2e, String name, Toggle toggle) {
        return e2e.services().settings().enabled(e2e.uuid(name), toggle);
    }

    // ------------------------------------------------------------------ scenarios

    static void format(E2E e2e) {
        String talkerName = e2e.name("Talker");
        String readerName = e2e.name("Reader");
        Bot talker = e2e.bot(talkerName);
        Bot reader = e2e.bot(readerName);
        e2e.console("eco set " + talkerName + " 12345");

        e2e.step("a chat line is the name, a colon and the message");
        reader.clearLogs();
        say(e2e, talker, "hello from the chat test");
        Component line = line(e2e, reader, "hello from the chat test");
        e2e.expect(line.getString().equals(talkerName + ": hello from the chat test"), "the unranked format: " + line.getString());

        e2e.step("the name shows a card on hover and starts a message on click");
        Component name = part(line, talkerName);
        e2e.expect(name != null, "the name is its own part: " + line);
        e2e.expect(name.getStyle().getHoverEvent() instanceof HoverEvent.ShowText, "a hover card on the name: " + name.getStyle());
        String card = ((HoverEvent.ShowText) name.getStyle().getHoverEvent()).value().getString();
        e2e.expect(card.contains(talkerName) && card.contains("Balance $12,345") && card.contains("Kills 0")
            && card.contains("Playtime") && card.contains("No team"), "the card's lines: " + card);
        e2e.expect(name.getStyle().getClickEvent() instanceof ClickEvent.SuggestCommand suggest
            && suggest.command().equals("/msg " + talkerName + " "), "a click that suggests /msg: " + name.getStyle().getClickEvent());

        e2e.step("[item] shows the held item with its hover");
        hold(e2e, talkerName, new ItemStack(Material.DIAMOND_SWORD));
        reader.clearLogs();
        say(e2e, talker, "check my [item] out");
        Component item = line(e2e, reader, "check my");
        e2e.expect(item.getString().equals(talkerName + ": check my [Diamond Sword] out"), "the item name in brackets: " + item.getString());
        HoverEvent.ShowItem hover = itemHover(item);
        e2e.expect(hover != null && BuiltInRegistries.ITEM.getKey(hover.item().item().value()).getPath().equals("diamond_sword"),
            "a show_item hover with the sword: " + hover);

        e2e.step("stacks show their amount, and only the first tag is replaced");
        hold(e2e, talkerName, new ItemStack(Material.DIAMOND, 5));
        reader.clearLogs();
        say(e2e, talker, "[i] for sale, [i]");
        e2e.eventually(() -> reader.chatContains(talkerName + ": [Diamond x5] for sale, [i]"), "the stack: " + reader.chat());

        e2e.step("a shulker box shows its contents, but never book pages");
        ItemStack book = ItemStack.of(Material.WRITTEN_BOOK);
        book.setData(DataComponentTypes.WRITTEN_BOOK_CONTENT, WrittenBookContent.writtenBookContent("Secret plans", talkerName)
            .addPage(net.kyori.adventure.text.Component.text("x".repeat(900)))
            .addPage(net.kyori.adventure.text.Component.text("y".repeat(900)))
            .build());
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(List.of(book, ItemStack.of(Material.EMERALD, 7))));
        hold(e2e, talkerName, box);
        reader.clearLogs();
        say(e2e, talker, "my [item] is full");
        Component boxLine = line(e2e, reader, "is full");
        HoverEvent.ShowItem boxHover = itemHover(boxLine);
        e2e.expect(boxHover != null, "a hover on the box: " + boxLine);
        ItemStack shownBox = CraftItemStack.asBukkitCopy(boxHover.item().create());
        ItemContainerContents shownContents = shownBox.getData(DataComponentTypes.CONTAINER);
        e2e.expect(shownBox.getType() == Material.SHULKER_BOX && shownContents != null, "the hover is the box with contents: " + shownBox);
        List<ItemStack> inside = shownContents.contents().stream().filter(stack -> !stack.isEmpty()).toList();
        ItemStack shownBook = inside.stream().filter(stack -> stack.getType() == Material.WRITTEN_BOOK).findFirst().orElse(null);
        e2e.expect(inside.stream().anyMatch(stack -> stack.getType() == Material.EMERALD && stack.getAmount() == 7),
            "the emeralds are in the hover: " + inside);
        WrittenBookContent shownPages = shownBook == null ? null : shownBook.getData(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        e2e.expect(shownPages != null && shownPages.title().raw().equals("Secret plans") && shownPages.author().equals(talkerName),
            "the book keeps its title and author: " + shownBook);
        e2e.expect(shownPages.pages().isEmpty(), "but not its pages: " + shownPages.pages().size() + " pages");

        e2e.step("a renamed item's name passes the word filter");
        ItemStack renamed = ItemStack.of(Material.IRON_SWORD);
        renamed.setData(DataComponentTypes.CUSTOM_NAME, net.kyori.adventure.text.Component.text("kys blade"));
        hold(e2e, talkerName, renamed);
        reader.clearLogs();
        say(e2e, talker, "behold [item]");
        Component renamedLine = line(e2e, reader, "behold");
        e2e.expect(renamedLine.getString().equals(talkerName + ": behold [*** blade]"), "the cleaned name: " + renamedLine.getString());
        HoverEvent.ShowItem renamedHover = itemHover(renamedLine);
        net.kyori.adventure.text.Component shownName = renamedHover == null ? null
            : CraftItemStack.asBukkitCopy(renamedHover.item().create()).getData(DataComponentTypes.CUSTOM_NAME);
        e2e.expect(shownName != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(shownName).equals("*** blade"), "the hover shows the cleaned name too: " + shownName);

        e2e.step("an empty hand leaves the tag as typed");
        e2e.sleep(6_000); // stay within the rate limit of five messages in ten seconds
        hold(e2e, talkerName, new ItemStack(Material.AIR));
        reader.clearLogs();
        say(e2e, talker, "nothing in my hands [item]");
        e2e.eventually(() -> reader.chatContains("nothing in my hands [item]"), "the tag stays: " + reader.chat());

        e2e.step("tags typed by players stay literal text");
        reader.clearLogs();
        say(e2e, talker, "<red>not red</red> <click:run_command:'/op me'>click");
        e2e.eventually(() -> reader.chatContains("<red>not red</red> <click:run_command:'/op me'>click"), "literal tags: " + reader.chat());
    }

    static void ignore(E2E e2e) {
        String loudName = e2e.name("Loud");
        String quietName = e2e.name("Quiet");
        String otherName = e2e.name("Other");
        String modName = e2e.name("IgnMod");
        Bot loud = e2e.bot(loudName);
        Bot quiet = e2e.bot(quietName);
        Bot other = e2e.bot(otherName);
        e2e.bot(modName);
        e2e.console("op " + modName);
        UUID loudId = e2e.uuid(loudName);
        UUID quietId = e2e.uuid(quietName);
        try {
            e2e.step("/ignore hides the ignored player's chat");
            quiet.clearLogs();
            quiet.command("ignore " + loudName);
            expectSaw(e2e, quiet, "You now ignore " + loudName);
            e2e.expect(chat(e2e).ignores().ignores(quietId, loudId), "IgnoreLookup reports the ignore");
            e2e.expect(!chat(e2e).ignores().ignores(loudId, quietId), "only one way");
            e2e.expect("1".equals(placeholder(e2e, quietName, "chat_ignoring")), "the ignoring placeholder: "
                + placeholder(e2e, quietName, "chat_ignoring"));
            quiet.clearLogs();
            other.clearLogs();
            say(e2e, loud, "can anybody hear me");
            e2e.eventually(() -> other.chatContains("can anybody hear me"), "others still read it: " + other.chat());
            e2e.sleep(800);
            e2e.expect(!quiet.chatContains("can anybody hear me"), "the ignoring player doesn't: " + quiet.chat());

            e2e.step("private messages are refused both ways");
            loud.clearLogs();
            loud.command("msg " + quietName + " please listen");
            expectSaw(e2e, loud, "You can't message " + quietName);
            quiet.clearLogs();
            quiet.command("msg " + loudName + " hi");
            expectSaw(e2e, quiet, "You ignore " + loudName);

            e2e.step("teleport requests from an ignored player never arrive");
            loud.clearLogs();
            quiet.clearLogs();
            loud.command("tpa " + quietName);
            expectSaw(e2e, loud, "You can't send " + quietName + " a teleport request");
            e2e.sleep(500);
            e2e.expect(!quiet.anyFeedbackContains("teleport"), "the target is never told: " + quiet.chat());

            e2e.step("staff can't be ignored");
            quiet.clearLogs();
            quiet.command("ignore " + modName);
            expectSaw(e2e, quiet, "they are staff");
            e2e.expect(!chat(e2e).ignores().ignores(quietId, e2e.uuid(modName)), "no ignore entry for staff");

            e2e.step("the list dialog stops ignoring after a confirmation");
            quiet.clearLogs();
            quiet.command("ignore list");
            Bot.SeenDialog list = e2e.dialog(quiet, "Ignored players");
            e2e.expect(list.bodyText().contains("You ignore 1 player") && list.button(loudName) != null, "the ignored player: " + list.body()
                + " " + list.buttons());
            e2e.click(quiet, loudName);
            Bot.SeenDialog confirm = e2e.dialog(quiet, "Stop ignoring");
            e2e.expect(confirm.bodyText().contains(loudName), "the name in the confirmation: " + confirm.body());
            e2e.click(quiet, "Stop ignoring");
            Bot.SeenDialog empty = e2e.dialog(quiet, "Ignored players");
            e2e.expect(empty.bodyText().contains("You don't ignore anyone"), "the list is empty: " + empty.body());
            e2e.expect(!chat(e2e).ignores().ignores(quietId, loudId), "the ignore is gone");
            quiet.clearLogs();
            say(e2e, loud, "and now can you hear me");
            e2e.eventually(() -> quiet.chatContains("and now can you hear me"), "chat reaches them again: " + quiet.chat());

            e2e.step("the form ignores by name and refuses unknown names");
            quiet.clearLogs();
            quiet.command("ignore");
            e2e.dialog(quiet, "Ignored players");
            e2e.click(quiet, "Ignore a player");
            e2e.dialog(quiet, "Ignore a player");
            e2e.click(quiet, "Ignore", Map.of("player", "NoSuchPlayer_x9"));
            Bot.SeenDialog error = e2e.dialog(quiet, "Ignore a player");
            e2e.expect(error.bodyText().contains("Nobody called NoSuchPlayer_x9"), "unknown name: " + error.body());
            e2e.click(quiet, "Ignore", Map.of("player", loudName));
            e2e.dialog(quiet, "Ignored players");
            e2e.eventually(() -> chat(e2e).ignores().ignores(quietId, loudId), "ignored through the form");

            e2e.step("/unignore and /ignore toggle it off again");
            quiet.clearLogs();
            quiet.command("unignore " + loudName);
            expectSaw(e2e, quiet, "You no longer ignore " + loudName);
            quiet.command("unignore " + loudName);
            expectSaw(e2e, quiet, "You don't ignore " + loudName);
            quiet.command("ignore " + loudName);
            e2e.eventually(() -> chat(e2e).ignores().ignores(quietId, loudId), "ignored again");
            quiet.command("ignore " + loudName);
            e2e.eventually(() -> !chat(e2e).ignores().ignores(quietId, loudId), "the second /ignore stops ignoring");
            quiet.command("ignore " + quietName);
            expectSaw(e2e, quiet, "You can't do that to yourself");
        } finally {
            e2e.console("deop " + modName);
        }
    }

    static void privateMessages(E2E e2e) {
        String aName = e2e.name("Writer");
        String bName = e2e.name("Answer");
        String cName = e2e.name("Third");
        String spyName = e2e.name("Spy");
        String hiddenName = e2e.name("Hidden");
        Bot a = e2e.bot(aName);
        Bot b = e2e.bot(bName);
        Bot c = e2e.bot(cName);
        Bot spy = e2e.bot(spyName);
        Bot hidden = e2e.bot(hiddenName);
        e2e.console("op " + spyName);
        e2e.console("op " + hiddenName);
        try {
            e2e.step("/msg reaches the receiver, with a receipt for the sender");
            spy.command("socialspy");
            expectSaw(e2e, spy, "Social spy on");
            a.clearLogs();
            b.clearLogs();
            spy.clearLogs();
            pm(e2e, a, "msg " + bName + " hello there friend");
            e2e.eventually(() -> a.chatContains("To " + bName + ": hello there friend"), "the receipt: " + a.chat());
            Component incoming = line(e2e, b, "From " + aName + ": hello there friend");
            Component from = part(incoming, aName);
            e2e.expect(from != null && from.getStyle().getClickEvent() instanceof ClickEvent.SuggestCommand suggest
                && suggest.command().equals("/msg " + aName + " "), "clicking the name answers: " + incoming);
            e2e.eventually(() -> spy.chatContains("Spy " + aName + " to " + bName + ": hello there friend"), "social spy: " + spy.chat());
            e2e.expect(!c.chatContains("hello there friend"), "nobody else reads it");
            e2e.eventually(() -> bName.equals(placeholder(e2e, aName, "chat_reply")) && aName.equals(placeholder(e2e, bName, "chat_reply"))
                && "-".equals(placeholder(e2e, cName, "chat_reply")), "the reply placeholders");

            e2e.step("/r answers the last conversation");
            a.clearLogs();
            pm(e2e, b, "r right back at you");
            e2e.eventually(() -> a.chatContains("From " + bName + ": right back at you"), "the reply: " + a.chat());
            c.clearLogs();
            c.command("r anyone");
            expectSaw(e2e, c, "You have nobody to reply to");

            e2e.step("yourself, offline and hidden players can't be messaged");
            a.clearLogs();
            a.command("msg " + aName + " me");
            expectSaw(e2e, a, "You can't message yourself");
            a.command("msg NoSuchPlayer_x9 hi");
            expectSaw(e2e, a, "NoSuchPlayer_x9 is not online");
            hidden.command("vanish");
            expectSaw(e2e, hidden, "You are vanished");
            a.clearLogs();
            a.command("msg " + hiddenName + " where are you");
            expectSaw(e2e, a, hiddenName + " is not online");
            e2e.expect(!hidden.chatContains("where are you"), "the vanished player got nothing");

            e2e.step("a vanished player who writes first can be answered");
            a.clearLogs();
            pm(e2e, hidden, "msg " + aName + " staff here");
            e2e.eventually(() -> a.chatContains("From " + hiddenName + ": staff here"), "the staff message: " + a.chat());
            hidden.clearLogs();
            pm(e2e, a, "r thanks staff");
            e2e.eventually(() -> hidden.chatContains("From " + aName + ": thanks staff"), "the answer reaches them: " + hidden.chat());
            hidden.command("vanish");

            e2e.step("/msgtoggle refuses new conversations but not answers");
            b.clearLogs();
            b.command("msgtoggle");
            expectSaw(e2e, b, "Private messages to you are off");
            e2e.expect(!setting(e2e, bName, ChatFeature.PRIVATE_MESSAGES), "the toggle is stored");
            c.clearLogs();
            pm(e2e, c, "msg " + bName + " are you there");
            expectSaw(e2e, c, bName + " isn't taking private messages");
            b.clearLogs();
            pm(e2e, b, "msg " + aName + " one more thing");
            e2e.eventually(() -> a.chatContains("one more thing"), "B can still write");
            pm(e2e, a, "msg " + bName + " sure, go on");
            e2e.eventually(() -> b.chatContains("From " + aName + ": sure, go on"), "an answer to someone who wrote first arrives: " + b.chat());
            spy.clearLogs();
            pm(e2e, spy, "msg " + bName + " staff can always write");
            e2e.eventually(() -> b.chatContains("From " + spyName + ": staff can always write"), "staff reach them: " + b.chat());
            b.command("msgtoggle");
            expectSaw(e2e, b, "Players can send you private messages again");

            e2e.step("the console can message a player, who can answer it");
            a.clearLogs();
            e2e.console("msg " + aName + " a word from the console");
            e2e.eventually(() -> a.chatContains("From Console: a word from the console"), "the console message: " + a.chat());
            pm(e2e, a, "r thanks console");
            e2e.eventually(() -> a.chatContains("To Console: thanks console"), "the answer to the console: " + a.chat());

            e2e.step("the filter and the [item] tag work in private messages");
            hold(e2e, aName, new ItemStack(Material.GOLDEN_APPLE, 3));
            b.clearLogs();
            pm(e2e, a, "msg " + bName + " you should kys, here is [item]");
            Component filtered = line(e2e, b, "you should");
            e2e.expect(filtered.getString().equals("From " + aName + ": you should ***, here is [Golden Apple x3]"),
                "filtered with the item: " + filtered.getString());
            e2e.expect(itemHover(filtered) != null, "the item hover");

            e2e.step("muted players can't send private messages under any alias");
            e2e.console("mute " + aName + " 5m spam test");
            e2e.eventually(() -> e2e.feature(StaffFeature.class).mutes().mute(e2e.uuid(aName)).isPresent(), "the mute is active");
            a.clearLogs();
            b.clearLogs();
            pm(e2e, a, "pm " + bName + " sneaky");
            expectSaw(e2e, a, "You are muted");
            a.command("r sneaky reply");
            e2e.sleep(800);
            e2e.expect(!b.chatContains("sneaky"), "nothing reached the receiver: " + b.chat());
            e2e.console("unmute " + aName);
        } finally {
            e2e.console("deop " + spyName);
            e2e.console("deop " + hiddenName);
        }
    }

    static void spam(E2E e2e) {
        String spammerName = e2e.name("Spammer");
        String readerName = e2e.name("SpamRead");
        Bot spammer = e2e.bot(spammerName);
        Bot reader = e2e.bot(readerName);

        e2e.step("five messages fit in ten seconds, the sixth waits");
        for (String message : List.of("anyone selling iron", "i pay well for it", "meet me near spawn", "or send it by mail",
            "thanks in advance")) {
            say(e2e, spammer, message);
        }
        e2e.eventually(() -> reader.chatContains("thanks in advance"), "five messages arrived: " + reader.chat());
        e2e.expect(reader.chat().stream().filter(line -> line.startsWith(spammerName + ": ")).count() == 5, "all five: " + reader.chat());
        spammer.clearLogs();
        say(e2e, spammer, "the sixth one");
        expectSaw(e2e, spammer, "You are sending messages too fast");
        e2e.sleep(1_000);
        e2e.expect(!reader.chatContains("the sixth one"), "the sixth message was refused");
        e2e.sleep(9_000);

        e2e.step("one message a second");
        reader.clearLogs();
        spammer.clearLogs();
        spammer.chat("quick one");
        spammer.chat("quick two");
        expectSaw(e2e, spammer, "Slow down");
        e2e.eventually(() -> reader.chatContains("quick one"), "the first arrived");
        e2e.sleep(800);
        e2e.expect(!reader.chatContains("quick two"), "the second was refused");

        e2e.step("repeats are refused");
        spammer.clearLogs();
        say(e2e, spammer, "QUICK ONE!!");
        expectSaw(e2e, spammer, "You just said that");
        say(e2e, spammer, "trading my iron for gold");
        e2e.eventually(() -> reader.chatContains("trading my iron for gold"), "a new message arrives: " + reader.chat());
        spammer.clearLogs();
        say(e2e, spammer, "trading my iron for gold!!!1");
        expectSaw(e2e, spammer, "You just said that");

        e2e.step("capitals are lowered, filtered words replaced, long messages refused");
        reader.clearLogs();
        say(e2e, spammer, "WHO IS SELLING ELYTRA NOW");
        e2e.eventually(() -> reader.chatContains(spammerName + ": who is selling elytra now"), "lowercased: " + reader.chat());
        say(e2e, spammer, "you should k y s honestly");
        e2e.eventually(() -> reader.chatContains(spammerName + ": you should *** honestly"), "filtered: " + reader.chat());
        spammer.clearLogs();
        say(e2e, spammer, "long ".repeat(45));
        expectSaw(e2e, spammer, "That message is too long");
    }

    static void links(E2E e2e) {
        String advertiserName = e2e.name("Advert");
        String readerName = e2e.name("LinkRead");
        Bot advertiser = e2e.bot(advertiserName);
        Bot reader = e2e.bot(readerName);
        try {
            e2e.step("server addresses are refused in chat");
            advertiser.clearLogs();
            reader.clearLogs();
            say(e2e, advertiser, "join play.otherserver.net now");
            expectSaw(e2e, advertiser, "Links and server addresses aren't allowed in chat");
            e2e.sleep(800);
            e2e.expect(!reader.chatContains("otherserver"), "the reader never sees it: " + reader.chat());

            e2e.step("IP addresses too, and in private messages");
            advertiser.clearLogs();
            pm(e2e, advertiser, "msg " + readerName + " come to 51.12.3.4:25565");
            expectSaw(e2e, advertiser, "Links and server addresses aren't allowed in chat");
            e2e.sleep(800);
            e2e.expect(!reader.chatContains("51.12.3.4"), "no private message: " + reader.chat());

            e2e.step("the server's own addresses and version numbers pass");
            say(e2e, advertiser, "rules at siftvanilla.net/rules and discord.gg/siftvanilla");
            e2e.eventually(() -> reader.chatContains(advertiserName + ": rules at siftvanilla.net/rules and discord.gg/siftvanilla"),
                "the allowed addresses: " + reader.chat());
            say(e2e, advertiser, "the farm still works on 1.21.5");
            e2e.eventually(() -> reader.chatContains("the farm still works on 1.21.5"), "a version number: " + reader.chat());

            e2e.step("a renamed item can't advertise through [item]");
            ItemStack sign = ItemStack.of(Material.OAK_SIGN);
            sign.setData(DataComponentTypes.CUSTOM_NAME, net.kyori.adventure.text.Component.text("play.otherserver.net"));
            hold(e2e, advertiserName, sign);
            reader.clearLogs();
            say(e2e, advertiser, "look at my [item]");
            e2e.eventually(() -> reader.chatContains(advertiserName + ": look at my [***]"), "the cleaned item name: " + reader.chat());

            e2e.step("the console sees what the link check does");
            List<String> test = e2e.consoleOutput("chat test join play.otherserver.net");
            e2e.expect(String.join("\n", test).contains("The link check refuses that message. Found play.otherserver.net"),
                "link test: " + test);

            e2e.step("staff can post links");
            e2e.console("op " + advertiserName);
            reader.clearLogs();
            say(e2e, advertiser, "our partner is play.friendserver.net");
            e2e.eventually(() -> reader.chatContains("our partner is play.friendserver.net"), "staff links pass: " + reader.chat());
        } finally {
            e2e.console("deop " + advertiserName);
        }
    }

    static void mentions(E2E e2e) {
        String callerName = e2e.name("Caller");
        String calledName = e2e.name("Called");
        Bot caller = e2e.bot(callerName);
        Bot called = e2e.bot(calledName);

        e2e.step("an item named after a player shown with [item] doesn't ping them");
        ItemStack named = ItemStack.of(Material.NAME_TAG);
        named.setData(DataComponentTypes.CUSTOM_NAME, net.kyori.adventure.text.Component.text("@" + calledName));
        hold(e2e, callerName, named);
        called.clearLogs();
        say(e2e, caller, "look at my [item]");
        e2e.eventually(() -> called.chatContains("look at my [@" + calledName + "]"), "the item line: " + called.chat());
        e2e.sleep(800);
        e2e.expect(!called.actionBarContains("mentioned you"), "no ping from an item name: " + called.actionBar());
        hold(e2e, callerName, new ItemStack(Material.AIR));

        e2e.step("@name pings the mentioned player");
        called.clearLogs();
        say(e2e, caller, "hey @" + calledName.toLowerCase() + " come here");
        e2e.eventually(() -> called.actionBarContains(callerName + " mentioned you"), "the mention notice: " + called.actionBar());
        e2e.expect(called.chatContains("hey @" + calledName.toLowerCase() + " come here"), "the message is unchanged (no highlight)");

        e2e.step("the bare name pings too, once per cooldown");
        e2e.sleep(3_000);
        called.clearLogs();
        say(e2e, caller, calledName + " look at this");
        e2e.eventually(() -> called.actionBarContains("mentioned you"), "plain name mention: " + called.actionBar());
        called.clearLogs();
        say(e2e, caller, "still there " + calledName + "?");
        e2e.sleep(1_000);
        e2e.expect(!called.actionBarContains("mentioned you"), "no second ping within the cooldown: " + called.actionBar());

        e2e.step("turning mention alerts off in the settings stops them");
        called.clearLogs();
        called.command("settings chat");
        Bot.SeenDialog dialog = chatGroup(e2e, called);
        e2e.expect(dialog.inputs().containsKey("mentions"), "a mentions switch: " + dialog.inputs());
        Map<String, Object> values = inputs(dialog);
        values.put("mentions", false);
        e2e.click(called, "Save", values);
        expectSaw(e2e, called, "Mention alerts turned off");
        e2e.expect(!setting(e2e, calledName, ChatFeature.MENTIONS), "stored");
        e2e.sleep(3_000);
        called.clearLogs();
        say(e2e, caller, "@" + calledName + " ping again");
        e2e.eventually(() -> called.chatContains("ping again"), "the message still arrives");
        e2e.sleep(800);
        e2e.expect(!called.actionBarContains("mentioned you"), "no ping: " + called.actionBar());
    }

    static void admin(E2E e2e) {
        String playerName = e2e.name("Citizen");
        String staffName = e2e.name("ChatMod");
        Bot player = e2e.bot(playerName);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        try {
            e2e.step("a locked chat only lets staff talk");
            staff.clearLogs();
            e2e.console("chat lock");
            expectSaw(e2e, staff, "Chat is locked");
            player.clearLogs();
            say(e2e, player, "is anyone there");
            expectSaw(e2e, player, "Chat is locked right now");
            e2e.sleep(500);
            e2e.expect(!staff.chatContains("is anyone there"), "nothing went through");
            say(e2e, staff, "staff can still talk");
            e2e.eventually(() -> player.chatContains("staff can still talk"), "staff talk: " + player.chat());
            e2e.console("chat unlock");
            expectSaw(e2e, player, "Chat is open again");

            e2e.step("slow mode spaces every player's messages");
            e2e.console("chat slow 5s");
            expectSaw(e2e, player, "Slow mode is on");
            e2e.expect("5".equals(placeholder(e2e, playerName, "chat_slowmode")), "the slow mode placeholder: "
                + placeholder(e2e, playerName, "chat_slowmode"));
            staff.clearLogs();
            say(e2e, player, "first slow message");
            e2e.eventually(() -> staff.chatContains("first slow message"), "the first message: " + staff.chat());
            player.clearLogs();
            say(e2e, player, "second slow message");
            expectSaw(e2e, player, "Slow mode is on. You can talk again in");
            e2e.console("chat slow off");
            expectSaw(e2e, player, "Slow mode is off");
            List<String> tooLong = e2e.consoleOutput("chat slow 2h");
            e2e.expect(String.join("\n", tooLong).contains("Slow mode can be at most 1h"), "the slow mode limit: " + tooLong);
            List<String> invalid = e2e.consoleOutput("chat slow soon");
            e2e.expect(String.join("\n", invalid).contains("soon is not a duration"), "a bad duration: " + invalid);
            e2e.expect("0".equals(placeholder(e2e, playerName, "chat_slowmode")), "the slow mode placeholder is back to 0");

            e2e.step("the console sees the status, tests the filter and reads ignore lists");
            List<String> status = e2e.consoleOutput("chat");
            e2e.expect(String.join("\n", status).contains("Chat open") && String.join("\n", status).contains("Slow mode off"), "status: " + status);
            List<String> test = e2e.consoleOutput("chat test k y s please");
            e2e.expect(String.join("\n", test).contains("The filter sends: *** please"), "filter test: " + test);
            player.clearLogs();
            player.command("ignore " + staffName);
            expectSaw(e2e, player, "they are staff");
            e2e.sleep(500);
            String other = e2e.name("Loner");
            e2e.bot(other);
            player.command("ignore " + other);
            e2e.eventually(() -> chat(e2e).ignores().ignores(e2e.uuid(playerName), e2e.uuid(other)), "ignored");
            List<String> ignores = e2e.consoleOutput("chat ignores " + playerName);
            e2e.expect(ignores.size() == 2 && ignores.get(1).equals(other), "the ignore list: " + ignores);
            e2e.log("left in place for the restart check: " + playerName + " ignores " + other);
        } finally {
            e2e.console("chat unlock");
            e2e.console("chat slow off");
            e2e.console("deop " + staffName);
        }
    }

    static void settings(E2E e2e) throws Exception {
        String name = e2e.name("Setter");
        String staffName = e2e.name("SetStaff");
        Bot bot = e2e.bot(name);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        try {
            e2e.step("the main menu and the pause menu open the settings list");
            bot.command("menu");
            e2e.dialog(bot, "SiftVanilla");
            e2e.click(bot, "Settings");
            Bot.SeenDialog fromMenu = settingsList(e2e, bot);
            e2e.expect(fromMenu.button("Back") != null, "a way back to the menu: " + fromMenu.buttons());
            e2e.expect("none".equals(fromMenu.after()), "the list stays on screen until the next dialog: " + fromMenu.after());
            e2e.click(bot, "Back");
            e2e.dialog(bot, "SiftVanilla");
            bot.clearLogs();
            bot.rawClick("siftcore:hub/settings", null);
            settingsList(e2e, bot);

            e2e.step("switches are grouped: Chat, then General for switches without a group");
            bot.clearLogs();
            bot.command("settings");
            Bot.SeenDialog list = settingsList(e2e, bot);
            e2e.expect(list.button("Chat") != null && list.button("General") != null, "both groups: " + list.buttons());
            e2e.expect(list.bodyText().contains("Chat: Mentions and private messages"), "group descriptions: " + list.body());
            e2e.expect(list.bodyText().indexOf("Chat:") < list.bodyText().indexOf("General:"), "Chat comes first: " + list.body());
            e2e.expect(list.button("Close") != null, "a command opens it with Close: " + list.buttons());
            e2e.click(bot, "Chat");
            Bot.SeenDialog chat = chatGroup(e2e, bot);
            e2e.expect("toggle".equals(chat.inputs().get("mentions")) && "toggle".equals(chat.inputs().get("private_messages")),
                "the chat switches: " + chat.inputs());
            e2e.expect(!chat.inputs().containsKey("social_spy"), "no staff switches for players: " + chat.inputs());
            e2e.expect(chat.bodyText().contains("Private messages: Let players send me private messages"), "descriptions: " + chat.body());
            e2e.click(bot, "Back");
            settingsList(e2e, bot);
            e2e.click(bot, "General");
            Bot.SeenDialog general = e2e.dialog(bot, "General settings");
            for (String key : List.of("pay_notifications", "auction_sales", "crate_wins", "death_messages", "tpa_requests")) {
                e2e.expect("toggle".equals(general.inputs().get(key)), "a switch for " + key + ": " + general.inputs());
            }
            e2e.expect(!general.inputs().containsKey("mentions"), "chat switches stay in their group: " + general.inputs());

            e2e.step("saving stores the flipped switches, says so and goes back to the list");
            bot.clearLogs();
            Map<String, Object> values = inputs(general);
            values.put("tpa_requests", false);
            values.put("pay_notifications", false);
            e2e.click(bot, "Save", values);
            expectSaw(e2e, bot, "Saved 2 settings");
            settingsList(e2e, bot);
            e2e.expect(!e2e.services().settings().enabled(e2e.uuid(name), e2e.services().settings().toggle("tpa-requests")),
                "teleport requests off");
            e2e.expect(!e2e.services().settings().enabled(e2e.uuid(name), e2e.services().settings().toggle("pay-notifications")),
                "payment messages off");
            e2e.click(bot, "Chat");
            Bot.SeenDialog chatAgain = chatGroup(e2e, bot);
            Map<String, Object> chatValues = inputs(chatAgain);
            chatValues.put("private_messages", false);
            bot.clearLogs();
            e2e.click(bot, "Save", chatValues);
            expectSaw(e2e, bot, "Private messages turned off");
            e2e.expect(!setting(e2e, name, ChatFeature.PRIVATE_MESSAGES), "private messages off");

            e2e.step("saving without changes says nothing changed; Back leaves without saving");
            bot.clearLogs();
            bot.command("settings chat");
            Bot.SeenDialog unchanged = chatGroup(e2e, bot);
            e2e.expect(Boolean.FALSE.equals(unchanged.toggleValue("private_messages")), "the page shows the stored value");
            e2e.click(bot, "Save", inputs(unchanged));
            expectSaw(e2e, bot, "Nothing changed");
            bot.command("settings chat");
            Bot.SeenDialog discard = chatGroup(e2e, bot);
            Map<String, Object> discarded = inputs(discard);
            discarded.put("mentions", false);
            e2e.click(bot, "Back", discarded);
            settingsList(e2e, bot);
            e2e.expect(setting(e2e, name, ChatFeature.MENTIONS), "Back saved nothing");

            e2e.step("a switch changed elsewhere while the page was open is not overwritten");
            bot.clearLogs();
            bot.command("settings chat");
            Bot.SeenDialog open = chatGroup(e2e, bot);
            Map<String, Object> stale = inputs(open);
            e2e.expect(Boolean.FALSE.equals(stale.get("private_messages")), "the page shows private messages off");
            bot.command("msgtoggle");
            expectSaw(e2e, bot, "Players can send you private messages again");
            e2e.expect(bot.dialog() == open, "the settings page is still the open one");
            stale.put("mentions", false);
            e2e.click(bot, "Save", stale);
            expectSaw(e2e, bot, "Mention alerts turned off");
            e2e.expect(setting(e2e, name, ChatFeature.PRIVATE_MESSAGES), "the /msgtoggle change survived the stale page");
            e2e.expect(!setting(e2e, name, ChatFeature.MENTIONS), "the flipped switch was saved");

            e2e.step("/settings <group> opens a group, and an unknown group is refused");
            bot.clearLogs();
            bot.command("settings nosuchgroup");
            expectSaw(e2e, bot, "There is no settings group called nosuchgroup");
            bot.command("settings GENERAL");
            e2e.dialog(bot, "General settings");

            e2e.step("staff see their own switches in the group they belong to");
            staff.command("settings chat");
            Bot.SeenDialog staffChat = chatGroup(e2e, staff);
            e2e.expect("toggle".equals(staffChat.inputs().get("social_spy")), "social spy for staff: " + staffChat.inputs());

            e2e.step("long groups are paged; flips are carried between pages and saved together");
            withConfig(e2e, "features/settings.yml", "page-size: 8", "page-size: 2", x -> {
                bot.clearLogs();
                bot.command("settings general");
                Bot.SeenDialog page1 = e2e.dialog(bot, "General settings");
                e2e.expect(page1.inputs().size() == 2 && page1.bodyText().contains("Page 1 of 3"), "page 1 of 3: " + page1.inputs()
                    + " " + page1.body());
                e2e.expect(page1.button("Next page") != null && page1.button("Previous page") == null, "only Next: " + page1.buttons());
                String first = page1.inputs().keySet().stream().sorted().findFirst().orElseThrow();
                Map<String, Object> flipped = inputs(page1);
                boolean firstWas = Boolean.TRUE.equals(flipped.get(first));
                flipped.put(first, !firstWas);
                e2e.click(bot, "Next page", flipped);
                Bot.SeenDialog page2 = e2e.dialog(bot, "General settings");
                e2e.expect(page2.bodyText().contains("Page 2 of 3") && page2.bodyText().contains("Changes on other pages: 1"),
                    "page 2 knows about the change on page 1: " + page2.body());
                e2e.expect(page2.button("Previous page") != null && page2.button("Next page") != null, "both ways: " + page2.buttons());
                String second = page2.inputs().keySet().stream().sorted().findFirst().orElseThrow();
                Map<String, Object> flipped2 = inputs(page2);
                boolean secondWas = Boolean.TRUE.equals(flipped2.get(second));
                flipped2.put(second, !secondWas);
                e2e.click(bot, "Previous page", flipped2);
                Bot.SeenDialog back1 = e2e.dialog(bot, "General settings");
                e2e.expect(back1.bodyText().contains("Page 1 of 3") && Boolean.valueOf(!firstWas).equals(back1.toggleValue(first)),
                    "page 1 shows the unsaved flip: " + back1.body());
                bot.clearLogs();
                e2e.click(bot, "Save", inputs(back1));
                expectSaw(e2e, bot, "Saved 2 settings");
                var settings = e2e.services().settings();
                e2e.expect(settings.enabled(e2e.uuid(name), settings.toggle(first.replace('_', '-'))) == !firstWas
                    && settings.enabled(e2e.uuid(name), settings.toggle(second.replace('_', '-'))) == !secondWas,
                    "both flips were saved");
            });
        } finally {
            e2e.console("deop " + staffName);
        }
    }
}
