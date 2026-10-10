package net.siftvanilla.siftcore.feature.sell;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Routing;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * The selling settings players choose (all in Money &amp; selling, next to the shared sale receipts) and the pure
 * rules that apply them.
 */
final class SellPrefs {

    /** Whether {@code /sell all} (and category selling) takes the hotbar: the server's rule, never, or always. */
    enum Hotbar {
        SERVER("server"),
        KEEP("keep"),
        SELL("sell");

        private final String id;

        Hotbar(String id) {
            this.id = id;
        }

        String id() {
            return this.id;
        }
    }

    /** What closing the sell menu does with what is in its grid. */
    enum MenuClose {
        RETURN("return"),
        SELL("sell");

        private final String id;

        MenuClose(String id) {
            this.id = id;
        }

        String id() {
            return this.id;
        }
    }

    /**
     * From which total selling everything asks first. Was the "Ask before /sell all" switch: on reads as the server's
     * amount, off as never. The server's {@code sell-all.confirm: always|never} wins over it.
     */
    static final Choice<ConfirmAbove> CONFIRM = Choices.confirmAbove("sell_all_confirm", Currency.MONEY, true, "10k", "100k", "1m")
        .legacyValue("true", ConfirmAbove.SERVER.id()).legacyValue("false", ConfirmAbove.NEVER.id())
        .text(SellMessages.SETTING_CONFIRM, SellMessages.SETTING_CONFIRM_DESCRIPTION).build();
    /** Whether {@code /sell all} keeps or sells the hotbar. */
    static final Choice<Hotbar> HOTBAR = Choice.ofEnum("sell-all-hotbar", Hotbar.class, Hotbar::id, Hotbar.SERVER)
        .option(Hotbar.SERVER, OptionTexts.CONFIRM_SERVER)
        .option(Hotbar.KEEP, SellMessages.SETTING_HOTBAR_KEEP)
        .option(Hotbar.SELL, SellMessages.SETTING_HOTBAR_SELL)
        .text(SellMessages.SETTING_HOTBAR, SellMessages.SETTING_HOTBAR_DESCRIPTION).build();
    /** Items go to buy orders that pay more than the server (offered while there are buy orders). */
    static final Toggle ORDERS = new Toggle("sell_orders", true, SellMessages.SETTING_ORDERS, SellMessages.SETTING_ORDERS_DESCRIPTION, null);
    /** Whether {@code /sell all} sells what is inside shulker boxes (only while the server lets it). */
    static final Toggle SHULKERS = new Toggle("sell-all-shulkers", true, SellMessages.SETTING_SHULKERS,
        SellMessages.SETTING_SHULKERS_DESCRIPTION, null);
    /** Whether closing the sell menu gives its items back or sells them. */
    static final Choice<MenuClose> MENU_CLOSE = Choice.ofEnum("sell-menu-close", MenuClose.class, MenuClose::id, MenuClose.RETURN)
        .option(MenuClose.RETURN, SellMessages.SETTING_MENU_CLOSE_RETURN)
        .option(MenuClose.SELL, SellMessages.SETTING_MENU_CLOSE_SELL)
        .text(SellMessages.SETTING_MENU_CLOSE, SellMessages.SETTING_MENU_CLOSE_DESCRIPTION).build();
    /** How sell mastery level-ups are announced to the player (offered while mastery is on). */
    static final Choice<AlertStyle> LEVEL_UP = Choices.alert("mastery-levelup", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.TITLE, AlertStyle.OFF)
        .text(SellMessages.SETTING_LEVEL_UP, SellMessages.SETTING_LEVEL_UP_DESCRIPTION).build();

    private SellPrefs() {
    }

    /**
     * Registers the selling settings in Money &amp; selling at their catalog places (sale receipts, shared, is first;
     * the payment settings sit in between), each offered only while the server's config gives it a meaning, and
     * declares that selling acts on the shared sale receipts and that the top sellers leave out hide-from-leaderboards.
     *
     * @param config the sell settings in effect
     * @param orders whether there are buy orders to sell to (the orders feature may be off, and starts after this one)
     */
    static void register(PlayerSettings prefs, Supplier<SellSettings> config, BooleanSupplier orders) {
        prefs.reads(SharedSettings.SELL_RECEIPTS);
        prefs.reads(SharedSettings.HIDE_FROM_LEADERBOARDS);
        prefs.register(SettingCategories.ECONOMY, CONFIRM, SettingOptions.<ConfirmAbove>builder().order(3)
            .availableWhen(() -> config.get().sellAll().confirm() == SellSettings.Confirm.ABOVE).build());
        prefs.register(SettingCategories.ECONOMY, HOTBAR, SettingOptions.<Hotbar>builder().order(4).build());
        prefs.register(SettingCategories.ECONOMY, ORDERS, SettingOptions.<Boolean>builder().order(5).availableWhen(orders).build());
        prefs.register(SettingCategories.ECONOMY, SHULKERS, SettingOptions.<Boolean>builder().order(10)
            .availableWhen(() -> config.get().shulkerSwitchOffered()).build());
        prefs.register(SettingCategories.ECONOMY, MENU_CLOSE, SettingOptions.<MenuClose>builder().order(11).build());
        prefs.register(SettingCategories.ECONOMY, LEVEL_UP, SettingOptions.<AlertStyle>builder().order(12)
            .availableWhen(() -> config.get().mastery().enabled()).build());
    }

    /**
     * How the mastery level-ups of one sale are told.
     *
     * @param popup       the one short alert sent in the player's {@code mastery-levelup} style (null for none):
     *                    the level-up itself when there is one, a count when there are several
     * @param style       the style of the pop-up
     * @param linesInChat whether each level-up's full line also goes to chat
     */
    record LevelUpNotice(MessageKey popup, AlertStyle style, boolean linesInChat) {
    }

    /**
     * How the level-ups of one sale reach the player. The action bar and a title show one line at a time, and each new
     * line replaces the last, so a sale sends at most one pop-up: the level-up itself, or a count of them with every
     * line in chat. When the pop-up would take the place where the sale's receipt shows (both above the hotbar), the
     * level-ups go to chat so the sale total stays readable. Chat (or quiet in combat, which turns pop-ups into chat)
     * gets one full line per level-up. Null when there is nothing to tell or the player turned it off.
     *
     * @param style   the player's {@code mastery-levelup} style
     * @param receipt the player's {@code sell_receipts} style (the receipt is sent just before)
     * @param quiet   quiet in combat applies to the player now
     * @param count   how many categories the sale levelled up
     */
    static LevelUpNotice levelUpNotice(AlertStyle style, AlertStyle receipt, boolean quiet, int count) {
        if (count <= 0 || style == AlertStyle.OFF) {
            return null;
        }
        Set<Routing.Place> places = popups(style, quiet);
        if (places.isEmpty()) {
            return new LevelUpNotice(null, AlertStyle.CHAT, true);
        }
        places.retainAll(popups(receipt, quiet));
        if (!places.isEmpty()) {
            // The receipt's total is on the action bar (or a title) already: a level-up there would replace it at once.
            return new LevelUpNotice(null, AlertStyle.CHAT, true);
        }
        boolean title = popups(style, quiet).contains(Routing.Place.TITLE);
        if (count == 1) {
            return new LevelUpNotice(title ? SellMessages.LEVEL_UP_TITLE : SellMessages.LEVEL_UP, style, false);
        }
        return new LevelUpNotice(title ? SellMessages.LEVEL_UP_MANY_TITLE : SellMessages.LEVEL_UP_MANY, style, true);
    }

    /** Where a style shows besides chat: the action bar or a title (none for chat, off, or quiet in combat). */
    private static Set<Routing.Place> popups(AlertStyle style, boolean quiet) {
        Set<Routing.Place> places = EnumSet.noneOf(Routing.Place.class);
        places.addAll(Routing.alert(style, quiet));
        places.remove(Routing.Place.CHAT);
        return places;
    }

    /**
     * Whether closing a sell menu sells its grid: only when the player closed it themselves ({@code PLAYER}: not a
     * quit, a teleport, another screen or a plugin), did not just die, and chose to sell on close.
     */
    static boolean sellsOnClose(MenuClose choice, InventoryCloseEvent.Reason reason, boolean dying) {
        return choice == MenuClose.SELL && reason == InventoryCloseEvent.Reason.PLAYER && !dying;
    }
}
