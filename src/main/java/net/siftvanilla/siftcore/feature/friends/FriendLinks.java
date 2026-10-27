package net.siftvanilla.siftcore.feature.friends;

import java.util.Objects;
import java.util.UUID;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;

/**
 * The other features the friends system talks to, through their {@code core.link} contracts. Each one is the real
 * implementation once its feature exists and its {@code NONE} until then; every use here is thread-safe.
 *
 * @param combat  combat tags (sneak-click cards are refused in combat)
 * @param ignores ignore lists (hidden requests, no alerts from ignored friends)
 * @param vanish  vanished staff (they look offline everywhere here)
 * @param afk     AFK players (the "AFK" status word)
 * @param teams   teams (team lines, "people I know" privacy, the invite button)
 * @param ranks   rank labels (the rank line of profiles)
 * @param mutes   chat mutes (a muted player can't send a message from a profile)
 */
public record FriendLinks(CombatStatus combat, IgnoreLookup ignores, VanishStatus vanish, AfkStatus afk, TeamLookup teams,
                          Ranks ranks, MuteStatus mutes) {

    public FriendLinks {
        Objects.requireNonNull(combat);
        Objects.requireNonNull(ignores);
        Objects.requireNonNull(vanish);
        Objects.requireNonNull(afk);
        Objects.requireNonNull(teams);
        Objects.requireNonNull(ranks);
        Objects.requireNonNull(mutes);
    }

    /** Whether either of the two ignores the other. */
    public boolean ignoredEitherWay(UUID a, UUID b) {
        return this.ignores.ignores(a, b) || this.ignores.ignores(b, a);
    }
}
