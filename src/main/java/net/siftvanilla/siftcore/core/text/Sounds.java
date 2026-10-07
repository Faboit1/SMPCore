package net.siftvanilla.siftcore.core.text;

import java.util.EnumMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;

/** Maps {@link Feedback} to quiet UI sounds, played only to the player concerned. */
public final class Sounds {

    private volatile Map<Feedback, Sound> sounds = new EnumMap<>(Feedback.class);

    public void load(Map<Feedback, Sound> sounds) {
        Map<Feedback, Sound> copy = new EnumMap<>(Feedback.class);
        copy.putAll(sounds);
        this.sounds = copy;
    }

    public void play(Audience audience, Feedback feedback) {
        if (feedback == Feedback.NONE) {
            return;
        }
        Sound sound = this.sounds.get(feedback);
        if (sound != null) {
            audience.playSound(sound, Sound.Emitter.self());
        }
    }
}
