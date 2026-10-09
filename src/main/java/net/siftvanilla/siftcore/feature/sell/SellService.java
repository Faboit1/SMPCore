package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.ItemSellEvent;
import net.siftvanilla.siftcore.api.event.SellMasteryLevelEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.Handoffs;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Sells items: {@code /sell hand}, {@code /sell hand all}, {@code /sell all}, category selling and the sell menu.
 * Every method runs on the player's thread and follows remove-before-grant: the items (whole stacks, partial stacks
 * and the sold contents of shulker boxes) leave their slots first, then one ledger transaction pays for them (the
 * server's part, the buy orders' part and the mastery rows together); if it fails everything goes back where it was.
 * <p>
 * Selling everything can ask first (sell-all.confirm): the dialog keeps the draft it showed, and confirming works
 * the sale out again from the live inventory and only sells when nothing changed.
 */
final class SellService {

    /** Receipt hover cards list at most this many item kinds. */
    private static final int RECEIPT_LINES = 12;

    static final Toggle CONFIRM = new Toggle("sell_all_confirm", true, SellMessages.TOGGLE_CONFIRM,
        SellMessages.TOGGLE_CONFIRM_DESCRIPTION, null);
    static final Toggle ORDERS = new Toggle("sell_orders", true, SellMessages.TOGGLE_ORDERS,
        SellMessages.TOGGLE_ORDERS_DESCRIPTION, null);

    /** How a sale attempt ended. */
    enum Outcome {
        SOLD,
        NOTHING,
        ASKED,
        CANCELLED,
        FAILED
    }

    private final Services services;
    private final WorthService worth;
    private final Setting<SellSettings> settings;
    private final ItemHandout handout;
    private final SaleBuilder builder;
    private final OrderBids bids;
    private final MasteryBook mastery;
    private final CombatStatus combat;
    private BiConsumer<Player, SaleRequest> chooseItems = (player, request) -> { };

    SellService(Services services, WorthService worth, Setting<SellSettings> settings, ItemHandout handout,
                SaleBuilder builder, OrderBids bids, MasteryBook mastery, CombatStatus combat) {
        this.services = services;
        this.worth = worth;
        this.settings = settings;
        this.handout = handout;
        this.builder = builder;
        this.bids = bids;
        this.mastery = mastery;
        this.combat = combat;
    }

    /** What the confirmation's "Choose items" button does (opens the sell menu filled with sellable items). */
    void chooseItems(BiConsumer<Player, SaleRequest> chooser) {
        this.chooseItems = chooser;
    }

    SaleBuilder builder() {
        return this.builder;
    }

    // ------------------------------------------------------------------ checks

    /**
     * True (and tells the player) when they can't sell right now: combat, or their mastery totals are still being
     * read after they joined (a sale then would pay too small a bonus and count from the wrong level).
     */
    boolean blocked(Player player) {
        if (this.settings.get().blockInCombat() && this.combat.tagged(player.getUniqueId())) {
            this.services.messenger().send(player, SellMessages.IN_COMBAT,
                Arg.time("time", this.combat.remaining(player.getUniqueId())));
            return true;
        }
        if (this.settings.get().mastery().enabled() && !this.mastery.loaded(player.getUniqueId())) {
            this.services.messenger().send(player, SellMessages.LOADING);
            return true;
        }
        return false;
    }

    /** Whether this player's sales go to buy orders that pay more (orders exist, the toggle is on, they may fill). */
    boolean routing(Player player) {
        OrderMarket market = this.bids.market();
        if (!market.available()) {
            return false;
        }
        return this.services.settings().enabled(player.getUniqueId(), ORDERS) && market.usable(player) == null;
    }

    /** A draft from the preview cache (menus, dialogs). Never moves anything. */
    SaleBuilder.Result preview(Player player, Inventory inventory, SaleRequest request) {
        return this.builder.build(player, inventory, request, this.settings.get(), routing(player), true, Set.of());
    }

    private SaleBuilder.Result fresh(Player player, Inventory inventory, SaleRequest request, boolean routing,
                                     Set<Long> excluded) {
        return this.builder.build(player, inventory, request, this.settings.get(), routing, false, excluded);
    }

    // ------------------------------------------------------------------ requests

    /** {@code /sell hand}: the stack in the main hand, or the sellable contents of a shulker box held there. */
    Outcome sellHand(Player player) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        PlayerInventory inventory = player.getInventory();
        SaleRequest request = SaleRequest.hand();
        SaleBuilder.Result result = fresh(player, inventory, request, routing(player), Set.of());
        if (result.draft() == null) {
            refuse(player, result, inventory.getItemInMainHand());
            return Outcome.NOTHING;
        }
        return execute(player, request, result.draft());
    }

    /** {@code /sell hand all}: every plain stack of the held item's type. */
    Outcome sellHeldType(Player player) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            this.services.messenger().send(player, SellMessages.EMPTY_HAND);
            return Outcome.NOTHING;
        }
        String key = WorthService.key(held.getType());
        if (!this.worth.pristine(held)) {
            if (TradeGuard.marked(held)) {
                this.services.messenger().send(player, SellMessages.TRADED);
            } else {
                this.services.messenger().send(player, SellMessages.HOLD_PLAIN, Arg.text("item", ItemKeys.name(key)));
            }
            return Outcome.NOTHING;
        }
        return sellWithPolicy(player, SaleRequest.type(key), false, null);
    }

    /**
     * Every plain stack of one item (the worth details' "Sell your ..." button, and a right click in the shop, which
     * always asks first). {@code back}, when not null, is where the confirmation's Cancel returns to.
     */
    Outcome sellType(Player player, String key, boolean alwaysAsk, Runnable back) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        return sellWithPolicy(player, SaleRequest.type(key), alwaysAsk, back);
    }

    /** {@code /sell all}. */
    Outcome sellAll(Player player) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        return sellWithPolicy(player, SaleRequest.all(), false, null);
    }

    /** Everything of one sell category ({@code /sell mastery}). */
    Outcome sellCategory(Player player, String category) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        return sellWithPolicy(player, SaleRequest.category(category), false, null);
    }

    /**
     * The mastery details' Sell button: sells what the button showed ({@code shown}). When the inventory no longer
     * makes exactly that sale, nothing is sold and the confirmation shows the new count and total instead. The
     * confirmation closes nothing: its Cancel, and its Sell once done, show {@code back} (the details) again.
     */
    Outcome sellCategory(Player player, String category, SaleDraft shown, Runnable back) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        SaleRequest request = SaleRequest.category(category);
        SaleBuilder.Result result = fresh(player, player.getInventory(), request, routing(player), Set.of());
        if (result.draft() == null) {
            refuse(player, result, null);
            return Outcome.NOTHING;
        }
        SaleDraft draft = result.draft();
        if (!draft.sameAs(shown)) {
            this.services.dialogs().show(player, confirmView(player, request, draft,
                this.services.lang().get(SellMessages.CONFIRM_CHANGED), back, true));
            this.services.messenger().feedback(player, Feedback.ERROR);
            return Outcome.ASKED;
        }
        boolean asking = this.services.settings().enabled(player.getUniqueId(), CONFIRM);
        if (this.settings.get().sellAll().asks(draft.total(), asking)) {
            this.services.dialogs().show(player, confirmView(player, request, draft, null, back, true));
            return Outcome.ASKED;
        }
        return execute(player, request, draft);
    }

    /**
     * The sell menu's Sell button: sells the grid unless the total dropped below what the button showed (orders
     * changed, a rank was lost), in which case nothing is sold and the player sees the new total.
     */
    Outcome sellMenu(Player player, Inventory menu, long shownTotal) {
        if (blocked(player)) {
            return Outcome.NOTHING;
        }
        SaleRequest request = SaleRequest.menu();
        SaleBuilder.Result result = fresh(player, menu, request, routing(player), Set.of());
        if (result.draft() == null) {
            refuse(player, result, null);
            return Outcome.NOTHING;
        }
        long total = result.draft().total();
        if (total < shownTotal) {
            this.services.messenger().send(player, SellMessages.MENU_TOTAL_LOWER, Arg.money("total", total));
            return Outcome.NOTHING;
        }
        return execute(player, request, result.draft());
    }

    /** Sells right away, or shows the confirmation first when {@code sell-all.confirm} asks for it. */
    private Outcome sellWithPolicy(Player player, SaleRequest request, boolean alwaysAsk, Runnable back) {
        PlayerInventory inventory = player.getInventory();
        SaleBuilder.Result result = fresh(player, inventory, request, routing(player), Set.of());
        if (result.draft() == null) {
            refuse(player, result, null);
            return Outcome.NOTHING;
        }
        SaleDraft draft = result.draft();
        boolean asking = this.services.settings().enabled(player.getUniqueId(), CONFIRM);
        if (alwaysAsk || this.settings.get().sellAll().asks(draft.total(), asking)) {
            this.services.dialogs().show(player, confirmView(player, request, draft, null, back));
            return Outcome.ASKED;
        }
        return execute(player, request, draft);
    }

    // ------------------------------------------------------------------ the confirmation

    /** The "Sell everything" dialog for a draft, with an optional error line; Cancel returns to {@code back} if set. */
    View confirmView(Player player, SaleRequest request, SaleDraft draft, Component error, Runnable back) {
        return confirmView(player, request, draft, error, back, false);
    }

    /**
     * {@link #confirmView(Player, SaleRequest, SaleDraft, Component, Runnable)}; with {@code stay} the confirmation
     * never closes anything: Cancel, and Sell once done, show {@code back} in its place (the mastery details, which can
     * sit over a sell menu that closing would close and empty).
     */
    private View confirmView(Player player, SaleRequest request, SaleDraft draft, Component error, Runnable back, boolean stay) {
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>();
        lines.addAll(lang.lines(SellMessages.CONFIRM_BODY, Arg.number("count", draft.count()), Arg.money("total", draft.total())));
        BigDecimal shared = draft.sharedMultiplier();
        if (shared != null && shared.compareTo(BigDecimal.ONE) > 0) {
            lines.addAll(lang.lines(SellMessages.CONFIRM_BONUS, Arg.text("multiplier", Multipliers.format(shared.doubleValue()))));
        } else if (shared == null && draft.topMultiplier().compareTo(BigDecimal.ONE) > 0) {
            lines.addAll(lang.lines(SellMessages.CONFIRM_BONUSES));
        }
        if (!draft.takes().isEmpty()) {
            lines.addAll(lang.lines(SellMessages.CONFIRM_ORDERS, Arg.money("orders", draft.ordersNet()),
                Arg.number("count", draft.orderCount())));
        }
        if (draft.innerCount() > 0) {
            lines.addAll(lang.lines(SellMessages.CONFIRM_INNER, Arg.number("count", draft.innerCount())));
        }
        if (draft.kept() > 0 && request.scope() == SaleRequest.Scope.ALL) {
            lines.addAll(lang.lines(SellMessages.CONFIRM_KEPT, Arg.number("count", draft.kept())));
        }
        List<Body> body = new ArrayList<>();
        body.add(Body.text(Component.join(JoinConfiguration.newlines(), lines)));
        Component title = switch (request.scope()) {
            case TYPE -> lang.get(SellMessages.CONFIRM_TITLE_TYPE, Arg.text("item", ItemKeys.name(request.target())));
            case CATEGORY -> lang.get(SellMessages.CONFIRM_TITLE_CATEGORY,
                Arg.text("category", this.worth.categories().name(request.target())));
            default -> lang.get(SellMessages.CONFIRM_TITLE);
        };
        boolean returns = stay && back != null;
        Button sell = Button.of(lang.get(SellMessages.CONFIRM_SELL,
            Arg.text("total", this.services.money().get().format(draft.total()))),
            s -> onConfirm(s.player(), request, draft, s, back, returns)).width(150);
        Button choose = Button.of(lang.get(SellMessages.CONFIRM_CHOOSE), s -> {
            s.close();
            this.chooseItems.accept(s.player(), request);
        }).width(150);
        // What back shows (the item's details, the shop, the mastery details) replaces this dialog; a chest menu marks
        // itself shown, so nothing closes the sell menu under the dialog.
        Button cancel = Button.of(lang.get(CoreMessages.UI_CANCEL), back == null ? null : s -> back.run())
            .width(Button.DEFAULT_WIDTH + 100);
        View view = new View(View.Kind.LIST, title, body, List.of(), List.of(sell, choose), cancel, 2, true);
        return error == null ? view : view.withError(error, FormValues.EMPTY);
    }

    /** Sell on the confirmation; {@code returns}: show {@code back} when done instead of closing (see {@code stay}). */
    private void onConfirm(Player player, SaleRequest request, SaleDraft shown,
                           Submission s, Runnable back, boolean returns) {
        Runnable done = returns ? back : s::close;
        if (blocked(player)) {
            done.run();
            return;
        }
        SaleBuilder.Result now = fresh(player, player.getInventory(), request, routing(player), Set.of());
        if (now.draft() == null) {
            done.run();
            refuse(player, now, null);
            return;
        }
        if (!now.draft().sameAs(shown)) {
            s.show(confirmView(player, request, now.draft(), this.services.lang().get(SellMessages.CONFIRM_CHANGED), back, returns));
            this.services.messenger().feedback(player, Feedback.ERROR);
            return;
        }
        // Sell first: closing can give back a sell menu's grid that was open under the dialog, which would change the
        // inventory the draft was made from.
        execute(player, request, now.draft());
        done.run();
    }

    // ------------------------------------------------------------------ selling

    /** Fires the sale event once, then sells the draft (with the buy-order retry). Player's thread. */
    Outcome execute(Player player, SaleRequest request, SaleDraft draft) {
        if (!this.services.ledger().available()) {
            this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            return Outcome.FAILED;
        }
        if (!event(player, draft).callEvent()) {
            this.services.messenger().send(player, SellMessages.CANCELLED);
            return Outcome.CANCELLED;
        }
        return attempt(player, request, draft, 0);
    }

    private ItemSellEvent event(Player player, SaleDraft draft) {
        List<ItemSellEvent.OrderFill> fills = new ArrayList<>(draft.takes().size());
        for (OrderMarket.Take take : draft.takes()) {
            fills.add(new ItemSellEvent.OrderFill(take.orderId(), take.units(), take.priceEach()));
        }
        Map<String, Double> categories = new LinkedHashMap<>();
        draft.multipliers().forEach((category, value) -> categories.put(category, value.doubleValue()));
        return new ItemSellEvent(player, draft.source(), draft.items(), draft.serverTotal(), draft.ordersNet(), fills,
            draft.topMultiplier().doubleValue(), categories);
    }

    /**
     * One try at a sale. Round 0 sells the draft; round 1 is the retry with fresh bids after an order refused;
     * round 2 sells to the server only.
     */
    private Outcome attempt(Player player, SaleRequest request, SaleDraft first, int round) {
        SaleDraft draft = approved(player, request, first);
        if (draft == null) {
            return Outcome.NOTHING;
        }
        List<Moved> moved = new ArrayList<>();
        if (!take(draft, moved)) {
            putBack(player, draft.inventory(), moved);
            gridChanged(draft);
            this.services.messenger().send(player, SellMessages.FAILED);
            return Outcome.FAILED;
        }
        // Remove before grant: the items leave the player file (or the sell menu's copy in it) before the money can be
        // stored, so a crash in between can never leave the items with the player and the money too.
        gridChanged(draft);
        saveIfConfigured(player);
        UUID uuid = player.getUniqueId();
        Map<String, Integer> levelsBefore = levels(uuid, draft.credits().keySet());
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(uuid)
            .note(note(draft));
        if (draft.serverTotal() > 0) {
            tx.source(uuid, Currency.MONEY, draft.serverTotal(), "sell", draft.source().name().toLowerCase(Locale.ROOT));
        }
        OrderMarket market = this.bids.market();
        if (!draft.takes().isEmpty()) {
            market.contribute(tx, uuid, draft.takes());
        }
        this.mastery.contribute(tx, uuid, draft.credits());
        TransactionResult result = this.services.ledger().execute(tx.build());
        if (!result.success()) {
            putBack(player, draft.inventory(), moved);
            gridChanged(draft);
            saveIfConfigured(player);
            if (result.status() == TransactionStatus.REJECTED && OrderMarket.Refusal.of(result.reason()) != null && round < 2) {
                SaleBuilder.Result again = round == 0
                    ? fresh(player, draft.inventory(), request, routing(player), Set.of())
                    : fresh(player, draft.inventory(), request, false, Set.of());
                if (again.draft() == null) {
                    refuse(player, again, null);
                    return Outcome.NOTHING;
                }
                Outcome retried = attempt(player, request, again.draft(), round + 1);
                if (round == 1 && retried == Outcome.SOLD) {
                    this.services.messenger().send(player, SellMessages.ORDERS_CHANGED);
                }
                return retried;
            }
            switch (result.status()) {
                case BALANCE_LIMIT -> this.services.messenger().send(player, SellMessages.BALANCE_FULL);
                case UNAVAILABLE -> this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
                case CANCELLED -> this.services.messenger().send(player, SellMessages.CANCELLED);
                default -> this.services.messenger().send(player, SellMessages.FAILED);
            }
            return result.status() == TransactionStatus.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED;
        }
        Map<String, Integer> levelsAfter = levels(uuid, draft.credits().keySet());
        SaleDraft sold = draft;
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                // Storage failed and the money (and mastery) was taken back, so the items go back too: on the seller's
                // thread, or into their claim box when they left (or the server is stopping) before that can run.
                Handoffs.onEntity(this.services.scheduler(), player, () -> {
                    restoreAfterFailure(player, sold, moved);
                    saveIfConfigured(player);
                    this.services.messenger().send(player, SellMessages.FAILED);
                }, () -> this.handout.toClaimBox(uuid, sold.items(), "sell", null));
                return;
            }
            this.services.scheduler().entity(player, () -> {
                if (!sold.takes().isEmpty()) {
                    market.committed(player, sold.takes());
                }
                receipt(player, sold);
                levelUps(player, levelsBefore, levelsAfter);
            }, null);
        });
        return Outcome.SOLD;
    }

    /**
     * Lets the order feature veto fills (its cancellable fill event). Vetoed orders are left out and the sale is
     * worked out again; if a second round is vetoed too, everything goes to the server. Null when nothing is left.
     */
    private SaleDraft approved(Player player, SaleRequest request, SaleDraft draft) {
        if (draft.takes().isEmpty()) {
            return draft;
        }
        OrderMarket market = this.bids.market();
        Set<Long> excluded = new HashSet<>();
        SaleDraft current = draft;
        for (int round = 0; round < 2; round++) {
            List<OrderMarket.Take> accepted = market.approve(player, current.takes());
            if (accepted.equals(current.takes())) {
                return current;
            }
            Set<Long> kept = new HashSet<>();
            for (OrderMarket.Take take : accepted) {
                kept.add(take.orderId());
            }
            for (OrderMarket.Take take : current.takes()) {
                if (!kept.contains(take.orderId())) {
                    excluded.add(take.orderId());
                }
            }
            SaleBuilder.Result again = fresh(player, current.inventory(), request, true, excluded);
            if (again.draft() == null) {
                refuse(player, again, null);
                return null;
            }
            current = again.draft();
            if (current.takes().isEmpty()) {
                return current;
            }
        }
        SaleBuilder.Result serverOnly = fresh(player, current.inventory(), request, false, Set.of());
        if (serverOnly.draft() == null) {
            refuse(player, serverOnly, null);
            return null;
        }
        return serverOnly.draft();
    }

    /** A sale from a sell menu changed its grid: the copy in the player's data follows it before anything saves them. */
    private static void gridChanged(SaleDraft draft) {
        if (draft.inventory().getHolder(false) instanceof SellMenu menu) {
            menu.backup();
        }
    }

    /** What was taken out of one slot, for putting it back. */
    private record Moved(int slot, ItemStack before, ItemStack after, List<ItemStack> taken) {
    }

    /** Takes the draft's items out of their slots; false (with what was taken so far in {@code moved}) on a mismatch. */
    private static boolean take(SaleDraft draft, List<Moved> moved) {
        Inventory inventory = draft.inventory();
        for (SaleDraft.Stack stack : draft.stacks()) {
            ItemStack now = inventory.getItem(stack.slot());
            if (now == null || !now.equals(stack.snapshot())) {
                return false;
            }
            ItemStack rest = stack.remainder();
            inventory.setItem(stack.slot(), rest);
            moved.add(new Moved(stack.slot(), stack.snapshot(), rest, List.of(stack.taken())));
        }
        for (SaleDraft.Container container : draft.containers()) {
            ItemStack now = inventory.getItem(container.slot());
            if (now == null || !now.equals(container.original())) {
                return false;
            }
            inventory.setItem(container.slot(), container.rebuilt());
            moved.add(new Moved(container.slot(), container.original(), container.rebuilt(), container.taken()));
        }
        return true;
    }

    /**
     * Puts taken items back: a slot that still holds what the sale left there gets its original back; otherwise the
     * taken items are handed to the player (inventory first, then the claim box), never dropped.
     */
    private void putBack(Player player, Inventory inventory, List<Moved> moved) {
        List<ItemStack> rest = new ArrayList<>();
        for (Moved move : moved) {
            ItemStack now = inventory.getItem(move.slot());
            boolean untouched = move.after() == null ? now == null || now.isEmpty() : move.after().equals(now);
            if (untouched) {
                inventory.setItem(move.slot(), move.before());
            } else {
                for (ItemStack stack : move.taken()) {
                    rest.add(stack.clone());
                }
            }
        }
        if (!rest.isEmpty()) {
            long claimed = this.handout.give(player, rest, "sell", null);
            if (claimed > 0) {
                this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
            }
        }
    }

    /**
     * After the storage of a sale failed (the money was taken back): the player's own slots get their items back
     * where nothing changed since (a shulker box its original contents), everything else is handed to the player. A
     * sell menu may have been closed and emptied by now, so what was sold from it is always handed to the player.
     */
    private void restoreAfterFailure(Player player, SaleDraft draft, List<Moved> moved) {
        if (draft.source() != ItemSellEvent.Source.MENU) {
            putBack(player, draft.inventory(), moved);
            return;
        }
        List<ItemStack> taken = new ArrayList<>();
        for (Moved move : moved) {
            for (ItemStack stack : move.taken()) {
                taken.add(stack.clone());
            }
        }
        long claimed = this.handout.give(player, taken, "sell", null);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
    }

    private Map<String, Integer> levels(UUID player, Set<String> categories) {
        Mastery rules = this.settings.get().mastery();
        Map<String, Integer> levels = new HashMap<>();
        for (String category : categories) {
            levels.put(category, rules.level(this.mastery.sold(player, category)));
        }
        return levels;
    }

    private void levelUps(Player player, Map<String, Integer> before, Map<String, Integer> after) {
        WorthService.Rates rates = this.worth.rates(player);
        SellCategories categories = this.worth.categories();
        after.forEach((category, level) -> {
            int previous = before.getOrDefault(category, 0);
            if (level > previous) {
                BigDecimal multiplier = rates.multiplier(category);
                this.services.messenger().send(player, SellMessages.LEVEL_UP, Arg.text("category", categories.name(category)),
                    Arg.number("level", level), Arg.text("multiplier", Multipliers.format(multiplier.doubleValue())));
                new SellMasteryLevelEvent(player, category, previous, level, multiplier.doubleValue()).callEvent();
            }
        });
    }

    private String note(SaleDraft draft) {
        List<SalePlan.Line> lines = new ArrayList<>();
        draft.units().forEach((key, amount) -> lines.add(new SalePlan.Line(key, amount, 1)));
        long inner = draft.innerCount();
        return SalePlan.note(lines, inner > 0 ? " (" + inner + " from shulker boxes)" : "");
    }

    private void saveIfConfigured(Player player) {
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    // ------------------------------------------------------------------ messages

    /** Tells the player why a request sold nothing. */
    void refuse(Player player, SaleBuilder.Result result, ItemStack held) {
        String item = result.item() == null ? "" : ItemKeys.name(result.item());
        switch (result.refusal()) {
            case EMPTY_HAND -> this.services.messenger().send(player, SellMessages.EMPTY_HAND);
            case NOT_SELLABLE -> this.services.messenger().send(player, SellMessages.NOT_SELLABLE, Arg.text("item", item));
            case MODIFIED -> this.services.messenger().send(player,
                held != null && TradeGuard.marked(held) ? SellMessages.TRADED : SellMessages.MODIFIED);
            case HOLD_PLAIN -> this.services.messenger().send(player, SellMessages.HOLD_PLAIN, Arg.text("item", item));
            case BOX_NOTHING -> this.services.messenger().send(player, SellMessages.BOX_NOTHING);
            case BUNDLE_NOTHING -> this.services.messenger().send(player, SellMessages.BUNDLE_NOTHING);
            case NOTHING -> this.services.messenger().send(player, result.item() == null ? SellMessages.NOTHING_TO_SELL
                : SellMessages.NOTHING_OF_TYPE, Arg.text("item", item));
            case NOTHING_IN_MENU -> this.services.messenger().send(player, SellMessages.NOTHING_IN_MENU);
            case NOTHING_IN_CATEGORY -> this.services.messenger().send(player, SellMessages.NOTHING_IN_CATEGORY,
                Arg.text("category", this.worth.categories().name(result.item())));
            case TOO_MUCH -> this.services.messenger().send(player, SellMessages.TOO_MUCH);
        }
    }

    private void receipt(Player player, SaleDraft draft) {
        Lang lang = this.services.lang();
        long total = draft.total();
        // The shared sale receipt setting (core, so selling spawner storage follows it too): chat, hotbar or nothing.
        // The hotbar total is an alert in that style: it stays there whatever the feedback channel, and becomes a chat
        // line while the player is in combat with quiet in combat on.
        AlertStyle style = this.services.settings().get(player.getUniqueId(), SharedSettings.SELL_RECEIPTS);
        if (style == AlertStyle.OFF) {
            return;
        }
        if (style != AlertStyle.CHAT) {
            this.services.messenger().alert(player, style, SellMessages.SOLD_ACTION_BAR, Arg.money("total", total));
            return;
        }
        Map<String, Long> units = draft.units();
        long count = draft.count();
        Component items;
        if (units.size() == 1) {
            Map.Entry<String, Long> only = units.entrySet().iterator().next();
            items = lang.get(SellMessages.ITEMS_ONE, Arg.number("amount", only.getValue()), Arg.text("item", ItemKeys.name(only.getKey())));
        } else {
            items = lang.get(SellMessages.ITEMS_MANY, Arg.number("amount", count));
        }
        List<Component> card = new ArrayList<>();
        List<SalePlan.Line> lines = draft.server().lines();
        for (int i = 0; i < Math.min(RECEIPT_LINES, lines.size()); i++) {
            SalePlan.Line line = lines.get(i);
            long value = SaleMath.withMultiplier(line.value(), draft.multipliers().getOrDefault(line.category(), BigDecimal.ONE));
            card.add(lang.get(SellMessages.RECEIPT_LINE, Arg.number("amount", line.amount()),
                Arg.text("item", ItemKeys.name(line.item())), Arg.money("value", value)));
        }
        if (lines.size() > RECEIPT_LINES) {
            card.add(lang.get(SellMessages.RECEIPT_MORE, Arg.number("count", lines.size() - RECEIPT_LINES)));
        }
        BigDecimal shared = draft.sharedMultiplier();
        if (shared != null && shared.compareTo(BigDecimal.ONE) > 0) {
            card.add(lang.get(SellMessages.RECEIPT_BONUS, Arg.text("multiplier", Multipliers.format(shared.doubleValue()))));
        } else if (shared == null) {
            SellCategories categories = this.worth.categories();
            draft.multipliers().forEach((category, value) -> {
                if (value.compareTo(BigDecimal.ONE) > 0) {
                    card.add(lang.get(SellMessages.RECEIPT_CATEGORY_BONUS, Arg.text("category", categories.name(category)),
                        Arg.text("multiplier", Multipliers.format(value.doubleValue()))));
                }
            });
        }
        if (!draft.takes().isEmpty()) {
            int tax = this.bids.market().taxBasisPoints();
            for (OrderMarket.Take take : draft.takes()) {
                UUID owner = draft.owners().get(take.orderId());
                String name = owner == null ? "?" : this.services.directory().name(owner);
                card.add(lang.get(SellMessages.RECEIPT_ORDER, Arg.number("amount", take.units()),
                    Arg.text("item", ItemKeys.name(take.key())), Arg.text("owner", name == null ? "?" : name),
                    Arg.money("value", OrderMarket.net(take, tax))));
            }
            card.add(lang.get(SellMessages.RECEIPT_TAX, Arg.money("tax", draft.ordersTax())));
            if (draft.serverTotal() > 0) {
                card.add(lang.get(SellMessages.RECEIPT_REST, Arg.money("value", draft.serverTotal())));
            }
        }
        if (draft.innerCount() > 0) {
            card.add(lang.get(SellMessages.RECEIPT_BOXES, Arg.number("count", draft.innerCount())));
        }
        items = items.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), card)));
        if (!draft.takes().isEmpty()) {
            this.services.messenger().send(player, SellMessages.SOLD_ORDERS, Arg.component("items", items),
                Arg.money("total", total), Arg.money("orders", draft.ordersNet()));
        } else if (shared != null && shared.compareTo(BigDecimal.ONE) > 0) {
            this.services.messenger().send(player, SellMessages.SOLD_BONUS, Arg.component("items", items),
                Arg.money("total", total), Arg.text("multiplier", Multipliers.format(shared.doubleValue())));
        } else if (shared == null && draft.topMultiplier().compareTo(BigDecimal.ONE) > 0) {
            this.services.messenger().send(player, SellMessages.SOLD_BONUSES, Arg.component("items", items),
                Arg.money("total", total));
        } else {
            this.services.messenger().send(player, SellMessages.SOLD, Arg.component("items", items), Arg.money("total", total));
        }
        // The extra hotbar total is a pop-up: quiet in combat leaves it out (the receipt is in chat already).
        if (this.settings.get().actionBarTotal() && !this.services.messenger().quietNow(player.getUniqueId())) {
            this.services.messenger().actionbar(player, lang.get(SellMessages.SOLD_ACTION_BAR, Arg.money("total", total)));
        }
    }

    /** The plain name of a stack's item, e.g. {@code diamond}. */
    static String name(ItemStack item) {
        return ItemKeys.name(WorthService.key(item.getType()));
    }
}
