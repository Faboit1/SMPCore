package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One player's stored cosmetic choices. Immutable; a change makes a new profile. A choice stays stored when the
 * player loses the permission for it (an expired rank), so it comes back when they have it again; only a nickname can
 * be taken over by someone else once its hold has run out ({@link NickRules#held}).
 *
 * @param chatStyle    the colour of their chat messages
 * @param nick         their nickname, or null
 * @param nickStyle    the colour of their nickname
 * @param tag          the id of their chat tag, or null
 * @param ownedTags    monthly exclusive tags they picked in their month and keep for good
 * @param joinMessage  their custom join message (with {@code {name}}), or null
 * @param leaveMessage their custom leave message, or null
 * @param killEffect   the id of their kill effect, or null
 * @param nickSeen     when they were last seen able to show their nickname (epoch millis; 0 without a nickname)
 * @param nickLost     a nickname another player took over while they couldn't show it, until they are told; or null
 */
record Profile(ChatStyle chatStyle, String nick, ChatStyle nickStyle, String tag, Set<String> ownedTags, String joinMessage,
               String leaveMessage, String killEffect, long nickSeen, String nickLost) {

    static final Profile EMPTY = new Profile(ChatStyle.NONE, null, ChatStyle.NONE, null, Set.of(), null, null, null, 0L, null);

    Profile {
        chatStyle = chatStyle == null ? ChatStyle.NONE : chatStyle;
        nickStyle = nickStyle == null ? ChatStyle.NONE : nickStyle;
        ownedTags = Set.copyOf(ownedTags == null ? Set.of() : ownedTags);
        nick = blankToNull(nick);
        tag = blankToNull(tag);
        joinMessage = blankToNull(joinMessage);
        leaveMessage = blankToNull(leaveMessage);
        killEffect = blankToNull(killEffect);
        nickLost = blankToNull(nickLost);
        nickSeen = nick == null ? 0L : Math.max(0L, nickSeen);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    boolean empty() {
        return this.chatStyle.none() && this.nick == null && this.nickStyle.none() && this.tag == null && this.ownedTags.isEmpty()
            && this.joinMessage == null && this.leaveMessage == null && this.killEffect == null && this.nickLost == null;
    }

    Profile withChatStyle(ChatStyle style) {
        return new Profile(style, this.nick, this.nickStyle, this.tag, this.ownedTags, this.joinMessage, this.leaveMessage, this.killEffect,
            this.nickSeen, this.nickLost);
    }

    /** A new nickname (null: none) in a colour, seen able to show it at {@code seen}. */
    Profile withNick(String nick, ChatStyle style, long seen) {
        return new Profile(this.chatStyle, nick, style, this.tag, this.ownedTags, this.joinMessage, this.leaveMessage, this.killEffect,
            seen, this.nickLost);
    }

    /** The same nickname, last seen able to show it at {@code seen}. */
    Profile withNickSeen(long seen) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, this.tag, this.ownedTags, this.joinMessage, this.leaveMessage,
            this.killEffect, seen, this.nickLost);
    }

    /** Remembers ({@code nick}) or forgets (null) a nickname someone else took over. */
    Profile withNickLost(String nick) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, this.tag, this.ownedTags, this.joinMessage, this.leaveMessage,
            this.killEffect, this.nickSeen, nick);
    }

    Profile withTag(String tag) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, tag, this.ownedTags, this.joinMessage, this.leaveMessage, this.killEffect,
            this.nickSeen, this.nickLost);
    }

    Profile withOwnedTag(String tag) {
        Set<String> owned = new LinkedHashSet<>(this.ownedTags);
        owned.add(tag);
        return withOwnedTags(owned);
    }

    Profile withoutOwnedTag(String tag) {
        Set<String> owned = new LinkedHashSet<>(this.ownedTags);
        owned.remove(tag);
        return withOwnedTags(owned);
    }

    Profile withOwnedTags(Set<String> owned) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, this.tag, owned, this.joinMessage, this.leaveMessage, this.killEffect,
            this.nickSeen, this.nickLost);
    }

    Profile withJoinMessage(String message) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, this.tag, this.ownedTags, message, this.leaveMessage, this.killEffect,
            this.nickSeen, this.nickLost);
    }

    Profile withLeaveMessage(String message) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, this.tag, this.ownedTags, this.joinMessage, message, this.killEffect,
            this.nickSeen, this.nickLost);
    }

    Profile withKillEffect(String effect) {
        return new Profile(this.chatStyle, this.nick, this.nickStyle, this.tag, this.ownedTags, this.joinMessage, this.leaveMessage, effect,
            this.nickSeen, this.nickLost);
    }

    /**
     * What a staff reset leaves: the monthly exclusives the player owns (they can't be picked again once their month is
     * over), nothing else.
     */
    Profile reset() {
        return EMPTY.withOwnedTags(this.ownedTags);
    }

    /** Owned tags as stored: ids separated by commas, sorted. */
    String ownedTagsText() {
        return this.ownedTags.stream().sorted().collect(Collectors.joining(","));
    }

    /**
     * Every choice on one line, for the audit log (so a mistaken reset can be put back by hand). Player text is quoted
     * as written.
     */
    String describe() {
        StringBuilder sb = new StringBuilder();
        append(sb, "chat", this.chatStyle.none() ? null : this.chatStyle.serialize());
        append(sb, "nick", this.nick == null ? null : this.nick + (this.nickStyle.none() ? "" : " " + this.nickStyle.serialize()));
        append(sb, "tag", this.tag);
        append(sb, "owned", this.ownedTags.isEmpty() ? null : ownedTagsText());
        append(sb, "join", this.joinMessage == null ? null : "'" + this.joinMessage + "'");
        append(sb, "leave", this.leaveMessage == null ? null : "'" + this.leaveMessage + "'");
        append(sb, "effect", this.killEffect);
        return sb.isEmpty() ? "nothing" : sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("; ");
        }
        sb.append(label).append('=').append(value);
    }

    /** Parses stored owned tags, skipping anything that is not a tag id. */
    static Set<String> parseOwnedTags(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.split(","))
            .map(String::strip)
            .filter(ChatTag::validId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
