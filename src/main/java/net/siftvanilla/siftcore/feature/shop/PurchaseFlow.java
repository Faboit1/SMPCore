package net.siftvanilla.siftcore.feature.shop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.ShopPurchaseEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.ClaimHandouts;
import net.siftvanilla.siftcore.economy.Handoffs;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.feature.sell.SellLink;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Buying from the shop. The purchase dialog has an amount slider and an exact-amount field; its Buy button always
 * names the amount and total it buys, and when the player changed the amount it first shows the new total instead
 * of buying. Totals at or above the confirmation threshold ask once more. Everything is checked again when the
 * player buys (the entry still exists, its price is the one they saw, the balance covers it), and the price is
 * checked a last time inside the transaction.
 * <p>
 * The money is taken in one ledger transaction that also puts every bought item into the claim box (and remembers
 * the purchase for "Buy again"), so the purchase is stored whole whatever happens next. Once it is stored, the part
 * that fitted the inventory is claimed into it on the buyer's thread; if the buyer left or the server stopped first,
 * it simply waits in the claim box.
 * <p>
 * The dialog also shows what the item sells back for (the player's own rate) and how many they have, and offers
 * "Max you can afford" and "Fill your inventory", which work the amount out when pressed and show the new total
 * before anything is bought. Combat-tagged players can't open it or buy (checked again on every press).
 */
final class PurchaseFlow {

    private static final String AMOUNT = "amount";
    private static final String EXACT = "exact";
    /** The claim box source of purchases. */
    static final String SOURCE = "shop";

    private final Services services;
    private final Setting<ShopSettings> settings;
    private final ShopItems items;
    private final ClaimHandouts handouts;
    private final SellLink sell;
    private final CombatStatus combat;
    private final RecentPurchases recent;

    PurchaseFlow(Services services, Setting<ShopSettings> settings, ShopItems items, ClaimHandouts handouts, SellLink sell,
                 CombatStatus combat, RecentPurchases recent) {
        this.services = services;
        this.settings = settings;
        this.items = items;
        this.handouts = handouts;
        this.sell = sell;
        this.combat = combat;
        this.recent = recent;
    }

    /** True (after telling the player) when combat keeps them out of the shop. */
    boolean blocked(Player player) {
        if (this.settings.get().blockInCombat() && this.combat.tagged(player.getUniqueId())) {
            this.services.messenger().send(player, ShopMessages.IN_COMBAT,
                Arg.time("time", this.combat.remaining(player.getUniqueId())));
            return true;
        }
        return false;
    }

    /** A dialog press while tagged: closes the dialog and says why. */
    private boolean blocked(Submission s) {
        if (blocked(s.player())) {
            s.close();
            return true;
        }
        return false;
    }

    /** Opens the purchase dialog of an entry. {@code back} returns to where the player came from. */
    void open(Player player, String ref, Runnable back) {
        open(player, ref, back, 0);
    }

    /** Opens the purchase dialog starting at {@code amount} (0 for the usual stack; capped at the entry's limit). */
    void open(Player player, String ref, Runnable back, int amount) {
        if (blocked(player)) {
            return;
        }
        ShopSettings.Entry entry = this.settings.get().entry(ref);
        if (entry == null) {
            this.services.messenger().send(player, ShopMessages.NO_LONGER_SOLD);
            return;
        }
        Optional<ItemStack> unit = this.items.unit(entry);
        if (unit.isEmpty()) {
            this.services.messenger().send(player, ShopMessages.UNAVAILABLE);
            return;
        }
        int start = amount > 0 ? Math.min(amount, entry.max())
            : PurchaseMath.defaultAmount(entry.spawner() ? 1 : unit.get().getMaxStackSize(), entry.max());
        this.services.dialogs().show(player, view(player, entry, unit.get(), start, null, back));
    }

    private View view(Player player, ShopSettings.Entry entry, ItemStack unit, int amount, Component note, Runnable back) {
        Lang lang = this.services.lang();
        long total = PurchaseMath.total(entry.price(), amount, this.services.money().get().maxAmount()).orElse(0);
        long balance = this.services.ledger().balance(player.getUniqueId(), Currency.MONEY);
        List<Body> body = new ArrayList<>();
        body.add(Body.item(unit, null));
        body.add(Body.text(Component.join(JoinConfiguration.newlines(), lang.lines(ShopMessages.BUY_BODY,
            Arg.money("price", entry.price()), Arg.money("balance", balance), Arg.number("max", entry.max())))));
        List<Component> extra = new ArrayList<>(2);
        if (!entry.spawner()) {
            long sellBack = this.sell.sellBack(player, entry.item());
            if (sellBack > 0) {
                extra.addAll(lang.lines(ShopMessages.BUY_SELLS_BACK, Arg.money("price", sellBack)));
            }
            extra.addAll(lang.lines(ShopMessages.BUY_YOU_HAVE, Arg.number("count", this.sell.carried(player, entry.item()))));
        }
        if (!extra.isEmpty()) {
            body.add(Body.text(Component.join(JoinConfiguration.newlines(), extra)));
        }
        if (note != null) {
            body.add(Body.text(note));
        }
        List<Input> inputs = new ArrayList<>(2);
        if (entry.max() > 1) {
            inputs.add(Templates.range(AMOUNT, lang.get(ShopMessages.BUY_AMOUNT), 1, entry.max(), 1, amount));
            inputs.add(Templates.text(EXACT, lang.get(ShopMessages.BUY_EXACT), "", 9));
        }
        String ref = entry.ref();
        long price = entry.price();
        Button buy = Button.of(lang.get(ShopMessages.BUY_BUTTON, Arg.text("amount", Lang.number(amount)),
            Arg.text("total", this.services.money().get().format(total))), s -> onBuy(s, ref, price, amount, back)).width(150);
        Button backButton = Button.of(lang.get(CoreMessages.UI_BACK), s -> {
            s.close();
            back.run();
        }).width(150);
        List<Button> buttons = new ArrayList<>(4);
        if (entry.max() > 1) {
            buttons.add(Button.of(lang.get(ShopMessages.BUY_MAX), s -> onQuick(s, ref, price, false, back)).width(150));
            buttons.add(Button.of(lang.get(ShopMessages.BUY_FILL), s -> onQuick(s, ref, price, true, back)).width(150));
        }
        buttons.add(buy);
        buttons.add(backButton);
        return new View(View.Kind.FORM, lang.get(ShopMessages.BUY_TITLE, Arg.text("item", this.items.name(entry))), body,
            inputs, buttons, null, 2, true);
    }

    /**
     * "Max you can afford" or "Fill your inventory": works the amount out now (balance, free space, the limit) and
     * shows the dialog with it, so the Buy button names the new amount and total before anything is bought.
     */
    private void onQuick(Submission s, String ref, long price, boolean fill, Runnable back) {
        if (blocked(s)) {
            return;
        }
        ShopSettings.Entry entry = this.settings.get().entry(ref);
        Optional<ItemStack> unit = entry == null ? Optional.empty() : this.items.unit(entry);
        if (entry == null || unit.isEmpty()) {
            s.close();
            this.services.messenger().send(s.player(), entry == null ? ShopMessages.NO_LONGER_SOLD : ShopMessages.UNAVAILABLE);
            return;
        }
        Lang lang = this.services.lang();
        int current = Math.max(1, Math.min(entry.max(), (int) Math.max(1, s.values().number(AMOUNT))));
        if (entry.price() != price) {
            showError(s, entry, unit.get(), current, lang.get(ShopMessages.BUY_PRICE_CHANGED, Arg.money("price", entry.price())), back);
            return;
        }
        Player player = s.player();
        int amount;
        if (fill) {
            amount = PurchaseMath.fill(capacity(player, unit.get()), entry.max());
            if (amount == 0) {
                showError(s, entry, unit.get(), current, lang.get(ShopMessages.BUY_NO_ROOM), back);
                return;
            }
        } else {
            // Every entry's price x max fits the money limit (checked when the config is read).
            long balance = this.services.ledger().balance(player.getUniqueId(), Currency.MONEY);
            amount = PurchaseMath.affordable(balance, entry.price(), entry.max());
            if (amount == 0) {
                showError(s, entry, unit.get(), current, lang.get(ShopMessages.BUY_CANT_AFFORD, Arg.money("price", entry.price())), back);
                return;
            }
        }
        s.show(view(player, entry, unit.get(), amount, lang.get(ShopMessages.BUY_CHANGED), back));
    }

    /** The Buy button: buys what it said, or shows the new total when the player changed the amount. */
    private void onBuy(Submission s, String ref, long price, int amount, Runnable back) {
        if (blocked(s)) {
            return;
        }
        ShopSettings.Entry entry = this.settings.get().entry(ref);
        Optional<ItemStack> unit = entry == null ? Optional.empty() : this.items.unit(entry);
        if (entry == null || unit.isEmpty()) {
            s.close();
            this.services.messenger().send(s.player(), entry == null ? ShopMessages.NO_LONGER_SOLD : ShopMessages.UNAVAILABLE);
            return;
        }
        int max = entry.max();
        OptionalInt chosen = max > 1
            ? PurchaseMath.chosenAmount(s.values().number(AMOUNT), s.values().text(EXACT), max)
            : OptionalInt.of(1);
        Lang lang = this.services.lang();
        if (chosen.isEmpty()) {
            s.error(lang.get(ShopMessages.BUY_INVALID, Arg.number("max", max)));
            return;
        }
        int wanted = chosen.getAsInt();
        if (entry.price() != price) {
            showError(s, entry, unit.get(), wanted, lang.get(ShopMessages.BUY_PRICE_CHANGED, Arg.money("price", entry.price())), back);
            return;
        }
        if (wanted != amount) {
            s.show(view(s.player(), entry, unit.get(), wanted, lang.get(ShopMessages.BUY_CHANGED), back));
            return;
        }
        buy(s, entry, unit.get(), amount, false, back);
    }

    private View confirmView(Player player, ShopSettings.Entry entry, ItemStack unit, int amount, long total, long balance,
                             Runnable back) {
        Lang lang = this.services.lang();
        List<Body> body = List.of(Body.item(unit, null), Body.text(Component.join(JoinConfiguration.newlines(),
            lang.lines(ShopMessages.CONFIRM_BODY, Arg.number("amount", amount), Arg.text("item", this.items.name(entry)),
                Arg.money("total", total), Arg.money("left", Math.max(0, balance - total))))));
        String ref = entry.ref();
        long price = entry.price();
        return this.services.templates().confirmWithBody(lang.get(ShopMessages.CONFIRM_TITLE), body,
            lang.get(ShopMessages.CONFIRM_BUTTON), lang.get(CoreMessages.UI_BACK),
            s -> onConfirm(s, ref, price, amount, back),
            s -> s.show(view(s.player(), entry, unit, amount, null, back)));
    }

    private void onConfirm(Submission s, String ref, long price, int amount, Runnable back) {
        if (blocked(s)) {
            return;
        }
        ShopSettings.Entry entry = this.settings.get().entry(ref);
        Optional<ItemStack> unit = entry == null ? Optional.empty() : this.items.unit(entry);
        if (entry == null || unit.isEmpty()) {
            s.close();
            this.services.messenger().send(s.player(), entry == null ? ShopMessages.NO_LONGER_SOLD : ShopMessages.UNAVAILABLE);
            return;
        }
        if (entry.price() != price || amount > entry.max()) {
            int fixed = Math.min(amount, entry.max());
            showError(s, entry, unit.get(), fixed,
                this.services.lang().get(ShopMessages.BUY_PRICE_CHANGED, Arg.money("price", entry.price())), back);
            return;
        }
        buy(s, entry, unit.get(), amount, true, back);
    }

    private void buy(Submission s, ShopSettings.Entry entry, ItemStack unit, int amount, boolean confirmed, Runnable back) {
        Player player = s.player();
        UUID uuid = player.getUniqueId();
        Lang lang = this.services.lang();
        OptionalLong maybeTotal = PurchaseMath.total(entry.price(), amount, this.services.money().get().maxAmount());
        if (maybeTotal.isEmpty()) {
            showError(s, entry, unit, amount, lang.get(ShopMessages.BUY_TOO_EXPENSIVE), back);
            return;
        }
        long total = maybeTotal.getAsLong();
        long balance = this.services.ledger().balance(uuid, Currency.MONEY);
        if (balance < total) {
            showError(s, entry, unit, amount, lang.get(ShopMessages.BUY_NOT_ENOUGH, Arg.money("total", total),
                Arg.money("balance", balance)), back);
            return;
        }
        long confirmAbove = this.settings.get().confirmAbove();
        if (!confirmed && confirmAbove > 0 && total >= confirmAbove) {
            s.show(confirmView(player, entry, unit, amount, total, balance, back));
            return;
        }
        if (!this.services.ledger().available()) {
            s.close();
            this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            return;
        }
        if (!new ShopPurchaseEvent(player, entry.ref(), unit, amount, entry.price(), total).callEvent()) {
            s.close();
            this.services.messenger().send(player, ShopMessages.CANCELLED);
            return;
        }

        int[] split = PurchaseMath.split(amount, capacity(player, unit));
        int toInventory = split[0];
        int toClaimBox = split[1];
        String ref = entry.ref();
        // Every bought item goes into the claim box with the purchase: what fits the inventory now under a reference of
        // its own (claimed into the inventory once the purchase is stored), the rest under the entry's reference.
        String handRef = handRef();
        long price = entry.price();
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(uuid)
            .note(amount + " " + ShopItems.ledgerName(entry))
            .sink(uuid, Currency.MONEY, total, "shop_buy", ref)
            .check(() -> {
                ShopSettings.Entry now = this.settings.get().entry(ref);
                return now != null && now.price() == price ? null : "price_changed";
            });
        if (toInventory > 0) {
            this.services.deliveries().add(tx, uuid, SOURCE, handRef, unit.asQuantity(toInventory));
        }
        if (toClaimBox > 0) {
            this.services.deliveries().add(tx, uuid, SOURCE, ref, unit.asQuantity(toClaimBox));
        }
        this.recent.contribute(tx, uuid, ref, amount);
        TransactionResult result = this.services.ledger().execute(tx.build());
        switch (result.status()) {
            case SUCCESS -> {
                s.close();
                back.run();
                String name = this.items.name(entry);
                result.committed().whenComplete((ignored, error) -> {
                    if (error != null) {
                        // Storage failed: the money and the claim box items were rolled back, nothing is handed out.
                        this.services.messenger().send(player, ShopMessages.FAILED);
                        return;
                    }
                    // The buyer left or the server is stopping: the purchase waits in the claim box.
                    Handoffs.onEntity(this.services.scheduler(), player,
                        () -> deliver(player, name, amount, toInventory, toClaimBox, total, handRef), () -> { });
                });
            }
            case INSUFFICIENT_FUNDS -> showError(s, entry, unit, amount, lang.get(ShopMessages.BUY_NOT_ENOUGH,
                Arg.money("total", total), Arg.money("balance", this.services.ledger().balance(uuid, Currency.MONEY))), back);
            case REJECTED -> {
                ShopSettings.Entry now = this.settings.get().entry(ref);
                if (now == null) {
                    s.close();
                    this.services.messenger().send(player, ShopMessages.NO_LONGER_SOLD);
                } else {
                    showError(s, now, unit, Math.min(amount, now.max()),
                        lang.get(ShopMessages.BUY_PRICE_CHANGED, Arg.money("price", now.price())), back);
                }
            }
            case CANCELLED -> {
                s.close();
                this.services.messenger().send(player, ShopMessages.CANCELLED);
            }
            case UNAVAILABLE -> {
                s.close();
                this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            }
            case BALANCE_LIMIT -> {
                s.close();
                this.services.messenger().send(player, ShopMessages.FAILED);
            }
        }
    }

    /**
     * After the purchase is stored, on the buyer's thread: claims the part that fitted the inventory out of the claim
     * box into it (whatever no longer fits stays there) and tells the buyer.
     */
    private void deliver(Player player, String name, int amount, int toInventory, int toClaimBox, long total, String handRef) {
        if (toInventory <= 0) {
            bought(player, name, amount, toClaimBox, total);
            return;
        }
        this.handouts.claim(player, SOURCE, handRef).whenComplete((outcome, error) -> {
            int waiting = error != null || outcome.failed() ? amount : toClaimBox + outcome.left();
            bought(player, name, amount, waiting, total);
        });
    }

    private void bought(Player player, String name, int amount, long inClaimBox, long total) {
        if (inClaimBox > 0) {
            this.services.messenger().send(player, ShopMessages.BOUGHT_CLAIM_BOX, Arg.number("amount", amount),
                Arg.text("item", name), Arg.money("total", total), Arg.number("count", inClaimBox));
        } else {
            this.services.messenger().send(player, ShopMessages.BOUGHT, Arg.number("amount", amount), Arg.text("item", name),
                Arg.money("total", total));
        }
    }

    /** A claim box reference of its own for the part of one purchase that goes into the inventory. */
    static String handRef() {
        return "shop:" + UUID.randomUUID().toString().replace("-", "");
    }

    private void showError(Submission s, ShopSettings.Entry entry, ItemStack unit, int amount, Component error, Runnable back) {
        s.show(view(s.player(), entry, unit, amount, null, back).withError(error, FormValues.EMPTY));
        this.services.messenger().feedback(s.player(), Feedback.ERROR);
    }

    /** How many more of {@code unit} fit into the main inventory (not armor or the off hand). Player's thread. */
    private static long capacity(Player player, ItemStack unit) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int empty = 0;
        int[] partial = new int[contents.length];
        int partials = 0;
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                empty++;
            } else if (stack.isSimilar(unit)) {
                partial[partials++] = stack.getAmount();
            }
        }
        return PurchaseMath.capacity(Arrays.copyOf(partial, partials), empty, Math.max(1, unit.getMaxStackSize()));
    }
}
