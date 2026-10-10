package net.siftvanilla.siftcore.feature.staff;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;

/** How staff records are worded: reasons, staff names, punishment types, times and item names. */
final class StaffText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final Lang lang;

    StaffText(Lang lang) {
        this.lang = lang;
    }

    Lang lang() {
        return this.lang;
    }

    /** The reason as shown to people: the given text, or "No reason given". */
    String reason(String reason) {
        return reason == null || reason.isBlank() ? this.lang.plain(StaffMessages.NO_REASON) : reason;
    }

    /** The name shown for a stored staff member: their name, or the console's lang name. */
    String staff(String staffId, String staffName) {
        if (staffId == null || staffId.isEmpty() || staffId.equals(Actor.CONSOLE_ID)) {
            return this.lang.plain(StaffMessages.CONSOLE);
        }
        return staffName;
    }

    String staff(Actor actor) {
        return actor.isConsole() ? this.lang.plain(StaffMessages.CONSOLE) : actor.name();
    }

    Component type(PunishmentType type) {
        MessageKey key = switch (type) {
            case BAN -> StaffMessages.TYPE_BAN;
            case MUTE -> StaffMessages.TYPE_MUTE;
            case KICK -> StaffMessages.TYPE_KICK;
            case WARN -> StaffMessages.TYPE_WARN;
        };
        return this.lang.get(key);
    }

    /** A length for messages: the duration, or the word "permanent" when there is none. */
    Arg length(String name, Duration length) {
        return length == null ? Arg.component(name, this.lang.get(StaffMessages.PERMANENT)) : Arg.time(name, length);
    }

    /** Milliseconds since {@code then}, never negative. */
    static Duration since(long then, long now) {
        return Duration.ofMillis(Math.max(0, now - then));
    }

    static String date(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** The game mode in the reader's language (the client translates it). */
    static Component gameMode(GameMode mode) {
        return Component.translatable("gameMode." + mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * An item's name without its colours: a custom name as plain text, otherwise the vanilla name (translated by
     * the client). Inherits the colour of the surrounding text.
     */
    static Component itemName(ItemStack item) {
        Component custom = item.getData(DataComponentTypes.CUSTOM_NAME);
        if (custom != null) {
            return Component.text(PlainTextComponentSerializer.plainText().serialize(custom));
        }
        return Component.translatable(item.translationKey());
    }
}
