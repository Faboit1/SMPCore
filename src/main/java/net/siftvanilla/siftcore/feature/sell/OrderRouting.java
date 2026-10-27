package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.link.OrderMarket;

/**
 * Decides which units of a sale go to buy orders and which to the server. Pure logic.
 * <p>
 * Per item key, units go to the best bids (highest price each, then oldest) for as long as an order's pay per item
 * after tax, {@code priceEach x (10000 - tax) / 10000}, is strictly more than what the server pays this seller for
 * one unit (worth x rank x mastery). The comparison is exact. A bid's remaining amount is shared by every stack of
 * its key, so one order is never promised the same units twice. Units no order takes go to the server when it buys
 * them, and otherwise stay with the player.
 */
public final class OrderRouting {

    private static final BigDecimal BASIS = BigDecimal.valueOf(10_000);

    /**
     * Units of one item a sale could move.
     *
     * @param key           the item key (the order key for plain items)
     * @param units         how many
     * @param serverUnit    what the server pays this seller for one unit, exactly (0 when it doesn't buy the item)
     * @param routeUnpriced whether units the server doesn't buy may go to orders ({@code /sell all} never moves
     *                      items the server doesn't buy)
     */
    public record Line(String key, long units, BigDecimal serverUnit, boolean routeUnpriced) {
        public Line {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(serverUnit, "serverUnit");
            if (units < 0 || serverUnit.signum() < 0) {
                throw new IllegalArgumentException("Units and prices are never negative");
            }
        }

        boolean priced() {
            return this.serverUnit.signum() > 0;
        }
    }

    /**
     * Where the units go.
     *
     * @param takes       units per order, best bid first
     * @param serverUnits per key, the units the server buys
     * @param kept        per key, the units that stay with the player (no buyer)
     */
    public record Allocation(List<OrderMarket.Take> takes, Map<String, Long> serverUnits, Map<String, Long> kept) {
        public Allocation {
            takes = List.copyOf(takes);
            serverUnits = Map.copyOf(serverUnits);
            kept = Map.copyOf(kept);
        }

        /** Units of a key sent to orders. */
        public long routed(String key) {
            long units = 0;
            for (OrderMarket.Take take : this.takes) {
                if (take.key().equals(key)) {
                    units = Math.addExact(units, take.units());
                }
            }
            return units;
        }

        public long server(String key) {
            return this.serverUnits.getOrDefault(key, 0L);
        }

        public long kept(String key) {
            return this.kept.getOrDefault(key, 0L);
        }

        /** Everything goes to the server and nothing stays. */
        public static Allocation serverOnly(Map<String, Long> units) {
            return new Allocation(List.of(), units, Map.of());
        }
    }

    private OrderRouting() {
    }

    /** What one item pays the seller after tax, exactly. */
    public static BigDecimal netEach(long priceEach, int taxBasisPoints) {
        return BigDecimal.valueOf(priceEach).multiply(BigDecimal.valueOf(10_000L - taxBasisPoints)).divide(BASIS);
    }

    /**
     * Plans a sale.
     *
     * @param lines          the units, possibly several lines (stacks) per key
     * @param bids           the open bids per key (any order; they are sorted best first, then oldest)
     * @param taxBasisPoints the order tax
     * @throws ArithmeticException when an amount does not fit in a long
     */
    public static Allocation plan(List<Line> lines, Function<String, List<OrderMarket.Bid>> bids, int taxBasisPoints) {
        if (taxBasisPoints < 0 || taxBasisPoints > 10_000) {
            throw new IllegalArgumentException("Tax is 0 to 10000 basis points");
        }
        // Pool the lines of each key (first line decides the server price; all lines of a key share it).
        Map<String, Long> units = new LinkedHashMap<>();
        Map<String, Line> first = new LinkedHashMap<>();
        for (Line line : lines) {
            units.merge(line.key(), line.units(), Math::addExact);
            Line known = first.putIfAbsent(line.key(), line);
            if (known != null && (known.serverUnit().compareTo(line.serverUnit()) != 0
                || known.routeUnpriced() != line.routeUnpriced())) {
                throw new IllegalArgumentException(line.key() + " has two different server prices in one sale");
            }
        }
        List<OrderMarket.Take> takes = new ArrayList<>();
        Map<String, Long> server = new LinkedHashMap<>();
        Map<String, Long> kept = new LinkedHashMap<>();
        Map<Long, Long> used = new HashMap<>();
        for (Map.Entry<String, Long> entry : units.entrySet()) {
            String key = entry.getKey();
            long left = entry.getValue();
            Line line = first.get(key);
            boolean mayRoute = line.priced() || line.routeUnpriced();
            if (mayRoute && left > 0) {
                List<OrderMarket.Bid> sorted = new ArrayList<>(bids.apply(key));
                sorted.sort(Comparator.comparingLong(OrderMarket.Bid::priceEach).reversed()
                    .thenComparingLong(OrderMarket.Bid::created)
                    .thenComparingLong(OrderMarket.Bid::orderId));
                for (OrderMarket.Bid bid : sorted) {
                    if (left == 0) {
                        break;
                    }
                    if (netEach(bid.priceEach(), taxBasisPoints).compareTo(line.serverUnit()) <= 0) {
                        // Sorted best first: no later bid pays more than the server either.
                        break;
                    }
                    long available = bid.remaining() - used.getOrDefault(bid.orderId(), 0L);
                    if (available <= 0) {
                        continue;
                    }
                    long part = Math.min(available, left);
                    takes.add(new OrderMarket.Take(bid.orderId(), key, part, bid.priceEach()));
                    used.merge(bid.orderId(), part, Math::addExact);
                    left -= part;
                }
            }
            if (left > 0) {
                if (line.priced()) {
                    server.put(key, left);
                } else {
                    kept.put(key, left);
                }
            }
        }
        return new Allocation(takes, server, kept);
    }
}
