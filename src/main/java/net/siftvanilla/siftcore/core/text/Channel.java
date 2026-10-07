package net.siftvanilla.siftcore.core.text;

/**
 * Where a message is shown. Transient feedback goes to the action bar; chat is only for things worth keeping;
 * titles are rare.
 */
public enum Channel {
    CHAT,
    ACTIONBAR,
    TITLE,
    /** Not sent by itself: used inside GUIs, dialogs, lore, scoreboard lines. */
    NONE
}
