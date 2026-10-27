package net.siftvanilla.siftcore.feature.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Remember team chat mode ({@code team-chat-sticky}) across sessions, with the real settings store: it comes back only
 * when it was on at the end of the last session, in the same membership. A mode that was off for a whole session, or
 * that belongs to a membership the player lost while offline, never comes back when the setting is turned on later.
 */
class TeamChatStickyTest {

    private static final Logger LOGGER = Logger.getLogger("team-chat-sticky-test");
    private static final String RESTORED = "Team chat is still on.";

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;
    private TeamRegistry registry;
    private TeamChat chat;
    private final UUID owner = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final List<String> chatLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void open() throws Exception {
        this.database = TeamsTestDatabase.open(this.dir);
        this.settings = new PlayerSettings(this.database, null, LOGGER);
        Relations relations = new Relations();
        SharedSettings.register(this.settings, relations);
        TeamPrefs.register(this.settings, TeamsFeature.SPY, relations);
        this.registry = new TeamRegistry();
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/teams.yml"), TeamsMessages.class), new Sounds());
        this.chat = new TeamChat(this.registry, messenger, this.settings, TeamsFeature.SPY, null, MuteStatus.NONE);
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private Player online() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getUniqueId" -> this.player;
                case "getName" -> "Stickler";
                case "isOnline" -> true;
                case "hasPermission" -> false;
                case "sendMessage" -> {
                    if (args.length == 1 && args[0] instanceof Component line) {
                        this.chatLines.add(PlainTextComponentSerializer.plainText().serialize(line));
                    }
                    yield null;
                }
                case "hashCode" -> this.player.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player(" + this.player + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private Team join(long joined) {
        Team team = this.registry.get(7).orElseGet(() -> Team.create(7, "Seven", this.owner, 10, false))
            .withMember(new TeamMember(this.player, TeamRole.MEMBER, joined));
        this.registry.put(team);
        return team;
    }

    /** A login: the settings are read (after every queued write), then the join handler runs. */
    private Player login() throws Exception {
        this.settings.load(this.player).get(10, TimeUnit.SECONDS);
        this.settings.joined(this.player);
        this.chatLines.clear();
        Player online = online();
        this.chat.restore(online);
        return online;
    }

    /** A logout: the quit handler runs while the settings are still loaded, then core forgets them. */
    private void logout() {
        this.chat.quit(this.player);
        this.settings.forget(this.player);
    }

    private boolean told() {
        return this.chatLines.stream().anyMatch(line -> line.startsWith(RESTORED));
    }

    private void sticky(boolean on) {
        this.settings.set(this.player, TeamPrefs.CHAT_STICKY, on);
    }

    private String remembered() throws Exception {
        return this.settings.stored(this.player).get(10, TimeUnit.SECONDS).getOrDefault(TeamChat.MODE_KEY, TeamChat.MODE_OFF);
    }

    @Test
    void comesBackWhenOnAtTheEndOfTheSession() throws Exception {
        Team team = join(100);
        login();
        sticky(true);
        assertTrue(this.chat.toggle(this.player, team));
        logout();
        login();
        assertTrue(this.chat.inChatMode(this.player), "team chat mode is back");
        assertTrue(told(), "and the player is told: " + this.chatLines);
    }

    @Test
    void aSessionWithTeamChatOffIsNeverUndone() throws Exception {
        Team team = join(100);
        login();
        assertTrue(this.chat.toggle(this.player, team), "team chat on while the setting is off");
        logout();
        login();
        assertFalse(this.chat.inChatMode(this.player), "not kept with the setting off");
        assertEquals(TeamChat.MODE_OFF, remembered(), "and forgotten at that login");
        // A whole session in public chat, then the player turns the setting on and relogs.
        sticky(true);
        logout();
        login();
        assertFalse(this.chat.inChatMode(this.player), "a mode that was off for a whole session never comes back");
        assertFalse(told(), "nothing said: " + this.chatLines);
    }

    @Test
    void turnedOffBeforeLeavingIsNotRestored() throws Exception {
        Team team = join(100);
        login();
        sticky(true);
        this.chat.toggle(this.player, team);
        this.chat.toggle(this.player, team);
        logout();
        assertEquals(TeamChat.MODE_OFF, remembered());
        login();
        assertFalse(this.chat.inChatMode(this.player));
    }

    @Test
    void removedWhileOfflineAndInvitedBackStartsFresh() throws Exception {
        Team team = join(100);
        login();
        sticky(true);
        this.chat.toggle(this.player, team);
        logout();
        // Kicked while offline: nothing is known (or written) for them; then invited back into the same team.
        assertFalse(this.chat.off(this.player), "not in team chat mode while offline");
        this.registry.put(this.registry.get(7).orElseThrow().withoutMember(this.player));
        join(500);
        login();
        assertFalse(this.chat.inChatMode(this.player), "the old membership's mode does not come back");
        assertEquals(TeamChat.MODE_OFF, remembered(), "and it is forgotten at that login");
    }

    @Test
    void leavingTheTeamForgetsIt() throws Exception {
        Team team = join(100);
        login();
        sticky(true);
        this.chat.toggle(this.player, team);
        assertTrue(this.chat.off(this.player), "left the team with team chat on");
        assertEquals(TeamChat.MODE_OFF, remembered());
    }
}
