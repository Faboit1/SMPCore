package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.TextChecks;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import org.bukkit.Bukkit;

/**
 * Public chat (format, hover cards, {@code [item]}, mentions, anti-spam, the word filter, chat lock and slow mode),
 * private messages ({@code /msg}, {@code /r}, social spy) and ignore lists ({@code /ignore}).
 * <p>
 * Other features consult it through {@link #ignores()}: teleport requests and friend requests from a player you
 * ignore never reach you; and through {@link #textChecks()}: nicknames and join messages pass the word filter and the
 * link check. It consults rank labels, teams, balances and stats for the hover card, mutes and vanish from the staff
 * tools, AFK status, and cosmetics (nicknames, chat tags and chat colours in chat and private messages).
 */
public final class ChatFeature implements Feature {

    /** The chat group of the settings dialog. */
    public static final SettingCategory SETTINGS = new SettingCategory("chat", 20, ChatMessages.SETTING_CATEGORY,
        ChatMessages.SETTING_CATEGORY_DESCRIPTION);
    /** Mention alerts (sound and action bar). */
    public static final Toggle MENTIONS = new Toggle("mentions", true, ChatMessages.SETTING_MENTIONS,
        ChatMessages.SETTING_MENTIONS_DESCRIPTION, null);
    /** Whether players can send you private messages. */
    public static final Toggle PRIVATE_MESSAGES = new Toggle("private-messages", true, ChatMessages.SETTING_PRIVATE,
        ChatMessages.SETTING_PRIVATE_DESCRIPTION, null);
    /** Staff: see private messages between other players. */
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
        services.settings().register(SETTINGS, MENTIONS);
        services.settings().register(SETTINGS, PRIVATE_MESSAGES);
        services.settings().register(SETTINGS, SOCIAL_SPY);
        ChatNodes.declare(services.permissions());
        var logger = services.plugin().getLogger();
        this.ignores = new IgnoreList(services.database(), logger);
        this.conversations = new Conversations(System::currentTimeMillis, () -> this.settings.get().replyExpiry());
        this.publicScreen = new MessageScreen(new SpamGuard(), this.settings, logger, false);
        this.privateScreen = new MessageScreen(new SpamGuard(), this.settings, logger, true);
        PlayerCards cards = new PlayerCards(services.lang(), ranks, teams, services.ledger(), stats, cosmetics);
        HeldItems items = new HeldItems(services.scheduler(), services.lang(), this.settings);
        ChatListener.Links links = new ChatListener.Links(mutes, afk, vanish, cosmetics);
        this.listener = new ChatListener(services, this.settings, this.publicScreen, this.privateScreen, this.moderation,
            this.ignores, cards, items, links, MENTIONS);
        this.messages = new PrivateMessages(services, this.settings, this.ignores, this.conversations, this.privateScreen,
            cards, items, links, PRIVATE_MESSAGES, SOCIAL_SPY);
        this.commands = new ChatCommands(services, this.settings, this.messages, this.ignores,
            new IgnoreViews(services, this.settings, this.ignores), this.moderation, PRIVATE_MESSAGES, SOCIAL_SPY);
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
        var placeholders = this.services.placeholders();
        placeholders.register("chat_ignoring", "How many players you ignore",
            player -> player == null ? "0" : Integer.toString(this.ignores.count(player.getUniqueId())));
        placeholders.register("chat_reply", "Who /r answers, - when nobody",
            player -> player == null ? "-" : this.conversations.replyTarget(player.getUniqueId())
                .map(this::partnerName).orElse("-"));
        placeholders.register("chat_slowmode", "The chat slow mode gap in seconds, 0 when off",
            player -> Long.toString(this.moderation.slow().toSeconds()));
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
            LinkGuard guard = new LinkGuard(java.util.Set.copyOf(ChatSettings.DEFAULT_TOP_LEVEL_DOMAINS), List.of("siftvanilla.net"));
            List<String> found = guard.apply("join play.other.net or 1.2.3.4:25565, rules at siftvanilla.net/rules on 1.21.5",
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
