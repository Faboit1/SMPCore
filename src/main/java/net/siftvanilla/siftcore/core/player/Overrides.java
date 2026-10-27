package net.siftvanilla.siftcore.core.player;

import java.util.Map;
import java.util.Set;

/**
 * The server's settings overrides from {@code features/settings.yml}, as stored strings by setting id. Entries for
 * ids nobody registered (yet) and values a setting can't read are ignored when reading; the settings feature reports
 * them as problems.
 *
 * @param defaults what players who never changed a setting read, instead of the code default
 * @param locked   forced values: players read these whatever they stored, and can't change them
 * @param hidden   settings kept out of the dialog, commands and placeholders: everyone reads their lock, else their
 *                 default (stored values are ignored while hidden, and kept), and nobody can change them
 */
public record Overrides(Map<String, String> defaults, Map<String, String> locked, Set<String> hidden) {

    /** No overrides. */
    public static final Overrides NONE = new Overrides(Map.of(), Map.of(), Set.of());

    public Overrides {
        defaults = Map.copyOf(defaults);
        locked = Map.copyOf(locked);
        hidden = Set.copyOf(hidden);
    }
}
