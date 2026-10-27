package net.siftvanilla.siftcore.ui.dialog;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * A dialog input. Every value the client sends back is re-validated against the input's own definition
 * (length, allowed options, range and step) before any handler sees it.
 */
public sealed interface Input {

    String key();

    Component label();

    /** Free text. */
    record Text(String key, Component label, String initial, int maxLength, int lines, int width) implements Input {
        public Text {
            Objects.requireNonNull(key);
            initial = initial == null ? "" : initial;
            if (maxLength < 1) {
                throw new IllegalArgumentException("maxLength must be positive");
            }
            if (initial.length() > maxLength) {
                initial = initial.substring(0, maxLength);
            }
            lines = Math.clamp(lines, 1, 8);
            width = Math.clamp(width, 1, 1024);
        }

        public Text withInitial(String value) {
            return new Text(this.key, this.label, value, this.maxLength, this.lines, this.width);
        }
    }

    /** An on/off switch. */
    record Toggle(String key, Component label, boolean initial) implements Input {
        public Toggle withInitial(boolean value) {
            return new Toggle(this.key, this.label, value);
        }
    }

    /** One option out of a list. */
    record Choice(String key, Component label, List<Option> options, String initial, int width) implements Input {
        public Choice {
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("A choice needs options");
            }
            boolean known = false;
            for (Option option : options) {
                known |= option.id().equals(initial);
            }
            if (!known) {
                initial = options.getFirst().id();
            }
            width = Math.clamp(width, 1, 1024);
        }

        public boolean allows(String id) {
            return this.options.stream().anyMatch(o -> o.id().equals(id));
        }

        public Choice withInitial(String id) {
            return new Choice(this.key, this.label, this.options, id, this.width);
        }
    }

    /** An option of a {@link Choice}. */
    record Option(String id, Component label) {
        public Option {
            if (id == null || id.isEmpty() || id.length() > 64) {
                throw new IllegalArgumentException("Option id must be 1-64 characters");
            }
        }
    }

    /**
     * A slider over whole numbers from {@code min} to {@code max} in steps of {@code step}.
     * {@code labelFormat} is the vanilla translation pattern, {@code %s: %s} by default.
     */
    record Range(String key, Component label, long min, long max, long step, long initial, String labelFormat, int width) implements Input {
        public Range {
            if (max <= min || step < 1) {
                throw new IllegalArgumentException("Invalid range " + min + ".." + max + " step " + step);
            }
            initial = Math.clamp(initial, min, max);
            labelFormat = labelFormat == null ? "options.generic_value" : labelFormat;
            width = Math.clamp(width, 1, 1024);
        }

        public boolean allows(long value) {
            return value >= this.min && value <= this.max && (value - this.min) % this.step == 0;
        }

        public Range withInitial(long value) {
            return new Range(this.key, this.label, this.min, this.max, this.step, value, this.labelFormat, this.width);
        }
    }
}
