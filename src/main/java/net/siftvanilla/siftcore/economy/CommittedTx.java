package net.siftvanilla.siftcore.economy;

import java.util.List;
import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.Posting;

/** A committed transaction as seen by listeners. {@code balancesAfter[i]} is the balance after posting {@code i}. */
public record CommittedTx(UUID id, String actor, String kind, List<Posting> postings, long[] balancesAfter, long timestamp) {
}
