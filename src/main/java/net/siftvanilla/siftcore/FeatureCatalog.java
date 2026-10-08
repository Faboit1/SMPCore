package net.siftvanilla.siftcore;

import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.feature.auction.AuctionFeature;
import net.siftvanilla.siftcore.feature.bounties.BountiesFeature;
import net.siftvanilla.siftcore.feature.combat.CombatFeature;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import net.siftvanilla.siftcore.feature.displays.DisplaysFeature;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import net.siftvanilla.siftcore.feature.extras.ExtrasFeature;
import net.siftvanilla.siftcore.feature.homes.HomesFeature;
import net.siftvanilla.siftcore.feature.hub.HubFeature;
import net.siftvanilla.siftcore.feature.rtp.RtpFeature;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
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
        StatsFeature stats = new StatsFeature(this.services, this.problems, AfkStatus.NONE, economy.economy(), admin);
        TeamsFeature teams = new TeamsFeature(this.services, this.problems, stats.recorder(), staff.mutes(), staff.vanish());
        SellFeature sell = new SellFeature(this.services, this.problems);
        SpawnersFeature spawners = new SpawnersFeature(this.services, this.problems, sell.worth(), teams.lookup(), staff.vanish(), AfkStatus.NONE,
            this.combatTags);
        CratesFeature crates = new CratesFeature(this.services, this.problems, sell.worth(), spawners.items(), staff.vanish(),
            this.combatTags, AfkStatus.NONE);
        SpawnFeature spawn = new SpawnFeature(this.services, this.problems);
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
        features.add(new ShopFeature(this.services, this.problems, sell.worth(), spawners.items()));
        features.add(spawn);
        features.add(new HomesFeature(this.services, this.problems, spawn.area()));
        features.add(new RtpFeature(this.services, this.problems, spawn.area(), spawn.borders()));
        features.add(new TpaFeature(this.services, this.problems, staff.vanish(), AfkStatus.NONE, FriendLookup.NONE, IgnoreLookup.NONE));
        features.add(new ExtrasFeature(this.services, this.problems, staff.vanish()));
        features.add(new DisplaysFeature(this.services, this.problems));
        features.add(combat);
        features.add(new BountiesFeature(this.services, this.problems));
        features.add(admin);
        return features;
    }
}
