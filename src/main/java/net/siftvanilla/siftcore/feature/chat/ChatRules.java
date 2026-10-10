package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The decisions behind the per-player chat settings, kept pure so they are unit tested: who reads a public line, who
 * gets a mention alert, who may write to a player and who sees a player's balance on their card. "Who can" answers
 * come from {@code Relations#allows} and are passed in as {@code allowed}.
 */
final class ChatRules {

    private ChatRules() {
    }

    /**
     * Whether a sender counts as a brand-new player for "Hide brand-new players": less active playtime than the
     * server's threshold. Exempt senders (staff) never do, and a zero threshold turns the rule off.
     */
    static boolean newPlayer(long playtimeSeconds, Duration threshold, boolean exempt) {
        return !exempt && !threshold.isZero() && !threshold.isNegative() && playtimeSeconds < threshold.toSeconds();
    }

    /**
     * Whether a viewer stops being a viewer of a public line: they turned public chat off, or they hide brand-new
     * players and the sender is one. The sender always reads their own line.
     */
    static boolean hides(boolean sender, boolean publicChat, boolean hideNew, boolean senderNew) {
        return !sender && (!publicChat || (hideNew && senderNew));
    }

    /**
     * Whether a mentioned player gets the alert and the ping: they want mention alerts somewhere, the mention was
     * written with {@code @} or they also want pings on their bare name, and the sender is in their "who can ping me"
     * audience.
     */
    static boolean alerts(AlertStyle style, boolean atMention, boolean plainNames, boolean allowed) {
        return style != AlertStyle.OFF && (atMention || plainNames) && allowed;
    }

    /**
     * Whether a private message reaches a player: the sender is in their "who can message me" audience, or the
     * receiver wrote to the sender recently (an answer is always welcome), or the sender is staff with the bypass.
     */
    static boolean acceptsMessage(boolean allowed, boolean answering, boolean bypass) {
        return allowed || answering || bypass;
    }

    /**
     * Whether a viewer sees a player's balance on their chat card: the player's own card, a viewer in their
     * balance-privacy audience, or staff with the economy admin node.
     */
    static boolean showsBalance(boolean self, boolean allowed, boolean bypass) {
        return self || allowed || bypass;
    }

    /**
     * What a click on a name in public chat runs to open a profile (the name follows), or null when names don't open
     * profiles: the server needs friends (profiles are theirs) and SiftCore's own {@code /profile}. That is the plain
     * {@code /profile} when it is SiftCore's, else the namespaced form while another plugin holds the plain label;
     * nothing while {@code commands.yml} turned it off or left it to another plugin.
     *
     * @param ownsLabel     whether the plain {@code profile} label runs SiftCore's command
     * @param ownsNamespaced whether {@code <namespace>:profile} is SiftCore's command (registered at all)
     */
    static String profileCommand(boolean friends, boolean ownsLabel, boolean ownsNamespaced, String namespace) {
        if (!friends) {
            return null;
        }
        if (ownsLabel) {
            return "/profile ";
        }
        return ownsNamespaced ? "/" + namespace + ":profile " : null;
    }

    /**
     * What a reader's click on a name runs ({@link #profileCommand}), or null to start a private message instead:
     * readers without {@code /profile} (its permission was taken away) get the message.
     */
    static String readerProfileCommand(String profileCommand, boolean mayOpenProfiles) {
        return mayOpenProfiles ? profileCommand : null;
    }

    /**
     * The once-a-session reminder for a player who talks with public chat off: the one that points to
     * {@code /settings chat}, or, when the server sets public chat (locked or hidden in {@code features/settings.yml}),
     * the one that says so, since the player can't turn it back on.
     */
    static MessageKey publicOffReminder(boolean locked, boolean hidden) {
        return locked || hidden ? ChatMessages.PUBLIC_OFF_SERVER : ChatMessages.PUBLIC_OFF;
    }

    /**
     * Whether settings about private messages do something: SiftCore's {@code /msg} is registered (not turned off in
     * {@code commands.yml} or left to another plugin), and, for the {@code /r} target, its {@code /r} too.
     */
    static boolean messagesOffered(boolean msg, boolean reply, boolean forReply) {
        return msg && (!forReply || reply);
    }
}
