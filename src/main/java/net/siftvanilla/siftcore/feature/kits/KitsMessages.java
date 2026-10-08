package net.siftvanilla.siftcore.feature.kits;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the kits feature ({@code lang/kits.yml}). */
public final class KitsMessages {

    public static final MessageKey HUB_LABEL = MessageKey.ui("kits.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("kits.hub.description");

    // ------------------------------------------------------------------ the kits dialog

    public static final MessageKey LIST_TITLE = MessageKey.ui("kits.list.title");
    public static final MessageKey LIST_LINE = MessageKey.ui("kits.list.line", "name", "status");
    public static final MessageKey LIST_EMPTY = MessageKey.ui("kits.list.empty");
    public static final MessageKey LIST_WAITING = MessageKey.ui("kits.list.waiting");
    public static final MessageKey LIST_CLAIM_ONE = MessageKey.ui("kits.list.claim-one", "name");
    public static final MessageKey LIST_CLAIM_READY = MessageKey.ui("kits.list.claim-ready", "count");
    public static final MessageKey LIST_COLLECT = MessageKey.ui("kits.list.collect");
    public static final MessageKey LIST_PERKS = MessageKey.ui("kits.list.perks");

    public static final MessageKey STATUS_READY = MessageKey.ui("kits.status.ready");
    public static final MessageKey STATUS_WAITING = MessageKey.ui("kits.status.waiting", "time");
    public static final MessageKey STATUS_CLAIMED = MessageKey.ui("kits.status.claimed");
    public static final MessageKey STATUS_LOCKED = MessageKey.ui("kits.status.locked");

    public static final MessageKey KIT_TITLE = MessageKey.ui("kits.kit.title", "name");
    public static final MessageKey KIT_DESCRIPTION = MessageKey.ui("kits.kit.description", "description");
    public static final MessageKey KIT_ONCE = MessageKey.ui("kits.kit.once");
    public static final MessageKey KIT_EVERY = MessageKey.ui("kits.kit.every", "time");
    public static final MessageKey KIT_READY = MessageKey.ui("kits.kit.ready");
    public static final MessageKey KIT_WAITING = MessageKey.ui("kits.kit.waiting", "time");
    public static final MessageKey KIT_CLAIMED = MessageKey.ui("kits.kit.claimed");
    public static final MessageKey KIT_LOCKED = MessageKey.ui("kits.kit.locked");
    public static final MessageKey KIT_ITEM = MessageKey.ui("kits.kit.item", "name");
    public static final MessageKey KIT_ITEM_MANY = MessageKey.ui("kits.kit.item-many", "name", "amount");
    public static final MessageKey KIT_KEYS = MessageKey.ui("kits.kit.keys", "keys");
    public static final MessageKey KIT_CLAIM = MessageKey.ui("kits.kit.claim");

    public static final MessageKey KEYS_ONE = MessageKey.ui("kits.keys.one", "crate");
    public static final MessageKey KEYS_MANY = MessageKey.ui("kits.keys.many", "count", "crate");
    public static final MessageKey SEPARATOR = MessageKey.ui("kits.separator");

    // ------------------------------------------------------------------ claiming

    public static final MessageKey CLAIMED = MessageKey.success("kits.claim.claimed", "name");
    public static final MessageKey CLAIMED_MANY = MessageKey.success("kits.claim.claimed-many", "count");
    public static final MessageKey CLAIM_BOX = MessageKey.chat("kits.claim.claim-box", "name");
    public static final MessageKey CLAIM_BOX_MANY = MessageKey.chat("kits.claim.claim-box-many");
    public static final MessageKey KEYS_GIVEN = MessageKey.chat("kits.claim.keys", "name", "keys");
    public static final MessageKey KEYS_FAILED = MessageKey.chat("kits.claim.keys-failed", "name", "keys");
    public static final MessageKey NOT_READY = MessageKey.error("kits.claim.not-ready", "name", "time");
    public static final MessageKey ALREADY_CLAIMED = MessageKey.error("kits.claim.already-claimed", "name");
    public static final MessageKey UNKNOWN = MessageKey.error("kits.claim.unknown", "input");
    public static final MessageKey LOCKED = MessageKey.error("kits.claim.locked", "name");
    public static final MessageKey IN_COMBAT = MessageKey.error("kits.claim.in-combat", "time");
    public static final MessageKey CANCELLED = MessageKey.info("kits.claim.cancelled", "name");
    public static final MessageKey FAILED = MessageKey.error("kits.claim.failed");
    public static final MessageKey NONE_READY = MessageKey.error("kits.claim.none-ready");

    public static final MessageKey COLLECTED = MessageKey.success("kits.collect.done");
    public static final MessageKey COLLECTED_PARTLY = MessageKey.info("kits.collect.partly");
    public static final MessageKey COLLECT_NO_ROOM = MessageKey.error("kits.collect.no-room");
    public static final MessageKey COLLECT_NONE = MessageKey.info("kits.collect.none");

    // ------------------------------------------------------------------ reminders

    public static final MessageKey REMINDER_JOIN = MessageKey.notify("kits.reminder.join", "kits");
    public static final MessageKey REMINDER_READY = MessageKey.notify("kits.reminder.ready", "name");
    public static final MessageKey REMINDER_WAITING = MessageKey.chat("kits.reminder.waiting");
    public static final MessageKey SETTING_REMINDERS = MessageKey.ui("kits.settings.reminders");
    public static final MessageKey SETTING_REMINDERS_DESCRIPTION = MessageKey.ui("kits.settings.reminders-description");

    // ------------------------------------------------------------------ staff

    public static final MessageKey ADMIN_GIVEN = MessageKey.chat("kits.admin.given", "player", "name");
    public static final MessageKey ADMIN_GIVEN_OFFLINE = MessageKey.chat("kits.admin.given-offline", "player", "name");
    public static final MessageKey ADMIN_RECEIVED = MessageKey.notify("kits.admin.received", "name");
    public static final MessageKey ADMIN_GIVE_FAILED = MessageKey.chat("kits.admin.give-failed", "player", "name");
    public static final MessageKey ADMIN_GIVE_CANCELLED = MessageKey.chat("kits.admin.give-cancelled", "player", "name");
    public static final MessageKey ADMIN_RESET_ONE = MessageKey.chat("kits.admin.reset-one", "player", "name");
    public static final MessageKey ADMIN_RESET_ALL = MessageKey.chat("kits.admin.reset-all", "player");
    public static final MessageKey ADMIN_RESET_NONE = MessageKey.chat("kits.admin.reset-none", "player");
    public static final MessageKey ADMIN_CHECK_HEADER = MessageKey.chat("kits.admin.check-header", "player");
    public static final MessageKey ADMIN_CHECK_LINE = MessageKey.chat("kits.admin.check-line", "name", "id", "status");
    public static final MessageKey ADMIN_LIST_HEADER = MessageKey.chat("kits.admin.list-header", "count");
    public static final MessageKey ADMIN_LIST_LINE = MessageKey.chat("kits.admin.list-line", "name", "id", "cooldown", "items", "access");
    public static final MessageKey ADMIN_EVERYONE = MessageKey.ui("kits.admin.everyone");
    public static final MessageKey ADMIN_NODE = MessageKey.ui("kits.admin.node", "node");

    // ------------------------------------------------------------------ perks

    public static final MessageKey PERKS_TITLE = MessageKey.ui("kits.perks.title");
    public static final MessageKey PERKS_BODY = MessageKey.ui("kits.perks.body");
    public static final MessageKey PERK_COMMAND = MessageKey.ui("kits.perks.command", "command");
    public static final MessageKey PERK_EC = MessageKey.ui("kits.perks.names.ec");
    public static final MessageKey PERK_CRAFT = MessageKey.ui("kits.perks.names.craft");
    public static final MessageKey PERK_ANVIL = MessageKey.ui("kits.perks.names.anvil");
    public static final MessageKey PERK_STONECUTTER = MessageKey.ui("kits.perks.names.stonecutter");
    public static final MessageKey PERK_GRINDSTONE = MessageKey.ui("kits.perks.names.grindstone");
    public static final MessageKey PERK_SMITHING = MessageKey.ui("kits.perks.names.smithing");
    public static final MessageKey PERK_LOOM = MessageKey.ui("kits.perks.names.loom");
    public static final MessageKey PERK_CARTOGRAPHY = MessageKey.ui("kits.perks.names.cartography");
    public static final MessageKey PERK_TRASH = MessageKey.ui("kits.perks.names.trash");
    public static final MessageKey PERK_HAT = MessageKey.ui("kits.perks.names.hat");
    public static final MessageKey PERK_IN_COMBAT = MessageKey.error("kits.perks.in-combat", "time");
    public static final MessageKey PERK_CLOSED = MessageKey.error("kits.perks.closed");
    public static final MessageKey EC_OTHERS_TITLE = MessageKey.ui("kits.perks.ec-others-title", "name");
    public static final MessageKey TRASH_TITLE = MessageKey.ui("kits.perks.trash-title");
    public static final MessageKey TRASH_DELETED_ONE = MessageKey.info("kits.perks.trash-deleted-one");
    public static final MessageKey TRASH_DELETED = MessageKey.info("kits.perks.trash-deleted", "count");
    public static final MessageKey HAT_EMPTY = MessageKey.error("kits.perks.hat-empty");
    public static final MessageKey HAT_BLOCKED = MessageKey.error("kits.perks.hat-blocked");
    public static final MessageKey HAT_CURSED = MessageKey.error("kits.perks.hat-cursed");
    public static final MessageKey HAT_NO_ROOM = MessageKey.error("kits.perks.hat-no-room");
    public static final MessageKey HAT_DONE = MessageKey.success("kits.perks.hat-done", "item");

    private KitsMessages() {
    }

    /** The label of a perk in the perks dialog. */
    static MessageKey name(Perk perk) {
        return switch (perk) {
            case EC -> PERK_EC;
            case CRAFT -> PERK_CRAFT;
            case ANVIL -> PERK_ANVIL;
            case STONECUTTER -> PERK_STONECUTTER;
            case GRINDSTONE -> PERK_GRINDSTONE;
            case SMITHING -> PERK_SMITHING;
            case LOOM -> PERK_LOOM;
            case CARTOGRAPHY -> PERK_CARTOGRAPHY;
            case TRASH -> PERK_TRASH;
            case HAT -> PERK_HAT;
        };
    }
}
