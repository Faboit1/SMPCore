package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-spam for one channel (public chat, or private messages): message length, a minimum gap between messages, a
 * rate limit (so many messages per window), repeated or near-identical messages, and shouting in capitals.
 * <p>
 * Only messages that pass are remembered, so a refused message never counts against the player. Per-player state
 * is a small bounded history guarded by its own lock; chat events of one player arrive one after another, but
 * private messages run on the player's thread, so both may touch it. Pure (no server classes) for unit tests.
 */
final class SpamGuard {

    /** Why a message was refused, or {@link #OK}. */
    enum Verdict {
        OK,
        /** Longer than the limit. */
        TOO_LONG,
        /** Sent sooner than the minimum gap after the previous message. */
        TOO_FAST,
        /** Too many messages within the rate window. */
        RATE_LIMITED,
        /** The same as, or very close to, a recent message. */
        DUPLICATE,
        /** Too many capitals, and the rules say to refuse it. */
        CAPS
    }

    /** What to do with a message in capitals. */
    enum CapsAction {
        LOWERCASE,
        BLOCK
    }

    /**
     * The rules, from {@code features/chat.yml}.
     *
     * @param maxLength        the most characters one message may have
     * @param cooldown         the minimum gap between two messages ({@code 0} turns it off)
     * @param rateMessages     how many messages fit in {@code rateWindow} ({@code 0} turns the rate limit off)
     * @param rateWindow       the rate limit window
     * @param duplicateWindow  how long a message is remembered for the repeat check ({@code 0} turns it off)
     * @param similarity       how alike two messages must be to count as a repeat, 0.5 to 1 (1 = only exact repeats)
     * @param compareLast      how many recent messages are compared
     * @param capsRatio        the largest share of capitals allowed, 0 to 1 ({@code 1} turns the check off)
     * @param capsMinLetters   messages with fewer letters are never treated as shouting
     * @param capsAction       lowercase or refuse a shouted message
     */
    record Rules(int maxLength, Duration cooldown, int rateMessages, Duration rateWindow, Duration duplicateWindow,
                 double similarity, int compareLast, double capsRatio, int capsMinLetters, CapsAction capsAction) {
    }

    /**
     * The outcome of a check.
     *
     * @param verdict OK or why the message was refused
     * @param text    the text to send (lowercased when it was shouted and the rules lowercase)
     * @param retryIn for {@link Verdict#TOO_FAST} and {@link Verdict#RATE_LIMITED}: how long until the next message fits
     */
    record Outcome(Verdict verdict, String text, Duration retryIn) {

        boolean allowed() {
            return this.verdict == Verdict.OK;
        }
    }

    /** Messages shorter than this (after normalising) are only compared for exact repeats. */
    static final int SIMILARITY_MIN_LENGTH = 5;
    /** Only this many normalised characters take part in the similarity comparison. */
    private static final int COMPARE_CHARS = 128;
    /** The rate history never grows beyond this, whatever the config says. */
    private static final int MAX_HISTORY = 64;

    private record Sent(long at, String normalized) {
    }

    private static final class History {
        private final Deque<Sent> sent = new ArrayDeque<>();
    }

    private final Map<UUID, History> histories = new ConcurrentHashMap<>();

    /**
     * Checks a message and, if it passes, remembers it.
     *
     * @param checkRepeats whether repeats are refused (private messages to different people may repeat)
     */
    Outcome check(UUID player, String text, long now, Rules rules, boolean checkRepeats) {
        if (text.length() > rules.maxLength()) {
            return new Outcome(Verdict.TOO_LONG, text, Duration.ZERO);
        }
        String result = text;
        boolean shouting = shouting(text, rules.capsRatio(), rules.capsMinLetters());
        if (shouting && rules.capsAction() == CapsAction.BLOCK) {
            return new Outcome(Verdict.CAPS, text, Duration.ZERO);
        }
        if (shouting) {
            result = text.toLowerCase(Locale.ROOT);
        }
        String normalized = normalizeForCompare(result);
        History history = this.histories.computeIfAbsent(player, k -> new History());
        synchronized (history) {
            long keep = Math.max(rules.rateWindow().toMillis(), Math.max(rules.duplicateWindow().toMillis(), rules.cooldown().toMillis()));
            Iterator<Sent> old = history.sent.iterator();
            while (old.hasNext()) {
                if (now - old.next().at() >= keep) {
                    old.remove();
                }
            }
            Sent last = history.sent.peekLast();
            long cooldown = rules.cooldown().toMillis();
            if (last != null && cooldown > 0 && now - last.at() < cooldown) {
                return new Outcome(Verdict.TOO_FAST, text, Duration.ofMillis(cooldown - (now - last.at())));
            }
            long window = rules.rateWindow().toMillis();
            if (rules.rateMessages() > 0 && window > 0) {
                int inWindow = 0;
                long oldest = Long.MAX_VALUE;
                for (Sent sent : history.sent) {
                    if (now - sent.at() < window) {
                        inWindow++;
                        oldest = Math.min(oldest, sent.at());
                    }
                }
                if (inWindow >= rules.rateMessages()) {
                    return new Outcome(Verdict.RATE_LIMITED, text, Duration.ofMillis(Math.max(1, window - (now - oldest))));
                }
            }
            if (checkRepeats && rules.duplicateWindow().toMillis() > 0 && repeats(history, normalized, now, rules)) {
                return new Outcome(Verdict.DUPLICATE, text, Duration.ZERO);
            }
            history.sent.addLast(new Sent(now, normalized));
            while (history.sent.size() > MAX_HISTORY) {
                history.sent.removeFirst();
            }
        }
        return new Outcome(Verdict.OK, result, Duration.ZERO);
    }

    private static boolean repeats(History history, String normalized, long now, Rules rules) {
        long window = rules.duplicateWindow().toMillis();
        int compared = 0;
        Iterator<Sent> recent = history.sent.descendingIterator();
        while (recent.hasNext() && compared < rules.compareLast()) {
            Sent sent = recent.next();
            if (now - sent.at() >= window) {
                break;
            }
            compared++;
            if (sent.normalized().equals(normalized)) {
                return true;
            }
            if (normalized.length() >= SIMILARITY_MIN_LENGTH && sent.normalized().length() >= SIMILARITY_MIN_LENGTH
                && similarity(sent.normalized(), normalized) >= rules.similarity()) {
                return true;
            }
        }
        return false;
    }

    /** Forgets a player (they left). */
    void forget(UUID player) {
        this.histories.remove(player);
    }

    /** Drops histories with nothing recent in them. */
    void sweep(long now, Duration keep) {
        long millis = keep.toMillis();
        this.histories.entrySet().removeIf(entry -> {
            History history = entry.getValue();
            synchronized (history) {
                Sent last = history.sent.peekLast();
                return last == null || now - last.at() >= millis;
            }
        });
    }

    int tracked() {
        return this.histories.size();
    }

    /**
     * The form two messages are compared in: lowercase, letters and digits only, and runs of one character
     * shortened to one ({@code heyyyyy} and {@code hey} compare equal). Symbol-only messages keep their symbols so
     * {@code ???} still repeats {@code ???}.
     */
    static String normalizeForCompare(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Math.min(lower.length(), COMPARE_CHARS));
        for (int i = 0; i < lower.length() && sb.length() < COMPARE_CHARS; i++) {
            char c = lower.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                continue;
            }
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == c) {
                continue;
            }
            sb.append(c);
        }
        if (sb.isEmpty()) {
            String stripped = lower.strip().replaceAll("\\s+", " ");
            return stripped.length() > COMPARE_CHARS ? stripped.substring(0, COMPARE_CHARS) : stripped;
        }
        return sb.toString();
    }

    /** 1 for equal strings, 0 for completely different ones (one minus the normalised edit distance). */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 1.0;
        }
        return 1.0 - (double) distance(a, b) / longest;
    }

    /** The Levenshtein distance, with two rows of memory. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Whether a message counts as shouting: enough letters, and too many of them capitals. */
    static boolean shouting(String text, double maxRatio, int minLetters) {
        if (maxRatio >= 1.0) {
            return false;
        }
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters >= minLetters && letters > 0 && (double) upper / letters > maxRatio;
    }
}
