package net.siftvanilla.siftcore.core.player;

import java.util.Objects;
import java.util.Optional;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A per-player whole number from {@code min} to {@code max} in steps of {@code step}, shown as a slider. The slider
 * sends floats, so values stay within {@link #MAX_ABS} (exact as a float) and the range is a whole number of steps
 * (otherwise the slider's end would be refused). Large money thresholds belong in a {@link Choice} of presets.
 * <p>
 * A stored number outside the range or off the step is brought into range and snapped to the nearest step, so
 * narrowing a range later does not reset players; text that is not a number reads as the default.
 *
 * @param defaultNumber the value for players who never changed it
 * @param unit          text after the number (for example {@code %} or {@code  keys}, with its own leading space), or null
 */
public record NumberSetting(String id, long defaultNumber, long min, long max, long step, MessageKey unit, MessageKey label,
                            MessageKey description, String permission) implements PlayerSetting<Long> {

    /** Integers up to 2^24 survive the float round trip of the dialog slider exactly. */
    public static final long MAX_ABS = 1L << 24;
    /** More positions than this make a slider fiddly. */
    public static final long MAX_STEPS = 1000;

    public NumberSetting {
        Objects.requireNonNull(id);
        Objects.requireNonNull(label, "label of " + id);
        Objects.requireNonNull(description, "description of " + id);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid setting id " + id);
        }
        if (min >= max) {
            throw new IllegalArgumentException(id + ": min must be below max");
        }
        if (step < 1) {
            throw new IllegalArgumentException(id + ": step must be at least 1");
        }
        if (Math.abs(min) > MAX_ABS || Math.abs(max) > MAX_ABS) {
            throw new IllegalArgumentException(id + ": values must stay within " + MAX_ABS);
        }
        if ((max - min) % step != 0) {
            throw new IllegalArgumentException(id + ": the range must be a whole number of steps");
        }
        if ((max - min) / step > MAX_STEPS) {
            throw new IllegalArgumentException(id + ": more than " + MAX_STEPS + " steps");
        }
        if (defaultNumber < min || defaultNumber > max || (defaultNumber - min) % step != 0) {
            throw new IllegalArgumentException(id + ": the default must be in range and on a step");
        }
    }

    /** The default as a {@code Long} (the record keeps it as {@link #defaultNumber()}). */
    @Override
    public Long defaultValue() {
        return this.defaultNumber;
    }

    @Override
    public Kind kind() {
        return Kind.NUMBER;
    }

    /** Brings a value into range and onto the nearest step (halfway rounds up). */
    public long snap(long value) {
        long clamped = Math.clamp(value, this.min, this.max);
        long steps = Math.floorDiv(clamped - this.min + this.step / 2, this.step);
        return Math.min(this.max, this.min + steps * this.step);
    }

    /** Whether a value is in range and on a step. */
    public boolean allows(long value) {
        return value >= this.min && value <= this.max && (value - this.min) % this.step == 0;
    }

    @Override
    public String encode(Long value) {
        return Long.toString(Objects.requireNonNull(value));
    }

    @Override
    public Optional<Long> decode(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        String text = stored.strip();
        if (text.isEmpty() || text.length() > 20) {
            return Optional.empty();
        }
        try {
            return Optional.of(snap(Long.parseLong(text)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Parses typed text strictly: a whole number that is in range and on a step, otherwise null. */
    public Long parseExact(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 20) {
            return null;
        }
        try {
            long value = Long.parseLong(trimmed);
            return allows(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean valid(Long value) {
        return value != null && allows(value);
    }

    @Override
    public String display(Lang lang, Long value) {
        return Lang.number(value == null ? this.defaultNumber : value) + (this.unit == null ? "" : lang.plain(this.unit));
    }

    @Override
    public Long cast(Object value) {
        return switch (value) {
            case Long l -> l;
            case Integer i -> i.longValue();
            case null, default -> null;
        };
    }
}
