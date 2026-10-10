package net.siftvanilla.siftcore.feature.cosmetics;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What makes a nickname acceptable: 3 to 16 letters, digits and underscores (like a Minecraft name), no reserved
 * word anywhere in it (so nobody poses as staff or the server), not another player's name, not a nickname another
 * player holds ({@link #held}), and nothing the chat word filter catches. A player may use their own name in another
 * case or colour. Pure: lookups come in as functions, so it is unit tested.
 *
 * @param minLength     shortest nickname
 * @param maxLength     longest nickname (at most 16, the longest Minecraft name)
 * @param reservedWords lowercase words no nickname may contain
 */
record NickRules(int minLength, int maxLength, List<String> reservedWords) {

    /** Why a nickname is refused, or {@link #OK}. */
    enum Problem {
        OK,
        LENGTH,
        CHARACTERS,
        RESERVED,
        PLAYER_NAME,
        TAKEN,
        FILTERED
    }

    /** The verdict; {@code word} is the reserved word found, for the message. */
    record Verdict(Problem problem, String word) {

        static final Verdict OK = new Verdict(Problem.OK, null);

        boolean ok() {
            return this.problem == Problem.OK;
        }
    }

    NickRules {
        reservedWords = List.copyOf(reservedWords);
    }

    /** True for the characters Minecraft allows in names. */
    static boolean nameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * Checks a nickname for {@code owner}.
     *
     * @param playerName the player who has ever used a name (ignoring case), if any
     * @param nickOwner  the player who holds a nickname (ignoring case), if any: one whose hold ran out doesn't count
     * @param filtered   whether the chat word filter catches the text
     */
    Verdict check(UUID owner, String nick, Function<String, Optional<UUID>> playerName, Function<String, Optional<UUID>> nickOwner,
                  Predicate<String> filtered) {
        if (nick == null || nick.length() < this.minLength || nick.length() > this.maxLength) {
            return new Verdict(Problem.LENGTH, null);
        }
        for (int i = 0; i < nick.length(); i++) {
            if (!nameChar(nick.charAt(i))) {
                return new Verdict(Problem.CHARACTERS, null);
            }
        }
        String lower = nick.toLowerCase(Locale.ROOT);
        String squashed = lower.replace("_", "");
        for (String word : this.reservedWords) {
            if (lower.contains(word) || squashed.contains(word)) {
                return new Verdict(Problem.RESERVED, word);
            }
        }
        Optional<UUID> named = playerName.apply(nick);
        if (named.isPresent() && !named.get().equals(owner)) {
            return new Verdict(Problem.PLAYER_NAME, null);
        }
        Optional<UUID> taken = nickOwner.apply(nick);
        if (taken.isPresent() && !taken.get().equals(owner)) {
            return new Verdict(Problem.TAKEN, null);
        }
        if (filtered.test(nick) || filtered.test(nick.replace('_', ' '))) {
            return new Verdict(Problem.FILTERED, null);
        }
        return Verdict.OK;
    }

    /**
     * Whether a stored nickname still keeps other players from taking it: its holder can show it now, or could within
     * {@code hold} (an expired rank, or a holder who is offline). A nickname that is now another player's real name is
     * never held: its holder can't show it any more.
     *
     * @param shadowed another player has the nickname as their real name
     * @param showsNow the holder is online and may show a nickname
     * @param lastSeen when the holder was last seen able to show it (epoch millis, 0 when unknown)
     * @param now      the time now (epoch millis)
     */
    static boolean held(boolean shadowed, boolean showsNow, long lastSeen, long now, Duration hold) {
        if (shadowed) {
            return false;
        }
        return showsNow || lastSeen > 0 && now - lastSeen <= hold.toMillis();
    }
}
