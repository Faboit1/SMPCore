package net.siftvanilla.siftcore.feature.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.storage.Database;

/**
 * How much each player has sent with /pay today (server time zone). The first payment of a day loads the day's
 * total from the ledger; after that the count lives in memory and is changed only inside pay transactions, so the
 * limit check and the payment are atomic. Today's count stays in memory when the player leaves: payments apply at once
 * but are stored a little later, so a total read back from storage on rejoin could miss some (dupe audit R15).
 */
public final class PayLimits {

    /** Pure limit formula: base plus a per-hour bonus for time played, capped. */
    public static long limit(long base, long perHour, long maximum, long hoursPlayed) {
        long bonus;
        try {
            bonus = Math.multiplyExact(perHour, Math.max(0, hoursPlayed));
        } catch (ArithmeticException e) {
            bonus = Long.MAX_VALUE;
        }
        long total = base > Long.MAX_VALUE - bonus ? Long.MAX_VALUE : base + bonus;
        return Math.min(maximum, total);
    }

    private record Day(LocalDate day, long sent) {
    }

    private final Database database;
    private final Clock clock;
    private final Map<UUID, Day> days = new ConcurrentHashMap<>();

    public PayLimits(Database database) {
        this(database, Clock.systemDefaultZone());
    }

    /** @param clock the server's clock and time zone (a fixed one in tests) */
    PayLimits(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(this.clock);
    }

    /** Loads today's total if needed. Completes off-thread. */
    public CompletableFuture<Long> load(UUID player) {
        LocalDate today = today();
        Day day = this.days.get(player);
        if (day != null && day.day().equals(today)) {
            return CompletableFuture.completedFuture(day.sent());
        }
        long since = today.atStartOfDay(this.clock.getZone()).toInstant().toEpochMilli();
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(-delta), 0) FROM ledger WHERE account = ? AND kind = 'pay' AND delta < 0 AND ts >= ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).thenApply(sent -> {
            this.days.merge(player, new Day(today, sent), (old, fresh) -> old.day().equals(today) ? old : fresh);
            return this.days.get(player).sent();
        });
    }

    /** Sent today (call {@link #load} first; returns 0 for a new day). Use only inside the economy lock. */
    public long sent(UUID player) {
        Day day = this.days.get(player);
        return day == null || !day.day().equals(today()) ? 0 : day.sent();
    }

    /** Adds to today's total (inside the economy lock). */
    public void add(UUID player, long amount) {
        LocalDate today = today();
        this.days.compute(player, (k, day) -> day == null || !day.day().equals(today)
            ? new Day(today, amount) : new Day(today, day.sent() + amount));
    }

    /**
     * A player left. Their count for today stays: a payment they made may still be waiting for storage, and reading the
     * total back from the ledger when they pay again would not see it, so leaving and rejoining would reset the limit.
     * Counts of past days are dropped (everyone's: memory holds at most today's payers).
     */
    public void forget(UUID player) {
        LocalDate today = today();
        this.days.values().removeIf(day -> !day.day().equals(today));
    }
}
