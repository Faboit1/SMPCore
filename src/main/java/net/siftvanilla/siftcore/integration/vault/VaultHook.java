package net.siftvanilla.siftcore.integration.vault;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/**
 * Makes SiftCore's money the server's Vault economy: registers the classic and the modern VaultUnlocked interface at
 * the highest priority, so shop, auction and other plugins pay and charge through the ledger. Only loaded after
 * checking that VaultUnlocked (plugin name {@code Vault}) is enabled; nothing here is touched otherwise.
 */
public final class VaultHook {

    /** The plugin name VaultUnlocked registers under. */
    public static final String PLUGIN = "Vault";

    private final LegacyVaultEconomy legacy;
    private final ModernVaultEconomy modern;

    private VaultHook(LegacyVaultEconomy legacy, ModernVaultEconomy modern) {
        this.legacy = legacy;
        this.modern = modern;
    }

    public static VaultHook register(Plugin plugin, Ledger ledger, PlayerDirectory directory, Supplier<MoneyFormat> money,
                                     Logger logger) {
        VaultBridge.Moves moves = new VaultBridge.Moves() {
            @Override
            public long balance(UUID account) {
                return ledger.balance(account, Currency.MONEY);
            }

            @Override
            public TransactionResult credit(UUID account, long amount, String kind, String actor) {
                return ledger.execute(LedgerTx.builder().actor(actor).source(account, Currency.MONEY, amount, kind, null).build());
            }

            @Override
            public TransactionResult debit(UUID account, long amount, String kind, String actor) {
                return ledger.execute(LedgerTx.builder().actor(actor).sink(account, Currency.MONEY, amount, kind, null).build());
            }
        };
        VaultBridge bridge = new VaultBridge(moves, directory::uuid, uuid -> directory.get(uuid).isPresent());
        Callers callers = new Callers(Set.of(plugin.getName(), PLUGIN));
        LegacyVaultEconomy legacy = new LegacyVaultEconomy(bridge, callers, amount -> money.get().format(amount));
        ModernVaultEconomy modern = new ModernVaultEconomy(bridge, amount -> money.get().formatExact(amount), directory::names);
        ServicesManager services = Bukkit.getServicesManager();
        services.register(net.milkbowl.vault.economy.Economy.class, legacy, plugin, ServicePriority.Highest);
        services.register(net.milkbowl.vault2.economy.Economy.class, modern, plugin, ServicePriority.Highest);
        logger.info("Registered the Vault economy (classic and VaultUnlocked interfaces): plugins now use SiftCore money.");
        return new VaultHook(legacy, modern);
    }

    /** Whether SiftCore is the economy other plugins get from Vault (another plugin could register above it). */
    public boolean active() {
        var legacy = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        var modern = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault2.economy.Economy.class);
        return legacy != null && legacy.getProvider() == this.legacy && modern != null && modern.getProvider() == this.modern;
    }

    public void unregister() {
        this.legacy.disable();
        this.modern.disable();
        Bukkit.getServicesManager().unregister(net.milkbowl.vault.economy.Economy.class, this.legacy);
        Bukkit.getServicesManager().unregister(net.milkbowl.vault2.economy.Economy.class, this.modern);
    }
}
