package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The settings search (pure, unit tested). A player types a few words; a setting matches when every word occurs in
 * one of its texts: its id and short name, its label and description, its category's label, its option labels, its
 * unit or its extra search words. Matching ignores case and punctuation, and a word may be the start or any part of a
 * longer one ({@code vol} finds Volume).
 * <p>
 * Results come in this order: settings whose label starts with what was typed, then those whose label holds every
 * word, then the rest; within each, in dialog order.
 */
final class SettingsSearch {

    /** The longest query the search form takes. */
    static final int MAX_QUERY = 32;
    /** Words beyond this many are ignored. */
    static final int MAX_WORDS = 8;

    /**
     * One searchable setting.
     *
     * @param id    the setting id
     * @param label its label (ranked first)
     * @param texts every other text it can be found by
     */
    record Doc(String id, String label, List<String> texts) {
        Doc {
            texts = List.copyOf(texts);
        }
    }

    private record Hit(String id, int rank, int order) {
    }

    private SettingsSearch() {
    }

    /** Lowercase letters and digits, every other run of characters as one space, trimmed. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = true;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /** The words of a query (at most {@link #MAX_WORDS}); empty when nothing searchable was typed. */
    static List<String> words(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> words = Arrays.asList(normalized.split(" "));
        return List.copyOf(words.subList(0, Math.min(words.size(), MAX_WORDS)));
    }

    /** A query as typed, trimmed and cut to {@link #MAX_QUERY} characters (for titles and the search form). */
    static String clean(String query) {
        String stripped = query == null ? "" : query.strip();
        return stripped.length() > MAX_QUERY ? stripped.substring(0, MAX_QUERY).strip() : stripped;
    }

    /** The ids of the settings that match, best first (empty for an empty query). */
    static List<String> match(List<Doc> docs, String query) {
        List<String> words = words(query);
        if (words.isEmpty()) {
            return List.of();
        }
        String whole = String.join(" ", words);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            String label = normalize(doc.label());
            StringBuilder all = new StringBuilder(label);
            for (String text : doc.texts()) {
                all.append(' ').append(normalize(text));
            }
            String haystack = all.toString();
            boolean every = true;
            boolean inLabel = true;
            for (String word : words) {
                every &= haystack.contains(word);
                inLabel &= label.contains(word);
            }
            if (!every) {
                continue;
            }
            int rank = label.startsWith(whole) ? 0 : inLabel ? 1 : 2;
            hits.add(new Hit(doc.id(), rank, i));
        }
        hits.sort(Comparator.comparingInt(Hit::rank).thenComparingInt(Hit::order));
        List<String> ids = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            ids.add(hit.id());
        }
        return ids;
    }
}
