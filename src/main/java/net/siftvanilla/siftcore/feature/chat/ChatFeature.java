package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.TextChecks;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;

/**
 * Public chat (format, hover cards, {@code [item]}, mentions, anti-spam, the word filter, chat lock and slow mode),
 * private messages ({@code /msg}, {@code /r}, social spy) and ignore lists ({@code /ignore}).
 * <p>
 * Other features consult it through {@link #ignores()}: teleport requests and friend requests from a player you
 * ignore never reach you; and through {@link #textChecks()}: nicknames and join messages pass the word filter and the
 * link check. It consults rank labels, teams, balances and stats for the hover card, mutes and vanish from the staff
 * tools, AFK status, and cosmetics (nicknames, chat tags and chat colours in chat and private messages). Names in
 * public chat open the player's profile when the friends feature provides {@code /profile}.
 * <p>
 * Players shape their chat in the Chat group of the settings: how and from whom mentions alert them, who may message
 * them, public chat on or off, a private message pop-up, their name highlighted, a stricter word filter, who
 * {@code /r} answers, pings on the bare name, and hiding brand-new players. Who-can settings ask
 * {@code services.relations()}.
 */
public final class ChatFeature implements Feature {

    /** The chat group of the settings dialog (the shared {@link SettingCategories#CHAT}). */
    public static final SettingCategory SETTINGS = SettingCategories.CHAT;
    /**
     * How a player is told someone mentioned them: above the hotbar, in chat, as a title or not at all. Was the
     * {@code mentions} switch (on: above the hotbar, off: not at all).
     */
    public static final Choice<AlertStyle> MENTIONS = Choices.alert("mentions", AlertStyle.ACTIONBAR,
            AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.TITLE, AlertStyle.OFF)
        .legacyValue("true", AlertStyle.ACTIONBAR.id()).legacyValue("false", AlertStyle.OFF.id())
        .text(ChatMessages.SETTING_MENTIONS, ChatMessages.SETTING_MENTIONS_DESCRIPTION).build();
    /**
     * Who can send the player private messages (people they wrote to recently and staff always can). Was the
     * {@code private-messages} switch (on: everyone, off: nobody); {@code /msgtoggle} switches everyone and nobody.
     */
    public static final Choice<Audience> PRIVATE_MESSAGES = audience("private-messages", Audience.NOBODY.id(), true)
        .legacyValue("true", Audience.EVERYONE.id()).legacyValue("false", Audience.NOBODY.id())
        .text(ChatMessages.SETTING_PRIVATE, ChatMessages.SETTING_PRIVATE_DESCRIPTION).build();
    /** Whether the player reads other players' public chat (private messages, team chat and notices still arrive). */
    public static final Toggle PUBLIC_CHAT = new Toggle("public-chat", true, ChatMessages.SETTING_PUBLIC,
        ChatMessages.SETTING_PUBLIC_DESCRIPTION, null);
    /**
     * An extra pop-up for a new private message, above the hotbar or as a title (the chat line always arrives). Like
     * every chat choice with an {@code off} option, {@code false} reads as off: an unquoted {@code off} in
     * {@code features/settings.yml} is the YAML boolean.
     */
    public static final Choice<AlertStyle> PM_ALERT = Choices.alert("pm-alert", AlertStyle.OFF,
            AlertStyle.OFF, AlertStyle.ACTIONBAR, AlertStyle.TITLE)
        .legacyValue("false", AlertStyle.OFF.id())
        .text(ChatMessages.SETTING_PM_ALERT, ChatMessages.SETTING_PM_ALERT_DESCRIPTION).build();
    /** Whose mentions alert the player (the chat line shows either way). */
    public static final Choice<Audience> MENTION_FROM = audience("mention-from", null, false)
        .text(ChatMessages.SETTING_MENTION_FROM, ChatMessages.SETTING_MENTION_FROM_DESCRIPTION).build();
    /** How the player's own name stands out in public lines that mention them. */
    public static final Choice<MentionHighlight> MENTION_HIGHLIGHT = Choice.ofEnum("mention-highlight", MentionHighlight.class,
            MentionHighlight::id, MentionHighlight.BOLD)
        .option(MentionHighlight.BOLD, MentionHighlight.BOLD.label())
        .option(MentionHighlight.UNDERLINE, MentionHighlight.UNDERLINE.label())
        .option(MentionHighlight.OFF, MentionHighlight.OFF.label())
        .legacyValue("false", MentionHighlight.OFF.id())
        .text(ChatMessages.SETTING_HIGHLIGHT, ChatMessages.SETTING_HIGHLIGHT_DESCRIPTION).build();
    /** The player also stops reading the milder words of {@code filter.strict-words}. */
    public static final Toggle CHAT_FILTER_STRICT = new Toggle("chat-filter-strict", false, ChatMessages.SETTING_STRICT,
        ChatMessages.SETTING_STRICT_DESCRIPTION, null);
    /** Who {@code /r} answers. */
    public static final Choice<ReplyTarget> REPLY_TARGET = Choice.ofEnum("reply-target", ReplyTarget.class, ReplyTarget::id,
            ReplyTarget.LAST_CONVERSATION)
        .option(ReplyTarget.LAST_CONVERSATION, ReplyTarget.LAST_CONVERSATION.label())
        .option(ReplyTarget.LAST_RECEIVED, ReplyTarget.LAST_RECEIVED.label())
        .text(ChatMessages.SETTING_REPLY, ChatMessages.SETTING_REPLY_DESCRIPTION).build();
    /** Whether the player's bare name (without {@code @}) alerts them too. */
    public static final Toggle MENTION_PLAIN_NAMES = new Toggle("mention-plain-names", true, ChatMessages.SETTING_PLAIN_NAMES,
        ChatMessages.SETTING_PLAIN_NAMES_DESCRIPTION, null);
    /** The player stops reading public chat of players with very little playtime ({@code new-players.playtime}). */
    public static final Toggle CHAT_HIDE_NEW = new Toggle("chat-hide-new", false, ChatMessages.SETTING_HIDE_NEW,
        ChatMessages.SETTING_HIDE_NEW_DESCRIPTION, null);
    /** Staff: see private messages between other players (in the Staff group). */
    public static final Toggle SOCIAL_SPY = new Toggle("social-spy", false, ChatMessages.SETTING_SPY,
        ChatMessages.SETTING_SPY_DESCRIPTION, ChatNodes.SOCIALSPY);

    private static final Duration SWEEP = Duration.ofMinutes(1);
    private static final UUID SELF_TEST_PLAYER = new UUID(0L, 1L);

    private final Services services;
    private final Setting<ChatSettings> settings;
    private final IgnoreList ignores;
    private final Conversations conversations;
    private final MessageScreen publicScreen;
    private final MessageScreen privateScreen;
    private final ChatModeration moderation = new ChatModeration();
    private final ChatListener listener;
    private final PrivateMessages messages;
    private final ChatCommands commands;
    private Task sweeper = Task.NONE;
    /**
     * What a click on a name in public chat runs to open a profile ({@code "/profile "}, or the namespaced form while
     * another plugin holds {@code /profile}), or null while SiftCore has no profiles (friends off, {@code /profile}
     * turned off in {@code commands.yml}); checked once the server has registered every command ({@link #findCommands}).
     */
    private volatile String profileCommand;
    /** Whether SiftCore's {@code /msg} is registered: the private message settings need it (checked like profiles). */
    private volatile boolean messaging = true;
    /** Whether SiftCore's {@code /r} is registered: the {@code /r} target setting needs it. */
    private volatile boolean replying = true;

    /**
     * @param ranks  rank labels in chat and on the hover card (integrations)
     * @param teams  the team on the hover card (teams)
     * @param stats  kills and playtime on the hover card (stats)
     * @param mutes  muted players can't send private messages (staff tools)
     * @param vanish vanished staff can't be messaged by players who can't see them (staff tools)
     * @param afk       senders are told when the player they write to or mention is AFK (AFK)
     * @param cosmetics nicknames, chat tags and chat colours (cosmetics; built later, so late-bound)
     */
    public ChatFeature(Services services, List<ConfigProblem> problems, Ranks ranks, TeamLookup teams, StatsRecorder stats,
                       MuteStatus mutes, VanishStatus vanish, AfkStatus afk, Cosmetics cosmetics) {
        this.services = services;
        this.settings = services.configs().register("features/chat.yml", ChatSettings::parse, problems);
        services.lang().register(ChatMessages.class);
        registerSettings(services.settings(), services.relations(), stats, this.settings::get,
            () -> ChatRules.messagesOffered(this.messaging, this.replying, false),
            () -> ChatRules.messagesOffered(this.messaging, this.replying, true));
        ChatNodes.declare(services.permissions());
        var logger = services.plugin().getLogger();
        this.ignores = new IgnoreList(services.database(), logger);
        this.conversations = new Conversations(System::currentTimeMillis, () -> this.settings.get().replyExpiry());
        this.publicScreen = new MessageScreen(new SpamGuard(), this.settings, logger, false);
        this.privateScreen = new MessageScreen(new SpamGuard(), this.settings, logger, true);
        PlayerCards cards = new PlayerCards(services.lang(), ranks, teams, services.ledger(), stats, cosmetics, () -> this.profileCommand);
        HeldItems items = new HeldItems(services.scheduler(), services.lang(), this.settings);
        ChatListener.Links links = new ChatListener.Links(mutes, afk, vanish, cosmetics, stats);
        this.listener = new ChatListener(services, this.settings, this.publicScreen, this.privateScreen, this.moderation,
            this.ignores, cards, items, links);
        this.messages = new PrivateMessages(services, this.settings, this.ignores, this.conversations, this.privateScreen,
            cards, items, links);
        this.commands = new ChatCommands(services, this.settings, this.messages, this.ignores,
            new IgnoreViews(services, this.settings, this.ignores), this.moderation);
    }

    /**
     * Registers the chat settings in the Chat group (social spy in Staff), in the order players use them most, each
     * offered only while the server has what it needs (mentions on, a strict word list, a playtime threshold and
     * stats, friends for the friend options, SiftCore's {@code /msg} for the private message settings and its
     * {@code /r} for the reply target), and declares the shared settings chat acts on: the mention and private message
     * sounds and balance privacy (the hover card).
     *
     * @param messages whether private messages can be sent ({@code /msg} registered)
     * @param replies  whether they can be answered with {@code /r} too
     */
    static void registerSettings(PlayerSettings registry, Relations relations, StatsRecorder stats, Supplier<ChatSettings> config,
                                 BooleanSupplier messages, BooleanSupplier replies) {
        BooleanSupplier mentions = () -> config.get().mentions();
        BooleanSupplier friends = relations::friendsAvailable;
        SettingCategory chat = SETTINGS;
        registry.register(chat, MENTIONS, SettingOptions.<AlertStyle>builder().order(1).availableWhen(mentions).build());
        registry.register(chat, PRIVATE_MESSAGES, SettingOptions.<Audience>builder().order(2).availableWhen(messages)
            .optionAvailableWhen(Audience.FRIENDS_TEAM.id(), friends).optionAvailableWhen(Audience.FRIENDS.id(), friends)
            .placeholder(false).build());
        registry.register(chat, PUBLIC_CHAT, SettingOptions.<Boolean>builder().order(3).build());
        registry.register(chat, PM_ALERT, SettingOptions.<AlertStyle>builder().order(4).availableWhen(messages).build());
        registry.register(chat, MENTION_FROM, SettingOptions.<Audience>builder().order(5)
            .availableWhen(() -> mentions.getAsBoolean() && friends.getAsBoolean())
            .optionAvailableWhen(Audience.FRIENDS_TEAM.id(), friends).optionAvailableWhen(Audience.FRIENDS.id(), friends)
            .placeholder(false).build());
        registry.register(chat, MENTION_HIGHLIGHT, SettingOptions.<MentionHighlight>builder().order(6).availableWhen(mentions).build());
        registry.register(chat, CHAT_FILTER_STRICT, SettingOptions.<Boolean>builder().order(7)
            .availableWhen(() -> config.get().strictFilter().size() > 0).build());
        registry.register(chat, REPLY_TARGET, SettingOptions.<ReplyTarget>builder().order(8).availableWhen(replies).build());
        registry.register(chat, MENTION_PLAIN_NAMES, SettingOptions.<Boolean>builder().order(9)
            .availableWhen(() -> mentions.getAsBoolean() && config.get().plainNameMentions()).build());
        registry.register(chat, CHAT_HIDE_NEW, SettingOptions.<Boolean>builder().order(10)
            .availableWhen(() -> stats != StatsRecorder.NONE && !config.get().newPlayerPlaytime().isZero()).build());
        registry.register(SettingCategories.STAFF, SOCIAL_SPY, SettingOptions.<Boolean>builder().order(1).availableWhen(messages).build());
        registry.reads(SharedSettings.SOUND_MENTION);
        registry.reads(SharedSettings.SOUND_PM);
        registry.reads(SharedSettings.BALANCE_PRIVACY);
    }

    /**
     * A "who can" choice: everyone, friends and teammates, friends and (when {@code nobody}) nobody. The friend options
     * are offered only on servers with friends; a player who picked one reads {@code unavailableAs} meanwhile (null:
     * the setting's default).
     */
    private static Choice.Builder<Audience> audience(String id, String unavailableAs, boolean nobody) {
        Choice.Builder<Audience> builder = Choice.ofEnum(id, Audience.class, Audience::id, Audience.EVERYONE)
            .option(Audience.EVERYONE, Audience.EVERYONE.label())
            .option(Audience.FRIENDS_TEAM, Audience.FRIENDS_TEAM.label(), null, unavailableAs)
            .option(Audience.FRIENDS, Audience.FRIENDS.label(), null, unavailableAs);
        if (nobody) {
            builder.option(Audience.NOBODY, Audience.NOBODY.label());
        }
        return builder;
    }

    @Override
    public String id() {
        return "chat";
    }

    /** Ignore lists, for features that must respect them (teleport requests, friend requests). */
    public IgnoreLookup ignores() {
        return this.ignores;
    }

    /**
     * The word filter and the link check for text players choose for others to read (nicknames, join messages):
     * any filtered word counts, whatever the filter's action in chat; any address counts, the allowed ones too, and
     * also while chat's own link check is off.
     */
    public TextChecks textChecks() {
        return new TextChecks() {
            @Override
            public boolean filtered(String text) {
                return !ChatFeature.this.settings.get().filter().apply(text, ChatFilter.Action.BLOCK, "").clean();
            }

            @Override
            public boolean link(String text) {
                return !ChatFeature.this.strictLinks().apply(text, ChatFilter.Action.BLOCK, "").clean();
            }
        };
    }

    /** A link check that allows no address, with chat's top-level domains (or the defaults when the check is off). */
    private LinkGuard strictLinks() {
        LinkGuard configured = this.settings.get().links();
        java.util.Set<String> domains = configured.topLevelDomains().isEmpty()
            ? java.util.Set.copyOf(ChatSettings.DEFAULT_TOP_LEVEL_DOMAINS) : configured.topLevelDomains();
        return new LinkGuard(domains, List.of());
    }

    @Override
    public void enable() throws Exception {
        this.ignores.load();
        Bukkit.getPluginManager().registerEvents(this.listener, this.services.plugin());
        this.sweeper = this.services.scheduler().asyncTimer(this::sweep, SWEEP, SWEEP);
        // Commands are registered after every plugin is enabled: look for them once the server runs.
        this.services.scheduler().globalLater(this::findCommands, 20);
        var placeholders = this.services.placeholders();
        placeholders.register("chat_ignoring", "How many players you ignore",
            player -> player == null ? "0" : Integer.toString(this.ignores.count(player.getUniqueId())));
        placeholders.register("chat_reply", "Who /r answers (following the /r replies to setting), - when nobody",
            player -> player == null ? "-" : this.conversations.replyTarget(player.getUniqueId(),
                this.services.settings().get(player.getUniqueId(), REPLY_TARGET)).map(this::partnerName).orElse("-"));
        placeholders.register("chat_slowmode", "The chat slow mode gap in seconds, 0 when off",
            player -> Long.toString(this.moderation.slow().toSeconds()));
    }

    /**
     * Finds which of SiftCore's commands the server runs ({@code commands.yml} can turn a command off or leave it to
     * another plugin; changes need a restart, so once is enough): {@code /msg} and {@code /r} for the private message
     * settings, {@code /profile} for names in chat. Only SiftCore's own commands count, never another plugin's
     * {@code /profile}. Global region thread.
     */
    private void findCommands() {
        CommandMap map = Bukkit.getCommandMap();
        String namespace = this.services.plugin().getPluginMeta().namespace();
        this.messaging = ours(map.getCommand(namespace + ":msg"));
        this.replying = ours(map.getCommand(namespace + ":r"));
        this.profileCommand = ChatRules.profileCommand(this.services.relations().friendsAvailable(), ours(map.getCommand("profile")),
            ours(map.getCommand(namespace + ":profile")), namespace);
    }

    /** Whether a command of the server's command map is one SiftCore registered. */
    private boolean ours(Command command) {
        return command instanceof PluginIdentifiableCommand owned && owned.getPlugin() == this.services.plugin();
    }

    private String partnerName(UUID partner) {
        return partner.equals(Conversations.CONSOLE)
            ? this.services.lang().plain(ChatMessages.PM_CONSOLE)
            : this.services.directory().name(partner);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        ChatSettings settings = this.settings.get();
        Duration keep = settings.spam().rateWindow().plus(settings.spam().duplicateWindow()).plus(settings.spam().cooldown())
            .plus(Duration.ofMinutes(1));
        this.publicScreen.guard().sweep(now, keep);
        this.privateScreen.guard().sweep(now, keep);
        this.conversations.sweep();
    }

    @Override
    public void disable() {
        this.sweeper.cancel();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "filter catches disguised words", () -> {
            ChatFilter filter = new ChatFilter(List.of(ChatFilter.entry("kys", true, new String[1]),
                ChatFilter.entry("idiot*", true, new String[1])), true, true);
            ChatFilter.Result result = filter.apply("K Y S you 1d10ts, skys is fine", ChatFilter.Action.REPLACE, "***");
            return result.text().equals("*** you ***, skys is fine") ? null : "got '" + result.text() + "'";
        });
        test.check(id(), "link check finds server addresses", () -> {
            LinkGuard guard = new LinkGuard(java.util.Set.copyOf(ChatSettings.DEFAULT_TOP_LEVEL_DOMAINS), List.of("siftvanilla.com"));
            List<String> found = guard.apply("join play.other.net or 1.2.3.4:25565, rules at siftvanilla.com/rules on 1.21.5",
                ChatFilter.Action.BLOCK, "***").matched();
            return found.equals(List.of("play.other.net", "1.2.3.4:25565")) ? null : "found " + found;
        });
        test.check(id(), "anti-spam refuses repeats", () -> {
            SpamGuard guard = new SpamGuard();
            SpamGuard.Rules rules = new SpamGuard.Rules(200, Duration.ZERO, 0, Duration.ZERO, Duration.ofSeconds(30), 0.9, 3,
                0.6, 8, SpamGuard.CapsAction.LOWERCASE);
            long now = 1_000_000L;
            SpamGuard.Verdict first = guard.check(SELF_TEST_PLAYER, "hello everyone out there", now, rules, true).verdict();
            SpamGuard.Verdict repeat = guard.check(SELF_TEST_PLAYER, "Hello everyone out there!!", now + 5_000L, rules, true).verdict();
            SpamGuard.Outcome shout = guard.check(SELF_TEST_PLAYER, "WHO WANTS TO TRADE", now + 6_000L, rules, true);
            return first == SpamGuard.Verdict.OK && repeat == SpamGuard.Verdict.DUPLICATE && shout.allowed()
                && shout.text().equals("who wants to trade") ? null : "got " + first + ", " + repeat + ", " + shout;
        });
        test.check(id(), "mentions are found", () -> {
            var found = Mentions.find("hey @alex and Steve, not alexander", List.of("Alex", "Steve", "Bo"), true, 3);
            return found.equals(java.util.Set.of("Alex", "Steve")) ? null : "found " + found;
        });
        test.check(id(), "[item] replaces the first tag only", () -> {
            Component replaced = ItemTag.replaceFirst(Component.text("look [item] and [i]"), Component.text("[Sword]"));
            String plain = ChatText.plain(replaced);
            return plain.equals("look [Sword] and [i]") ? null : "got '" + plain + "'";
        });
        test.check(id(), "chat format renders", () -> {
            String ranked = ChatText.plain(this.listener.line(Component.text("Elite"), Component.text("Alex"), Component.text("<red>hi")));
            String unranked = ChatText.plain(this.listener.line(null, Component.text("Alex"), Component.text("hi")));
            return ranked.equals("Elite Alex: <red>hi") && unranked.equals("Alex: hi") ? null : "got '" + ranked + "' and '" + unranked + "'";
        });
        test.checkAsync(id(), "ignore lists match the table", () -> {
            int inMemory = this.ignores.total();
            return this.ignores.countRows().thenApply(rows -> rows == inMemory || this.services.database().pendingWrites() > 0
                ? null : rows + " rows in the table but " + inMemory + " in memory");
        });
    }
}
