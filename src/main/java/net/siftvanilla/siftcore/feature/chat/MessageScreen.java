package net.siftvanilla.siftcore.feature.chat;

import java.util.List;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.entity.Player;

/**
 * The checks every typed message passes before anyone reads it: anti-spam (length, cooldown, rate, repeats,
 * capitals), the link check (advertising) and the word filter. Public chat and private messages share it, each with its own anti-spam history.
 * Safe from any thread (it only reads settings, the player's permissions and the guard's own locked state).
 */
final class MessageScreen {

    /**
     * What happens to a message.
     *
     * @param text    the text to send, or null when the message is refused
     * @param refusal why it was refused (action bar), or null
     * @param args    the refusal's placeholders
     */
    record Verdict(String text, MessageKey refusal, Arg[] args) {

        static Verdict send(String text) {
            return new Verdict(text, null, new Arg[0]);
        }

        static Verdict refuse(MessageKey key, Arg... args) {
            return new Verdict(null, key, args);
        }

        boolean refused() {
            return this.text == null;
        }
    }

    private final SpamGuard guard;
    private final Setting<ChatSettings> settings;
    private final Logger logger;
    private final boolean privateMessages;

    MessageScreen(SpamGuard guard, Setting<ChatSettings> settings, Logger logger, boolean privateMessages) {
        this.guard = guard;
        this.settings = settings;
        this.logger = logger;
        this.privateMessages = privateMessages;
    }

    SpamGuard guard() {
        return this.guard;
    }

    /** Checks a cleaned, non-empty message of {@code player}. */
    Verdict screen(Player player, String text, long now) {
        ChatSettings settings = this.settings.get();
        String result = text;
        if (!player.hasPermission(ChatNodes.BYPASS)) {
            SpamGuard.Outcome outcome = this.guard.check(player.getUniqueId(), text, now, settings.spam(), !this.privateMessages);
            switch (outcome.verdict()) {
                case TOO_LONG -> {
                    return Verdict.refuse(ChatMessages.TOO_LONG, Arg.number("max", settings.spam().maxLength()));
                }
                case TOO_FAST -> {
                    return Verdict.refuse(ChatMessages.TOO_FAST, Arg.time("time", roundUp(outcome.retryIn())));
                }
                case RATE_LIMITED -> {
                    return Verdict.refuse(ChatMessages.RATE_LIMITED, Arg.time("time", roundUp(outcome.retryIn())));
                }
                case DUPLICATE -> {
                    return Verdict.refuse(ChatMessages.REPEATED);
                }
                case CAPS -> {
                    return Verdict.refuse(ChatMessages.CAPS);
                }
                case OK -> result = outcome.text();
            }
        }
        boolean linksChecked = !this.privateMessages || settings.linksPrivate();
        if (linksChecked && !player.hasPermission(ChatNodes.LINKS)) {
            LinkGuard.Result links = settings.links().apply(result, settings.linkAction(), settings.filterReplacement());
            if (links.blocked()) {
                log(settings, player, "Link check", "refused", result, links.matched());
                return Verdict.refuse(ChatMessages.LINK);
            }
            if (links.changed()) {
                log(settings, player, "Link check", "changed", result, links.matched());
                result = links.text();
            }
        }
        boolean filtered = !this.privateMessages || settings.filterPrivate();
        if (filtered && !player.hasPermission(ChatNodes.FILTER_BYPASS)) {
            ChatFilter.Result filter = settings.filter().apply(result, settings.filterAction(), settings.filterReplacement());
            if (filter.blocked()) {
                log(settings, player, "Chat filter", "refused", result, filter.matched());
                return Verdict.refuse(ChatMessages.FILTERED);
            }
            if (filter.changed()) {
                log(settings, player, "Chat filter", "changed", result, filter.matched());
                result = filter.text();
            }
        }
        return Verdict.send(result);
    }

    private void log(ChatSettings settings, Player player, String check, String what, String text, List<String> matched) {
        if (settings.filterLog()) {
            this.logger.info(check + " " + what + " " + (this.privateMessages ? "a private message" : "a message") + " of "
                + player.getName() + " (matched " + String.join(", ", matched) + "): " + text);
        }
    }

    /** Waits shown to players never read "0s". */
    private static java.time.Duration roundUp(java.time.Duration wait) {
        long seconds = (wait.toMillis() + 999) / 1000;
        return java.time.Duration.ofSeconds(Math.max(1, seconds));
    }
}
