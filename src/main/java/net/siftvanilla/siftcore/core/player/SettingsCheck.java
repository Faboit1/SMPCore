package net.siftvanilla.siftcore.core.player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** The self-test checks of the settings registry (pure, so they are unit tested too). */
public final class SettingsCheck {

    /** Plain text of a message, like {@code Lang#plain}. */
    @FunctionalInterface
    public interface Text {
        String plain(MessageKey key, Arg... args);
    }

    private SettingsCheck() {
    }

    /**
     * Every label, description, option label and unit of the registered settings, and the text of every shared group,
     * that has no text (blank, or the bare key path a missing lang entry renders as).
     */
    public static List<String> missingText(Registry registry, Text text) {
        List<String> missing = new ArrayList<>();
        for (SettingCategory category : SettingCategories.ALL) {
            check(text, category.id(), category.label(), List.of(), missing);
            check(text, category.id(), category.description(), List.of(), missing);
        }
        for (Registry.Entry<?> entry : registry.byId().values()) {
            PlayerSetting<?> setting = entry.setting();
            check(text, setting.id(), setting.label(), List.of(), missing);
            check(text, setting.id(), setting.description(), List.of(), missing);
            if (setting instanceof Choice<?> choice) {
                for (Choice.Option<?> option : choice.options()) {
                    check(text, setting.id() + "/" + option.id(), option.label(), option.args(), missing);
                }
            }
            if (setting instanceof NumberSetting number && number.unit() != null) {
                check(text, setting.id() + " unit", number.unit(), List.of(), missing);
            }
            if (entry.options().keywords() != null) {
                check(text, setting.id() + " keywords", entry.options().keywords(), List.of(), missing);
            }
        }
        return missing;
    }

    private static void check(Text text, String owner, MessageKey key, List<Arg> args, List<String> missing) {
        String plain = text.plain(key, args.toArray(Arg[]::new));
        if (plain == null || plain.isBlank() || plain.equals(key.path())) {
            missing.add(owner + " (" + key.path() + ")");
        }
    }

    /** The shared groups whose icon is not a known icon name. */
    public static List<String> badIcons(Predicate<String> known) {
        List<String> bad = new ArrayList<>();
        for (SettingCategory category : SettingCategories.ALL) {
            if (category.icon() != null && !known.test(category.icon())) {
                bad.add(category.id() + " (" + category.icon() + ")");
            }
        }
        return bad;
    }

    /** The shared settings that are not registered (or registered as something else under their id). */
    public static List<String> missingShared(Registry registry) {
        List<String> missing = new ArrayList<>();
        for (PlayerSetting<?> setting : SharedSettings.ALL) {
            Registry.Entry<?> entry = registry.entry(setting.id());
            if (entry == null || !entry.setting().equals(setting)) {
                missing.add(setting.id());
            }
        }
        return missing;
    }

    /**
     * Groups that hold too many or too few settings, counted as staff see them (every permission; settings the
     * server hides or does not offer don't count). More than {@link SettingCategories#MAX_SETTINGS} is always a
     * problem. Fewer than {@link SettingCategories#MIN_SETTINGS} is one once no setting is left in the General group:
     * while General still holds settings, features are still moving theirs into the shared groups.
     */
    public static List<String> groupSizes(Registry registry, Predicate<PlayerSetting<?>> hidden) {
        List<String> problems = new ArrayList<>();
        boolean settled = count(registry, SettingCategories.GENERAL.id(), hidden) == 0;
        for (SettingCategory category : SettingCategories.ALL) {
            if (category.equals(SettingCategories.GENERAL)) {
                continue;
            }
            int count = count(registry, category.id(), hidden);
            if (count > SettingCategories.MAX_SETTINGS) {
                problems.add(category.id() + " holds " + count + " settings (at most " + SettingCategories.MAX_SETTINGS + ")");
            } else if (settled && count < SettingCategories.MIN_SETTINGS) {
                problems.add(category.id() + " holds " + count + " settings (at least " + SettingCategories.MIN_SETTINGS + ")");
            }
        }
        return problems;
    }

    /**
     * Shared settings no feature reads ({@link SharedSettings#FEATURE_READ} without {@link PlayerSettings#reads}),
     * which stay out of the dialog. Like the minimum group size this is a problem once no setting is left in the
     * General group; until then features are still wiring theirs.
     */
    public static List<String> unread(Registry registry, Predicate<PlayerSetting<?>> hasReader, Predicate<PlayerSetting<?>> hidden) {
        List<String> unread = new ArrayList<>();
        if (count(registry, SettingCategories.GENERAL.id(), hidden) > 0) {
            return unread;
        }
        for (PlayerSetting<?> setting : SharedSettings.FEATURE_READ) {
            if (!hasReader.test(setting) && !hidden.test(setting)) {
                unread.add(setting.id());
            }
        }
        return unread;
    }

    private static int count(Registry registry, String category, Predicate<PlayerSetting<?>> hidden) {
        int count = 0;
        for (Registry.Entry<?> entry : registry.in(category)) {
            if (entry.offered() && !hidden.test(entry.setting())) {
                count++;
            }
        }
        return count;
    }
}
