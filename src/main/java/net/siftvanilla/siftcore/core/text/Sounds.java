package net.siftvanilla.siftcore.core.text;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.PingSound;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import org.bukkit.entity.Player;

/**
 * Maps {@link Feedback} to quiet UI sounds, played only to the player concerned, and plays personal pings. Every
 * SiftCore sound goes through here, so each player's sound settings apply once for all features: the volume
 * ({@code sound-volume}, 0 mutes everything), the kind switches ({@code sound-notify}, {@code sound-clicks},
 * {@code sound-success}, {@code sound-errors}) and quiet in combat (no pings or chimes while tagged). Thread-safe.
 */
public final class Sounds {

    /**
     * The sound settings of one player.
     *
     * @param volume        0 to 100 percent
     * @param pings         notification pings ({@link Feedback#NOTIFY} and the default ping)
     * @param quietInCombat no pings or chimes while in combat
     */
    public record Prefs(long volume, boolean pings, boolean clicks, boolean success, boolean errors, boolean quietInCombat) {
        /** Everything on at full volume (players without settings, the console). */
        public static final Prefs DEFAULTS = new Prefs(100, true, true, true, true, false);
    }

    private final PlayerSettings settings;
    private final CombatStatus combat;
    private volatile Map<Feedback, Sound> sounds = new EnumMap<>(Feedback.class);
    private volatile Map<PingSound, Sound> pings = new EnumMap<>(PingSound.class);

    /** Sounds without per-player settings (tests). */
    public Sounds() {
        this(null, null);
    }

    public Sounds(PlayerSettings settings, CombatStatus combat) {
        this.settings = settings;
        this.combat = combat;
    }

    public void load(Map<Feedback, Sound> sounds) {
        Map<Feedback, Sound> copy = new EnumMap<>(Feedback.class);
        copy.putAll(sounds);
        this.sounds = copy;
    }

    /** The named ping sounds (bell, pling, chime); {@link PingSound#DEFAULT} uses the notify sound. */
    public void loadPings(Map<PingSound, Sound> pings) {
        Map<PingSound, Sound> copy = new EnumMap<>(PingSound.class);
        copy.putAll(pings);
        this.pings = copy;
    }

    /** Plays a feedback sound, scaled and filtered by the player's sound settings. */
    public void play(Audience audience, Feedback feedback) {
        if (feedback == Feedback.NONE) {
            return;
        }
        Sound sound = this.sounds.get(feedback);
        if (sound == null) {
            return;
        }
        float factor = audience instanceof Player player ? factor(feedback, prefs(player.getUniqueId()), tagged(player.getUniqueId())) : 1f;
        playScaled(audience, sound, factor);
    }

    /**
     * Plays a sound a feature picks itself (a config sound, like the AFK zone's shard sound) as if it were a feedback
     * sound of {@code kind}: scaled by the player's volume, silent when they switched that kind off, and for
     * {@link Feedback#NOTIFY} and {@link Feedback#SUCCESS} silent in combat with quiet in combat on. Null plays nothing.
     */
    public void play(Audience audience, Sound sound, Feedback kind) {
        if (sound == null || kind == Feedback.NONE) {
            return;
        }
        float factor = audience instanceof Player player ? factor(kind, prefs(player.getUniqueId()), tagged(player.getUniqueId())) : 1f;
        playScaled(audience, sound, factor);
    }

    /**
     * Plays a personal ping (a mention, a private message, team chat) in the sound the player chose:
     * {@link PingSound#DEFAULT} is the notify sound and follows "Notification pings"; the named sounds play even with
     * that off; {@link PingSound#OFF} plays nothing. Volume and quiet in combat apply to all.
     */
    public void ping(Audience audience, PingSound choice) {
        Sound sound = choice == PingSound.DEFAULT ? this.sounds.get(Feedback.NOTIFY) : this.pings.get(choice);
        if (sound == null) {
            return;
        }
        float factor = audience instanceof Player player ? pingFactor(choice, prefs(player.getUniqueId()), tagged(player.getUniqueId())) : 1f;
        playScaled(audience, sound, factor);
    }

    private static void playScaled(Audience audience, Sound sound, float factor) {
        if (factor <= 0f) {
            return;
        }
        Sound scaled = factor >= 1f ? sound : Sound.sound(sound.name(), sound.source(), sound.volume() * factor, sound.pitch());
        audience.playSound(scaled, Sound.Emitter.self());
    }

    /** A player's sound settings (defaults without a settings registry). */
    public Prefs prefs(UUID player) {
        PlayerSettings s = this.settings;
        if (s == null) {
            return Prefs.DEFAULTS;
        }
        return new Prefs(s.get(player, SharedSettings.SOUND_VOLUME), s.get(player, SharedSettings.SOUND_NOTIFY),
            s.get(player, SharedSettings.SOUND_CLICKS), s.get(player, SharedSettings.SOUND_SUCCESS),
            s.get(player, SharedSettings.SOUND_ERRORS), s.get(player, SharedSettings.QUIET_IN_COMBAT));
    }

    private boolean tagged(UUID player) {
        return this.combat != null && this.combat.tagged(player);
    }

    /**
     * The volume factor of a feedback sound for a player (0 = silent): their volume, unless the kind is switched off,
     * or it is a ping or chime while they are in combat with quiet in combat on.
     */
    public static float factor(Feedback kind, Prefs prefs, boolean tagged) {
        boolean on = switch (kind) {
            case NONE -> false;
            case NOTIFY -> prefs.pings() && !(prefs.quietInCombat() && tagged);
            case SUCCESS -> prefs.success() && !(prefs.quietInCombat() && tagged);
            case CLICK -> prefs.clicks();
            case ERROR -> prefs.errors();
        };
        return on ? volume(prefs) : 0f;
    }

    /** The volume factor of a ping for a player (0 = silent). */
    public static float pingFactor(PingSound choice, Prefs prefs, boolean tagged) {
        return switch (choice) {
            case OFF -> 0f;
            case DEFAULT -> factor(Feedback.NOTIFY, prefs, tagged);
            case BELL, PLING, CHIME -> prefs.quietInCombat() && tagged ? 0f : volume(prefs);
        };
    }

    private static float volume(Prefs prefs) {
        return Math.clamp(prefs.volume(), 0, 100) / 100f;
    }
}
