package net.siftvanilla.siftcore.feature.crates;

/**
 * A rarity tier rewards belong to.
 *
 * @param id       stable id (config key)
 * @param label    plain text shown in previews and results ({@code Rare})
 * @param audit    whether winning a reward of this rarity is written to the audit log
 * @param announce whether winning a reward of this rarity is announced in chat to everyone
 */
public record Rarity(String id, String label, boolean audit, boolean announce) {
}
