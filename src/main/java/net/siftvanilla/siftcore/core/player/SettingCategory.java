package net.siftvanilla.siftcore.core.player;

import java.util.Objects;
import java.util.Set;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A group of per-player settings in the settings dialog (Chat, Sounds, Privacy...). The shared groups are the
 * constants of {@link SettingCategories}; features register their settings into them with
 * {@link PlayerSettings#register(SettingCategory, PlayerSetting, SettingOptions)}, and several features share one
 * group by registering the same constant. Settings registered without a category land in
 * {@link SettingCategories#GENERAL}.
 *
 * @param id          stable id, also what {@code /settings <id>} opens, e.g. {@code chat}
 * @param order       position in the settings dialog, lowest first
 * @param label       short name of the group (a {@code ui} key with no placeholders)
 * @param description one line saying what is in the group (a {@code ui} key with no placeholders)
 * @param icon        an {@code icons.yml} name shown next to the group, or null
 */
public record SettingCategory(String id, int order, MessageKey label, MessageKey description, String icon) {

    /** Words {@code /settings} uses for itself, so no category may take them. */
    public static final Set<String> RESERVED = Set.of("search", "changed", "reset", "all");

    public SettingCategory {
        Objects.requireNonNull(id);
        Objects.requireNonNull(label);
        Objects.requireNonNull(description);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid setting category id " + id);
        }
    }

    /** A category without an icon. */
    public SettingCategory(String id, int order, MessageKey label, MessageKey description) {
        this(id, order, label, description, null);
    }
}
