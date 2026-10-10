package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.event.PlayerKillCreditEvent;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Kill credit and kill counting. The last player who hit the victim within the combat window gets the kill, even
 * when a fall, lava or the void finished them; without such a hit the game's own killer is used. A credited kill is
 * then checked against the anti-farm rules, announced to listeners with {@link PlayerKillCreditEvent} (bounties are
 * claimed there), reported to the stats and logged, counted or not.
 */
final class KillTracker {

    /** Who gets the kill and the weapon of their last hit; {@code killer} is null for deaths without a player. */
    record Credit(UUID killer, ItemStack weapon) {

        static final Credit NONE = new Credit(null, null);

        boolean pvp() {
            return this.killer != null;
        }
    }

    /**
     * What a death did.
     *
     * @param decision     whether the kill counted, null for a death without a killer
     * @param killerStreak the killer's kill streak after a counted kill, otherwise 0
     * @param endedStreak  the victim's kill streak that this death ended (0 when they had none)
     */
    record Outcome(AntiFarm.Decision decision, int killerStreak, int endedStreak) {

        boolean counted() {
            return this.decision != null && this.decision.counted();
        }
    }

    private final Setting<CombatSettings> settings;
    private final HitLog<ItemStack> hits;
    private final RecentPairs pairs;
    private final KillLog log;
    private final StatsRecorder stats;
    private final TeamLookup teams;
    private final FriendLookup friends;
    private final PlayerDirectory directory;
    private final Participants participants;
    private final Scheduler scheduler;
    private final Logger logger;

    KillTracker(Setting<CombatSettings> settings, HitLog<ItemStack> hits, RecentPairs pairs, KillLog log, StatsRecorder stats,
                TeamLookup teams, FriendLookup friends, PlayerDirectory directory, Participants participants, Scheduler scheduler,
                Logger logger) {
        this.settings = settings;
        this.hits = hits;
        this.pairs = pairs;
        this.log = log;
        this.stats = stats;
        this.teams = teams;
        this.friends = friends;
        this.directory = directory;
        this.participants = participants;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** Who gets credit for this death. Runs on the victim's thread; changes nothing. */
    Credit resolve(Player victim, long now) {
        UUID id = victim.getUniqueId();
        HitLog.Hit<ItemStack> hit = this.hits.within(id, now, this.settings.get().tagDuration().toMillis());
        if (hit != null && !hit.attacker().equals(id)) {
            return new Credit(hit.attacker(), hit.weapon());
        }
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || this.participants.outOfPlay(killer) || this.participants.outOfPlay(victim)) {
            return Credit.NONE;
        }
        ItemStack weapon = null;
        if (this.scheduler.owns(killer)) {
            ItemStack hand = killer.getInventory().getItemInMainHand();
            weapon = hand.isEmpty() ? null : hand.clone();
        }
        return new Credit(killer.getUniqueId(), weapon);
    }

    /**
     * The facts the anti-farm rules decide on. Friends are friends now or within the friends window, so removing a
     * friend just to kill them (and claim the bounty on them) gives no credit.
     */
    AntiFarm.Facts facts(UUID killer, UUID victim) {
        Duration window = this.settings.get().antiFarm().friendsWindow();
        return new AntiFarm.Facts(this.teams.sameTeam(killer, victim), this.friends.recentlyFriends(killer, victim, window),
            this.directory.sameIp(killer, victim), this.pairs.last(killer, victim));
    }

    /**
     * Records a death that happened: decides whether a credited kill counts, fires the credit event, updates the
     * stats and writes the kill log. Runs on the victim's thread after the death is final.
     */
    Outcome died(Player victim, Credit credit, boolean combatLog, long now) {
        UUID victimId = victim.getUniqueId();
        int endedStreak = this.stats.streak(victimId);
        if (!credit.pvp()) {
            this.stats.death(victimId);
            return new Outcome(null, 0, endedStreak);
        }
        UUID killer = credit.killer();
        AntiFarm.Decision decision = AntiFarm.decide(this.settings.get().antiFarm(), facts(killer, victimId), now);
        if (decision.counted() && !new PlayerKillCreditEvent(killer, victim, combatLog).callEvent()) {
            decision = AntiFarm.Decision.denied(AntiFarm.Reason.CANCELLED);
        }
        int killerStreak = 0;
        if (decision.counted()) {
            this.pairs.record(killer, victimId, now);
            this.stats.kill(killer, victimId);
            killerStreak = this.stats.streak(killer);
        } else {
            this.stats.death(victimId);
        }
        AntiFarm.Decision logged = decision;
        this.log.record(killer, victimId, now, decision.counted(), decision.counted() ? null : decision.reason().id())
            .exceptionally(error -> {
                this.logger.log(Level.WARNING, "Could not log the kill of " + victimId + " by " + killer
                    + " (counted=" + logged.counted() + ")", error);
                return null;
            });
        return new Outcome(decision, killerStreak, endedStreak);
    }
}
