package net.siftvanilla.siftcore.core.player;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A per-player on/off setting contributed by a feature, shown automatically in the settings dialog. Stored as
 * {@code true}/{@code false}; {@code on}/{@code off}, {@code yes}/{@code no} and {@code 1}/{@code 0} are read too
 * (commands, hand-edited rows and config entries).
 *
 * @param id          stable id stored in the database, e.g. {@code scoreboard}
 * @param defaultOn   the value for players who never changed it
 * @param label       short label shown next to the switch
 * @param description one line explaining it
 * @param permission  permission needed to see it, or null
 */
public record Toggle(String id, boolean defaultOn, MessageKey label, MessageKey description, String permission)
    implements PlayerSetting<Boolean> {

    public Toggle {
        Objects.requireNonNull(id);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid toggle id " + id);
        }
    }

    @Override
    public Boolean defaultValue() {
        return this.defaultOn;
    }

    @Override
    public Kind kind() {
        return Kind.TOGGLE;
    }

    @Override
    public String encode(Boolean value) {
        return Boolean.toString(Objects.requireNonNull(value));
    }

    @Override
    public Optional<Boolean> decode(String stored) {
        return Optional.ofNullable(parse(stored));
    }

    /** {@code true}/{@code on}/{@code yes}/{@code 1} or their opposites, ignoring case and spaces; null otherwise. */
    public static Boolean parse(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    @Override
    public boolean valid(Boolean value) {
        return value != null;
    }

    @Override
    public String display(Lang lang, Boolean value) {
        return lang.plain(Boolean.TRUE.equals(value) ? SettingTexts.STATE_ON : SettingTexts.STATE_OFF);
    }

    @Override
    public Boolean cast(Object value) {
        return value instanceof Boolean b ? b : null;
    }
}
