package net.siftvanilla.siftcore.feature.economy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.CoreSettings;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerTx;

/** The public economy API, backed by the ledger. Used by other plugins, Vault and PlaceholderAPI. */
public final class EconomyService implements EconomyApi {

    private final Ledger ledger;
    private final BalanceTop top;
    private final Supplier<CoreSettings> core;

    public EconomyService(Ledger ledger, BalanceTop top, Supplier<CoreSettings> core) {
        this.ledger = ledger;
        this.top = top;
        this.core = core;
    }

    @Override
    public long balance(UUID account, Currency currency) {
        return this.ledger.balance(account, currency);
    }

    @Override
    public boolean has(UUID account, Currency currency, long amount) {
        return amount <= 0 || this.ledger.balance(account, currency) >= amount;
    }

    private static String kind(String kind) {
        String clean = kind == null || kind.isBlank() ? "plugin" : kind.toLowerCase(java.util.Locale.ROOT);
        return clean.length() > 32 ? clean.substring(0, 32) : clean;
    }

    @Override
    public TransactionResult deposit(UUID account, Currency currency, long amount, String kind, String ref) {
        return this.ledger.execute(LedgerTx.builder().actor("plugin").source(account, currency, amount, kind(kind), ref).build());
    }

    @Override
    public TransactionResult withdraw(UUID account, Currency currency, long amount, String kind, String ref) {
        return this.ledger.execute(LedgerTx.builder().actor("plugin").sink(account, currency, amount, kind(kind), ref).build());
    }

    @Override
    public TransactionResult transfer(UUID from, UUID to, Currency currency, long amount, String kind, String ref) {
        return this.ledger.execute(LedgerTx.builder().actor(from).transfer(from, to, currency, amount, kind(kind), ref).build());
    }

    @Override
    public String format(Currency currency, long amount) {
        CoreSettings settings = this.core.get();
        return currency == Currency.MONEY ? settings.money().format(amount) : Lang.number(amount) + settings.shardsSuffix();
    }

    @Override
    public List<TopEntry> top(Currency currency, int limit) {
        return this.top.top(currency, limit);
    }

    @Override
    public CompletableFuture<List<LedgerEntry>> history(UUID account, int limit) {
        return this.ledger.history(account, Math.clamp(limit, 1, 500), 0);
    }

    public BalanceTop leaderboard() {
        return this.top;
    }

    public Ledger ledger() {
        return this.ledger;
    }
}
