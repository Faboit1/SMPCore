package net.siftvanilla.siftcore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.feature.afk.AfkFeature;
import net.siftvanilla.siftcore.feature.auction.AuctionFeature;
import net.siftvanilla.siftcore.feature.boosters.BoostersFeature;
import net.siftvanilla.siftcore.feature.bounties.BountiesFeature;
import net.siftvanilla.siftcore.feature.chat.ChatFeature;
import net.siftvanilla.siftcore.feature.combat.CombatFeature;
import net.siftvanilla.siftcore.feature.cosmetics.CosmeticsFeature;
import net.siftvanilla.siftcore.feature.crates.CratesFeature;
import net.siftvanilla.siftcore.feature.displays.DisplaysFeature;
import net.siftvanilla.siftcore.feature.economy.EconomyFeature;
import net.siftvanilla.siftcore.feature.extras.ExtrasFeature;
import net.siftvanilla.siftcore.feature.friends.FriendsFeature;
import net.siftvanilla.siftcore.feature.homes.HomesFeature;
import net.siftvanilla.siftcore.feature.hub.HubFeature;
import net.siftvanilla.siftcore.feature.integrations.IntegrationsFeature;
import net.siftvanilla.siftcore.feature.kits.KitsFeature;
import net.siftvanilla.siftcore.feature.orders.OrdersFeature;
import net.siftvanilla.siftcore.feature.rtp.RtpFeature;
import net.siftvanilla.siftcore.feature.scoreboard.ScoreboardFeature;
import net.siftvanilla.siftcore.feature.sell.SellFeature;
import net.siftvanilla.siftcore.feature.settings.SettingsFeature;
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
        // The auction house warns about listings far below the server's sell price, but selling is built later:
        // a late-bound worth table breaks the cycle.
        AtomicReference<WorthLookup> worth = new AtomicReference<>(WorthLookup.NONE);
        AuctionFeature auction = new AuctionFeature(this.services, this.problems, this.combatTags, worth::get);
        HubFeature hub = new HubFeature(this.services, this.problems);
        StaffFeature staff = new StaffFeature(this.services, this.problems);
        // A frozen player can't teleport or use any menu: the shared teleports and the dialog router ask the staff feature.
        this.services.teleports().freezes(staff.freezes());
        this.services.dialogs().freezes(staff.freezes());
        // Player-name arguments never offer or find vanished staff for players who can't see them.
        this.services.commands().vanish(staff.vanish());
        SpawnFeature spawn = new SpawnFeature(this.services, this.problems, this.combatTags);
        AfkFeature afk = new AfkFeature(this.services, this.problems, this.combatTags, spawn.area(), staff.vanish());
        StatsFeature stats = new StatsFeature(this.services, this.problems, afk.status(), economy.economy(), admin, staff.vanish());
        TeamsFeature teams = new TeamsFeature(this.services, this.problems, stats.recorder(), staff.mutes(), staff.vanish(), spawn.area());
        // Boosters raise sell prices and arrive as store deliveries, so they come before both.
        BoostersFeature boosters = new BoostersFeature(this.services, this.problems, admin, ScoreboardFeature.DISPLAY);
        // Ranks are needed by chat and friends, store deliveries need crate keys, and crates are built later:
        // late-bound keys break the cycle.
        AtomicReference<CrateKeys> crateKeys = new AtomicReference<>(CrateKeys.NONE);
        IntegrationsFeature integrations = new IntegrationsFeature(this.services, this.problems, admin, this.combatTags,
            CrateKeys.late(crateKeys::get), economy.economy(), boosters.boosters());
        // Chat shows nicknames, tags and chat colours, while cosmetics checks nicknames with chat's word filter:
        // late-bound cosmetics break the cycle.
        AtomicReference<Cosmetics> cosmeticsLink = new AtomicReference<>(Cosmetics.NONE);
        ChatFeature chat = new ChatFeature(this.services, this.problems, integrations.ranks(), teams.lookup(), stats.recorder(),
            staff.mutes(), staff.vanish(), afk.status(), Cosmetics.late(cosmeticsLink::get));
        // Payment notices and team invites respect ignore lists, but economy and teams are built before chat.
        economy.ignores(chat.ignores());
        teams.ignores(chat.ignores());
        CosmeticsFeature cosmetics = new CosmeticsFeature(this.services, this.problems, integrations.ranks(), chat.textChecks(),
            spawn.area(), this.combatTags, ChatFeature.SETTINGS, ScoreboardFeature.DISPLAY);
        cosmeticsLink.set(cosmetics.cosmetics());
        // Staff fake join and leave lines (vanish) copy the cosmetic rank lines and nicknames.
        staff.cosmetics(cosmetics.cosmetics());
        FriendsFeature friends = new FriendsFeature(this.services, this.problems, admin, this.combatTags, chat.ignores(),
            staff.vanish(), afk.status(), teams.lookup(), integrations.ranks(), staff.mutes());
        // Selling routes items into buy orders, but orders are built after sell (they price with sell.worth()):
        // a late-bound market breaks the cycle.
        AtomicReference<OrderMarket> orderMarket = new AtomicReference<>(OrderMarket.NONE);
        SellFeature sell = new SellFeature(this.services, this.problems, this.combatTags, orderMarket::get, boosters.boosters());
        worth.set(sell.worth());
        SpawnersFeature spawners = new SpawnersFeature(this.services, this.problems, sell.worth(), teams.lookup(), staff.vanish(), afk.status(),
            this.combatTags);
        CratesFeature crates = new CratesFeature(this.services, this.problems, sell.worth(), spawners.items(), staff.vanish(),
            this.combatTags, afk.status());
        crateKeys.set(crates.keys());
        OrdersFeature orders = new OrdersFeature(this.services, this.problems, this.combatTags, sell.worth(),
            () -> sell.worth().current().highestMultiplier(), spawners.items(), chat.ignores(), staff.vanish());
        orderMarket.set(orders.market());
        CombatFeature combat = new CombatFeature(this.services, this.problems, this.combatTags, stats.recorder(), teams.lookup(),
            friends.lookup(), staff.vanish(), spawn.area(), cosmetics.cosmetics());
        // Who-can settings of every feature ask how players are related through services.relations().
        this.services.relations().bind(friends.lookup(), teams.lookup(), chat.ignores());
        features.add(economy);
        features.add(auction);
        features.add(teams);
        features.add(friends);
        features.add(hub);
        features.add(staff);
        features.add(chat);
        features.add(cosmetics);
        features.add(new SettingsFeature(this.services, this.problems, admin));
        features.add(stats);
        features.add(boosters);
        features.add(sell);
        features.add(spawners);
        features.add(crates);
        features.add(new KitsFeature(this.services, this.problems, this.combatTags, crates.keys(), sell.worth()));
        features.add(orders);
        ShopFeature shop = new ShopFeature(this.services, this.problems, sell.worth(), sell.link(), spawners.items(), this.combatTags);
        sell.shop(shop.offers());
        features.add(shop);
        features.add(spawn);
        HomesFeature homes = new HomesFeature(this.services, this.problems, spawn.area(), this.combatTags);
        // Team homes follow /sethome's disabled worlds too, but homes are built after teams.
        teams.homeWorlds(homes.disabledWorlds());
        features.add(homes);
        features.add(new RtpFeature(this.services, this.problems, spawn.area(), spawn.borders()));
        features.add(new TpaFeature(this.services, this.problems, staff.vanish(), afk.status(), friends.lookup(), chat.ignores(),
            this.combatTags));
        features.add(new ExtrasFeature(this.services, this.problems, staff.vanish(), cosmetics.cosmetics()));
        features.add(new DisplaysFeature(this.services, this.problems));
        features.add(new ScoreboardFeature(this.services, this.problems, stats.recorder(), teams.lookup(), afk.status(), integrations.ranks(),
            this.combatTags, staff.vanish()));
        features.add(combat);
        features.add(new BountiesFeature(this.services, this.problems));
        features.add(afk);
        features.add(new ShardsFeature(this.services, this.problems, afk.zone(), crates.keys(), this.combatTags));
        features.add(integrations);
        features.add(admin);
        return features;
    }
}
