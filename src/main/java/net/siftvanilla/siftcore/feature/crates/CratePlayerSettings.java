package net.siftvanilla.siftcore.feature.crates;

import java.util.List;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The crates' player settings: which of other players' wins a player sees (Server announcements), and their own win
 * receipt, the unopened key reminder, the keyall countdown, sneak + right-click on a crate block and how many keys a
 * bulk opening opens (Crates &amp; kits). Each is only offered while {@code features/crates.yml} turns its behaviour on.
 * Also the pure deciders the crates feature reads them with (unit tested).
 */
public final class CratePlayerSettings {

    /** Which of other players' announced crate wins a player sees. */
    public enum WinFilter {
        /** Every announced win. */
        ALL("all", OptionTexts.ANNOUNCE_ALL),
        /** Only wins of the rarest rarity the server announces. */
        RAREST("rarest", CratesMessages.OPTION_RAREST),
        OFF("off", OptionTexts.ANNOUNCE_OFF);

        private final String id;
        private final MessageKey label;

        WinFilter(String id, MessageKey label) {
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

    /** What sneaking and right-clicking a crate block does. */
    public enum QuickOpen {
        /** Opens one key straight away. */
        ONE("one", CratesMessages.OPTION_QUICK_ONE),
        /** Opens several keys in a row (Keys per bulk open). */
        BULK("bulk", CratesMessages.OPTION_QUICK_BULK),
        /** Shows the crate window like a plain right-click. */
        OFF("off", CratesMessages.OPTION_QUICK_OFF);

        private final String id;
        private final MessageKey label;

        QuickOpen(String id, MessageKey label) {
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

    /** Other players' crate wins in chat. Was a switch: on reads as all, off as none. */
    public static final Choice<WinFilter> WIN_ANNOUNCEMENTS = Choice.ofEnum("crate-wins", WinFilter.class, WinFilter::id, WinFilter.ALL)
        .option(WinFilter.ALL, WinFilter.ALL.label())
        .option(WinFilter.RAREST, WinFilter.RAREST.label(), null, WinFilter.ALL.id())
        .option(WinFilter.OFF, WinFilter.OFF.label())
        .legacyValue("true", WinFilter.ALL.id()).legacyValue("false", WinFilter.OFF.id())
        .text(CratesMessages.SETTING_WINS, CratesMessages.SETTING_WINS_DESCRIPTION).build();
    /** Where the player's own "You won ..." line shows. Rewards sent to the claim box are always told in chat. */
    public static final Choice<AlertStyle> RECEIPT = Choices.alert("crate-receipt", AlertStyle.CHAT,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
        .text(CratesMessages.SETTING_RECEIPT, CratesMessages.SETTING_RECEIPT_DESCRIPTION).build();
    /** The reminder on join about keys that are still to be opened. */
    public static final Toggle KEY_REMINDER = new Toggle("crate-key-reminder", true, CratesMessages.SETTING_KEY_REMINDER,
        CratesMessages.SETTING_KEY_REMINDER_DESCRIPTION, null);
    /**
     * How the keyall countdown shows: chat announcements and the action bar count, either, or none. On a server that
     * has only one of the two, both reads as chat (or, with no chat lines, as the first open option: the action bar)
     * and each of chat and actionbar reads as the other.
     */
    public static final Choice<AlertStyle> KEYALL_COUNTDOWN = Choice.ofEnum("keyall-countdown", AlertStyle.class, AlertStyle::id,
            AlertStyle.BOTH)
        .option(AlertStyle.BOTH, AlertStyle.BOTH.label(), null, AlertStyle.CHAT.id())
        .option(AlertStyle.CHAT, AlertStyle.CHAT.label(), null, AlertStyle.ACTIONBAR.id())
        .option(AlertStyle.ACTIONBAR, AlertStyle.ACTIONBAR.label(), null, AlertStyle.CHAT.id())
        .option(AlertStyle.OFF, AlertStyle.OFF.label())
        .text(CratesMessages.SETTING_KEYALL, CratesMessages.SETTING_KEYALL_DESCRIPTION).build();
    /** What sneak + right-click on a crate block does. */
    public static final Choice<QuickOpen> QUICK_OPEN = Choice.ofEnum("crate-quick-open", QuickOpen.class, QuickOpen::id, QuickOpen.ONE)
        .option(QuickOpen.ONE, QuickOpen.ONE.label())
        .option(QuickOpen.BULK, QuickOpen.BULK.label(), null, QuickOpen.ONE.id())
        .option(QuickOpen.OFF, QuickOpen.OFF.label())
        .text(CratesMessages.SETTING_QUICK_OPEN, CratesMessages.SETTING_QUICK_OPEN_DESCRIPTION).build();
    /** How many keys "Open n" and a right-click in the preview open at once (never more than the server allows). */
    public static final NumberSetting BULK_AMOUNT = new NumberSetting("crate-bulk-amount", 10, 2, CratesSettings.MAX_BULK_OPEN, 1,
        CratesMessages.UNIT_KEYS, CratesMessages.SETTING_BULK_AMOUNT, CratesMessages.SETTING_BULK_AMOUNT_DESCRIPTION, null);

    private CratePlayerSettings() {
    }

    /**
     * Registers the crate settings in their groups, at their place in the catalog (the kits feature fills the other
     * places of Crates &amp; kits). Settings whose behaviour {@code crates.yml} can turn off are only offered while it
     * is on.
     */
    static void register(PlayerSettings settings, Supplier<CratesSettings> config) {
        settings.register(SettingCategories.ANNOUNCEMENTS, WIN_ANNOUNCEMENTS, SettingOptions.<WinFilter>builder().order(3)
            .availableWhen(() -> announced(config.get().rarities()) > 0)
            .optionAvailableWhen(WinFilter.RAREST.id(), () -> announced(config.get().rarities()) > 1).build());
        settings.register(SettingCategories.CRATES, RECEIPT, SettingOptions.<AlertStyle>builder().order(1).build());
        settings.register(SettingCategories.CRATES, KEY_REMINDER, SettingOptions.<Boolean>builder().order(3)
            .availableWhen(() -> config.get().joinReminder()).build());
        settings.register(SettingCategories.CRATES, KEYALL_COUNTDOWN, SettingOptions.<AlertStyle>builder().order(4)
            .availableWhen(() -> countdownShown(config.get().keyall()))
            .optionAvailableWhen(AlertStyle.BOTH.id(), () -> countsInChat(config.get().keyall()) && countsInActionBar(config.get().keyall()))
            .optionAvailableWhen(AlertStyle.CHAT.id(), () -> countsInChat(config.get().keyall()))
            .optionAvailableWhen(AlertStyle.ACTIONBAR.id(), () -> countsInActionBar(config.get().keyall())).build());
        settings.register(SettingCategories.CRATES, QUICK_OPEN, SettingOptions.<QuickOpen>builder().order(5)
            .availableWhen(() -> config.get().quickOpen())
            .optionAvailableWhen(QuickOpen.BULK.id(), () -> config.get().bulkOpen() >= 2).build());
        settings.register(SettingCategories.CRATES, BULK_AMOUNT, SettingOptions.<Long>builder().order(7)
            .availableWhen(() -> config.get().bulkOpen() >= 2).build());
    }

    // ------------------------------------------------------------------ deciders

    /** How many rarities the server announces. */
    static int announced(List<Rarity> rarities) {
        int count = 0;
        for (Rarity rarity : rarities) {
            if (rarity.announce()) {
                count++;
            }
        }
        return count;
    }

    /** The rarest rarity the server announces (rarities go from most common to rarest), or null when none. */
    static Rarity rarestAnnounced(List<Rarity> rarities) {
        Rarity rarest = null;
        for (Rarity rarity : rarities) {
            if (rarity.announce()) {
                rarest = rarity;
            }
        }
        return rarest;
    }

    /** Whether a player with this filter sees an announced win of {@code won}. */
    static boolean showsWin(WinFilter filter, Rarity won, List<Rarity> rarities) {
        return switch (filter) {
            case ALL -> true;
            case OFF -> false;
            case RAREST -> {
                Rarity rarest = rarestAnnounced(rarities);
                yield rarest != null && rarest.id().equals(won.id());
            }
        };
    }

    /** Whether the keyall countdown shows anything at all (the keyall runs and announces or counts down). */
    static boolean countdownShown(CratesSettings.Keyall keyall) {
        return keyall.enabled() && (countsInChat(keyall) || countsInActionBar(keyall));
    }

    /** Whether the server announces the coming keyall in chat ({@code keyall.countdown.chat} has times). */
    static boolean countsInChat(CratesSettings.Keyall keyall) {
        return !keyall.chatAt().isEmpty();
    }

    /** Whether the server counts the keyall's last seconds down in the action bar ({@code countdown.action-bar}). */
    static boolean countsInActionBar(CratesSettings.Keyall keyall) {
        return !keyall.actionBarFrom().isZero();
    }

    /** Whether the keyall's chat announcements reach a player with this countdown style. */
    static boolean countdownInChat(AlertStyle style) {
        return style == AlertStyle.BOTH || style == AlertStyle.CHAT;
    }

    /** Whether the keyall's last seconds are counted down in the action bar of a player with this countdown style. */
    static boolean countdownInActionBar(AlertStyle style) {
        return style == AlertStyle.BOTH || style == AlertStyle.ACTIONBAR;
    }

    /**
     * How many keys a bulk opening opens: the player's preferred amount, never more than they have or than the server
     * allows. Below 2 there is no bulk opening (0).
     */
    static int bulkAmount(long preferred, int owned, int serverMax) {
        long many = Math.min(Math.min(preferred, owned), serverMax);
        return many >= 2 ? (int) many : 0;
    }

    /**
     * Whether the reason a bulk opening stopped early goes to chat: when its receipt took the action bar, where the
     * reason (a refusal, on the action bar too unless the player sends feedback to chat) would replace it at once.
     *
     * @param receipt where the receipt went: {@link AlertStyle#OFF} when none was sent, chat when it went to chat
     */
    static boolean stopReasonInChat(AlertStyle receipt) {
        return receipt == AlertStyle.ACTIONBAR;
    }

    /** Whether sneak + right-click opens keys straight away (otherwise the crate window shows). */
    static boolean quickOpens(QuickOpen choice, boolean serverAllows, boolean sneaking) {
        return sneaking && serverAllows && choice != QuickOpen.OFF;
    }
}
