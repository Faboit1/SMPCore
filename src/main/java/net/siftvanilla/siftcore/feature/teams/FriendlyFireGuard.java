package net.siftvanilla.siftcore.feature.teams;

import java.time.Duration;
import java.util.UUID;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Stops members of a team with friendly fire off from hurting each other: melee, projectiles, explosions they lit,
 * their tamed pets, fire, and splash or lingering potions with harmful effects. Runs early (low priority) so combat
 * tagging and kill tracking, which skip cancelled events, never see the blocked hit. A player can always hurt
 * themselves.
 * <p>
 * Damage events run on the victim's region thread; only thread-safe state is read (team snapshots, UUIDs) plus the
 * damaging entity itself, which is in that region.
 */
final class FriendlyFireGuard implements Listener {

    private static final Duration HINT_INTERVAL = Duration.ofSeconds(3);

    private final TeamRegistry registry;
    private final Setting<TeamsSettings> settings;
    private final Messenger messenger;
    private final Cooldowns cooldowns;

    FriendlyFireGuard(TeamRegistry registry, Setting<TeamsSettings> settings, Messenger messenger, Cooldowns cooldowns) {
        this.registry = registry;
        this.settings = settings;
        this.messenger = messenger;
        this.cooldowns = cooldowns;
    }

    /** Whether {@code attacker} may not hurt {@code victim} because they are teammates with friendly fire off. */
    boolean protects(UUID attacker, UUID victim) {
        if (attacker == null || victim == null || attacker.equals(victim) || !this.settings.get().protectMembers()) {
            return false;
        }
        Team team = this.registry.of(attacker).orElse(null);
        return team != null && !team.friendlyFire() && team.isMember(victim);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        // The causing entity (a shooter, whoever lit the TNT) may be in another region: only its UUID is read, and
        // only when it is a player. Everything else is resolved from the direct damager, which is in this region.
        UUID attacker = event.getDamageSource().getCausingEntity() instanceof Player player ? player.getUniqueId() : null;
        if (attacker == null) {
            attacker = responsible(event.getDamager());
        }
        if (protects(attacker, victim.getUniqueId())) {
            event.setCancelled(true);
            hint(attacker);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && protects(responsible(event.getCombuster()), victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        UUID thrower = source(event.getPotion().getShooter());
        if (thrower == null || !harmful(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && protects(thrower, victim.getUniqueId())) {
                event.setIntensity(affected, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        UUID thrower = source(cloud.getSource());
        if (thrower == null) {
            return;
        }
        PotionType base = cloud.getBasePotionType();
        boolean harmful = (base != null && harmful(base.getPotionEffects()))
            || (cloud.hasCustomEffects() && harmful(cloud.getCustomEffects()));
        if (harmful) {
            event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim && protects(thrower, victim.getUniqueId()));
        }
    }

    private void hint(UUID attacker) {
        if (this.cooldowns.tryUse(attacker, "teams:friendly-fire-hint", HINT_INTERVAL).isZero()) {
            Player player = Bukkit.getPlayer(attacker);
            if (player != null) {
                this.messenger.send(player, TeamsMessages.FRIENDLY_FIRE_BLOCKED);
            }
        }
    }

    /** The player behind a hit: the attacker, a projectile's shooter, whoever lit the TNT, or a tamed pet's owner. */
    private static UUID responsible(Entity entity) {
        return switch (entity) {
            case null -> null;
            case Player player -> player.getUniqueId();
            case Projectile projectile -> source(projectile.getShooter());
            case TNTPrimed tnt -> tnt.getSource() instanceof Player player ? player.getUniqueId() : null;
            case AreaEffectCloud cloud -> source(cloud.getSource());
            case Tameable pet -> pet.isTamed() ? pet.getOwnerUniqueId() : null;
            default -> null;
        };
    }

    private static UUID source(ProjectileSource source) {
        return source instanceof Player player ? player.getUniqueId() : null;
    }

    private static boolean harmful(Iterable<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }
}
