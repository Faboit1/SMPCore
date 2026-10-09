package net.siftvanilla.siftcore.feature.integrations;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.integration.Ranks;
import org.bukkit.entity.Player;

/**
 * The {@link Ranks} other features receive at construction. It answers {@link Ranks#NONE} until LuckPerms is
 * connected in {@code enable()} (and again after a reload turns it off), so consumers keep one stable reference.
 */
final class SwitchableRanks implements Ranks {

    private volatile Ranks delegate = Ranks.NONE;

    void use(Ranks ranks) {
        this.delegate = ranks == null ? Ranks.NONE : ranks;
    }

    boolean connected() {
        return this.delegate != Ranks.NONE;
    }

    @Override
    public String label(UUID player) {
        return this.delegate.label(player);
    }

    @Override
    public Component component(Player player) {
        return this.delegate.component(player);
    }

    @Override
    public String group(UUID player) {
        return this.delegate.group(player);
    }
}
