package net.siftvanilla.siftcore.core.player.options;

import java.util.Locale;
import java.util.Objects;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Which of other players' announcements to show: the shared vocabulary of the announcement filters. The options are
 * {@code all}, amount presets such as {@code 1m} (only announcements of at least that much), and {@code off}.
 *
 * @param kind    which filter
 * @param minimum the preset amount for {@link Kind#FROM}, otherwise 0
 * @param id      the stored option id
 */
public record Announce(Kind kind, long minimum, String id) {

    /** The kinds of filter. */
    public enum Kind {
        ALL,
        FROM,
        OFF
    }

    public static final Announce ALL = new Announce(Kind.ALL, 0, "all");
    public static final Announce OFF = new Announce(Kind.OFF, 0, "off");

    public Announce {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
        if (kind == Kind.FROM ? minimum <= 0 : minimum != 0) {
            throw new IllegalArgumentException("Bad announcement filter " + kind + " " + minimum);
        }
    }

    /** A preset such as {@code 100k} or {@code 1m} (case-insensitive). Throws when it is not a positive whole amount. */
    public static Announce preset(String preset) {
        String id = preset.strip().toLowerCase(Locale.ROOT);
        MoneyFormat.ParseResult parsed = MoneyFormat.defaults().parse(id);
        if (!parsed.ok()) {
            throw new IllegalArgumentException("Bad announcement preset " + preset);
        }
        return new Announce(Kind.FROM, parsed.amount(), id);
    }

    /** Whether an announcement about {@code amount} shows. */
    public boolean shows(long amount) {
        return switch (this.kind) {
            case ALL -> true;
            case FROM -> amount >= this.minimum;
            case OFF -> false;
        };
    }
}
