package net.siftvanilla.siftcore;

import java.util.List;
import net.siftvanilla.siftcore.storage.Migrations.Migration;

/** Every schema migration, in order. Never edit a released migration; add a new one. */
final class MigrationList {

    static final List<Migration> ALL = List.of(
        new Migration(1, "core", "db/migrations/V001__core.sql"),
        new Migration(2, "auction", "db/migrations/V002__auction.sql"),
        new Migration(3, "orders", "db/migrations/V003__orders.sql"),
        new Migration(4, "spawners", "db/migrations/V004__spawners.sql"),
        new Migration(5, "teams", "db/migrations/V005__teams.sql"),
        new Migration(6, "homes", "db/migrations/V006__homes.sql"),
        new Migration(7, "combat", "db/migrations/V007__combat.sql"),
        new Migration(8, "rewards", "db/migrations/V008__rewards.sql"),
        new Migration(9, "social", "db/migrations/V009__social.sql"));

    private MigrationList() {
    }
}
