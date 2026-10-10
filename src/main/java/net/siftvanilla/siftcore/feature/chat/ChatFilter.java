package net.siftvanilla.siftcore.feature.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The word filter. Words and phrases from the config are matched as whole words, ignoring case, accents and (when
 * enabled) leetspeak such as {@code k1ll} or {@code $hit} (a symbol is tried both as a letter and as punctuation, so
 * {@code sh!t} and {@code kys!} are both caught). An entry ending in {@code *} also matches longer words
 * that start with it ({@code idiot*} matches {@code idiots}). Letters typed one by one with gaps ({@code k y s},
 * {@code k.y.s}) are joined and checked as one word when that option is on.
 * <p>
 * Normalising keeps every character's position, so a match maps straight back to the text the player typed and only
 * that part is replaced. Immutable and thread-safe; pure (no server classes) so it is unit tested.
 */
final class ChatFilter {

    /** What happens to a message with a filtered word. */
    enum Action {
        /** The word is replaced and the message goes through. */
        REPLACE,
        /** The message is refused. */
        BLOCK
    }

    /**
     * The filter's verdict.
     *
     * @param text    the message to send (with replacements), or the original text when blocked
     * @param matched the words that matched, as typed by the player, in order
     * @param blocked whether the message must be refused
     */
    record Result(String text, List<String> matched, boolean blocked) {

        boolean changed() {
            return !this.matched.isEmpty() && !this.blocked;
        }

        boolean clean() {
            return this.matched.isEmpty();
        }
    }

    /** One entry of the word list: one or more words, each optionally a prefix. */
    record Entry(List<String> words, List<Boolean> prefix, String source) {
    }

    /** A word of the normalised message with its position in the original text. */
    record Token(String text, int start, int end) {
    }

    private static final int MIN_JOINED_LETTERS = 3;

    private final List<Entry> entries;
    private final boolean leetspeak;
    private final boolean joinSpacedLetters;

    ChatFilter(List<Entry> entries, boolean leetspeak, boolean joinSpacedLetters) {
        this.entries = List.copyOf(entries);
        this.leetspeak = leetspeak;
        this.joinSpacedLetters = joinSpacedLetters;
    }

    /** A filter without words: lets everything through. */
    static ChatFilter none() {
        return new ChatFilter(List.of(), false, false);
    }

    int size() {
        return this.entries.size();
    }

    /**
     * Parses a word list entry. Returns null (with the reason in {@code problem[0]}) for an entry that can never
     * match, such as one made of symbols only.
     */
    static Entry entry(String raw, boolean leetspeak, String[] problem) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty()) {
            problem[0] = "is empty";
            return null;
        }
        List<String> words = new ArrayList<>();
        List<Boolean> prefix = new ArrayList<>();
        for (String part : trimmed.split("\\s+")) {
            boolean isPrefix = part.endsWith("*");
            String word = normalize(isPrefix ? part.substring(0, part.length() - 1) : part, leetspeak);
            StringBuilder letters = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    letters.append(c);
                }
            }
            if (letters.isEmpty()) {
                problem[0] = "has a part without letters or digits ('" + part + "')";
                return null;
            }
            if (letters.length() != word.length()) {
                problem[0] = "can only contain letters and digits, with spaces between words and an optional * at the end of a word ('" + part + "')";
                return null;
            }
            words.add(letters.toString());
            prefix.add(isPrefix);
        }
        return new Entry(List.copyOf(words), List.copyOf(prefix), trimmed);
    }

    /**
     * Lowercases, strips accents and maps leetspeak (digits and symbols), one character for one character, so
     * positions are kept. Characters that are not letters after mapping stay as they are (and later split words).
     */
    static String normalize(String text, boolean leetspeak) {
        return normalize(text, leetspeak, leetspeak);
    }

    /**
     * The same with digits and symbols mapped separately. A symbol can be a letter ({@code sh!t}) or punctuation
     * ({@code kys!}); the filter checks both readings.
     */
    static String normalize(String text, boolean digits, boolean symbols) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            sb.append(normalize(text.charAt(i), digits, symbols));
        }
        return sb.toString();
    }

    private static char normalize(char c, boolean digits, boolean symbols) {
        char mapped = switch (c) {
            case '0' -> digits ? 'o' : 0;
            case '1' -> digits ? 'i' : 0;
            case '3' -> digits ? 'e' : 0;
            case '4' -> digits ? 'a' : 0;
            case '5' -> digits ? 's' : 0;
            case '7' -> digits ? 't' : 0;
            case '8' -> digits ? 'b' : 0;
            case '9' -> digits ? 'g' : 0;
            case '!', '|' -> symbols ? 'i' : 0;
            case '@' -> symbols ? 'a' : 0;
            case '$' -> symbols ? 's' : 0;
            case '+' -> symbols ? 't' : 0;
            default -> 0;
        };
        if (mapped != 0) {
            return mapped;
        }
        if (c < 128) {
            return Character.toLowerCase(c);
        }
        String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKD);
        char base = decomposed.isEmpty() ? c : decomposed.charAt(0);
        return Character.isLetterOrDigit(base) ? Character.toLowerCase(base) : Character.toLowerCase(c);
    }

    /** The words of a normalised text with their positions. */
    static List<Token> tokens(String normalized) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < normalized.length()) {
            if (!Character.isLetterOrDigit(normalized.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < normalized.length() && Character.isLetterOrDigit(normalized.charAt(i))) {
                i++;
            }
            tokens.add(new Token(normalized.substring(start, i), start, i));
        }
        return tokens;
    }

    /** Runs of single letters typed with gaps ({@code k y s}), joined into one word each. */
    static List<Token> joinedLetters(List<Token> tokens) {
        List<Token> joined = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (tokens.get(i).text().length() != 1) {
                i++;
                continue;
            }
            int start = i;
            StringBuilder letters = new StringBuilder();
            while (i < tokens.size() && tokens.get(i).text().length() == 1) {
                letters.append(tokens.get(i).text());
                i++;
            }
            if (letters.length() >= MIN_JOINED_LETTERS) {
                joined.add(new Token(letters.toString(), tokens.get(start).start(), tokens.get(i - 1).end()));
            }
        }
        return joined;
    }

    /** Checks a message and replaces or blocks filtered words. */
    Result apply(String text, Action action, String replacement) {
        if (this.entries.isEmpty() || text.isEmpty()) {
            return new Result(text, List.of(), false);
        }
        List<int[]> spans = new ArrayList<>();
        find(normalize(text, this.leetspeak, this.leetspeak), spans);
        if (this.leetspeak) {
            find(normalize(text, true, false), spans);
        }
        if (spans.isEmpty()) {
            return new Result(text, List.of(), false);
        }
        List<int[]> merged = merge(spans);
        List<String> matched = new ArrayList<>(merged.size());
        for (int[] span : merged) {
            matched.add(text.substring(span[0], span[1]));
        }
        if (action == Action.BLOCK) {
            return new Result(text, List.copyOf(matched), true);
        }
        StringBuilder out = new StringBuilder(text);
        for (int i = merged.size() - 1; i >= 0; i--) {
            int[] span = merged.get(i);
            out.replace(span[0], span[1], replacement);
        }
        return new Result(out.toString(), List.copyOf(matched), false);
    }

    /** Adds the spans of every entry found in one normalised reading of the text. */
    private void find(String normalized, List<int[]> spans) {
        List<Token> tokens = tokens(normalized);
        for (int i = 0; i < tokens.size(); i++) {
            for (Entry entry : this.entries) {
                int length = entry.words().size();
                if (i + length <= tokens.size() && matches(entry, tokens.subList(i, i + length))) {
                    spans.add(new int[] {tokens.get(i).start(), tokens.get(i + length - 1).end()});
                }
            }
        }
        if (this.joinSpacedLetters) {
            for (Token joined : joinedLetters(tokens)) {
                for (Entry entry : this.entries) {
                    if (entry.words().size() == 1 && matches(entry, List.of(joined))) {
                        spans.add(new int[] {joined.start(), joined.end()});
                    }
                }
            }
        }
    }

    private static boolean matches(Entry entry, List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String word = entry.words().get(i);
            String token = tokens.get(i).text();
            boolean ok = entry.prefix().get(i) ? token.startsWith(word) : token.equals(word);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static List<int[]> merge(List<int[]> spans) {
        List<int[]> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.<int[]>comparingInt(s -> s[0]).thenComparingInt(s -> s[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : sorted) {
            if (!merged.isEmpty() && span[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], span[1]);
            } else {
                merged.add(new int[] {span[0], span[1]});
            }
        }
        return merged;
    }
}
