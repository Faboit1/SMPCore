package net.siftvanilla.siftcore.api.economy;

/** How a ledger posting affects total supply. */
public enum Flow {
    /** New currency enters the economy (selling to the server, rewards, admin grants). */
    SOURCE,
    /** Currency leaves the economy (shop purchases, taxes, fees, admin removals). */
    SINK,
    /** Currency moves between accounts; the postings of one transaction net to zero. */
    TRANSFER
}
