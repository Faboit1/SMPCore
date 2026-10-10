package net.siftvanilla.siftcore.feature.sell;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /sell history}: a player's recent sales (to the server and to buy orders), newest first, read from the
 * ledger. One entry per sale with what it paid, how long ago and (in the tooltip) what was sold. Read off the world
 * threads, then shown on the player's thread; nothing in it can be clicked.
 */
final class SellHistory {

    /** The most sales the list shows. */
    static final int LIMIT = 200;
    /** Ledger rows read for that many sales (a sale to orders writes up to three rows per order). */
    private static final int ROWS = 1_000;
    /** Note items listed in a tooltip before the rest is cut. */
    private static final int NOTE_LINES = 10;

    /**
     * One sale.
     *
     * @param ts     when (epoch millis)
     * @param total  what the seller received (server, orders after tax)
     * @param orders what buy orders paid after tax
     * @param note   the ledger note ("64 minecraft:diamond, ...")
     */
    record Sale(long ts, long total, long orders, String note) {
    }

    private final Services services;

    SellHistory(Services services) {
        this.services = services;
    }

    /** Reads a player's recent sales (newest first). */
    CompletableFuture<List<Sale>> read(UUID player) {
        return this.services.database().read(c -> {
            Map<String, long[]> totals = new LinkedHashMap<>();
            Map<String, String> notes = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT tx_id, ts, kind, delta, note FROM ledger WHERE account = ? "
                + "AND currency = ? AND kind IN ('sell', 'order_fill', 'order_tax') ORDER BY ts DESC, id DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, Currency.MONEY.id());
                ps.setInt(3, ROWS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tx = rs.getString(1);
                        if (!totals.containsKey(tx) && totals.size() >= LIMIT) {
                            continue;
                        }
                        long[] row = totals.computeIfAbsent(tx, k -> new long[3]);
                        row[0] = Math.max(row[0], rs.getLong(2));
                        long delta = rs.getLong(4);
                        row[1] += delta;
                        String kind = rs.getString(3);
                        if (!"sell".equals(kind)) {
                            row[2] += delta;
                        }
                        String note = rs.getString(5);
                        if (note != null) {
                            notes.putIfAbsent(tx, note);
                        }
                    }
                }
            }
            List<Sale> sales = new ArrayList<>(totals.size());
            totals.forEach((tx, row) -> {
                if (row[1] > 0) {
                    sales.add(new Sale(row[0], row[1], Math.max(0, row[2]), notes.getOrDefault(tx, "")));
                }
            });
            return sales;
        });
    }

    /** Reads the sales and opens the list on the player's thread. */
    void open(Player player) {
        read(player.getUniqueId()).whenComplete((sales, error) -> this.services.scheduler().entity(player, () -> {
            if (error != null) {
                this.services.messenger().send(player, CoreMessages.ACTION_FAILED);
                return;
            }
            new Menu(this.services, player, sales).open();
        }, null));
    }

    /** The parts of a note: {@code [amount, item key]} pairs and the rest ("+3 more", "312 from shulker boxes"). */
    static List<String[]> noteItems(String note) {
        List<String[]> items = new ArrayList<>();
        if (note == null || note.isBlank()) {
            return items;
        }
        String text = note;
        int bracket = text.indexOf(" (");
        if (bracket >= 0) {
            text = text.substring(0, bracket);
        }
        for (String part : text.split(", ")) {
            String[] words = part.strip().split(" ", 2);
            if (words.length == 2 && !words[0].startsWith("+") && words[0].chars().allMatch(Character::isDigit)) {
                items.add(new String[] {words[0], words[1]});
            }
        }
        return items;
    }

    /** The paged list. */
    private static final class Menu extends PagedMenu<Sale> {

        private final Services services;
        private final List<Sale> sales;

        Menu(Services services, Player viewer, List<Sale> sales) {
            super(services.menus(), viewer, Component.text(services.lang().plain(SellMessages.HISTORY_TITLE)), null, null, null);
            this.services = services;
            this.sales = List.copyOf(sales);
        }

        @Override
        protected List<Sale> entries() {
            return this.sales;
        }

        @Override
        protected ItemStack icon(Sale sale) {
            Lang lang = this.services.lang();
            List<String[]> items = noteItems(sale.note());
            Material material = Material.PAPER;
            if (!items.isEmpty()) {
                Material first = Material.matchMaterial(items.getFirst()[1]);
                if (first != null && first.isItem() && !first.isAir()) {
                    material = first;
                }
            }
            List<Component> lore = new ArrayList<>();
            Duration ago = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - sale.ts()));
            lore.addAll(lang.lines(SellMessages.HISTORY_AGO, Arg.time("time", ago.isZero() ? Duration.ofSeconds(1) : ago)));
            if (sale.orders() > 0) {
                lore.addAll(lang.lines(SellMessages.HISTORY_ORDERS, Arg.money("orders", sale.orders())));
            }
            int shown = 0;
            for (String[] item : items) {
                if (shown++ == NOTE_LINES) {
                    break;
                }
                lore.addAll(lang.lines(SellMessages.HISTORY_ITEMS, Arg.text("items", item[0] + " " + ItemKeys.name(item[1]))));
            }
            int bracket = sale.note().indexOf(" (");
            if (bracket >= 0 && sale.note().endsWith(")")) {
                lore.addAll(lang.lines(SellMessages.HISTORY_ITEMS,
                    Arg.text("items", sale.note().substring(bracket + 2, sale.note().length() - 1))));
            }
            if (items.size() > NOTE_LINES || sale.note().contains(" more")) {
                lore.addAll(lang.lines(SellMessages.HISTORY_MORE));
            }
            return Items.icon(material, lang.get(SellMessages.HISTORY_ENTRY, Arg.money("total", sale.total())), lore);
        }

        @Override
        protected void clicked(Sale sale, ClickContext click) {
        }

        @Override
        protected String searchText(Sale sale) {
            return sale.note().toLowerCase(Locale.ROOT);
        }
    }
}
