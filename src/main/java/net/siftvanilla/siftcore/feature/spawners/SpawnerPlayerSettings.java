package net.siftvanilla.siftcore.feature.spawners;

import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The spawners' player settings (all in Spawners): how storage opens and stacking adds, the full storage alert, where
 * collected XP goes, what happens to the storage when the owner picks a spawner up, teammate notices and the
 * confirmation before giving spawners away. Options named "server" follow {@code features/spawners.yml}. Also the pure
 * deciders the spawners feature reads them with (unit tested). Selling a storage follows the shared sale receipts
 * setting ({@code sell_receipts}).
 */
public final class SpawnerPlayerSettings {

    /** Whether opening a spawner's storage needs sneaking. */
    public enum OpenClick {
        /** As {@code interaction.open-requires-sneak} says. */
        SERVER("server", OptionTexts.CONFIRM_SERVER),
        SNEAK_RIGHT_CLICK("sneak-right-click", SpawnersMessages.OPTION_SNEAK_RIGHT_CLICK),
        RIGHT_CLICK("right-click", SpawnersMessages.OPTION_RIGHT_CLICK);

        private final String id;
        private final MessageKey label;

        OpenClick(String id, MessageKey label) {
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

    /** What a plain right-click with spawner items on a placed stack adds (sneaking does the other). */
    public enum StackClick {
        ONE("one", SpawnersMessages.OPTION_ONE),
        WHOLE_HAND("whole-hand", SpawnersMessages.OPTION_WHOLE_HAND);

        private final String id;
        private final MessageKey label;

        StackClick(String id, MessageKey label) {
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

    /** Where collected spawner XP goes. */
    public enum XpMending {
        /** As {@code xp.apply-mending} says. */
        SERVER("server", OptionTexts.CONFIRM_SERVER),
        /** Repairs mending gear first, like picking up XP orbs. */
        REPAIR_FIRST("repair-first", SpawnersMessages.OPTION_REPAIR_FIRST),
        /** All goes to levels. */
        LEVELS_ONLY("levels-only", SpawnersMessages.OPTION_LEVELS_ONLY);

        private final String id;
        private final MessageKey label;

        XpMending(String id, MessageKey label) {
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

    /** What happens to the storage when the owner picks their own spawner up. */
    public enum PickupStorage {
        /** As {@code breaking.storage} says. */
        SERVER("server", OptionTexts.CONFIRM_SERVER),
        CLAIM_BOX("claim-box", SpawnersMessages.OPTION_CLAIM_BOX),
        SELL("sell", SpawnersMessages.OPTION_SELL);

        private final String id;
        private final MessageKey label;

        PickupStorage(String id, MessageKey label) {
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

    /** Which of teammates' actions on the player's spawners they are told about. */
    public enum TeamNotices {
        /** Only when a teammate picks one up. */
        PICKUPS("pickups", SpawnersMessages.OPTION_PICKUPS),
        /** Also when they stack, take items, sell the storage or collect the XP. */
        ALL("all", SpawnersMessages.OPTION_ALL),
        OFF("off", OptionTexts.ALERT_OFF);

        private final String id;
        private final MessageKey label;

        TeamNotices(String id, MessageKey label) {
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

    /** What a teammate did with a spawner, for {@link #notifies}. */
    enum TeamAction {
        PICKUP,
        STACK,
        TAKE,
        SELL,
        XP
    }

    public static final Choice<OpenClick> OPEN_CLICK = Choice.ofEnum("spawner-open-click", OpenClick.class, OpenClick::id,
            OpenClick.SERVER)
        .option(OpenClick.SERVER, OpenClick.SERVER.label())
        .option(OpenClick.SNEAK_RIGHT_CLICK, OpenClick.SNEAK_RIGHT_CLICK.label())
        .option(OpenClick.RIGHT_CLICK, OpenClick.RIGHT_CLICK.label())
        .text(SpawnersMessages.SETTING_OPEN_CLICK, SpawnersMessages.SETTING_OPEN_CLICK_DESCRIPTION).build();
    /** Told when one of the player's spawners' storage fills up and loot starts being lost. */
    public static final Choice<AlertStyle> FULL_ALERT = Choices.alert("spawner-full-alert", AlertStyle.ACTIONBAR,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .text(SpawnersMessages.SETTING_FULL_ALERT, SpawnersMessages.SETTING_FULL_ALERT_DESCRIPTION).build();
    public static final Choice<StackClick> STACK_CLICK = Choice.ofEnum("spawner-stack-click", StackClick.class, StackClick::id,
            StackClick.ONE)
        .option(StackClick.ONE, StackClick.ONE.label())
        .option(StackClick.WHOLE_HAND, StackClick.WHOLE_HAND.label())
        .text(SpawnersMessages.SETTING_STACK_CLICK, SpawnersMessages.SETTING_STACK_CLICK_DESCRIPTION).build();
    public static final Choice<XpMending> XP_MENDING = Choice.ofEnum("spawner-xp-mending", XpMending.class, XpMending::id,
            XpMending.SERVER)
        .option(XpMending.SERVER, XpMending.SERVER.label())
        .option(XpMending.REPAIR_FIRST, XpMending.REPAIR_FIRST.label())
        .option(XpMending.LEVELS_ONLY, XpMending.LEVELS_ONLY.label())
        .text(SpawnersMessages.SETTING_XP_MENDING, SpawnersMessages.SETTING_XP_MENDING_DESCRIPTION).build();
    public static final Choice<PickupStorage> PICKUP_STORAGE = Choice.ofEnum("spawner-pickup-storage", PickupStorage.class,
            PickupStorage::id, PickupStorage.SERVER)
        .option(PickupStorage.SERVER, PickupStorage.SERVER.label())
        .option(PickupStorage.CLAIM_BOX, PickupStorage.CLAIM_BOX.label())
        .option(PickupStorage.SELL, PickupStorage.SELL.label())
        .text(SpawnersMessages.SETTING_PICKUP_STORAGE, SpawnersMessages.SETTING_PICKUP_STORAGE_DESCRIPTION).build();
    public static final Choice<TeamNotices> TEAM_NOTICES = Choice.ofEnum("spawner-team-notices", TeamNotices.class, TeamNotices::id,
            TeamNotices.PICKUPS)
        .option(TeamNotices.PICKUPS, TeamNotices.PICKUPS.label())
        .option(TeamNotices.ALL, TeamNotices.ALL.label())
        .option(TeamNotices.OFF, TeamNotices.OFF.label())
        .text(SpawnersMessages.SETTING_TEAM_NOTICES, SpawnersMessages.SETTING_TEAM_NOTICES_DESCRIPTION).build();
    /** A second click before adding spawners to someone else's stack (they become that player's). */
    public static final Toggle CONFIRM_GIVE = new Toggle("spawner-confirm-give", true, SpawnersMessages.SETTING_CONFIRM_GIVE,
        SpawnersMessages.SETTING_CONFIRM_GIVE_DESCRIPTION, null);

    private SpawnerPlayerSettings() {
    }

    /**
     * Registers the spawner settings into Spawners in the catalog's order, and declares that selling a storage
     * follows the shared sale receipts setting.
     */
    static void register(PlayerSettings settings) {
        settings.register(SettingCategories.SPAWNERS, OPEN_CLICK, SettingOptions.<OpenClick>builder().order(1).build());
        settings.register(SettingCategories.SPAWNERS, FULL_ALERT, SettingOptions.<AlertStyle>builder().order(2).build());
        settings.register(SettingCategories.SPAWNERS, STACK_CLICK, SettingOptions.<StackClick>builder().order(3).build());
        settings.register(SettingCategories.SPAWNERS, XP_MENDING, SettingOptions.<XpMending>builder().order(4).build());
        settings.register(SettingCategories.SPAWNERS, PICKUP_STORAGE, SettingOptions.<PickupStorage>builder().order(5).build());
        settings.register(SettingCategories.SPAWNERS, TEAM_NOTICES, SettingOptions.<TeamNotices>builder().order(6).build());
        settings.register(SettingCategories.SPAWNERS, CONFIRM_GIVE, SettingOptions.<Boolean>builder().order(7).build());
        settings.reads(SharedSettings.SELL_RECEIPTS);
    }

    // ------------------------------------------------------------------ deciders

    /** Whether opening the storage needs sneaking for a player with this choice. */
    static boolean requiresSneak(OpenClick choice, boolean server) {
        return switch (choice) {
            case SERVER -> server;
            case SNEAK_RIGHT_CLICK -> true;
            case RIGHT_CLICK -> false;
        };
    }

    /** Whether a right-click with spawner items adds the whole held stack (sneaking does the opposite of the choice). */
    static boolean wholeStack(StackClick choice, boolean sneaking) {
        return sneaking != (choice == StackClick.WHOLE_HAND);
    }

    /** Whether collected XP repairs mending gear first. */
    static boolean mending(XpMending choice, boolean server) {
        return switch (choice) {
            case SERVER -> server;
            case REPAIR_FIRST -> true;
            case LEVELS_ONLY -> false;
        };
    }

    /**
     * Where the storage goes when a spawner is picked up: the owner's own choice when the owner picks it up, the
     * server's {@code breaking.storage} for anyone else (teammates and staff).
     */
    static SpawnersSettings.BreakStorage breakStorage(PickupStorage ownerChoice, SpawnersSettings.BreakStorage server, boolean byOwner) {
        if (!byOwner) {
            return server;
        }
        return switch (ownerChoice) {
            case SERVER -> server;
            case CLAIM_BOX -> SpawnersSettings.BreakStorage.CLAIM_BOX;
            case SELL -> SpawnersSettings.BreakStorage.SELL;
        };
    }

    /** Whether an owner with this choice is told that a teammate did {@code action} with one of their spawners. */
    static boolean notifies(TeamNotices choice, TeamAction action) {
        return switch (choice) {
            case OFF -> false;
            case PICKUPS -> action == TeamAction.PICKUP;
            case ALL -> true;
        };
    }
}
