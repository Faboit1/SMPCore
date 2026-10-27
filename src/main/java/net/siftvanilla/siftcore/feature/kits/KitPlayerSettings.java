package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.entity.Player;

/**
 * The kits' player settings, all in Crates &amp; kits: how and when kit reminders come, putting kit armour on
 * straight away, and the trash bin's protection and mode. Also the pure deciders the kits feature reads them with
 * (unit tested).
 */
public final class KitPlayerSettings {

    /** When kit reminders come. */
    public enum ReminderWhen {
        JOIN_AND_READY("join-and-ready", KitsMessages.OPTION_JOIN_AND_READY),
        /** Only when joining (no timer runs for the player). */
        JOIN("join", KitsMessages.OPTION_JOIN),
        /** Only the moment a kit becomes ready. */
        READY("ready", KitsMessages.OPTION_READY);

        private final String id;
        private final MessageKey label;

        ReminderWhen(String id, MessageKey label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return this.id;
        }

        public MessageKey label() {
            return this.label;
        }

        /** Whether the player is reminded when they join. */
        public boolean onJoin() {
            return this != READY;
        }

        /** Whether the player is reminded the moment a kit is ready. */
        public boolean whenReady() {
            return this != JOIN;
        }
    }

    /** What the trash gives back instead of deleting. */
    public enum TrashProtect {
        /** Enchanted or named items, shulker boxes, spawners and trial keys. */
        GEAR("gear", KitsMessages.OPTION_GEAR),
        /** Gear, and any stack worth at least the server's threshold at /sell. */
        VALUABLES("valuables", KitsMessages.OPTION_VALUABLES),
        /** Nothing: everything is deleted. */
        OFF("off", KitsMessages.OPTION_PROTECT_OFF);

        private final String id;
        private final MessageKey label;

        TrashProtect(String id, MessageKey label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return this.id;
        }

        public MessageKey label() {
            return this.label;
        }
    }

    /** When the trash deletes. */
    public enum TrashMode {
        /** Everything still in the bin is deleted when it closes (the classic bin). */
        DELETE_ON_CLOSE("delete-on-close", KitsMessages.OPTION_DELETE_ON_CLOSE),
        /** Only the Delete button deletes; closing gives everything back. */
        DELETE_BUTTON("delete-button", KitsMessages.OPTION_DELETE_BUTTON);

        private final String id;
        private final MessageKey label;

        TrashMode(String id, MessageKey label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return this.id;
        }

        public MessageKey label() {
            return this.label;
        }
    }

    /** An armour slot. */
    enum Piece {
        HEAD,
        CHEST,
        LEGS,
        FEET
    }

    /** How a player is reminded that a kit is ready. Was a switch: on reads as chat, off as no reminders. */
    public static final Choice<AlertStyle> REMINDERS = Choices.alert("kit-reminders", AlertStyle.CHAT,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.TITLE, AlertStyle.OFF)
        .legacyValue("true", AlertStyle.CHAT.id()).legacyValue("false", AlertStyle.OFF.id())
        .permission(KitsFeature.PERMISSION_USE)
        .text(KitsMessages.SETTING_REMINDERS, KitsMessages.SETTING_REMINDERS_DESCRIPTION).build();
    /** When kit reminders come: on join, the moment a kit is ready, or both. */
    public static final Choice<ReminderWhen> REMINDER_WHEN = Choice.ofEnum("kit-reminder-when", ReminderWhen.class, ReminderWhen::id,
            ReminderWhen.JOIN_AND_READY)
        .option(ReminderWhen.JOIN_AND_READY, ReminderWhen.JOIN_AND_READY.label())
        .option(ReminderWhen.JOIN, ReminderWhen.JOIN.label())
        .option(ReminderWhen.READY, ReminderWhen.READY.label())
        .permission(KitsFeature.PERMISSION_USE)
        .text(KitsMessages.SETTING_REMINDER_WHEN, KitsMessages.SETTING_REMINDER_WHEN_DESCRIPTION).build();
    /** Armour from claimed kits goes straight into empty armour slots. */
    public static final Toggle AUTO_EQUIP = new Toggle("kit-auto-equip", false, KitsMessages.SETTING_AUTO_EQUIP,
        KitsMessages.SETTING_AUTO_EQUIP_DESCRIPTION, KitsFeature.PERMISSION_USE);
    /** What the trash gives back instead of deleting. */
    public static final Choice<TrashProtect> TRASH_PROTECT = Choice.ofEnum("trash-protect", TrashProtect.class, TrashProtect::id,
            TrashProtect.GEAR)
        .option(TrashProtect.GEAR, TrashProtect.GEAR.label())
        .option(TrashProtect.VALUABLES, TrashProtect.VALUABLES.label(), null, TrashProtect.GEAR.id())
        .option(TrashProtect.OFF, TrashProtect.OFF.label())
        .permission(Perk.TRASH.node())
        .text(KitsMessages.SETTING_TRASH_PROTECT, KitsMessages.SETTING_TRASH_PROTECT_DESCRIPTION).build();
    /** When the trash deletes: when it closes, or only on the Delete button. */
    public static final Choice<TrashMode> TRASH_MODE = Choice.ofEnum("trash-confirm", TrashMode.class, TrashMode::id,
            TrashMode.DELETE_ON_CLOSE)
        .option(TrashMode.DELETE_ON_CLOSE, TrashMode.DELETE_ON_CLOSE.label())
        .option(TrashMode.DELETE_BUTTON, TrashMode.DELETE_BUTTON.label())
        .permission(Perk.TRASH.node())
        .text(KitsMessages.SETTING_TRASH_MODE, KitsMessages.SETTING_TRASH_MODE_DESCRIPTION).build();

    private KitPlayerSettings() {
    }

    /**
     * Registers the kit settings into Crates &amp; kits at their place in the catalog (the crates feature fills the
     * other places). The reminder settings are only offered while {@code kits.yml} has reminders on, and the
     * valuables option while the server prices items and has a threshold.
     *
     * @param remindersChanged sets a player's reminder timer again after they changed how or when they are reminded
     *                         (player's thread)
     */
    static void register(PlayerSettings settings, Supplier<KitsSettings> config, BooleanSupplier pricesItems,
                         Consumer<Player> remindersChanged) {
        settings.register(SettingCategories.CRATES, REMINDERS, SettingOptions.<AlertStyle>builder().order(2)
            .availableWhen(() -> config.get().reminders())
            .onChange((player, before, after) -> remindersChanged.accept(player)).build());
        settings.register(SettingCategories.CRATES, TRASH_PROTECT, SettingOptions.<TrashProtect>builder().order(6)
            .optionAvailableWhen(TrashProtect.VALUABLES.id(), () -> pricesItems.getAsBoolean() && config.get().perks().protectWorth() > 0)
            .build());
        settings.register(SettingCategories.CRATES, REMINDER_WHEN, SettingOptions.<ReminderWhen>builder().order(8)
            .availableWhen(() -> config.get().reminders())
            .onChange((player, before, after) -> remindersChanged.accept(player)).build());
        settings.register(SettingCategories.CRATES, AUTO_EQUIP, SettingOptions.<Boolean>builder().order(9).build());
        settings.register(SettingCategories.CRATES, TRASH_MODE, SettingOptions.<TrashMode>builder().order(10).build());
    }

    // ------------------------------------------------------------------ deciders

    /** Whether a reminder timer runs for a player: reminders reach them, and they want them the moment a kit is ready. */
    static boolean timer(AlertStyle style, ReminderWhen when) {
        return style != AlertStyle.OFF && when.whenReady();
    }

    /** Which join reminder lines go out ({@link #joinLines}). */
    enum JoinLine {
        /** Kits ready to claim (clickable in chat). */
        READY,
        /** Kit items waiting for room (clickable in chat). */
        WAITING,
        /** Both in one short line, for the action bar and titles, which show one line at a time. */
        READY_AND_WAITING
    }

    /**
     * The join reminder lines a player gets: in chat a line for ready kits and one for waiting items, each clickable;
     * above the hotbar or as a title, where a second line would replace the first at once, a single short line that
     * says both.
     */
    static List<JoinLine> joinLines(AlertStyle style, boolean ready, boolean waiting) {
        if (style == AlertStyle.OFF) {
            return List.of();
        }
        if (ready && waiting && style != AlertStyle.CHAT) {
            return List.of(JoinLine.READY_AND_WAITING);
        }
        List<JoinLine> lines = new ArrayList<>(2);
        if (ready) {
            lines.add(JoinLine.READY);
        }
        if (waiting) {
            lines.add(JoinLine.WAITING);
        }
        return lines;
    }

    /**
     * Whether an item counts as gear the trash gives back: enchanted (or an enchanted book), renamed, a shulker box,
     * a spawner or a trial key.
     *
     * @param item the item id, like {@code minecraft:diamond_sword}
     */
    static boolean gear(String item, boolean enchanted, boolean named) {
        if (enchanted || named) {
            return true;
        }
        return item.endsWith("shulker_box") || item.equals("minecraft:spawner") || item.equals("minecraft:trial_key")
            || item.equals("minecraft:ominous_trial_key");
    }

    /**
     * Whether the trash gives a stack back instead of deleting it.
     *
     * @param gear      whether it is gear ({@link #gear})
     * @param worth     what the stack sells for at /sell (0 when it can't be sold)
     * @param threshold the server's valuables threshold (0: off)
     */
    static boolean protects(TrashProtect protect, boolean gear, long worth, long threshold) {
        return switch (protect) {
            case OFF -> false;
            case GEAR -> gear;
            case VALUABLES -> gear || threshold > 0 && worth >= threshold;
        };
    }

    /** The armour slot an item id is worn in (helmets, chestplates, leggings and boots), or null for anything else. */
    static Piece piece(String item) {
        if (item.endsWith("_helmet")) {
            return Piece.HEAD;
        }
        if (item.endsWith("_chestplate")) {
            return Piece.CHEST;
        }
        if (item.endsWith("_leggings")) {
            return Piece.LEGS;
        }
        if (item.endsWith("_boots")) {
            return Piece.FEET;
        }
        return null;
    }

    /**
     * Which handed-out items to put on: for each empty armour slot, the first item worn there that has no curse of
     * binding (a cursed piece put on by itself could not be taken off). Worn armour is never replaced.
     *
     * @param pieces   each item's armour slot ({@link #piece}) or null, in hand-out order
     * @param cursed   whether each item has curse of binding
     * @param occupied the armour slots the player is wearing something in
     * @return item index to the slot it goes into, in hand-out order
     */
    static Map<Integer, Piece> equip(List<Piece> pieces, List<Boolean> cursed, Set<Piece> occupied) {
        Set<Piece> free = EnumSet.allOf(Piece.class);
        free.removeAll(occupied);
        Map<Integer, Piece> plan = new LinkedHashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            if (piece != null && !Boolean.TRUE.equals(cursed.get(i)) && free.remove(piece)) {
                plan.put(i, piece);
            }
        }
        return plan;
    }

    /** The armour slots in hand-out order, for readable failures in tests. */
    static List<Piece> pieces(List<String> items) {
        List<Piece> list = new ArrayList<>(items.size());
        for (String item : items) {
            list.add(piece(item));
        }
        return list;
    }
}
