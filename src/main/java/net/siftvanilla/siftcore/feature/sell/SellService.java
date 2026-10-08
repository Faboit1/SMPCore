package net.siftvanilla.siftcore.feature.sell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.ItemSellEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Sells items to the server: {@code /sell hand}, {@code /sell all} and the sell menu. Every method runs on the
 * player's thread and follows remove-before-grant: the sellable stacks are taken out of their inventory first,
 * then one ledger transaction pays for them; if that fails the stacks go back where they were.
 */
final class SellService {

    /** Receipt hover cards list at most this many item kinds. */
    private static final int RECEIPT_LINES = 12;

    /** How a sale attempt ended. */
    enum Outcome {
        SOLD,
        NOTHING,
        CANCELLED,
        FAILED
    }

    /** A stack picked for sale: where it was and a copy of it. */
    private record Picked(int slot, ItemStack item, String key, long unitPrice) {
    }

    private final Services services;
    private final WorthService worth;
    private final Setting<SellSettings> settings;
    private final ItemHandout handout;

    SellService(Services services, WorthService worth, Setting<SellSettings> settings, ItemHandout handout) {
        this.services = services;
        this.worth = worth;
        this.settings = settings;
        this.handout = handout;
    }

    /** {@code /sell hand}: the stack in the main hand. */
    Outcome sellHand(Player player) {
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack item = inventory.getItem(slot);
        switch (this.worth.verdict(item)) {
            case EMPTY -> {
                this.services.messenger().send(player, SellMessages.EMPTY_HAND);
                return Outcome.NOTHING;
            }
            case NO_PRICE -> {
                this.services.messenger().send(player, SellMessages.NOT_SELLABLE, Arg.text("item", name(item)));
                return Outcome.NOTHING;
            }
            case MODIFIED -> {
                this.services.messenger().send(player, SellMessages.MODIFIED);
                return Outcome.NOTHING;
            }
            case SELLABLE -> {
                return sell(player, inventory, List.of(slot), ItemSellEvent.Source.HAND);
            }
        }
        return Outcome.NOTHING;
    }

    /** {@code /sell all}: every sellable stack of the main inventory; never armor or the off hand. */
    Outcome sellAll(Player player) {
        SellSettings s = this.settings.get();
        PlayerInventory inventory = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int slot = s.sellAllSkipHotbar() ? 9 : 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || (s.sellAllSkipUnstackable() && item.getMaxStackSize() == 1)) {
                continue;
            }
            if (this.worth.verdict(item) == WorthService.Verdict.SELLABLE) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            this.services.messenger().send(player, SellMessages.NOTHING_TO_SELL);
            return Outcome.NOTHING;
        }
        return sell(player, inventory, slots, ItemSellEvent.Source.ALL);
    }

    /** The sell menu: every sellable stack in the first {@code gridSize} slots of {@code menu}. */
    Outcome sellMenu(Player player, Inventory menu, int gridSize) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < gridSize; slot++) {
            if (this.worth.verdict(menu.getItem(slot)) == WorthService.Verdict.SELLABLE) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            this.services.messenger().send(player, SellMessages.NOTHING_IN_MENU);
            return Outcome.NOTHING;
        }
        return sell(player, menu, slots, ItemSellEvent.Source.MENU);
    }

    private Outcome sell(Player player, Inventory inventory, List<Integer> slots, ItemSellEvent.Source source) {
        List<Picked> picked = new ArrayList<>(slots.size());
        List<SalePlan.Line> lines = new ArrayList<>(slots.size());
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            long unit = this.worth.unitPrice(item);
            if (unit <= 0) {
                continue;
            }
            String key = WorthService.key(item.getType());
            picked.add(new Picked(slot, item.clone(), key, unit));
            lines.add(new SalePlan.Line(key, item.getAmount(), unit));
        }
        if (picked.isEmpty()) {
            this.services.messenger().send(player, SellMessages.NOTHING_TO_SELL);
            return Outcome.NOTHING;
        }
        double multiplier = this.worth.multiplier(player);
        SalePlan plan;
        long total;
        try {
            plan = SalePlan.of(lines);
            total = plan.total(multiplier);
        } catch (ArithmeticException e) {
            this.services.messenger().send(player, SellMessages.TOO_MUCH);
            return Outcome.FAILED;
        }
        if (!this.services.ledger().available()) {
            this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            return Outcome.FAILED;
        }
        List<ItemStack> items = new ArrayList<>(picked.size());
        for (Picked p : picked) {
            items.add(p.item());
        }
        if (!new ItemSellEvent(player, source, items, total, multiplier).callEvent()) {
            this.services.messenger().send(player, SellMessages.CANCELLED);
            return Outcome.CANCELLED;
        }

        // Remove before grant: the stacks leave the inventory before any money is created.
        List<Picked> taken = new ArrayList<>(picked.size());
        for (Picked p : picked) {
            ItemStack now = inventory.getItem(p.slot());
            if (now == null || !now.equals(p.item())) {
                putBack(player, inventory, taken);
                this.services.messenger().send(player, SellMessages.FAILED);
                return Outcome.FAILED;
            }
            inventory.setItem(p.slot(), null);
            taken.add(p);
        }

        UUID uuid = player.getUniqueId();
        LedgerTx tx = LedgerTx.builder()
            .actor(uuid)
            .note(plan.note())
            .source(uuid, Currency.MONEY, total, "sell", source.name().toLowerCase(Locale.ROOT))
            .build();
        TransactionResult result = this.services.ledger().execute(tx);
        if (!result.success()) {
            putBack(player, inventory, taken);
            switch (result.status()) {
                case BALANCE_LIMIT -> this.services.messenger().send(player, SellMessages.BALANCE_FULL);
                case UNAVAILABLE -> this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
                case CANCELLED -> this.services.messenger().send(player, SellMessages.CANCELLED);
                default -> this.services.messenger().send(player, SellMessages.FAILED);
            }
            return result.status() == TransactionStatus.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED;
        }
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                // Storage failed and the money was taken back, so the items go back too.
                this.services.scheduler().entity(player, () -> {
                    long claimed = this.handout.give(player, items, "sell", null);
                    saveIfConfigured(player);
                    this.services.messenger().send(player, SellMessages.FAILED);
                    if (claimed > 0) {
                        this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
                    }
                }, () -> this.handout.toClaimBox(uuid, items, "sell", null));
            }
        });
        saveIfConfigured(player);
        receipt(player, plan, total, multiplier);
        return Outcome.SOLD;
    }

    /** Puts taken stacks back into their slots (still empty: nothing ran in between), anything else to the player. */
    private void putBack(Player player, Inventory inventory, List<Picked> taken) {
        List<ItemStack> rest = new ArrayList<>();
        for (Picked p : taken) {
            ItemStack now = inventory.getItem(p.slot());
            if (now == null || now.isEmpty()) {
                inventory.setItem(p.slot(), p.item());
            } else {
                rest.add(p.item());
            }
        }
        if (!rest.isEmpty()) {
            long claimed = this.handout.give(player, rest, "sell", null);
            if (claimed > 0) {
                this.services.messenger().send(player, SellMessages.CLAIM_BOX, Arg.number("count", claimed));
            }
        }
    }

    private void saveIfConfigured(Player player) {
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
    }

    private void receipt(Player player, SalePlan plan, long total, double multiplier) {
        Lang lang = this.services.lang();
        Component items;
        if (plan.singleKind()) {
            SalePlan.Line line = plan.lines().getFirst();
            items = lang.get(SellMessages.ITEMS_ONE, Arg.number("amount", line.amount()), Arg.text("item", ItemKeys.name(line.item())));
        } else {
            items = lang.get(SellMessages.ITEMS_MANY, Arg.number("amount", plan.itemCount()));
        }
        List<Component> card = new ArrayList<>();
        List<SalePlan.Line> lines = plan.lines();
        for (int i = 0; i < Math.min(RECEIPT_LINES, lines.size()); i++) {
            SalePlan.Line line = lines.get(i);
            card.add(lang.get(SellMessages.RECEIPT_LINE, Arg.number("amount", line.amount()),
                Arg.text("item", ItemKeys.name(line.item())), Arg.money("value", line.value())));
        }
        if (lines.size() > RECEIPT_LINES) {
            card.add(lang.get(SellMessages.RECEIPT_MORE, Arg.number("count", lines.size() - RECEIPT_LINES)));
        }
        boolean bonus = multiplier > 1.0;
        if (bonus) {
            card.add(lang.get(SellMessages.RECEIPT_BONUS, Arg.text("multiplier", Multipliers.format(multiplier))));
        }
        items = items.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), card)));
        if (bonus) {
            this.services.messenger().send(player, SellMessages.SOLD_BONUS, Arg.component("items", items),
                Arg.money("total", total), Arg.text("multiplier", Multipliers.format(multiplier)));
        } else {
            this.services.messenger().send(player, SellMessages.SOLD, Arg.component("items", items), Arg.money("total", total));
        }
    }

    /** The plain name of a stack's item, e.g. {@code diamond}. */
    static String name(ItemStack item) {
        return ItemKeys.name(WorthService.key(item.getType()));
    }
}
