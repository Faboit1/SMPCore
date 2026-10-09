package net.siftvanilla.siftcore.feature.sell;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.item.ContainerItems;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.gui.GridBackup;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Opens sell menus and makes sure the items in them always find their way back: to the inventory (overflow to the
 * claim box) when a menu closes, onto the ground with the rest of the death drops when the player dies, and back
 * to the player when the server stops (no close events are fired then). While items sit in a grid, a copy of them is
 * kept in the player's own data ({@link GridBackup}), saved together with their inventory, so a crash can't lose them:
 * a copy still there when the player joins is given back.
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
    private final GridBackup backup;
    /** The menu each player uses now (the last one opened for them). */
    private final Map<UUID, SellMenu> open = new ConcurrentHashMap<>();
    /**
     * Every menu of a player that has not handed its grid back yet: the one in use, and for a moment the one it
     * replaces (until the server closes that one) or one that never got on screen. The copy holds all of their items.
     */
    private final Map<UUID, List<SellMenu>> live = new ConcurrentHashMap<>();
    private BiConsumer<Player, Button.Handler> mastery = (player, back) -> { };

    SellMenus(Services services, WorthService worth, Setting<SellSettings> settings, SellService sales, ItemHandout handout) {
        this.services = services;
        this.worth = worth;
        this.settings = settings;
        this.sales = sales;
        this.handout = handout;
        this.backup = new GridBackup(new NamespacedKey(services.plugin(), "sell_grid"), services.plugin().getLogger());
    }

    /** What the Mastery button opens; the handler it gets is the dialog's Back, which returns to the menu. */
    void onMastery(BiConsumer<Player, Button.Handler> opener) {
        this.mastery = opener;
    }

    /**
     * Writes the copy in the viewer's player data after {@code menu}'s grid changed: the grids of all their menus that
     * still hold items, without slot {@code skip} of {@code menu} (-1: every slot). A menu that already handed its grid
     * back writes nothing. Viewer's thread.
     */
    void backup(SellMenu menu, int skip) {
        if (menusOf(menu.viewer()).contains(menu)) {
            writeCopy(menu.viewer(), menu, skip);
        }
    }

    /** Makes the copy hold exactly what the player's live menus hold (nothing: cleared), without {@code skip} of {@code in}. */
    private void writeCopy(Player player, SellMenu in, int skip) {
        List<ItemStack> items = new ArrayList<>();
        for (SellMenu menu : menusOf(player)) {
            Inventory grid = menu.getInventory();
            for (int slot = 0; slot < SellMenu.GRID; slot++) {
                ItemStack item = grid.getItem(slot);
                if ((menu != in || slot != skip) && item != null && !item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        this.backup.save(player, items);
    }

    private List<SellMenu> menusOf(Player player) {
        return this.live.getOrDefault(player.getUniqueId(), List.of());
    }

    /** A new menu becomes the one the player uses; until it hands its grid back, the copy covers it. */
    private void register(Player player, SellMenu menu) {
        this.live.compute(player.getUniqueId(), (uuid, menus) -> {
            List<SellMenu> now = menus == null ? new ArrayList<>() : new ArrayList<>(menus);
            now.add(menu);
            return List.copyOf(now);
        });
        this.open.put(player.getUniqueId(), menu);
    }

    /** A menu hands its grid back: the copy no longer covers it. */
    private void unregister(Player player, SellMenu menu) {
        this.open.remove(player.getUniqueId(), menu);
        this.live.computeIfPresent(player.getUniqueId(), (uuid, menus) -> {
            List<SellMenu> now = new ArrayList<>(menus);
            now.remove(menu);
            return now.isEmpty() ? null : List.copyOf(now);
        });
    }

    /** Opens a fresh sell menu. Any menu the player had open is closed (and emptied) by the server first. */
    void open(Player player) {
        SellMenu menu = new SellMenu(this.services.menus(), player, this);
        register(player, menu);
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
            register(player, menu);
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
                BigDecimal shared = draft.sharedBonus();
                if (shared != null && shared.compareTo(BigDecimal.ONE) > 0) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_BONUS,
                        Arg.text("multiplier", Multipliers.format(shared.doubleValue()))));
                } else if (shared == null && draft.topBonus().compareTo(BigDecimal.ONE) > 0) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_BONUSES));
                }
                if (draft.boosted()) {
                    lore.addAll(lang.lines(SellMessages.MENU_TOTAL_BOOSTER, Arg.number("percent", draft.boost())));
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

    /** The Mastery button: the mastery dialogs over the menu, whose Back returns to it with its grid as it was. */
    void openMastery(SellMenu menu) {
        this.mastery.accept(menu.viewer(), s -> back(s, menu));
    }

    /** Back from a dialog over a sell menu: that menu again (a fresh one when it was closed meanwhile). */
    private void back(Submission s, SellMenu menu) {
        Player player = s.player();
        if (this.open.get(player.getUniqueId()) != menu || !menu.reopen()) {
            open(player);
        }
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
        // The player's own sell-all rules: their hotbar choice decides whether it is added too.
        SellSettings s = this.sales.personal(player);
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
        if (moved > 0) {
            menu.backup();
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
        // The copy drops the items before they reach the inventory and the player is saved.
        menu.backup();
        long claimed = handBack(player, items);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
    }

    /**
     * A menu closed (on the viewer's thread): its items go back to the viewer, or drop if they just died. A player who
     * chose to sell on close ({@code sell-menu-close}) and closed it themselves sells what sells first; the rest (and
     * everything, after any other kind of close: quitting, a teleport, another screen) goes back.
     */
    void closed(SellMenu menu) {
        Player player = menu.viewer();
        InventoryCloseEvent.Reason reason = menu.takeCloseReason();
        if (SellPrefs.sellsOnClose(this.services.settings().get(player.getUniqueId(), SellPrefs.MENU_CLOSE), reason, menu.dropOnClose())
            && menusOf(player).contains(menu) && summarize(player, menu.getInventory()).draft() != null) {
            // Sold while the menu still counts as live, so the copy in the player's data follows the grid.
            this.sales.sellMenu(player, menu.getInventory(), 0);
        }
        unregister(player, menu);
        List<ItemStack> items = menu.drain();
        // Before the items go anywhere the copy drops them: it keeps only what other menus of the player still hold (a
        // newer menu that replaced this one), or is cleared.
        writeCopy(player, null, -1);
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
        long claimed = handBack(player, items);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
    }

    /**
     * Gives items that left a grid (or its copy, which no longer holds them) back to the player: into the inventory,
     * the player saved, and only then the rest into the claim box ({@link GridBackup#handBack}). Returns how many items
     * went to the claim box. Player's thread.
     */
    private long handBack(Player player, List<ItemStack> items) {
        return GridBackup.handBack(items, all -> this.handout.toInventory(player, all), () -> save(player),
            left -> this.handout.toClaimBox(player.getUniqueId(), left, "sell", null));
    }

    private void save(Player player) {
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    /**
     * Notes why a sell menu closes, before the menu framework hands the close on (at MONITOR): only a menu the player
     * closed themselves may sell on close.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SellMenu menu && event.getPlayer().equals(menu.viewer())) {
            menu.closeReason(event.getReason());
        }
    }

    /**
     * The server closes an open menu with reason DEATH right after this event, once the inventory's own drops were
     * collected and before the inventory is cleared, so the grid must drop instead of going back to the inventory.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!event.getKeepInventory() && player.getOpenInventory().getTopInventory().getHolder(false) instanceof SellMenu menu
            && menusOf(player).contains(menu)) {
            menu.dropOnClose(true);
        }
    }

    /**
     * The server closes the open menu before this event, so a menu still live at quit never got its close event (it
     * was not on screen yet). The copy is made to hold exactly what such menus still have, and is saved with the player
     * as they leave: those items come back when the player joins again. Nothing else stays in the copy.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.open.remove(player.getUniqueId());
        List<SellMenu> menus = this.live.remove(player.getUniqueId());
        List<ItemStack> items = new ArrayList<>();
        for (SellMenu menu : menus == null ? List.<SellMenu>of() : menus) {
            items.addAll(menu.drain());
        }
        this.backup.save(player, items);
    }

    /** Gives back the items a sell menu held when the server stopped hard (their copy in the player's data). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> items = this.backup.take(player);
        if (items.isEmpty()) {
            return;
        }
        long claimed = handBack(player, items);
        this.services.messenger().send(player, SellMessages.MENU_RESTORED);
        if (claimed > 0) {
            this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
        }
    }

    /**
     * Gives back the items of every menu that still holds some. Called from the feature's disable (the server fires no
     * close or quit events at shutdown, and the shutdown thread may touch every player).
     */
    void returnAll() {
        for (List<SellMenu> menus : List.copyOf(this.live.values())) {
            Player player = menus.getFirst().viewer();
            List<ItemStack> items = new ArrayList<>();
            for (SellMenu menu : menus) {
                unregister(player, menu);
                items.addAll(menu.drain());
            }
            // The copy goes first, so nothing below can leave the items both handed out and in the saved copy.
            this.backup.clear(player);
            if (items.isEmpty()) {
                continue;
            }
            // The order of handBack, keeping track of what has not certainly arrived for the report.
            List<ItemStack> unsure = items;
            try {
                List<ItemStack> left = this.handout.toInventory(player, items);
                unsure = left;
                save(player);
                this.handout.toClaimBox(player.getUniqueId(), left, "sell", null);
            } catch (RuntimeException e) {
                // Never hand anything out twice: report what may not have arrived (in full, so staff can restore it).
                this.handout.reportLost(player.getUniqueId(), unsure, "sell menu at shutdown", e);
            }
        }
    }
}
