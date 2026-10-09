package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.feature.cosmetics.CosmeticsFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * End-to-end scenarios of the cosmetic rank perks: chat colours (dialog, command, the colour rules, viewers who turn
 * colours off, an expired rank), nicknames (chat, hover, /msg, mentions, /realname, refusals, staff), chat tags
 * (tiers, monthly exclusives), rank and custom join and leave lines (with LuckPerms ranks, vanish, the cooldown),
 * kill effects (particles and the lightning bolt reach clients, rate limits, opting out, no errors) and the menu.
 */
final class CosmeticsScenarios {

    /** Anti-spam allows one message a second by default; scenarios leave room for network and thread jitter. */
    private static final long CHAT_GAP = 1_400;
    private static final long JOIN_PROTECTION_MILLIS = 3_500;
    private static final String CONFIG = "features/cosmetics.yml";

    private CosmeticsScenarios() {
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
        list.add(of("cosmetics-chat-color", CosmeticsScenarios::chatColour));
        list.add(of("cosmetics-nick", CosmeticsScenarios::nick));
        list.add(of("cosmetics-tags", CosmeticsScenarios::tags));
        list.add(of("cosmetics-join-lines", CosmeticsScenarios::joinLines));
        list.add(of("cosmetics-kill-effects", CosmeticsScenarios::killEffects));
        list.add(of("cosmetics-menu", CosmeticsScenarios::menu));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    private static Cosmetics cosmetics(E2E e2e) {
        return e2e.feature(CosmeticsFeature.class).cosmetics();
    }

    /** Grants permission nodes until revoked; returns the attachment. */
    private static PermissionAttachment grant(E2E e2e, String name, String... nodes) {
        PermissionAttachment attachment = e2e.onPlayer(name, () -> {
            Player player = e2e.player(name);
            PermissionAttachment created = player.addAttachment(harness());
            for (String node : nodes) {
                created.setPermission(node, true);
            }
            player.updateCommands();
            return created;
        });
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission(nodes[0])), name + " has " + nodes[0]);
        return attachment;
    }

    private static void revoke(E2E e2e, String name, PermissionAttachment attachment) {
        e2e.onPlayer(name, () -> {
            e2e.player(name).removeAttachment(attachment);
            e2e.player(name).updateCommands();
            return null;
        });
        e2e.sleep(200);
    }

    private static void say(E2E e2e, Bot bot, String message) {
        e2e.sleep(CHAT_GAP);
        bot.chat(message);
    }

    private static void command(E2E e2e, Bot bot, String command) {
        bot.clearMessages();
        bot.command(command);
        e2e.sleep(300);
    }

    private static void expectSaw(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.anyFeedbackContains(text), bot.name + " sees '" + text + "' (chat " + bot.chat() + ", action bar "
            + bot.actionBar() + ")");
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

    /** A part of a component tree whose own text is {@code text}, with the colour it shows in (inherited colours resolved). */
    private record Part(Component component, Integer color) {
    }

    private static Part part(Component root, String text) {
        return part(root, text, null);
    }

    private static Part part(Component node, String text, Integer inherited) {
        TextColor own = node.getStyle().getColor();
        Integer color = own == null ? inherited : own.getValue();
        if (node.getContents() instanceof PlainTextContents plain && plain.text().equals(text)) {
            return new Part(node, color);
        }
        for (Component sibling : node.getSiblings()) {
            Part found = part(sibling, text, color);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The colour a typed part shows in (white when it inherits nothing). */
    private static int colour(Component root, String text) {
        Part found = part(root, text);
        if (found == null) {
            throw new E2E.Failure("no part '" + text + "' in " + root);
        }
        return found.color() == null ? 0xFFFFFF : found.color();
    }

    private static String hoverText(Component component) {
        return component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText show ? show.value().getString() : null;
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(e2e.player(name), placeholder);
    }

    /** One column of a player's row in {@code player_cosmetics}, or null without a row. */
    private static String stored(E2E e2e, UUID player, String column) {
        var database = e2e.services().database();
        database.flush();
        try {
            return database.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM player_cosmetics WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading player_cosmetics failed: " + e);
        }
    }

    /** The details of the newest audit entry for an action and target, or null. */
    private static String auditDetails(E2E e2e, String action, UUID target) {
        var database = e2e.services().database();
        database.flush();
        try {
            return database.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT details FROM audit_log WHERE action = ? AND target = ? ORDER BY id DESC LIMIT 1")) {
                    ps.setString(1, action);
                    ps.setString(2, target.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading the audit log failed: " + e);
        }
    }

    private static long audits(E2E e2e, String action, UUID target) {
        var database = e2e.services().database();
        database.flush();
        try {
            return database.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ? AND target = ?")) {
                    ps.setString(1, action);
                    ps.setString(2, target.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading the audit log failed: " + e);
        }
    }

    /** Runs {@code body} with text in a SiftCore config file replaced, then restores the file and reloads. */
    private static void withConfig(E2E e2e, String file, Map<String, String> replacements, Body body) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(file);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), file + " contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        Files.writeString(path, changed, StandardCharsets.UTF_8);
        e2e.expect(String.join(" ", e2e.consoleOutput("sift reload")).contains("Reloaded"), "the changed " + file + " reloads");
        try {
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /** Collects warnings and errors the server logs while it is open (SiftCore's and the scheduler's). */
    private static final class Problems extends Handler implements AutoCloseable {

        final List<String> seen = new CopyOnWriteArrayList<>();
        private final Logger root = Logger.getLogger("");

        Problems() {
            this.root.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                String message = record.getMessage() == null ? "" : record.getMessage();
                if (record.getThrown() != null || message.contains("SiftCore") || message.toLowerCase().contains("exception")) {
                    this.seen.add(record.getLevel() + " " + message + (record.getThrown() == null ? "" : " " + record.getThrown()));
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            this.root.removeHandler(this);
        }
    }

    /** Puts the victim where the attacker stands, so a punch reaches. */
    private static void together(E2E e2e, String attacker, String victim) {
        Location at = e2e.onPlayer(attacker, () -> e2e.player(attacker).getLocation().clone());
        try {
            Boolean moved = e2e.player(victim).teleportAsync(at).get(10, TimeUnit.SECONDS);
            e2e.expect(Boolean.TRUE.equals(moved), victim + " was moved next to " + attacker);
        } catch (E2E.Failure failure) {
            throw failure;
        } catch (Exception e) {
            throw new E2E.Failure("moving " + victim + " next to " + attacker + " failed: " + e);
        }
        e2e.sleep(400);
    }

    /** Moves a bot onto the ground {@code dx} blocks east of where another player stands and waits until its client is there. */
    private static void standApart(E2E e2e, Bot bot, String from, int dx) {
        Location base = e2e.onPlayer(from, () -> e2e.player(from).getLocation().clone());
        Location target = e2e.ground(base.getWorld(), base.getBlockX() + dx, base.getBlockZ(), base.getYaw());
        e2e.player(bot.name).teleportAsync(target);
        e2e.eventually(() -> Math.abs(bot.x() - target.getX()) < 0.5 && Math.abs(bot.z() - target.getZ()) < 0.5,
            bot.name + " stands " + dx + " blocks from " + from);
        e2e.sleep(1_000);
    }

    /** Brings the victim down to half a heart and hits them until they die, then waits out the respawn protection. */
    private static void killWithHit(E2E e2e, Bot killer, Bot victim) {
        together(e2e, killer.name, victim.name);
        int before = victim.deaths();
        long end = System.currentTimeMillis() + 12_000;
        while (victim.deaths() == before && System.currentTimeMillis() < end) {
            Player target = e2e.player(victim.name);
            e2e.onPlayer(victim.name, () -> {
                if (!target.isDead()) {
                    target.setHealth(Math.min(target.getHealth(), 0.5));
                }
                return null;
            });
            killer.attack(target.getEntityId());
            Bot.await(() -> victim.deaths() > before, 700);
        }
        e2e.expect(victim.deaths() > before, victim.name + " died from " + killer.name + "'s hit");
    }

    /** Waits for the respawn, moves the player out of the protected spawn, then waits out the respawn protection. */
    private static void afterDeath(E2E e2e, Bot victim) {
        String name = victim.name;
        e2e.eventually(() -> {
            Player player = Bukkit.getPlayerExact(name);
            return player != null && !player.isDead() && player.getHealth() > 0;
        }, name + " respawned");
        e2e.leaveSpawn(victim);
        e2e.sleep(JOIN_PROTECTION_MILLIS);
    }

    private static final Pattern EFFECT_COUNTS = Pattern.compile("Kill effects played (\\d+), held back by the limits (\\d+)");

    /** Kill effects played and held back, from {@code /cosmetics admin}. */
    private static long[] effectCounts(E2E e2e) {
        String status = String.join(" ", e2e.consoleOutput("cosmetics admin"));
        Matcher matcher = EFFECT_COUNTS.matcher(status);
        e2e.expect(matcher.find(), "the status lists kill effects: " + status);
        return new long[] {Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
    }

    // ------------------------------------------------------------------ chat colour

    static void chatColour(E2E e2e) throws Exception {
        String talkerName = e2e.name("Painter");
        String readerName = e2e.name("Viewer");
        Bot talker = e2e.bot(talkerName);
        Bot reader = e2e.bot(readerName);
        UUID talkerId = e2e.uuid(talkerName);
        Toggle colours = e2e.services().settings().toggle("show-chat-colors");
        e2e.expect(colours != null, "the show-chat-colors setting is registered");

        e2e.step("Baron colours: the dialog offers vanilla colours without red or green");
        PermissionAttachment baron = grant(e2e, talkerName, "siftcore.chat.color");
        talker.clearLogs();
        talker.command("chatcolor");
        Bot.SeenDialog dialog = e2e.dialog(talker, "Chat colour");
        for (String offered : List.of("Gold", "Yellow", "Aqua", "Blue", "Light purple", "Dark purple", "Gray", "More colours", "Default colour")) {
            e2e.expect(dialog.button(offered) != null, "a '" + offered + "' button: " + dialog.buttons());
        }
        e2e.expect(dialog.button("Red") == null && dialog.button("Green") == null, "no red or green: " + dialog.buttons());
        talker.clearMessages();
        e2e.click(talker, "Gold");
        expectSaw(e2e, talker, "Your chat colour is now Gold");
        e2e.expect("gold".equals(placeholder(e2e, talkerName, "chat_color")), "chat_color: " + placeholder(e2e, talkerName, "chat_color"));

        e2e.step("other players see the message in gold");
        reader.clearLogs();
        talker.clearLogs();
        say(e2e, talker, "hello in gold");
        Component seen = line(e2e, reader, "hello in gold");
        e2e.expect(seen.getString().equals(talkerName + ": hello in gold"), "the line: " + seen.getString());
        e2e.expect(colour(seen, "hello in gold") == 0xFFAA00, "the message is gold: " + seen);
        e2e.expect(colour(seen, talkerName) == 0xFFFFFF, "the name keeps its colour");
        e2e.expect(colour(line(e2e, talker, "hello in gold"), "hello in gold") == 0xFFAA00, "the sender sees it too");

        e2e.step("a viewer who turned chat colours off sees it plain, the sender still in colour");
        e2e.services().settings().set(e2e.uuid(readerName), colours, false);
        reader.clearLogs();
        talker.clearLogs();
        say(e2e, talker, "plain for you");
        e2e.expect(colour(line(e2e, reader, "plain for you"), "plain for you") == 0xFFFFFF, "plain for the viewer");
        e2e.expect(colour(line(e2e, talker, "plain for you"), "plain for you") == 0xFFAA00, "gold for the sender");
        e2e.services().settings().set(e2e.uuid(readerName), colours, true);

        e2e.step("hex colours and gradients are Tycoon's");
        command(e2e, talker, "chatcolor #55FFFF");
        expectSaw(e2e, talker, "comes with the Tycoon rank");
        talker.clearLogs();
        talker.command("chatcolor");
        e2e.dialog(talker, "Chat colour");
        e2e.click(talker, "More colours");
        e2e.eventually(() -> talker.dialog() != null && talker.dialog().bodyText().contains("Tycoon rank"),
            "the dialog says what unlocks it: " + (talker.dialog() == null ? "none" : talker.dialog().bodyText()));

        e2e.step("red, green and unreadable colours are refused, also as a gradient");
        PermissionAttachment tycoon = grant(e2e, talkerName, "siftcore.chat.color.hex");
        command(e2e, talker, "chatcolor #FF5555");
        expectSaw(e2e, talker, "too close to the red used for errors and kills");
        command(e2e, talker, "chatcolor #1AFF1A");
        expectSaw(e2e, talker, "too close to the green used for money");
        command(e2e, talker, "chatcolor #00AA00");
        expectSaw(e2e, talker, "too close to the green used for money");
        command(e2e, talker, "chatcolor #222222");
        expectSaw(e2e, talker, "too dark to read");
        command(e2e, talker, "chatcolor #FFAA00 #FF55FF");
        expectSaw(e2e, talker, "too close to the red");
        command(e2e, talker, "chatcolor purple-ish");
        expectSaw(e2e, talker, "is not a colour");
        e2e.expect("gold".equals(placeholder(e2e, talkerName, "chat_color")), "the colour stayed gold");

        e2e.step("the mixer refuses red and keeps what was typed, then takes a gradient");
        talker.clearLogs();
        talker.command("chatcolor");
        e2e.dialog(talker, "Chat colour");
        e2e.click(talker, "More colours");
        Bot.SeenDialog more = e2e.dialog(talker, "More colours");
        e2e.expect(more.button("Candy") != null && more.button("Ocean") != null && more.button("Mix your own") != null,
            "presets and the mixer: " + more.buttons());
        e2e.click(talker, "Mix your own");
        e2e.dialog(talker, "Mix a colour");
        e2e.click(talker, "Use it", Map.of("from", "#ff4b4b", "to", ""));
        e2e.eventually(() -> talker.dialog() != null && talker.dialog().bodyText().contains("too close to the red"),
            "refused in the dialog: " + (talker.dialog() == null ? "none" : talker.dialog().bodyText()));
        e2e.expect("#ff4b4b".equals(talker.dialog().initial("from")), "the typed colour is kept: " + talker.dialog().initial("from"));
        talker.clearMessages();
        e2e.click(talker, "Use it", Map.of("from", "#55ffff", "to", "#5555ff"));
        expectSaw(e2e, talker, "Your chat colour is now");
        e2e.expect("#55FFFF:#5555FF".equals(placeholder(e2e, talkerName, "chat_color")), "the gradient: "
            + placeholder(e2e, talkerName, "chat_color"));

        e2e.step("a gradient runs across the message");
        reader.clearLogs();
        say(e2e, talker, "qwxyz");
        Component gradient = line(e2e, reader, "qwxyz");
        e2e.expect(colour(gradient, "q") == 0x55FFFF && colour(gradient, "z") == 0x5555FF, "from aqua to blue: " + gradient);

        e2e.step("an expired rank falls back to the default colour and keeps the choice");
        revoke(e2e, talkerName, tycoon);
        revoke(e2e, talkerName, baron);
        reader.clearLogs();
        say(e2e, talker, "back to plain");
        e2e.expect(colour(line(e2e, reader, "back to plain"), "back to plain") == 0xFFFFFF, "plain again");
        e2e.expect(placeholder(e2e, talkerName, "chat_color").isEmpty(), "no colour shown: " + placeholder(e2e, talkerName, "chat_color"));
        e2e.expect("#55FFFF:#5555FF".equals(stored(e2e, talkerId, "chat_style")), "the choice is kept: " + stored(e2e, talkerId, "chat_style"));

        e2e.step("rebuying the rank brings it back");
        PermissionAttachment again = grant(e2e, talkerName, "siftcore.chat.color.hex");
        reader.clearLogs();
        say(e2e, talker, "colour is back");
        Component back = line(e2e, reader, "colour is back");
        e2e.expect(colour(back, "c") == 0x55FFFF, "the gradient is back: " + back);
        command(e2e, talker, "chatcolor reset");
        expectSaw(e2e, talker, "Your chat is back to the default colour");
        e2e.expect(stored(e2e, talkerId, "chat_style") == null, "the reset is stored");
        revoke(e2e, talkerName, again);
    }

    // ------------------------------------------------------------------ nicknames

    static void nick(E2E e2e) throws Exception {
        String nickedName = e2e.name("Nicked");
        String watcherName = e2e.name("NickWatch");
        String rivalName = e2e.name("NickRival");
        Bot nicked = e2e.bot(nickedName);
        Bot watcher = e2e.bot(watcherName);
        Bot rival = e2e.bot(rivalName);
        UUID nickedId = e2e.uuid(nickedName);
        String nick = e2e.name("Nk");

        e2e.step("the nickname form saves a nickname in a vanilla colour");
        PermissionAttachment baron = grant(e2e, nickedName, "siftcore.command.nick");
        nicked.clearLogs();
        nicked.command("nick");
        Bot.SeenDialog form = e2e.dialog(nicked, "Nickname");
        e2e.expect(form.inputs().containsKey("nick") && form.inputs().containsKey("style") && !form.inputs().containsKey("custom"),
            "a name and a colour, no hex field for Baron: " + form.inputs());
        e2e.click(nicked, "Save", Map.of("nick", nick, "style", "c:aqua"));
        expectSaw(e2e, nicked, "You now show as " + nick);
        e2e.expect(nick.equals(placeholder(e2e, nickedName, "nick")), "%siftcore_nick%: " + placeholder(e2e, nickedName, "nick"));
        e2e.expect(nick.equals(placeholder(e2e, nickedName, "display_name")), "display_name");
        e2e.expect(("<aqua>" + nick + "</aqua>").equals(placeholder(e2e, nickedName, "display_name_mm")),
            "display_name_mm: " + placeholder(e2e, nickedName, "display_name_mm"));

        e2e.step("chat shows the nickname in its colour, the real name on hover, and the profile and /msg go to the real name");
        watcher.clearLogs();
        say(e2e, nicked, "hi from a nickname");
        Component seen = line(e2e, watcher, "hi from a nickname");
        e2e.expect(seen.getString().equals(nick + ": hi from a nickname"), "the line: " + seen.getString());
        Part name = part(seen, nick);
        e2e.expect(name != null && name.color() != null && name.color() == 0x55FFFF, "the nickname is aqua: " + seen);
        String card = hoverText(name.component());
        e2e.expect(card != null && card.contains("Real name " + nickedName), "the hover card has the real name: " + card);
        e2e.expect(name.component().getStyle().getClickEvent() instanceof ClickEvent.RunCommand run
            && run.command().equals("/profile " + nickedName), "clicking opens the real name's profile: " + name.component().getStyle());
        e2e.expect(("/msg " + nickedName + " ").equals(name.component().getStyle().getInsertion()),
            "shift-click starts a message to the real name");

        e2e.step("a second change right away waits for the cooldown");
        command(e2e, nicked, "nick " + e2e.name("Nq"));
        expectSaw(e2e, nicked, "You can change your nickname again in");

        e2e.step("staff-like names, other players' names, filtered words and taken nicknames are refused");
        PermissionAttachment rivalBaron = grant(e2e, rivalName, "siftcore.command.nick");
        command(e2e, rival, "nick xAdminx");
        expectSaw(e2e, rival, "Nicknames can't contain admin");
        command(e2e, rival, "nick Mod_Squad");
        expectSaw(e2e, rival, "Nicknames can't contain mod");
        command(e2e, rival, "nick " + watcherName);
        expectSaw(e2e, rival, "That is another player's name");
        command(e2e, rival, "nick kys_now");
        expectSaw(e2e, rival, "has a word that isn't allowed");
        command(e2e, rival, "nick " + nick.toUpperCase());
        expectSaw(e2e, rival, "Someone else uses that nickname");
        command(e2e, rival, "nick Ab-cd");
        expectSaw(e2e, rival, "Use letters, digits and underscores only");

        e2e.step("players reach a nickname with /msg and mentions, and /realname tells who it is");
        nicked.clearLogs();
        watcher.clearLogs();
        e2e.sleep(CHAT_GAP);
        watcher.command("msg " + nick + " psst");
        e2e.eventually(() -> nicked.chatContains("From " + watcherName + ": psst"), "the message arrives: " + nicked.chat());
        e2e.eventually(() -> watcher.chatContains("To " + nick + ": psst"), "the sender sees the nickname: " + watcher.chat());
        nicked.clearLogs();
        say(e2e, watcher, "hey " + nick.toLowerCase() + " look");
        e2e.eventually(() -> nicked.actionBarContains("mentioned you"), "a mention by nickname: " + nicked.actionBar());
        watcher.clearLogs();
        watcher.command("realname " + nick);
        expectSaw(e2e, watcher, nick + " is " + nickedName);

        e2e.step("Tycoon nicknames take a gradient");
        PermissionAttachment tycoon = grant(e2e, nickedName, "siftcore.nick.gradient");
        nicked.clearLogs();
        nicked.command("nick");
        Bot.SeenDialog premium = e2e.dialog(nicked, "Nickname");
        e2e.expect(premium.inputs().containsKey("custom"), "a hex field for Tycoon: " + premium.inputs());
        e2e.expect(nick.equals(premium.initial("nick")), "the form shows the nickname: " + premium.initial("nick"));
        e2e.click(nicked, "Save", Map.of("nick", nick, "style", "custom", "custom", "#ff5555"));
        e2e.eventually(() -> nicked.dialog() != null && nicked.dialog().bodyText().contains("too close to the red"),
            "a red nickname is refused: " + (nicked.dialog() == null ? "none" : nicked.dialog().bodyText()));
        nicked.clearMessages();
        e2e.click(nicked, "Save", Map.of("nick", nick, "style", "p:candy", "custom", ""));
        expectSaw(e2e, nicked, "You now show as");
        e2e.expect(placeholder(e2e, nickedName, "display_name_mm").equals("<gradient:#FF6AD5:#B26BFF>" + nick + "</gradient>"),
            "the gradient for the tab list: " + placeholder(e2e, nickedName, "display_name_mm"));

        e2e.step("an expired rank shows the real name again and keeps the nickname");
        revoke(e2e, nickedName, tycoon);
        revoke(e2e, nickedName, baron);
        watcher.clearLogs();
        say(e2e, nicked, "who am i now");
        e2e.expect(line(e2e, watcher, "who am i now").getString().equals(nickedName + ": who am i now"), "the real name");
        e2e.expect(placeholder(e2e, nickedName, "nick").isEmpty() && nickedName.equals(placeholder(e2e, nickedName, "display_name")),
            "placeholders follow");
        e2e.expect(nick.equals(stored(e2e, nickedId, "nick")), "the nickname is kept: " + stored(e2e, nickedId, "nick"));
        command(e2e, rival, "nick " + nick);
        expectSaw(e2e, rival, "Someone else uses that nickname");
        UUID rivalId = e2e.uuid(rivalName);

        e2e.step("once the hold has run out another player takes the nickname, and the old holder is told");
        withConfig(e2e, CONFIG, Map.of("hold: 14d", "hold: 0s"), x -> {
            nicked.clearLogs();
            command(e2e, rival, "nick " + nick);
            expectSaw(e2e, rival, "You now show as " + nick);
            expectSaw(e2e, nicked, "Another player took the nickname " + nick);
            e2e.expect(stored(e2e, nickedId, "nick") == null, "the old holder lost it: " + stored(e2e, nickedId, "nick"));
            e2e.expect(stored(e2e, nickedId, "nick_lost") == null, "told right away, so nothing is left to tell");
            e2e.expect(nick.equals(stored(e2e, rivalId, "nick")), "the new holder has it");
            String passed = auditDetails(e2e, "cosmetics.nick", nickedId);
            e2e.expect(passed != null && passed.contains("lost '" + nick + "' to " + rivalName), "the hand-over is audited: " + passed);
        });

        e2e.step("staff remove a nickname from the console, audited");
        rival.clearLogs();
        List<String> out = e2e.consoleOutput("nick " + rivalName + " off");
        e2e.expect(String.join(" ", out).contains("nickname is removed"), "staff reply: " + out);
        expectSaw(e2e, rival, "Staff removed your nickname");
        e2e.expect(stored(e2e, rivalId, "nick") == null, "the nickname is gone");
        e2e.expect(String.valueOf(auditDetails(e2e, "cosmetics.nick", rivalId)).contains("removed '" + nick + "'"), "the removal is audited");
        List<String> set = e2e.consoleOutput("nick " + nickedName + " xAdminx");
        e2e.expect(String.join(" ", set).contains("can't be used"), "staff can't set a staff-like nickname either: " + set);

        e2e.step("a nickname that becomes a new player's real name stops showing, and that player can take it");
        String realName = e2e.name("Rl");
        List<String> given = e2e.consoleOutput("nick " + rivalName + " " + realName);
        e2e.expect(String.join(" ", given).contains("now shows as"), "staff set the nickname: " + given);
        Bot real = e2e.bot(realName);
        watcher.clearLogs();
        say(e2e, rival, "my name again");
        e2e.expect(line(e2e, watcher, "my name again").getString().equals(rivalName + ": my name again"), "the real name wins");
        PermissionAttachment realBaron = grant(e2e, realName, "siftcore.command.nick");
        rival.clearLogs();
        command(e2e, real, "nick " + realName.toUpperCase());
        expectSaw(e2e, real, "You now show as " + realName.toUpperCase());
        expectSaw(e2e, rival, "Another player took the nickname " + realName);
        e2e.expect(stored(e2e, rivalId, "nick") == null, "the shadowed holder lost it");
        revoke(e2e, realName, realBaron);
        revoke(e2e, rivalName, rivalBaron);
    }

    // ------------------------------------------------------------------ tags

    static void tags(E2E e2e) throws Exception {
        String taggerName = e2e.name("Tagger");
        String readerName = e2e.name("TagReader");
        Bot tagger = e2e.bot(taggerName);
        Bot reader = e2e.bot(readerName);
        UUID taggerId = e2e.uuid(taggerName);
        boolean october = YearMonth.now().equals(YearMonth.of(2026, 10));

        e2e.step("without a rank every tag is locked and says which rank unlocks it");
        tagger.clearLogs();
        tagger.command("tags");
        Bot.SeenDialog dialog = e2e.dialog(tagger, "Chat tags");
        e2e.expect(dialog.bodyText().contains("You can use 0 of the"), "nothing usable: " + dialog.bodyText());
        e2e.expect(dialog.button("[Miner]") != null && dialog.button("[Mogul]") != null, "tags are listed: " + dialog.buttons());
        e2e.click(tagger, "[Miner]");
        e2e.eventually(() -> tagger.dialog() != null && tagger.dialog().bodyText().contains("That tag comes with the Prospector rank"),
            "the lock is explained: " + (tagger.dialog() == null ? "none" : tagger.dialog().bodyText()));

        e2e.step("Prospector picks one of three tags; it shows before the name in chat");
        PermissionAttachment prospector = grant(e2e, taggerName, "siftcore.tags.prospector");
        tagger.clearLogs();
        tagger.command("tags");
        e2e.eventually(() -> tagger.dialog() != null && tagger.dialog().bodyText().contains("You can use 3 of the"),
            "three tags: " + (tagger.dialog() == null ? "none" : tagger.dialog().bodyText()));
        tagger.clearMessages();
        e2e.click(tagger, "[Miner]");
        expectSaw(e2e, tagger, "Your chat tag is now [Miner]");
        reader.clearLogs();
        say(e2e, tagger, "tagged hello");
        Component seen = line(e2e, reader, "tagged hello");
        e2e.expect(seen.getString().equals("[Miner] " + taggerName + ": tagged hello"), "the tag before the name: " + seen.getString());
        Part miner = part(seen, "Miner");
        e2e.expect(miner != null && miner.color() != null && miner.color() == 0x9CCBFF, "the tag's colour: " + seen);
        e2e.expect("[Miner]".equals(placeholder(e2e, taggerName, "tag_plain")) && "miner".equals(placeholder(e2e, taggerName, "tag_id")),
            "tag placeholders: " + placeholder(e2e, taggerName, "tag_plain"));
        e2e.expect(placeholder(e2e, taggerName, "tag").contains("Miner"), "the MiniMessage tag: " + placeholder(e2e, taggerName, "tag"));
        command(e2e, tagger, "tags mogul");
        expectSaw(e2e, tagger, "That tag comes with the Tycoon rank");

        e2e.step("Baron and Tycoon tags include the tiers below");
        PermissionAttachment tycoon = grant(e2e, taggerName, "siftcore.tags.tycoon");
        e2e.eventually(() -> e2e.onPlayer(taggerName, () -> e2e.player(taggerName).hasPermission("siftcore.tags.baron")),
            "the Tycoon node includes Baron's");
        command(e2e, tagger, "tags trader");
        expectSaw(e2e, tagger, "Your chat tag is now [Trader]");
        command(e2e, tagger, "tags mogul");
        expectSaw(e2e, tagger, "Your chat tag is now [Mogul]");

        if (october) {
            e2e.step("the monthly exclusive is picked in its month and kept for good");
            command(e2e, tagger, "tags spooky");
            expectSaw(e2e, tagger, "is yours for good");
            e2e.expect(String.valueOf(stored(e2e, taggerId, "owned_tags")).contains("spooky"), "ownership is stored: "
                + stored(e2e, taggerId, "owned_tags"));
            revoke(e2e, taggerName, tycoon);
            revoke(e2e, taggerName, prospector);
            reader.clearLogs();
            say(e2e, tagger, "still spooky");
            e2e.expect(line(e2e, reader, "still spooky").getString().startsWith("[Spooky] "), "an owned exclusive stays without the rank");
            command(e2e, tagger, "tags miner");
            expectSaw(e2e, tagger, "That tag comes with the Prospector rank");

            e2e.step("a staff reset keeps the owned exclusive and writes what was there to the audit log");
            tagger.clearLogs();
            List<String> reset = e2e.consoleOutput("cosmetics admin reset " + taggerName);
            e2e.expect(String.join(" ", reset).contains("owned tags are kept"), "staff reply: " + reset);
            expectSaw(e2e, tagger, "Staff reset your cosmetics");
            e2e.expect(String.valueOf(stored(e2e, taggerId, "owned_tags")).contains("spooky"), "the exclusive is still owned");
            e2e.expect(stored(e2e, taggerId, "tag") == null, "the picked tag is reset");
            String details = auditDetails(e2e, "cosmetics.reset", taggerId);
            e2e.expect(details != null && details.contains("tag=spooky") && details.contains("owned=spooky"), "the audit lists it: " + details);
            command(e2e, tagger, "tags spooky");
            expectSaw(e2e, tagger, "Your chat tag is now [Spooky]");

            e2e.step("staff take an owned tag away and give it back");
            List<String> taken = e2e.consoleOutput("cosmetics admin owned " + taggerName + " take spooky");
            e2e.expect(String.join(" ", taken).contains("no longer owns the tag spooky"), "staff reply: " + taken);
            e2e.expect(placeholder(e2e, taggerName, "tag_id").isEmpty(), "a taken tag is no longer shown without the rank: "
                + placeholder(e2e, taggerName, "tag_id"));
            command(e2e, tagger, "tags spooky");
            expectSaw(e2e, tagger, "That tag comes with the");
            List<String> given = e2e.consoleOutput("cosmetics admin owned " + taggerName + " give spooky");
            e2e.expect(String.join(" ", given).contains("owns the tag spooky for good now"), "staff reply: " + given);
            e2e.expect(audits(e2e, "cosmetics.owned", taggerId) >= 2, "both are audited");
            command(e2e, tagger, "tags spooky");
            expectSaw(e2e, tagger, "Your chat tag is now [Spooky]");
            List<String> shown = e2e.consoleOutput("cosmetics admin show " + taggerName);
            e2e.expect(String.join(" ", shown).contains("Owned tags spooky"), "staff see owned tags: " + shown);
        } else {
            e2e.log("not October 2026: the monthly exclusive is not on offer, only checked by the unit tests");
            revoke(e2e, taggerName, tycoon);
            reader.clearLogs();
            say(e2e, tagger, "rank expired");
            e2e.expect(line(e2e, reader, "rank expired").getString().equals(taggerName + ": rank expired"), "an expired tag is not shown");
            revoke(e2e, taggerName, prospector);

            e2e.step("a staff reset is audited with what was there");
            List<String> reset = e2e.consoleOutput("cosmetics admin reset " + taggerName);
            e2e.expect(String.join(" ", reset).contains("cosmetics are reset"), "staff reply: " + reset);
            e2e.expect(String.valueOf(auditDetails(e2e, "cosmetics.reset", taggerId)).contains("tag=mogul"), "the audit lists the tag");
        }

        e2e.step("/tags off removes the tag");
        if (october) {
            command(e2e, tagger, "tags off");
            expectSaw(e2e, tagger, "You show no tag now");
        } else {
            command(e2e, tagger, "tags off");
            expectSaw(e2e, tagger, "You show no tag");
        }
        e2e.expect(stored(e2e, taggerId, "tag") == null, "no tag stored");
    }

    // ------------------------------------------------------------------ join and leave lines

    static void joinLines(E2E e2e) throws Exception {
        String rankedName = e2e.name("Joiner");
        String watcherName = e2e.name("JoinWatch");
        Bot watcher = e2e.bot(watcherName);
        boolean luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null && Bukkit.getPluginManager().getPlugin("LuckPerms").isEnabled();
        e2e.expect(luckPerms, "LuckPerms is installed on the test server (rank lines come from LuckPerms groups)");
        String group = "e2ecosmetics" + Long.toString(System.nanoTime() % 1_000, 36);

        e2e.step("a brand-new player still gets the welcome");
        watcher.clearLogs();
        Bot ranked = e2e.bot(rankedName);
        e2e.eventually(() -> watcher.chatContains("Welcome " + rankedName), "the first-join welcome: " + watcher.chat());

        e2e.step("a rank group with the rank join line, like Baron on the live server");
        e2e.console("lp creategroup " + group);
        e2e.console("lp group " + group + " meta set siftcore-rank Baron");
        e2e.console("lp group " + group + " meta set siftcore-rank-color #FFAA00");
        e2e.console("lp group " + group + " permission set siftcore.join.message true");
        e2e.console("lp user " + rankedName + " parent add " + group);
        e2e.eventually(() -> e2e.onPlayer(rankedName, () -> e2e.player(rankedName).hasPermission("siftcore.join.message")),
            "the rank's permission arrived");
        try {
            withConfig(e2e, CONFIG, Map.of("cooldown: 60s", "cooldown: 0s"), x -> {
                e2e.step("leaving and joining show the rank line in the rank's colour");
                watcher.clearLogs();
                ranked.quit();
                e2e.eventually(() -> Bukkit.getPlayerExact(rankedName) == null, "left");
                Component left = line(e2e, watcher, rankedName + " left");
                e2e.expect(left.getString().equals("Baron " + rankedName + " left"), "the rank leave line: " + left.getString());
                e2e.expect(colour(left, "Baron") == 0xFFAA00, "the rank in its colour: " + left);
                watcher.clearLogs();
                Bot back = e2e.bot(rankedName);
                Component joined = line(e2e, watcher, rankedName + " joined");
                e2e.expect(joined.getString().equals("Baron " + rankedName + " joined"), "the rank join line: " + joined.getString());

                e2e.step("Tycoon writes their own messages: checked, previewed, then shown");
                e2e.console("lp group " + group + " permission set siftcore.join.message.custom true");
                e2e.eventually(() -> e2e.onPlayer(rankedName, () -> e2e.player(rankedName).hasPermission("siftcore.join.message.custom")),
                    "custom messages are allowed");
                command(e2e, back, "joinmessage set join play.example.net");
                expectSaw(e2e, back, "Links and server addresses aren't allowed");
                command(e2e, back, "joinmessage set {name} the kys");
                expectSaw(e2e, back, "has a word that isn't allowed");
                command(e2e, back, "joinmessage set " + "x".repeat(41));
                expectSaw(e2e, back, "The limit is 40 characters");
                command(e2e, back, "joinmessage set <red>{name} hacked");
                expectSaw(e2e, back, "can't use braces or angle brackets");
                command(e2e, back, "joinmessage set {name} rolls in");
                expectSaw(e2e, back, "Your join message is saved");
                command(e2e, back, "leavemessage set Make way, {name} heads out");
                expectSaw(e2e, back, "Your leave message is saved");
                back.clearLogs();
                back.command("joinmessage preview");
                e2e.eventually(() -> back.chatContains("Baron " + rankedName + " rolls in")
                    && back.chatContains("Baron Make way, " + rankedName + " heads out"), "the preview: " + back.chat());
                e2e.expect(placeholder(e2e, rankedName, "join_message").equals("{name} rolls in"), "join_message placeholder");

                e2e.step("without the custom permission the placeholder is empty like the line, and the message is kept");
                e2e.console("lp group " + group + " permission unset siftcore.join.message.custom");
                e2e.eventually(() -> !e2e.onPlayer(rankedName, () -> e2e.player(rankedName).hasPermission("siftcore.join.message.custom")),
                    "custom messages are no longer allowed");
                e2e.expect(placeholder(e2e, rankedName, "join_message").isEmpty(), "join_message is empty: "
                    + placeholder(e2e, rankedName, "join_message"));
                back.clearLogs();
                back.command("cosmetics");
                Bot.SeenDialog menu = e2e.dialog(back, "Cosmetics");
                e2e.expect(menu.bodyText().contains("Join message rank line"), "the menu shows the rank line: " + menu.bodyText());
                e2e.console("lp group " + group + " permission set siftcore.join.message.custom true");
                e2e.eventually(() -> e2e.onPlayer(rankedName, () -> e2e.player(rankedName).hasPermission("siftcore.join.message.custom")),
                    "custom messages are allowed again");
                e2e.expect(placeholder(e2e, rankedName, "join_message").equals("{name} rolls in"), "the kept message is back");

                watcher.clearLogs();
                back.quit();
                e2e.eventually(() -> watcher.chatContains("Make way, " + rankedName + " heads out"), "the custom leave line: " + watcher.chat());
                watcher.clearLogs();
                Bot again = e2e.bot(rankedName);
                e2e.eventually(() -> watcher.chatContains(rankedName + " rolls in"), "the custom join line: " + watcher.chat());

                e2e.step("the join message dialog shows both lines and resets to the rank lines");
                again.clearLogs();
                again.command("joinmessage");
                Bot.SeenDialog form = e2e.dialog(again, "Join and leave messages");
                e2e.expect(form.bodyText().contains(rankedName + " rolls in"), "the dialog previews the lines: " + form.bodyText());
                e2e.expect("{name} rolls in".equals(form.initial("join")), "the stored join message: " + form.initial("join"));
                again.clearMessages();
                e2e.click(again, "Use the rank lines");
                expectSaw(e2e, again, "You use the rank join and leave lines again");

                e2e.step("vanished staff are never announced");
                e2e.console("lp group " + group + " permission set siftcore.staff.vanish true");
                e2e.eventually(() -> e2e.onPlayer(rankedName, () -> e2e.player(rankedName).hasPermission("siftcore.staff.vanish")),
                    "may vanish");
                again.command("vanish");
                e2e.eventually(() -> again.actionBarContains("You are vanished"), "vanished: " + again.actionBar());
                watcher.clearLogs();
                again.quit();
                e2e.eventually(() -> Bukkit.getPlayerExact(rankedName) == null, "left");
                e2e.sleep(1_000);
                e2e.expect(!watcher.chatContains(rankedName), "no line for a vanished player: " + watcher.chat());
                Bot hidden = e2e.bot(rankedName);
                e2e.sleep(1_000);
                e2e.expect(!watcher.chatContains(rankedName), "no join line either: " + watcher.chat());
                hidden.command("vanish");
                e2e.eventually(() -> !e2e.feature(net.siftvanilla.siftcore.feature.staff.StaffFeature.class).vanish()
                    .vanished(e2e.uuid(rankedName)), "visible again");
            });

            withConfig(e2e, CONFIG, Map.of("cooldown: 60s", "cooldown: 5s"), x -> {
                e2e.step("with the cooldown a quick rejoin is not announced twice");
                e2e.sleep(5_500);
                e2e.expect(Bukkit.getPlayerExact(rankedName) != null, "the ranked player is online");
                watcher.clearLogs();
                e2e.consoleOutput("kick " + rankedName + " rejoin test");
                e2e.eventually(() -> Bukkit.getPlayerExact(rankedName) == null, "left");
                e2e.eventually(() -> watcher.chatContains("Baron " + rankedName + " left"), "the first leave line: " + watcher.chat());
                Bot quick = e2e.bot(rankedName);
                e2e.eventually(() -> watcher.chatContains("Baron " + rankedName + " joined"), "the join line: " + watcher.chat());
                watcher.clearLogs();
                quick.quit();
                e2e.eventually(() -> Bukkit.getPlayerExact(rankedName) == null, "left again");
                e2e.sleep(1_000);
                e2e.expect(!watcher.chatContains(rankedName), "the second leave within the cooldown is quiet: " + watcher.chat());
            });
        } finally {
            e2e.console("lp deletegroup " + group);
        }
    }

    // ------------------------------------------------------------------ kill effects

    static void killEffects(E2E e2e) throws Exception {
        String killerName = e2e.name("Effector");
        String victimName = e2e.name("EffVictim");
        Bot killer = e2e.bot(killerName);
        Bot victim = e2e.bot(victimName);
        Toggle effects = e2e.services().settings().toggle("show-kill-effects");
        e2e.expect(effects != null, "the show-kill-effects setting is registered");
        e2e.sleep(JOIN_PROTECTION_MILLIS);

        e2e.step("kill effects are Tycoon's; the dialog says so");
        killer.clearLogs();
        killer.command("killeffect");
        Bot.SeenDialog dialog = e2e.dialog(killer, "Kill effects");
        for (String effect : List.of("Hearts", "Flames", "Soul burst", "Totem burst", "Lightning", "Note burst", "Ender")) {
            e2e.expect(dialog.button(effect) != null, "a '" + effect + "' button: " + dialog.buttons());
        }
        e2e.click(killer, "Hearts");
        e2e.eventually(() -> killer.dialog() != null && killer.dialog().bodyText().contains("Tycoon rank"),
            "locked: " + (killer.dialog() == null ? "none" : killer.dialog().bodyText()));

        e2e.step("picking an effect shows it to the player");
        PermissionAttachment tycoon = grant(e2e, killerName, "siftcore.killeffect.*");
        e2e.eventually(() -> e2e.onPlayer(killerName, () -> e2e.player(killerName).hasPermission("siftcore.killeffect.hearts")),
            "the wildcard gives each effect");
        killer.clearLogs();
        killer.clearParticles();
        killer.command("killeffect");
        e2e.dialog(killer, "Kill effects");
        e2e.click(killer, "Hearts");
        expectSaw(e2e, killer, "Your kill effect is now Hearts");
        e2e.eventually(() -> killer.particles("minecraft:heart") > 0, "the preview's hearts: " + killer.particleTypes());
        e2e.expect("hearts".equals(placeholder(e2e, killerName, "kill_effect")), "kill_effect placeholder");

        try (Problems problems = new Problems()) {
            withConfig(e2e, CONFIG, Map.of("cooldown: 3s", "cooldown: 10m"), x -> {
                e2e.step("a kill plays the effect where the victim fell, for both players");
                long[] before = effectCounts(e2e);
                killer.clearParticles();
                victim.clearParticles();
                killWithHit(e2e, killer, victim);
                e2e.eventually(() -> killer.particles("minecraft:heart") > 0 && victim.particles("minecraft:heart") > 0,
                    "hearts for both: " + killer.particleTypes() + " / " + victim.particleTypes());
                long[] after = effectCounts(e2e);
                e2e.expect(after[0] == before[0] + 1, "one effect played: " + after[0] + " after " + before[0]);
                afterDeath(e2e, victim);

                e2e.step("a second kill within the cooldown plays nothing");
                killer.clearParticles();
                killWithHit(e2e, killer, victim);
                e2e.sleep(1_500);
                e2e.expect(killer.particles("minecraft:heart") == 0, "no hearts: " + killer.particleTypes());
                long[] limited = effectCounts(e2e);
                e2e.expect(limited[1] == after[1] + 1 && limited[0] == after[0], "held back by the limit: " + limited[0] + "/" + limited[1]);
                afterDeath(e2e, victim);
            });

            e2e.step("the lightning effect strikes a visual-only bolt");
            command(e2e, killer, "killeffect lightning");
            expectSaw(e2e, killer, "You can't change cosmetics in combat");
            e2e.consoleOutput("combat untag " + killerName);
            command(e2e, killer, "killeffect lightning");
            expectSaw(e2e, killer, "Your kill effect is now Lightning");
            e2e.sleep(1_600);
            killer.clearParticles();
            victim.clearParticles();
            killWithHit(e2e, killer, victim);
            e2e.eventually(() -> killer.sawEntityType("minecraft:lightning_bolt"), "the bolt: " + killer.particleTypes());
            e2e.eventually(() -> killer.particles("minecraft:electric_spark") > 0, "sparks: " + killer.particleTypes());
            afterDeath(e2e, victim);

            e2e.step("a victim who turned kill effects off sees none, and the bolt holds off");
            e2e.services().settings().set(e2e.uuid(victimName), effects, false);
            e2e.sleep(3_200);
            killer.clearParticles();
            victim.clearParticles();
            killWithHit(e2e, killer, victim);
            e2e.eventually(() -> killer.particles("minecraft:electric_spark") > 0, "the killer still sees sparks: " + killer.particleTypes());
            e2e.sleep(1_000);
            e2e.expect(victim.particles("minecraft:electric_spark") == 0, "the victim sees none: " + victim.particleTypes());
            e2e.expect(!killer.sawEntityType("minecraft:lightning_bolt"), "no bolt while someone nearby turned effects off");
            e2e.services().settings().set(e2e.uuid(victimName), effects, true);
            afterDeath(e2e, victim);

            // Bots ask for a view distance of 2 chunks, so they track entities up to 32 blocks away (real clients see
            // the bolt up to 64): with the effect range at its minimum of 8, a bot 20 blocks away is out of the
            // range but can see the bolt, like a real player 50 blocks away with the shipped range of 32.
            String farName = e2e.name("EffFar");
            Bot far = e2e.bot(farName);
            withConfig(e2e, CONFIG, Map.of("range: 32", "range: 8"), x -> {
                e2e.step("a player out of the effect range but within sight who turned kill effects off holds the bolt off too");
                standApart(e2e, far, killerName, 20);
                e2e.services().settings().set(e2e.uuid(farName), effects, false);
                e2e.sleep(3_200);
                killer.clearParticles();
                far.clearParticles();
                killWithHit(e2e, killer, victim);
                e2e.eventually(() -> killer.particles("minecraft:electric_spark") > 0, "the killer sees sparks: " + killer.particleTypes());
                e2e.sleep(1_500);
                e2e.expect(!killer.sawEntityType("minecraft:lightning_bolt"), "no bolt for anyone while a player in sight turned effects off");
                e2e.expect(!far.sawEntityType("minecraft:lightning_bolt"), "the opted-out player out of range gets no bolt");
                afterDeath(e2e, victim);

                e2e.step("with that player's effects on the bolt strikes and reaches them, while the sparks stay within the range");
                e2e.services().settings().set(e2e.uuid(farName), effects, true);
                standApart(e2e, far, killerName, 20);
                killer.clearParticles();
                far.clearParticles();
                killWithHit(e2e, killer, victim);
                e2e.eventually(() -> killer.sawEntityType("minecraft:lightning_bolt"), "the killer sees the bolt: " + killer.particleTypes());
                e2e.eventually(() -> far.sawEntityType("minecraft:lightning_bolt"), "the bolt reaches a client out of the effect range");
                e2e.expect(far.particles("minecraft:electric_spark") == 0, "particles stay within the effect range: " + far.particleTypes());
                afterDeath(e2e, victim);
            });
            far.quit();

            e2e.step("never in the protected spawn");
            var area = e2e.spawnArea();
            if (area != null) {
                Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
                e2e.expect(area.contains(spawn), "the world spawn is protected");
                long[] before = effectCounts(e2e);
                e2e.sleep(3_200);
                cosmetics(e2e).kill(e2e.player(killerName), e2e.player(victimName), spawn);
                e2e.sleep(500);
                long[] after = effectCounts(e2e);
                e2e.expect(after[0] == before[0] && after[1] == before[1], "nothing played or counted at spawn");
            }
            e2e.expect(problems.seen.isEmpty(), "no warnings or errors while effects played: " + problems.seen);
        }

        e2e.step("with kill effects turned off in the config the placeholder is empty too");
        withConfig(e2e, CONFIG, Map.of("don't see them.\n  enabled: true", "don't see them.\n  enabled: false"), x ->
            e2e.expect(placeholder(e2e, killerName, "kill_effect").isEmpty(), "kill_effect: " + placeholder(e2e, killerName, "kill_effect")));
        e2e.expect("lightning".equals(placeholder(e2e, killerName, "kill_effect")), "back on: " + placeholder(e2e, killerName, "kill_effect"));

        e2e.step("an expired rank plays no effect");
        revoke(e2e, killerName, tycoon);
        e2e.expect(placeholder(e2e, killerName, "kill_effect").isEmpty(), "no effect shown");
        e2e.sleep(3_200);
        long[] before = effectCounts(e2e);
        killer.clearParticles();
        killWithHit(e2e, killer, victim);
        e2e.sleep(1_500);
        e2e.expect(effectCounts(e2e)[0] == before[0] && !killer.sawEntityType("minecraft:lightning_bolt"), "nothing played");
    }

    // ------------------------------------------------------------------ menu, combat and settings

    static void menu(E2E e2e) {
        String name = e2e.name("Browser");
        Bot bot = e2e.bot(name);

        e2e.step("the menu lists every perk and what the player has");
        e2e.expect(e2e.services().hub().get("cosmetics") != null, "a main menu entry");
        bot.clearLogs();
        bot.command("cosmetics");
        Bot.SeenDialog menu = e2e.dialog(bot, "Cosmetics");
        for (String entry : List.of("Chat colour", "Nickname", "Chat tag", "Join message", "Kill effect")) {
            e2e.expect(menu.button(entry) != null, "a '" + entry + "' button: " + menu.buttons());
            e2e.expect(menu.bodyText().contains(entry), "a line for " + entry + ": " + menu.bodyText());
        }
        e2e.click(bot, "Chat colour");
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().bodyText().contains("That comes with the Baron rank"),
            "a locked perk says which rank: " + (bot.dialog() == null ? "none" : bot.dialog().bodyText()));
        e2e.click(bot, "Chat tag");
        e2e.dialog(bot, "Chat tags");

        e2e.step("every perk is refused in combat");
        List<String> tagged = e2e.consoleOutput("combat tag " + name + " 30s");
        e2e.expect(String.join(" ", tagged).contains("now in combat"), "tagged: " + tagged);
        command(e2e, bot, "tags miner");
        expectSaw(e2e, bot, "You can't change cosmetics in combat");
        command(e2e, bot, "cosmetics");
        expectSaw(e2e, bot, "You can't change cosmetics in combat");
        command(e2e, bot, "killeffect off");
        expectSaw(e2e, bot, "You can't change cosmetics in combat");
        e2e.consoleOutput("combat untag " + name);

        e2e.step("the two viewer switches sit in the Chat and Display groups of the settings");
        Toggle colours = e2e.services().settings().toggle("show-chat-colors");
        Toggle effects = e2e.services().settings().toggle("show-kill-effects");
        SettingCategory chat = e2e.services().settings().category(colours);
        SettingCategory display = e2e.services().settings().category(effects);
        e2e.expect(chat != null && chat.id().equals("chat"), "chat colours under Chat: " + chat);
        e2e.expect(display != null && display.id().equals("display"), "kill effects under Display: " + display);
        e2e.expect(colours.defaultOn() && effects.defaultOn(), "both on by default");
    }
}
