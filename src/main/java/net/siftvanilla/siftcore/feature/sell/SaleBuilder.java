package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.link.OrderMarket;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Works out a {@link SaleDraft} from an inventory, on the owner's thread. Nothing moves here: it reads the slots,
 * decides which plain items (and which contents of shulker boxes and bundles) the request covers, sends units to buy
 * orders where they pay more, and prices the rest at the player's rank and mastery multipliers.
 */
final class SaleBuilder {

    /** Why a request found nothing to sell. */
    enum Refusal {
        EMPTY_HAND,
        NOT_SELLABLE,
        MODIFIED,
        HOLD_PLAIN,
        BOX_NOTHING,
        BUNDLE_NOTHING,
        NOTHING,
        NOTHING_IN_MENU,
        NOTHING_IN_CATEGORY,
        TOO_MUCH
    }

    /**
     * The outcome: a draft, or why there is none.
     *
     * @param draft   the sale (null when refused)
     * @param refusal why nothing can be sold (null when there is a draft)
     * @param item    the item key the refusal is about, if any
     */
    record Result(SaleDraft draft, Refusal refusal, String item) {
        static Result refused(Refusal refusal, String item) {
            return new Result(null, refusal, item);
        }
    }

    /** A plain stack in a slot that the request covers. */
    private record Outer(int slot, ItemStack stack, String key) {
    }

    /** A container in a slot with contents the request covers. */
    private record Box(int slot, ContainerItems.Kind kind, ItemStack container, List<ItemStack> contents) {
    }

    /** What the server pays for one item key. */
    private record Price(long worth, String category) {
    }

    private final WorthService worth;
    private final OrderBids bids;

    SaleBuilder(WorthService worth, OrderBids bids) {
        this.worth = worth;
        this.bids = bids;
    }

    /**
     * @param player     the seller
     * @param inventory  the inventory the request reads (the player's, or the sell menu)
     * @param request    what to sell
     * @param settings   the sell settings in effect
     * @param routing    whether units may go to buy orders
     * @param preview    read bids from the preview cache instead of fresh
     * @param excludedOrders orders that must not take part (their fill was cancelled)
     */
    Result build(Player player, Inventory inventory, SaleRequest request, SellSettings settings, boolean routing,
                 boolean preview, Set<Long> excludedOrders) {
        WorthService.Rates rates = this.worth.rates(player);
        OrderMarket market = this.bids.market();
        boolean routeUnpriced = routing && request.routesUnpriced();
        List<Outer> outers = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        Map<String, Price> prices = new HashMap<>();
        long scanned = 0;
        int[] slots = slots(inventory, request, settings);
        String target = request.target();
        if (request.scope() == SaleRequest.Scope.TYPE) {
            if (target == null) {
                return Result.refused(Refusal.EMPTY_HAND, null);
            }
        }
        for (int slot : slots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            scanned += stack.getAmount();
            ContainerItems.Kind kind = openable(stack, settings, request);
            if (kind != null && !kind.contents(stack).isEmpty()) {
                List<ItemStack> contents = kind.contents(stack);
                boolean any = false;
                for (ItemStack inner : contents) {
                    if (eligible(inner, request, settings, prices, routeUnpriced, market)) {
                        any = true;
                    }
                }
                if (any) {
                    boxes.add(new Box(slot, kind, stack.clone(), contents));
                }
                continue;
            }
            if (request.scope() == SaleRequest.Scope.ALL || request.scope() == SaleRequest.Scope.CATEGORY) {
                if (settings.sellAll().skipUnstackable() && stack.getMaxStackSize() == 1) {
                    continue;
                }
            }
            if (eligible(stack, request, settings, prices, routeUnpriced, market)) {
                outers.add(new Outer(slot, stack.clone(), WorthService.key(stack.getType())));
            }
        }
        if (outers.isEmpty() && boxes.isEmpty()) {
            return refuse(player, inventory, request, settings, routeUnpriced, market);
        }

        // Units per key, in the order they were found.
        Map<String, Long> available = new LinkedHashMap<>();
        for (Outer outer : outers) {
            available.merge(outer.key(), (long) outer.stack().getAmount(), Long::sum);
        }
        for (Box box : boxes) {
            for (ItemStack inner : box.contents()) {
                if (!inner.isEmpty() && eligible(inner, request, settings, prices, routeUnpriced, market)) {
                    available.merge(WorthService.key(inner.getType()), (long) inner.getAmount(), Long::sum);
                }
            }
        }

        OrderRouting.Allocation allocation;
        Map<Long, UUID> owners = new HashMap<>();
        try {
            allocation = allocate(player, available, prices, rates, routing, routeUnpriced, preview, market, excludedOrders, owners);
        } catch (ArithmeticException e) {
            return Result.refused(Refusal.TOO_MUCH, null);
        }

        // Take units per key: whole slots first, then container contents.
        Map<String, Long> toTake = new LinkedHashMap<>();
        for (String key : available.keySet()) {
            long units = allocation.server(key) + allocation.routed(key);
            if (units > 0) {
                toTake.put(key, units);
            }
        }
        if (toTake.isEmpty()) {
            return refuse(player, inventory, request, settings, routeUnpriced, market);
        }
        Map<String, Long> left = new LinkedHashMap<>(toTake);
        List<SaleDraft.Stack> stacks = new ArrayList<>();
        long takenOuter = 0;
        for (Outer outer : outers) {
            long want = left.getOrDefault(outer.key(), 0L);
            if (want <= 0) {
                continue;
            }
            int units = (int) Math.min(want, outer.stack().getAmount());
            stacks.add(new SaleDraft.Stack(outer.slot(), outer.stack(), outer.key(), units));
            left.put(outer.key(), want - units);
            takenOuter += units;
        }
        List<SaleDraft.Container> containers = new ArrayList<>();
        for (Box box : boxes) {
            List<ItemStack> contents = box.contents();
            List<ItemStack> taken = new ArrayList<>();
            Map<String, Long> units = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : left.entrySet()) {
                String key = entry.getKey();
                long want = entry.getValue();
                if (want <= 0) {
                    continue;
                }
                ContainerItems.Extraction<ItemStack> extraction = ContainerItems.extract(contents,
                    inner -> WorthService.key(inner.getType()).equals(key) && this.worth.pristine(inner), want);
                if (extraction.nothing()) {
                    continue;
                }
                contents = extraction.remaining();
                taken.addAll(extraction.taken());
                units.put(key, extraction.units());
                entry.setValue(want - extraction.units());
            }
            if (!taken.isEmpty()) {
                containers.add(new SaleDraft.Container(box.slot(), box.kind(), box.container(),
                    box.kind().rebuild(box.container(), contents), taken, units));
            }
        }

        // Price the server part, per category at the player's multiplier.
        List<SalePlan.Line> lines = new ArrayList<>();
        Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
        Map<String, Long> credits = new LinkedHashMap<>();
        Map<String, Long> routedNet = new HashMap<>();
        int tax = routing ? market.taxBasisPoints() : 0;
        long gross = 0;
        long taxTotal = 0;
        try {
            for (OrderMarket.Take take : allocation.takes()) {
                long takeGross = take.gross();
                long takeTax = OrderMarket.tax(takeGross, tax);
                gross = Math.addExact(gross, takeGross);
                taxTotal = Math.addExact(taxTotal, takeTax);
                routedNet.merge(take.key(), takeGross - takeTax, Math::addExact);
            }
            for (String key : toTake.keySet()) {
                Price price = prices.get(key);
                long server = allocation.server(key);
                if (price == null || price.worth() <= 0) {
                    continue;
                }
                if (server > 0) {
                    lines.add(new SalePlan.Line(key, server, price.worth(), price.category()));
                    multipliers.put(price.category(), rates.multiplier(price.category()));
                }
                long credit = Mastery.credit(server, price.worth(), allocation.routed(key), routedNet.getOrDefault(key, 0L));
                if (credit > 0) {
                    credits.merge(price.category(), credit, Math::addExact);
                }
            }
            SalePlan plan = SalePlan.of(lines);
            long serverTotal = plan.total(multipliers::get);
            long kept = Math.max(0, scanned - takenOuter);
            Map<Long, UUID> takeOwners = new HashMap<>();
            for (OrderMarket.Take take : allocation.takes()) {
                UUID owner = owners.get(take.orderId());
                if (owner != null) {
                    takeOwners.put(take.orderId(), owner);
                }
            }
            SaleDraft draft = new SaleDraft(request.source(), inventory, stacks, containers, plan, multipliers, serverTotal,
                allocation.takes(), takeOwners, gross, taxTotal, settings.mastery().enabled() ? credits : Map.of(), kept);
            draft.total();
            return new Result(draft, null, null);
        } catch (ArithmeticException e) {
            return Result.refused(Refusal.TOO_MUCH, null);
        }
    }

    /** Routes units to orders where they pay more, the rest to the server (or kept when the server won't buy). */
    private OrderRouting.Allocation allocate(Player player, Map<String, Long> available, Map<String, Price> prices,
                                             WorthService.Rates rates, boolean routing, boolean routeUnpriced,
                                             boolean preview, OrderMarket market, Set<Long> excludedOrders,
                                             Map<Long, UUID> owners) {
        if (!routing) {
            Map<String, Long> server = new LinkedHashMap<>();
            available.forEach((key, units) -> {
                Price price = prices.get(key);
                if (price != null && price.worth() > 0) {
                    server.put(key, units);
                }
            });
            return OrderRouting.Allocation.serverOnly(server);
        }
        List<OrderRouting.Line> lines = new ArrayList<>();
        available.forEach((key, units) -> {
            Price price = prices.get(key);
            BigDecimal unit = price == null || price.worth() <= 0 ? BigDecimal.ZERO
                : SaleMath.unit(price.worth(), rates.multiplier(price.category()));
            lines.add(new OrderRouting.Line(key, units, unit, routeUnpriced));
        });
        Function<String, List<OrderMarket.Bid>> bidsOf = key -> {
            List<OrderMarket.Bid> found = preview
                ? this.bids.cached(player.getUniqueId(), key)
                : this.bids.fresh(player.getUniqueId(), key);
            List<OrderMarket.Bid> usable = new ArrayList<>(found.size());
            for (OrderMarket.Bid bid : found) {
                if (!excludedOrders.contains(bid.orderId()) && !bid.owner().equals(player.getUniqueId())) {
                    usable.add(bid);
                    owners.put(bid.orderId(), bid.owner());
                }
            }
            return usable;
        };
        return OrderRouting.plan(lines, bidsOf, market.taxBasisPoints());
    }

    /**
     * Whether a stack is one the request sells: plain, of the right type or category, and bought by the server (or,
     * where allowed, wanted by a buy order).
     */
    private boolean eligible(ItemStack stack, SaleRequest request, SellSettings settings, Map<String, Price> prices,
                             boolean routeUnpriced, OrderMarket market) {
        if (stack == null || stack.isEmpty() || !this.worth.pristine(stack)) {
            return false;
        }
        if ((request.scope() == SaleRequest.Scope.ALL || request.scope() == SaleRequest.Scope.CATEGORY)
            && settings.sellAll().skipUnstackable() && stack.getMaxStackSize() == 1) {
            // Selling everything leaves tools and armor alone, also inside shulker boxes.
            return false;
        }
        String key = WorthService.key(stack.getType());
        if (request.scope() == SaleRequest.Scope.TYPE && !key.equals(request.target())) {
            return false;
        }
        Price price = prices.computeIfAbsent(key, k -> {
            WorthTable.Entry entry = settings.table().entry(k);
            return entry == null ? new Price(0, null) : new Price(entry.price(), entry.category());
        });
        if (request.scope() == SaleRequest.Scope.CATEGORY && (price.category() == null
            || !price.category().equals(request.target()))) {
            return false;
        }
        if (price.worth() > 0) {
            return true;
        }
        return routeUnpriced && market.available() && key.equals(market.key(stack));
    }

    /** The container kind a stack is opened as for this request, or null. */
    private static ContainerItems.Kind openable(ItemStack stack, SellSettings settings, SaleRequest request) {
        ContainerItems.Kind kind = ContainerItems.kind(stack);
        if (kind == null) {
            return null;
        }
        boolean allowed = kind == ContainerItems.Kind.SHULKER_BOX ? settings.shulkerContents() : settings.bundleContents();
        if (!allowed) {
            return null;
        }
        if ((request.scope() == SaleRequest.Scope.ALL || request.scope() == SaleRequest.Scope.CATEGORY)
            && !settings.sellAll().shulkerContents()) {
            return null;
        }
        return kind;
    }

    /** The slots a request looks at. */
    private static int[] slots(Inventory inventory, SaleRequest request, SellSettings settings) {
        return switch (request.scope()) {
            case HAND -> new int[] {((PlayerInventory) inventory).getHeldItemSlot()};
            case TYPE -> range(0, 36);
            case ALL, CATEGORY -> range(settings.sellAll().skipHotbar() ? 9 : 0, 36);
            case MENU -> range(0, SellMenu.GRID);
        };
    }

    private static int[] range(int from, int to) {
        int[] slots = new int[to - from];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = from + i;
        }
        return slots;
    }

    /** Why a request sells nothing, worded for what the player tried. */
    private Result refuse(Player player, Inventory inventory, SaleRequest request, SellSettings settings,
                          boolean routeUnpriced, OrderMarket market) {
        switch (request.scope()) {
            case HAND -> {
                ItemStack held = inventory.getItem(((PlayerInventory) inventory).getHeldItemSlot());
                if (held == null || held.isEmpty()) {
                    return Result.refused(Refusal.EMPTY_HAND, null);
                }
                String key = WorthService.key(held.getType());
                ContainerItems.Kind kind = openable(held, settings, request);
                if (kind != null && !kind.contents(held).isEmpty()) {
                    return Result.refused(kind == ContainerItems.Kind.SHULKER_BOX ? Refusal.BOX_NOTHING : Refusal.BUNDLE_NOTHING, key);
                }
                if (settings.table().entry(key) == null) {
                    return Result.refused(Refusal.NOT_SELLABLE, key);
                }
                return Result.refused(this.worth.pristine(held) ? Refusal.NOT_SELLABLE : Refusal.MODIFIED, key);
            }
            case TYPE -> {
                return Result.refused(settings.table().entry(request.target()) == null ? Refusal.NOT_SELLABLE : Refusal.NOTHING,
                    request.target());
            }
            case CATEGORY -> {
                return Result.refused(Refusal.NOTHING_IN_CATEGORY, request.target());
            }
            case MENU -> {
                return Result.refused(Refusal.NOTHING_IN_MENU, null);
            }
            default -> {
                return Result.refused(Refusal.NOTHING, null);
            }
        }
    }

    /**
     * Item keys of plain items a player carries, with amounts (hotbar and storage, and the contents of shulker boxes
     * there when selling may open them): what "Sell your ..." would look at.
     */
    Map<String, Long> carried(Player player, SellSettings settings) {
        Map<String, Long> counts = new LinkedHashMap<>();
        PlayerInventory inventory = player.getInventory();
        Set<ContainerItems.Kind> open = new LinkedHashSet<>();
        if (settings.shulkerContents()) {
            open.add(ContainerItems.Kind.SHULKER_BOX);
        }
        if (settings.bundleContents()) {
            open.add(ContainerItems.Kind.BUNDLE);
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ContainerItems.Kind kind = ContainerItems.kind(stack);
            if (kind != null && open.contains(kind)) {
                List<ItemStack> contents = kind.contents(stack);
                if (!contents.isEmpty()) {
                    for (ItemStack inner : contents) {
                        if (!inner.isEmpty() && this.worth.pristine(inner)) {
                            counts.merge(WorthService.key(inner.getType()), (long) inner.getAmount(), Long::sum);
                        }
                    }
                    continue;
                }
            }
            if (this.worth.pristine(stack)) {
                counts.merge(WorthService.key(stack.getType()), (long) stack.getAmount(), Long::sum);
            }
        }
        return counts;
    }
}
