package net.siftvanilla.siftcore.feature.chat;

import net.siftvanilla.siftcore.core.permission.Permissions;

/** Permission nodes of chat, private messages and ignore lists. */
final class ChatNodes {

    static final String MSG = "siftcore.command.msg";
    static final String REPLY = "siftcore.command.reply";
    static final String IGNORE = "siftcore.command.ignore";
    static final String MSGTOGGLE = "siftcore.command.msgtoggle";
    static final String SOCIALSPY = "siftcore.chat.socialspy";
    static final String BYPASS = "siftcore.chat.bypass";
    static final String FILTER_BYPASS = "siftcore.chat.filter.bypass";
    static final String LINKS = "siftcore.chat.links";
    static final String ITEM = "siftcore.chat.item";
    static final String UNIGNORABLE = "siftcore.chat.unignorable";
    static final String MSG_BYPASS = "siftcore.chat.msg.bypass";
    static final String ADMIN = "siftcore.admin.chat";

    private ChatNodes() {
    }

    static void declare(Permissions permissions) {
        permissions.declare(MSG, "Send private messages with /msg", true);
        permissions.declare(REPLY, "Answer private messages with /r", true);
        permissions.declare(IGNORE, "Ignore players with /ignore", true);
        permissions.declare(MSGTOGGLE, "Turn incoming private messages on or off with /msgtoggle", true);
        permissions.declare(ITEM, "Show the held item in chat with [item]", true);
        permissions.declare(SOCIALSPY, "See private messages between players with /socialspy", false);
        permissions.declare(BYPASS, "Skip chat cooldown, rate limit, repeat and capitals checks, chat lock and slow mode", false);
        permissions.declare(FILTER_BYPASS, "Skip the chat word filter", false);
        permissions.declare(LINKS, "Post links and server addresses in chat and private messages", false);
        permissions.declare(UNIGNORABLE, "Can't be ignored: chat and private messages reach players who ignore you", false);
        permissions.declare(MSG_BYPASS, "Send private messages to players who turned them off", false);
        permissions.declare(ADMIN, "Lock chat, set slow mode, test the filter and look up ignore lists with /chat", false);
    }
}
