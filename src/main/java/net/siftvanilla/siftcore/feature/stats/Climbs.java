package net.siftvanilla.siftcore.feature.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Who moved up a leaderboard between two rebuilds ({@code leaderboard-rank-alerts}). Pure: the caller hands the old
 * and the new boards and the players to check (the online ones), this compares their places. A climb is a better
 * place than before, or a place on a board they were not on; dropping, staying and the first build after a start
 * (nothing to compare with) are not. The deaths board is left out: nobody wants to hear they died more.
 */
final class Climbs {

    /** One player moved up one board to {@code rank}. */
    record Climb(UUID player, Board board, int rank) {
    }

    private Climbs() {
    }

    /** Whether a board's places count for climb alerts. */
    static boolean counts(Board board) {
        return board != Board.DEATHS;
    }

    /**
     * The climbs of {@code players} that their own choice tells about, at most one per player and board.
     *
     * @param choices each player's {@link RankAlerts} choice
     */
    static List<Climb> of(Map<Board, Leaderboard> before, Map<Board, Leaderboard> after, Collection<UUID> players,
                          Function<UUID, RankAlerts> choices) {
        List<Climb> climbs = new ArrayList<>();
        for (UUID player : players) {
            RankAlerts choice = choices.apply(player);
            if (choice == null || choice == RankAlerts.OFF) {
                continue;
            }
            for (Board board : Board.values()) {
                Leaderboard old = before.get(board);
                Leaderboard now = after.get(board);
                if (!counts(board) || old == null || now == null || old.builtAt() == 0) {
                    continue;
                }
                int rank = now.rankOf(player);
                if (climbed(old.rankOf(player), rank) && choice.tells(rank)) {
                    climbs.add(new Climb(player, board, rank));
                }
            }
        }
        return climbs;
    }

    /** Whether going from place {@code before} to {@code after} (0: not on the board) is moving up. */
    static boolean climbed(int before, int after) {
        return after > 0 && (before == 0 || after < before);
    }
}
