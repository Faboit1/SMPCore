package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.AuctionListEvent;
import net.siftvanilla.siftcore.api.event.AuctionPurchaseEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What players and staff do on the auction house: list, buy, take down, remove, expire. Player-facing methods run
 * on the player's thread; they re-validate everything at execution time, fire the public events, run the engine's
 * transactions and hand items over only after the transaction is committed.
 */
final class AuctionService {

    static final String PERMISSION_USE = "siftcore.command.ah";
    static final String PERMISSION_SELL = "siftcore.auction.sell";
    static final String PERMISSION_ADMIN = "siftcore.admin.auction";
    static final String PERMISSION_CLAIMS = "siftcore.command.claims";
    static final String SLOTS_PREFIX = "siftcore.auction.listings";

    /** The result of preparing a sale: a draft to confirm or a problem to show. */
    sealed interface Preparation permits SaleDraft, Problem {
    }

    /** A validation problem: the message to show, with its arguments. */
    record Problem(MessageKey key, Arg... args) implements Preparation {
    }

    /**
     * A sale the player is about to confirm: the snapshot of the stack in {@code slot} and the part of it to list.
     */
    record SaleDraft(int slot, ItemStack snapshot, int amount, long price, int slotLimit) implements Preparation {
        ItemStack listed() {
            ItemStack item = this.snapshot.clone();
            item.setAmount(this.amount);
            return item;
        }
    }

    private final Services services;
    private final Setting<AuctionSettings> settings;
    private final AuctionEngine<ItemStack> engine;
    private final AuctionItems items;
    private final ClaimBox claims;
    private final CombatStatus combat;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private volatile long lastSweep;

    AuctionService(Services services, Setting<AuctionSettings> settings, AuctionEngine<ItemStack> engine, AuctionItems items,
                   ClaimBox claims, CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.engine = engine;
        this.items = items;
        this.claims = claims;
        this.combat = combat;
    }

    AuctionEngine<ItemStack> engine() {
        return this.engine;
    }

    ClaimBox claims() {
        return this.claims;
    }

    AuctionItems items() {
        return this.items;
    }

    AuctionSettings settings() {
        return this.settings.get();
    }

    long lastSweep() {
        return this.lastSweep;
    }

    private void send(CommandSender sender, Problem problem) {
        this.services.messenger().send(sender, problem.key(), problem.args());
    }

    /** Money with every digit, in the money colour (prices must be exact). */
    Arg price(String name, long amount) {
        return Arg.component(name, Component.text(this.services.money().get().formatExact(amount),
            this.services.lang().style().palette().money()));
    }

    String name(UUID uuid) {
        return this.services.directory().name(uuid);
    }

    /** Null when the player may use the auction house right now, otherwise why not. */
    Problem blocked(Player player) {
        if (this.settings.get().blockInCombat() && this.combat.tagged(player.getUniqueId())) {
            return new Problem(AuctionMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(player.getUniqueId())));
        }
        return null;
    }

    /** Checks the auction house is usable, telling the player when it is not. */
    boolean usable(Player player) {
        Problem problem = blocked(player);
        if (problem != null) {
            send(player, problem);
            return false;
        }
        return true;
    }

    int slotLimit(Player player) {
        return Limits.highest(player, SLOTS_PREFIX, this.settings.get().defaultSlots());
    }

    // ------------------------------------------------------------------ selling

    /**
     * Validates listing {@code amount} (0 = the whole stack) of the held item for {@code price}. Returns the draft to
     * confirm, or the problem.
     */
    Preparation prepareSale(Player player, long price, int amount) {
        Problem blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack held = inventory.getItem(slot);
        if (held == null || held.isEmpty()) {
            return new Problem(AuctionMessages.SELL_NOTHING);
        }
        int listed = amount <= 0 ? held.getAmount() : amount;
        if (listed > held.getAmount()) {
            return new Problem(AuctionMessages.SELL_AMOUNT_RANGE, Arg.number("max", held.getAmount()));
        }
        int limit = slotLimit(player);
        Problem problem = validate(player, held, listed, price, limit);
        if (problem != null) {
            return problem;
        }
        return new SaleDraft(slot, held.clone(), listed, price, limit);
    }

    private Problem validate(Player player, ItemStack item, int amount, long price, int limit) {
        AuctionSettings s = this.settings.get();
        if (!player.hasPermission(PERMISSION_SELL)) {
            return new Problem(CoreMessages.NO_PERMISSION);
        }
        if (!s.allowCreative() && AuctionItems.creative(player)) {
            return new Problem(AuctionMessages.SELL_CREATIVE);
        }
        if (s.blacklist().matches(AuctionItems.typeKey(item))) {
            return new Problem(AuctionMessages.SELL_BLACKLISTED);
        }
        if (!s.allowFilledContainers() && AuctionItems.holdsItems(item)) {
            return new Problem(AuctionMessages.SELL_FILLED_CONTAINER);
        }
        if (s.maxItemBytes() > 0 && AuctionItems.dataSize(item, s.maxItemBytes()) > s.maxItemBytes()) {
            return new Problem(AuctionMessages.SELL_TOO_LARGE);
        }
        if (!s.price().allows(price, amount)) {
            return new Problem(AuctionMessages.SELL_PRICE_RANGE, Arg.number("amount", amount),
                Arg.text("item", AuctionItems.plainName(item)), price("min", s.price().lowest(amount)), price("max", s.price().highest(amount)));
        }
        if (limit <= 0) {
            return new Problem(AuctionMessages.SELL_NO_SLOTS);
        }
        if (this.engine.book().count(player.getUniqueId()) >= limit) {
            return new Problem(AuctionMessages.SELL_SLOTS_FULL, Arg.number("limit", limit));
        }
        return null;
    }

    /**
     * Lists a confirmed draft: re-validates, fires {@link AuctionListEvent}, takes the items out of the inventory
     * and runs the listing transaction. Returns the problem to show, or null when the listing went ahead (the
     * player is told when it is saved). Call on the player's thread.
     */
    Problem confirmSale(Player player, SaleDraft draft) {
        Problem blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack current = stillHeld(inventory, draft);
        if (current == null) {
            return new Problem(AuctionMessages.SELL_ITEM_CHANGED);
        }
        int limit = slotLimit(player);
        Problem problem = validate(player, current, draft.amount(), draft.price(), limit);
        if (problem != null) {
            return problem;
        }
        AuctionSettings s = this.settings.get();
        ItemStack listed = draft.listed();
        if (!new AuctionListEvent(player.getUniqueId(), listed, draft.price(), s.duration()).callEvent()) {
            return new Problem(AuctionMessages.SELL_CANCELLED);
        }
        // Listeners run other plugins' code: look at the slot again right before taking from it.
        current = stillHeld(inventory, draft);
        if (current == null) {
            return new Problem(AuctionMessages.SELL_ITEM_CHANGED);
        }
        // Remove before grant: the items leave the inventory before the listing exists.
        if (current.getAmount() == draft.amount()) {
            inventory.setItem(draft.slot(), null);
        } else {
            current.setAmount(current.getAmount() - draft.amount());
            inventory.setItem(draft.slot(), current);
        }
        // The same order on disk: the player file without the items is written before the listing can be stored, so
        // a crash in between can never leave the items both in the inventory and on the auction house.
        boolean save = this.services.core().get().savePlayerAfterTrade();
        if (save) {
            player.saveData();
        }
        AuctionEngine.Created<ItemStack> created;
        try {
            created = this.engine.create(new AuctionEngine.Draft<>(player.getUniqueId(), listed, AuctionItems.typeKey(listed),
                AuctionItems.searchText(listed), this.items.category(listed), draft.amount(), draft.price(), s.duration()),
                limit, player.getUniqueId().toString());
        } catch (RuntimeException e) {
            this.services.plugin().getLogger().log(Level.SEVERE, "Listing an item for " + player.getName() + " failed", e);
            restore(player, draft.slot(), listed);
            return new Problem(AuctionMessages.SELL_FAILED);
        }
        TransactionResult result = created.result();
        if (!result.success()) {
            restore(player, draft.slot(), listed);
            Refusal refusal = Refusal.from(result.reason());
            return switch (result.status()) {
                case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
                case REJECTED -> refusal == Refusal.SLOTS_FULL
                    ? new Problem(AuctionMessages.SELL_SLOTS_FULL, Arg.number("limit", limit))
                    : new Problem(AuctionMessages.SELL_FAILED);
                default -> new Problem(AuctionMessages.SELL_FAILED);
            };
        }
        Listing<ItemStack> listing = created.listing();
        this.claims.begin();
        created.saved().whenComplete((ignored, error) -> {
            try {
                if (error != null) {
                    this.services.plugin().getLogger().log(Level.WARNING, "Auction listing " + listing.id()
                        + " could not be stored; returning the item", error);
                    this.claims.give(player.getUniqueId(), List.of(listed), AuctionEngine.SOURCE, listing.ref());
                    this.services.messenger().send(player, AuctionMessages.SELL_FAILED);
                    return;
                }
                this.services.messenger().send(player, AuctionMessages.SELL_LISTED, Arg.number("amount", listing.amount()),
                    Arg.text("item", AuctionItems.plainName(listed)), price("price", listing.price()));
                this.services.scheduler().entity(player, () -> {
                    // The listing becomes visible once stored: show it if the seller is looking at the auction house.
                    if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu
                        && (menu instanceof AuctionMenu || menu instanceof MyListingsMenu)) {
                        menu.redraw();
                    }
                }, null);
            } finally {
                this.claims.end();
            }
        });
        return null;
    }

    /** The stack in the draft's slot when it still holds what the player confirmed, otherwise null. */
    private static ItemStack stillHeld(PlayerInventory inventory, SaleDraft draft) {
        ItemStack current = inventory.getItem(draft.slot());
        if (current == null || current.isEmpty() || !current.isSimilar(draft.snapshot()) || current.getAmount() < draft.amount()) {
            return null;
        }
        return current;
    }

    /**
     * Puts items taken for a listing back where they were, or anywhere they fit, or into the claim box, and saves the
     * player file again (it was saved without them).
     */
    private void restore(Player player, int slot, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(slot);
        if (current == null || current.isEmpty()) {
            inventory.setItem(slot, item.clone());
        } else if (current.isSimilar(item) && current.getAmount() + item.getAmount() <= current.getMaxStackSize()) {
            current.setAmount(current.getAmount() + item.getAmount());
            inventory.setItem(slot, current);
        } else {
            List<ItemStack> left = new ArrayList<>(inventory.addItem(item.clone()).values());
            if (!left.isEmpty()) {
                this.claims.store(player.getUniqueId(), left, AuctionEngine.SOURCE, null);
            }
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    // ------------------------------------------------------------------ buying

    /**
     * Buys a listing at the price the player confirmed. Fires {@link AuctionPurchaseEvent}, runs the transaction,
     * and after the commit hands the item over (or leaves it in the claim box) and tells both players.
     * Returns the problem to show, or null when the purchase went through.
     */
    Problem buy(Player buyer, long id, long confirmedPrice) {
        Problem blocked = blocked(buyer);
        if (blocked != null) {
            return blocked;
        }
        Listing<ItemStack> listing = this.engine.book().get(id);
        if (listing == null) {
            return new Problem(AuctionMessages.BUY_GONE);
        }
        // Cheap early answers so listeners only ever see purchases that can happen; the transaction checks all of
        // this again under the economy lock.
        Refusal early = this.engine.book().check(id, new ListingBook.Sale(buyer.getUniqueId(), confirmedPrice, this.engine.now()));
        if (early != null) {
            return refusal(early);
        }
        if (this.services.ledger().balance(buyer.getUniqueId(), Currency.MONEY) < listing.price()) {
            return new Problem(CoreMessages.NOT_ENOUGH_MONEY, price("amount", listing.price()));
        }
        if (!new AuctionPurchaseEvent(id, buyer.getUniqueId(), listing.seller(), listing.item(), listing.price()).callEvent()) {
            return new Problem(AuctionMessages.BUY_CANCELLED);
        }
        int taxRate = this.settings.get().taxBasisPoints();
        TransactionResult result = this.engine.buy(buyer.getUniqueId(), id, confirmedPrice, taxRate, buyer.getUniqueId().toString());
        if (!result.success()) {
            return switch (result.status()) {
                case INSUFFICIENT_FUNDS -> new Problem(CoreMessages.NOT_ENOUGH_MONEY, price("amount", confirmedPrice));
                case BALANCE_LIMIT -> new Problem(AuctionMessages.BUY_SELLER_FULL);
                case CANCELLED -> new Problem(AuctionMessages.BUY_CANCELLED);
                case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
                default -> refusal(Refusal.from(result.reason()));
            };
        }
        long tax = AuctionMath.tax(listing.price(), taxRate);
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.messenger().send(buyer, CoreMessages.ACTION_FAILED);
                return;
            }
            notifySeller(listing, buyer, tax);
            this.services.scheduler().entity(buyer, () -> deliverPurchase(buyer, listing), null);
        });
        return null;
    }

    private void deliverPurchase(Player buyer, Listing<ItemStack> listing) {
        Arg[] args = {Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
            Arg.text("seller", name(listing.seller())), price("price", listing.price())};
        if (!this.settings.get().autoClaim()) {
            this.services.messenger().send(buyer, AuctionMessages.BUY_DONE_CLAIM_BOX, args);
            return;
        }
        this.claims.claimReference(buyer, listing.ref()).whenComplete((outcome, error) -> {
            boolean inInventory = error == null && outcome.complete();
            this.services.messenger().send(buyer, inInventory ? AuctionMessages.BUY_DONE : AuctionMessages.BUY_DONE_CLAIM_BOX, args);
        });
    }

    /** Tells an online seller about the sale in their {@code auction-sales} style (offline sellers see it on join). */
    private void notifySeller(Listing<ItemStack> listing, Player buyer, long tax) {
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller == null) {
            return;
        }
        AlertStyle style = this.services.settings().get(listing.seller(), AuctionFeature.SALE_ALERTS);
        this.services.messenger().alert(seller, style, AuctionMessages.SOLD, Arg.text("buyer", buyer.getName()),
            Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
            price("price", listing.price()), price("earned", listing.price() - tax));
    }

    private Problem refusal(Refusal refusal) {
        if (refusal == null) {
            return new Problem(CoreMessages.ACTION_FAILED);
        }
        return switch (refusal) {
            case GONE -> new Problem(AuctionMessages.BUY_GONE);
            case PENDING -> new Problem(AuctionMessages.BUY_PENDING);
            case OWN_LISTING -> new Problem(AuctionMessages.BUY_OWN);
            case PRICE_CHANGED -> new Problem(AuctionMessages.BUY_PRICE_CHANGED);
            case EXPIRED -> new Problem(AuctionMessages.BUY_EXPIRED);
            case NOT_OWNER -> new Problem(CoreMessages.NO_PERMISSION);
            case NOT_EXPIRED, SLOTS_FULL -> new Problem(CoreMessages.ACTION_FAILED);
        };
    }

    // ------------------------------------------------------------------ taking listings down

    /** Takes down the player's own listing; the item comes back after the commit. Returns the problem, or null. */
    Problem cancel(Player seller, long id) {
        Problem blocked = blocked(seller);
        if (blocked != null) {
            return blocked;
        }
        TransactionResult result = this.engine.cancel(id, seller.getUniqueId(), seller.getUniqueId().toString());
        if (!result.success()) {
            return switch (result.status()) {
                case UNAVAILABLE -> new Problem(CoreMessages.ECONOMY_UNAVAILABLE);
                case REJECTED -> refusal(Refusal.from(result.reason()));
                default -> new Problem(CoreMessages.ACTION_FAILED);
            };
        }
        String ref = Listing.ref(id);
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.messenger().send(seller, CoreMessages.ACTION_FAILED);
                return;
            }
            this.services.scheduler().entity(seller, () -> {
                if (!this.settings.get().autoClaim()) {
                    this.services.messenger().send(seller, AuctionMessages.CANCEL_DONE_CLAIM_BOX);
                    return;
                }
                this.claims.claimReference(seller, ref).whenComplete((outcome, claimError) -> this.services.messenger().send(seller,
                    claimError == null && outcome.complete() ? AuctionMessages.CANCEL_DONE : AuctionMessages.CANCEL_DONE_CLAIM_BOX));
            }, null);
        });
        return null;
    }

    /** Staff removal: the listing is cancelled and the item goes to the seller's claim box. Audited. */
    void remove(CommandSender staff, long id) {
        Listing<ItemStack> listing = this.engine.book().get(id);
        if (listing == null) {
            this.services.messenger().chat(staff, AuctionMessages.ADMIN_NOT_FOUND, Arg.text("id", Long.toString(id)));
            return;
        }
        String actor = staff instanceof Player player ? player.getUniqueId().toString() : "console";
        TransactionResult result = this.engine.cancel(id, null, actor);
        if (!result.success()) {
            this.services.messenger().chat(staff, AuctionMessages.ADMIN_FAILED, Arg.text("id", Long.toString(id)),
                Arg.text("reason", result.reason() == null ? result.status().name().toLowerCase(java.util.Locale.ROOT) : result.reason()));
            return;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.messenger().chat(staff, AuctionMessages.ADMIN_FAILED, Arg.text("id", Long.toString(id)),
                    Arg.text("reason", "storage"));
                return;
            }
            this.services.messenger().chat(staff, AuctionMessages.ADMIN_REMOVED, Arg.text("id", Long.toString(id)),
                Arg.text("name", name(listing.seller())));
            this.services.audit().record(actor, "auction.remove", Long.toString(id), name(listing.seller()) + " "
                + listing.amount() + " " + listing.typeKey() + " for " + listing.price());
        });
    }

    // ------------------------------------------------------------------ expiry

    /**
     * Returns every listing whose time ran out to its seller's claim box (one transaction each) and tells online
     * sellers. Runs on an async thread; overlapping runs are skipped. Completes with the number of listings expired.
     */
    CompletableFuture<Integer> sweep() {
        if (!this.sweeping.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        try {
            this.lastSweep = System.currentTimeMillis();
            List<AuctionEngine.Closed<ItemStack>> closed = this.engine.expireDue("system");
            List<CompletableFuture<Listing<ItemStack>>> stored = new ArrayList<>();
            for (AuctionEngine.Closed<ItemStack> entry : closed) {
                if (entry.result().success()) {
                    stored.add(entry.result().committed().handle((ignored, error) -> error == null ? entry.listing() : null));
                }
            }
            return CompletableFuture.allOf(stored.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
                Map<UUID, List<Listing<ItemStack>>> bySeller = new HashMap<>();
                int count = 0;
                for (CompletableFuture<Listing<ItemStack>> future : stored) {
                    Listing<ItemStack> listing = future.join();
                    if (listing != null) {
                        count++;
                        bySeller.computeIfAbsent(listing.seller(), k -> new ArrayList<>()).add(listing);
                    }
                }
                bySeller.forEach(this::notifyExpired);
                return count;
            });
        } finally {
            this.sweeping.set(false);
        }
    }

    /** Tells an online seller their listings went to the claim box, in their {@code auction-expiry-alerts} style. */
    private void notifyExpired(UUID sellerId, List<Listing<ItemStack>> expired) {
        Player seller = Bukkit.getPlayer(sellerId);
        if (seller == null) {
            return;
        }
        AlertStyle style = this.services.settings().get(sellerId, AuctionFeature.EXPIRY_ALERTS);
        if (expired.size() == 1) {
            Listing<ItemStack> listing = expired.getFirst();
            this.services.messenger().alert(seller, style, AuctionMessages.EXPIRED_ONE, Arg.number("amount", listing.amount()),
                Arg.text("item", AuctionItems.plainName(listing.item())));
        } else {
            this.services.messenger().alert(seller, style, AuctionMessages.EXPIRED_MANY, Arg.number("count", expired.size()));
        }
    }

    /** Time until the next listing expires, for staff info, or null when nothing is listed. */
    Duration nextExpiry() {
        long next = this.engine.book().nextExpiry();
        return next == Long.MAX_VALUE ? null : Duration.ofMillis(Math.max(0, next - this.engine.now()));
    }

    /** Balance of a player (for the purchase dialog). */
    long balance(UUID player) {
        return this.services.ledger().balance(player, Currency.MONEY);
    }
}
