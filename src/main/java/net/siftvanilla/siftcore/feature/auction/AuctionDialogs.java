package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
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
 * <p>
 * Every flow ends with a screen change the client acts on: back into the menu it came from ({@code back}), another
 * dialog, or a full close. A bare {@code closeDialog} is not enough after a button press, because the vanilla client
 * then sits on its "waiting for response" screen, which only another screen or a container close replaces.
 */
final class AuctionDialogs {

    private final Services services;
    private final AuctionService service;

    AuctionDialogs(Services services, AuctionService service) {
        this.services = services;
        this.service = service;
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
        return Button.of(lang().get(back != null ? CoreMessages.UI_BACK : CoreMessages.UI_CANCEL), s -> finish(s, back));
    }

    private static Arg time(long millis) {
        return Arg.time("time", Duration.ofMillis(Math.max(0, millis)));
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
            Button.of(lang.get(AuctionMessages.SELL_FORM_BUTTON), submission -> submitSellForm(submission, back)).width(150),
            closeButton(back).width(150));
        List<Body> body = List.of(Body.item(AuctionItems.revealed(held), null),
            text(AuctionMessages.SELL_FORM_BODY, Arg.number("amount", held.getAmount()), Arg.text("item", AuctionItems.plainName(held))));
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
        long tax = AuctionMath.tax(draft.price(), settings.taxBasisPoints());
        List<Component> lines = new ArrayList<>(lang().lines(AuctionMessages.SELL_CONFIRM_BODY,
            Arg.number("amount", draft.amount()), Arg.text("item", AuctionItems.plainName(listed)),
            this.service.price("price", draft.price()), this.service.price("tax", tax),
            Arg.text("rate", AuctionMath.formatPercent(settings.taxBasisPoints())),
            this.service.price("earn", draft.price() - tax), Arg.time("time", settings.duration())));
        if (draft.slotLimit() != Limits.UNLIMITED) {
            int used = this.service.engine().book().count(player.getUniqueId()) + 1;
            lines.addAll(lang().lines(AuctionMessages.SELL_CONFIRM_SLOTS, Arg.number("used", used), Arg.number("limit", draft.slotLimit())));
        }
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listed), null),
            Body.text(Component.join(JoinConfiguration.newlines(), lines)));
        return this.services.templates().confirmWithBody(lang().get(AuctionMessages.SELL_CONFIRM_TITLE), body,
            lang().get(AuctionMessages.SELL_CONFIRM_BUTTON), lang().get(CoreMessages.UI_CANCEL),
            yes -> {
                AuctionService.Problem problem = this.service.confirmSale(yes.player(), draft);
                if (problem != null) {
                    send(yes.player(), problem);
                }
                finish(yes, back);
            },
            no -> {
                this.services.messenger().send(no.player(), AuctionMessages.SELL_CANCELLED);
                finish(no, back);
            });
    }

    // ------------------------------------------------------------------ buying and taking down

    /** The purchase confirmation: the item, its price, seller and time left. */
    void buyConfirm(Player player, Listing<ItemStack> listing, Runnable back) {
        long now = this.service.engine().now();
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listing.item()), null),
            text(AuctionMessages.BUY_BODY, Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
                this.service.price("price", listing.price()), Arg.text("seller", this.service.name(listing.seller())),
                time(listing.millisLeft(now)), this.service.price("balance", this.service.balance(player.getUniqueId()))));
        this.services.dialogs().show(player, this.services.templates().confirmWithBody(lang().get(AuctionMessages.BUY_TITLE), body,
            lang().get(AuctionMessages.BUY_BUTTON), lang().get(CoreMessages.UI_CANCEL),
            yes -> {
                AuctionService.Problem problem = this.service.buy(yes.player(), listing.id(), listing.price());
                if (problem != null) {
                    send(yes.player(), problem);
                }
                finish(yes, back);
            },
            no -> finish(no, back)));
    }

    /** Confirmation for taking down one's own listing. */
    void cancelConfirm(Player player, Listing<ItemStack> listing, Runnable back) {
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listing.item()), null),
            text(AuctionMessages.CANCEL_BODY, Arg.number("amount", listing.amount()), Arg.text("item", AuctionItems.plainName(listing.item())),
                this.service.price("price", listing.price())));
        this.services.dialogs().show(player, this.services.templates().confirmWithBody(lang().get(AuctionMessages.CANCEL_TITLE), body,
            lang().get(AuctionMessages.CANCEL_BUTTON), lang().get(AuctionMessages.CANCEL_KEEP),
            yes -> {
                AuctionService.Problem problem = this.service.cancel(yes.player(), listing.id());
                if (problem != null) {
                    send(yes.player(), problem);
                }
                finish(yes, back);
            },
            no -> finish(no, back)));
    }

    /** Staff confirmation for removing someone's listing. */
    void removeConfirm(Player staff, Listing<ItemStack> listing, Runnable back) {
        List<Body> body = List.of(Body.item(AuctionItems.revealed(listing.item()), null),
            text(AuctionMessages.REMOVE_BODY, Arg.text("seller", this.service.name(listing.seller())), Arg.number("amount", listing.amount()),
                Arg.text("item", AuctionItems.plainName(listing.item())), this.service.price("price", listing.price())));
        this.services.dialogs().show(staff, this.services.templates().confirmWithBody(lang().get(AuctionMessages.REMOVE_TITLE), body,
            lang().get(AuctionMessages.REMOVE_BUTTON), lang().get(CoreMessages.UI_BACK),
            yes -> {
                if (yes.player().hasPermission(AuctionService.PERMISSION_ADMIN)) {
                    this.service.remove(yes.player(), listing.id());
                } else {
                    this.services.messenger().send(yes.player(), CoreMessages.NO_PERMISSION);
                }
                finish(yes, back);
            },
            no -> finish(no, back)));
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
            body.add(text(entries.isEmpty() ? AuctionMessages.HISTORY_EMPTY : AuctionMessages.HISTORY_HEADER));
            for (AuctionEngine.HistoryEntry<ItemStack> entry : entries) {
                String other = entry.counterparty() == null ? "-" : this.service.name(entry.counterparty());
                Component description = lang.get(entry.sale() ? AuctionMessages.HISTORY_SOLD : AuctionMessages.HISTORY_BOUGHT,
                    Arg.number("amount", entry.amount()), Arg.text("item", AuctionItems.plainName(entry.item())),
                    Arg.text("name", other), this.service.price("price", entry.price()),
                    Arg.time("ago", Duration.ofMillis(Math.max(0, now - entry.closedAt()))));
                body.add(Body.item(AuctionItems.revealed(entry.item()), description));
            }
            Button footer = Button.of(lang.get(back != null ? CoreMessages.UI_BACK : CoreMessages.UI_CLOSE), s -> finish(s, back))
                .width(Templates.WIDE);
            this.services.dialogs().show(player, new View(View.Kind.LIST, lang.get(AuctionMessages.HISTORY_TITLE), body, List.of(),
                List.of(), footer, 1, true));
        });
    }
}
