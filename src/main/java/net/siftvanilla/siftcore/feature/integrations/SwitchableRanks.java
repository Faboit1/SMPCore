package net.siftvanilla.siftcore.feature.integrations;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.integration.Ranks;
import org.bukkit.entity.Player;

/**
 * The {@link Ranks} other features receive at construction. It answers {@link Ranks#NONE} until LuckPerms is
 * connected in {@code enable()} (and again after a reload turns it off), so consumers keep one stable reference.
 * <p>
 * It also applies the {@code show-my-rank} setting ({@link IntegrationsFeature#SHOW_MY_RANK}): for a player who
 * turned it off, the label and the coloured label are empty, so chat lines, chat cards, join lines, friend profiles,
 * the rank placeholders and the public API show no rank. The group stays the real one: it is what code acts on
 * (perks, limits, the scoreboard's rank order), and the scoreboard hides the rank itself. Purely cosmetic.
 */
final class SwitchableRanks implements Ranks {

    private final Predicate<UUID> shown;
    private volatile Ranks delegate = Ranks.NONE;

    /** @param shown whether a player shows their rank (the setting, with their permission); safe on any thread */
    SwitchableRanks(Predicate<UUID> shown) {
        this.shown = Objects.requireNonNull(shown);
    }

    void use(Ranks ranks) {
        this.delegate = ranks == null ? Ranks.NONE : ranks;
    }

    boolean connected() {
        return this.delegate != Ranks.NONE;
    }

    /** Whether the player shows their rank tag (true unless they turned {@code show-my-rank} off). */
    boolean shown(UUID player) {
        return player == null || this.shown.test(player);
    }

    @Override
    public String label(UUID player) {
        return shown(player) ? this.delegate.label(player) : "";
    }

    @Override
    public Component component(Player player) {
        return shown(player.getUniqueId()) ? this.delegate.component(player) : Component.empty();
    }

    @Override
    public String group(UUID player) {
        return this.delegate.group(player);
    }

    /** The group as placeholders show it: {@code default} for a player who hides their rank. */
    String shownGroup(UUID player) {
        return shown(player) ? this.delegate.group(player) : "default";
    }
}
