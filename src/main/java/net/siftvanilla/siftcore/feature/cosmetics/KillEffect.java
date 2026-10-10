package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * The kill effects a player can pick (Tycoon). Each plays particles and a sound where the victim died, for the
 * players nearby who want to see kill effects. Purely visual: nothing is damaged, set on fire or dropped, and the
 * lightning strike is a visual-only bolt. Particles and sounds go to an explicit list of receivers, and everything
 * runs on the thread that owns the location.
 */
enum KillEffect {

    HEARTS,
    FLAMES,
    SOULS,
    TOTEM,
    LIGHTNING,
    NOTES,
    ENDER;

    /** The id in configs, permissions and commands. */
    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The effect with this id, or null. */
    static KillEffect byId(String id) {
        if (id == null) {
            return null;
        }
        for (KillEffect effect : values()) {
            if (effect.id().equalsIgnoreCase(id.strip())) {
                return effect;
            }
        }
        return null;
    }

    /**
     * Plays the effect at {@code at} (the victim's feet) for {@code receivers}. {@code bolt} allows the lightning
     * effect's visual bolt, which every client tracking it sees ({@link BoltRule}); without it the strike is shown with
     * particles and its sound only to the receivers. Call on the thread that owns {@code at}.
     */
    void play(Location at, Collection<Player> receivers, boolean bolt) {
        if (receivers.isEmpty()) {
            return;
        }
        List<Player> to = List.copyOf(receivers);
        Location body = at.clone().add(0, 1.0, 0);
        switch (this) {
            case HEARTS -> {
                particles(Particle.HEART, body, to, 14, 0.6, 0.5, 0.6, 0.0);
                sound(body, to, "block.amethyst_block.chime", 1.0f, 1.2f);
                sound(body, to, "entity.player.levelup", 0.4f, 1.6f);
            }
            case FLAMES -> {
                particles(Particle.FLAME, body, to, 45, 0.35, 0.8, 0.35, 0.04);
                particles(Particle.LAVA, body, to, 6, 0.3, 0.3, 0.3, 0.0);
                sound(body, to, "item.firecharge.use", 0.8f, 1.0f);
            }
            case SOULS -> {
                particles(Particle.SOUL, body, to, 18, 0.4, 0.6, 0.4, 0.03);
                particles(Particle.SOUL_FIRE_FLAME, body, to, 30, 0.3, 0.8, 0.3, 0.03);
                sound(body, to, "particle.soul_escape", 1.0f, 0.8f);
                sound(body, to, "block.soul_sand.break", 0.8f, 0.7f);
            }
            case TOTEM -> {
                particles(Particle.TOTEM_OF_UNDYING, body, to, 70, 0.4, 0.6, 0.4, 0.45);
                sound(body, to, "item.totem.use", 0.5f, 1.2f);
            }
            case LIGHTNING -> {
                if (bolt) {
                    at.getWorld().strikeLightningEffect(at);
                } else {
                    sound(body, to, "entity.lightning_bolt.thunder", 0.6f, 1.4f);
                }
                particles(Particle.ELECTRIC_SPARK, body, to, 40, 0.4, 0.9, 0.4, 0.15);
                particles(Particle.FIREWORK, body, to, 12, 0.2, 0.4, 0.2, 0.08);
            }
            case NOTES -> {
                // A note particle with no spread takes its colour from the x offset (0 to 1).
                for (int i = 0; i < 12; i++) {
                    double angle = Math.PI * 2 * i / 12;
                    Location spot = body.clone().add(Math.cos(angle) * 0.8, 0.3 + (i % 3) * 0.25, Math.sin(angle) * 0.8);
                    particles(Particle.NOTE, spot, to, 0, i / 24.0, 0, 0, 1.0);
                }
                sound(body, to, "block.note_block.chime", 1.0f, 1.0f);
                sound(body, to, "block.note_block.bell", 0.8f, 1.5f);
            }
            case ENDER -> {
                particles(Particle.PORTAL, body, to, 90, 0.4, 0.8, 0.4, 0.9);
                particles(Particle.REVERSE_PORTAL, body, to, 30, 0.3, 0.6, 0.3, 0.05);
                sound(body, to, "entity.enderman.teleport", 1.0f, 0.9f);
            }
        }
    }

    private static void particles(Particle particle, Location at, List<Player> to, int count, double dx, double dy, double dz,
                                  double extra) {
        particle.builder().location(at).receivers(to).count(count).offset(dx, dy, dz).extra(extra).spawn();
    }

    private static void sound(Location at, List<Player> to, String key, float volume, float pitch) {
        Sound sound = Sound.sound(Key.key(key), Sound.Source.PLAYER, volume, pitch);
        for (Player player : to) {
            player.playSound(sound, at.getX(), at.getY(), at.getZ());
        }
    }
}
