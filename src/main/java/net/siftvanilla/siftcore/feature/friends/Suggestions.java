package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * "People you may know": online players who share friends with the viewer (or are in their team), ranked by how many
 * friends they share. Pure; the caller collects the candidates the viewer can see and passes what memory knows.
 */
public final class Suggestions {

    /**
     * An online player the viewer can see.
     *
     * @param id       the player
     * @param name     their name
     * @param friends  their friends (from memory; they are online, so loaded)
     * @param teammate whether they are in the viewer's team
     * @param privacy  who they take requests from
     * @param blocked  a request is open in either direction, or one of the two ignores the other
     */
    public record Candidate(UUID id, String name, Set<UUID> friends, boolean teammate, Privacy privacy, boolean blocked) {
    }

    /** One suggestion: the player and the number of friends they share with the viewer. */
    public record Suggestion(UUID id, String name, int mutual, boolean teammate) {
    }

    private static final Comparator<Suggestion> ORDER = Comparator.comparingInt(Suggestion::mutual).reversed()
        .thenComparing(Suggestion::teammate, Comparator.reverseOrder())
        .thenComparing(s -> s.name().toLowerCase(Locale.ROOT));

    private Suggestions() {
    }

    /**
     * Ranks the candidates: friends, the viewer, blocked pairs and players whose privacy would refuse the viewer are
     * left out; the rest need at least one shared friend or a shared team. At most {@code max} are returned.
     */
    public static List<Suggestion> rank(UUID viewer, Set<UUID> viewerFriends, List<Candidate> candidates, int max) {
        if (max <= 0) {
            return List.of();
        }
        List<Suggestion> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.id().equals(viewer) || viewerFriends.contains(candidate.id()) || candidate.blocked()) {
                continue;
            }
            int mutual = 0;
            for (UUID friend : candidate.friends()) {
                if (viewerFriends.contains(friend)) {
                    mutual++;
                }
            }
            boolean known = mutual > 0 || candidate.teammate();
            if (!known || !candidate.privacy().allows(true)) {
                continue;
            }
            result.add(new Suggestion(candidate.id(), candidate.name(), mutual, candidate.teammate()));
        }
        result.sort(ORDER);
        return result.size() > max ? List.copyOf(result.subList(0, max)) : List.copyOf(result);
    }
}
