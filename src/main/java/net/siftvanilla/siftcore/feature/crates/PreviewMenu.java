package net.siftvanilla.siftcore.feature.crates;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Cycle;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A crate's rewards with their chances. Every reward that can currently be won is shown with its chance (the shown
 * chances add up to exactly 100%), its rarity and, for items, what the server pays for them. Slot 50 opens a key
 * right here; the menu is locked while that opening is stored.
 */
final class PreviewMenu extends PagedMenu<Reward> {

    private final String crateId;
    private final Setting<CratesSettings> settings;
    private final RewardItems items;
    private final KeyService keys;
    private final CrateOpener opener;
    private final CrateText text;
    private final WorthLookup worth;
    private final PlayerSettings prefs;
    private final Cycle<Comparator<Reward>> sort;
    private String savedSort;
    private Map<String, Long> chances = Map.of();

    /** The remembered sort of preview menus (a free per-player value, not a setting). */
    static final String SORT_SETTING = "crate-preview-sort";

    /** @param prefs the players' settings (Keys per bulk open sets what a right-click opens; the remembered sort) */
    PreviewMenu(MenuContext ctx, Player viewer, Crate crate, Setting<CratesSettings> settings, RewardItems items, KeyService keys,
                CrateOpener opener, CrateText text, WorthLookup worth, PlayerSettings prefs, Runnable back) {
        this(ctx, viewer, crate, settings, items, keys, opener, text, worth, prefs, back,
            sort(ctx.lang(), prefs.raw(viewer.getUniqueId(), SORT_SETTING, "order")));
    }

    private PreviewMenu(MenuContext ctx, Player viewer, Crate crate, Setting<CratesSettings> settings, RewardItems items, KeyService keys,
                        CrateOpener opener, CrateText text, WorthLookup worth, PlayerSettings prefs, Runnable back,
                        Cycle<Comparator<Reward>> sort) {
        super(ctx, viewer, Component.text(ctx.lang().plain(CratesMessages.PREVIEW_TITLE, Arg.text("name", crate.name()))),
            sort, null, back);
        this.crateId = crate.id();
        this.settings = settings;
        this.items = items;
        this.keys = keys;
        this.opener = opener;
        this.text = text;
        this.worth = worth;
        this.prefs = prefs;
        this.sort = sort;
        this.savedSort = sort.selected().id();
    }

    private static Cycle<Comparator<Reward>> sort(Lang lang, String initial) {
        return new Cycle<>(List.of(
            new Cycle.Option<Comparator<Reward>>("order", lang.get(CratesMessages.PREVIEW_SORT_ORDER), (a, b) -> 0),
            new Cycle.Option<Comparator<Reward>>("likely", lang.get(CratesMessages.PREVIEW_SORT_LIKELY),
                Comparator.comparingDouble(Reward::weight).reversed()),
            new Cycle.Option<Comparator<Reward>>("rarest", lang.get(CratesMessages.PREVIEW_SORT_RAREST),
                Comparator.comparingDouble(Reward::weight))), initial);
    }

    /** The next preview opens with the sort the player last picked. */
    @Override
    protected void closed() {
        String sortId = this.sort.selected().id();
        if (!sortId.equals(this.savedSort)) {
            this.prefs.setRaw(this.viewer.getUniqueId(), SORT_SETTING, sortId);
            this.savedSort = sortId;
        }
    }

    private Crate crate() {
        return this.settings.get().crate(this.crateId);
    }

    @Override
    protected List<Reward> entries() {
        Crate crate = crate();
        if (crate == null) {
            this.chances = Map.of();
            return List.of();
        }
        List<Reward> available = this.items.available(crate);
        double[] weights = new double[available.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = available.get(i).weight();
        }
        long[] hundredths = Chances.hundredths(weights);
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < hundredths.length; i++) {
            map.put(available.get(i).id(), hundredths[i]);
        }
        this.chances = map;
        return available;
    }

    @Override
    protected ItemStack icon(Reward reward) {
        Lang lang = this.ctx.lang();
        List<Component> lore = new ArrayList<>();
        lore.add(lang.get(CratesMessages.PREVIEW_CHANCE, Arg.text("chance", Chances.format(this.chances.getOrDefault(reward.id(), 0L)))));
        lore.add(lang.get(CratesMessages.PREVIEW_RARITY, Arg.text("rarity", this.settings.get().rarity(reward.rarity()).label())));
        if (reward.kind() instanceof Reward.Keys keys) {
            Crate target = this.settings.get().crate(keys.crate());
            lore.add(lang.get(CratesMessages.PREVIEW_OPENS, Arg.text("name", target == null ? keys.crate() : target.name())));
        }
        ItemStack icon = this.items.icon(reward);
        if (RewardItems.showsOwnItem(reward)) {
            long value = this.items.build(reward).map(this.worth::price).orElse(0L);
            if (value > 0) {
                lore.add(lang.get(CratesMessages.PREVIEW_WORTH, Arg.money("worth", value)));
            }
            return Items.display(icon, lore);
        }
        icon.setData(DataComponentTypes.CUSTOM_NAME, lang.get(CratesMessages.PREVIEW_NAME, Arg.component("reward", this.text.reward(reward)))
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> lines = new ArrayList<>(lore.size());
        for (Component line : lore) {
            lines.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        icon.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        Items.hideDetails(icon);
        return icon;
    }

    @Override
    protected void clicked(Reward reward, ClickContext click) {
        // The preview only shows what can be won.
    }

    @Override
    protected void drawExtras() {
        Crate crate = crate();
        if (crate == null) {
            clear(SLOT_EXTRA_1);
            return;
        }
        Lang lang = this.ctx.lang();
        int owned = this.keys.keys(this.viewer.getUniqueId(), crate.id());
        if (owned <= 0) {
            set(SLOT_EXTRA_1, Items.icon(Material.BARRIER, lang.get(CratesMessages.PREVIEW_NO_KEYS),
                lang.lines(CratesMessages.PREVIEW_NO_KEYS_LORE)), null);
            return;
        }
        // A right-click opens the player's Keys per bulk open (never more than they have or the server allows).
        int many = CratePlayerSettings.bulkAmount(this.prefs.get(this.viewer, CratePlayerSettings.BULK_AMOUNT), owned,
            this.settings.get().bulkOpen());
        List<Component> lore = new ArrayList<>(lang.lines(CratesMessages.PREVIEW_OPEN_LORE,
            Arg.component("keys", this.text.keys(owned, crate.name()))));
        if (many >= 2) {
            lore.addAll(lang.lines(CratesMessages.PREVIEW_OPEN_MANY_LORE, Arg.number("count", many)));
        }
        set(SLOT_EXTRA_1, Items.icon(Material.TRIPWIRE_HOOK, lang.get(CratesMessages.PREVIEW_OPEN), lore),
            click -> open(click, click.right() && many >= 2 ? many : 1));
    }

    /** Opens one key (left click) or several in a row (right click); the menu is locked until they are stored. */
    private void open(ClickContext click, int count) {
        if (!lock()) {
            return;
        }
        click(Feedback.CLICK);
        if (count == 1) {
            this.opener.open(this.viewer, this.crateId, false, result -> {
                unlock();
                if (result instanceof CrateOpener.Refused refused) {
                    this.opener.report(this.viewer, refused);
                }
                redraw();
            });
            return;
        }
        this.opener.openMany(this.viewer, this.crateId, count, false, batch -> {
            unlock();
            this.opener.receipt(this.viewer, batch);
            redraw();
        });
    }
}
