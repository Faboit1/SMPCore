package net.siftvanilla.siftcore.core.player;

import java.util.Objects;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A group of per-player settings in the settings dialog (Chat, Teleports, Notifications...). A feature declares its
 * categories as constants and registers its toggles into them with
 * {@link PlayerSettings#register(SettingCategory, Toggle)}; several features may share one category by registering
 * the same constant. Toggles registered without a category are shown in a general group.
 *
 * @param id          stable id, also what {@code /settings <id>} opens, e.g. {@code chat}
 * @param order       position in the settings dialog, lowest first
 * @param label       short name of the group (a {@code ui} key with no placeholders)
 * @param description one line saying what is in the group (a {@code ui} key with no placeholders)
 */
public record SettingCategory(String id, int order, MessageKey label, MessageKey description) {

    public SettingCategory {
        Objects.requireNonNull(id);
        Objects.requireNonNull(label);
        Objects.requireNonNull(description);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid setting category id " + id);
        }
    }
}
