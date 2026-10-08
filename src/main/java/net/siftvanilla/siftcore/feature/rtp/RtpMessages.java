package net.siftvanilla.siftcore.feature.rtp;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of random teleport ({@code lang/rtp.yml}). */
public final class RtpMessages {

    public static final MessageKey SEARCHING = MessageKey.info("rtp.searching");
    public static final MessageKey ALREADY_SEARCHING = MessageKey.error("rtp.already-searching");
    public static final MessageKey NO_SPOT = MessageKey.error("rtp.no-spot");
    public static final MessageKey LANDED = MessageKey.success("rtp.landed", "region", "x", "z");
    public static final MessageKey LANDED_PAID = MessageKey.success("rtp.landed-paid", "region", "x", "z", "amount");
    public static final MessageKey REFUNDED = MessageKey.info("rtp.refunded", "amount");
    public static final MessageKey CANCELLED = MessageKey.info("rtp.cancelled");
    public static final MessageKey UNKNOWN = MessageKey.error("rtp.unknown", "name");
    public static final MessageKey DISABLED = MessageKey.error("rtp.disabled", "region");
    public static final MessageKey NO_PERMISSION = MessageKey.error("rtp.no-permission", "region");
    public static final MessageKey UNAVAILABLE = MessageKey.error("rtp.unavailable", "region");
    public static final MessageKey COOLDOWN = MessageKey.error("rtp.cooldown", "region", "time");
    public static final MessageKey NONE = MessageKey.error("rtp.none");
    public static final MessageKey SENT = MessageKey.chat("rtp.sent", "name", "region", "x", "z");
    public static final MessageKey SENT_FAILED = MessageKey.chat("rtp.sent-failed", "name", "region");

    public static final MessageKey MENU_TITLE = MessageKey.ui("rtp.menu.title");
    public static final MessageKey MENU_INTRO = MessageKey.ui("rtp.menu.intro");
    public static final MessageKey MENU_LINE_FREE = MessageKey.ui("rtp.menu.line-free", "region", "min", "max");
    public static final MessageKey MENU_LINE_COST = MessageKey.ui("rtp.menu.line-cost", "region", "min", "max", "amount");
    public static final MessageKey MENU_READY = MessageKey.ui("rtp.menu.ready");
    public static final MessageKey MENU_WAIT = MessageKey.ui("rtp.menu.wait", "time");
    public static final MessageKey MENU_TOOLTIP_FREE = MessageKey.ui("rtp.menu.tooltip-free", "min", "max");
    public static final MessageKey MENU_TOOLTIP_COST = MessageKey.ui("rtp.menu.tooltip-cost", "min", "max", "amount");

    public static final MessageKey HUB_LABEL = MessageKey.ui("rtp.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("rtp.hub.description");

    private RtpMessages() {
    }
}
