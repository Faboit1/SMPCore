package net.siftvanilla.siftcore.feature.sell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.link.OrderMarket;

/**
 * Buy-order bids as selling sees them. Sales read fresh bids; previews (the sell menu total, confirmation dialogs,
 * {@code /worth}) read through a small cache keyed by seller and item that is dropped whenever the order book's
 * revision moves, so redrawing a menu never rebuilds bid lists. Thread-safe.
 */
final class OrderBids {

    /** Most cached bid lists. */
    static final int CACHE_SIZE = 2_048;

    private record Key(UUID seller, String item) {
    }

    private final Supplier<OrderMarket> market;
    private final Map<Key, List<OrderMarket.Bid>> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, List<OrderMarket.Bid>> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long revision = Long.MIN_VALUE;

    OrderBids(Supplier<OrderMarket> market) {
        this.market = market;
    }

    OrderMarket market() {
        return this.market.get();
    }

    /** Fresh bids, for a sale. */
    List<OrderMarket.Bid> fresh(UUID seller, String key) {
        OrderMarket market = this.market.get();
        return market.available() ? market.bids(seller, key) : List.of();
    }

    /** Bids from the preview cache (refilled when the book changed). */
    List<OrderMarket.Bid> cached(UUID seller, String key) {
        OrderMarket market = this.market.get();
        if (!market.available()) {
            return List.of();
        }
        long now = market.revision();
        synchronized (this.cache) {
            if (now != this.revision) {
                this.cache.clear();
                this.revision = now;
            }
            List<OrderMarket.Bid> known = this.cache.get(new Key(seller, key));
            if (known != null) {
                return known;
            }
        }
        List<OrderMarket.Bid> bids = List.copyOf(market.bids(seller, key));
        synchronized (this.cache) {
            if (this.revision == now) {
                this.cache.put(new Key(seller, key), bids);
            }
        }
        return bids;
    }

    /** The best bid for an item, or null (preview cache). */
    OrderMarket.Bid best(UUID seller, String key) {
        List<OrderMarket.Bid> bids = cached(seller, key);
        OrderMarket.Bid best = null;
        for (OrderMarket.Bid bid : bids) {
            if (bid.remaining() > 0 && (best == null || bid.priceEach() > best.priceEach())) {
                best = bid;
            }
        }
        return best;
    }
}
