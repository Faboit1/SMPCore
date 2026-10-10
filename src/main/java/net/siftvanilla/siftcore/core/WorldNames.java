package net.siftvanilla.siftcore.core;

import java.util.List;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * How worlds are named to players (homes, death locations, spawners, team homes): the server's main world is
 * "Overworld", its nether "Nether" and its end "The End" (lang {@code core.worlds}), never the folder names
 * {@code world_nether} or {@code world_the_end}. Any other world shows its own name. Staff and console lines may keep
 * the folder names.
 */
public final class WorldNames {

    private WorldNames() {
    }

    /** The name players read for {@code world}, with the first loaded world as the main one. Any thread. */
    public static String of(Lang lang, String world) {
        List<World> worlds = Bukkit.getWorlds();
        return of(lang, world, worlds.isEmpty() ? null : worlds.getFirst().getName());
    }

    /**
     * The name players read for {@code world}.
     *
     * @param mainWorld the name of the server's main world (the first one loaded), or null when unknown
     */
    public static String of(Lang lang, String world, String mainWorld) {
        if (world == null) {
            return "";
        }
        MessageKey key = null;
        if (mainWorld != null) {
            if (world.equals(mainWorld)) {
                key = CoreMessages.WORLD_OVERWORLD;
            } else if (world.equals(mainWorld + "_nether")) {
                key = CoreMessages.WORLD_NETHER;
            } else if (world.equals(mainWorld + "_the_end")) {
                key = CoreMessages.WORLD_END;
            }
        }
        return key == null ? world : lang.plain(key);
    }
}
