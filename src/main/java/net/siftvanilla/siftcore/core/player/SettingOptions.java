package net.siftvanilla.siftcore.core.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.entity.Player;

/**
 * How a registered setting behaves beyond its value: its place in the category, when a change takes effect, what
 * runs on a change, when it is offered, old ids it replaces, and whether placeholders and the dialog show it.
 *
 * @param order           position within the category, lowest first (then registration order)
 * @param apply           when a change takes effect
 * @param onChange        runs on the player's thread after an {@link Apply#INSTANT} change, or null
 * @param available       false hides the setting (the server turned its feature off); null means always offered.
 *                        Hidden settings still read their stored or default value: callers decide what it means.
 * @param optionAvailable per option id: false hides that option, and a player who picked it reads its
 *                        {@link Choice.Option#unavailableAs()} (or the default) meanwhile
 * @param legacy          old setting ids whose stored rows move to this setting when a player loads
 * @param placeholder     whether {@code %siftcore_setting_<id>%} may expose it; null means "yes unless the setting
 *                        needs a permission"
 * @param listed          false keeps it out of the dialog and commands (code and staff can still read and set it)
 * @param keywords        extra words the settings search matches, or null
 */
public record SettingOptions<T>(int order, Apply apply, ChangeHook<T> onChange, BooleanSupplier available,
                                Map<String, BooleanSupplier> optionAvailable, List<Legacy> legacy, Boolean placeholder,
                                boolean listed, MessageKey keywords) {

    /** The order of settings registered without one: after every ordered setting. */
    public static final int UNORDERED = 1_000;

    /** When a change takes effect. */
    public enum Apply {
        /** At once: {@link SettingOptions#onChange()} runs on the player's thread. */
        INSTANT,
        /** The next time the feature reads it (the default: most settings are read when something happens). */
        NEXT_USE,
        /** After the player rejoins. */
        REJOIN
    }

    /** Runs after a setting changed for an online player, on that player's thread. */
    @FunctionalInterface
    public interface ChangeHook<T> {
        void changed(Player player, T oldValue, T newValue);
    }

    /**
     * An old setting id whose rows move to this setting.
     *
     * @param oldId    the old id
     * @param mapValue the old stored value to this setting's stored value; returning null (or an invalid value)
     *                 leaves the old row untouched
     */
    public record Legacy(String oldId, UnaryOperator<String> mapValue) {
        public Legacy {
            Objects.requireNonNull(mapValue);
            if (oldId == null || !PlayerSetting.ID.matcher(oldId).matches()) {
                throw new IllegalArgumentException("Invalid legacy setting id " + oldId);
            }
        }
    }

    public SettingOptions {
        apply = apply == null ? Apply.NEXT_USE : apply;
        optionAvailable = Map.copyOf(optionAvailable);
        legacy = List.copyOf(legacy);
    }

    /** Defaults: unordered, applied on next use, always available, listed. */
    public static <T> SettingOptions<T> defaults() {
        return SettingOptions.<T>builder().build();
    }

    /** Applied at once with a hook. */
    public static <T> SettingOptions<T> instant(ChangeHook<T> hook) {
        return SettingOptions.<T>builder().onChange(hook).build();
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Whether the setting is offered now (its availability supplier, guarded against failures). */
    public boolean isAvailable() {
        return test(this.available);
    }

    /** Whether an option is offered now. */
    public boolean isOptionAvailable(String optionId) {
        return test(this.optionAvailable.get(optionId));
    }

    private static boolean test(BooleanSupplier supplier) {
        if (supplier == null) {
            return true;
        }
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Builds {@link SettingOptions}. */
    public static final class Builder<T> {

        private int order = UNORDERED;
        private Apply apply = Apply.NEXT_USE;
        private ChangeHook<T> onChange;
        private BooleanSupplier available;
        private final Map<String, BooleanSupplier> optionAvailable = new LinkedHashMap<>();
        private final List<Legacy> legacy = new ArrayList<>();
        private Boolean placeholder;
        private boolean listed = true;
        private MessageKey keywords;

        private Builder() {
        }

        public Builder<T> order(int order) {
            this.order = order;
            return this;
        }

        public Builder<T> apply(Apply apply) {
            this.apply = Objects.requireNonNull(apply);
            return this;
        }

        /** A hook that runs on the player's thread after each change; makes the setting {@link Apply#INSTANT}. */
        public Builder<T> onChange(ChangeHook<T> hook) {
            this.onChange = Objects.requireNonNull(hook);
            this.apply = Apply.INSTANT;
            return this;
        }

        /** Offers the setting only while {@code available} says so (for config-dependent settings). */
        public Builder<T> availableWhen(BooleanSupplier available) {
            this.available = Objects.requireNonNull(available);
            return this;
        }

        /** Offers one option only while {@code available} says so. */
        public Builder<T> optionAvailableWhen(String optionId, BooleanSupplier available) {
            this.optionAvailable.put(optionId, Objects.requireNonNull(available));
            return this;
        }

        /** Moves rows of an old id to this setting (see {@link Legacy}). */
        public Builder<T> legacy(String oldId, UnaryOperator<String> mapValue) {
            this.legacy.add(new Legacy(oldId, mapValue));
            return this;
        }

        public Builder<T> placeholder(boolean placeholder) {
            this.placeholder = placeholder;
            return this;
        }

        public Builder<T> listed(boolean listed) {
            this.listed = listed;
            return this;
        }

        public Builder<T> keywords(MessageKey keywords) {
            this.keywords = keywords;
            return this;
        }

        public SettingOptions<T> build() {
            return new SettingOptions<>(this.order, this.apply, this.onChange, this.available, this.optionAvailable, this.legacy,
                this.placeholder, this.listed, this.keywords);
        }
    }
}
