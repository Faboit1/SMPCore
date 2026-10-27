package net.siftvanilla.siftcore.integration.vault;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

/**
 * VaultUnlocked's modern {@code net.milkbowl.vault2.economy.Economy} interface over the ledger, for plugins that ask
 * for it (VaultUnlocked does not bridge it to the classic one). One currency, {@value #CURRENCY}, in whole dollars;
 * worlds are ignored; shared accounts are not supported. Callers name themselves, which the ledger history records.
 */
@SuppressWarnings("deprecation")
final class ModernVaultEconomy implements Economy {

    static final String CURRENCY = "money";

    private final VaultBridge bridge;
    private final LongFunction<String> format;
    private final Supplier<Map<UUID, String>> names;
    private volatile boolean enabled = true;

    ModernVaultEconomy(VaultBridge bridge, LongFunction<String> format, Supplier<Map<UUID, String>> names) {
        this.bridge = bridge;
        this.format = format;
        this.names = names;
    }

    void disable() {
        this.enabled = false;
    }

    private static boolean ours(String currency) {
        return currency != null && CURRENCY.equalsIgnoreCase(currency);
    }

    private static EconomyResponse response(VaultBridge.Outcome outcome) {
        return new EconomyResponse(BigDecimal.valueOf(outcome.amount()), BigDecimal.valueOf(outcome.balance()),
            outcome.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, outcome.error());
    }

    private EconomyResponse unknownCurrency(UUID account) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.valueOf(this.bridge.balance(account)),
            EconomyResponse.ResponseType.FAILURE, "SiftCore only has the currency " + CURRENCY + ".");
    }

    // ------------------------------------------------------------------ about

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    @Override
    public String getName() {
        return "SiftCore";
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return 0;
    }

    @Override
    public String format(BigDecimal amount) {
        if (amount == null) {
            return this.format.apply(0);
        }
        long whole = VaultMoney.credit(amount.abs());
        String text = this.format.apply(whole == VaultMoney.INVALID ? 0 : whole);
        return amount.signum() < 0 ? "-" + text : text;
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return ours(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return CURRENCY;
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return "dollars";
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return "dollar";
    }

    @Override
    public Collection<String> currencies() {
        return List.of(CURRENCY);
    }

    // ------------------------------------------------------------------ accounts

    /** Accounts are implicit (any player can hold money), so creating one always succeeds. */
    @Override
    public boolean createAccount(UUID accountID, String name) {
        return accountID != null;
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        return accountID != null;
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return accountID != null;
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return accountID != null;
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return this.names.get();
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        return Optional.ofNullable(accountID == null ? null : this.names.get().get(accountID));
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        return this.bridge.hasAccount(accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        return hasAccount(accountID);
    }

    /** Account names are player names, which only Mojang changes. */
    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return false;
    }

    @Override
    public boolean renameAccount(String pluginName, UUID accountID, String name) {
        return false;
    }

    /** Player accounts are never deleted: their history is part of the ledger. */
    @Override
    public boolean deleteAccount(String pluginName, UUID accountID) {
        return false;
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return ours(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return ours(currency);
    }

    // ------------------------------------------------------------------ balances

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        return BigDecimal.valueOf(this.bridge.balance(accountID));
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return getBalance(pluginName, accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return ours(currency) ? getBalance(pluginName, accountID) : BigDecimal.ZERO;
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        return this.bridge.has(accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return has(pluginName, accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return ours(currency) && has(pluginName, accountID, amount);
    }

    // ------------------------------------------------------------------ moving money

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return response(this.bridge.withdraw(pluginName, accountID, amount));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return ours(currency) ? withdraw(pluginName, accountID, amount) : unknownCurrency(accountID);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return response(this.bridge.deposit(pluginName, accountID, amount));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return deposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return ours(currency) ? deposit(pluginName, accountID, amount) : unknownCurrency(accountID);
    }

    // ------------------------------------------------------------------ shared accounts (not supported)

    @Override
    public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
        return false;
    }

    @Override
    public List<String> accountsAccessTo(String pluginName, UUID accountID, AccountPermission... permissions) {
        return List.of();
    }

    @Override
    public List<UUID> accountsWithAccessTo(String pluginName, UUID accountID, AccountPermission... permissions) {
        return List.of();
    }

    /** Every account belongs to its player alone. */
    @Override
    public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
        return accountID != null && accountID.equals(uuid);
    }

    @Override
    public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return isAccountOwner(pluginName, accountID, uuid);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) {
        return isAccountOwner(pluginName, accountID, uuid);
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) {
        return false;
    }
}
