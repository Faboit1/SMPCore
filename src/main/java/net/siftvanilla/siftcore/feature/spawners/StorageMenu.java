package net.siftvanilla.siftcore.feature.spawners;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A spawner's storage: one icon per stored item with its amount and worth. Left click takes a stack, right click
 * takes as much as fits; the bottom row has Collect XP, Sell all and the spawner's details. Every action is a
 * transaction that re-checks access and amounts, the menu is locked while one runs, and the menu redraws when the
 * storage changes (new loot, another viewer) while it is open.
 */
final class StorageMenu extends PagedMenu<StorageMenu.Entry> {

    /** One stored item. */
    record Entry(String item, Material material, long amount, long price) {

        long value() {
            return this.price <= 0 ? 0 : StorageMath.saturatingMultiply(this.price, this.amount);
        }
    }

    private static final long REFRESH_TICKS = 40L;

    private final SpawnerService service;
    private final WorthLookup worth;
    private final ManagedSpawner spawner;
    private long drawnVersion = -1;
    private Task refresher;

    StorageMenu(MenuContext ctx, Player viewer, SpawnerService service, WorthLookup worth, ManagedSpawner spawner, Runnable back) {
        super(ctx, viewer, Component.text(ctx.lang().plain(SpawnersMessages.MENU_TITLE, Arg.text("name", service.name(spawner.mob)))),
            sorts(ctx.lang()), null, back);
        this.service = service;
        this.worth = worth;
        this.spawner = spawner;
    }

    private static Cycle<Comparator<Entry>> sorts(Lang lang) {
        Comparator<Entry> byName = Comparator.comparing(Entry::item);
        return new Cycle<>(List.of(
            new Cycle.Option<>("amount", lang.get(SpawnersMessages.SORT_AMOUNT),
                Comparator.comparingLong(Entry::amount).reversed().thenComparing(byName)),
            new Cycle.Option<>("value", lang.get(SpawnersMessages.SORT_VALUE),
                Comparator.comparingLong(Entry::value).reversed().thenComparing(byName)),
            new Cycle.Option<>("name", lang.get(SpawnersMessages.SORT_NAME), byName)), "amount");
    }

    @Override
    protected List<Entry> entries() {
        ManagedSpawner.State state = this.service.state(this.spawner);
        this.drawnVersion = state.version();
        List<Entry> list = new ArrayList<>(state.items().size());
        for (Map.Entry<String, Long> line : state.items().entrySet()) {
            Material material = this.service.material(line.getKey());
            if (material == Material.AIR) {
                continue;
            }
            list.add(new Entry(line.getKey(), material, line.getValue(), this.worth.unitPrice(ItemStack.of(material))));
        }
        return list;
    }

    @Override
    protected ItemStack icon(Entry entry) {
        Lang lang = this.ctx.lang();
        Component name = lang.get(SpawnersMessages.MENU_ITEM_NAME, Arg.component("item", SpawnerService.itemName(entry.material())));
        List<Component> lore = entry.price() > 0
            ? lang.lines(SpawnersMessages.MENU_ITEM_LORE, Arg.number("amount", entry.amount()), Arg.money("price", entry.price()),
                Arg.money("value", entry.value()))
            : lang.lines(SpawnersMessages.MENU_ITEM_LORE_UNSELLABLE, Arg.number("amount", entry.amount()));
        ItemStack icon = Items.icon(entry.material(), name, lore);
        icon.setAmount(Math.clamp(entry.amount(), 1, Math.min(64, entry.material().getMaxStackSize())));
        return icon;
    }

    @Override
    protected String searchText(Entry entry) {
        return entry.item().substring(entry.item().indexOf(':') + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    @Override
    protected void clicked(Entry entry, ClickContext click) {
        long wanted = click.right() || click.shift() ? Long.MAX_VALUE : entry.material().getMaxStackSize();
        click(Feedback.CLICK);
        runBusy(this.service.take(this.viewer, this.spawner, entry.item(), wanted), outcome -> afterAction());
    }

    @Override
    protected void drawExtras() {
        startRefresher();
        Lang lang = this.ctx.lang();
        ManagedSpawner.State state = this.service.state(this.spawner);
        SpawnersSettings s = this.service.settings();
        long xpCap = this.service.xpCapacity(state.stack());
        set(SLOT_EXTRA_1, Items.icon(Material.EXPERIENCE_BOTTLE, lang.get(SpawnersMessages.MENU_XP),
                state.xp() > 0
                    ? lang.lines(SpawnersMessages.MENU_XP_LORE, Arg.number("xp", state.xp()), Arg.number("cap", xpCap))
                    : lang.lines(SpawnersMessages.MENU_XP_EMPTY, Arg.number("cap", xpCap))),
            click -> {
                click(Feedback.CLICK);
                runBusy(this.service.collectXp(this.viewer, this.spawner), outcome -> afterAction());
            });
        WorthLookup.SellRate rate = this.worth.rate(this.viewer);
        SpawnerService.Sale sale = this.service.price(state.items(), rate);
        List<Component> sellLore;
        if (sale == null || sale.total() <= 0) {
            sellLore = lang.lines(SpawnersMessages.MENU_SELL_EMPTY);
        } else {
            sellLore = new ArrayList<>(rate.rank() > 1.0
                ? lang.lines(SpawnersMessages.MENU_SELL_LORE_BONUS, Arg.number("count", sale.count()), Arg.money("total", sale.total()),
                    Arg.text("multiplier", SpawnerService.multiplier(rate.rank())))
                : lang.lines(SpawnersMessages.MENU_SELL_LORE, Arg.number("count", sale.count()), Arg.money("total", sale.total())));
            if (sale.boost() > 0) {
                sellLore.addAll(lang.lines(SpawnersMessages.MENU_SELL_BOOSTER, Arg.number("percent", sale.boost())));
            }
        }
        set(SLOT_EXTRA_2, Items.icon(Material.EMERALD, lang.get(SpawnersMessages.MENU_SELL), sellLore), click -> {
            click(Feedback.CLICK);
            runBusy(this.service.sellAll(this.viewer, this.spawner), outcome -> afterAction());
        });
        List<Component> info = new ArrayList<>(lang.lines(SpawnersMessages.MENU_INFO_LORE,
            Arg.number("stack", state.stack()),
            Arg.number("cap", this.service.stackCap(this.viewer, this.spawner.mob)),
            Arg.text("owner", this.service.ownerName(this.spawner.owner)),
            Arg.number("used", state.used()),
            Arg.number("capacity", this.service.capacity(this.spawner, state.stack())),
            Arg.number("xp", state.xp()),
            Arg.number("xp-cap", xpCap),
            Arg.text("location", this.spawner.pos.coordinates()),
            Arg.text("world", this.spawner.pos.world()),
            Arg.time("interval", s.interval()),
            Arg.number("radius", s.radius())));
        if (this.spawner.lastFull()) {
            info.addAll(lang.lines(SpawnersMessages.MENU_INFO_FULL));
        }
        set(SLOT_EXTRA_3, Items.icon(Material.SPAWNER, lang.get(SpawnersMessages.MENU_INFO, Arg.text("name", this.service.name(this.spawner.mob))),
            info), null);
    }

    private void afterAction() {
        if (this.spawner.removed()) {
            this.viewer.closeInventory();
            return;
        }
        redraw();
    }

    /** Redraws every two seconds while open when the storage changed (new loot, another viewer); closes if it's gone. */
    private void startRefresher() {
        if (this.refresher != null) {
            return;
        }
        this.refresher = this.ctx.scheduler().entityTimer(this.viewer, () -> {
            if (this.viewer.getOpenInventory().getTopInventory().getHolder(false) != this) {
                return;
            }
            if (this.spawner.removed()) {
                this.ctx.messenger().send(this.viewer, SpawnersMessages.GONE);
                this.viewer.closeInventory();
            } else if (!busy() && this.spawner.version() != this.drawnVersion) {
                redraw();
            }
        }, null, REFRESH_TICKS, REFRESH_TICKS);
    }

    @Override
    protected void closed() {
        Task task = this.refresher;
        this.refresher = null;
        if (task != null) {
            task.cancel();
        }
    }
}
