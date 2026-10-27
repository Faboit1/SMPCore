package net.siftvanilla.siftcore.api;

import net.siftvanilla.siftcore.api.economy.EconomyApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * SiftCore's public API for other plugins. Registered in Bukkit's {@code ServicesManager} while SiftCore is enabled:
 * <pre>{@code
 * SiftCoreApi siftcore = SiftCoreApi.get();
 * long money = siftcore.economy().balance(player.getUniqueId(), Currency.MONEY);
 * }</pre>
 * Everything here is thread-safe and non-blocking. Depend on SiftCore ({@code depend} in plugin.yml, or a
 * paper-plugin.yml server dependency with {@code load: BEFORE}) so it is enabled first. See {@code docs/api.md} for
 * the events SiftCore fires.
 */
public interface SiftCoreApi {

    /** The running API; throws when SiftCore is not enabled. */
    static SiftCoreApi get() {
        RegisteredServiceProvider<SiftCoreApi> registration = Bukkit.getServicesManager().getRegistration(SiftCoreApi.class);
        if (registration == null) {
            throw new IllegalStateException("SiftCore is not enabled");
        }
        return registration.getProvider();
    }

    /** SiftCore's version, e.g. {@code 1.0.0}. */
    String version();

    /** Money and shards: balances, deposits, withdrawals, transfers, leaderboards and history. */
    EconomyApi economy();

    /** Who is in combat (read-only). */
    CombatView combat();

    /** SiftCore's placeholders, the same values PlaceholderAPI shows as {@code %siftcore_<name>%} (read-only). */
    PlaceholderView placeholders();

    /** Players' rank labels and groups (read-only; from LuckPerms when it is installed). */
    RankView ranks();

    /**
     * Players' settings: every setting and group, each player's values, and changing or resetting them. Also in
     * Bukkit's {@code ServicesManager} under {@link SettingsView} while SiftCore's settings feature is enabled.
     *
     * @throws IllegalStateException when the settings feature is not enabled
     */
    default SettingsView settings() {
        RegisteredServiceProvider<SettingsView> registration = Bukkit.getServicesManager().getRegistration(SettingsView.class);
        if (registration == null) {
            throw new IllegalStateException("SiftCore's settings are not enabled");
        }
        return registration.getProvider();
    }
}
