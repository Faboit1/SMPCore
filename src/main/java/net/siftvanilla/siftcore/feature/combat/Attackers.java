package net.siftvanilla.siftcore.feature.combat;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

/** Finds the player behind a hit and the weapon they used. */
final class Attackers {

    private Attackers() {
    }

    /**
     * The player behind a hit: the attacker of a melee hit, the shooter of a projectile, whoever lit the TNT or set
     * off the crystal (the damage source's causing entity), or, with {@code pets}, the online owner of a tamed
     * animal. Null when no player is behind it.
     */
    static Player of(EntityDamageByEntityEvent event, boolean pets) {
        Entity source = event.getDamageSource().getCausingEntity();
        if (source == null && event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source == null) {
            source = event.getDamager();
        }
        if (source instanceof Player player) {
            return player;
        }
        if (pets && source instanceof Tameable tameable && tameable.isTamed()) {
            UUID owner = tameable.getOwnerUniqueId();
            return owner == null ? null : Bukkit.getPlayer(owner);
        }
        return null;
    }

    /**
     * What the hit was dealt with: the bow or crossbow that fired an arrow, a thrown trident, or the attacker's
     * main hand for a melee hit. Null for an empty hand, pets, explosions and other projectiles. Runs on the
     * victim's thread, which also owns a melee attacker and the projectile.
     */
    static ItemStack weapon(EntityDamageByEntityEvent event, Player attacker) {
        Entity direct = event.getDamager();
        ItemStack item = null;
        if (direct instanceof Trident trident) {
            item = trident.getItemStack();
        } else if (direct instanceof AbstractArrow arrow) {
            item = arrow.getWeapon();
        } else if (direct.equals(attacker)) {
            item = attacker.getInventory().getItemInMainHand();
        }
        return item == null || item.isEmpty() ? null : item.clone();
    }
}
