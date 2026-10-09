package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the cosmetic perks ({@code lang/cosmetics.yml}). */
public final class CosmeticsMessages {

    // ------------------------------------------------------------------ shared
    public static final MessageKey IN_COMBAT = MessageKey.error("cosmetics.in-combat", "time");
    public static final MessageKey DISABLED = MessageKey.error("cosmetics.disabled");
    public static final MessageKey LOCKED = MessageKey.error("cosmetics.locked", "unlock");
    public static final MessageKey NONE = MessageKey.ui("cosmetics.none");
    public static final MessageKey RANK_PROSPECTOR = MessageKey.ui("cosmetics.ranks.prospector");
    public static final MessageKey RANK_BARON = MessageKey.ui("cosmetics.ranks.baron");
    public static final MessageKey RANK_TYCOON = MessageKey.ui("cosmetics.ranks.tycoon");
    public static final MessageKey HIGHER_RANK = MessageKey.ui("cosmetics.ranks.higher");
    public static final MessageKey NAME_HOVER = MessageKey.ui("cosmetics.name-hover", "name");
    public static final MessageKey NEXT_PAGE = MessageKey.ui("cosmetics.next-page");
    public static final MessageKey PREVIOUS_PAGE = MessageKey.ui("cosmetics.previous-page");
    public static final MessageKey PAGE = MessageKey.ui("cosmetics.page", "page", "pages");

    // ------------------------------------------------------------------ menu
    public static final MessageKey HUB_LABEL = MessageKey.ui("cosmetics.menu.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("cosmetics.menu.description");
    public static final MessageKey MENU_TITLE = MessageKey.ui("cosmetics.menu.title");
    public static final MessageKey MENU_BODY = MessageKey.ui("cosmetics.menu.body");
    public static final MessageKey MENU_LINE = MessageKey.ui("cosmetics.menu.line", "label", "value");
    public static final MessageKey MENU_CHAT_COLOR = MessageKey.ui("cosmetics.menu.chat-color");
    public static final MessageKey MENU_NICK = MessageKey.ui("cosmetics.menu.nick");
    public static final MessageKey MENU_TAG = MessageKey.ui("cosmetics.menu.tag");
    public static final MessageKey MENU_JOIN = MessageKey.ui("cosmetics.menu.join");
    public static final MessageKey MENU_KILL = MessageKey.ui("cosmetics.menu.kill");
    public static final MessageKey MENU_OPEN = MessageKey.ui("cosmetics.menu.open");
    public static final MessageKey MENU_UNLOCK = MessageKey.ui("cosmetics.menu.unlock", "unlock");
    public static final MessageKey MENU_JOIN_DEFAULT = MessageKey.ui("cosmetics.menu.join-default");
    public static final MessageKey MENU_JOIN_RANK = MessageKey.ui("cosmetics.menu.join-rank");
    public static final MessageKey MENU_JOIN_CUSTOM = MessageKey.ui("cosmetics.menu.join-custom");

    // ------------------------------------------------------------------ chat colour
    public static final MessageKey COLOR_TITLE = MessageKey.ui("cosmetics.color.title");
    public static final MessageKey COLOR_BODY = MessageKey.ui("cosmetics.color.body");
    public static final MessageKey COLOR_CURRENT = MessageKey.ui("cosmetics.color.current", "preview");
    public static final MessageKey COLOR_SAMPLE = MessageKey.ui("cosmetics.color.sample");
    public static final MessageKey COLOR_MORE = MessageKey.ui("cosmetics.color.more");
    public static final MessageKey COLOR_MORE_TOOLTIP = MessageKey.ui("cosmetics.color.more-tooltip");
    public static final MessageKey COLOR_RESET_BUTTON = MessageKey.ui("cosmetics.color.reset");
    public static final MessageKey COLOR_CUSTOM_BUTTON = MessageKey.ui("cosmetics.color.custom");
    public static final MessageKey COLOR_PREMIUM_TITLE = MessageKey.ui("cosmetics.color.premium-title");
    public static final MessageKey COLOR_PREMIUM_BODY = MessageKey.ui("cosmetics.color.premium-body");
    public static final MessageKey COLOR_CUSTOM_TITLE = MessageKey.ui("cosmetics.color.custom-title");
    public static final MessageKey COLOR_CUSTOM_BODY = MessageKey.ui("cosmetics.color.custom-body");
    public static final MessageKey COLOR_CUSTOM_FROM = MessageKey.ui("cosmetics.color.custom-from");
    public static final MessageKey COLOR_CUSTOM_TO = MessageKey.ui("cosmetics.color.custom-to");
    public static final MessageKey COLOR_CUSTOM_SUBMIT = MessageKey.ui("cosmetics.color.custom-submit");
    public static final MessageKey COLOR_GRADIENT_TOOLTIP = MessageKey.ui("cosmetics.color.gradient-tooltip");
    public static final MessageKey COLOR_SELECTED_TOOLTIP = MessageKey.ui("cosmetics.color.selected-tooltip");
    public static final MessageKey COLOR_SET = MessageKey.success("cosmetics.color.set", "preview");
    public static final MessageKey COLOR_CLEARED = MessageKey.success("cosmetics.color.cleared");
    public static final MessageKey COLOR_INVALID = MessageKey.error("cosmetics.color.invalid", "input");
    public static final MessageKey COLOR_TOO_DARK = MessageKey.error("cosmetics.color.too-dark");
    public static final MessageKey COLOR_RESERVED_ERRORS = MessageKey.error("cosmetics.color.reserved-errors");
    public static final MessageKey COLOR_RESERVED_MONEY = MessageKey.error("cosmetics.color.reserved-money");
    public static final MessageKey COLOR_RESERVED_SERVER = MessageKey.error("cosmetics.color.reserved-server");
    public static final MessageKey COLOR_CUSTOM_NAME = MessageKey.ui("cosmetics.color.custom-name");
    public static final MessageKey COLOR_GRADIENT_NAME = MessageKey.ui("cosmetics.color.gradient-name");

    public static final MessageKey COLOR_BLACK = MessageKey.ui("cosmetics.colors.black");
    public static final MessageKey COLOR_DARK_BLUE = MessageKey.ui("cosmetics.colors.dark-blue");
    public static final MessageKey COLOR_DARK_GREEN = MessageKey.ui("cosmetics.colors.dark-green");
    public static final MessageKey COLOR_DARK_AQUA = MessageKey.ui("cosmetics.colors.dark-aqua");
    public static final MessageKey COLOR_DARK_RED = MessageKey.ui("cosmetics.colors.dark-red");
    public static final MessageKey COLOR_DARK_PURPLE = MessageKey.ui("cosmetics.colors.dark-purple");
    public static final MessageKey COLOR_GOLD = MessageKey.ui("cosmetics.colors.gold");
    public static final MessageKey COLOR_GRAY = MessageKey.ui("cosmetics.colors.gray");
    public static final MessageKey COLOR_DARK_GRAY = MessageKey.ui("cosmetics.colors.dark-gray");
    public static final MessageKey COLOR_BLUE = MessageKey.ui("cosmetics.colors.blue");
    public static final MessageKey COLOR_GREEN = MessageKey.ui("cosmetics.colors.green");
    public static final MessageKey COLOR_AQUA = MessageKey.ui("cosmetics.colors.aqua");
    public static final MessageKey COLOR_RED = MessageKey.ui("cosmetics.colors.red");
    public static final MessageKey COLOR_LIGHT_PURPLE = MessageKey.ui("cosmetics.colors.light-purple");
    public static final MessageKey COLOR_YELLOW = MessageKey.ui("cosmetics.colors.yellow");
    public static final MessageKey COLOR_WHITE = MessageKey.ui("cosmetics.colors.white");

    private static final Map<String, MessageKey> COLOR_NAMES = Map.ofEntries(
        Map.entry("black", COLOR_BLACK), Map.entry("dark_blue", COLOR_DARK_BLUE), Map.entry("dark_green", COLOR_DARK_GREEN),
        Map.entry("dark_aqua", COLOR_DARK_AQUA), Map.entry("dark_red", COLOR_DARK_RED), Map.entry("dark_purple", COLOR_DARK_PURPLE),
        Map.entry("gold", COLOR_GOLD), Map.entry("gray", COLOR_GRAY), Map.entry("dark_gray", COLOR_DARK_GRAY),
        Map.entry("blue", COLOR_BLUE), Map.entry("green", COLOR_GREEN), Map.entry("aqua", COLOR_AQUA), Map.entry("red", COLOR_RED),
        Map.entry("light_purple", COLOR_LIGHT_PURPLE), Map.entry("yellow", COLOR_YELLOW), Map.entry("white", COLOR_WHITE));

    /** The name of a vanilla colour. */
    static MessageKey colorName(NamedTextColor color) {
        return COLOR_NAMES.get(NamedTextColor.NAMES.key(color).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ nicknames
    public static final MessageKey NICK_TITLE = MessageKey.ui("cosmetics.nick.title");
    public static final MessageKey NICK_BODY = MessageKey.ui("cosmetics.nick.body", "min", "max");
    public static final MessageKey NICK_CURRENT = MessageKey.ui("cosmetics.nick.current", "nick");
    public static final MessageKey NICK_CURRENT_NONE = MessageKey.ui("cosmetics.nick.current-none");
    public static final MessageKey NICK_INPUT = MessageKey.ui("cosmetics.nick.input");
    public static final MessageKey NICK_STYLE_INPUT = MessageKey.ui("cosmetics.nick.style-input");
    public static final MessageKey NICK_CUSTOM_INPUT = MessageKey.ui("cosmetics.nick.custom-input");
    public static final MessageKey NICK_STYLE_DEFAULT = MessageKey.ui("cosmetics.nick.style-default");
    public static final MessageKey NICK_STYLE_CUSTOM = MessageKey.ui("cosmetics.nick.style-custom");
    public static final MessageKey NICK_SAVE = MessageKey.ui("cosmetics.nick.save");
    public static final MessageKey NICK_REMOVE = MessageKey.ui("cosmetics.nick.remove");
    public static final MessageKey NICK_SET = MessageKey.success("cosmetics.nick.set", "nick");
    public static final MessageKey NICK_REMOVED = MessageKey.success("cosmetics.nick.removed");
    public static final MessageKey NICK_NOT_SET = MessageKey.error("cosmetics.nick.not-set");
    public static final MessageKey NICK_LENGTH = MessageKey.error("cosmetics.nick.length", "min", "max");
    public static final MessageKey NICK_CHARACTERS = MessageKey.error("cosmetics.nick.characters");
    public static final MessageKey NICK_RESERVED = MessageKey.error("cosmetics.nick.reserved", "word");
    public static final MessageKey NICK_PLAYER_NAME = MessageKey.error("cosmetics.nick.player-name");
    public static final MessageKey NICK_TAKEN = MessageKey.error("cosmetics.nick.taken");
    public static final MessageKey NICK_FILTERED = MessageKey.error("cosmetics.nick.filtered");
    public static final MessageKey NICK_COOLDOWN = MessageKey.error("cosmetics.nick.cooldown", "time");
    public static final MessageKey NICK_ADMIN_SET = MessageKey.chat("cosmetics.nick.admin-set", "name", "nick");
    public static final MessageKey NICK_ADMIN_REMOVED = MessageKey.chat("cosmetics.nick.admin-removed", "name");
    public static final MessageKey NICK_ADMIN_NONE = MessageKey.chat("cosmetics.nick.admin-none", "name");
    public static final MessageKey NICK_ADMIN_REFUSED = MessageKey.chat("cosmetics.nick.admin-refused", "reason");
    public static final MessageKey NICK_STAFF_SET = MessageKey.notify("cosmetics.nick.staff-set", "nick");
    public static final MessageKey NICK_STAFF_REMOVED = MessageKey.notify("cosmetics.nick.staff-removed");
    public static final MessageKey NICK_LOST = MessageKey.notify("cosmetics.nick.lost", "nick");
    public static final MessageKey REALNAME = MessageKey.chat("cosmetics.nick.realname", "nick", "name");
    public static final MessageKey REALNAME_NONE = MessageKey.chat("cosmetics.nick.realname-none", "nick");

    // ------------------------------------------------------------------ tags
    public static final MessageKey TAGS_TITLE = MessageKey.ui("cosmetics.tags.title");
    public static final MessageKey TAGS_BODY = MessageKey.ui("cosmetics.tags.body", "count", "total");
    public static final MessageKey TAGS_CURRENT = MessageKey.ui("cosmetics.tags.current", "tag");
    public static final MessageKey TAGS_CURRENT_NONE = MessageKey.ui("cosmetics.tags.current-none");
    public static final MessageKey TAGS_EMPTY = MessageKey.ui("cosmetics.tags.empty");
    public static final MessageKey TAGS_REMOVE = MessageKey.ui("cosmetics.tags.remove");
    public static final MessageKey TAG_HOVER = MessageKey.ui("cosmetics.tags.hover", "description");
    public static final MessageKey TAG_TOOLTIP_SELECTED = MessageKey.ui("cosmetics.tags.tooltip-selected");
    public static final MessageKey TAG_TOOLTIP_LOCKED = MessageKey.ui("cosmetics.tags.tooltip-locked", "unlock");
    public static final MessageKey TAG_TOOLTIP_MONTHLY = MessageKey.ui("cosmetics.tags.tooltip-monthly");
    public static final MessageKey TAG_TOOLTIP_OWNED = MessageKey.ui("cosmetics.tags.tooltip-owned");
    public static final MessageKey TAG_SET = MessageKey.success("cosmetics.tags.set", "tag");
    public static final MessageKey TAG_CLAIMED = MessageKey.notify("cosmetics.tags.claimed", "tag");
    public static final MessageKey TAG_REMOVED = MessageKey.success("cosmetics.tags.removed");
    public static final MessageKey TAG_LOCKED = MessageKey.error("cosmetics.tags.locked", "unlock");
    public static final MessageKey TAG_UNKNOWN = MessageKey.error("cosmetics.tags.unknown", "id");
    public static final MessageKey TAG_NOT_SET = MessageKey.error("cosmetics.tags.not-set");

    // ------------------------------------------------------------------ join and leave lines
    public static final MessageKey JOIN_RANK_LINE = MessageKey.ui("cosmetics.join.rank-line", "rank", "name");
    public static final MessageKey JOIN_LINE = MessageKey.ui("cosmetics.join.line", "name");
    public static final MessageKey QUIT_RANK_LINE = MessageKey.ui("cosmetics.join.rank-leave", "rank", "name");
    public static final MessageKey QUIT_LINE = MessageKey.ui("cosmetics.join.leave", "name");
    public static final MessageKey CUSTOM_RANK_LINE = MessageKey.ui("cosmetics.join.custom-rank-line", "rank", "message");
    public static final MessageKey CUSTOM_LINE = MessageKey.ui("cosmetics.join.custom-line", "message");
    public static final MessageKey JOINMSG_TITLE = MessageKey.ui("cosmetics.join.title");
    public static final MessageKey JOINMSG_BODY = MessageKey.ui("cosmetics.join.body", "max");
    public static final MessageKey JOINMSG_JOIN_INPUT = MessageKey.ui("cosmetics.join.join-input");
    public static final MessageKey JOINMSG_LEAVE_INPUT = MessageKey.ui("cosmetics.join.leave-input");
    public static final MessageKey JOINMSG_SAVE = MessageKey.ui("cosmetics.join.save");
    public static final MessageKey JOINMSG_PREVIEW = MessageKey.ui("cosmetics.join.preview");
    public static final MessageKey JOINMSG_RESET = MessageKey.ui("cosmetics.join.reset");
    public static final MessageKey JOINMSG_RANK_TITLE = MessageKey.ui("cosmetics.join.rank-title");
    public static final MessageKey JOINMSG_RANK_BODY = MessageKey.ui("cosmetics.join.rank-body");
    public static final MessageKey JOINMSG_JOIN_SET = MessageKey.success("cosmetics.join.join-set");
    public static final MessageKey JOINMSG_LEAVE_SET = MessageKey.success("cosmetics.join.leave-set");
    public static final MessageKey JOINMSG_SAVED = MessageKey.success("cosmetics.join.saved");
    public static final MessageKey JOINMSG_JOIN_RESET = MessageKey.success("cosmetics.join.join-reset");
    public static final MessageKey JOINMSG_LEAVE_RESET = MessageKey.success("cosmetics.join.leave-reset");
    public static final MessageKey JOINMSG_RESET_DONE = MessageKey.success("cosmetics.join.reset-done");
    public static final MessageKey JOINMSG_PREVIEW_HEADER = MessageKey.chat("cosmetics.join.preview-header");
    public static final MessageKey JOINMSG_EMPTY = MessageKey.error("cosmetics.join.empty");
    public static final MessageKey JOINMSG_TOO_LONG = MessageKey.error("cosmetics.join.too-long", "max");
    public static final MessageKey JOINMSG_CHARACTERS = MessageKey.error("cosmetics.join.characters");
    public static final MessageKey JOINMSG_TOKENS = MessageKey.error("cosmetics.join.tokens");
    public static final MessageKey JOINMSG_LINK = MessageKey.error("cosmetics.join.link");
    public static final MessageKey JOINMSG_FILTERED = MessageKey.error("cosmetics.join.filtered");
    public static final MessageKey JOINMSG_OFF = MessageKey.error("cosmetics.join.off");

    // ------------------------------------------------------------------ kill effects
    public static final MessageKey KILL_TITLE = MessageKey.ui("cosmetics.kill.title");
    public static final MessageKey KILL_BODY = MessageKey.ui("cosmetics.kill.body");
    public static final MessageKey KILL_CURRENT = MessageKey.ui("cosmetics.kill.current", "effect");
    public static final MessageKey KILL_CURRENT_NONE = MessageKey.ui("cosmetics.kill.current-none");
    public static final MessageKey KILL_NONE_BUTTON = MessageKey.ui("cosmetics.kill.none");
    public static final MessageKey KILL_SET = MessageKey.success("cosmetics.kill.set", "effect");
    public static final MessageKey KILL_CLEARED = MessageKey.success("cosmetics.kill.cleared");
    public static final MessageKey KILL_UNKNOWN = MessageKey.error("cosmetics.kill.unknown", "id");
    public static final MessageKey KILL_OFF = MessageKey.error("cosmetics.kill.off");
    public static final MessageKey KILL_HEARTS = MessageKey.ui("cosmetics.kill.effects.hearts");
    public static final MessageKey KILL_FLAMES = MessageKey.ui("cosmetics.kill.effects.flames");
    public static final MessageKey KILL_SOULS = MessageKey.ui("cosmetics.kill.effects.souls");
    public static final MessageKey KILL_TOTEM = MessageKey.ui("cosmetics.kill.effects.totem");
    public static final MessageKey KILL_LIGHTNING = MessageKey.ui("cosmetics.kill.effects.lightning");
    public static final MessageKey KILL_NOTES = MessageKey.ui("cosmetics.kill.effects.notes");
    public static final MessageKey KILL_ENDER = MessageKey.ui("cosmetics.kill.effects.ender");
    public static final MessageKey KILL_HEARTS_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.hearts");
    public static final MessageKey KILL_FLAMES_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.flames");
    public static final MessageKey KILL_SOULS_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.souls");
    public static final MessageKey KILL_TOTEM_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.totem");
    public static final MessageKey KILL_LIGHTNING_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.lightning");
    public static final MessageKey KILL_NOTES_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.notes");
    public static final MessageKey KILL_ENDER_DESCRIPTION = MessageKey.ui("cosmetics.kill.descriptions.ender");

    /** The name of a kill effect. */
    static MessageKey name(KillEffect effect) {
        return switch (effect) {
            case HEARTS -> KILL_HEARTS;
            case FLAMES -> KILL_FLAMES;
            case SOULS -> KILL_SOULS;
            case TOTEM -> KILL_TOTEM;
            case LIGHTNING -> KILL_LIGHTNING;
            case NOTES -> KILL_NOTES;
            case ENDER -> KILL_ENDER;
        };
    }

    /** One line about a kill effect. */
    static MessageKey description(KillEffect effect) {
        return switch (effect) {
            case HEARTS -> KILL_HEARTS_DESCRIPTION;
            case FLAMES -> KILL_FLAMES_DESCRIPTION;
            case SOULS -> KILL_SOULS_DESCRIPTION;
            case TOTEM -> KILL_TOTEM_DESCRIPTION;
            case LIGHTNING -> KILL_LIGHTNING_DESCRIPTION;
            case NOTES -> KILL_NOTES_DESCRIPTION;
            case ENDER -> KILL_ENDER_DESCRIPTION;
        };
    }

    // ------------------------------------------------------------------ settings
    public static final MessageKey SETTING_CHAT_COLORS = MessageKey.ui("cosmetics.settings.chat-colors");
    public static final MessageKey SETTING_CHAT_COLORS_DESCRIPTION = MessageKey.ui("cosmetics.settings.chat-colors-description");
    public static final MessageKey SETTING_KILL_EFFECTS = MessageKey.ui("cosmetics.settings.kill-effects");
    public static final MessageKey SETTING_KILL_EFFECTS_DESCRIPTION = MessageKey.ui("cosmetics.settings.kill-effects-description");

    // ------------------------------------------------------------------ staff
    public static final MessageKey ADMIN_STATUS = MessageKey.chat("cosmetics.admin.status", "state", "players", "nicks", "tags",
        "played", "limited");
    public static final MessageKey ADMIN_ON = MessageKey.ui("cosmetics.admin.on");
    public static final MessageKey ADMIN_OFF = MessageKey.ui("cosmetics.admin.off");
    public static final MessageKey ADMIN_SHOW = MessageKey.chat("cosmetics.admin.show", "name", "color", "nick", "tag", "owned", "join",
        "leave", "effect");
    public static final MessageKey ADMIN_RESET = MessageKey.chat("cosmetics.admin.reset", "name");
    public static final MessageKey ADMIN_RESET_KEPT = MessageKey.chat("cosmetics.admin.reset-kept", "name", "owned");
    public static final MessageKey STAFF_RESET = MessageKey.notify("cosmetics.admin.staff-reset");
    public static final MessageKey ADMIN_OWNED_GIVEN = MessageKey.chat("cosmetics.admin.owned-given", "name", "id");
    public static final MessageKey ADMIN_OWNED_TAKEN = MessageKey.chat("cosmetics.admin.owned-taken", "name", "id");
    public static final MessageKey ADMIN_OWNED_ALREADY = MessageKey.chat("cosmetics.admin.owned-already", "name", "id");
    public static final MessageKey ADMIN_OWNED_NOT = MessageKey.chat("cosmetics.admin.owned-not", "name", "id");

    private CosmeticsMessages() {
    }
}
