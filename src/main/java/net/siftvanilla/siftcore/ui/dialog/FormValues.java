package net.siftvanilla.siftcore.ui.dialog;

import java.util.Map;
import java.util.Optional;

/** Validated values submitted with a dialog, keyed by input key. */
public final class FormValues {

    public static final FormValues EMPTY = new FormValues(Map.of());

    private final Map<String, Object> values;

    public FormValues(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public String text(String key) {
        Object value = this.values.get(key);
        return value instanceof String s ? s : "";
    }

    public boolean toggle(String key) {
        return this.values.get(key) instanceof Boolean b && b;
    }

    public String choice(String key) {
        return text(key);
    }

    public long number(String key) {
        Object value = this.values.get(key);
        return value instanceof Long l ? l : 0L;
    }

    public Optional<Object> raw(String key) {
        return Optional.ofNullable(this.values.get(key));
    }

    public Map<String, Object> asMap() {
        return this.values;
    }

    public boolean isEmpty() {
        return this.values.isEmpty();
    }
}
