package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The auction house dialogs: the sell form, the confirmations for listing, buying and taking down, and the history.
 * Each shows the item and the one question the player answers (the dialog style, {@code docs/development.md}): what a
 * button does, how long a listing runs and the slots used are in the buttons' tooltips, and the tax is only mentioned
 * while the auction house takes one.
 * <p>
 * Every flow ends with a screen change the client acts on: back into the menu it came from ({@code back}), another
 * dialog, or a full close. A bare {@code closeDialog} is not enough after a button press, because the vanilla client
 * then sits on its "waiting for response" screen, which only another screen or a container close replaces.
 */
final class AuctionDialogs {

    private final Services services;
    private final AuctionService service;
    private final Supplier<WorthLookup> worth;

    /** @param worth what /sell pays (bound once selling is built), for the low price warning */
    AuctionDialogs(Services services, AuctionService service, Supplier<WorthLookup> worth) {
        this.services = services;
        this.service = service;
        this.worth = worth;
    }

    private Lang lang() {
        return this.services.lang();
    }

    /** Ends a dialog flow: reopens the menu it came from, or closes every screen. Runs on the player's thread. */
    static void finish(Submission submission, Runnable back) {
        submission.close();
        if (back != null) {
            back.run();
        } else {
            submission.player().closeInventory();
        }
    }

    private void send(Player player, AuctionService.Problem problem) {
        this.services.messenger().send(player, problem.key(), problem.args());
    }

    private Body text(MessageKey key, Arg... args) {
        return Body.text(Component.join(JoinConfiguration.newlines(), lang().lines(key, args)));
    }

    private Button closeButton(Runnable back) {
        return Button.of(lang().get(back != null ? CoreMessages.UI_BACK : CoreMessages.UI_CANCEL), s -> finish(s, back)).closes();
    }

    /** A time for a lang text that colours it ({@code <accent><time></accent>}). */
    private static Arg time(Duration duration) {
        return Arg.text("time", Durations.format(duration));
    }

    /** A confirmation whose buttons carry tooltips: Yes then No, side by side, both finishing the flow. */
    private static View confirm(Component title, List<Body> body, Button yes, Button no) {
        return new View(View.Kind.CONFIRM, title, body, List.of(), List.of(yes.width(150), no.width(150)), null, 2, true).closing();
    }

    // ------------------------------------------------------------------ selling

    /** The sell form for the item in the player's hand: price and, for stacks, how many. */
    void sellForm(Player player, Runnable back) {
        if (!this.service.usable(player)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            this.services.messenger().send(player, AuctionMessages.SELL_NOTHING);
            return;
        }
        Lang lang = lang();
        List<Input> inputs = new ArrayList<>();
        inputs.add(Templates.text("price", lang.get(AuctionMessages.SELL_FORM_PRICE), "", 24));
        if (held.getAmount() > 1) {
            inputs.add(Templates.range("amount", lang.get(AuctionMessages.SELL_FORM_AMOUNT), 1, held.getAmount(), 1, held.getAmount()));
        }
        List<Button> buttons = List.of(
            Button.of(lang.get(AuctionMessages.SELL_FORM_BUTTON), lang.get(AuctionMessages.SELL_FORM_BUTTON_TOOLTIP),
                submission -> submitSellForm(submission, back)).width(150),
            closeButton(back).width(150));
        // The item (its tooltip shows it whole); the inputs say what to type.
        List<Body> body = List.of(Body.item(AuctionItems.revealed(held), null));
        this.services.dialogs().show(player, new View(View.Kind.FORM, lang.get(AuctionMessages.SELL_FORM_TITLE), body, inputs,
            buttons, null, 2, true));
    }

    private void submitSellForm(Submission submission, Runnable back) {
        Player player = submission.player();
        String priceText = submission.values().text("price");
        var parsed = this.services.money().get().parse(priceText);
        if (!parsed.ok()) {
            submission.error(lang().get(CoreMessages.INVALID_AMOUNT, Arg.text("input", priceText)));
            return;
        }
        int amount = submission.values().raw("amount").isPresent() ? (int) submission.values().number("amount") : 0;
        AuctionService.Preparation preparation = this.service.prepareSale(player, parsed.amount(), amount);
        switch (preparation) {
            case AuctionService.Problem problem -> submission.error(lang().get(problem.key(), problem.args()));
            case AuctionService.SaleDraft draft -> submission.show(sellConfirmView(player, draft, back));
        }
    }

    /** Shows the listing confirmation for a prepared draft (from /ah sell). */
    void sellConfirm(Player player, AuctionService.SaleDraft draft, Runnable back) {
        this.services.dialogs().show(player, sellConfirmView(player, draft, back));
    }

    private View sellConfirmView(Player player, AuctionService.SaleDraft draft, Runnable back) {
        AuctionSettings settings = this.service.settings();
        ItemStack listed = draft.listed();
        Lang lang = lang();
        List<Component> lines = new ArrayList<>(lang.lines(AuctionMessages.SELL_CONFIRM_BODY,
            Arg.number("amount", draft.amount()), Arg.text("item", AuctionItems.plainName(listed)), this.service.price("price", draft.price())));
        // No tax, no word about it: the seller simply gets the price.
        if (settings.taxBasisPoints() > 0) {
            long tax = AuctionMath.tax(draft.price(), settings.taxBasisPoints());
            lines.addAll(lang.lines(AuctionMessages.SELL_CONFIRM_TAX, this.service.price("tax", tax),
                Arg.text("rate", AuctionMath.formatPercent(settings.taxBasisPoints())), this.service.price("earn", draft.price() - tax)));
        }
        if (this.services.settings().get(player.getUniqueId(), AuctionFeature.PRICE_WARNING)) {
            lines.addAll(priceWarnings(player, draft, listed));
        }
        List<Component> tooltip = new ArrayList<>(lang.lines(AuctionMessages.SELL_CONFIRM_TOOLTIP, time(settings.duration())));
        if (draft.slotLimit() != Limits.UNLIMITED) {
            int used = this.service.engine().book().count(player.getUniqueId()) + 1;
            tooltip.addAll(lang.lines(AuctionMessages.SELL_CONFIRM_SLOTS, Arg.text("used", Lang.number(used)),
                Arg.text("limit", Lang.number(draft.slotLimit()))));
        }
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listed), null),
            Body.text(Component.join(JoinConfiguration.newlines(), lines)));
        Button yes = Button.of(lang.get(AuctionMessages.SELL_CONFIRM_BUTTON), Templates.lines(tooltip), s -> {
            AuctionService.Problem problem = this.service.confirmSale(s.player(), draft);
            if (problem != null) {
                send(s.player(), problem);
            }
            finish(s, back);
        });
        Button no = Button.of(lang.get(CoreMessages.UI_CANCEL), s -> {
            this.services.messenger().send(s.player(), AuctionMessages.SELL_CANCELLED);
            finish(s, back);
        });
        return confirm(lang.get(AuctionMessages.SELL_CONFIRM_TITLE), body, yes, no);
    }

    /**
     * The low price warning lines (none when the price is fine): below what /sell pays the player for the same items,
     * or less than half the price per item of the cheapest similar listing of another seller. They never block.
     */
    private List<Component> priceWarnings(Player player, AuctionService.SaleDraft draft, ItemStack listed) {
        List<Component> warnings = new ArrayList<>(2);
        WorthLookup lookup = this.worth.get();
        long sellValue;
        try {
            sellValue = lookup == null ? 0 : lookup.priceFor(player, listed);
        } catch (ArithmeticException e) {
            sellValue = 0;
        }
        if (PriceCheck.belowSell(draft.price(), sellValue)) {
            warnings.addAll(lang().lines(AuctionMessages.SELL_WARNING_SELL, this.service.price("worth", sellValue)));
        }
        String type = AuctionItems.typeKey(listed);
        List<Listing<ItemStack>> similar = new ArrayList<>();
        for (Listing<ItemStack> listing : this.service.engine().book().available(this.service.engine().now())) {
            if (!listing.seller().equals(player.getUniqueId()) && listing.typeKey().equals(type) && listing.item().isSimilar(listed)) {
                similar.add(listing);
            }
        }
        Listing<ItemStack> cheapest = PriceCheck.cheapest(similar);
        if (cheapest != null && PriceCheck.farBelow(draft.price(), draft.amount(), cheapest.price(), cheapest.amount())) {
            warnings.addAll(lang().lines(AuctionMessages.SELL_WARNING_MARKET,
                this.service.price("price", PriceCheck.each(cheapest.price(), cheapest.amount())),
                this.service.price("yours", PriceCheck.each(draft.price(), draft.amount()))));
        }
        return warnings;
    }

    // ------------------------------------------------------------------ buying and taking down

    /** The purchase confirmation: the item, its price and seller, the buyer's balance; the time left in the tooltip. */
    void buyConfirm(Player player, Listing<ItemStack> listing, Runnable back) {
        long now = this.service.engine().now();
        Lang lang = lang();
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listing.item()), null),
            text(AuctionMessages.BUY_BODY, Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
                this.service.price("price", listing.price()), Arg.text("seller", this.service.name(listing.seller())),
                this.service.price("balance", this.service.balance(player.getUniqueId()))));
        Button yes = Button.of(lang.get(AuctionMessages.BUY_BUTTON),
            lang.get(AuctionMessages.BUY_BUTTON_TOOLTIP, time(Duration.ofMillis(Math.max(0, listing.millisLeft(now))))), s -> {
                AuctionService.Problem problem = this.service.buy(s.player(), listing.id(), listing.price());
                if (problem != null) {
                    send(s.player(), problem);
                }
                finish(s, back);
            });
        Button no = Button.of(lang.get(CoreMessages.UI_CANCEL), s -> finish(s, back));
        this.services.dialogs().show(player, confirm(lang.get(AuctionMessages.BUY_TITLE), body, yes, no));
    }

    /** Confirmation for taking down one's own listing. */
    void cancelConfirm(Player player, Listing<ItemStack> listing, Runnable back) {
        Lang lang = lang();
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listing.item()), null),
            text(AuctionMessages.CANCEL_BODY, Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
                this.service.price("price", listing.price())));
        Button yes = Button.of(lang.get(AuctionMessages.CANCEL_BUTTON), lang.get(AuctionMessages.CANCEL_BUTTON_TOOLTIP), s -> {
            AuctionService.Problem problem = this.service.cancel(s.player(), listing.id());
            if (problem != null) {
                send(s.player(), problem);
            }
            finish(s, back);
        });
        Button no = Button.of(lang.get(AuctionMessages.CANCEL_KEEP), s -> finish(s, back));
        this.services.dialogs().show(player, confirm(lang.get(AuctionMessages.CANCEL_TITLE), body, yes, no));
    }

    /** Staff confirmation for removing someone's listing. */
    void removeConfirm(Player staff, Listing<ItemStack> listing, Runnable back) {
        Lang lang = lang();
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listing.item()), null),
            text(AuctionMessages.REMOVE_BODY, Arg.text("seller", this.service.name(listing.seller())), Arg.number("amount", listing.amount()),
                Arg.text("item", AuctionItems.plainName(listing.item())), this.service.price("price", listing.price())));
        Button yes = Button.of(lang.get(AuctionMessages.REMOVE_BUTTON), lang.get(AuctionMessages.REMOVE_BUTTON_TOOLTIP), s -> {
            if (s.player().hasPermission(AuctionService.PERMISSION_ADMIN)) {
                this.service.remove(s.player(), listing.id());
            } else {
                this.services.messenger().send(s.player(), CoreMessages.NO_PERMISSION);
            }
            finish(s, back);
        });
        Button no = Button.of(lang.get(CoreMessages.UI_BACK), s -> finish(s, back));
        this.services.dialogs().show(staff, confirm(lang.get(AuctionMessages.REMOVE_TITLE), body, yes, no));
    }

    // ------------------------------------------------------------------ history

    /** The player's latest sales and purchases, loaded off-thread. */
    void history(Player player, Runnable back) {
        this.services.messenger().send(player, CoreMessages.LOADING);
        int size = this.service.settings().historySize();
        this.service.engine().history(player.getUniqueId(), size).whenComplete((entries, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "Loading the auction history of " + player.getName() + " failed", error);
                this.services.messenger().send(player, CoreMessages.ACTION_FAILED);
                return;
            }
            Lang lang = lang();
            long now = System.currentTimeMillis();
            List<Body> body = new ArrayList<>();
            body.add(entries.isEmpty() ? text(AuctionMessages.HISTORY_EMPTY) : text(AuctionMessages.HISTORY_HEADER, Arg.number("count", size)));
            for (AuctionEngine.HistoryEntry<ItemStack> entry : entries) {
                String other = entry.counterparty() == null ? "-" : this.service.name(entry.counterparty());
                Component description = lang.get(entry.sale() ? AuctionMessages.HISTORY_SOLD : AuctionMessages.HISTORY_BOUGHT,
                    Arg.number("amount", entry.amount()), Arg.text("item", AuctionItems.plainName(entry.item())),
                    Arg.text("name", other), this.service.price("price", entry.price()),
                    Arg.time("ago", Duration.ofMillis(Math.max(0, now - entry.closedAt()))));
                body.add(Body.item(AuctionItems.revealed(entry.item()), description));
            }
            Button footer = Button.of(lang.get(back != null ? CoreMessages.UI_BACK : CoreMessages.UI_CLOSE), s -> finish(s, back))
                .width(Templates.WIDE).closes();
            this.services.dialogs().show(player, new View(View.Kind.LIST, lang.get(AuctionMessages.HISTORY_TITLE), body, List.of(),
                List.of(), footer, 1, true));
        });
    }
}
