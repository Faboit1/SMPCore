package net.siftvanilla.siftcore.feature.settings;

import java.util.UUID;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import org.bukkit.entity.Player;

/**
 * Whose settings a page or command shows: their id and name, their permissions and their values. Pages are built from
 * a viewer so the self-test can build every page for an operator who is not online.
 */
interface Viewer {

    UUID id();

    String name();

    /** Whether they have a permission. */
    boolean has(String permission);

    /** Their effective value of a setting (their permissions applied). */
    <T> T value(PlayerSetting<T> setting);

    /** An online player. */
    static Viewer of(PlayerSettings settings, Player player) {
        return new Viewer() {
            @Override
            public UUID id() {
                return player.getUniqueId();
            }

            @Override
            public String name() {
                return player.getName();
            }

            @Override
            public boolean has(String permission) {
                return player.hasPermission(permission);
            }

            @Override
            public <T> T value(PlayerSetting<T> setting) {
                return settings.get(player, setting);
            }
        };
    }

    /** Someone with every permission who reads the stored or default values (the self-test). */
    static Viewer everything(PlayerSettings settings, UUID id, String name) {
        return new Viewer() {
            @Override
            public UUID id() {
                return id;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean has(String permission) {
                return true;
            }

            @Override
            public <T> T value(PlayerSetting<T> setting) {
                return settings.get(id, setting);
            }
        };
    }
}
