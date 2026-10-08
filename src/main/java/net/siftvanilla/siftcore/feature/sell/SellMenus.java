package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Opens sell menus and makes sure the items in them always find their way back: to the inventory (overflow to the
 * claim box) when a menu closes, onto the ground with the rest of the death drops when the player dies, and back
 * to the player when the server stops (no close events are fired then).
 */
final class SellMenus implements Listener {

    /** Item kinds listed on the total button before "and n more kinds". */
    private static final int TOTAL_LINES = 8;

    /**
     * What the grid holds right now.
     *
     * @param draft      the sale the grid would make, or null when nothing in it sells
     * @param unsellable items that can't be sold (they are given back on close)
     * @param total      what selling pays
     * @param tooMuch    the total does not fit in a long
     */
    record Summary(SaleDraft draft, long unsellable, long total, boolean tooMuch) {
    }

    private final Services services;
    private final WorthService worth;
    private final Setting<SellSettings> settings;
    private final SellService sales;
    private final ItemHandout handout;
    private final Map<UUID, SellMenu> open = new ConcurrentHashMap<>();
    private Consumer<Player> mastery = player -> { };

    SellMenus(Services services, WorthService worth, Setting<SellSettings> settings, SellService sales, ItemHandout handout) {
        this.services = services;
        this.worth = worth;
        this.settings = settings;
        this.sales = sales;
        this.handout = handout;
    }

    /** What the Mastery button opens. */
    void onMastery(Consumer<Player> opener) {
        this.mastery = opener;
    }

    /** Opens a fresh sell menu. Any menu the player had open is closed (and emptied) by the server first. */
    void open(Player player) {
        SellMenu menu = new SellMenu(this.services.menus(), player, this);
        this.open.put(player.getUniqueId(), menu);
        menu.open();
    }

    /**
     * Opens a sell menu with what the request would sell already in it (the confirmation's "Choose items"): for
     * {@code /sell all} everything sellable, for one item or one category only those. The menu is registered right
     * before its grid is filled, on the player's thread, so whatever happens next its items find their way back.
     */
    void openFilled(Player player, SaleRequest request) {
        Runnable open = () -> {
            SellMenu menu = new SellMenu(this.services.menus(), player, this);
            this.open.put(player.getUniqueId(), menu);
            addSellable(menu, request);
            menu.open();
        };
        if (this.services.scheduler().owns(player)) {
            open.run();
        } else {
            this.services.scheduler().entity(player, open, null);
        }
    }

    int openCount() {
        return this.open.size();
    }

    Summary summarize(Player viewer, Inventory inventory) {
        long items = 0;
        for (int slot = 0; slot < SellMenu.GRID; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty()) {
                items += item.getAmount();
            }
        }
        SaleBuilder.Result result = this.sales.preview(viewer, inventory, SaleRequest.menu());
        if (result.draft() == null) {
            return new Summary(null, items, 0, result.refusal() == SaleBuilder.Refusal.TOO_MUCH);
        }
        SaleDraft draft = result.draft();
        long sold = 0;
        for (SaleDraft.Stack stack : draft.stacks()) {
            sold += stack.units();
        }
        long unsellable = Math.max(0, items - sold);
        return new Summary(draft, unsellable, draft.total(), false);
    }

    ItemStack totalIcon(Player viewer, Summary summary) {
        Lang lang = this.services.lang();
        List<Component> lore = new ArrayList<>();
        SaleDraft draft = summary.draft();
        if (draft == null && summary.unsellable() == 0) {
            lore.addAll(lang.lines(SellMessages.MENU_TOTAL_EMPTY));
        } else {
            if (draft != null) {
                lore.addAll(lang.lines(SellMessages.MENU_TOTAL_COUNT, Arg.number("count", draft.count())));
                List<SalePlan.Line> lines = draft.server().lines();
                int shown = Math.min(TOTAL_LINES, lines.size());
                for (int i = 0; i < shown; i++) {
                    SalePlan.Line line = lines.get(i);
                    long value = SaleMath.withMultiplier(line.value(),
                        draft.multipliers().getOrDefault(line.category(), BigDecimal.ONE));
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_LINE, Arg.number("amount", line.amount()),
                        Arg.text("item", ItemKeys.name(line.item())), Arg.money("value", value)));
                }
                if (lines.size() > shown) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_MORE, Arg.number("count", lines.size() - shown)));
                }
                BigDecimal shared = draft.sharedMultiplier();
                if (shared != null && shared.compareTo(BigDecimal.ONE) > 0) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_BONUS,
                        Arg.text("multiplier", Multipliers.format(shared.doubleValue()))));
                } else if (shared == null && draft.topMultiplier().compareTo(BigDecimal.ONE) > 0) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_BONUSES));
                }
                if (!draft.takes().isEmpty()) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_ORDERS, Arg.money("orders", draft.ordersNet()),
                        Arg.number("count", draft.orderCount())));
                }
            }
            if (summary.unsellable() > 0) {
                lore.addAll(lang.lines(SellMessages.MENU_TOTAL_UNSELLABLE, Arg.number("count", summary.unsellable())));
            }
            if (draft != null) {
                masteryLine(viewer, draft, lore);
            }
        }
        lore.add(Component.empty());
        lore.addAll(lang.lines(SellMessages.MENU_TOTAL_HINT));
        return Items.icon(Material.GOLD_INGOT, lang.get(SellMessages.MENU_TOTAL, Arg.money("total", summary.total())), lore);
    }

    /** "Mastery: +$x toward Mining level 3" for the category the sale helps most (when not at max level). */
    private void masteryLine(Player viewer, SaleDraft draft, List<Component> lore) {
        Mastery rules = this.settings.get().mastery();
        if (!rules.enabled() || draft.credits().isEmpty()) {
            return;
        }
        String best = null;
        long credit = 0;
        for (Map.Entry<String, Long> entry : draft.credits().entrySet()) {
            if (entry.getValue() > credit) {
                best = entry.getKey();
                credit = entry.getValue();
            }
        }
        if (best == null) {
            return;
        }
        int level = this.worth.rates(viewer).level(best);
        if (level >= rules.maxLevel()) {
            return;
        }
        lore.addAll(this.services.lang().lines(SellMessages.MENU_TOTAL_MASTERY, Arg.money("value", credit),
            Arg.text("category", this.worth.categories().name(best)), Arg.number("level", level + 1)));
    }

    ItemStack sellIcon(Summary summary) {
        Lang lang = this.services.lang();
        List<Component> lore = summary.total() > 0
            ? lang.lines(SellMessages.MENU_SELL_LORE, Arg.money("total", summary.total()))
            : lang.lines(SellMessages.MENU_SELL_EMPTY);
        return Items.icon(Material.EMERALD, lang.get(SellMessages.MENU_SELL), lore);
    }

    ItemStack addIcon() {
        Lang lang = this.services.lang();
        return Items.icon(Material.HOPPER, lang.get(SellMessages.MENU_ADD), lang.lines(SellMessages.MENU_ADD_LORE));
    }

    ItemStack giveBackIcon() {
        Lang lang = this.services.lang();
        return Items.icon(Material.OAK_DOOR, lang.get(SellMessages.MENU_GIVE_BACK), lang.lines(SellMessages.MENU_GIVE_BACK_LORE));
    }

    ItemStack masteryIcon() {
        Lang lang = this.services.lang();
        return Items.icon(Material.BOOK, lang.get(SellMessages.MENU_MASTERY), lang.lines(SellMessages.MENU_MASTERY_LORE));
    }

    boolean masteryShown() {
        return this.settings.get().mastery().enabled();
    }

    void openMastery(Player player) {
        this.mastery.accept(player);
    }

    void sell(SellMenu menu, long shownTotal) {
        this.sales.sellMenu(menu.viewer(), menu.getInventory(), shownTotal);
    }

    /**
     * Add: moves every plain stack the server buys, and every shulker box (or bundle) with something sellable
     * inside, from the hotbar and storage into empty grid slots. Never the tool in the main hand, armor or the off
     * hand; the hotbar only when sell-all doesn't skip it; items that don't stack only when they sell. Each slot is
     * read again right before it moves, and moving stops when the grid is full.
     */
    void addSellable(SellMenu menu) {
        addSellable(menu, null);
    }

    /** {@link #addSellable(SellMenu)} limited to what {@code request} covers (one item or one category; null: all). */
    void addSellable(SellMenu menu, SaleRequest request) {
        Player player = menu.viewer();
        SellSettings s = this.settings.get();
        PlayerInventory inventory = player.getInventory();
        Inventory grid = menu.getInventory();
        int held = inventory.getHeldItemSlot();
        int moved = 0;
        boolean full = false;
        Predicate<String> wanted = wanted(request, s);
        for (int slot = s.sellAll().skipHotbar() ? 9 : 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || !addable(stack, s, wanted)) {
                continue;
            }
            if (slot == held && stack.getMaxStackSize() == 1 && ContainerItems.kind(stack) == null) {
                // The tool in the player's hand stays there.
                continue;
            }
            int target = menu.emptySlot();
            if (target < 0) {
                full = true;
                break;
            }
            grid.setItem(target, stack.clone());
            inventory.setItem(slot, null);
            moved++;
        }
        if (moved == 0 && !full) {
            this.services.messenger().send(player, SellMessages.MENU_ADD_NONE);
        } else if (full) {
            this.services.messenger().send(player, SellMessages.MENU_ADD_FULL);
        }
    }

    /** Which item keys a request covers: one item, the items of one category, or everything. */
    private static Predicate<String> wanted(SaleRequest request, SellSettings s) {
        if (request == null) {
            return key -> true;
        }
        return switch (request.scope()) {
            case TYPE -> key -> key.equals(request.target());
            case CATEGORY -> key -> request.target().equals(s.table().category(key));
            default -> key -> true;
        };
    }

    private boolean addable(ItemStack stack, SellSettings s, Predicate<String> wanted) {
        ContainerItems.Kind kind = ContainerItems.kind(stack);
        if (kind != null && (kind == ContainerItems.Kind.SHULKER_BOX ? s.shulkerContents() : s.bundleContents())) {
            for (ItemStack inner : kind.contents(stack)) {
                if (!inner.isEmpty() && this.worth.unitPrice(inner) > 0 && wanted.test(WorthService.key(inner.getType()))) {
                    return true;
                }
            }
        }
        return this.worth.unitPrice(stack) > 0 && wanted.test(WorthService.key(stack.getType()));
    }

    /** Give back: empties the grid into the inventory (the rest to the claim box) without closing the menu. */
    void giveBack(SellMenu menu) {
        Player player = menu.viewer();
        List<ItemStack> items = menu.drain();
        if (items.isEmpty()) {
            return;
        }
        long claimed = this.handout.give(player, items, "sell", null);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    /** A menu closed (on the viewer's thread): its items go back to the viewer, or drop if they just died. */
    void closed(SellMenu menu) {
        Player player = menu.viewer();
        this.open.remove(player.getUniqueId(), menu);
        List<ItemStack> items = menu.drain();
        if (items.isEmpty()) {
            return;
        }
        if (menu.dropOnClose()) {
            Location at = player.getLocation();
            for (ItemStack item : items) {
                player.getWorld().dropItemNaturally(at, item);
            }
            return;
        }
        long claimed = this.handout.give(player, items, "sell", null);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    /**
     * The server closes an open menu with reason DEATH right after this event, once the inventory's own drops were
     * collected and before the inventory is cleared, so the grid must drop instead of going back to the inventory.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        SellMenu menu = this.open.get(event.getEntity().getUniqueId());
        if (menu != null && !event.getKeepInventory()) {
            menu.dropOnClose(true);
        }
    }

    /** A menu still registered at quit never received its close event: keep its items in the claim box. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        SellMenu menu = this.open.remove(uuid);
        if (menu != null) {
            List<ItemStack> items = menu.drain();
            if (!items.isEmpty()) {
                this.handout.toClaimBox(uuid, items, "sell", null);
            }
        }
    }

    /**
     * Gives back the items of every open menu. Called from the feature's disable (the server fires no close or quit
     * events at shutdown, and the shutdown thread may touch every player).
     */
    void returnAll() {
        for (SellMenu menu : List.copyOf(this.open.values())) {
            Player player = menu.viewer();
            this.open.remove(player.getUniqueId(), menu);
            List<ItemStack> items = menu.drain();
            if (items.isEmpty()) {
                continue;
            }
            try {
                this.handout.give(player, items, "sell", null);
            } catch (RuntimeException e) {
                // Never hand anything out twice: report what may not have arrived instead of retrying.
                this.handout.reportLost(player.getUniqueId(), items, "sell menu at shutdown", e);
            }
        }
    }
}
