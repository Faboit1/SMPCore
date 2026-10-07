package net.siftvanilla.siftcore;

import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import net.siftvanilla.siftcore.feature.hub.HubFeature;

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
        HubFeature hub = new HubFeature(this.services, this.problems);
        features.add(economy);
        features.add(hub);
        features.add(admin);
        return features;
    }
}
