package net.siftvanilla.siftcore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.feature.afk.AfkFeature;
import net.siftvanilla.siftcore.feature.auction.AuctionFeature;
import net.siftvanilla.siftcore.feature.bounties.BountiesFeature;
import net.siftvanilla.siftcore.feature.combat.CombatFeature;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import net.siftvanilla.siftcore.feature.displays.DisplaysFeature;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import net.siftvanilla.siftcore.feature.extras.ExtrasFeature;
import net.siftvanilla.siftcore.feature.homes.HomesFeature;
import net.siftvanilla.siftcore.feature.hub.HubFeature;
import net.siftvanilla.siftcore.feature.orders.OrdersFeature;
import net.siftvanilla.siftcore.feature.rtp.RtpFeature;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
import net.siftvanilla.siftcore.feature.shards.ShardsFeature;
import net.siftvanilla.siftcore.feature.shop.ShopFeature;
import net.siftvanilla.siftcore.feature.spawn.SpawnFeature;
import net.siftvanilla.siftcore.feature.spawners.SpawnersFeature;
import net.siftvanilla.siftcore.feature.staff.StaffFeature;
import net.siftvanilla.siftcore.feature.stats.StatsFeature;
import net.siftvanilla.siftcore.feature.teams.TeamsFeature;
import net.siftvanilla.siftcore.feature.tpa.TpaFeature;

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
        SpawnFeature spawn = new SpawnFeature(this.services, this.problems);
        AfkFeature afk = new AfkFeature(this.services, this.problems, this.combatTags, spawn.area(), staff.vanish());
        StatsFeature stats = new StatsFeature(this.services, this.problems, afk.status(), economy.economy(), admin);
        TeamsFeature teams = new TeamsFeature(this.services, this.problems, stats.recorder(), staff.mutes(), staff.vanish());
        // Selling routes items into buy orders, but orders are built after sell (they price with sell.worth()):
        // a late-bound market breaks the cycle.
        AtomicReference<OrderMarket> orderMarket = new AtomicReference<>(OrderMarket.NONE);
        SellFeature sell = new SellFeature(this.services, this.problems, this.combatTags, orderMarket::get);
        SpawnersFeature spawners = new SpawnersFeature(this.services, this.problems, sell.worth(), teams.lookup(), staff.vanish(), afk.status(),
            this.combatTags);
        CratesFeature crates = new CratesFeature(this.services, this.problems, sell.worth(), spawners.items(), staff.vanish(),
            this.combatTags, afk.status());
        OrdersFeature orders = new OrdersFeature(this.services, this.problems, this.combatTags, sell.worth(),
            () -> sell.worth().current().highestMultiplier(), spawners.items(), IgnoreLookup.NONE, staff.vanish());
        orderMarket.set(orders.market());
        CombatFeature combat = new CombatFeature(this.services, this.problems, this.combatTags, stats.recorder(), teams.lookup(),
            FriendLookup.NONE, staff.vanish(), spawn.area());
        features.add(economy);
        features.add(auction);
        features.add(teams);
        features.add(hub);
        features.add(staff);
        features.add(stats);
        features.add(sell);
        features.add(spawners);
        features.add(crates);
        features.add(orders);
        ShopFeature shop = new ShopFeature(this.services, this.problems, sell.worth(), sell.link(), spawners.items(), this.combatTags);
        sell.shop(shop.offers());
        features.add(shop);
        features.add(spawn);
        features.add(new HomesFeature(this.services, this.problems, spawn.area()));
        features.add(new RtpFeature(this.services, this.problems, spawn.area(), spawn.borders()));
        features.add(new TpaFeature(this.services, this.problems, staff.vanish(), afk.status(), FriendLookup.NONE, IgnoreLookup.NONE));
        features.add(new ExtrasFeature(this.services, this.problems, staff.vanish()));
        features.add(new DisplaysFeature(this.services, this.problems));
        features.add(combat);
        features.add(new BountiesFeature(this.services, this.problems));
        features.add(afk);
        features.add(new ShardsFeature(this.services, this.problems, afk.zone(), crates.keys()));
        features.add(admin);
        return features;
    }
}
