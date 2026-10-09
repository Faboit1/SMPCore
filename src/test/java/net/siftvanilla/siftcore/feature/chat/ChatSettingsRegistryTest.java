package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The chat settings as the registry holds them: the Chat group in catalog order, social spy in Staff, which settings
 * are offered under which config, the old switch values that still read, and the friend options.
 */
class ChatSettingsRegistryTest {

    private static final UUID PLAYER = UUID.randomUUID();
    /** A stats recorder that is not {@link StatsRecorder#NONE} (the stats feature is on). */
    private static final StatsRecorder STATS = new StatsRecorder() {
        @Override
        public void add(UUID player, Stat stat, long amount) {
        }

        @Override
        public void kill(UUID killer, UUID victim) {
        }

        @Override
        public void death(UUID victim) {
        }

        @Override
        public long get(UUID player, Stat stat) {
            return 0;
        }

        @Override
        public int streak(UUID player) {
            return 0;
        }

        @Override
        public int bestStreak(UUID player) {
            return 0;
        }
    };
    private static final FriendLookup FRIENDS = new FriendLookup() {
        @Override
        public boolean friends(UUID a, UUID b) {
            return false;
        }

        @Override
        public Set<UUID> friendsOf(UUID player) {
            return Set.of();
        }
    };

    private final AtomicReference<ChatSettings> config = new AtomicReference<>();
    /** Whether SiftCore's /msg and /r are registered (commands.yml can turn them off). */
    private final AtomicBoolean msg = new AtomicBoolean(true);
    private final AtomicBoolean reply = new AtomicBoolean(true);
    private PlayerSettings settings;
    private Relations relations;

    private static ChatSettings parse(Map<String, ?> changes) throws Exception {
        YamlConfiguration yaml = ChatResourcesTest.yaml("features/chat.yml");
        changes.forEach(yaml::set);
        ConfigReader reader = new ConfigReader("features/chat.yml", yaml);
        ChatSettings parsed = ChatSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return parsed;
    }

    @BeforeEach
    void register() throws Exception {
        this.config.set(parse(Map.of()));
        this.settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        this.relations = new Relations();
        ChatFeature.registerSettings(this.settings, this.relations, STATS, this.config::get,
            () -> ChatRules.messagesOffered(this.msg.get(), this.reply.get(), false),
            () -> ChatRules.messagesOffered(this.msg.get(), this.reply.get(), true));
    }

    private List<String> ids(String category) {
        return this.settings.registry().in(category).stream().map(Registry.Entry::id).toList();
    }

    private List<String> offered(String category) {
        return this.settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    private Registry.Entry<?> entry(String id) {
        return this.settings.registry().entry(id);
    }

    private void friendsOn() {
        this.relations.bind(FRIENDS, TeamLookup.NONE, IgnoreLookup.NONE);
    }

    @Test
    void theChatGroupHoldsItsSettingsInCatalogOrderAndSpyIsStaff() {
        assertEquals(List.of("mentions", "private-messages", "public-chat", "pm-alert", "mention-from", "mention-highlight",
            "chat-filter-strict", "reply-target", "mention-plain-names", "chat-hide-new"), ids("chat"));
        assertEquals(List.of("social-spy"), ids("staff"));
        assertTrue(this.settings.hasReader(SharedSettings.SOUND_MENTION) && this.settings.hasReader(SharedSettings.SOUND_PM)
            && this.settings.hasReader(SharedSettings.BALANCE_PRIVACY), "chat acts on the mention and message sounds and balance privacy");
        assertFalse(entry("private-messages").placeholder(), "who can message me is private");
        assertFalse(entry("mention-from").placeholder(), "who can ping me is private");
        assertFalse(entry("social-spy").placeholder(), "staff settings stay out of placeholders");
        assertTrue(entry("mentions").placeholder());
    }

    @Test
    void settingsAreOfferedOnlyWhenTheServerHasWhatTheyNeed() throws Exception {
        assertFalse(offered("chat").contains("mention-from"), "no friends: nothing to choose but everyone");
        friendsOn();
        assertEquals(ids("chat"), offered("chat"), "everything with the default config and friends");
        this.config.set(parse(Map.of("mentions.enabled", false)));
        assertEquals(List.of("private-messages", "public-chat", "pm-alert", "chat-filter-strict", "reply-target", "chat-hide-new"),
            offered("chat"), "mentions off removes every mention setting");
        this.config.set(parse(Map.of("mentions.plain-names", false, "filter.strict-words", List.of(), "new-players.playtime", "0s")));
        assertFalse(offered("chat").contains("mention-plain-names"));
        assertFalse(offered("chat").contains("chat-filter-strict"));
        assertFalse(offered("chat").contains("chat-hide-new"));
        PlayerSettings withoutStats = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        ChatFeature.registerSettings(withoutStats, this.relations, StatsRecorder.NONE, () -> {
            try {
                return parse(Map.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, () -> true, () -> true);
        assertFalse(withoutStats.registry().entry("chat-hide-new").offered(), "no playtime without the stats feature");
    }

    @Test
    void privateMessageSettingsNeedSiftCoresMsgAndTheReplyTargetItsReplyCommand() {
        friendsOn();
        this.reply.set(false);
        assertFalse(offered("chat").contains("reply-target"), "/r turned off in commands.yml: no /r target");
        assertTrue(offered("chat").containsAll(List.of("private-messages", "pm-alert")), "/msg still works");
        assertTrue(entry("social-spy").offered());
        this.reply.set(true);
        this.msg.set(false);
        assertEquals(List.of("mentions", "public-chat", "mention-from", "mention-highlight", "chat-filter-strict",
            "mention-plain-names", "chat-hide-new"), offered("chat"), "/msg off (or left to another plugin): no message settings");
        assertFalse(entry("social-spy").offered(), "nothing to spy on");
        this.msg.set(true);
        assertEquals(ids("chat"), offered("chat"));
    }

    @Test
    void theOldSwitchValuesStillRead() {
        assertEquals(AlertStyle.ACTIONBAR, PlayerSettings.configValue(entry("mentions"), "true"));
        assertEquals(AlertStyle.OFF, PlayerSettings.configValue(entry("mentions"), "false"));
        assertEquals(AlertStyle.TITLE, PlayerSettings.configValue(entry("mentions"), " Title "));
        assertEquals(Audience.EVERYONE, PlayerSettings.configValue(entry("private-messages"), "true"));
        assertEquals(Audience.NOBODY, PlayerSettings.configValue(entry("private-messages"), "FALSE"));
        assertNull(PlayerSettings.configValue(entry("pm-alert"), "true"), "new choices have no old values");
        // An unquoted "off" in features/settings.yml reaches the settings as the YAML boolean false.
        assertEquals(AlertStyle.OFF, PlayerSettings.configValue(entry("pm-alert"), "false"));
        assertEquals(MentionHighlight.OFF, PlayerSettings.configValue(entry("mention-highlight"), "false"));
        assertNull(PlayerSettings.configValue(entry("reply-target"), "false"), "no off option, no alias");
        assertEquals("actionbar", ChatFeature.MENTIONS.encode(AlertStyle.ACTIONBAR), "rows are rewritten with option ids");
        // Server config written for the old switches keeps working.
        this.settings.overrides(new Overrides(Map.of("mentions", "false"), Map.of("private-messages", "false"), Set.of()));
        assertEquals(AlertStyle.OFF, this.settings.get(PLAYER, ChatFeature.MENTIONS));
        assertEquals(Audience.NOBODY, this.settings.get(PLAYER, ChatFeature.PRIVATE_MESSAGES));
        assertTrue(this.settings.locked(ChatFeature.PRIVATE_MESSAGES));
    }

    @Test
    void friendOptionsNeedFriendsAndFallBackSafely() {
        this.settings.overrides(new Overrides(Map.of("private-messages", "friends", "mention-from", "friends-team"), Map.of(), Set.of()));
        assertEquals(Audience.NOBODY, this.settings.get(PLAYER, ChatFeature.PRIVATE_MESSAGES),
            "friends only reads as nobody without friends, so turning friends off never opens messages");
        assertEquals(Audience.EVERYONE, this.settings.get(PLAYER, ChatFeature.MENTION_FROM), "who can ping me falls back to everyone");
        assertEquals(List.of("everyone", "nobody"), optionIds("private-messages"));
        friendsOn();
        assertEquals(Audience.FRIENDS, this.settings.get(PLAYER, ChatFeature.PRIVATE_MESSAGES));
        assertEquals(Audience.FRIENDS_TEAM, this.settings.get(PLAYER, ChatFeature.MENTION_FROM));
        assertEquals(List.of("everyone", "friends-team", "friends", "nobody"), optionIds("private-messages"));
        assertEquals(List.of("everyone", "friends-team", "friends"), optionIds("mention-from"), "no nobody: turn alerts off instead");
    }

    @Test
    void choiceOptionsMatchTheCatalog() {
        assertEquals(List.of("actionbar", "chat", "title", "off"), ChatFeature.MENTIONS.optionIds());
        assertEquals(List.of("off", "actionbar", "title"), ChatFeature.PM_ALERT.optionIds());
        assertEquals(List.of("bold", "underline", "off"), ChatFeature.MENTION_HIGHLIGHT.optionIds());
        assertEquals(List.of("last-conversation", "last-received"), ChatFeature.REPLY_TARGET.optionIds());
        assertEquals(MentionHighlight.BOLD, ChatFeature.MENTION_HIGHLIGHT.defaultValue());
        assertEquals(AlertStyle.OFF, ChatFeature.PM_ALERT.defaultValue());
        assertTrue(ChatFeature.PUBLIC_CHAT.defaultOn() && ChatFeature.MENTION_PLAIN_NAMES.defaultOn());
        assertFalse(ChatFeature.CHAT_FILTER_STRICT.defaultOn() || ChatFeature.CHAT_HIDE_NEW.defaultOn());
        assertEquals(Duration.ofMinutes(30), this.config.get().newPlayerPlaytime());
    }

    @SuppressWarnings("unchecked")
    private List<String> optionIds(String id) {
        Registry.Entry<Object> entry = (Registry.Entry<Object>) entry(id);
        Collection<Choice.Option<Object>> options = this.settings.options(entry, permission -> true);
        return options.stream().map(Choice.Option::id).toList();
    }
}
