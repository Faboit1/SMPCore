package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.feature.chat.ChatFeature;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * End-to-end scenarios of public chat (format, hover card, profile click, [item], mentions, anti-spam, the filter, chat
 * lock and slow mode), private messages (/msg, /r, social spy, /msgtoggle), ignore lists (/ignore and its dialogs,
 * and the teleport requests they block), and every chat setting doing what it says: mention alerts, who can ping and
 * message me, the name highlight, the strict filter, public chat, hiding new players, the message pop-up, the ping
 * sounds, the /r target and balance privacy on the card, and server locks. Settings are changed through the settings
 * dialog (one choice and one switch at least), through {@code /settings chat <setting> <value>} where the settings
 * command takes values, and otherwise through the setting API that command uses. The settings dialog itself has its
 * own scenarios ({@link SettingsScenarios}).
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
        list.add(of("chat-mention-settings", ChatScenarios::mentionSettings));
        list.add(of("chat-reading-settings", ChatScenarios::readingSettings));
        list.add(of("chat-msg-settings", ChatScenarios::messageSettings));
        list.add(of("chat-balance-privacy", ChatScenarios::balancePrivacy));
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
        return new HashMap<>(dialog.values());
    }

    /** Waits for the Chat group's settings page, as the form it used to be ({@link SettingsSteps#form}). */
    private static Bot.SeenDialog chatGroup(E2E e2e, Bot bot) {
        Bot.SeenDialog dialog = e2e.dialog(bot, "Chat settings");
        e2e.expect(dialog.title().equals("Chat settings"), "the chat group: " + dialog.title());
        return SettingsSteps.form(e2e, dialog);
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(e2e.player(name), placeholder);
    }

    /** A player's effective value of a setting. */
    private static <T> T choice(E2E e2e, String name, PlayerSetting<T> setting) {
        return e2e.services().settings().get(e2e.uuid(name), setting);
    }

    /**
     * Changes a setting the way a settings command does (by its id and a typed value, with the player's permissions).
     * {@code /settings <setting> <value>} comes with the settings dialog's next version; until then this is its API.
     */
    private static void set(E2E e2e, String name, String id, String value) {
        SetResult result = e2e.onPlayer(name, () -> e2e.services().settings().setParsed(e2e.player(name), id, value,
            Change.command(name)));
        e2e.expect(result.succeeded(), name + " sets " + id + " to " + value + ": " + result);
    }

    /**
     * Changes a setting with {@code /settings <group> <setting> <value>} where the settings command takes values (that
     * form comes with the settings dialog's next version, together with its public {@code SettingsView} API), else
     * through the setting API that command uses. Either way the stored value must then be the one asked for.
     */
    private static void setByCommand(E2E e2e, Bot bot, String name, String group, String id, String value) {
        if (!settingsCommandTakesValues()) {
            e2e.log("/settings <group> <setting> <value> is not on this server yet: " + id + " is set through the setting API");
            set(e2e, name, id, value);
            return;
        }
        bot.clearLogs();
        bot.command("settings " + group + " " + id + " " + value);
        e2e.eventually(() -> value.equals(e2e.services().settings().encoded(e2e.uuid(name), id)),
            "/settings " + group + " " + id + " " + value + " stores it (chat " + bot.chat() + ", action bar " + bot.actionBar() + ")");
    }

    /** Whether this server's {@code /settings} changes values (its public SettingsView API is there). */
    private static boolean settingsCommandTakesValues() {
        try {
            Class.forName("net.siftvanilla.siftcore.api.SettingsView", false, ChatScenarios.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Opens the Chat group with {@code /settings chat} and sets the given settings on their buttons (all on one page);
     * a click says nothing ({@code confirmation}, the message a command would send, never shows): the button shows it.
     */
    private static void editChat(E2E e2e, Bot bot, Map<String, Object> wanted, String confirmation) {
        bot.clearLogs();
        SettingsSteps.edit(e2e, bot, "chat", "Chat settings", wanted);
        e2e.expect(!bot.anyFeedbackContains(confirmation), "no message for a click: " + bot.chat() + " " + bot.actionBar());
    }

    /**
     * The style a part of a line is drawn with (its own style over its parents'), for the first part whose own text is
     * {@code text}; null when no part has that text.
     */
    private static net.minecraft.network.chat.Style styleOf(Component root, String text) {
        return styleOf(root, text, net.minecraft.network.chat.Style.EMPTY);
    }

    private static net.minecraft.network.chat.Style styleOf(Component component, String text, net.minecraft.network.chat.Style parent) {
        net.minecraft.network.chat.Style style = component.getStyle().applyTo(parent);
        if (component.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents plain && plain.text().equals(text)) {
            return style;
        }
        for (Component sibling : component.getSiblings()) {
            net.minecraft.network.chat.Style found = styleOf(sibling, text, style);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Makes two players friends with the staff command and waits until the relation reads true. */
    private static void befriend(E2E e2e, String a, String b) {
        e2e.console("sift friends add " + a + " " + b);
        e2e.eventually(() -> e2e.services().relations().areFriends(e2e.uuid(a), e2e.uuid(b)), a + " and " + b + " are friends");
    }

    /**
     * Runs {@code body} with a setting locked in {@code features/settings.yml} (written the way an owner would, then
     * reloaded), and removes the lock again afterwards.
     */
    private static void withSettingsLock(E2E e2e, String id, String value, Body body) throws Exception {
        Path file = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve("features/settings.yml");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        e2e.expect(original.contains("hidden: []"), "features/settings.yml has its hidden line");
        Files.writeString(file, original.replace("hidden: []", "hidden: []\nlocked:\n  " + id + ": " + value), StandardCharsets.UTF_8);
        try {
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the lock reloads: " + reload);
            body.run(e2e);
        } finally {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /** Whether the bot heard a SiftCore sound with this id since its logs were cleared. */
    private static boolean heard(Bot bot, String sound) {
        return bot.sounds().stream().anyMatch(seen -> seen.sound().equals(sound));
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

        e2e.step("the name shows a card on hover, opens the profile on click and starts a message on shift-click");
        Component name = part(line, talkerName);
        e2e.expect(name != null, "the name is its own part: " + line);
        e2e.expect(name.getStyle().getHoverEvent() instanceof HoverEvent.ShowText, "a hover card on the name: " + name.getStyle());
        String card = ((HoverEvent.ShowText) name.getStyle().getHoverEvent()).value().getString();
        e2e.expect(card.contains(talkerName) && card.contains("Balance $12,345") && card.contains("Kills 0")
            && card.contains("Playtime") && card.contains("No team"), "the card's lines: " + card);
        e2e.expect(card.contains("Click for " + talkerName + "'s profile, shift-click to message"), "the card says what clicks do: " + card);
        e2e.expect(name.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run
            && run.command().equals("/profile " + talkerName), "a click that opens the profile: " + name.getStyle().getClickEvent());
        e2e.expect(("/msg " + talkerName + " ").equals(name.getStyle().getInsertion()), "shift-click puts /msg in the chat box: "
            + name.getStyle().getInsertion());
        reader.clearLogs();
        reader.command("profile " + talkerName);
        Bot.SeenDialog profile = e2e.dialog(reader, talkerName);
        e2e.expect(profile.button("Message") != null, "the profile the click opens offers a message: " + profile.buttons());
        if (profile.button("Close") != null) {
            e2e.click(reader, "Close");
        }
        Plugin core = e2e.services().plugin();
        e2e.expect(Bukkit.getCommandMap().getCommand("profile") instanceof PluginIdentifiableCommand owned && owned.getPlugin() == core
            && Bukkit.getCommandMap().getCommand(core.getPluginMeta().namespace() + ":profile") != null,
            "/profile is SiftCore's own, which is what makes names open profiles");

        e2e.step("a reader who may not open profiles gets a click that starts a message; everyone else keeps the profile");
        // The talker reads this time (their own five messages in ten seconds are needed for the [item] steps).
        PermissionAttachment noProfiles = e2e.onPlayer(talkerName, () -> e2e.player(talkerName).addAttachment(core,
            "siftcore.command.profile", false));
        try {
            e2e.eventually(() -> e2e.onPlayer(talkerName, () -> !e2e.player(talkerName).hasPermission("siftcore.command.profile")),
                "the talker lost /profile");
            reader.clearLogs();
            talker.clearLogs();
            say(e2e, reader, "a line for readers with and without profiles");
            Component plainName = part(line(e2e, talker, "a line for readers with and without profiles"), readerName);
            e2e.expect(plainName != null && plainName.getStyle().getClickEvent() instanceof ClickEvent.SuggestCommand suggest
                && suggest.command().equals("/msg " + readerName + " "), "the click starts a message: "
                + (plainName == null ? null : plainName.getStyle().getClickEvent()));
            String plainCard = ((HoverEvent.ShowText) plainName.getStyle().getHoverEvent()).value().getString();
            e2e.expect(plainCard.contains("Click to message " + readerName) && !plainCard.contains("profile"),
                "the card says what the click does for this reader: " + plainCard);
            Component ownName = part(line(e2e, reader, "a line for readers with and without profiles"), readerName);
            e2e.expect(ownName != null && ownName.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run
                && run.command().equals("/profile " + readerName), "a reader with /profile keeps the profile click: "
                + (ownName == null ? null : ownName.getStyle().getClickEvent()));
        } finally {
            e2e.onPlayer(talkerName, () -> {
                e2e.player(talkerName).removeAttachment(noProfiles);
                return null;
            });
        }

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
            e2e.expect(list.bodyText().contains("Ignoring 1 of 100 players") && list.button(loudName) != null, "the ignored player: "
                + list.body() + " " + list.buttons());
            e2e.expect(list.button(loudName).tooltip().contains("Stop ignoring " + loudName), "what a click does: "
                + list.button(loudName).tooltip());
            e2e.expect(list.button("Ignore a player").tooltip() != null && list.button("Next page") == null, "explained, no pages");
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
            Bot.SeenDialog form = e2e.dialog(quiet, "Ignore a player");
            e2e.expect(form.body().isEmpty() && form.button("Ignore").tooltip().contains("You won't see their chat"),
                "the form explains on its button: " + form.body());
            e2e.click(quiet, "Ignore", Map.of("player", modName));
            e2e.eventually(() -> quiet.dialog() != null && quiet.dialog().bodyText().contains("they are staff"),
                "a refusal shows in red on the form: " + (quiet.dialog() == null ? "none" : quiet.dialog().body()));
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
            e2e.expect(choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.NOBODY, "who can message me is nobody now");
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
            e2e.expect(choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.EVERYONE, "everyone again");

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
            say(e2e, advertiser, "rules at siftvanilla.com/rules and discord.gg/siftvanilla");
            e2e.eventually(() -> reader.chatContains(advertiserName + ": rules at siftvanilla.com/rules and discord.gg/siftvanilla"),
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
        e2e.expect("choice".equals(dialog.inputs().get("mentions")), "a mention alerts choice: " + dialog.inputs());
        e2e.expect(List.of("actionbar", "chat", "title", "off").equals(dialog.options().get("mentions")),
            "its options: " + dialog.options().get("mentions"));
        Map<String, Object> values = inputs(dialog);
        values.put("mentions", "off");
        SettingsSteps.applyChanged(e2e, called, dialog, values);
        e2e.expect(called.dialog().button("Mention alerts: Off") != null, "the button shows Off: " + called.dialog().buttons());
        e2e.expect(choice(e2e, calledName, ChatFeature.MENTIONS) == AlertStyle.OFF, "stored");
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

    // ------------------------------------------------------------------ chat settings

    /** The sounds of the default ping (the notify sound), the bell and the chime. */
    private static final String PLING = "minecraft:block.note_block.pling";
    private static final String BELL = "minecraft:block.note_block.bell";
    private static final String CHIME = "minecraft:block.amethyst_block.chime";
    /** One sender pings the same player at most once per 3 seconds; a little more for jitter. */
    private static final long MENTION_GAP = 3_400;

    static void mentionSettings(E2E e2e) {
        String callerName = e2e.name("Pinger");
        String calledName = e2e.name("Pinged");
        String palName = e2e.name("PingPal");
        Bot caller = e2e.bot(callerName);
        Bot called = e2e.bot(calledName);
        Bot pal = e2e.bot(palName);

        e2e.step("mention alerts can go to chat instead, chosen in the settings dialog, with the default ping");
        editChat(e2e, called, Map.of("mentions", "chat"), "Mention alerts set to Chat");
        e2e.expect(choice(e2e, calledName, ChatFeature.MENTIONS) == AlertStyle.CHAT, "stored");
        called.clearLogs();
        say(e2e, caller, "@" + calledName + " over here");
        e2e.eventually(() -> called.chatContains(callerName + " mentioned you"), "a chat line: " + called.chat());
        e2e.eventually(() -> heard(called, PLING), "the default ping: " + called.sounds());
        e2e.expect(!called.actionBarContains("mentioned you"), "nothing above the hotbar: " + called.actionBar());

        e2e.step("as a title, chosen with /settings chat mentions title, with the mention sound picked in the Sounds settings");
        setByCommand(e2e, called, calledName, "chat", "mentions", "title");
        set(e2e, calledName, "sound-mention", "bell");
        e2e.sleep(MENTION_GAP);
        called.clearLogs();
        say(e2e, caller, "@" + calledName + " second ping please");
        e2e.eventually(() -> called.titles().stream().anyMatch(title -> title.contains(callerName + " mentioned you")),
            "a title: " + called.titles());
        e2e.eventually(() -> heard(called, BELL), "the bell: " + called.sounds());
        e2e.expect(!called.chatContains("mentioned you"), "no chat line: " + called.chat());

        e2e.step("pings on the bare name can be turned off; @name still pings");
        set(e2e, calledName, "mention-plain-names", "off");
        set(e2e, calledName, "mentions", "actionbar");
        e2e.sleep(MENTION_GAP);
        called.clearLogs();
        say(e2e, caller, calledName + " are you around");
        e2e.eventually(() -> called.chatContains("are you around"), "the line arrives");
        e2e.sleep(800);
        e2e.expect(!called.actionBarContains("mentioned you"), "no alert for the bare name: " + called.actionBar());
        say(e2e, caller, "@" + calledName + " now with the at sign");
        e2e.eventually(() -> called.actionBarContains(callerName + " mentioned you"), "@name alerts: " + called.actionBar());

        e2e.step("who can ping me: friends only");
        befriend(e2e, calledName, palName);
        set(e2e, calledName, "mention-from", "friends");
        e2e.sleep(6_000); // the caller stays inside the rate limit of five messages in ten seconds
        called.clearLogs();
        say(e2e, caller, "@" + calledName + " a stranger calls");
        e2e.eventually(() -> called.chatContains("a stranger calls"), "the line still shows");
        e2e.sleep(800);
        e2e.expect(!called.actionBarContains("mentioned you"), "no alert from a stranger: " + called.actionBar());
        say(e2e, pal, "@" + calledName + " your friend calls");
        e2e.eventually(() -> called.actionBarContains(palName + " mentioned you"), "a friend alerts: " + called.actionBar());

        e2e.step("the mention sound off: the alert comes without a sound");
        set(e2e, calledName, "sound-mention", "off");
        e2e.sleep(MENTION_GAP);
        called.clearLogs();
        say(e2e, pal, "@" + calledName + " quiet one this time");
        e2e.eventually(() -> called.actionBarContains(palName + " mentioned you"), "the alert: " + called.actionBar());
        e2e.sleep(400);
        e2e.expect(!heard(called, BELL) && !heard(called, PLING), "no ping: " + called.sounds());
    }

    static void readingSettings(E2E e2e) throws Exception {
        String talkerName = e2e.name("Speaker");
        String readerName = e2e.name("Listener");
        String otherName = e2e.name("Bystand");
        String staffName = e2e.name("ReadMod");
        Bot talker = e2e.bot(talkerName);
        Bot reader = e2e.bot(readerName);
        Bot other = e2e.bot(otherName);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        try {
            e2e.step("the reader's name stands out in lines that mention them: bold by default");
            reader.clearLogs();
            talker.clearLogs();
            say(e2e, talker, "hello @" + readerName + " and friends");
            Component line = line(e2e, reader, "and friends");
            net.minecraft.network.chat.Style bold = styleOf(line, "@" + readerName);
            e2e.expect(bold != null && bold.isBold() && !bold.isUnderlined(), "the name is bold: " + line);
            Component own = line(e2e, talker, "and friends");
            net.minecraft.network.chat.Style plain = styleOf(own, "@" + readerName);
            e2e.expect(plain == null || !plain.isBold(), "the sender's own line is not highlighted: " + own);
            e2e.expect(line(e2e, reader, "and friends").getString().equals(talkerName + ": hello @" + readerName + " and friends"),
                "the text is unchanged");

            e2e.step("underlined instead, chosen in the settings dialog; or not at all");
            editChat(e2e, reader, Map.of("mention_highlight", "underline"), "Highlight my name set to Underlined");
            reader.clearLogs();
            say(e2e, talker, "hey " + readerName + " look here");
            Component underlined = line(e2e, reader, "look here");
            net.minecraft.network.chat.Style under = styleOf(underlined, readerName);
            e2e.expect(under != null && under.isUnderlined() && !under.isBold(), "the bare name is underlined: " + underlined);
            set(e2e, readerName, "mention-highlight", "off");
            reader.clearLogs();
            say(e2e, talker, "@" + readerName + " no highlight now");
            Component none = line(e2e, reader, "no highlight now");
            net.minecraft.network.chat.Style off = styleOf(none, "@" + readerName);
            e2e.expect(off == null || (!off.isBold() && !off.isUnderlined()), "nothing stands out: " + none);

            e2e.step("the strict word filter hides milder words for the reader only, in chat and private messages");
            set(e2e, readerName, "chat-filter-strict", "on");
            e2e.sleep(6_000); // the talker stays inside the rate limit
            reader.clearLogs();
            other.clearLogs();
            say(e2e, talker, "damn this crap weather");
            e2e.eventually(() -> reader.chatContains(talkerName + ": *** this *** weather"), "filtered for the reader: " + reader.chat());
            e2e.eventually(() -> other.chatContains(talkerName + ": damn this crap weather"), "not for others: " + other.chat());
            pm(e2e, talker, "msg " + readerName + " damn it all");
            e2e.eventually(() -> reader.chatContains("From " + talkerName + ": *** it all"), "the private copy too: " + reader.chat());
            e2e.eventually(() -> talker.chatContains("To " + readerName + ": damn it all"), "the sender's copy is as typed");

            e2e.step("public chat off: no public lines, private messages still arrive, one reminder when you talk");
            editChat(e2e, reader, Map.of("public_chat", false), "Show public chat turned off");
            e2e.sleep(6_000);
            reader.clearLogs();
            say(e2e, talker, "a public line nobody reads");
            e2e.eventually(() -> other.chatContains("a public line nobody reads"), "others read it");
            e2e.sleep(800);
            e2e.expect(!reader.chatContains("a public line nobody reads"), "the reader doesn't: " + reader.chat());
            pm(e2e, talker, "msg " + readerName + " private still works");
            e2e.eventually(() -> reader.chatContains("From " + talkerName + ": private still works"), "a private message: " + reader.chat());
            say(e2e, reader, "talking with public chat off");
            expectSaw(e2e, reader, "Your public chat is off");
            e2e.eventually(() -> talker.chatContains("talking with public chat off"), "others read the reader");
            reader.clearLogs();
            say(e2e, reader, "and talking once more");
            e2e.eventually(() -> talker.chatContains("and talking once more"), "the second line arrives");
            e2e.sleep(500);
            e2e.expect(!reader.chatContains("Your public chat is off"), "the reminder comes once a session: " + reader.chat());
            set(e2e, readerName, "public-chat", "on");

            e2e.step("public chat locked off by the server: the reminder says so instead of pointing to /settings");
            withSettingsLock(e2e, "public-chat", "false", x -> {
                e2e.expect(!choice(e2e, otherName, ChatFeature.PUBLIC_CHAT), "the lock turns public chat off for everyone");
                other.clearLogs();
                say(e2e, other, "talking while the server keeps public chat off");
                expectSaw(e2e, other, "Public chat is turned off by the server");
                e2e.expect(!saw(other, "/settings chat"), "no pointer to a setting the player can't change: " + other.chat());
            });
            e2e.eventually(() -> choice(e2e, otherName, ChatFeature.PUBLIC_CHAT), "public chat on again without the lock");

            e2e.step("hiding brand-new players (a switch on the second page) hides fresh accounts but never staff");
            editChat(e2e, reader, Map.of("chat_hide_new", true), "Hide brand-new players turned on");
            e2e.sleep(6_000);
            reader.clearLogs();
            say(e2e, talker, "a brand new account speaks");
            e2e.eventually(() -> other.chatContains("a brand new account speaks"), "others read it");
            e2e.sleep(800);
            e2e.expect(!reader.chatContains("a brand new account speaks"), "hidden from the reader: " + reader.chat());
            say(e2e, staff, "staff are never hidden");
            e2e.eventually(() -> reader.chatContains("staff are never hidden"), "staff reach the reader: " + reader.chat());
            set(e2e, readerName, "chat-hide-new", "off");
            say(e2e, talker, "visible again for everyone");
            e2e.eventually(() -> reader.chatContains("visible again for everyone"), "back with the switch off: " + reader.chat());
        } finally {
            e2e.console("deop " + staffName);
        }
    }

    static void messageSettings(E2E e2e) throws Exception {
        String aName = e2e.name("MsgA");
        String bName = e2e.name("MsgB");
        String cName = e2e.name("MsgC");
        String strangerName = e2e.name("MsgD");
        String palName = e2e.name("MsgPal");
        Bot a = e2e.bot(aName);
        Bot b = e2e.bot(bName);
        Bot c = e2e.bot(cName);
        Bot stranger = e2e.bot(strangerName);
        Bot pal = e2e.bot(palName);

        e2e.step("a private message pop-up as a title, chosen in the settings dialog, with the chosen message sound");
        editChat(e2e, b, Map.of("pm_alert", "title"), "Private message pop-up set to Title");
        set(e2e, bName, "sound-pm", "chime");
        b.clearLogs();
        pm(e2e, a, "msg " + bName + " hello with a pop-up");
        e2e.eventually(() -> b.chatContains("From " + aName + ": hello with a pop-up"), "the chat line stays: " + b.chat());
        e2e.eventually(() -> b.titles().stream().anyMatch(title -> title.contains("Message from " + aName)), "the title: " + b.titles());
        e2e.eventually(() -> heard(b, CHIME), "the chime: " + b.sounds());
        e2e.expect(!heard(b, PLING), "not the default ping: " + b.sounds());

        e2e.step("/r answers whoever wrote last, when chosen");
        set(e2e, bName, "reply-target", "last-received");
        pm(e2e, c, "msg " + bName + " a second writer here");
        e2e.eventually(() -> b.chatContains("a second writer here"), "C's message arrives");
        pm(e2e, b, "msg " + aName + " back to you A");
        e2e.eventually(() -> a.chatContains("back to you A"), "B wrote to A");
        e2e.expect(cName.equals(placeholder(e2e, bName, "chat_reply")), "the reply placeholder follows the choice: "
            + placeholder(e2e, bName, "chat_reply"));
        c.clearLogs();
        pm(e2e, b, "r this goes to the last writer");
        e2e.eventually(() -> c.chatContains("From " + bName + ": this goes to the last writer"), "/r reached C: " + c.chat());
        set(e2e, bName, "reply-target", "last-conversation");
        e2e.expect(cName.equals(placeholder(e2e, bName, "chat_reply")), "the classic /r: the last conversation is C now: "
            + placeholder(e2e, bName, "chat_reply"));

        e2e.step("who can message me: friends only refuses strangers and lets friends through");
        befriend(e2e, bName, palName);
        set(e2e, bName, "private-messages", "friends");
        stranger.clearLogs();
        pm(e2e, stranger, "msg " + bName + " a stranger writes");
        expectSaw(e2e, stranger, bName + " isn't taking private messages");
        e2e.expect(!b.chatContains("a stranger writes"), "nothing arrived");
        pm(e2e, pal, "msg " + bName + " your friend writes");
        e2e.eventually(() -> b.chatContains("From " + palName + ": your friend writes"), "a friend's message: " + b.chat());

        e2e.step("/msgtoggle from friends only turns messages off, and back on to everyone");
        b.clearLogs();
        b.command("msgtoggle");
        expectSaw(e2e, b, "Private messages to you are off");
        e2e.expect(choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.NOBODY, "nobody");
        b.command("msgtoggle");
        expectSaw(e2e, b, "Players can send you private messages again");
        e2e.expect(choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.EVERYONE, "everyone");

        e2e.step("a lock in features/settings.yml, written the old way (false), wins and /msgtoggle says so");
        withSettingsLock(e2e, "private-messages", "false", x -> {
            e2e.expect(choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.NOBODY, "the old 'false' reads as nobody");
            b.clearLogs();
            b.command("msgtoggle");
            expectSaw(e2e, b, "Who can message me is set by the server");
            e2e.expect(choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.NOBODY, "still locked");
            stranger.clearLogs();
            pm(e2e, stranger, "msg " + bName + " locked out now");
            expectSaw(e2e, stranger, bName + " isn't taking private messages");
        });
        e2e.eventually(() -> choice(e2e, bName, ChatFeature.PRIVATE_MESSAGES) == Audience.EVERYONE, "unlocked again");
    }

    static void balancePrivacy(E2E e2e) {
        String ownerName = e2e.name("Rich");
        String viewerName = e2e.name("Curious");
        String staffName = e2e.name("EcoMod");
        Bot owner = e2e.bot(ownerName);
        Bot viewer = e2e.bot(viewerName);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        e2e.console("eco set " + ownerName + " 4242");
        try {
            e2e.step("balance privacy nobody: the hover card leaves the balance out, except for the owner and staff");
            set(e2e, ownerName, "balance-privacy", "nobody");
            viewer.clearLogs();
            staff.clearLogs();
            owner.clearLogs();
            say(e2e, owner, "is my balance private now");
            e2e.expect(!card(e2e, viewer, ownerName, "is my balance private now").contains("Balance"), "no balance for a stranger");
            e2e.expect(card(e2e, staff, ownerName, "is my balance private now").contains("Balance $4,242"), "staff see it");
            e2e.expect(card(e2e, owner, ownerName, "is my balance private now").contains("Balance $4,242"), "the owner sees it");

            e2e.step("friends only: a friend sees it");
            befriend(e2e, ownerName, viewerName);
            set(e2e, ownerName, "balance-privacy", "friends");
            viewer.clearLogs();
            say(e2e, owner, "and now for my friends");
            e2e.expect(card(e2e, viewer, ownerName, "and now for my friends").contains("Balance $4,242"), "a friend sees it");
        } finally {
            e2e.console("deop " + staffName);
        }
    }

    /** The hover card on {@code name} in the line containing {@code text} that {@code bot} received. */
    private static String card(E2E e2e, Bot bot, String name, String text) {
        Component line = line(e2e, bot, text);
        Component part = part(line, name);
        e2e.expect(part != null && part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText, "a card on " + name + ": " + line);
        return ((HoverEvent.ShowText) part.getStyle().getHoverEvent()).value().getString();
    }
}
