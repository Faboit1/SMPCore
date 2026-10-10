package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;

/**
 * The words of {@code /settings} and {@code /sift settings} (pure, unit tested): what a typed word names, how a typed
 * value reads, and what to suggest.
 * <p>
 * <b>Resolution order.</b> The words {@code search}, {@code changed} and {@code reset} are the command's own (and no
 * category may take them). Otherwise the first word is a category the player sees, else a setting they see by its id,
 * its dialog input key, or its short name when only one visible setting has that short name. After a category, the
 * second word is a setting of that category by its id, short name or input key. Settings the player can't see resolve
 * as unknown, so a staff setting's name never leaks.
 */
final class SettingsArgs {

    /** The command's own first words. */
    static final Set<String> RESERVED = Set.of("search", "changed", "reset", "all");
    /** At most this many value suggestions. */
    static final int MAX_SUGGESTIONS = 15;
    /** Setting ids are suggested for the first word once this many characters are typed (categories always are). */
    static final int IDS_AFTER = 2;

    /** What a word names. */
    sealed interface Target permits Reserved, Category, One, Unknown {
    }

    /** One of the command's own words. */
    record Reserved(String word) implements Target {
    }

    /** A category the player sees. */
    record Category(SettingCategory category) implements Target {
    }

    /** A setting the player sees. */
    record One(Registry.Entry<?> entry) implements Target {
    }

    /** Nothing the player sees. */
    record Unknown(String word) implements Target {
    }

    /** Why a typed value was not taken. */
    enum Problem {
        /** Not a value of the setting at all. */
        NOT_A_VALUE,
        /** An option of the setting the player can't pick (no permission, or the server does not offer it now). */
        NOT_OFFERED
    }

    /** A typed value: the value, or why it was not taken. */
    record Parsed<T>(T value, Problem problem) {
        static <T> Parsed<T> ok(T value) {
            return new Parsed<>(value, null);
        }

        static <T> Parsed<T> bad(Problem problem) {
            return new Parsed<>(null, problem);
        }

        boolean ok() {
            return this.problem == null;
        }
    }

    private SettingsArgs() {
    }

    /** The first word of {@code /settings}. */
    static Target first(Registry registry, Predicate<Registry.Entry<?>> visible, String word) {
        String lower = lower(word);
        if (lower.isEmpty()) {
            return new Unknown(word == null ? "" : word);
        }
        if (RESERVED.contains(lower)) {
            return new Reserved(lower);
        }
        for (SettingCategory category : registry.categories()) {
            if (category.id().equals(lower) && registry.in(category.id()).stream().anyMatch(visible)) {
                return new Category(category);
            }
        }
        Registry.Entry<?> exact = registry.entry(lower);
        if (exact != null && visible.test(exact)) {
            return new One(exact);
        }
        Registry.Entry<?> shortName = null;
        int shortNames = 0;
        for (Registry.Entry<?> entry : registry.byId().values()) {
            if (!visible.test(entry)) {
                continue;
            }
            if (entry.inputKey().equalsIgnoreCase(lower)) {
                return new One(entry);
            }
            if (entry.shortName().equals(lower)) {
                shortName = entry;
                shortNames++;
            }
        }
        return shortNames == 1 ? new One(shortName) : new Unknown(word);
    }

    /** A setting of a category by its id, short name or input key. */
    static Target inCategory(Registry registry, Predicate<Registry.Entry<?>> visible, SettingCategory category, String word) {
        String lower = lower(word);
        for (Registry.Entry<?> entry : registry.in(category.id())) {
            if (visible.test(entry) && (entry.id().equals(lower) || entry.shortName().equals(lower)
                || entry.inputKey().equalsIgnoreCase(lower))) {
                return new One(entry);
            }
        }
        return new Unknown(word);
    }

    /** Any registered setting (staff tools): by id or input key, case-insensitive. */
    static Registry.Entry<?> anySetting(Registry registry, String word) {
        String lower = lower(word);
        Registry.Entry<?> exact = registry.entry(lower);
        if (exact != null) {
            return exact;
        }
        for (Registry.Entry<?> entry : registry.byId().values()) {
            if (entry.inputKey().equalsIgnoreCase(lower)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * A typed value. A toggle takes on/off (true/false, yes/no, 1/0) or {@code toggle} for the opposite of
     * {@code current}; a choice an option id (or an old stored value) or an option's label without regard to case,
     * spaces and punctuation, refused as {@link Problem#NOT_OFFERED} when it is not among {@code offered}; a number a
     * whole number in range and on a step (a trailing {@code %} is fine).
     *
     * @param labels an option's label as plain text
     */
    static <T> Parsed<T> parse(PlayerSetting<T> setting, String input, T current, List<Choice.Option<T>> offered,
                               Function<Choice.Option<T>, String> labels) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty()) {
            return Parsed.bad(Problem.NOT_A_VALUE);
        }
        return switch (setting) {
            case Toggle toggle -> {
                Boolean on = text.equalsIgnoreCase("toggle") ? Boolean.valueOf(!Boolean.TRUE.equals(current)) : Toggle.parse(text);
                yield on == null ? Parsed.bad(Problem.NOT_A_VALUE) : Parsed.ok(setting.cast(on));
            }
            case NumberSetting number -> {
                String digits = text.endsWith("%") ? text.substring(0, text.length() - 1).strip() : text;
                Long value = number.parseExact(digits);
                yield value == null ? Parsed.bad(Problem.NOT_A_VALUE) : Parsed.ok(setting.cast(value));
            }
            case Choice<T> choice -> parseChoice(choice, text, offered, labels);
        };
    }

    private static <T> Parsed<T> parseChoice(Choice<T> choice, String text, List<Choice.Option<T>> offered,
                                             Function<Choice.Option<T>, String> labels) {
        Choice.Option<T> option = choice.option(text);
        if (option == null) {
            String typed = squash(text);
            for (Choice.Option<T> candidate : choice.options()) {
                if (!typed.isEmpty() && squash(labels.apply(candidate)).equals(typed)) {
                    option = candidate;
                    break;
                }
            }
        }
        if (option == null) {
            return Parsed.bad(Problem.NOT_A_VALUE);
        }
        for (Choice.Option<T> open : offered) {
            if (open.id().equals(option.id())) {
                return Parsed.ok(option.value());
            }
        }
        return Parsed.bad(Problem.NOT_OFFERED);
    }

    /** Letters and digits only, lowercase ("Above the hotbar" becomes "abovethehotbar"). */
    private static String squash(String text) {
        return SettingsSearch.normalize(text).replace(" ", "");
    }

    /**
     * The first word's suggestions: the categories the player sees, then (once {@link #IDS_AFTER} characters are typed)
     * the ids of the settings they may change.
     */
    static List<String> suggestFirst(List<SettingCategory> categories, List<Registry.Entry<?>> changeable, String prefix) {
        String lower = lower(prefix);
        Set<String> out = new LinkedHashSet<>();
        for (SettingCategory category : categories) {
            if (category.id().startsWith(lower)) {
                out.add(category.id());
            }
        }
        if (lower.length() >= IDS_AFTER) {
            for (Registry.Entry<?> entry : changeable) {
                if (entry.id().startsWith(lower)) {
                    out.add(entry.id());
                }
            }
        }
        return List.copyOf(out);
    }

    /** The short names of a category's settings the player may change. */
    static List<String> suggestSettings(List<Registry.Entry<?>> changeable, String prefix) {
        String lower = lower(prefix);
        List<String> out = new ArrayList<>();
        for (Registry.Entry<?> entry : changeable) {
            if (entry.shortName().startsWith(lower)) {
                out.add(entry.shortName());
            }
        }
        return out;
    }

    /**
     * Values to suggest: on, off and toggle for a toggle; the option ids the player may pick for a choice; for a
     * number its minimum, default and maximum, then the steps starting with what was typed (at most
     * {@link #MAX_SUGGESTIONS}).
     */
    static List<String> suggestValues(PlayerSetting<?> setting, List<String> offeredOptions, String prefix) {
        String lower = lower(prefix);
        Set<String> out = new LinkedHashSet<>();
        switch (setting) {
            case Toggle toggle -> {
                for (String word : List.of("on", "off", "toggle")) {
                    if (word.startsWith(lower)) {
                        out.add(word);
                    }
                }
            }
            case Choice<?> choice -> {
                for (String id : offeredOptions) {
                    if (id.startsWith(lower)) {
                        out.add(id);
                    }
                }
            }
            case NumberSetting number -> {
                if (lower.isEmpty()) {
                    out.add(Long.toString(number.min()));
                    out.add(Long.toString(number.defaultNumber()));
                    out.add(Long.toString(number.max()));
                }
                for (long value = number.min(); value <= number.max() && out.size() < MAX_SUGGESTIONS; value += number.step()) {
                    String text = Long.toString(value);
                    if (text.startsWith(lower)) {
                        out.add(text);
                    }
                }
            }
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_SUGGESTIONS ? List.copyOf(list.subList(0, MAX_SUGGESTIONS)) : List.copyOf(list);
    }

    private static String lower(String word) {
        return word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
    }
}
