package net.siftvanilla.siftcore.feature.auction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.economy.IdSequence;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.Database;

/**
 * The auction house's transactions. Every change to a listing is one {@link LedgerTx}: its checks validate the
 * listing under the economy lock, its apply/revert pair changes the {@link ListingBook}, its writes update the
 * listing row (guarded by {@code state = 'ACTIVE'}, so storage refuses a second close even if memory were wrong) and
 * owed items join the same transaction through the claim box. Money, listing state, row and delivery therefore
 * commit or fail together, and a failed commit is reverted in memory by the ledger.
 * <p>
 * The engine knows nothing about the server: items are an opaque payload with a {@link Codec}, and the claim box is a
 * {@link Delivery} callback. That keeps it testable against a real ledger and database.
 *
 * @param <T> the item payload type ({@code ItemStack} on the server)
 */
public final class AuctionEngine<T> {

    /** Turns item payloads into stored bytes and back. */
    public interface Codec<T> {
        byte[] encode(T item);

        /** Throws a {@link RuntimeException} for unreadable data. */
        T decode(byte[] bytes);
    }

    /** Adds an item owed to {@code owner} to a transaction (the claim box on the server). */
    @FunctionalInterface
    public interface Delivery<T> {
        void add(LedgerTx.Builder tx, UUID owner, String ref, T item);
    }

    /** A listing to create. */
    public record Draft<T>(UUID seller, T item, String typeKey, String searchText, Category category, int amount,
                           long price, Duration duration) {
        public Draft {
            Objects.requireNonNull(seller, "seller");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(duration, "duration");
        }
    }

    /**
     * The outcome of creating a listing.
     *
     * @param result  the transaction result; when not successful nothing changed
     * @param listing the listing that was (or would have been) created
     * @param saved   completes when the row is committed and the listing can be bought; completes exceptionally when
     *                storing failed (the listing is then gone again and the item must be returned)
     */
    public record Created<T>(TransactionResult result, Listing<T> listing, CompletableFuture<Void> saved) {
    }

    /** The result of closing one listing. */
    public record Closed<T>(Listing<T> listing, TransactionResult result) {
    }

    /** A sale or purchase in a player's history, newest first. */
    public record HistoryEntry<T>(long id, boolean sale, UUID counterparty, T item, int amount, long price, long tax,
                                  long closedAt) {
    }

    /** What startup loaded. */
    public record LoadResult(int loaded, int unreadable) {
    }

    /** The claim box source of every auction delivery. */
    public static final String SOURCE = "auction";
    /** Ledger kind of the money moving from buyer to seller. */
    public static final String KIND_SALE = "ah_sale";
    /** Ledger kind of the tax removed from the seller. */
    public static final String KIND_TAX = "ah_tax";

    private static final String COLUMNS = "id, seller, item, item_type, search_name, category, amount, price, created, expires";

    private final Ledger ledger;
    private final Database database;
    private final Codec<T> codec;
    private final Delivery<T> delivery;
    private final LongSupplier clock;
    private final Logger logger;
    private final ListingBook<T> book = new ListingBook<>();
    private volatile IdSequence ids;
    private volatile int unreadable;

    public AuctionEngine(Ledger ledger, Database database, Codec<T> codec, Delivery<T> delivery, LongSupplier clock,
                         Logger logger) {
        this.ledger = ledger;
        this.database = database;
        this.codec = codec;
        this.delivery = delivery;
        this.clock = clock;
        this.logger = logger;
    }

    public ListingBook<T> book() {
        return this.book;
    }

    public long now() {
        return this.clock.getAsLong();
    }

    /** Active rows that could not be loaded at startup (their items are unreadable); they stay in storage. */
    public int unreadable() {
        return this.unreadable;
    }

    // ------------------------------------------------------------------ startup

    private record Row(long id, UUID seller, byte[] item, String type, String search, String category, int amount,
                       long price, long created, long expires) {
    }

    /** Loads every active listing. Blocking; call once at startup before any transaction. */
    public LoadResult load() throws Exception {
        this.ids = IdSequence.forTable(this.database, "auction_listings");
        List<Row> rows = this.database.read(c -> {
            List<Row> list = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + COLUMNS + " FROM auction_listings WHERE state = 'ACTIVE' ORDER BY id")) {
                while (rs.next()) {
                    list.add(new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getBytes(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8), rs.getLong(9), rs.getLong(10)));
                }
            }
            return list;
        }).get();
        this.book.clear();
        int bad = 0;
        for (Row row : rows) {
            try {
                T item = this.codec.decode(row.item());
                Category category = Category.byId(row.category());
                this.book.open(new Listing<>(row.id(), row.seller(), item, row.type(), row.search(),
                    category == null ? Category.MISC : category, row.amount(), row.price(), row.created(), row.expires()), true);
            } catch (RuntimeException e) {
                bad++;
                this.logger.log(Level.SEVERE, "Auction listing " + row.id() + " could not be loaded and stays in storage untouched", e);
            }
        }
        this.unreadable = bad;
        return new LoadResult(rows.size() - bad, bad);
    }

    // ------------------------------------------------------------------ listing

    /**
     * Creates a listing whose item was already taken from the seller. Checks the slot limit under the economy lock.
     * When the result is not successful, or {@code saved} fails, the caller must give the item back.
     */
    public Created<T> create(Draft<T> draft, int slotLimit, String actor) {
        long now = this.clock.getAsLong();
        long id = this.ids.next();
        Listing<T> listing = new Listing<>(id, draft.seller(), draft.item(), truncate(draft.typeKey(), 96),
            truncate(draft.searchText(), 160), draft.category(), draft.amount(), draft.price(), now,
            Math.addExact(now, Math.max(1L, draft.duration().toMillis())));
        byte[] bytes = this.codec.encode(draft.item());
        LedgerTx tx = LedgerTx.builder()
            .actor(actor)
            .note("auction list " + id)
            .silent()
            .check(() -> this.book.count(draft.seller()) >= slotLimit ? Refusal.SLOTS_FULL.id() : null)
            .apply(() -> this.book.open(listing, false), () -> this.book.discard(id))
            .write(c -> {
                insert(c, listing, bytes);
                return null;
            })
            .build();
        TransactionResult result = this.ledger.executeDomain(tx);
        CompletableFuture<Void> saved = result.success()
            ? result.committed().thenRun(() -> this.book.markSaved(id))
            : CompletableFuture.completedFuture(null);
        return new Created<>(result, listing, saved);
    }

    private static void insert(Connection c, Listing<?> listing, byte[] item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO auction_listings (" + COLUMNS
            + ", state, buyer, closed_at, tax) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)")) {
            ps.setLong(1, listing.id());
            ps.setString(2, listing.seller().toString());
            ps.setBytes(3, item);
            ps.setString(4, listing.typeKey());
            ps.setString(5, listing.searchText());
            ps.setString(6, listing.category().id());
            ps.setInt(7, listing.amount());
            ps.setLong(8, listing.price());
            ps.setLong(9, listing.created());
            ps.setLong(10, listing.expires());
            ps.setString(11, ListingState.ACTIVE.name());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ closing

    /**
     * Buys a listing: the buyer pays the seller the confirmed price, the seller pays the tax, the listing becomes
     * SOLD and the item goes to the buyer's claim box, all in one transaction.
     */
    public TransactionResult buy(UUID buyer, long id, long expectedPrice, int taxBasisPoints, String actor) {
        Listing<T> listing = this.book.get(id);
        if (listing == null) {
            return refused(Refusal.GONE);
        }
        if (buyer.equals(listing.seller())) {
            return refused(Refusal.OWN_LISTING);
        }
        long now = this.clock.getAsLong();
        long tax = AuctionMath.tax(listing.price(), taxBasisPoints);
        String ref = listing.ref();
        ListingBook.Sale sale = new ListingBook.Sale(buyer, expectedPrice, now);
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(actor)
            .note("auction buy " + id)
            .transfer(buyer, listing.seller(), Currency.MONEY, listing.price(), KIND_SALE, ref);
        if (tax > 0) {
            tx.sink(listing.seller(), Currency.MONEY, tax, KIND_TAX, ref);
        }
        tx.check(() -> refusal(listing, sale))
            .apply(() -> this.book.close(id), () -> this.book.reopen(listing))
            .write(c -> {
                markClosed(c, id, sale.result(), buyer, now, tax);
                return null;
            });
        this.delivery.add(tx, buyer, ref, listing.item());
        return this.ledger.execute(tx.build());
    }

    /** Cancels a listing; the item goes to the seller's claim box. {@code by} null means staff. */
    public TransactionResult cancel(long id, UUID by, String actor) {
        return close(id, new ListingBook.Cancellation(by), actor);
    }

    /** Expires a listing whose time ran out; the item goes to the seller's claim box. */
    public TransactionResult expire(long id, String actor) {
        return close(id, new ListingBook.Expiry(this.clock.getAsLong()), actor);
    }

    /** Expires every listing whose time ran out, one transaction each. */
    public List<Closed<T>> expireDue(String actor) {
        List<Closed<T>> closed = new ArrayList<>();
        for (Listing<T> listing : this.book.due(this.clock.getAsLong())) {
            closed.add(new Closed<>(listing, expire(listing.id(), actor)));
        }
        return closed;
    }

    /** Takes a listing down without a sale (cancellation or expiry): the item goes back to the seller's claim box. */
    private TransactionResult close(long id, ListingBook.Closing closing, String actor) {
        if (closing instanceof ListingBook.Sale) {
            throw new IllegalArgumentException("A sale moves money; use buy");
        }
        Listing<T> listing = this.book.get(id);
        if (listing == null) {
            return refused(Refusal.GONE);
        }
        ListingState state = closing.result();
        long now = this.clock.getAsLong();
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(actor)
            .note("auction " + state.name().toLowerCase(java.util.Locale.ROOT) + " " + id)
            .silent()
            .check(() -> refusal(listing, closing))
            .apply(() -> this.book.close(id), () -> this.book.reopen(listing))
            .write(c -> {
                markClosed(c, id, state, null, now, 0);
                return null;
            });
        this.delivery.add(tx, listing.seller(), listing.ref(), listing.item());
        return this.ledger.executeDomain(tx.build());
    }

    /** The domain check: the transition is allowed and the book still holds exactly this listing. */
    private String refusal(Listing<T> listing, ListingBook.Closing closing) {
        Refusal refusal = this.book.check(listing.id(), closing);
        if (refusal != null) {
            return refusal.id();
        }
        return this.book.get(listing.id()) == listing ? null : Refusal.GONE.id();
    }

    private static void markClosed(Connection c, long id, ListingState state, UUID buyer, long now, long tax) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE auction_listings SET state = ?, buyer = ?, closed_at = ?, tax = ? WHERE id = ? AND state = 'ACTIVE'")) {
            ps.setString(1, state.name());
            if (buyer == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, buyer.toString());
            }
            ps.setLong(3, now);
            ps.setLong(4, tax);
            ps.setLong(5, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Auction listing " + id + " is not active in storage");
            }
        }
    }

    private static TransactionResult refused(Refusal refusal) {
        return TransactionResult.failed(UUID.randomUUID(), TransactionStatus.REJECTED, refusal.id());
    }

    private static String truncate(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() > max ? value.substring(0, max) : value;
    }

    // ------------------------------------------------------------------ reads

    private record HistoryRow(long id, boolean sale, String counterparty, byte[] item, int amount, long price, long tax,
                              long closedAt) {
    }

    /** A player's latest sales and purchases, newest first. Unreadable items are skipped. */
    public CompletableFuture<List<HistoryEntry<T>>> history(UUID player, int limit) {
        String uuid = player.toString();
        return this.database.read(c -> {
            List<HistoryRow> rows = new ArrayList<>();
            readHistory(c, "SELECT id, buyer, item, amount, price, tax, closed_at FROM auction_listings "
                + "WHERE seller = ? AND state = 'SOLD' ORDER BY closed_at DESC LIMIT ?", uuid, limit, true, rows);
            readHistory(c, "SELECT id, seller, item, amount, price, tax, closed_at FROM auction_listings "
                + "WHERE buyer = ? AND state = 'SOLD' ORDER BY closed_at DESC LIMIT ?", uuid, limit, false, rows);
            return rows;
        }).thenApply(rows -> {
            rows.sort(Comparator.comparingLong(HistoryRow::closedAt).reversed()
                .thenComparing(Comparator.comparingLong(HistoryRow::id).reversed()));
            List<HistoryEntry<T>> entries = new ArrayList<>();
            for (HistoryRow row : rows) {
                if (entries.size() >= limit) {
                    break;
                }
                T item;
                try {
                    item = this.codec.decode(row.item());
                } catch (RuntimeException e) {
                    this.logger.log(Level.WARNING, "Auction listing " + row.id() + " has an unreadable item; it is left out of the history", e);
                    continue;
                }
                UUID counterparty = row.counterparty() == null ? null : UUID.fromString(row.counterparty());
                entries.add(new HistoryEntry<>(row.id(), row.sale(), counterparty, item, row.amount(), row.price(), row.tax(), row.closedAt()));
            }
            return entries;
        });
    }

    private static void readHistory(Connection c, String sql, String uuid, int limit, boolean sale, List<HistoryRow> into)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.add(new HistoryRow(rs.getLong(1), sale, rs.getString(2), rs.getBytes(3), rs.getInt(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)));
                }
            }
        }
    }

    /**
     * Compares the listings in memory with the active rows in storage at one consistent point: the snapshot is taken
     * under the economy lock and the comparison is queued on the ordered writer at the same moment, so it sees exactly
     * the writes of the transactions applied before the snapshot. Completes with null when they match.
     */
    public CompletableFuture<String> verify() {
        return this.ledger.locked(() -> {
            Set<Long> memory = new HashSet<>();
            for (Listing<T> listing : this.book.all()) {
                memory.add(listing.id());
            }
            return this.database.write(c -> {
                Set<Long> stored = new HashSet<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id FROM auction_listings WHERE state = 'ACTIVE'")) {
                    while (rs.next()) {
                        stored.add(rs.getLong(1));
                    }
                }
                Set<Long> notStored = new HashSet<>(memory);
                notStored.removeAll(stored);
                Set<Long> notLoaded = new HashSet<>(stored);
                notLoaded.removeAll(memory);
                if (notStored.isEmpty() && notLoaded.size() == this.unreadable) {
                    return null;
                }
                List<String> problems = new ArrayList<>();
                if (!notStored.isEmpty()) {
                    problems.add(notStored.size() + " listing(s) in memory are not active in storage " + sample(notStored));
                }
                if (notLoaded.size() != this.unreadable) {
                    problems.add(notLoaded.size() + " active row(s) are not in memory " + sample(notLoaded));
                }
                return String.join("; ", problems);
            });
        });
    }

    private static String sample(Set<Long> ids) {
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        return sorted.subList(0, Math.min(5, sorted.size())).toString();
    }
}
