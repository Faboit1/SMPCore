package net.siftvanilla.siftcore.core.player.options;

import java.util.Locale;
import java.util.Objects;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * When to ask for confirmation before spending: the shared vocabulary of every "confirm from" setting. The options
 * are {@code server} (follow the server's rule), {@code always}, amount presets such as {@code 10k}, and
 * {@code never}. Presets are parsed with the default money suffixes, so their ids stay stable whatever the server's
 * currency format is.
 *
 * @param kind   which rule
 * @param amount the preset amount for {@link Kind#FROM}, otherwise 0
 * @param id     the stored option id
 */
public record ConfirmAbove(Kind kind, long amount, String id) {

    /** The kinds of rule. */
    public enum Kind {
        SERVER,
        ALWAYS,
        FROM,
        NEVER
    }

    public static final ConfirmAbove SERVER = new ConfirmAbove(Kind.SERVER, 0, "server");
    public static final ConfirmAbove ALWAYS = new ConfirmAbove(Kind.ALWAYS, 0, "always");
    public static final ConfirmAbove NEVER = new ConfirmAbove(Kind.NEVER, 0, "never");

    public ConfirmAbove {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
        if (kind == Kind.FROM ? amount <= 0 : amount != 0) {
            throw new IllegalArgumentException("Bad confirm threshold " + kind + " " + amount);
        }
    }

    /**
     * A preset such as {@code 10k}, {@code 1m} or {@code 500} (case-insensitive). Throws when it is not a positive
     * whole amount.
     */
    public static ConfirmAbove preset(String preset) {
        String id = preset.strip().toLowerCase(Locale.ROOT);
        MoneyFormat.ParseResult parsed = MoneyFormat.defaults().parse(id);
        if (!parsed.ok()) {
            throw new IllegalArgumentException("Bad confirm preset " + preset);
        }
        return new ConfirmAbove(Kind.FROM, parsed.amount(), id);
    }

    /**
     * Whether to ask before spending {@code total}.
     *
     * @param serverAsks what the server's own rule says for this total (used by {@link Kind#SERVER})
     */
    public boolean asks(long total, boolean serverAsks) {
        return switch (this.kind) {
            case SERVER -> serverAsks;
            case ALWAYS -> true;
            case FROM -> total >= this.amount;
            case NEVER -> false;
        };
    }
}
