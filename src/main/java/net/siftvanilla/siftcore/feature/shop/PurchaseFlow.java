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
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.feature.sell.ItemHandout;
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
 * The money is taken in one ledger transaction that also puts whatever won't fit in the inventory into the claim
 * box; the rest is handed out only after that transaction is stored.
 */
final class PurchaseFlow {

    private static final String AMOUNT = "amount";
    private static final String EXACT = "exact";

    private final Services services;
    private final Setting<ShopSettings> settings;
    private final ShopItems items;
    private final ItemHandout handout;

    PurchaseFlow(Services services, Setting<ShopSettings> settings, ShopItems items, ItemHandout handout) {
        this.services = services;
        this.settings = settings;
        this.items = items;
        this.handout = handout;
    }

    /** Opens the purchase dialog of an entry. {@code back} returns to where the player came from. */
    void open(Player player, String ref, Runnable back) {
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
        int amount = PurchaseMath.defaultAmount(entry.spawner() ? 1 : unit.get().getMaxStackSize(), entry.max());
        this.services.dialogs().show(player, view(player, entry, unit.get(), amount, null, back));
    }

    private View view(Player player, ShopSettings.Entry entry, ItemStack unit, int amount, Component note, Runnable back) {
        Lang lang = this.services.lang();
        long total = PurchaseMath.total(entry.price(), amount, this.services.money().get().maxAmount()).orElse(0);
        long balance = this.services.ledger().balance(player.getUniqueId(), Currency.MONEY);
        List<Body> body = new ArrayList<>();
        body.add(Body.item(unit, null));
        body.add(Body.text(Component.join(JoinConfiguration.newlines(), lang.lines(ShopMessages.BUY_BODY,
            Arg.money("price", entry.price()), Arg.money("balance", balance), Arg.number("max", entry.max())))));
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
        return new View(View.Kind.FORM, lang.get(ShopMessages.BUY_TITLE, Arg.text("item", this.items.name(entry))), body,
            inputs, List.of(buy, backButton), null, 2, true);
    }

    /** The Buy button: buys what it said, or shows the new total when the player changed the amount. */
    private void onBuy(Submission s, String ref, long price, int amount, Runnable back) {
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
        long price = entry.price();
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(uuid)
            .note(amount + " " + ShopItems.ledgerName(entry))
            .sink(uuid, Currency.MONEY, total, "shop_buy", ref)
            .check(() -> {
                ShopSettings.Entry now = this.settings.get().entry(ref);
                return now != null && now.price() == price ? null : "price_changed";
            });
        if (toClaimBox > 0) {
            this.services.deliveries().add(tx, uuid, "shop", ref, unit.asQuantity(toClaimBox));
        }
        TransactionResult result = this.services.ledger().execute(tx.build());
        switch (result.status()) {
            case SUCCESS -> {
                s.close();
                back.run();
                String name = this.items.name(entry);
                result.committed().whenComplete((ignored, error) -> {
                    if (error != null) {
                        // Storage failed: the money and the claim box part were rolled back, nothing is handed out.
                        this.services.messenger().send(player, ShopMessages.FAILED);
                        return;
                    }
                    this.services.scheduler().entity(player,
                        () -> deliver(player, name, unit, amount, toInventory, toClaimBox, total, ref),
                        () -> {
                            if (toInventory > 0) {
                                this.handout.toClaimBox(uuid, List.of(unit.asQuantity(toInventory)), "shop", ref);
                            }
                        });
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

    /** Hands out the stored purchase on the buyer's thread; what no longer fits goes to the claim box too. */
    private void deliver(Player player, String name, ItemStack unit, int amount, int toInventory, int toClaimBox, long total,
                         String ref) {
        long claimed = toClaimBox;
        if (toInventory > 0) {
            claimed += this.handout.give(player, List.of(unit.asQuantity(toInventory)), "shop", ref);
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            player.saveData();
        }
        if (claimed > 0) {
            this.services.messenger().send(player, ShopMessages.BOUGHT_CLAIM_BOX, Arg.number("amount", amount),
                Arg.text("item", name), Arg.money("total", total), Arg.number("count", claimed));
        } else {
            this.services.messenger().send(player, ShopMessages.BOUGHT, Arg.number("amount", amount), Arg.text("item", name),
                Arg.money("total", total));
        }
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
