package net.siftvanilla.siftcore.feature.hub;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the main menu ({@code lang/hub.yml}). */
public final class HubMessages {

    public static final MessageKey TITLE = MessageKey.ui("hub.title");
    public static final MessageKey BODY = MessageKey.ui("hub.body", "name", "balance", "shards");
    public static final MessageKey LINKS = MessageKey.ui("hub.links.label");
    public static final MessageKey LINKS_DESCRIPTION = MessageKey.ui("hub.links.description");
    public static final MessageKey LINKS_TITLE = MessageKey.ui("hub.links.title");
    public static final MessageKey MENU_LABEL = MessageKey.ui("hub.menu.label");
    public static final MessageKey MENU_DESCRIPTION = MessageKey.ui("hub.menu.description");
    public static final MessageKey UNAVAILABLE = MessageKey.error("hub.unavailable");

    private HubMessages() {
    }
}
