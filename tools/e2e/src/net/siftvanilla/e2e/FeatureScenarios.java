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
        list.addAll(AuctionScenarios.all());
        list.addAll(TeamsScenarios.all());
        list.addAll(SellShopScenarios.all());
        list.addAll(DisplaysScenarios.all());
        list.addAll(StaffScenarios.all());
        list.addAll(CratesScenarios.all());
        list.addAll(CombatScenarios.all());
        list.addAll(TeleportScenarios.all());
        list.addAll(VaultScenarios.all());
        list.addAll(UiScenarios.all());
        list.addAll(CoreScenarios.all());
        list.addAll(AxAuctionsScenarios.all());
        list.addAll(FriendsScenarios.all());
        list.addAll(SpawnerScenarios.all());
        list.addAll(AfkScenarios.all());
        list.addAll(SellPlusScenarios.all());
        list.addAll(OrdersScenarios.all());
        list.addAll(ChatScenarios.all());
        list.addAll(KitsScenarios.all());
        list.addAll(IntegrationsScenarios.all());
        list.addAll(ScoreboardScenarios.all());
        list.addAll(BoostersScenarios.all());
        list.addAll(MoneyScenarios.all());
        list.addAll(CosmeticsScenarios.all());
        list.addAll(ExtrasScenarios.all());
        return list;
    }
}
