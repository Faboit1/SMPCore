package net.siftvanilla.siftcore.feature.bounties;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the bounties feature ({@code lang/bounties.yml}). */
public final class BountiesMessages {

    public static final MessageKey PLACED = MessageKey.chat("bounties.place.placed", "amount", "name", "total");
    public static final MessageKey PLACED_TARGET = MessageKey.notify("bounties.place.target", "amount", "total");
    public static final MessageKey JOIN_REMINDER = MessageKey.chat("bounties.place.reminder", "total");
    public static final MessageKey PLACED_ANNOUNCE = MessageKey.chat("bounties.place.announce", "amount", "name", "total");
    public static final MessageKey PLACE_MINIMUM = MessageKey.error("bounties.place.minimum", "amount");
    public static final MessageKey PLACE_YOURSELF = MessageKey.error("bounties.place.yourself");
    public static final MessageKey PLACE_CANCELLED = MessageKey.info("bounties.place.cancelled");
    public static final MessageKey CONFIRM_TITLE = MessageKey.ui("bounties.place.confirm-title");
    public static final MessageKey CONFIRM_BODY = MessageKey.ui("bounties.place.confirm-body", "amount", "name", "tax", "time");
    public static final MessageKey CONFIRM_BUTTON = MessageKey.ui("bounties.place.confirm-button");
    public static final MessageKey FORM_TITLE = MessageKey.ui("bounties.place.form-title");
    public static final MessageKey FORM_BODY = MessageKey.ui("bounties.place.form-body", "time");
    public static final MessageKey FORM_PLAYER = MessageKey.ui("bounties.place.form-player");
    public static final MessageKey FORM_AMOUNT = MessageKey.ui("bounties.place.form-amount", "minimum");

    public static final MessageKey CLAIMED = MessageKey.notify("bounties.claim.claimed", "payout", "name", "tax");
    public static final MessageKey CLAIMED_NO_TAX = MessageKey.notify("bounties.claim.claimed-no-tax", "payout", "name");
    public static final MessageKey CLAIM_ANNOUNCE = MessageKey.chat("bounties.claim.announce", "killer", "total", "name");
    public static final MessageKey CLAIM_SPONSOR = MessageKey.chat("bounties.claim.sponsor", "name", "killer");
    public static final MessageKey CLAIM_OWN = MessageKey.chat("bounties.claim.own", "name");
    public static final MessageKey CLAIM_TOO_RICH = MessageKey.chat("bounties.claim.too-rich", "name");

    public static final MessageKey REFUND_EXPIRED = MessageKey.chat("bounties.refund.expired", "amount", "name");
    public static final MessageKey REFUND_REMOVED = MessageKey.chat("bounties.refund.removed", "amount", "name");

    public static final MessageKey LIST_TITLE = MessageKey.ui("bounties.list.title");
    public static final MessageKey LIST_INTRO = MessageKey.ui("bounties.list.intro");
    public static final MessageKey LIST_LINE = MessageKey.chat("bounties.list.line", "rank", "name", "total", "sponsors");
    public static final MessageKey LIST_EMPTY = MessageKey.chat("bounties.list.empty");
    public static final MessageKey LIST_YOURS = MessageKey.ui("bounties.list.yours", "total");
    public static final MessageKey LIST_HEADER = MessageKey.chat("bounties.list.header");
    public static final MessageKey LIST_PLACE = MessageKey.ui("bounties.list.place");

    public static final MessageKey DETAILS_TITLE = MessageKey.ui("bounties.details.title", "name");
    public static final MessageKey DETAILS_TOTAL = MessageKey.ui("bounties.details.total", "total");
    public static final MessageKey DETAILS_SPONSORS = MessageKey.ui("bounties.details.sponsors", "sponsors");
    public static final MessageKey DETAILS_YOURS = MessageKey.ui("bounties.details.yours", "amount");
    public static final MessageKey DETAILS_EXPIRY = MessageKey.ui("bounties.details.expiry", "time");
    public static final MessageKey DETAILS_HINT = MessageKey.ui("bounties.details.hint", "name", "tax");
    public static final MessageKey DETAILS_HINT_SELF = MessageKey.ui("bounties.details.hint-self");
    public static final MessageKey DETAILS_NONE = MessageKey.chat("bounties.details.none", "name");
    public static final MessageKey DETAILS_ADD = MessageKey.ui("bounties.details.add");
    public static final MessageKey DETAILS_PLACE = MessageKey.ui("bounties.details.place");

    public static final MessageKey SPONSORS_ONE = MessageKey.ui("bounties.sponsors.one");
    public static final MessageKey SPONSORS_MANY = MessageKey.ui("bounties.sponsors.many", "count");

    public static final MessageKey ADMIN_INFO_HEADER = MessageKey.chat("bounties.admin.info-header", "name", "total");
    public static final MessageKey ADMIN_INFO_LINE = MessageKey.chat("bounties.admin.info-line", "id", "amount", "sponsor", "ago", "left");
    public static final MessageKey ADMIN_NONE = MessageKey.chat("bounties.admin.none", "name");
    public static final MessageKey ADMIN_REMOVED = MessageKey.chat("bounties.admin.removed", "name", "amount", "count");
    public static final MessageKey ADMIN_EXPIRED = MessageKey.chat("bounties.admin.expired", "count", "amount");
    public static final MessageKey ADMIN_NOTHING_EXPIRED = MessageKey.chat("bounties.admin.nothing-expired");
    public static final MessageKey ADMIN_FAILED = MessageKey.chat("bounties.admin.failed", "count");
    public static final MessageKey ADMIN_SUMMARY = MessageKey.chat("bounties.admin.summary", "targets", "total", "escrow", "rows");

    public static final MessageKey HUB_LABEL = MessageKey.ui("bounties.hub.label");
    public static final MessageKey HUB_DESCRIPTION = MessageKey.ui("bounties.hub.description");

    private BountiesMessages() {
    }
}
