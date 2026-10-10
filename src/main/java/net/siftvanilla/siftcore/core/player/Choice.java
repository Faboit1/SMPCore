package net.siftvanilla.siftcore.core.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A per-player setting with one option out of a short list (shown as a cycling button in the settings dialog). The
 * stored value is the option id, so existing enums keep their stored values ({@link #ofEnum}).
 * <p>
 * An existing toggle can become a choice under the same id: {@link Builder#legacyValue} maps the old stored values
 * (and config entries like {@code death-messages: true}) to options, so old rows keep working and are rewritten on
 * the next change.
 *
 * @param <E> the option value type
 */
public final class Choice<E> implements PlayerSetting<E> {

    /** Option ids: lowercase letters, digits, {@code -} and {@code _}, at most 32 characters. */
    public static final Pattern OPTION_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** A choice is a cycling button on Java Edition: more than a handful of options is slow to use. */
    public static final int MAX_OPTIONS = 6;

    /**
     * One option.
     *
     * @param id            stored value and command word
     * @param value         the typed value features read
     * @param label         its text in the dialog (a {@code ui} key; may declare placeholders filled by {@code args})
     * @param args          placeholder values for the label (for example a money preset)
     * @param permission    permission needed to pick it, or null for everyone (store ranks may sell options)
     * @param unavailableAs what a player who picked it reads while it is not available to them (no permission, or the
     *                      server turned the feature it needs off), as an option id; null means the setting's default
     */
    public record Option<E>(String id, E value, MessageKey label, List<Arg> args, String permission, String unavailableAs) {

        public Option {
            Objects.requireNonNull(id);
            Objects.requireNonNull(value);
            Objects.requireNonNull(label);
            if (!OPTION_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid option id " + id);
            }
            args = List.copyOf(args);
        }

        /** The label as plain text. */
        public String text(Lang lang) {
            return lang.plain(this.label, this.args.toArray(Arg[]::new));
        }
    }

    private final String id;
    private final List<Option<E>> options;
    private final E defaultValue;
    private final MessageKey label;
    private final MessageKey description;
    private final String permission;
    private final Map<String, String> legacyValues;
    private final Map<String, Option<E>> byId;

    private Choice(String id, List<Option<E>> options, E defaultValue, MessageKey label, MessageKey description,
                   String permission, Map<String, String> legacyValues) {
        this.id = Objects.requireNonNull(id);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid setting id " + id);
        }
        this.options = List.copyOf(options);
        this.defaultValue = Objects.requireNonNull(defaultValue, "default of " + id);
        this.label = Objects.requireNonNull(label, "label of " + id);
        this.description = Objects.requireNonNull(description, "description of " + id);
        this.permission = permission;
        if (this.options.size() < 2) {
            throw new IllegalArgumentException("Choice " + id + " needs at least two options");
        }
        if (this.options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("Choice " + id + " has more than " + MAX_OPTIONS + " options");
        }
        Map<String, Option<E>> map = new HashMap<>();
        for (Option<E> option : this.options) {
            if (map.put(option.id(), option) != null) {
                throw new IllegalArgumentException("Choice " + id + " has the option " + option.id() + " twice");
            }
            for (Option<E> other : this.options) {
                if (other != option && other.value().equals(option.value())) {
                    throw new IllegalArgumentException("Choice " + id + " has two options with the same value");
                }
            }
        }
        for (Option<E> option : this.options) {
            if (option.unavailableAs() != null && (option.unavailableAs().equals(option.id()) || !map.containsKey(option.unavailableAs()))) {
                throw new IllegalArgumentException("Option " + option.id() + " of " + id + " falls back to an unknown option " + option.unavailableAs());
            }
        }
        if (optionOf(defaultValue) == null) {
            throw new IllegalArgumentException("The default of " + id + " is not one of its options");
        }
        Map<String, String> legacy = new LinkedHashMap<>();
        legacyValues.forEach((stored, option) -> {
            String key = stored.strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || map.containsKey(key)) {
                throw new IllegalArgumentException("Legacy value '" + stored + "' of " + id + " is empty or an option id");
            }
            if (!map.containsKey(option)) {
                throw new IllegalArgumentException("Legacy value '" + stored + "' of " + id + " maps to an unknown option " + option);
            }
            legacy.put(key, option);
        });
        this.legacyValues = Map.copyOf(legacy);
        Map<String, Option<E>> lookup = new HashMap<>(map);
        this.legacyValues.forEach((stored, option) -> lookup.put(stored, map.get(option)));
        this.byId = Map.copyOf(lookup);
    }

    @Override
    public String id() {
        return this.id;
    }

    /** The options in the order the dialog cycles through them. */
    public List<Option<E>> options() {
        return this.options;
    }

    @Override
    public E defaultValue() {
        return this.defaultValue;
    }

    @Override
    public MessageKey label() {
        return this.label;
    }

    @Override
    public MessageKey description() {
        return this.description;
    }

    @Override
    public String permission() {
        return this.permission;
    }

    /** Old stored values (lowercase) and the option id each one reads as. */
    public Map<String, String> legacyValues() {
        return this.legacyValues;
    }

    @Override
    public Kind kind() {
        return Kind.CHOICE;
    }

    /** The option with this id or legacy value (case and surrounding spaces ignored), or null. */
    public Option<E> option(String id) {
        return id == null ? null : this.byId.get(id.strip().toLowerCase(Locale.ROOT));
    }

    /** The option holding a value, or null. */
    public Option<E> optionOf(E value) {
        if (value == null) {
            return null;
        }
        for (Option<E> option : this.options) {
            if (option.value().equals(value)) {
                return option;
            }
        }
        return null;
    }

    /** The option ids in order. */
    public List<String> optionIds() {
        List<String> ids = new ArrayList<>(this.options.size());
        for (Option<E> option : this.options) {
            ids.add(option.id());
        }
        return ids;
    }

    @Override
    public String encode(E value) {
        Option<E> option = optionOf(value);
        if (option == null) {
            throw new IllegalArgumentException(value + " is not an option of " + this.id);
        }
        return option.id();
    }

    @Override
    public Optional<E> decode(String stored) {
        Option<E> option = option(stored);
        return option == null ? Optional.empty() : Optional.of(option.value());
    }

    @Override
    public boolean valid(E value) {
        return optionOf(value) != null;
    }

    @Override
    public String display(Lang lang, E value) {
        Option<E> option = optionOf(value);
        return option == null ? String.valueOf(value) : option.text(lang);
    }

    @Override
    public E cast(Object value) {
        for (Option<E> option : this.options) {
            if (option.value().equals(value)) {
                return option.value();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Choice[" + this.id + " " + optionIds() + "]";
    }

    /**
     * A builder for a choice over enum constants whose stored ids come from {@code ids} (for example
     * {@code Privacy::id}), so existing stored values stay valid.
     */
    public static <E extends Enum<E>> Builder<E> ofEnum(String id, Class<E> type, Function<E, String> ids, E defaultValue) {
        Objects.requireNonNull(type);
        return new Builder<>(id, defaultValue, ids);
    }

    /** A builder whose options name their ids explicitly ({@link Builder#option(String, Object, MessageKey, Arg...)}). */
    public static <E> Builder<E> builder(String id, E defaultValue) {
        return new Builder<>(id, defaultValue, null);
    }

    /** A builder whose option ids come from {@code ids}. */
    public static <E> Builder<E> builder(String id, E defaultValue, Function<E, String> ids) {
        return new Builder<>(id, defaultValue, ids);
    }

    /** Builds a {@link Choice}; {@link #build()} checks every rule. */
    public static final class Builder<E> {

        private final String id;
        private final E defaultValue;
        private final Function<E, String> ids;
        private final List<Option<E>> options = new ArrayList<>();
        private final Map<String, String> legacy = new LinkedHashMap<>();
        private MessageKey label;
        private MessageKey description;
        private String permission;

        private Builder(String id, E defaultValue, Function<E, String> ids) {
            this.id = id;
            this.defaultValue = defaultValue;
            this.ids = ids;
        }

        /** Adds an option whose id comes from the builder's id function. */
        public Builder<E> option(E value, MessageKey label) {
            return option(value, label, null, null);
        }

        /** Adds an option that needs a permission or falls back to another option when unavailable. */
        public Builder<E> option(E value, MessageKey label, String permission, String unavailableAs) {
            if (this.ids == null) {
                throw new IllegalStateException("Choice " + this.id + " has no id function; name the option id");
            }
            this.options.add(new Option<>(this.ids.apply(value), value, label, List.of(), permission, unavailableAs));
            return this;
        }

        /** Adds an option with an explicit id; {@code args} fill the label's placeholders. */
        public Builder<E> option(String optionId, E value, MessageKey label, Arg... args) {
            this.options.add(new Option<>(optionId, value, label, List.of(args), null, null));
            return this;
        }

        /** Adds a fully described option. */
        public Builder<E> option(Option<E> option) {
            this.options.add(option);
            return this;
        }

        /** The setting's label and description. */
        public Builder<E> text(MessageKey label, MessageKey description) {
            this.label = label;
            this.description = description;
            return this;
        }

        /** Permission needed to see and use the setting. */
        public Builder<E> permission(String node) {
            this.permission = node;
            return this;
        }

        /**
         * An old stored value that reads as an option, for a toggle that became a choice under the same id (for
         * example {@code legacyValue("true", "actionbar")}). Rows and config entries holding it keep working.
         */
        public Builder<E> legacyValue(String stored, String optionId) {
            this.legacy.put(stored, optionId);
            return this;
        }

        public Choice<E> build() {
            return new Choice<>(this.id, this.options, this.defaultValue, this.label, this.description, this.permission, this.legacy);
        }
    }
}
