package net.siftvanilla.siftcore.integration.vault;

import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * The classic {@code net.milkbowl.vault.economy.Economy} interface most shop and auction plugins use, over the
 * ledger. Whole dollars ({@link #fractionalDigits()} is 0); worlds are ignored (one economy everywhere); banks are not
 * supported. The calling plugin is found on the stack for the ledger history.
 */
@SuppressWarnings("deprecation")
final class LegacyVaultEconomy implements Economy {

    private static final String NO_BANKS = "SiftCore has no bank accounts.";

    private final VaultBridge bridge;
    private final Callers callers;
    private final LongFunction<String> format;
    private volatile boolean enabled = true;

    LegacyVaultEconomy(VaultBridge bridge, Callers callers, LongFunction<String> format) {
        this.bridge = bridge;
        this.callers = callers;
        this.format = format;
    }

    void disable() {
        this.enabled = false;
    }

    private static UUID id(OfflinePlayer player) {
        return player == null ? null : player.getUniqueId();
    }

    private UUID id(String name) {
        return this.bridge.account(name).orElse(null);
    }

    private EconomyResponse response(VaultBridge.Outcome outcome) {
        return new EconomyResponse(outcome.amount(), outcome.balance(),
            outcome.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, outcome.error());
    }

    private EconomyResponse deposit(UUID account, double amount) {
        return response(this.bridge.deposit(this.callers.plugin(), account, VaultMoney.decimal(amount)));
    }

    private EconomyResponse withdraw(UUID account, double amount) {
        return response(this.bridge.withdraw(this.callers.plugin(), account, VaultMoney.decimal(amount)));
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
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        long whole = VaultMoney.credit(Math.abs(amount));
        String text = this.format.apply(whole == VaultMoney.INVALID ? 0 : whole);
        return amount < 0 ? "-" + text : text;
    }

    @Override
    public String currencyNamePlural() {
        return "dollars";
    }

    @Override
    public String currencyNameSingular() {
        return "dollar";
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public boolean hasAccount(String playerName) {
        return this.bridge.hasAccount(id(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return this.bridge.hasAccount(id(player));
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    /** Accounts are implicit: any player can hold money, so creating one always succeeds. */
    @Override
    public boolean createPlayerAccount(String playerName) {
        return id(playerName) != null;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ------------------------------------------------------------------ balances

    @Override
    public double getBalance(String playerName) {
        return this.bridge.balance(id(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return this.bridge.balance(id(player));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return this.bridge.has(id(playerName), VaultMoney.decimal(amount));
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return this.bridge.has(id(player), VaultMoney.decimal(amount));
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ------------------------------------------------------------------ moving money

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdraw(id(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(id(player), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return deposit(id(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(id(player), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ------------------------------------------------------------------ banks (not supported)

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, NO_BANKS);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
