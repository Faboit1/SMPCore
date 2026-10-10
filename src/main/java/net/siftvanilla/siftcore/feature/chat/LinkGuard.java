package net.siftvanilla.siftcore.feature.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds web and server addresses in chat, so players can't advertise other servers: links with a scheme
 * ({@code https://...}), IPv4 addresses with an optional port ({@code 51.12.3.4:25565}) and domain names that end in
 * a known top-level domain ({@code play.example.net}, {@code example.gg/invite}). Addresses on the allow list (the
 * server's own website, store and Discord invite) pass. Version numbers ({@code 1.21.5}), e-mail addresses and words
 * ending in an unknown top-level domain ({@code file.txt}) are not addresses.
 * <p>
 * Immutable and thread-safe; pure (no server classes) so it is unit tested.
 */
final class LinkGuard {

    /**
     * The verdict.
     *
     * @param text    the message to send (addresses replaced), or the original text when blocked
     * @param matched the addresses found, as typed, in order
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

    /** An allowed address: a host (and its subdomains) or, with a path, one page of a host. */
    record Allowed(String host, String path) {
    }

    private static final Pattern SCHEME = Pattern.compile("(?i)(?<![\\w])(?:https?|ftp)://\\S+");
    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\w.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::\\d{1,5})?(?:/\\S*)?(?![\\w.]*\\w)");
    private static final Pattern DOMAIN = Pattern.compile(
        "(?i)(?<![\\w.@-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,24})(?![\\w-])(?::\\d{1,5})?(?:/\\S*)?");
    /** Punctuation that ends a sentence rather than an address ({@code see example.net!}). */
    private static final String TRAILING = ".,!?;:)]}'\"";

    private final boolean enabled;
    private final Set<String> topLevelDomains;
    private final List<Allowed> allowed;

    /**
     * @param topLevelDomains lowercase top-level domains that make a word an address ({@code net}, {@code gg})
     * @param allowed         addresses that are fine, as written in the config ({@code siftvanilla.com},
     *                        {@code discord.gg/siftvanilla})
     */
    LinkGuard(Set<String> topLevelDomains, List<String> allowed) {
        this(true, topLevelDomains, allowed);
    }

    private LinkGuard(boolean enabled, Set<String> topLevelDomains, List<String> allowed) {
        this.enabled = enabled;
        this.topLevelDomains = Set.copyOf(topLevelDomains);
        List<Allowed> parsed = new ArrayList<>(allowed.size());
        for (String entry : allowed) {
            Allowed address = allowed(entry);
            if (address != null) {
                parsed.add(address);
            }
        }
        this.allowed = List.copyOf(parsed);
    }

    /** A guard that lets everything through (the check is off). */
    static LinkGuard none() {
        return new LinkGuard(false, Set.of(), List.of());
    }

    /** Whether the check is on. */
    boolean enabled() {
        return this.enabled;
    }

    /** The top-level domains that make a word an address (empty when the check is off). */
    Set<String> topLevelDomains() {
        return this.topLevelDomains;
    }

    /** How many allow list entries are in use. */
    int allowedCount() {
        return this.allowed.size();
    }

    /** Parses an allow list entry; null when it is not an address. */
    static Allowed allowed(String entry) {
        if (entry == null) {
            return null;
        }
        String address = strip(entry.strip().toLowerCase(Locale.ROOT));
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        int slash = address.indexOf('/');
        String host = slash < 0 ? address : address.substring(0, slash);
        String path = slash < 0 ? "" : address.substring(slash);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.isEmpty() || !host.contains(".") || !host.matches("[a-z0-9.-]+")) {
            return null;
        }
        return new Allowed(host, path);
    }

    /** Lowercase address without its scheme and a leading {@code www.}. */
    private static String strip(String address) {
        String result = address;
        int scheme = result.indexOf("://");
        if (scheme >= 0) {
            result = result.substring(scheme + 3);
        }
        if (result.startsWith("www.")) {
            result = result.substring(4);
        }
        return result;
    }

    /** Checks a message: replaces the addresses found, or refuses the message. */
    Result apply(String text, ChatFilter.Action action, String replacement) {
        if (!this.enabled || text.isEmpty()) {
            return new Result(text, List.of(), false);
        }
        List<int[]> spans = new ArrayList<>();
        find(SCHEME.matcher(text), text, spans, false, false);
        find(IPV4.matcher(text), text, spans, true, false);
        find(DOMAIN.matcher(text), text, spans, false, true);
        if (spans.isEmpty()) {
            return new Result(text, List.of(), false);
        }
        List<int[]> merged = merge(spans);
        List<String> matched = new ArrayList<>(merged.size());
        for (int[] span : merged) {
            matched.add(text.substring(span[0], span[1]));
        }
        if (action == ChatFilter.Action.BLOCK) {
            return new Result(text, List.copyOf(matched), true);
        }
        StringBuilder out = new StringBuilder(text);
        for (int i = merged.size() - 1; i >= 0; i--) {
            out.replace(merged.get(i)[0], merged.get(i)[1], replacement);
        }
        return new Result(out.toString(), List.copyOf(matched), false);
    }

    private void find(Matcher matcher, String text, List<int[]> spans, boolean ip, boolean domain) {
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            while (end > start && TRAILING.indexOf(text.charAt(end - 1)) >= 0) {
                end--;
            }
            if (end <= start) {
                continue;
            }
            if (ip && !octets(matcher)) {
                continue;
            }
            if (domain && !this.topLevelDomains.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!isAllowed(text.substring(start, end))) {
                spans.add(new int[] {start, end});
            }
        }
    }

    private static boolean octets(Matcher matcher) {
        for (int group = 1; group <= 4; group++) {
            if (Integer.parseInt(matcher.group(group)) > 255) {
                return false;
            }
        }
        return true;
    }

    /** Whether an address as typed is on the allow list. */
    boolean isAllowed(String typed) {
        String address = strip(typed.toLowerCase(Locale.ROOT));
        int slash = address.indexOf('/');
        String host = slash < 0 ? address : address.substring(0, slash);
        String path = slash < 0 ? "" : address.substring(slash);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (Allowed entry : this.allowed) {
            if (entry.path().isEmpty()) {
                if (host.equals(entry.host()) || host.endsWith("." + entry.host())) {
                    return true;
                }
            } else if (host.equals(entry.host()) && (path.equals(entry.path()) || path.startsWith(entry.path() + "/")
                || path.startsWith(entry.path() + "?"))) {
                return true;
            }
        }
        return false;
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
