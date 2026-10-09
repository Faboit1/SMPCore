package net.siftvanilla.e2e;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.players.NameAndId;
import net.siftvanilla.siftcore.SiftCore;
import net.siftvanilla.siftcore.SiftCorePlugin;
import net.siftvanilla.siftcore.api.event.PlayerPayEvent;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Scenarios for the staff tools: mutes, bans, freeze, vanish, reports, inventory inspection, staff chat, lookups. */
final class StaffScenarios {

    private StaffScenarios() {
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
        list.add(of("staff-mute", StaffScenarios::mute));
        list.add(of("staff-ban", StaffScenarios::ban));
        list.add(of("staff-freeze", StaffScenarios::freeze));
        list.add(of("staff-freeze-escapes", StaffScenarios::freezeEscapes));
        list.add(of("staff-freeze-portal", StaffScenarios::freezePortal));
        list.add(of("staff-hierarchy", StaffScenarios::hierarchy));
        list.add(of("staff-vanish", StaffScenarios::vanish));
        list.add(of("staff-vanish-join-messages", StaffScenarios::vanishJoinMessages));
        list.add(of("staff-report", StaffScenarios::report));
        list.add(of("staff-invsee", StaffScenarios::invsee));
        list.add(of("staff-chat", StaffScenarios::staffChat));
        list.add(of("staff-lookups", StaffScenarios::lookups));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** The running staff feature (read through the core's feature list). */
    private static StaffFeature staff() throws ReflectiveOperationException {
        SiftCorePlugin plugin = (SiftCorePlugin) Bukkit.getPluginManager().getPlugin("SiftCore");
        if (plugin == null || plugin.core() == null) {
            throw new E2E.Failure("SiftCore is not running");
        }
        Field field = SiftCore.class.getDeclaredField("features");
        field.setAccessible(true);
        for (Object feature : (List<?>) field.get(plugin.core())) {
            if (feature instanceof StaffFeature staff) {
                return staff;
            }
        }
        throw new E2E.Failure("the staff feature is not loaded");
    }

    /** Chat, action bar or title. */
    private static boolean saw(Bot bot, String text) {
        String needle = text.toLowerCase();
        return bot.anyFeedbackContains(text) || bot.titles().stream().anyMatch(line -> line.toLowerCase().contains(needle));
    }

    private static void expectSaw(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> saw(bot, text), bot.name + " sees '" + text + "' (chat " + bot.chat() + ", action bar "
            + bot.actionBar() + ", titles " + bot.titles() + ")");
    }

    private static Location location(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> e2e.player(name).getLocation());
    }

    private static boolean recentAudit(E2E e2e, String action, UUID target) {
        try {
            List<AuditLog.Entry> rows = e2e.services().audit().recent(action, target.toString(), 10).get(5, TimeUnit.SECONDS);
            return !rows.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** What the server list shows: player count and sample names. */
    private record Ping(int online, List<String> sample) {
    }

    /** A real status ping (the multiplayer screen's request) against this server. */
    private static Ping ping() throws Exception {
        CompletableFuture<ServerStatus> result = new CompletableFuture<>();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("handleStatusResponse")) {
                result.complete(((ClientboundStatusResponsePacket) args[0]).status());
                return null;
            }
            if (name.equals("isAcceptingMessages")) {
                return true;
            }
            if (name.equals("onDisconnect")) {
                result.completeExceptionally(new E2E.Failure("the status ping was disconnected"));
                return null;
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            if (name.equals("toString")) {
                return "StatusPing";
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return null;
        };
        ClientStatusPacketListener listener = (ClientStatusPacketListener) Proxy.newProxyInstance(StaffScenarios.class.getClassLoader(),
            new Class<?>[] {ClientStatusPacketListener.class}, handler);
        int port = Bukkit.getPort();
        Connection connection = Connection.connectToServer(new InetSocketAddress("127.0.0.1", port), EventLoopGroupHolder.remote(false), null);
        try {
            Bot.awaitActive(connection);
            connection.initiateServerboundStatusConnection("127.0.0.1", port, listener);
            connection.send(ServerboundStatusRequestPacket.INSTANCE);
            ServerStatus status = result.get(10, TimeUnit.SECONDS);
            ServerStatus.Players players = status.players().orElseThrow(() -> new E2E.Failure("the ping has no player section"));
            List<String> sample = new ArrayList<>();
            for (NameAndId entry : players.sample()) {
                sample.add(entry.name());
            }
            return new Ping(players.online(), sample);
        } finally {
            connection.disconnect(Component.literal("ping done"));
        }
    }

    // ------------------------------------------------------------------ mutes

    static void mute(E2E e2e) throws Exception {
        String MOD = e2e.name("MuteMod");
        String TALKER = e2e.name("Muted");
        Bot mod = e2e.bot(MOD);
        Bot talker = e2e.bot(TALKER);
        e2e.console("op " + MOD);
        MuteStatus mutes = staff().mutes();
        UUID talkerId = e2e.uuid(TALKER);

        e2e.step("public chat works before the mute");
        mod.clearLogs();
        talker.chat("hello before the mute");
        e2e.eventually(() -> mod.chatContains("hello before the mute"), "the moderator sees normal chat: " + mod.chat());

        e2e.step("a timed mute with a reason");
        mod.clearLogs();
        talker.clearLogs();
        mod.command("mute " + TALKER + " 10m spamming links");
        expectSaw(e2e, mod, "Muted " + TALKER + " for");
        expectSaw(e2e, talker, "You were muted for");
        e2e.eventually(() -> mutes.mute(talkerId).isPresent(), "MuteStatus reports the mute");
        MuteStatus.Mute active = mutes.mute(talkerId).orElseThrow();
        e2e.expect(active.reason().equals("spamming links"), "the reason is kept: " + active.reason());
        e2e.expect(!active.permanent() && active.until() > System.currentTimeMillis() + 9 * 60_000L, "about ten minutes left: " + active.until());
        e2e.expect(active.staff().equals(MOD), "the staff name is kept: " + active.staff());

        e2e.step("muted chat is blocked with the reason and time");
        mod.clearLogs();
        talker.clearLogs();
        talker.chat("can anyone hear me");
        expectSaw(e2e, talker, "You are muted for");
        e2e.expect(saw(talker, "spamming links"), "the reason in the muted message: " + talker.actionBar() + talker.chat());
        e2e.sleep(800);
        e2e.expect(!mod.chatContains("can anyone hear me"), "nobody receives the muted message: " + mod.chat());

        e2e.step("blocked commands are refused while muted");
        talker.clearLogs();
        talker.command("me waves");
        expectSaw(e2e, talker, "You are muted");
        e2e.expect(!mod.chatContains("waves"), "/me did not go through: " + mod.chat());

        e2e.step("players without permission can't mute");
        talker.command("mute " + MOD + " revenge");
        e2e.sleep(800);
        e2e.expect(mutes.mute(e2e.uuid(MOD)).isEmpty(), "the moderator is not muted");

        e2e.step("a permanent mute replaces the timed one");
        mod.command("mute " + TALKER + " caps");
        e2e.eventually(() -> mutes.mute(talkerId).map(MuteStatus.Mute::permanent).orElse(false), "the mute is permanent now");
        e2e.expect(mutes.mute(talkerId).orElseThrow().reason().equals("caps"), "the new reason");

        e2e.step("unmute lets the player talk again");
        mod.clearLogs();
        talker.clearLogs();
        mod.command("unmute " + TALKER);
        e2e.eventually(() -> mutes.mute(talkerId).isEmpty(), "MuteStatus reports no mute");
        expectSaw(e2e, talker, "You can talk again");
        talker.chat("thanks for unmuting");
        e2e.eventually(() -> mod.chatContains("thanks for unmuting"), "chat is public again: " + mod.chat());
        mod.clearLogs();
        mod.command("unmute " + TALKER);
        expectSaw(e2e, mod, "isn't muted");
        e2e.console("deop " + MOD);
    }

    // ------------------------------------------------------------------ bans, kicks, warnings

    static void ban(E2E e2e) throws Exception {
        String MOD = e2e.name("BanMod");
        String BANNED = e2e.name("Banned");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot victim = e2e.bot(BANNED);

        e2e.step("a permanent ban removes the player with the ban screen");
        mod.clearLogs();
        e2e.console("ban " + BANNED + " using x-ray");
        e2e.eventually(victim::disconnected, "the banned player is removed");
        String screen = victim.disconnectReason();
        e2e.expect(screen.contains("You are banned from SiftVanilla."), "the ban screen: " + screen);
        e2e.expect(screen.contains("using x-ray") && screen.contains("This ban is permanent."), "reason and permanence: " + screen);
        e2e.expect(screen.contains("Appeal on our Discord"), "the appeal line: " + screen);
        e2e.eventually(() -> Bukkit.getPlayerExact(BANNED) == null, "the banned player is gone");
        e2e.eventually(() -> mod.chatContains("banned " + BANNED), "staff are told: " + mod.chat());

        e2e.step("a banned player is refused at login with the ban screen");
        Bot retry = new Bot(BANNED);
        try {
            retry.connect(Bukkit.getPort());
            e2e.eventually(retry::disconnected, "the login is refused");
            e2e.expect(retry.disconnectReason().contains("You are banned from SiftVanilla."), "refused with the ban screen: "
                + retry.disconnectReason());
            e2e.expect(Bukkit.getPlayerExact(BANNED) == null, "the banned player never got in");
        } finally {
            retry.quit();
        }

        e2e.step("a warning given while offline waits for the next join");
        e2e.console("warn " + BANNED + " mind your language");

        e2e.step("unban lets the player join again");
        e2e.console("unban " + BANNED);
        Bot back = e2e.bot(BANNED);
        expectSaw(e2e, back, "while you were away");
        e2e.expect(saw(back, "mind your language"), "the missed warning's reason: " + back.chat());

        e2e.step("a kick shows the kick screen");
        mod.command("kick " + BANNED + " calm down");
        e2e.eventually(back::disconnected, "the kicked player is removed");
        e2e.expect(back.disconnectReason().contains("You were kicked from SiftVanilla.") && back.disconnectReason().contains("calm down"),
            "the kick screen: " + back.disconnectReason());
        e2e.eventually(() -> Bukkit.getPlayerExact(BANNED) == null, "the kicked player is gone");

        e2e.step("a kicked player may come back; a temporary ban shows the time left");
        Bot again = e2e.bot(BANNED);
        mod.clearLogs();
        mod.command("tempban " + BANNED + " 2h alt account");
        e2e.eventually(again::disconnected, "the temp-banned player is removed");
        e2e.expect(again.disconnectReason().contains("Time left") && again.disconnectReason().contains("alt account"),
            "the temporary ban screen: " + again.disconnectReason());
        expectSaw(e2e, mod, "Banned " + BANNED + " for");
        Bot refused = new Bot(BANNED);
        try {
            refused.connect(Bukkit.getPort());
            e2e.eventually(refused::disconnected, "the temp-banned login is refused");
            e2e.expect(refused.disconnectReason().contains("Time left"), "the time left at login: " + refused.disconnectReason());
        } finally {
            refused.quit();
        }

        e2e.step("bad times are refused and nothing changes");
        mod.clearLogs();
        mod.command("tempban " + BANNED + " soon");
        expectSaw(e2e, mod, "Give a time");
        mod.clearLogs();
        mod.command("tempban " + BANNED + " 9999d spam");
        expectSaw(e2e, mod, "Use /ban for a permanent ban.");
        e2e.expect(!saw(mod, "Leave the time out"), "a /tempban without a time is refused, so it isn't suggested: " + mod.actionBar());

        e2e.step("history lists everything, newest first");
        mod.clearLogs();
        mod.command("history " + BANNED);
        Bot.SeenDialog history = e2e.dialog(mod, "History of " + BANNED);
        String body = history.bodyText();
        e2e.expect(body.contains("Ban") && body.contains("Kick") && body.contains("Warning"), "every entry: " + history.body());
        e2e.expect(body.indexOf("alt account") < body.indexOf("using x-ray"), "newest first: " + history.body());

        e2e.step("unban, and unbanning twice says so");
        mod.command("unban " + BANNED);
        expectSaw(e2e, mod, "Unbanned " + BANNED);
        mod.clearLogs();
        mod.command("unban " + BANNED);
        expectSaw(e2e, mod, "isn't banned");
        e2e.bot(BANNED);
        e2e.console("deop " + MOD);
    }

    // ------------------------------------------------------------------ freeze

    static void freeze(E2E e2e) throws Exception {
        String MOD = e2e.name("FrzMod");
        String SUSPECT = e2e.name("Frozen");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot suspect = e2e.bot(SUSPECT);
        UUID suspectId = e2e.uuid(SUSPECT);

        e2e.step("a control move goes through before the freeze");
        Location start = location(e2e, SUSPECT);
        suspect.moveBy(0, 0.5, 0, 0f);
        e2e.eventually(() -> location(e2e, SUSPECT).getY() > start.getY() + 0.4, "the unfrozen player moved up");

        e2e.step("freeze");
        mod.clearLogs();
        suspect.clearLogs();
        mod.command("freeze " + SUSPECT);
        expectSaw(e2e, mod, "Froze " + SUSPECT);
        expectSaw(e2e, suspect, "Staff froze you");
        e2e.expect(e2e.onPlayer(SUSPECT, () -> e2e.player(SUSPECT).getAllowFlight()), "a frozen player may hang in the air");

        e2e.step("moving does not change the position, turning does");
        Location before = location(e2e, SUSPECT);
        suspect.moveBy(0, 0.5, 0, 45f);
        e2e.sleep(1_200);
        Location after = location(e2e, SUSPECT);
        e2e.expect(after.getX() == before.getX() && after.getY() == before.getY() && after.getZ() == before.getZ(),
            "the position is unchanged: " + before + " -> " + after);
        e2e.expect(after.getYaw() != before.getYaw(), "the rotation changed: " + before.getYaw() + " -> " + after.getYaw());

        e2e.step("the reminder is on the action bar");
        suspect.clearLogs();
        e2e.eventually(() -> suspect.actionBarContains("Frozen by staff. Do not log out."), 5_000, "the freeze reminder: " + suspect.actionBar());

        e2e.step("commands are blocked except the allowlist");
        suspect.clearLogs();
        suspect.command("report " + MOD + " freezing me for no reason");
        expectSaw(e2e, suspect, "You can't use that command while frozen.");
        e2e.expect(!saw(suspect, "Thanks. Staff will look"), "the report did not go through");
        mod.clearLogs();
        suspect.command("msg " + MOD + " what did I do");
        e2e.eventually(() -> mod.chatContains("what did I do"), "/msg still works: " + mod.chat());

        e2e.step("logging out while frozen tells staff and is audited");
        mod.clearLogs();
        suspect.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(SUSPECT) == null, "the suspect left");
        expectSaw(e2e, mod, SUSPECT + " logged out while frozen.");
        e2e.eventually(() -> recentAudit(e2e, "staff.freeze.logout", suspectId), "an audit row for the logout");

        e2e.step("the freeze survives a relog");
        Bot returned = e2e.bot(SUSPECT);
        e2e.eventually(() -> returned.actionBarContains("Frozen by staff. Do not log out."), 5_000, "still frozen after the relog: "
            + returned.actionBar());
        Location relog = location(e2e, SUSPECT);
        returned.moveBy(0, 0.5, 0, 0f);
        e2e.sleep(1_200);
        e2e.expect(location(e2e, SUSPECT).getY() == relog.getY(), "still unable to move");

        e2e.step("unfreeze");
        returned.clearLogs();
        mod.command("freeze " + SUSPECT);
        expectSaw(e2e, returned, "You can move again.");
        Location free = location(e2e, SUSPECT);
        returned.moveBy(0, 0.5, 0, 0f);
        e2e.eventually(() -> location(e2e, SUSPECT).getY() > free.getY() + 0.4, "the unfrozen player moves again");
        e2e.eventually(() -> recentAudit(e2e, "staff.freeze.off", suspectId), "the unfreeze is audited");

        e2e.step("a frozen player who gets banned is a kick, not a logout");
        returned.clearLogs();
        mod.command("freeze " + SUSPECT);
        expectSaw(e2e, returned, "Staff froze you");
        mod.clearLogs();
        e2e.console("ban " + SUSPECT + " ignoring staff");
        e2e.eventually(returned::disconnected, "the banned frozen player is removed");
        e2e.eventually(() -> mod.chatContains("banned " + SUSPECT), "staff are told about the ban: " + mod.chat());
        e2e.sleep(1_000);
        e2e.expect(!mod.chatContains("logged out while frozen"), "no logout notice for a ban: " + mod.chat());
        e2e.console("unban " + SUSPECT);
        e2e.console("freeze " + SUSPECT);

        e2e.step("unfrozen while offline: free on the next join, without the freeze flight");
        Bot rejoined = e2e.bot(SUSPECT);
        e2e.sleep(3_000);
        e2e.expect(!rejoined.actionBarContains("Frozen by staff"), "no freeze reminder: " + rejoined.actionBar());
        e2e.expect(!e2e.onPlayer(SUSPECT, () -> e2e.player(SUSPECT).getAllowFlight()), "the flight given while frozen is gone");
        e2e.console("deop " + MOD);
    }

    /**
     * A frozen player can't escape through the pause menu, dialogs from chat, plugin teleports, containers or payments:
     * the shared teleports and the dialog router refuse them, and the freeze closes what was open.
     */
    static void freezeEscapes(E2E e2e) throws Exception {
        String MOD = e2e.name("EscMod");
        String SUSPECT = e2e.name("Escapee");
        String ALLY = e2e.name("Ally");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot suspect = e2e.bot(SUSPECT);
        Bot ally = e2e.bot(ALLY);
        UUID suspectId = e2e.uuid(SUSPECT);
        UUID allyId = e2e.uuid(ALLY);

        e2e.step("before the freeze the pause menu works and an open menu stays");
        suspect.rawClick("siftcore:hub/menu", null);
        e2e.dialog(suspect, "SiftVanilla");

        e2e.step("freezing closes the open dialog");
        int cleared = suspect.dialogsCleared();
        suspect.clearLogs();
        mod.command("freeze " + SUSPECT);
        expectSaw(e2e, suspect, "Staff froze you");
        e2e.eventually(() -> suspect.dialogsCleared() > cleared && suspect.dialog() == null, "the dialog was closed");
        Location start = location(e2e, SUSPECT);

        e2e.step("the pause menu's spawn route does nothing: no teleport, even after the warmup");
        suspect.clearLogs();
        suspect.rawClick("siftcore:hub/spawn", null);
        expectSaw(e2e, suspect, "You can't do that while frozen.");
        suspect.rawClick("siftcore:hub/money", null);
        e2e.sleep(5_000);
        e2e.expect(suspect.dialog() == null, "no money menu: " + suspect.dialog());
        Location after = location(e2e, SUSPECT);
        e2e.expect(after.getWorld().equals(start.getWorld()) && after.distance(start) < 0.01, "not moved: " + start + " -> " + after);

        e2e.step("a teleport through the shared teleports is refused");
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        Location far = start.clone().add(400, 0, 400);
        suspect.clearLogs();
        e2e.onPlayer(SUSPECT, () -> {
            e2e.services().teleports().teleport(e2e.player(SUSPECT), "e2e", java.time.Duration.ZERO,
                () -> CompletableFuture.completedFuture(far), moved::complete);
            return null;
        });
        e2e.expect(!moved.get(10, TimeUnit.SECONDS), "the shared teleport refused");
        expectSaw(e2e, suspect, "You can't teleport while frozen.");

        e2e.step("a direct plugin teleport is cancelled too");
        Boolean direct = e2e.player(SUSPECT).teleportAsync(far).get(10, TimeUnit.SECONDS);
        e2e.expect(!Boolean.TRUE.equals(direct), "teleportAsync(PLUGIN) was cancelled");
        e2e.expect(location(e2e, SUSPECT).distance(start) < 0.01, "still in place");

        e2e.step("a teleport request answered from the chat dialog moves nobody, and stays for later");
        suspect.clearLogs();
        ally.command("tpahere " + SUSPECT);
        e2e.eventually(() -> suspect.chatContains("wants you to teleport to them"), "the request arrived: " + suspect.chat());
        e2e.expect(suspect.openChatDialog("wants you to teleport to them"), "the request's dialog opens from chat");
        e2e.click(suspect, "Accept");
        expectSaw(e2e, suspect, "You can't do that while frozen.");
        e2e.sleep(4_000);
        e2e.expect(location(e2e, SUSPECT).distance(start) < 0.01, "the frozen player didn't go to the ally");

        e2e.step("no containers or menus open");
        Object view = e2e.onPlayer(SUSPECT, () -> e2e.player(SUSPECT).openInventory(Bukkit.createInventory(null, 9)));
        e2e.expect(view == null, "the inventory open was cancelled");
        e2e.sleep(500);
        e2e.expect(suspect.screen() == null, "no screen on the client: " + suspect.screen());

        e2e.step("no payments");
        boolean paid = e2e.onPlayer(SUSPECT, () -> new PlayerPayEvent(suspectId, allyId, 100).callEvent());
        e2e.expect(!paid, "a payment from a frozen player is cancelled");
        boolean received = e2e.onPlayer(ALLY, () -> new PlayerPayEvent(allyId, suspectId, 100).callEvent());
        e2e.expect(received, "paying a frozen player is not blocked");

        e2e.step("unfreezing gives the menus back, and the request can be accepted");
        suspect.clearLogs();
        mod.command("freeze " + SUSPECT);
        expectSaw(e2e, suspect, "You can move again.");
        suspect.rawClick("siftcore:hub/menu", null);
        e2e.dialog(suspect, "SiftVanilla");
        suspect.clearLogs();
        suspect.command("tpaccept " + ALLY);
        e2e.eventually(() -> suspect.chatContains("Accepted " + ALLY + "'s request.") || suspect.actionBarContains("Accepted " + ALLY),
            "the request waited: " + suspect.chat() + " " + suspect.actionBar());
        e2e.console("deop " + MOD);
    }

    /**
     * A frozen player whom staff /tp into a nether portal stays in the overworld: Canvas carries players through
     * portals without any teleport event, so the freeze cancels the portal events themselves. Unfrozen, the same
     * portal takes them (so the portal really works).
     */
    static void freezePortal(E2E e2e) throws Exception {
        org.bukkit.World nether = Bukkit.getWorlds().stream()
            .filter(world -> world.getEnvironment() == org.bukkit.World.Environment.NETHER).findFirst().orElse(null);
        e2e.expect(nether != null, "the server has a nether");
        String MOD = e2e.name("PortMod");
        String SUSPECT = e2e.name("Portaler");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot suspect = e2e.bot(SUSPECT);
        Location start = location(e2e, SUSPECT);
        Location portal = e2e.ground(start.getWorld(), start.getBlockX() + 24, start.getBlockZ() + 24, 0f);
        try {
            e2e.step("a two-block nether portal near the suspect");
            setPortal(e2e, portal, Material.NETHER_PORTAL);

            e2e.step("staff freeze the suspect and /tp them into the portal; it never takes them");
            suspect.clearLogs();
            mod.command("freeze " + SUSPECT);
            expectSaw(e2e, suspect, "Staff froze you");
            e2e.console("tp " + SUSPECT + " " + portal.getX() + " " + portal.getY() + " " + portal.getZ());
            e2e.eventually(() -> location(e2e, SUSPECT).distance(portal) < 0.5, "staff teleports still move a frozen player");
            e2e.sleep(8_000);
            Location after = location(e2e, SUSPECT);
            e2e.expect(after.getWorld().equals(portal.getWorld()) && after.distance(portal) < 0.5,
                "still in the portal in the overworld after twice the portal time: " + after);

            e2e.step("unfrozen, the same portal takes them to the nether");
            suspect.clearLogs();
            mod.command("freeze " + SUSPECT);
            expectSaw(e2e, suspect, "You can move again.");
            e2e.eventually(() -> location(e2e, SUSPECT).getWorld().equals(nether), 20_000, "the portal works: " + location(e2e, SUSPECT));
        } finally {
            setPortal(e2e, portal, Material.AIR);
            e2e.console("deop " + MOD);
        }
    }

    /** Sets the block at {@code at} and the one above it, without physics, on the region thread that owns them. */
    private static void setPortal(E2E e2e, Location at, Material type) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(e2e.services().plugin(), at, task -> {
            try {
                at.getBlock().setType(type, false);
                at.clone().add(0, 1, 0).getBlock().setType(type, false);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    /**
     * The staff hierarchy: nobody punishes, kicks, freezes or vanishes the owner (siftcore.hierarchy.owner) but
     * the console; with LuckPerms, staff can't act on staff of the same or a higher group weight, online or offline.
     */
    static void hierarchy(E2E e2e) throws Exception {
        String MOD = e2e.name("HierMod");
        String OWNER = e2e.name("Owner");
        String PLAYER = e2e.name("Plain");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot owner = e2e.bot(OWNER);
        Bot plain = e2e.bot(PLAYER);
        UUID ownerId = e2e.uuid(OWNER);
        UUID plainId = e2e.uuid(PLAYER);
        MuteStatus mutes = staff().mutes();
        e2e.onPlayer(OWNER, () -> {
            var attachment = e2e.player(OWNER).addAttachment(e2e.services().plugin());
            attachment.setPermission("siftcore.hierarchy.owner", true);
            attachment.setPermission("siftcore.staff.mute", true);
            return null;
        });

        e2e.step("a moderator can't mute, warn, freeze, kick, vanish or ban the owner");
        String refused = "You can't do that to " + OWNER + ".";
        for (String command : List.of("mute " + OWNER + " 10m test", "warn " + OWNER + " test", "freeze " + OWNER,
            "kick " + OWNER + " test", "vanish " + OWNER, "ban " + OWNER + " test")) {
            mod.clearLogs();
            mod.command(command);
            e2e.eventually(() -> saw(mod, refused), "/" + command + " is refused: " + mod.actionBar() + mod.chat());
        }
        e2e.sleep(500);
        e2e.expect(mutes.mute(ownerId).isEmpty(), "the owner isn't muted");
        e2e.expect(!owner.disconnected(), "the owner wasn't kicked or banned");
        e2e.expect(!owner.actionBarContains("Frozen by staff"), "the owner wasn't frozen");
        e2e.eventually(() -> recentAudit(e2e, "staff.hierarchy.refused", ownerId), "the attempts are audited");

        e2e.step("players who aren't staff can still be punished");
        mod.clearLogs();
        mod.command("mute " + PLAYER + " 5m test");
        e2e.eventually(() -> mutes.mute(plainId).isPresent(), "the player is muted");
        mod.command("unmute " + PLAYER);
        e2e.eventually(() -> mutes.mute(plainId).isEmpty(), "and unmuted");

        e2e.step("the console is never refused");
        e2e.console("mute " + OWNER + " 1m console test");
        e2e.eventually(() -> mutes.mute(ownerId).isPresent(), "the console muted the owner");
        e2e.console("unmute " + OWNER);
        e2e.eventually(() -> mutes.mute(ownerId).isEmpty(), "and unmuted");

        e2e.step("the owner acts on anyone");
        owner.command("mute " + MOD + " 1m owner test");
        e2e.eventually(() -> mutes.mute(e2e.uuid(MOD)).isPresent(), "the owner muted the moderator");
        e2e.console("unmute " + MOD);
        e2e.eventually(() -> mutes.mute(e2e.uuid(MOD)).isEmpty(), "unmuted");

        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            String ADMIN = e2e.name("HierAdm");
            Bot admin = e2e.bot(ADMIN);
            UUID adminId = e2e.uuid(ADMIN);
            UUID modId = e2e.uuid(MOD);
            e2e.step("LuckPerms weights: a moderator (200) can't act on an admin (300); the admin can act on the moderator");
            e2e.console("lp creategroup e2estaffmod");
            e2e.console("lp group e2estaffmod setweight 200");
            e2e.console("lp creategroup e2estaffadmin");
            e2e.console("lp group e2estaffadmin setweight 300");
            e2e.console("lp user " + MOD + " parent add e2estaffmod");
            e2e.console("lp user " + ADMIN + " parent add e2estaffadmin");
            e2e.console("lp user " + ADMIN + " permission set siftcore.staff.mute true");
            e2e.eventually(() -> e2e.onPlayer(MOD, () -> e2e.player(MOD).hasPermission("group.e2estaffmod"))
                && e2e.onPlayer(ADMIN, () -> e2e.player(ADMIN).hasPermission("group.e2estaffadmin")
                    && e2e.player(ADMIN).hasPermission("siftcore.staff.mute")), "the groups are applied");
            try {
                mod.clearLogs();
                mod.command("mute " + ADMIN + " 10m test");
                e2e.eventually(() -> saw(mod, "You can't do that to " + ADMIN + "."), "a higher weight is refused: " + mod.actionBar());
                e2e.expect(mutes.mute(adminId).isEmpty(), "the admin isn't muted");
                admin.command("mute " + MOD + " 1m test");
                e2e.eventually(() -> mutes.mute(modId).isPresent(), "the admin muted the moderator");
                e2e.console("unmute " + MOD);

                e2e.step("an offline admin is looked up in LuckPerms first and stays protected");
                admin.quit();
                e2e.eventually(() -> Bukkit.getPlayerExact(ADMIN) == null, "the admin left");
                mod.clearLogs();
                mod.command("ban " + ADMIN + " offline test");
                e2e.eventually(() -> saw(mod, "You can't do that to " + ADMIN + "."), "refused offline: " + mod.actionBar());
                e2e.expect(staffBanned(adminId).isEmpty(), "the offline admin isn't banned");

                e2e.step("the same weight is refused too");
                e2e.console("lp user " + ADMIN + " parent remove e2estaffadmin");
                e2e.console("lp user " + ADMIN + " parent add e2estaffmod");
                e2e.sleep(1_000);
                mod.clearLogs();
                mod.command("ban " + ADMIN + " same weight");
                e2e.eventually(() -> saw(mod, "You can't do that to " + ADMIN + "."), "refused at the same weight: " + mod.actionBar());
                e2e.expect(staffBanned(adminId).isEmpty(), "still not banned");
            } finally {
                e2e.console("lp user " + MOD + " parent remove e2estaffmod");
                e2e.console("lp user " + ADMIN + " parent remove e2estaffmod");
                e2e.console("lp user " + ADMIN + " permission unset siftcore.staff.mute");
                e2e.console("lp deletegroup e2estaffmod");
                e2e.console("lp deletegroup e2estaffadmin");
            }
        } else {
            e2e.log("LuckPerms is not installed: group weights were not exercised");
        }
        e2e.console("deop " + MOD);
    }

    /** The active ban of a player, read through the staff feature's punishments (test-only reflection). */
    private static java.util.Optional<?> staffBanned(UUID player) throws ReflectiveOperationException {
        StaffFeature feature = staff();
        Field field = StaffFeature.class.getDeclaredField("punishments");
        field.setAccessible(true);
        Object punishments = field.get(feature);
        java.lang.reflect.Method activeBan = punishments.getClass().getDeclaredMethod("activeBan", UUID.class);
        activeBan.setAccessible(true);
        return (java.util.Optional<?>) activeBan.invoke(punishments, player);
    }

    // ------------------------------------------------------------------ vanish

    static void vanish(E2E e2e) throws Exception {
        String MOD = e2e.name("VanMod");
        String WATCHER = e2e.name("Watcher");
        String LATE = e2e.name("Late");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot watcher = e2e.bot(WATCHER);
        UUID modId = e2e.uuid(MOD);
        UUID watcherId = e2e.uuid(WATCHER);
        StaffFeature staff = staff();

        e2e.step("before vanishing everyone sees the moderator");
        e2e.eventually(() -> watcher.hasPlayerInfo(modId) && watcher.seesEntity(modId), "the watcher has the moderator in tab and world");
        Ping visible = ping();
        e2e.expect(visible.online() == Bukkit.getOnlinePlayers().size(), "the ping counts everyone: " + visible);

        e2e.step("vanish hides the moderator from the tab list and the world");
        mod.clearLogs();
        mod.command("vanish");
        expectSaw(e2e, mod, "You are vanished");
        e2e.eventually(() -> staff.vanish().vanished(modId), "VanishStatus reports the vanish");
        e2e.eventually(() -> !watcher.hasPlayerInfo(modId), "the player info entry was removed for the watcher");
        e2e.eventually(() -> !watcher.seesEntity(modId), "the watcher no longer tracks the moderator's entity");
        e2e.expect(!e2e.onPlayer(WATCHER, () -> e2e.player(WATCHER).canSee(e2e.player(MOD))), "the server says the watcher can't see them");
        e2e.expect(mod.hasPlayerInfo(watcherId), "the moderator still sees the watcher");

        e2e.step("the vanished moderator is out of the server list");
        Ping hidden = ping();
        e2e.expect(hidden.online() == Bukkit.getOnlinePlayers().size() - 1, "the ping count leaves the moderator out: " + hidden);
        e2e.expect(!hidden.sample().contains(MOD), "the sample leaves the moderator out: " + hidden);

        e2e.step("the action bar reminds the vanished moderator");
        mod.clearLogs();
        e2e.eventually(() -> mod.actionBarContains("You are vanished"), 6_000, "the vanish reminder: " + mod.actionBar());

        e2e.step("a player joining later never receives the moderator");
        Bot late = e2e.bot(LATE);
        e2e.sleep(1_000);
        e2e.expect(!late.everHadPlayerInfo(modId), "the late joiner never got a player info entry for the moderator");
        e2e.expect(!late.seesEntity(modId), "the late joiner does not track the moderator");
        e2e.expect(late.hasPlayerInfo(watcherId), "the late joiner does see the watcher");

        e2e.step("the vanish survives a relog without a flash");
        mod.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(MOD) == null, "the moderator left");
        Bot back = e2e.bot(MOD);
        e2e.eventually(() -> back.actionBarContains("You are vanished"), 6_000, "still vanished after the relog: " + back.actionBar());
        e2e.expect(!late.everHadPlayerInfo(modId), "the late joiner never saw the moderator rejoin");
        e2e.expect(!watcher.hasPlayerInfo(modId) && !watcher.seesEntity(modId), "the watcher does not see the rejoined moderator");

        e2e.step("unvanish shows the moderator again");
        back.clearLogs();
        back.command("vanish");
        expectSaw(e2e, back, "You are visible again");
        e2e.eventually(() -> !staff.vanish().vanished(modId), "VanishStatus reports visible");
        e2e.eventually(() -> watcher.hasPlayerInfo(modId) && late.hasPlayerInfo(modId), "both players get the moderator back in tab");
        e2e.eventually(() -> watcher.seesEntity(modId) && late.seesEntity(modId), "both players track the moderator again");
        e2e.eventually(() -> {
            try {
                return ping().online() == Bukkit.getOnlinePlayers().size();
            } catch (Exception e) {
                return false;
            }
        }, "the ping counts the moderator again");

        e2e.step("players without permission can't vanish");
        watcher.command("vanish");
        e2e.sleep(800);
        e2e.expect(!staff.vanish().vanished(watcherId), "the watcher did not vanish");
        e2e.console("deop " + MOD);
    }

    /** With join and leave messages on, a vanished moderator comes and goes without a message; a visible one doesn't. */
    static void vanishJoinMessages(E2E e2e) throws Exception {
        String MOD = e2e.name("VanMsg");
        String WATCHER = e2e.name("MsgWatch");
        java.nio.file.Path config = e2e.services().plugin().getDataFolder().toPath().resolve("features/extras.yml");
        String original = java.nio.file.Files.readString(config);
        e2e.expect(original.contains("join: false") && original.contains("quit: false"), "the default extras messages");
        try {
            java.nio.file.Files.writeString(config, original.replace("join: false", "join: true").replace("quit: false", "quit: true"));
            e2e.console("sift reload");
            Bot mod = e2e.bot(MOD);
            e2e.console("op " + MOD);
            Bot watcher = e2e.bot(WATCHER);
            UUID modId = e2e.uuid(MOD);
            StaffFeature staff = staff();

            e2e.step("a vanished moderator leaves without a message");
            mod.command("vanish");
            e2e.eventually(() -> staff.vanish().vanished(modId), "the moderator is vanished");
            watcher.clearLogs();
            mod.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(MOD) == null, "the moderator left");
            e2e.sleep(1_000);
            e2e.expect(!watcher.chatContains(MOD + " left"), "no leave message: " + watcher.chat());

            e2e.step("and comes back without a message");
            Bot back = e2e.bot(MOD);
            e2e.eventually(() -> back.actionBarContains("You are vanished"), 6_000, "still vanished: " + back.actionBar());
            e2e.expect(!watcher.chatContains(MOD + " joined"), "no join message: " + watcher.chat());

            e2e.step("once visible again, leaving is announced as usual");
            back.command("vanish");
            e2e.eventually(() -> !staff.vanish().vanished(modId), "the moderator is visible");
            back.quit();
            e2e.eventually(() -> watcher.chatContains(MOD + " left"), "the leave message: " + watcher.chat());
        } finally {
            java.nio.file.Files.writeString(config, original);
            e2e.console("sift reload");
            e2e.console("deop " + MOD);
        }
    }

    // ------------------------------------------------------------------ reports

    private static final Pattern REPORT_ID = Pattern.compile("Report #(\\d+)");

    static void report(E2E e2e) throws Exception {
        String MOD = e2e.name("RepMod");
        String REPORTER = e2e.name("Reporter");
        String SUSPECT = e2e.name("Suspect");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        Bot reporter = e2e.bot(REPORTER);
        e2e.bot(SUSPECT);

        e2e.step("the menu entry opens the form; a short reason is refused");
        reporter.command("menu");
        e2e.dialog(reporter, "SiftVanilla");
        e2e.click(reporter, "Report a player");
        e2e.dialog(reporter, "Report a player");
        e2e.click(reporter, "Send report", Map.of("player", SUSPECT, "reason", "x"));
        Bot.SeenDialog retry = e2e.dialog(reporter, "Report a player");
        e2e.expect(retry.bodyText().contains("Tell staff a bit more"), "the error in the form: " + retry.body());

        e2e.step("a valid report reaches staff with a clickable line");
        mod.clearLogs();
        e2e.click(reporter, "Send report", Map.of("player", SUSPECT, "reason", "flying over the spawn"));
        expectSaw(e2e, reporter, "Thanks. Staff will look at your report about " + SUSPECT);
        e2e.eventually(() -> mod.chatContains("reported " + SUSPECT), "staff are told: " + mod.chat());
        String line = mod.chat().stream().filter(text -> text.contains("reported " + SUSPECT)).findFirst().orElseThrow();
        e2e.expect(line.contains("flying over the spawn") && line.contains(REPORTER), "the notification: " + line);
        Matcher matcher = REPORT_ID.matcher(line);
        e2e.expect(matcher.find(), "a report number in " + line);
        String id = matcher.group(1);

        e2e.step("rules: duplicate, too short, yourself, cooldown");
        reporter.clearLogs();
        reporter.command("report " + SUSPECT + " still flying around");
        expectSaw(e2e, reporter, "You already reported " + SUSPECT);
        reporter.command("report " + MOD + " hi");
        expectSaw(e2e, reporter, "Tell staff a bit more");
        reporter.command("report " + REPORTER + " reporting myself");
        expectSaw(e2e, reporter, "You can't do that to yourself.");
        reporter.command("report " + MOD + " abusing their power");
        expectSaw(e2e, reporter, "before doing that again");

        e2e.step("the notification's command opens the report");
        mod.clearLogs();
        mod.command("reports " + id);
        Bot.SeenDialog detail = e2e.dialog(mod, "Report #" + id);
        e2e.expect(detail.bodyText().contains("flying over the spawn") && detail.bodyText().contains(REPORTER), "the details: " + detail.body());
        e2e.expect(detail.button("Teleport to " + SUSPECT) != null && detail.button("Mark handled") != null && detail.button("Dismiss") != null,
            "the actions: " + detail.buttons());

        e2e.step("teleport to the reported player");
        e2e.click(mod, "Teleport to " + SUSPECT);
        expectSaw(e2e, mod, "Teleported to " + SUSPECT);

        e2e.step("the list shows the report; mark it handled");
        mod.clearLogs();
        reporter.clearLogs();
        mod.command("reports");
        Bot.SeenDialog list = e2e.dialog(mod, "Open reports");
        e2e.expect(list.bodyText().contains("#" + id), "the report in the list: " + list.body());
        e2e.click(mod, "#" + id + " " + SUSPECT);
        e2e.dialog(mod, "Report #" + id);
        e2e.click(mod, "Mark handled");
        expectSaw(e2e, mod, "Report #" + id + " is marked handled.");
        expectSaw(e2e, reporter, "Staff looked at your report about " + SUSPECT);
        Bot.SeenDialog after = e2e.dialog(mod, "Open reports");
        e2e.expect(!after.bodyText().contains("#" + id + " "), "the report left the list: " + after.body());

        e2e.step("a closed report can't be handled again");
        mod.clearLogs();
        mod.command("reports " + id);
        expectSaw(e2e, mod, "Report #" + id + " is already closed.");

        e2e.step("players can't open the reports list");
        reporter.clearLogs();
        int dialogs = reporter.dialogs().size();
        reporter.command("reports");
        e2e.sleep(800);
        e2e.expect(reporter.dialogs().size() == dialogs, "no reports dialog for a player");
        e2e.console("deop " + MOD);
    }

    // ------------------------------------------------------------------ inventory inspection

    static void invsee(E2E e2e) throws Exception {
        String MOD = e2e.name("InvMod");
        String TARGET = e2e.name("InvTgt");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        e2e.bot(TARGET);
        e2e.onPlayer(TARGET, () -> {
            PlayerInventory inventory = e2e.player(TARGET).getInventory();
            inventory.clear();
            inventory.setItem(9, new ItemStack(Material.DIAMOND, 5));
            inventory.setItem(0, new ItemStack(Material.EMERALD, 3));
            e2e.player(TARGET).getEnderChest().setItem(4, new ItemStack(Material.GOLD_INGOT, 7));
            return null;
        });
        e2e.onPlayer(MOD, () -> {
            e2e.player(MOD).getInventory().clear();
            return null;
        });

        e2e.step("open the inventory view");
        mod.command("invsee " + TARGET);
        e2e.eventually(() -> mod.screen() != null && mod.screen().title().equals(TARGET + "'s inventory"),
            "the inventory view opens: " + mod.screen());
        e2e.eventually(() -> mod.screenItems().containsKey(0) && mod.screenItems().containsKey(27), "storage and hotbar items are shown: "
            + mod.screenItems().keySet());
        e2e.expect(mod.screenItems().get(0).getCount() == 5, "five diamonds in the first storage slot");

        e2e.step("clicking an item takes it");
        mod.clearLogs();
        mod.clickSlot(0);
        e2e.eventually(() -> e2e.onPlayer(TARGET, () -> {
            ItemStack left = e2e.player(TARGET).getInventory().getItem(9);
            return left == null || left.isEmpty();
        }), "the diamonds left the target");
        e2e.eventually(() -> e2e.onPlayer(MOD, () -> e2e.player(MOD).getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 5)),
            "the moderator has the diamonds");
        expectSaw(e2e, mod, "Took 5");
        e2e.eventually(() -> !mod.screenItems().containsKey(0), "the view refreshed");

        e2e.step("a slot that changed since the snapshot is not taken");
        e2e.onPlayer(TARGET, () -> {
            e2e.player(TARGET).getInventory().setItem(0, new ItemStack(Material.STONE, 1));
            return null;
        });
        mod.clearLogs();
        mod.clickSlot(27);
        expectSaw(e2e, mod, "That slot changed");
        e2e.expect(e2e.onPlayer(TARGET, () -> e2e.player(TARGET).getInventory().getItem(0).getType() == Material.STONE),
            "the target keeps the new item");
        e2e.expect(!e2e.onPlayer(MOD, () -> e2e.player(MOD).getInventory().contains(Material.EMERALD)), "no emerald was taken");
        e2e.expect(!e2e.onPlayer(MOD, () -> e2e.player(MOD).getInventory().contains(Material.STONE)), "no stone was taken");

        e2e.step("the ender chest view");
        mod.closeScreen();
        mod.command("ecsee " + TARGET);
        e2e.eventually(() -> mod.screen() != null && mod.screen().title().equals(TARGET + "'s ender chest"),
            "the ender chest view opens: " + mod.screen());
        e2e.eventually(() -> mod.screenItems().containsKey(4) && mod.screenItems().get(4).getCount() == 7, "the gold is shown");

        e2e.step("clear asks first, then deletes");
        mod.closeScreen();
        mod.command("invsee " + TARGET);
        e2e.eventually(() -> mod.screen() != null && mod.screen().title().equals(TARGET + "'s inventory"), "the inventory view again");
        mod.clearLogs();
        mod.clickSlot(50);
        e2e.dialog(mod, "Delete items?");
        e2e.click(mod, "Delete");
        expectSaw(e2e, mod, "Deleted");
        e2e.expect(e2e.onPlayer(TARGET, () -> e2e.player(TARGET).getInventory().isEmpty()), "the target's inventory is empty");
        e2e.expect(e2e.onPlayer(TARGET, () -> e2e.player(TARGET).getEnderChest().getItem(4) != null), "the ender chest is untouched");
        mod.closeScreen();
        e2e.console("deop " + MOD);
    }

    // ------------------------------------------------------------------ staff chat and announcements

    static void staffChat(E2E e2e) throws Exception {
        String MOD = e2e.name("ChatMod");
        String MATE = e2e.name("ChatMate");
        String PLAYER = e2e.name("Player");
        Bot mod = e2e.bot(MOD);
        Bot mate = e2e.bot(MATE);
        Bot player = e2e.bot(PLAYER);
        e2e.console("op " + MOD);
        e2e.console("op " + MATE);

        e2e.step("/sc <message> reaches staff only");
        mate.clearLogs();
        player.clearLogs();
        mod.command("sc meeting at spawn");
        e2e.eventually(() -> mate.chatContains("Staff " + MOD + ": meeting at spawn"), "staff see staff chat: " + mate.chat());
        e2e.sleep(500);
        e2e.expect(!player.chatContains("meeting at spawn"), "players don't: " + player.chat());

        e2e.step("/sc toggles staff chat mode for normal chat");
        mod.clearLogs();
        mod.command("sc");
        expectSaw(e2e, mod, "Staff chat on");
        mate.clearLogs();
        player.clearLogs();
        mod.chat("secret plans");
        e2e.eventually(() -> mate.chatContains("Staff " + MOD + ": secret plans"), "the toggled message goes to staff: " + mate.chat());
        e2e.sleep(500);
        e2e.expect(!player.chatContains("secret plans"), "and not to players: " + player.chat());
        mod.command("sc");
        expectSaw(e2e, mod, "Staff chat off");
        player.clearLogs();
        mod.chat("public again");
        e2e.eventually(() -> player.chatContains("public again"), "chat is public after the toggle: " + player.chat());

        e2e.step("players can't use staff chat");
        mate.clearLogs();
        player.command("sc let me in");
        e2e.sleep(800);
        e2e.expect(!mate.chatContains("let me in"), "nothing reached staff chat");

        e2e.step("broadcast");
        player.clearLogs();
        e2e.console("broadcast Restart in <red>five</red> minutes");
        e2e.eventually(() -> saw(player, "Restart in <red>five</red> minutes"), "the broadcast, tags shown literally: " + player.chat());

        e2e.step("clearchat");
        player.clearLogs();
        mate.clearLogs();
        mod.command("clearchat");
        e2e.eventually(() -> player.chatContains("Chat was cleared by staff."), "players see the clear notice: " + player.chat());
        e2e.expect(player.chat().stream().anyMatch(text -> text.chars().filter(c -> c == '\n').count() >= 50), "blank lines were sent");
        e2e.eventually(() -> mod.chatContains("Cleared chat for"), "the moderator gets a count: " + mod.chat());
        e2e.console("deop " + MOD);
        e2e.console("deop " + MATE);
    }

    // ------------------------------------------------------------------ lookups

    static void lookups(E2E e2e) throws Exception {
        String MOD = e2e.name("LookMod");
        String TARGET = e2e.name("Looked");
        Bot mod = e2e.bot(MOD);
        e2e.console("op " + MOD);
        e2e.bot(TARGET);
        UUID targetId = e2e.uuid(TARGET);

        e2e.step("whois");
        mod.command("whois " + TARGET);
        Bot.SeenDialog whois = e2e.dialog(mod, TARGET);
        String body = whois.bodyText();
        e2e.expect(body.contains(targetId.toString()), "the uuid: " + whois.body());
        e2e.expect(body.contains("Ping") && body.contains("Game mode"), "where they are right now: " + whois.body());
        e2e.expect(body.contains("no punishments"), "a clean record: " + whois.body());
        e2e.expect(body.contains("$0 and 0 shards"), "the balance: " + whois.body());
        e2e.expect(whois.button("Punishment history") != null, "a history button: " + whois.buttons());

        e2e.step("the history opened from whois goes back to it");
        e2e.click(mod, "Punishment history");
        Bot.SeenDialog history = e2e.dialog(mod, "History of " + TARGET);
        e2e.expect(history.button("Back") != null && history.button("Close") == null, "Back instead of Close: " + history.buttons());
        e2e.click(mod, "Back");
        Bot.SeenDialog again = e2e.dialog(mod, TARGET);
        e2e.expect(again.button("Punishment history") != null, "the whois view again: " + again.title());

        e2e.step("alts: bots share one address");
        mod.clearLogs();
        mod.command("alts " + TARGET);
        e2e.eventually(() -> mod.chatContains("Accounts on " + TARGET + "'s address"), "the alts header: " + mod.chat());
        e2e.eventually(() -> mod.chatContains(MOD), "the moderator's own account shares the address: " + mod.chat());
        e2e.console("deop " + MOD);
    }
}
