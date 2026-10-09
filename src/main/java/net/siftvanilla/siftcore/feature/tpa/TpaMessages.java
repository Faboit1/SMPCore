package net.siftvanilla.siftcore.feature.tpa;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of teleport requests ({@code lang/tpa.yml}). */
public final class TpaMessages {

    public static final MessageKey SENT = MessageKey.success("tpa.sent", "name", "time");
    public static final MessageKey SENT_HERE = MessageKey.success("tpa.sent-here", "name", "time");
    public static final MessageKey SENT_AFK = MessageKey.success("tpa.sent-afk", "name", "time");
    public static final MessageKey SENT_HERE_AFK = MessageKey.success("tpa.sent-here-afk", "name", "time");
    public static final MessageKey FRIEND_SENDER = MessageKey.chat("tpa.friend-sender", "name");
    public static final MessageKey FRIEND_TARGET = MessageKey.notify("tpa.friend-target", "name");
    public static final MessageKey INCOMING = MessageKey.notify("tpa.incoming", "name", "answer");
    public static final MessageKey INCOMING_HERE = MessageKey.notify("tpa.incoming-here", "name", "answer");
    public static final MessageKey ANSWER_LINK = MessageKey.ui("tpa.answer-link");
    public static final MessageKey ANSWER_HOVER = MessageKey.ui("tpa.answer-hover", "name");

    public static final MessageKey ACCEPTED = MessageKey.success("tpa.accepted", "name");
    public static final MessageKey ACCEPTED_BY = MessageKey.success("tpa.accepted-by", "name");
    public static final MessageKey DENIED = MessageKey.info("tpa.denied", "name");
    public static final MessageKey DENIED_BY = MessageKey.error("tpa.denied-by", "name");
    public static final MessageKey CANCELLED = MessageKey.info("tpa.cancelled", "name");
    public static final MessageKey CANCELLED_ALL = MessageKey.info("tpa.cancelled-all", "count");
    public static final MessageKey CANCELLED_BY = MessageKey.info("tpa.cancelled-by", "name");
    public static final MessageKey EXPIRED = MessageKey.info("tpa.expired", "name");
    public static final MessageKey NO_REQUESTS = MessageKey.error("tpa.no-requests");
    public static final MessageKey NO_REQUEST_FROM = MessageKey.error("tpa.no-request-from", "name");
    public static final MessageKey NO_OUTGOING = MessageKey.error("tpa.no-outgoing");
    public static final MessageKey NO_OUTGOING_TO = MessageKey.error("tpa.no-outgoing-to", "name");
    public static final MessageKey TARGET_DISABLED = MessageKey.error("tpa.target-disabled", "name");
    public static final MessageKey BLOCKED = MessageKey.error("tpa.blocked", "name");
    public static final MessageKey IN_COMBAT = MessageKey.error("tpa.in-combat", "time");
    public static final MessageKey OTHER_IN_COMBAT = MessageKey.error("tpa.other-in-combat", "name");
    public static final MessageKey OTHER_FIGHTING = MessageKey.error("tpa.other-fighting", "name");
    public static final MessageKey INSTANT = MessageKey.info("tpa.instant", "name");
    public static final MessageKey OTHER_LEFT = MessageKey.error("tpa.other-left", "name");
    public static final MessageKey NOT_MOVED = MessageKey.info("tpa.not-moved", "name");
    public static final MessageKey TOGGLED_ON = MessageKey.success("tpa.toggled-on");
    public static final MessageKey TOGGLED_OFF = MessageKey.success("tpa.toggled-off");
    public static final MessageKey FRIENDS_ON = MessageKey.success("tpa.friends-on");
    public static final MessageKey FRIENDS_OFF = MessageKey.success("tpa.friends-off");
    public static final MessageKey NO_FRIENDS = MessageKey.error("tpa.no-friends");

    public static final MessageKey ANSWER_TITLE = MessageKey.ui("tpa.dialog.title");
    public static final MessageKey ANSWER_BODY = MessageKey.ui("tpa.dialog.body", "name");
    public static final MessageKey ANSWER_BODY_HERE = MessageKey.ui("tpa.dialog.body-here", "name");
    public static final MessageKey ANSWER_EXPIRES = MessageKey.ui("tpa.dialog.expires", "time");
    public static final MessageKey ACCEPT = MessageKey.ui("tpa.dialog.accept");
    public static final MessageKey DENY = MessageKey.ui("tpa.dialog.deny");
    public static final MessageKey CHOICE_TITLE = MessageKey.ui("tpa.dialog.choice-title");
    public static final MessageKey CHOICE_ACCEPT_BODY = MessageKey.ui("tpa.dialog.choice-accept");
    public static final MessageKey CHOICE_DENY_BODY = MessageKey.ui("tpa.dialog.choice-deny");
    public static final MessageKey CHOICE_TOOLTIP = MessageKey.ui("tpa.dialog.choice-tooltip");
    public static final MessageKey CHOICE_TOOLTIP_HERE = MessageKey.ui("tpa.dialog.choice-tooltip-here");
    public static final MessageKey DENY_ALL = MessageKey.ui("tpa.dialog.deny-all");

    public static final MessageKey FORM_TITLE = MessageKey.ui("tpa.form.title");
    public static final MessageKey FORM_BODY = MessageKey.ui("tpa.form.body");
    public static final MessageKey FORM_WAITING = MessageKey.ui("tpa.form.waiting", "count");
    public static final MessageKey FORM_PLAYER = MessageKey.ui("tpa.form.player");
    public static final MessageKey FORM_DIRECTION = MessageKey.ui("tpa.form.direction");
    public static final MessageKey FORM_TO_THEM = MessageKey.ui("tpa.form.to-them");
    public static final MessageKey FORM_HERE = MessageKey.ui("tpa.form.here");
    public static final MessageKey FORM_SUBMIT = MessageKey.ui("tpa.form.submit");

    public static final MessageKey SETTING_LABEL = MessageKey.ui("tpa.settings.requests");
    public static final MessageKey SETTING_DESCRIPTION = MessageKey.ui("tpa.settings.requests-description");
    public static final MessageKey SETTING_FRIENDS_LABEL = MessageKey.ui("tpa.settings.friends");
    public static final MessageKey SETTING_FRIENDS_DESCRIPTION = MessageKey.ui("tpa.settings.friends-description");

    public static final MessageKey HUB_LABEL = MessageKey.ui("tpa.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("tpa.hub.description");

    private TpaMessages() {
    }
}
