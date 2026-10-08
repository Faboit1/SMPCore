package net.siftvanilla.e2e;

import java.util.ArrayList;
import java.util.List;

/** Scenarios for the gameplay features; filled in as features are integrated. */
final class FeatureScenarios {

    private FeatureScenarios() {
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.addAll(StatsScenarios.all());
        list.addAll(TeamsScenarios.all());
        list.addAll(SellShopScenarios.all());
        list.addAll(DisplaysScenarios.all());
        list.addAll(StaffScenarios.all());
        return list;
    }
}
