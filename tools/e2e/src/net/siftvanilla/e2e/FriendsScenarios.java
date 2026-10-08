package net.siftvanilla.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.feature.friends.FriendsFeature;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * End-to-end scenarios of the friends system: requests and their answers, hidden requests, limits, presence alerts and
 * the login summary, staff tools, the dialogs, profile buttons running other features' commands, and data surviving
 * memory eviction. Every scenario runs with test timings (no account age, fast alerts) written into the server's
 * {@code features/friends.yml} and restores the file afterwards.
 */
final class FriendsScenarios {

    private FriendsScenarios() {
    }

    private record Named(String name, Body body, Map<String, String> config) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            withConfig(e2e, this.config, () -> this.body.run(e2e));
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(E2E e2e) throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** Test timings: fresh bots may send requests, all bots share one address, alerts come within seconds. */
    private static final Map<String, String> FAST = Map.of(
        "min-account-age", "0s",
        "per-minute", "1000",
        "startup-quiet", "0s",
        "summary-delay", "1s",
        "join-delay", "1s",
        "leave-delay", "3s",
        "request-batch", "3s",
        "grace", "2s");

    private static Scenario of(String name, Body body) {
        return new Named(name, body, FAST);
    }

    private static Scenario of(String name, Body body, Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>(FAST);
        config.putAll(extra);
        return new Named(name, body, config);
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("friends-request", FriendsScenarios::request));
        list.add(of("friends-mutual", FriendsScenarios::mutual));
        list.add(of("friends-deny-silent", FriendsScenarios::denySilent));
        list.add(of("friends-ignore-shadow", FriendsScenarios::hiddenRequests, Map.of("max-incoming", "1")));
        list.add(of("friends-limits", FriendsScenarios::limits, Map.of("default", "1", "favourites", "1")));
        list.add(of("friends-remove", FriendsScenarios::remove));
        list.add(of("friends-double-accept", FriendsScenarios::doubleAccept));
        list.add(of("friends-presence", FriendsScenarios::presence, Map.of("relog-grace", "5s", "join-delay", "3s")));
        list.add(of("friends-offline-accept-summary", FriendsScenarios::offlineAcceptSummary));
        list.add(of("friends-staff", FriendsScenarios::staff));
        list.add(of("friends-menu", FriendsScenarios::menu, Map.of("page-size", "4")));
        list.add(of("friends-profile-actions", FriendsScenarios::profileActions));
        list.add(of("friends-restart", FriendsScenarios::restart));
        list.add(of("friends-request-alerts", FriendsScenarios::requestAlerts));
        list.add(of("friends-suggestions", FriendsScenarios::suggestions));
        list.add(of("friends-links", FriendsScenarios::links));
        list.add(of("friends-second-login", FriendsScenarios::secondLogin));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** Writes the test values into features/friends.yml, reloads, runs, and puts the file back. */
    private static void withConfig(E2E e2e, Map<String, String> values, Action action) throws Exception {
        Path file = e2e.services().plugin().getDataFolder().toPath().resolve("features/friends.yml");
        String original = Files.readString(file);
        String changed = original;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Matcher matcher = Pattern.compile("(?m)^(\\s*" + Pattern.quote(entry.getKey()) + ":)\\s*\\S+").matcher(changed);
            e2e.expect(matcher.find(), "features/friends.yml has " + entry.getKey());
            changed = matcher.replaceFirst("$1 " + Matcher.quoteReplacement(entry.getValue()));
        }
        Files.writeString(file, changed);
        e2e.console("sift reload");
        try {
            action.run();
        } finally {
            Files.writeString(file, original);
            e2e.console("sift reload");
        }
    }

    /** Whether the chat line (or a part of it) runs this command when clicked. */
    private static boolean runsCommand(net.minecraft.network.chat.Component line, String command) {
        if (line.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run
            && run.command().equals(command)) {
            return true;
        }
        for (net.minecraft.network.chat.Component part : line.getSiblings()) {
            if (runsCommand(part, command)) {
                return true;
            }
        }
        return false;
    }

    private static FriendLookup lookup(E2E e2e) {
        return e2e.feature(FriendsFeature.class).lookup();
    }

    private static String placeholder(E2E e2e, String player, String name) {
        String value = e2e.services().placeholders().resolve(e2e.player(player), name);
        return value == null ? "" : value;
    }

    /** Whether the player is in combat (the combat feature's placeholder). */
    private static boolean lookupTagged(E2E e2e, String player) {
        return "true".equals(placeholder(e2e, player, "combat_tagged"));
    }

    /** Waits until the friends of the player are loaded (the limit placeholder answers 0 until then). */
    private static void loaded(E2E e2e, String player) {
        e2e.eventually(() -> !"0".equals(placeholder(e2e, player, "friends_limit")), player + "'s friends are loaded");
    }

    private static Bot join(E2E e2e, String name) {
        Bot bot = e2e.bot(name);
        loaded(e2e, name);
        return bot;
    }

    /**
     * Runs a {@code /sift friends} command as a console-like sender and waits for its answer: the staff tools reply
     * after the database answered, so the reply can come well after the command returned on a busy server.
     */
    private static List<String> staff(E2E e2e, String command, Predicate<List<String>> answered) {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> sent = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(e2e.services().plugin(), () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message ->
                    lines.add(PlainTextComponentSerializer.plainText().serialize(message))), command);
                sent.complete(null);
            } catch (Throwable t) {
                sent.completeExceptionally(t);
            }
        });
        try {
            sent.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("'" + command + "' failed: " + e);
        }
        e2e.eventually(() -> answered.test(List.copyOf(lines)), "'" + command + "' answers: " + lines);
        e2e.sleep(150);
        return List.copyOf(lines);
    }

    /** {@link #staff(E2E, String, Predicate)} that waits for any answer. */
    private static List<String> staff(E2E e2e, String command) {
        return staff(e2e, command, lines -> !lines.isEmpty());
    }

    /** Makes two players friends with the staff command and waits for memory. */
    private static void befriend(E2E e2e, String a, String b) {
        staff(e2e, "sift friends add " + a + " " + b, out -> out.stream().anyMatch(line -> line.contains("Made " + a + " and " + b + " friends")));
        UUID ida = e2e.services().directory().uuid(a).orElseThrow();
        UUID idb = e2e.services().directory().uuid(b).orElseThrow();
        e2e.eventually(() -> lookup(e2e).friends(ida, idb), a + " and " + b + " are friends");
    }

    private static Bot.SeenDialog open(E2E e2e, Bot bot, String command, String title) {
        bot.clearLogs();
        bot.command(command);
        return e2e.dialog(bot, title);
    }

    private static void quit(E2E e2e, Bot bot) {
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(bot.name) == null, bot.name + " left");
    }

    private static int historyCount(E2E e2e, String player, String action) {
        List<String> out = staff(e2e, "sift friends history " + player);
        int count = 0;
        for (String line : out) {
            if (line.contains(" " + action + " ")) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ scenarios

    /** A request with its chat alert, the requests dialogs, accepting, and the chat list. */
    static void request(E2E e2e) {
        String alex = e2e.name("FrAlex");
        String bea = e2e.name("FrBea");
        Bot a = join(e2e, alex);
        Bot b = join(e2e, bea);
        UUID ida = e2e.uuid(alex);
        UUID idb = e2e.uuid(bea);

        e2e.step("/friend <name> sends a request; the target gets one chat line naming the command");
        a.clearLogs();
        b.clearLogs();
        a.command("friend " + bea);
        e2e.eventually(() -> a.actionBarContains("Friend request sent to " + bea + "."), "sent: " + a.actionBar());
        e2e.eventually(() -> b.chatContains(alex + " sent you a friend request. Accept or Deny, or type /friend requests."),
            "the alert: " + b.chat());
        e2e.expect("1".equals(placeholder(e2e, bea, "friends_requests")), "friends_requests placeholder");
        a.clearLogs();
        a.command("friend add " + bea);
        e2e.eventually(() -> a.actionBarContains("You already sent " + bea + " a request."), "already sent: " + a.actionBar());

        e2e.step("the requests dialogs show it on both sides");
        Bot.SeenDialog sent = open(e2e, a, "friend requests", "Friend requests");
        e2e.expect(sent.bodyText().contains("Incoming: 0. Sent: 1."), "sender body: " + sent.body());
        e2e.expect(sent.button("Cancel: " + bea) != null, "a cancel row: " + sent.buttons());
        Bot.SeenDialog incoming = open(e2e, b, "friend requests", "Friend requests");
        e2e.expect(incoming.bodyText().contains("Incoming: 1. Sent: 0."), "target body: " + incoming.body());
        e2e.click(b, alex + ",");
        Bot.SeenDialog one = e2e.dialog(b, "Friend request");
        e2e.expect(one.bodyText().contains(alex + " wants to be friends.") && one.bodyText().contains("Mutual friends: 0")
            && one.bodyText().contains("ago"), "the request: " + one.body());
        e2e.expect(one.button("Accept") != null && one.button("Deny") != null && one.button("Back") != null, "buttons: " + one.buttons());
        e2e.expect(one.button("Deny and ignore") != null, "chat's ignore list adds Deny and ignore: " + one.buttons());

        e2e.step("Accept makes them friends and tells the requester in chat");
        a.clearLogs();
        e2e.click(b, "Accept");
        e2e.dialog(b, "Friend requests");
        e2e.eventually(() -> lookup(e2e).friends(ida, idb), "friends in memory");
        e2e.eventually(() -> a.chatContains(bea + " accepted your friend request."), "the requester is told: " + a.chat());
        e2e.eventually(() -> b.chatContains("You and " + alex + " are now friends."), "the accepter: " + b.chat());
        e2e.expect("1".equals(placeholder(e2e, alex, "friends_count")) && "1".equals(placeholder(e2e, bea, "friends_count")),
            "friends_count");
        e2e.expect("1".equals(placeholder(e2e, alex, "friends_online")), "friends_online");
        e2e.expect("0".equals(placeholder(e2e, bea, "friends_requests")), "no request left");
        e2e.expect("50".equals(placeholder(e2e, alex, "friends_limit")), "friends_limit");

        e2e.step("/friend <friend> opens the profile; /friend list prints the list in chat");
        Bot.SeenDialog profile = open(e2e, a, "friend " + bea, bea);
        e2e.expect(profile.bodyText().contains("Online") && profile.bodyText().contains("Friends since"), "profile: " + profile.body());
        a.clearLogs();
        a.command("friend list");
        e2e.eventually(() -> a.chatContains("Friends: 1, online: 1. Page 1 of 1.") && a.chatContains(bea + " online"),
            "chat list: " + a.chat());

        e2e.step("/friend accept without a name with nothing waiting says so");
        b.clearLogs();
        b.command("friend accept");
        e2e.eventually(() -> b.actionBarContains("No friend requests right now."), "nothing waiting: " + b.actionBar());

        e2e.step("bad names are refused before any lookup");
        a.clearLogs();
        a.command("friend add " + alex);
        e2e.eventually(() -> a.actionBarContains("You can't add yourself."), "self: " + a.actionBar());
        a.clearLogs();
        a.command("friend add Nobody_" + e2e.name("X"));
        e2e.eventually(() -> a.actionBarContains("has played here"), "unknown: " + a.actionBar());
    }

    /**
     * {@code /friend add} on someone whose request you can see accepts it; asking someone who asked you while their
     * request is hidden from you (you denied it) makes you friends at once, and both are told.
     */
    static void mutual(E2E e2e) {
        String cleo = e2e.name("FrCleo");
        String dan = e2e.name("FrDan");
        String mia = e2e.name("FrMia");
        Bot c = join(e2e, cleo);
        Bot d = join(e2e, dan);
        Bot m = join(e2e, mia);

        e2e.step("the smart add accepts a request you can see");
        c.command("friend " + dan);
        e2e.eventually(() -> d.chatContains(cleo + " sent you a friend request"), "dan was asked");
        c.clearLogs();
        d.clearLogs();
        d.command("friend add " + cleo);
        e2e.eventually(() -> lookup(e2e).friends(e2e.uuid(cleo), e2e.uuid(dan)), "friends");
        e2e.eventually(() -> c.chatContains(dan + " accepted your friend request.") && d.chatContains("You and " + cleo + " are now friends."),
            "an accept: " + c.chat() + " / " + d.chat());
        e2e.eventually(() -> historyCount(e2e, dan, "accept") == 1, "logged as an accept");

        e2e.step("asking back after denying is mutual: both are told they are friends now");
        m.command("friend " + cleo);
        e2e.eventually(() -> "1".equals(placeholder(e2e, cleo, "friends_requests")), "cleo has mia's request");
        c.command("friend deny " + mia);
        e2e.eventually(() -> "0".equals(placeholder(e2e, cleo, "friends_requests")), "denied");
        c.clearLogs();
        m.clearLogs();
        c.command("friend " + mia);
        e2e.eventually(() -> lookup(e2e).friends(e2e.uuid(cleo), e2e.uuid(mia)), "friends at once");
        e2e.eventually(() -> c.chatContains("You and " + mia + " are now friends.") && m.chatContains("You and " + cleo + " are now friends."),
            "both told: " + c.chat() + " / " + m.chat());
        e2e.eventually(() -> historyCount(e2e, cleo, "mutual") == 1, "logged as mutual");
        e2e.expect("0".equals(placeholder(e2e, cleo, "friends_requests")) && "0".equals(placeholder(e2e, mia, "friends_requests")),
            "no requests left");
    }

    /** A deny is never told; the sender sees no difference; the deny is remembered and new requests stay hidden. */
    static void denySilent(E2E e2e) {
        String eve = e2e.name("FrEve");
        String finn = e2e.name("FrFinn");
        Bot e = join(e2e, eve);
        Bot f = join(e2e, finn);
        e.command("friend " + finn);
        e2e.eventually(() -> f.chatContains(eve + " sent you a friend request"), "finn was asked");

        e2e.step("deny: the target is told, the sender is not");
        e.clearLogs();
        f.clearLogs();
        f.command("friend deny " + eve);
        e2e.eventually(() -> f.actionBarContains("Request from " + eve + " denied."), "deny feedback: " + f.actionBar());
        e2e.sleep(1_000);
        e2e.expect(e.chat().isEmpty() && e.actionBar().isEmpty(), "the sender heard nothing: " + e.chat() + e.actionBar());
        e.command("friend " + finn);
        e2e.eventually(() -> e.actionBarContains("You already sent " + finn + " a request."), "still looks waiting: " + e.actionBar());
        Bot.SeenDialog mine = open(e2e, e, "friend requests", "Friend requests");
        e2e.expect(mine.bodyText().contains("Sent: 1."), "the sender still sees it: " + mine.body());
        Bot.SeenDialog theirs = open(e2e, f, "friend requests", "Friend requests");
        e2e.expect(theirs.bodyText().contains("Incoming: 0."), "the target does not: " + theirs.body());

        e2e.step("cancel and ask again: sent as far as the sender knows, hidden from the target");
        e.clearLogs();
        e.command("friend cancel " + finn);
        e2e.eventually(() -> e.actionBarContains("Request to " + finn + " cancelled."), "cancelled: " + e.actionBar());
        f.clearLogs();
        e.clearLogs();
        e.command("friend " + finn);
        e2e.eventually(() -> e.actionBarContains("Friend request sent to " + finn + "."), "reads as sent: " + e.actionBar());
        e2e.sleep(1_500);
        e2e.expect(!f.chatContains(eve), "no alert during the deny memory: " + f.chat());
        e2e.expect("0".equals(placeholder(e2e, finn, "friends_requests")), "not counted for the target");
        List<String> rows = staff(e2e, "sift friends requests " + finn);
        e2e.expect(rows.stream().anyMatch(line -> line.contains(eve + " to " + finn + ": shadow") && line.contains("denied")),
            "staff see the truth: " + rows);
        f.clearLogs();
        f.command("friend accept " + eve);
        e2e.eventually(() -> f.actionBarContains("No request from " + eve + "."), "a hidden request can't be accepted: " + f.actionBar());
    }

    /** Requests past the target's visible maximum are hidden the same way ignored senders are: sent for the sender, unseen. */
    static void hiddenRequests(E2E e2e) {
        String gus = e2e.name("FrGus");
        String hal = e2e.name("FrHal");
        String ivy = e2e.name("FrIvy");
        Bot g = join(e2e, gus);
        Bot h = join(e2e, hal);
        Bot i = join(e2e, ivy);
        g.command("friend " + ivy);
        e2e.eventually(() -> i.chatContains(gus + " sent you a friend request"), "the first one is seen");
        i.clearLogs();
        h.clearLogs();
        h.command("friend " + ivy);
        e2e.eventually(() -> h.actionBarContains("Friend request sent to " + ivy + "."), "the sender reads sent: " + h.actionBar());
        e2e.sleep(1_500);
        e2e.expect(!i.chatContains(hal), "the hidden request was not told: " + i.chat());
        e2e.expect("1".equals(placeholder(e2e, ivy, "friends_requests")), "only the visible one counts");
        Bot.SeenDialog sent = open(e2e, h, "friend requests", "Friend requests");
        e2e.expect(sent.button("Cancel: " + ivy) != null, "the sender sees it waiting: " + sent.buttons());
        e2e.expect(staff(e2e, "sift friends requests " + ivy).stream().anyMatch(line -> line.contains(hal + " to " + ivy + ": shadow")),
            "stored hidden");
        e2e.log("IgnoreLookup is NONE until an ignore list exists; ignore hiding uses this same path (unit-tested)");
    }

    /** Friend limits, favourites, privacy, account age and the rate buckets. */
    static void limits(E2E e2e) throws Exception {
        String jo = e2e.name("FrJo");
        String kai = e2e.name("FrKai");
        String lu = e2e.name("FrLu");
        String max = e2e.name("FrMax");
        Bot j = join(e2e, jo);
        Bot k = join(e2e, kai);
        join(e2e, lu);
        Bot m = join(e2e, max);

        e2e.step("a full list (default limit 1 in this test) refuses both ways and says ranks raise it");
        befriend(e2e, jo, lu);
        j.clearLogs();
        j.command("friend " + kai);
        e2e.eventually(() -> j.actionBarContains("Your friend list is full (1). Ranks raise this limit."), "sender full: " + j.actionBar());
        k.clearLogs();
        k.command("friend " + jo);
        e2e.eventually(() -> k.actionBarContains(jo + "'s friend list is full."), "target full: " + k.actionBar());
        Bot.SeenDialog list = open(e2e, j, "friend", "Friends");
        e2e.expect(list.bodyText().contains("1 of 1 friends.") && list.bodyText().contains("Ranks raise this limit."), "list: " + list.body());

        e2e.step("favourites are capped (1 in this test)");
        befriend(e2e, kai, lu);
        befriend(e2e, kai, max);
        k.clearLogs();
        k.command("friend fav " + lu);
        e2e.eventually(() -> k.actionBarContains(lu + " is a favourite now."), "first favourite: " + k.actionBar());
        k.command("friend favourite " + max);
        e2e.eventually(() -> k.actionBarContains("Your favourites are full (1)."), "the cap: " + k.actionBar());

        e2e.step("limits.favourites 0 turns favourites off: no button, no option, a clear refusal");
        withConfig(e2e, Map.of("favourites", "0"), () -> {
            Bot.SeenDialog noFav = open(e2e, k, "profile " + max, max);
            e2e.expect(noFav.button("Favourite") == null && noFav.button("Unfavourite") == null && noFav.button("Edit note") != null,
                "no favourite button: " + noFav.buttons());
            k.clearLogs();
            k.command("friend fav " + max);
            e2e.eventually(() -> k.actionBarContains("Favourites are turned off on this server."), "favourites off: " + k.actionBar());
            k.clearLogs();
            k.command("friend settings join-alerts favourites");
            e2e.eventually(() -> k.chatContains("Use one of these for join-alerts: all, off."), "no favourites choice: " + k.chat());
            open(e2e, k, "friend settings", "Friend settings");
            e2e.click(k, "Save", Map.of("requests", "everyone", "join_alerts", "favourites", "leave_alerts", "false",
                "request_alerts", "true", "announce", "true"));
            Bot.SeenDialog refused = e2e.dialog(k, "Friend settings");
            e2e.expect(!refused.body().isEmpty() && refused.bodyText().toLowerCase().contains("join alerts"),
                "the form has no favourites option: " + refused.body());
        });

        String nia = e2e.name("FrNia");
        Bot n = join(e2e, nia);
        withConfig(e2e, Map.of("default", "50"), () -> {
            e2e.step("privacy nobody refuses honestly");
            m.clearLogs();
            m.command("friend settings requests nobody");
            e2e.eventually(() -> m.actionBarContains("Set requests to nobody."), "privacy set");
            n.clearLogs();
            n.command("friend " + max);
            e2e.eventually(() -> n.actionBarContains(max + " isn't taking friend requests from you."), "private");
            n.clearLogs();
            n.command("friend settings requests sideways");
            e2e.eventually(() -> n.chatContains("Use one of these for requests: everyone, known, nobody."), "bad value: " + n.chat());
            n.clearLogs();
            n.command("friend settings alerts on");
            e2e.eventually(() -> n.chatContains("Unknown setting. Use one of: requests, join-alerts, leave-alerts, request-alerts, announce."),
                "unknown key, in chat: " + n.chat());
        });

        e2e.step("new accounts wait, and the per-minute bucket slows bursts down");
        withConfig(e2e, Map.of("min-account-age", "10m"), () -> {
            n.clearLogs();
            n.command("friend " + jo);
            e2e.eventually(() -> n.actionBarContains("You can send friend requests in"), "account age");
        });
        withConfig(e2e, Map.of("per-minute", "3", "default", "50"), () -> {
            // All bots share one address, so the address bucket already holds this test's earlier requests: five
            // quick requests against three a minute must be slowed down at least twice, whatever came before.
            String oz = e2e.name("FrOz");
            join(e2e, oz);
            n.clearLogs();
            for (String target : List.of(jo, kai, lu, max, oz)) {
                n.command("friend " + target);
            }
            e2e.eventually(() -> n.actionBar().stream().filter(line -> line.contains("Slow down. Try again in")).count() >= 2,
                "rate limited");
        });
    }

    /** Removing asks first, ends the friendship on both sides and tells nobody else. */
    static void remove(E2E e2e) {
        String pia = e2e.name("FrPia");
        String quin = e2e.name("FrQuin");
        Bot p = join(e2e, pia);
        Bot q = join(e2e, quin);
        befriend(e2e, pia, quin);
        p.clearLogs();
        q.clearLogs();
        p.command("friend remove " + quin);
        Bot.SeenDialog confirm = e2e.dialog(p, "Remove friend");
        e2e.expect(confirm.bodyText().contains("Remove " + quin + "? They won't be told."), "confirmation: " + confirm.body());
        e2e.expect(lookup(e2e).friends(e2e.uuid(pia), e2e.uuid(quin)), "nothing changed before confirming");
        e2e.click(p, "Remove");
        e2e.dialog(p, "Friends");
        e2e.eventually(() -> !lookup(e2e).friends(e2e.uuid(pia), e2e.uuid(quin)), "removed");
        e2e.eventually(() -> p.actionBarContains(quin + " is no longer your friend."), "feedback: " + p.actionBar());
        e2e.expect(q.chat().isEmpty(), "the other side is never told: " + q.chat());
        e2e.expect("0".equals(placeholder(e2e, quin, "friends_count")), "both sides");
        e2e.expect(lookup(e2e).recentlyFriends(e2e.uuid(pia), e2e.uuid(quin), java.time.Duration.ofHours(24)),
            "remembered for anti-farm");
        p.clearLogs();
        p.command("friend remove " + quin);
        e2e.eventually(() -> p.actionBarContains(quin + " isn't on your friend list."), "not friends any more: " + p.actionBar());
    }

    /** Burst clicks and commands make exactly one friendship. */
    static void doubleAccept(E2E e2e) {
        String rex = e2e.name("FrRex");
        String sol = e2e.name("FrSol");
        Bot r = join(e2e, rex);
        Bot s = join(e2e, sol);
        r.command("friend " + sol);
        e2e.eventually(() -> "1".equals(placeholder(e2e, sol, "friends_requests")), "sol has the request");
        open(e2e, s, "friend requests", "Friend requests");
        e2e.click(s, rex + ",");
        e2e.dialog(s, "Friend request");
        s.clickButton("Accept", Map.of());
        s.clickButton("Accept", Map.of());
        s.clickButton("Accept", Map.of());
        for (int i = 0; i < 5; i++) {
            s.command("friend accept " + rex);
        }
        e2e.eventually(() -> lookup(e2e).friends(e2e.uuid(rex), e2e.uuid(sol)), "friends");
        e2e.sleep(1_500);
        e2e.expect(historyCount(e2e, sol, "accept") == 1, "one accept in the history");
        e2e.expect("1".equals(placeholder(e2e, rex, "friends_count")), "one friend");
        long told = r.chat().stream().filter(line -> line.contains(sol + " accepted your friend request.")).count();
        e2e.expect(told == 1, "told once: " + r.chat());

        e2e.step("the self-test agrees while both are online: storage is consistent and memory matches it");
        List<String> selftest = staff(e2e, "sift selftest", lines -> lines.stream().anyMatch(line -> line.matches("\\d+ passed.*")));
        List<String> checks = selftest.stream().filter(line -> line.startsWith("pass friends") || line.startsWith("fail friends")).toList();
        e2e.expect(checks.size() == 7 && checks.stream().allMatch(line -> line.startsWith("pass")), "the friends checks: " + checks);
    }

    /** Join alerts (batched, favourites with sound), relog grace, leave alerts, vanish and the login summary. */
    static void presence(E2E e2e) {
        String tia = e2e.name("FrTia");
        String uma = e2e.name("FrUma");
        String vic = e2e.name("FrVic");
        String wes = e2e.name("FrWes");
        Bot t = join(e2e, tia);
        Bot u = join(e2e, uma);
        Bot v = join(e2e, vic);
        Bot w = join(e2e, wes);
        befriend(e2e, tia, uma);
        befriend(e2e, tia, vic);
        befriend(e2e, tia, wes);
        quit(e2e, u);
        quit(e2e, v);
        quit(e2e, w);
        e2e.sleep(6_000);

        e2e.step("a friend coming online is one gray chat line with a clickable name");
        t.clearLogs();
        Bot u2 = join(e2e, uma);
        e2e.eventually(() -> t.chatContains(uma + " is online."), 12_000, "join alert: " + t.chat());
        e2e.eventually(() -> u2.chatContains("Friends online: " + tia + "."), "the joiner's summary: " + u2.chat());

        e2e.step("two friends joining within the window are told in one line");
        t.clearLogs();
        join(e2e, vic);
        Bot w2 = join(e2e, wes);
        e2e.eventually(() -> t.chatContains(vic + " and " + wes + " are online.") || t.chatContains(wes + " and " + vic + " are online."),
            12_000, "batched: " + t.chat());

        e2e.step("a quick relog is not announced");
        t.clearLogs();
        quit(e2e, u2);
        Bot u3 = join(e2e, uma);
        e2e.sleep(7_000);
        e2e.expect(!t.chatContains(uma + " is online."), "relog grace: " + t.chat());

        e2e.step("leave alerts when turned on, dropped when the friend comes back in time");
        t.command("friend settings leave-alerts on");
        e2e.eventually(() -> t.actionBarContains("Set leave-alerts to on."), "leave alerts on: " + t.actionBar());
        t.clearLogs();
        quit(e2e, u3);
        e2e.eventually(() -> t.chatContains(uma + " went offline."), 8_000, "leave alert: " + t.chat());
        e2e.expect(t.chatComponents().stream().anyMatch(line -> line.getString().contains(uma + " went offline.")
                && runsCommand(line, "/profile " + uma)),
            "the leaving friend's name opens their profile like the join alert's: " + t.chat());
        e2e.sleep(6_000);
        Bot u4 = join(e2e, uma);
        t.clearLogs();
        quit(e2e, u4);
        Bot u5 = join(e2e, uma);
        e2e.sleep(5_000);
        e2e.expect(!t.chatContains("went offline"), "back within the leave delay: " + t.chat());

        e2e.step("a vanished friend looks offline and is never announced");
        e2e.console("op " + uma);
        u5.command("vanish");
        e2e.eventually(() -> u5.anyFeedbackContains("You are vanished"), "vanished: " + u5.actionBar() + u5.chat());

        e2e.step("a vanished player can't accept a request: the requester would learn they are online");
        w2.clearLogs();
        w2.command("friend " + uma);
        e2e.eventually(() -> w2.actionBarContains("Friend request sent to " + uma + "."), "a request to the vanished player");
        u5.clearLogs();
        u5.command("friend accept " + wes);
        e2e.eventually(() -> u5.actionBarContains("You can't accept friend requests while vanished."), "refused: " + u5.actionBar());
        u5.command("friend " + wes);
        e2e.sleep(1_000);
        e2e.expect(!lookup(e2e).friends(e2e.uuid(uma), e2e.uuid(wes)) && !w2.chatContains("accepted your friend request"),
            "the smart add's accept is refused the same way: " + w2.chat());
        e2e.eventually(() -> "2".equals(placeholder(e2e, tia, "friends_online")), 3_000,
            "friends_online leaves the vanished friend out: " + placeholder(e2e, tia, "friends_online"));
        Bot.SeenDialog list = open(e2e, t, "friend", "Friends");
        e2e.expect(list.button(uma + ", seen") != null, "listed as offline: " + list.buttons());
        t.clearLogs();
        quit(e2e, u5);
        e2e.sleep(6_000);
        e2e.expect(!t.chatContains(uma), "no leave alert for a vanished friend: " + t.chat());
        Bot u6 = join(e2e, uma);
        e2e.sleep(5_000);
        e2e.expect(!t.chatContains(uma + " is online"), "no join alert for a vanished friend: " + t.chat());
        u6.command("vanish");
        e2e.eventually(() -> u6.anyFeedbackContains("You are visible again"), "visible again");
        e2e.console("deop " + uma);
    }

    /** An accept while the requester is offline reaches them in the login summary, once. */
    static void offlineAcceptSummary(E2E e2e) {
        String xia = e2e.name("FrXia");
        String yan = e2e.name("FrYan");
        String zed = e2e.name("FrZed");
        Bot x = join(e2e, xia);
        Bot y = join(e2e, yan);
        Bot z = join(e2e, zed);
        x.command("friend " + yan);
        e2e.eventually(() -> "1".equals(placeholder(e2e, yan, "friends_requests")), "yan has the request");
        quit(e2e, x);
        y.clearLogs();
        y.command("friend accept");
        e2e.eventually(() -> y.chatContains("You and " + xia + " are now friends."), "the only request is accepted: " + y.chat());
        z.command("friend " + xia);
        e2e.eventually(() -> z.actionBarContains("Friend request sent to " + xia + "."), "a request while xia is away");

        e2e.step("the summary tells what happened while away");
        Bot back = join(e2e, xia);
        e2e.eventually(() -> back.chatContains("New friends while you were away: " + yan + "."), "new friends: " + back.chat());
        e2e.eventually(() -> back.chatContains("Friend requests waiting: 1. View, or type /friend requests."), "waiting: " + back.chat());
        e2e.eventually(() -> back.chatContains("Friends online: " + yan + "."), "online: " + back.chat());

        e2e.step("and only once");
        quit(e2e, back);
        Bot again = join(e2e, xia);
        e2e.eventually(() -> again.chatContains("Friends online:"), "summary again: " + again.chat());
        e2e.expect(!again.chatContains("New friends while you were away"), "the notice was used up: " + again.chat());
    }

    /** /sift friends from the console: list, requests, history, add, remove, clear-requests, with audit rows. */
    static void staff(E2E e2e) throws Exception {
        String ada = e2e.name("FrAda");
        String bo = e2e.name("FrBo");
        String cy = e2e.name("FrCy");
        Bot a = join(e2e, ada);
        Bot b = join(e2e, bo);
        Bot c = join(e2e, cy);

        e2e.step("add bypasses requests and tells both sides");
        a.clearLogs();
        b.clearLogs();
        befriend(e2e, ada, bo);
        e2e.eventually(() -> a.chatContains("You and " + bo + " are now friends.") && b.chatContains("You and " + ada + " are now friends."),
            "both told");
        List<String> again = staff(e2e, "sift friends add " + bo + " " + ada);
        e2e.expect(again.stream().anyMatch(line -> line.contains("already friends")), "already: " + again);
        List<String> list = staff(e2e, "sift friends list " + ada, out -> out.size() >= 2);
        e2e.expect(list.stream().anyMatch(line -> line.contains("Friends of " + ada + ": 1, limit 50."))
            && list.stream().anyMatch(line -> line.startsWith(bo + " since")), "list: " + list);

        e2e.step("the console's /friend points to the staff tools");
        List<String> consoleHelp = staff(e2e, "friend help", out -> out.size() >= 2);
        e2e.expect(consoleHelp.stream().anyMatch(line -> line.contains("/friend is for players. Staff tools: /sift friends."))
            && consoleHelp.stream().anyMatch(line -> line.startsWith("/sift friends list")), "console help: " + consoleHelp);

        e2e.step("an offline player's limit is the rank limit stored for them, as the write units check it");
        UUID cyId = e2e.uuid(cy);
        quit(e2e, c);
        e2e.sleep(4_000);
        e2e.services().database().write(conn -> {
            try (var delete = conn.prepareStatement("DELETE FROM friend_profiles WHERE uuid = ?")) {
                delete.setString(1, cyId.toString());
                delete.executeUpdate();
            }
            try (var insert = conn.prepareStatement(
                "INSERT INTO friend_profiles (uuid, friend_limit, rank_label, updated) VALUES (?, 75, NULL, ?)")) {
                insert.setString(1, cyId.toString());
                insert.setLong(2, System.currentTimeMillis());
                insert.executeUpdate();
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        List<String> offline = staff(e2e, "sift friends list " + cy, out -> out.size() >= 2);
        e2e.expect(offline.stream().anyMatch(line -> line.contains("Friends of " + cy + ": 0, limit 75.")), "stored limit: " + offline);
        join(e2e, cy);

        e2e.step("requests and history show every row");
        a.command("friend " + cy);
        e2e.eventually(() -> staff(e2e, "sift friends requests " + cy).stream()
            .anyMatch(line -> line.contains(ada + " to " + cy + ": pending")), "the pending row");
        List<String> history = staff(e2e, "sift friends history " + ada);
        e2e.expect(history.stream().anyMatch(line -> line.contains(" staff_add ")) && history.stream().anyMatch(line -> line.contains(" request ")),
            "history: " + history);

        e2e.step("clear-requests and remove");
        List<String> cleared = staff(e2e, "sift friends clear-requests " + cy);
        e2e.eventually(() -> "0".equals(placeholder(e2e, cy, "friends_requests")), "cleared: " + cleared);
        List<String> removed = staff(e2e, "sift friends remove " + ada + " " + bo);
        e2e.eventually(() -> !lookup(e2e).friends(e2e.uuid(ada), e2e.uuid(bo)), "removed: " + removed);
        List<String> none = staff(e2e, "sift friends remove " + ada + " " + bo);
        e2e.expect(none.stream().anyMatch(line -> line.contains("aren't friends")), "not friends: " + none);

        e2e.step("every staff change is in the audit log");
        AuditLog audit = e2e.services().audit();
        e2e.eventually(() -> {
            try {
                List<AuditLog.Entry> rows = audit.recent("friends.", null, 20).get();
                return rows.stream().anyMatch(r -> r.action().equals("friends.add") && r.target().equals(e2e.uuid(ada).toString()))
                    && rows.stream().anyMatch(r -> r.action().equals("friends.remove"))
                    && rows.stream().anyMatch(r -> r.action().equals("friends.clear-requests") && r.target().equals(e2e.uuid(cy).toString()));
            } catch (Exception ex) {
                return false;
            }
        }, "audit rows for add, remove and clear-requests");
    }

    /** The dialogs: from the menu with Back, add by name, requests, settings, Find. */
    static void menu(E2E e2e) {
        String dee = e2e.name("FrDee");
        String eli = e2e.name("FrEli");
        Bot d = join(e2e, dee);
        Bot e = join(e2e, eli);

        e2e.step("the main menu opens the list with Back; /friend opens it with Close");
        Bot.SeenDialog menu = open(e2e, d, "menu", "SiftVanilla");
        e2e.expect(menu.button("Friends") != null, "a Friends entry: " + menu.buttons());
        e2e.click(d, "Friends");
        Bot.SeenDialog list = e2e.dialog(d, "Friends");
        e2e.expect(list.bodyText().contains("0 of 0 online, 0 of 50 friends.") && list.bodyText().contains("No friends yet."),
            "empty list: " + list.body());
        e2e.expect(list.button("Back") != null && list.button("Add a friend") != null && list.button("Requests (0)") != null
            && list.button("Settings") != null, "footer: " + list.buttons());
        e2e.click(d, "Back");
        e2e.dialog(d, "SiftVanilla");
        Bot.SeenDialog direct = open(e2e, d, "friend", "Friends");
        e2e.expect(direct.button("Close") != null, "close from the command: " + direct.buttons());

        e2e.step("Add a friend: a name form that runs the request flow");
        int clearedBefore = d.dialogsCleared();
        e2e.click(d, "Add a friend");
        Bot.SeenDialog add = e2e.dialog(d, "Add a friend");
        e2e.expect(add.button("Enter a name") != null, "enter a name: " + add.buttons());
        e2e.click(d, "Enter a name");
        e2e.dialog(d, "Add a friend");
        e2e.click(d, "Send request", Map.of("name", "bad name!"));
        Bot.SeenDialog invalid = e2e.dialog(d, "Add a friend");
        e2e.expect(invalid.bodyText().contains("Names are 1 to 17 letters"), "invalid name: " + invalid.body());
        e.clearLogs();
        e2e.click(d, "Send request", Map.of("name", eli));
        e2e.eventually(() -> e.chatContains(dee + " sent you a friend request"), "the request went out: " + e.chat());
        e2e.eventually(() -> d.dialog() != null && d.dialog().button("Enter a name") != null,
            "the add dialog comes back after sending: " + (d.dialog() == null ? "none" : d.dialog().title()));
        e2e.expect(d.dialogsCleared() == clearedBefore,
            "screens that load first (suggestions, the request) replace the dialog without closing it: "
                + (d.dialogsCleared() - clearedBefore) + " close(s)");

        e2e.step("requests dialog: a sent row asks before it cancels");
        Bot.SeenDialog requests = open(e2e, d, "friend requests", "Friend requests");
        e2e.expect(requests.bodyText().contains("Rows starting with Cancel are requests you sent."), "the sent hint: " + requests.body());
        e2e.click(d, "Cancel: " + eli);
        Bot.SeenDialog confirm = e2e.dialog(d, "Cancel request");
        e2e.expect(confirm.bodyText().contains("Cancel your request to " + eli + "?"), "the question: " + confirm.body());
        e2e.click(d, "Back");
        e2e.dialog(d, "Friend requests");
        e2e.expect("1".equals(placeholder(e2e, eli, "friends_requests")), "Back cancels nothing");
        e2e.click(d, "Cancel: " + eli);
        e2e.dialog(d, "Cancel request");
        e2e.click(d, "Cancel request");
        Bot.SeenDialog after = e2e.dialog(d, "Friend requests");
        e2e.eventually(() -> "0".equals(placeholder(e2e, eli, "friends_requests")), "cancelled: " + requests.body() + " -> " + after.body());

        e2e.step("settings: one form, saved to the player's settings");
        Bot.SeenDialog settingsForm = open(e2e, d, "friend settings", "Friend settings");
        e2e.expect(settingsForm.inputs().keySet().equals(java.util.Set.of("requests", "join_alerts", "leave_alerts", "request_alerts",
            "announce")), "two choices and three switches, no teleport choice while TPA has its own: " + settingsForm.inputs());
        e2e.expect(settingsForm.bodyText().contains("People you know are friends of friends and teammates."), "known explained");
        e2e.click(d, "Save", Map.of("requests", "known", "join_alerts", "favourites", "leave_alerts", "true",
            "request_alerts", "false", "announce", "false"));
        e2e.dialog(d, "Friends");
        e2e.eventually(() -> d.actionBarContains("Friend settings saved."), "saved: " + d.actionBar());
        d.clearLogs();
        d.command("friend settings tpa");
        e2e.eventually(() -> d.chatContains("Unknown setting."), "no tpa key: " + d.chat());
        d.command("friend settings join-alerts");
        e2e.eventually(() -> d.chatContains("join-alerts: favourites"), "join-alerts: " + d.chat());
        d.command("friend settings requests");
        e2e.eventually(() -> d.chatContains("requests: known"), "requests: " + d.chat());
        d.command("friend settings announce");
        e2e.eventually(() -> d.chatContains("announce: off"), "announce: " + d.chat());

        e2e.step("Find shows once there is more than one page (4 per page here)");
        for (int i = 0; i < 5; i++) {
            String name = e2e.name("FrF" + i);
            join(e2e, name);
            befriend(e2e, dee, name);
        }
        Bot.SeenDialog full = open(e2e, d, "friend", "Friends");
        e2e.expect(full.button("Find") != null && full.button("Next") != null, "find and next: " + full.buttons());
        e2e.expect(full.bodyText().contains("Page 1 of 2."), "the page is named: " + full.body());
        e2e.click(d, "Next");
        Bot.SeenDialog second = e2e.dialog(d, "Friends");
        e2e.expect(second.bodyText().contains("Page 2 of 2.") && second.button("Previous") != null, "page 2: " + second.body());
        e2e.click(d, "Previous");
        e2e.dialog(d, "Friends");
        e2e.click(d, "Find");
        e2e.dialog(d, "Find a friend");
        e2e.click(d, "Find", Map.of("query", "FrF3"));
        Bot.SeenDialog found = e2e.dialog(d, "Friends");
        e2e.expect(found.bodyText().contains("Names starting with FrF3: 1") && found.button("Show all") != null, "filtered: " + found.body());
    }

    /** Profile buttons run the other features' commands as the player from the dialog handler (performCommand on Canvas). */
    static void profileActions(E2E e2e) {
        String fay = e2e.name("FrFay");
        String gil = e2e.name("FrGil");
        String hux = e2e.name("FrHux");
        Bot f = join(e2e, fay);
        Bot g = join(e2e, gil);
        Bot h = join(e2e, hux);
        befriend(e2e, fay, gil);

        e2e.step("a friend's profile shows status, since, mutual and the actions that exist");
        Bot.SeenDialog profile = open(e2e, f, "profile " + gil, gil);
        e2e.expect(profile.bodyText().contains("Online") && profile.bodyText().contains("Friends since")
            && profile.bodyText().contains("Mutual friends: 0"), "body: " + profile.body());
        e2e.expect(profile.button("Pay") != null && profile.button("Stats") != null && profile.button("Favourite") != null
            && profile.button("Edit note") != null && profile.button("Remove friend") != null && profile.button("Back") != null,
            "buttons: " + profile.buttons());
        e2e.expect(profile.button("Teleport request") != null, "a teleport request button (TPA is installed): " + profile.buttons());

        e2e.step("Teleport request runs /tpa <name> as the player: the friend gets the request");
        g.clearLogs();
        e2e.click(f, "Teleport request");
        e2e.eventually(() -> g.chatContains(fay + " wants to teleport to you."), "the tpa request arrived: " + g.chat());

        e2e.step("a profile button obeys the server's command guards: /tpa from it is refused in combat");
        e2e.console("combat tag " + fay + " 30s");
        e2e.eventually(() -> lookupTagged(e2e, fay), fay + " is in combat");
        open(e2e, f, "profile " + gil, gil);
        g.clearLogs();
        e2e.click(f, "Teleport request");
        e2e.eventually(() -> f.actionBarContains("You can't use that in combat."), "refused like a typed command: " + f.actionBar());
        e2e.sleep(1_000);
        e2e.expect(!g.chatContains(fay + " wants to teleport to you."), "no request went out: " + g.chat());
        e2e.console("combat untag " + fay);
        e2e.eventually(() -> !lookupTagged(e2e, fay), fay + " is out of combat");

        e2e.step("Stats runs /stats <name> as the player");
        open(e2e, f, "profile " + gil, gil);
        e2e.click(f, "Stats");
        e2e.dialog(f, gil + "'s stats");

        e2e.step("Pay runs /pay <name>, which opens the economy's form");
        open(e2e, f, "profile " + gil, gil);
        e2e.click(f, "Pay");
        e2e.dialog(f, "Pay a player");

        e2e.step("friends-tpa (stored, not offered while TPA has its own toggle) decides FriendLookup#autoAcceptTeleport");
        UUID fid = e2e.uuid(fay);
        UUID gid = e2e.uuid(gil);
        e2e.expect(!lookup(e2e).autoAcceptTeleport(gid, fid), "nobody by default");
        e2e.services().settings().setRaw(gid, "friends-tpa", "favourites");
        e2e.expect(!lookup(e2e).autoAcceptTeleport(gid, fid), "not a favourite yet");
        g.command("friend fav " + fay);
        e2e.eventually(() -> lookup(e2e).autoAcceptTeleport(gid, fid), "a favourite friend is let in");
        e2e.expect(!lookup(e2e).autoAcceptTeleport(gid, e2e.uuid(hux)), "never a stranger");
        e2e.services().settings().setRaw(gid, "friends-tpa", "nobody");
        e2e.eventually(() -> !lookup(e2e).autoAcceptTeleport(gid, fid), "back to nobody");
        g.command("friend fav " + fay);
        e2e.eventually(() -> g.actionBarContains(fay + " is no longer a favourite."), "unfavourited: " + g.actionBar());

        e2e.step("Favourite and the note change the profile");
        open(e2e, f, "profile " + gil, gil);
        e2e.click(f, "Favourite");
        Bot.SeenDialog fav = e2e.dialog(f, gil);
        e2e.eventually(() -> f.actionBarContains(gil + " is a favourite now."), "favourite: " + f.actionBar());
        e2e.expect(fav.button("Unfavourite") != null, "toggled: " + fav.buttons());
        e2e.click(f, "Edit note");
        e2e.dialog(f, "Note on " + gil);
        e2e.click(f, "Save", Map.of("note", "  builds the farms  "));
        Bot.SeenDialog noted = e2e.dialog(f, gil);
        e2e.expect(noted.bodyText().contains("Your note: builds the farms"), "note shown: " + noted.body());
        f.clearLogs();
        f.command("friend note " + gil + " -");
        e2e.eventually(() -> f.actionBarContains("Note on " + gil + " cleared."), "cleared: " + f.actionBar());

        e2e.step("Message (operators have vanilla /msg) sends through the command");
        e2e.console("op " + fay);
        Bot.SeenDialog asOp = open(e2e, f, "profile " + gil, gil);
        e2e.expect(asOp.button("Message") != null, "message for someone with /msg: " + asOp.buttons());
        e2e.click(f, "Message");
        e2e.dialog(f, "Message to " + gil);
        g.clearLogs();
        e2e.click(f, "Send", Map.of("message", "hello from the profile"));
        e2e.eventually(() -> g.chatContains("hello from the profile"), "delivered: " + g.chat());

        e2e.step("a muted player gets no Message button");
        MuteStatus mutes = e2e.feature(StaffFeature.class).mutes();
        e2e.console("mute " + fay + " 10m testing");
        e2e.eventually(() -> mutes.mute(fid).isPresent(), fay + " is muted");
        Bot.SeenDialog muted = open(e2e, f, "profile " + gil, gil);
        e2e.expect(muted.button("Message") == null && muted.button("Pay") != null, "no Message while muted: " + muted.buttons());
        e2e.console("unmute " + fay);
        e2e.eventually(() -> mutes.mute(fid).isEmpty(), fay + " can talk again");
        e2e.expect(open(e2e, f, "profile " + gil, gil).button("Message") != null, "Message is back after the mute");
        e2e.console("deop " + fay);

        e2e.step("Invite to team runs /team invite for a friend without a team");
        e2e.console("eco set " + fay + " 60k");
        e2e.eventually(() -> e2e.money(fay) == 60_000, "funded");
        String team = e2e.name("FrTeam");
        f.command("team create " + team);
        e2e.dialog(f, "Start a team");
        e2e.click(f, "Start team");
        e2e.eventually(() -> team.equals(e2e.services().placeholders().resolve(e2e.player(fay), "team_name")), "team created");
        Bot.SeenDialog withTeam = open(e2e, f, "profile " + gil, gil);
        e2e.expect(withTeam.bodyText().contains("Team") || withTeam.button("Invite to team") != null, "team context: " + withTeam.body());
        e2e.expect(withTeam.button("Invite to team") != null, "invite button: " + withTeam.buttons());
        g.clearLogs();
        e2e.click(f, "Invite to team");
        e2e.eventually(() -> g.chat().stream().anyMatch(line -> line.contains(team)), "gil got the invite: " + g.chat());

        e2e.step("a stranger's card: friendship button, no friendship data, Close");
        Bot.SeenDialog card = open(e2e, f, "profile " + hux, hux);
        e2e.expect(card.button("Add friend") != null && card.button("Close") != null && card.button("Remove friend") == null,
            "card buttons: " + card.buttons());
        e2e.expect(!card.bodyText().contains("Mutual") && !card.bodyText().contains("Friends since"), "no friendship data: " + card.body());
        h.clearLogs();
        e2e.click(f, "Add friend");
        Bot.SeenDialog pending = e2e.dialog(f, hux);
        e2e.eventually(() -> h.chatContains(fay + " sent you a friend request"), "the card sent a request");
        e2e.expect(pending.button("Cancel request") != null, "now a cancel button: " + pending.buttons());

        e2e.step("sneak and right-click a player with an empty hand opens their card");
        Location there = e2e.onPlayer(hux, () -> e2e.player(hux).getLocation());
        e2e.onPlayer(gil, () -> {
            e2e.player(gil).teleportAsync(there.clone().add(1, 0, 0));
            return null;
        });
        UUID hid = e2e.uuid(hux);
        e2e.eventually(() -> g.entityId(hid) >= 0, "gil sees hux");
        e2e.console("combat tag " + hux + " 30s");
        e2e.eventually(() -> lookupTagged(e2e, hux), hux + " is in combat");
        g.clearLogs();
        g.sneak(true);
        g.interactSneaking(g.entityId(hid));
        e2e.sleep(1_500);
        e2e.expect(g.dialogs().isEmpty(), "no card while the clicked player is in combat: " + g.dialogs());
        e2e.console("combat untag " + hux);
        e2e.eventually(() -> !lookupTagged(e2e, hux), hux + " is out of combat");
        g.interactSneaking(g.entityId(hid));
        Bot.SeenDialog sneak = e2e.dialog(g, hux);
        e2e.expect(sneak.button("Add friend") != null || sneak.button("Accept request") != null, "the card: " + sneak.buttons());
        g.sneak(false);
        Player hp = e2e.player(hux);
        e2e.expect(hp.isOnline(), "still here");
    }

    /** Friends, favourites and notes come back from storage after memory let them go. */
    static void restart(E2E e2e) {
        String ike = e2e.name("FrIke");
        String jan = e2e.name("FrJan");
        Bot i = join(e2e, ike);
        join(e2e, jan);
        befriend(e2e, ike, jan);
        i.command("friend fav " + jan);
        e2e.eventually(() -> i.actionBarContains(jan + " is a favourite now."), "favourite");
        i.command("friend note " + jan + " met at spawn");
        e2e.eventually(() -> i.actionBarContains("Note on " + jan + " saved."), "note");
        UUID iid = e2e.uuid(ike);
        UUID jid = e2e.uuid(jan);

        e2e.step("both leave; after the memory grace nothing of them is in memory");
        quit(e2e, i);
        e2e.expect(Bukkit.getPlayerExact(jan) != null, "jan is still online");
        e2e.sleep(3_500);
        e2e.expect(lookup(e2e).friendsOf(iid).isEmpty(), "ike's node was evicted after the 2s grace");
        e2e.expect(lookup(e2e).friends(iid, jid), "jan's node still knows");

        e2e.step("rejoining loads everything back from storage");
        Bot back = join(e2e, ike);
        e2e.eventually(() -> lookup(e2e).friendsOf(iid).contains(jid), "loaded again");
        Bot.SeenDialog profile = open(e2e, back, "profile " + jan, jan);
        e2e.expect(profile.button("Unfavourite") != null, "the favourite survived: " + profile.buttons());
        e2e.expect(profile.bodyText().contains("Your note: met at spawn"), "the note survived: " + profile.body());
    }

    /**
     * The same account logs in again while it is online: the server kicks the old session, and that quit comes after
     * the new login's pre-login. The friends must stay in memory past the memory grace (the new join takes the
     * leaving node back), so the list keeps working without a reload.
     */
    static void secondLogin(E2E e2e) {
        String kit = e2e.name("FrKit");
        String lou = e2e.name("FrLou");
        Bot first = join(e2e, kit);
        join(e2e, lou);
        befriend(e2e, kit, lou);
        UUID kid = e2e.uuid(kit);
        UUID lid = e2e.uuid(lou);

        e2e.step("the account logs in a second time; the old session is kicked");
        Bot second = e2e.bot(kit);
        e2e.eventually(first::disconnected, "the old session was kicked");
        e2e.eventually(() -> e2e.uuid(kit).equals(kid), kit + " is online again");

        e2e.step("after the 2s memory grace the friends are still in memory and the list opens");
        e2e.sleep(3_500);
        e2e.expect(lookup(e2e).friendsOf(kid).contains(lid), "the friends stayed loaded: " + lookup(e2e).friendsOf(kid));
        e2e.expect(lookup(e2e).friends(kid, lid), "still friends");
        Bot.SeenDialog list = open(e2e, second, "friend", "Friends");
        e2e.expect(list.button(lou + ", online") != null, "the list after the second login: " + list.buttons());
        e2e.expect(!second.actionBarContains("still loading"), "no loading message: " + second.actionBar());
    }

    /**
     * The first request in a quiet period is one line with Accept and Deny; more within the batch window add up to one
     * "New friend requests" line; from five waiting requests the requests dialog offers Deny all, which asks first,
     * hides every request in one go and tells none of the senders.
     */
    static void requestAlerts(E2E e2e) {
        String tom = e2e.name("FrTom");
        Bot t = join(e2e, tom);
        List<String> names = new ArrayList<>();
        List<Bot> senders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = e2e.name("FrS" + i);
            names.add(name);
            senders.add(join(e2e, name));
        }

        e2e.step("the first request is told at once, with the command in plain text");
        t.clearLogs();
        senders.get(0).command("friend " + tom);
        e2e.eventually(() -> t.chatContains(names.get(0) + " sent you a friend request. Accept or Deny, or type /friend requests."),
            "first alert: " + t.chat());

        e2e.step("more requests within the window are added up into one line");
        senders.get(1).command("friend " + tom);
        senders.get(2).command("friend " + tom);
        e2e.eventually(() -> t.chatContains("New friend requests: 2. View, or type /friend requests."), 8_000, "batched: " + t.chat());
        e2e.expect(!t.chatContains(names.get(1) + " sent you") && !t.chatContains(names.get(2) + " sent you"),
            "no line per request: " + t.chat());
        senders.get(3).command("friend " + tom);
        senders.get(4).command("friend " + tom);
        e2e.eventually(() -> "5".equals(placeholder(e2e, tom, "friends_requests")), "five waiting");

        e2e.step("the list counts them and Deny all asks first");
        Bot.SeenDialog list = open(e2e, t, "friend", "Friends");
        e2e.expect(list.button("Requests (5)") != null, "requests button: " + list.buttons());
        e2e.click(t, "Requests (5)");
        Bot.SeenDialog requests = e2e.dialog(t, "Friend requests");
        e2e.expect(requests.bodyText().contains("Incoming: 5. Sent: 0."), "body: " + requests.body());
        e2e.expect(requests.button("Accept all") == null, "there is no accept all");
        senders.forEach(Bot::clearLogs);
        e2e.click(t, "Deny all");
        Bot.SeenDialog confirm = e2e.dialog(t, "Deny all requests");
        e2e.expect(confirm.bodyText().contains("Requests: 5. Nobody is told."), "confirmation: " + confirm.body());
        e2e.expect("5".equals(placeholder(e2e, tom, "friends_requests")), "nothing changed before confirming");
        e2e.click(t, "Deny all");
        Bot.SeenDialog after = e2e.dialog(t, "Friend requests");
        e2e.eventually(() -> "0".equals(placeholder(e2e, tom, "friends_requests")), "all hidden: " + after.body());
        e2e.eventually(() -> t.actionBarContains("Requests denied: 5."), "feedback: " + t.actionBar());
        e2e.eventually(() -> historyCount(e2e, tom, "deny") == 5, "five denies in the history");
        e2e.sleep(1_000);
        for (Bot sender : senders) {
            e2e.expect(sender.chat().isEmpty(), sender.name + " was not told: " + sender.chat());
        }

        e2e.step("the senders still see their requests waiting");
        Bot.SeenDialog mine = open(e2e, senders.get(0), "friend requests", "Friend requests");
        e2e.expect(mine.bodyText().contains("Sent: 1.") && mine.button("Cancel: " + tom) != null, "sender's view: " + mine.body());
    }

    /**
     * "People you may know" in the add dialog: online friends of friends, ranked by shared friends, without existing
     * friends, open requests or players whose privacy refuses; picking one sends the request.
     */
    static void suggestions(E2E e2e) {
        String uli = e2e.name("FrUli");
        String val = e2e.name("FrVal");
        String wim = e2e.name("FrWim");
        String xan = e2e.name("FrXan");
        String yul = e2e.name("FrYul");
        Bot u = join(e2e, uli);
        join(e2e, val);
        Bot w = join(e2e, wim);
        join(e2e, xan);
        Bot y = join(e2e, yul);
        befriend(e2e, uli, val);
        befriend(e2e, uli, xan);
        befriend(e2e, val, wim);
        befriend(e2e, xan, wim);
        befriend(e2e, val, yul);
        y.command("friend settings requests nobody");
        e2e.eventually(() -> y.actionBarContains("Set requests to nobody."), "yul takes no requests");

        e2e.step("a friend of two friends is suggested with the count; a private player is not");
        Bot.SeenDialog list = open(e2e, u, "friend", "Friends");
        e2e.click(u, "Add a friend");
        Bot.SeenDialog add = e2e.dialog(u, "Add a friend");
        e2e.expect(add.button(wim + ", 2 mutual friends") != null, "suggestion: " + add.buttons() + " from " + list.title());
        e2e.expect(add.button(yul) == null, "privacy nobody is never suggested: " + add.buttons());
        e2e.expect(add.button(val) == null && add.button(xan) == null, "friends are not suggested: " + add.buttons());

        e2e.step("picking the suggestion sends the request and it leaves the list");
        w.clearLogs();
        e2e.click(u, wim + ", 2 mutual friends");
        e2e.eventually(() -> w.chatContains(uli + " sent you a friend request"), "the request: " + w.chat());
        e2e.eventually(() -> u.dialog() != null && u.dialog().button("Enter a name") != null && u.dialog().button(wim) == null,
            "the add dialog without the pending player: " + (u.dialog() == null ? "none" : u.dialog().buttons()));
    }

    /**
     * The friendship as other features see it: TPA lets a friend come without asking when the target allows friends,
     * while anyone else still has to ask; "friends of friends" privacy lets a friend of a friend send a request and
     * refuses a stranger honestly.
     */
    static void links(E2E e2e) {
        String ari = e2e.name("FrAri");
        String bex = e2e.name("FrBex");
        String cal = e2e.name("FrCal");
        String dot = e2e.name("FrDot");
        Bot a = join(e2e, ari);
        Bot b = join(e2e, bex);
        Bot c = join(e2e, cal);
        Bot d = join(e2e, dot);
        befriend(e2e, ari, bex);
        befriend(e2e, bex, cal);

        e2e.step("TPA: a friend comes without asking once the target lets friends in");
        b.command("tpatoggle friends");
        e2e.eventually(() -> b.actionBarContains("Friends can now teleport to you without asking."), "friends on: " + b.actionBar());
        a.clearLogs();
        b.clearLogs();
        a.command("tpa " + bex);
        e2e.eventually(() -> a.anyFeedbackContains(bex + " lets friends come without asking."), "the friend goes: " + a.actionBar() + a.chat());
        e2e.eventually(() -> b.anyFeedbackContains(ari + " is teleporting to you."), "the target is told: " + b.actionBar() + b.chat());
        d.clearLogs();
        b.clearLogs();
        d.command("tpa " + bex);
        e2e.eventually(() -> b.chatContains(dot + " wants to teleport to you."), "a stranger still asks: " + b.chat());

        e2e.step("friends of friends privacy: a friend of a friend may ask, a stranger is refused honestly");
        a.command("friend settings requests known");
        e2e.eventually(() -> a.actionBarContains("Set requests to known."), "privacy known: " + a.actionBar());
        c.clearLogs();
        c.command("friend " + ari);
        e2e.eventually(() -> c.actionBarContains("Friend request sent to " + ari + "."), "known through " + bex + ": " + c.actionBar());
        d.clearLogs();
        d.command("friend " + ari);
        e2e.eventually(() -> d.actionBarContains(ari + " isn't taking friend requests from you."), "a stranger: " + d.actionBar());
    }
}
