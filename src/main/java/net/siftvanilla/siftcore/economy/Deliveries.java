package net.siftvanilla.siftcore.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.storage.Database;
import org.bukkit.inventory.ItemStack;

/**
 * The claim box: items owed to players (auction purchases, expired or cancelled listings, crate rewards, items that
 * did not fit). Every unclaimed delivery is held in memory (bounded by what players have not collected yet) and
 * persisted with full item data. Adding a delivery is part of the economy transaction that caused it; claiming
 * marks it claimed in storage <em>before</em> the item is put into an inventory, so it can never be handed out twice.
 */
public final class Deliveries {

    /** One owed item. */
    public record Delivery(long id, UUID owner, String source, String ref, ItemStack item, long created) {
        public Delivery {
            item = item.clone();
        }

        @Override
        public ItemStack item() {
            return this.item.clone();
        }
    }

    private final Database database;
    private final Ledger ledger;
    private final Logger logger;
    private final Map<UUID, List<Delivery>> pending = new ConcurrentHashMap<>();
    private IdSequence ids;

    public Deliveries(Database database, Ledger ledger, Logger logger) {
        this.database = database;
        this.ledger = ledger;
        this.logger = logger;
    }

    /** Loads every unclaimed delivery. */
    public void load() throws Exception {
        this.ids = IdSequence.forTable(this.database, "deliveries");
        List<Delivery> rows = this.database.read(c -> {
            List<Delivery> list = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, owner, source, ref, item, created FROM deliveries WHERE claimed IS NULL ORDER BY id")) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    try {
                        list.add(new Delivery(id, UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                            ItemStack.deserializeBytes(rs.getBytes(5)), rs.getLong(6)));
                    } catch (RuntimeException e) {
                        this.logger.log(Level.SEVERE, "Delivery " + id + " has unreadable item data and was skipped (it stays in the database)", e);
                    }
                }
            }
            return list;
        }).get();
        this.pending.clear();
        for (Delivery delivery : rows) {
            this.pending.computeIfAbsent(delivery.owner(), k -> new CopyOnWriteArrayList<>()).add(delivery);
        }
    }

    /** Unclaimed deliveries of a player, oldest first. */
    public List<Delivery> of(UUID owner) {
        List<Delivery> list = this.pending.get(owner);
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    public int count(UUID owner) {
        List<Delivery> list = this.pending.get(owner);
        return list == null ? 0 : list.size();
    }

    public int totalPending() {
        int total = 0;
        for (List<Delivery> list : this.pending.values()) {
            total += list.size();
        }
        return total;
    }

    /**
     * Adds a delivery to {@code tx}: it appears in memory when the transaction applies and is stored atomically with
     * it. Item stacks larger than their max stack size are split into several deliveries.
     */
    public LedgerTx.Builder add(LedgerTx.Builder tx, UUID owner, String source, String ref, ItemStack item) {
        if (item == null || item.isEmpty()) {
            return tx;
        }
        int max = Math.max(1, item.getMaxStackSize());
        int remaining = item.getAmount();
        long now = System.currentTimeMillis();
        while (remaining > 0) {
            int amount = Math.min(max, remaining);
            remaining -= amount;
            ItemStack part = item.clone();
            part.setAmount(amount);
            Delivery delivery = new Delivery(this.ids.next(), owner, source, ref, part, now);
            byte[] bytes = part.serializeAsBytes();
            tx.apply(() -> this.pending.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>()).add(delivery),
                () -> remove(delivery));
            tx.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO deliveries (id, owner, source, ref, item, created, claimed) VALUES (?, ?, ?, ?, ?, ?, NULL)")) {
                    ps.setLong(1, delivery.id());
                    ps.setString(2, owner.toString());
                    ps.setString(3, source);
                    if (ref == null) {
                        ps.setNull(4, Types.VARCHAR);
                    } else {
                        ps.setString(4, ref);
                    }
                    ps.setBytes(5, bytes);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        return tx;
    }

    /** Stores items for a player right away (used for overflow and admin grants). */
    public TransactionResult give(UUID owner, String source, String ref, ItemStack item, String actor) {
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent();
        add(tx, owner, source, ref, item);
        return this.ledger.execute(tx.build());
    }

    private void remove(Delivery delivery) {
        List<Delivery> list = this.pending.get(delivery.owner());
        if (list != null) {
            list.removeIf(d -> d.id() == delivery.id());
        }
    }

    /**
     * Claims the given deliveries: they are removed from memory and marked claimed in storage first; when that is
     * committed, {@code grant} receives the items (on whatever thread the commit completes; hop to the player's
     * thread before touching the inventory). Deliveries already claimed elsewhere are skipped.
     */
    public void claim(UUID owner, List<Long> ids, String actor, Consumer<List<ItemStack>> grant, Runnable failed) {
        List<Delivery> claimed = new ArrayList<>();
        LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent();
        tx.check(() -> {
            claimed.clear();
            List<Delivery> list = this.pending.get(owner);
            if (list == null) {
                return "none";
            }
            for (Delivery delivery : list) {
                if (ids.contains(delivery.id())) {
                    claimed.add(delivery);
                }
            }
            return claimed.isEmpty() ? "none" : null;
        });
        tx.apply(() -> claimed.forEach(this::remove), () -> {
            for (Delivery delivery : claimed) {
                this.pending.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>()).add(delivery);
            }
        });
        long now = System.currentTimeMillis();
        tx.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE deliveries SET claimed = ? WHERE id = ? AND claimed IS NULL")) {
                for (Delivery delivery : claimed) {
                    ps.setLong(1, now);
                    ps.setLong(2, delivery.id());
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Delivery " + delivery.id() + " was already claimed in storage");
                    }
                }
            }
            return null;
        });
        TransactionResult result = this.ledger.execute(tx.build());
        if (!result.success()) {
            failed.run();
            return;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                failed.run();
                return;
            }
            List<ItemStack> items = new ArrayList<>(claimed.size());
            for (Delivery delivery : claimed) {
                items.add(delivery.item());
            }
            grant.accept(items);
        });
    }
}
