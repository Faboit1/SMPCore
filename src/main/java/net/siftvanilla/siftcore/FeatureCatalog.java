package net.siftvanilla.siftcore;

import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.SpawnerItems;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.feature.auction.AuctionFeature;
import net.siftvanilla.siftcore.feature.displays.DisplaysFeature;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import net.siftvanilla.siftcore.feature.extras.ExtrasFeature;
import net.siftvanilla.siftcore.feature.hub.HubFeature;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
import net.siftvanilla.siftcore.feature.shop.ShopFeature;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import net.siftvanilla.siftcore.feature.stats.StatsFeature;
import net.siftvanilla.siftcore.feature.teams.TeamsFeature;

/**
 * Constructs every feature in dependency order with exactly what it needs. A feature that depends on another takes
 * it (or one of its services) as a constructor argument, so the order here is the dependency order.
 */
final class FeatureCatalog {

    private final Services services;
    private final CombatTags combatTags;
    private final CoreControl control;
    private final List<ConfigProblem> problems;

    FeatureCatalog(Services services, CombatTags combatTags, CoreControl control, List<ConfigProblem> problems) {
        this.services = services;
        this.combatTags = combatTags;
        this.control = control;
        this.problems = problems;
    }

    List<Feature> create() {
        List<Feature> features = new ArrayList<>();
        AdminFeature admin = new AdminFeature(this.services, this.control);
        EconomyFeature economy = new EconomyFeature(this.services, this.problems);
        AuctionFeature auction = new AuctionFeature(this.services, this.problems, this.combatTags);
        HubFeature hub = new HubFeature(this.services, this.problems);
        StaffFeature staff = new StaffFeature(this.services, this.problems);
        StatsFeature stats = new StatsFeature(this.services, this.problems, AfkStatus.NONE, economy.economy(), admin);
        TeamsFeature teams = new TeamsFeature(this.services, this.problems, stats.recorder(), staff.mutes(), staff.vanish());
        SellFeature sell = new SellFeature(this.services, this.problems);
        features.add(economy);
        features.add(auction);
        features.add(teams);
        features.add(hub);
        features.add(staff);
        features.add(stats);
        features.add(sell);
        features.add(new ShopFeature(this.services, this.problems, sell.worth(), SpawnerItems.NONE));
        features.add(new ExtrasFeature(this.services, this.problems));
        features.add(new DisplaysFeature(this.services, this.problems));
        features.add(admin);
        return features;
    }
}
