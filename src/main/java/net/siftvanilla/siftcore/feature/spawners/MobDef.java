package net.siftvanilla.siftcore.feature.spawners;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One configured spawner type ({@code mobs.<id>} in {@code features/spawners.yml}).
 *
 * @param id            the mob id, a vanilla entity key without namespace ({@code cave_spider})
 * @param name          display name in sentence case ({@code Cave spider})
 * @param enabled       whether these spawners can be placed and stacked and make loot
 * @param killsPerCycle kills one stacked spawner makes every loot cycle (fractions are rounded fairly)
 * @param xpPerKill     XP every kill stores
 * @param stackCap      the most spawners one stack holds (before rank bonuses)
 * @param slots         storage slots of 64 items per stacked spawner
 * @param drops         the drop table
 */
record MobDef(String id, String name, boolean enabled, double killsPerCycle, int xpPerKill, int stackCap, int slots,
              List<DropEntry> drops) {

    MobDef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        drops = List.copyOf(drops);
    }

    /** The namespaced entity key ({@code minecraft:cave_spider}). */
    String entityKey() {
        return "minecraft:" + this.id;
    }

    /** The name inside a sentence ({@code cave spider}). */
    String lowerName() {
        return this.name.toLowerCase(Locale.ROOT);
    }

    /** Average items and XP one stacked spawner makes per hour of activity at a cycle interval in seconds. */
    double itemsPerHour(double intervalSeconds) {
        return cyclesPerHour(intervalSeconds) * this.killsPerCycle * LootMath.expectedItemsPerKill(this.drops);
    }

    double xpPerHour(double intervalSeconds) {
        return cyclesPerHour(intervalSeconds) * this.killsPerCycle * this.xpPerKill;
    }

    static double cyclesPerHour(double intervalSeconds) {
        return intervalSeconds <= 0 ? 0 : 3600.0 / intervalSeconds;
    }

    /** A readable name for a mob id that is not configured any more ({@code zombie_villager} → {@code Zombie villager}). */
    static String fallbackName(String id) {
        String spaced = id.replace('_', ' ');
        return spaced.isEmpty() ? id : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
