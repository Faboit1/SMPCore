package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/chat.yml}.
 *
 * @param hoverCard          names in chat show a card with rank, team, balance, kills and playtime
 * @param itemTag            {@code [item]} shows the held item
 * @param spam               anti-spam rules for public chat (private messages use them without the repeat check)
 * @param filter             the word filter ({@link ChatFilter#none()} when turned off)
 * @param filterAction       replace filtered words or refuse the message
 * @param filterReplacement  what a filtered word becomes
 * @param filterPrivate      whether private messages are filtered too
 * @param filterLog          refused and changed messages are written to the console
 * @param links              the link check ({@link LinkGuard#none()} when turned off)
 * @param linkAction         replace addresses or refuse the message
 * @param linksPrivate       whether private messages are checked for links too
 * @param mentions           mentions ping the mentioned player
 * @param plainNameMentions  a bare name mentions, not only {@code @name}
 * @param minPlainLength     shortest bare name that mentions
 * @param mentionCooldown    how often one player can ping the same player
 * @param replyExpiry        how long {@code /r} keeps its target
 * @param logPrivate         private messages are written to the console
 * @param maxIgnores         the most players one player can ignore
 * @param ignorePageSize     names per page of the ignore list dialog
 */
record ChatSettings(
    boolean hoverCard,
    boolean itemTag,
    SpamGuard.Rules spam,
    ChatFilter filter,
    ChatFilter.Action filterAction,
    String filterReplacement,
    boolean filterPrivate,
    boolean filterLog,
    LinkGuard links,
    ChatFilter.Action linkAction,
    boolean linksPrivate,
    boolean mentions,
    boolean plainNameMentions,
    int minPlainLength,
    Duration mentionCooldown,
    Duration replyExpiry,
    boolean logPrivate,
    int maxIgnores,
    int ignorePageSize) {

    /** The longest chat message a client can send. */
    static final int CLIENT_MAX_LENGTH = 256;

    /** Top-level domains that make a word an address when the config doesn't list its own. */
    static final List<String> DEFAULT_TOP_LEVEL_DOMAINS = List.of("com", "net", "org", "gg", "io", "me", "co", "us", "uk",
        "de", "eu", "fr", "nl", "pl", "es", "ru", "br", "ca", "au", "xyz", "club", "fun", "pro", "tk", "ml", "ga", "cf",
        "gq", "cc", "tv", "info", "biz", "online", "site", "network", "games", "host", "top", "icu", "dev", "app", "ws");

    static ChatSettings parse(ConfigReader r) {
        ConfigReader format = r.section("format");
        ConfigReader spam = r.section("anti-spam");
        ConfigReader rate = spam.section("rate-limit");
        ConfigReader repeats = spam.section("repeats");
        ConfigReader caps = spam.section("caps");
        ConfigReader filter = r.section("filter");
        ConfigReader links = r.section("links");
        ConfigReader mentions = r.section("mentions");
        ConfigReader messages = r.section("private-messages");
        ConfigReader ignore = r.section("ignore");

        SpamGuard.Rules rules = new SpamGuard.Rules(
            spam.integer("max-length", 16, CLIENT_MAX_LENGTH, 200),
            spam.duration("cooldown", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(1)),
            rate.integer("messages", 0, 100, 5),
            rate.duration("window", Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(10)),
            repeats.duration("window", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(30)),
            repeats.decimal("similarity", 0.5, 1.0, 0.9),
            repeats.integer("compare-last", 1, 20, 3),
            caps.decimal("max-ratio", 0.0, 1.0, 0.6),
            caps.integer("min-letters", 1, 256, 8),
            caps.enumValue("action", SpamGuard.CapsAction.class, SpamGuard.CapsAction.LOWERCASE));

        boolean filterOn = filter.bool("enabled", true);
        boolean leetspeak = filter.bool("leetspeak", true);
        boolean joinLetters = filter.bool("join-spaced-letters", true);
        List<ChatFilter.Entry> entries = new ArrayList<>();
        for (String raw : filter.stringList("words", List.of())) {
            String[] problem = new String[1];
            ChatFilter.Entry entry = ChatFilter.entry(raw, leetspeak, problem);
            if (entry == null) {
                filter.problem("words", "entry '" + raw + "' " + problem[0] + "; it is skipped");
            } else {
                entries.add(entry);
            }
        }
        String replacement = filter.string("replacement", "***");
        if (replacement.length() > 32) {
            filter.problem("replacement", "must be at most 32 characters");
            replacement = "***";
        }
        LinkGuard linkGuard = LinkGuard.none();
        if (links.bool("enabled", true)) {
            Set<String> domains = new LinkedHashSet<>();
            for (String raw : links.stringList("top-level-domains", DEFAULT_TOP_LEVEL_DOMAINS)) {
                String domain = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
                if (domain.startsWith(".")) {
                    domain = domain.substring(1);
                }
                if (domain.matches("[a-z]{2,24}")) {
                    domains.add(domain);
                } else {
                    links.problem("top-level-domains", "'" + raw + "' is not a top-level domain (letters only, like net or gg); it is skipped");
                }
            }
            List<String> allowed = new ArrayList<>();
            for (String raw : links.stringList("allowed", List.of())) {
                if (LinkGuard.allowed(raw) == null) {
                    links.problem("allowed", "'" + raw + "' is not an address (like example.net or discord.gg/name); it is skipped");
                } else {
                    allowed.add(raw);
                }
            }
            linkGuard = new LinkGuard(domains, allowed);
        }
        return new ChatSettings(
            format.bool("hover-card", true),
            format.bool("item-tag", true),
            rules,
            filterOn ? new ChatFilter(entries, leetspeak, joinLetters) : ChatFilter.none(),
            filter.enumValue("action", ChatFilter.Action.class, ChatFilter.Action.REPLACE),
            ChatText.clean(replacement),
            filter.bool("private-messages", true),
            filter.bool("log", true),
            linkGuard,
            links.enumValue("action", ChatFilter.Action.class, ChatFilter.Action.BLOCK),
            links.bool("private-messages", true),
            mentions.bool("enabled", true),
            mentions.bool("plain-names", true),
            mentions.integer("min-plain-length", 1, 16, 3),
            mentions.duration("cooldown", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(3)),
            messages.duration("reply-expiry", Duration.ofMinutes(1), Duration.ofDays(1), Duration.ofMinutes(10)),
            messages.bool("log-to-console", true),
            ignore.integer("max", 1, 1000, 100),
            ignore.integer("page-size", 4, 30, 10));
    }
}
