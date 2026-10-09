package net.siftvanilla.siftcore.feature.homes;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the homes feature ({@code lang/homes.yml}). */
public final class HomesMessages {

    public static final MessageKey SET = MessageKey.success("homes.set", "name", "count", "limit");
    public static final MessageKey MOVED = MessageKey.success("homes.moved", "name");
    public static final MessageKey LIMIT = MessageKey.error("homes.limit", "count", "limit");
    public static final MessageKey INVALID_NAME = MessageKey.error("homes.invalid-name");
    public static final MessageKey WORLD_DISABLED = MessageKey.error("homes.world-disabled");
    public static final MessageKey HOME_WORLD_DISABLED = MessageKey.error("homes.home-world-disabled", "name");
    public static final MessageKey IN_SPAWN = MessageKey.error("homes.in-spawn");
    public static final MessageKey IN_COMBAT = MessageKey.error("homes.in-combat", "time");
    public static final MessageKey LOADING = MessageKey.error("homes.loading");
    public static final MessageKey NOT_FOUND = MessageKey.error("homes.not-found", "name");
    public static final MessageKey NONE = MessageKey.error("homes.none");
    public static final MessageKey GONE = MessageKey.error("homes.gone", "name");
    public static final MessageKey WORLD_MISSING = MessageKey.error("homes.world-missing", "name");
    public static final MessageKey TELEPORTED = MessageKey.success("homes.teleported", "name");
    public static final MessageKey DELETED = MessageKey.success("homes.deleted", "name");
    public static final MessageKey UNLIMITED = MessageKey.ui("homes.unlimited");

    public static final MessageKey UNSAFE_TITLE = MessageKey.ui("homes.unsafe.title");
    public static final MessageKey UNSAFE_BODY = MessageKey.ui("homes.unsafe.body", "name", "reason");
    public static final MessageKey UNSAFE_QUESTION = MessageKey.ui("homes.unsafe.question");
    public static final MessageKey UNSAFE_LAVA = MessageKey.ui("homes.unsafe.lava");
    public static final MessageKey UNSAFE_FIRE = MessageKey.ui("homes.unsafe.fire");
    public static final MessageKey UNSAFE_BLOCKED = MessageKey.ui("homes.unsafe.blocked");
    public static final MessageKey UNSAFE_GO = MessageKey.ui("homes.unsafe.go");

    public static final MessageKey LIST_TITLE = MessageKey.ui("homes.list.title");
    public static final MessageKey LIST_HEADER = MessageKey.ui("homes.list.header", "count", "limit");
    public static final MessageKey LIST_EMPTY = MessageKey.ui("homes.list.empty");
    public static final MessageKey LIST_LINE = MessageKey.ui("homes.list.line", "name", "world", "x", "y", "z");
    public static final MessageKey LIST_PAGE = MessageKey.ui("homes.list.page", "page", "pages");
    public static final MessageKey LIST_DELETE = MessageKey.ui("homes.list.delete");
    public static final MessageKey LIST_TELEPORT_TOOLTIP = MessageKey.ui("homes.list.teleport-tooltip", "name");
    public static final MessageKey LIST_DELETE_TOOLTIP = MessageKey.ui("homes.list.delete-tooltip", "name");
    public static final MessageKey LIST_SET_HERE = MessageKey.ui("homes.list.set-here");
    public static final MessageKey LIST_NEXT = MessageKey.ui("homes.list.next");
    public static final MessageKey LIST_PREVIOUS = MessageKey.ui("homes.list.previous");

    public static final MessageKey DELETE_TITLE = MessageKey.ui("homes.delete.title");
    public static final MessageKey DELETE_BODY = MessageKey.ui("homes.delete.body", "name", "world", "x", "y", "z");
    public static final MessageKey DELETE_BUTTON = MessageKey.ui("homes.delete.button");

    public static final MessageKey FORM_TITLE = MessageKey.ui("homes.form.title");
    public static final MessageKey FORM_BODY = MessageKey.ui("homes.form.body");
    public static final MessageKey FORM_NAME = MessageKey.ui("homes.form.name");
    public static final MessageKey FORM_SUBMIT = MessageKey.ui("homes.form.submit");

    public static final MessageKey OTHER_TITLE = MessageKey.ui("homes.other.title", "name");
    public static final MessageKey OTHER_HEADER = MessageKey.ui("homes.other.header", "name", "count");
    public static final MessageKey ADMIN_HEADER = MessageKey.chat("homes.admin.header", "name", "count");
    public static final MessageKey ADMIN_LINE = MessageKey.chat("homes.admin.line", "home", "world", "x", "y", "z");
    public static final MessageKey ADMIN_EMPTY = MessageKey.chat("homes.admin.empty", "name");
    public static final MessageKey ADMIN_DELETED = MessageKey.chat("homes.admin.deleted", "home", "name");

    public static final MessageKey HUB_LABEL = MessageKey.ui("homes.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("homes.hub.description");

    private HomesMessages() {
    }
}
