package net.siftvanilla.siftcore.feature.staff;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The staff hierarchy: staff can't ban, mute, kick, warn, freeze or (un)vanish a staff member whose staff weight is
 * the same as or higher than their own, or take items from their inventory. Players who aren't staff (weight 0) can
 * always be acted on. The console and players with {@link StaffNodes#HIERARCHY_OWNER} (the owner) are never
 * refused, and only they can act on a player who has it. Configured under {@code hierarchy} in
 * {@code features/staff.yml}; staff weights come from {@link StaffRanks}.
 */
final class StaffHierarchy {

    /** What the rules say about one action. */
    enum Verdict {
        ALLOWED,
        REFUSED
    }

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final AuditLog audit;
    private final Setting<StaffSettings> settings;
    private final Supplier<StaffRanks> ranks;
    private final Function<UUID, Player> players;
    private final Logger logger;

    /**
     * @param ranks   where ranks come from right now (LuckPerms when it is enabled)
     * @param players online players by UUID ({@code Bukkit::getPlayer})
     */
    StaffHierarchy(Scheduler scheduler, Messenger messenger, AuditLog audit, Setting<StaffSettings> settings, Supplier<StaffRanks> ranks,
                   Function<UUID, Player> players, Logger logger) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.audit = audit;
        this.settings = settings;
        this.ranks = ranks;
        this.players = players;
        this.logger = logger;
    }

    /** The rules, for a player acting on another player. Pure. */
    static Verdict decide(StaffSettings.Hierarchy rules, StaffRanks.Rank actor, StaffRanks.Rank target) {
        if (!rules.enabled() || actor.owner()) {
            return Verdict.ALLOWED;
        }
        if (target.owner()) {
            return Verdict.REFUSED;
        }
        if (target.weight() <= 0) {
            return Verdict.ALLOWED;
        }
        return target.weight() >= actor.weight() ? Verdict.REFUSED : Verdict.ALLOWED;
    }

    /**
     * Runs {@code action} when {@code sender} may act on {@code target}: at once for the console, when the hierarchy
     * is off, and when both ranks are known right away (online players); otherwise on the sender's thread once the
     * target's rank is loaded. When refused, or when the rank can't be found out, the sender is told and nothing runs.
     * Call on the sender's thread.
     *
     * @param what the action, for the audit log of refusals ({@code ban}, {@code freeze}, ...)
     */
    void guard(CommandSender sender, UUID target, String targetName, String what, Runnable action) {
        StaffSettings.Hierarchy rules = this.settings.get().hierarchy();
        if (!rules.enabled() || !(sender instanceof Player actor) || actor.getUniqueId().equals(target)) {
            // The console, a switched-off hierarchy, and acting on yourself (vanish) are never refused.
            action.run();
            return;
        }
        StaffRanks source = this.ranks.get();
        StaffRanks.Rank actorNow = source.online(actor, rules.minWeight());
        if (actorNow != null && actorNow.owner()) {
            action.run();
            return;
        }
        CompletableFuture<StaffRanks.Rank> actorRank = actorNow != null ? CompletableFuture.completedFuture(actorNow)
            : source.any(actor.getUniqueId(), rules.minWeight());
        Player online = this.players.apply(target);
        StaffRanks.Rank targetNow = online == null ? null : source.online(online, rules.minWeight());
        CompletableFuture<StaffRanks.Rank> targetRank = targetNow != null ? CompletableFuture.completedFuture(targetNow)
            : source.any(target, rules.minWeight());
        actorRank.thenCombine(targetRank, (a, t) -> decide(rules, a, t)).whenComplete((verdict, error) -> {
            Runnable next = () -> {
                if (error != null) {
                    this.logger.log(Level.WARNING, "Could not look up the staff rank of " + targetName + " for /" + what + " by "
                        + actor.getName() + "; nothing was done", error);
                    this.messenger.send(actor, StaffMessages.HIERARCHY_UNKNOWN, Arg.text("name", targetName));
                } else if (verdict == Verdict.REFUSED) {
                    this.audit.record(actor.getUniqueId().toString(), "staff.hierarchy.refused", target.toString(), what);
                    this.messenger.send(actor, StaffMessages.HIERARCHY_REFUSED, Arg.text("name", targetName));
                } else {
                    action.run();
                }
            };
            if (this.scheduler.owns(actor)) {
                next.run();
            } else {
                this.scheduler.entity(actor, next, null);
            }
        });
    }

    /**
     * Whether {@code actor} may act on the online {@code target} right now (the inventory view's take and clear
     * buttons). An unknown rank counts as refused. Any thread.
     */
    boolean allowsNow(Player actor, Player target) {
        StaffSettings.Hierarchy rules = this.settings.get().hierarchy();
        if (!rules.enabled() || actor.getUniqueId().equals(target.getUniqueId())) {
            return true;
        }
        StaffRanks source = this.ranks.get();
        StaffRanks.Rank actorRank = source.online(actor, rules.minWeight());
        StaffRanks.Rank targetRank = source.online(target, rules.minWeight());
        if (actorRank == null || targetRank == null) {
            return false;
        }
        return decide(rules, actorRank, targetRank) == Verdict.ALLOWED;
    }
}
