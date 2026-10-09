package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionType;

/**
 * The orders dialogs: the new-order form and its confirmation, the variant lists (enchantment and level, potion,
 * spawner), the owner's order dialog with collecting, changing, extending, cancelling and details, quick deliver, and
 * the staff dialog with a cancel that needs a reason.
 * <p>
 * Every flow ends with a screen change the client acts on: back into the menu or dialog it came from, another dialog,
 * or a full close. Every button re-checks what it acts on at click time; what a dialog showed is never trusted.
 */
final class OrderDialogs {

    private final Services services;
    private final OrderService service;
    private final OrderMenus menus;
    private final CreateDrafts drafts;

    OrderDialogs(Services services, OrderService service, OrderMenus menus) {
        this.services = services;
        this.service = service;
        this.menus = menus;
        this.drafts = new CreateDrafts(System::currentTimeMillis);
    }

    CreateDrafts drafts() {
        return this.drafts;
    }

    private Lang lang() {
        return this.services.lang();
    }

    /** Ends a dialog flow: reopens the screen it came from, or closes every screen. Runs on the player's thread. */
    static void finish(Submission submission, Runnable back) {
        submission.close();
        if (back != null) {
            back.run();
        } else {
            submission.player().closeInventory();
        }
    }

    private void send(Player player, OrderService.Problem problem) {
        this.services.messenger().send(player, problem.key(), problem.args());
    }

    private Component message(OrderService.Problem problem) {
        return lang().get(problem.key(), problem.args());
    }

    private Body text(List<Component> lines) {
        return Body.text(Component.join(JoinConfiguration.newlines(), lines));
    }

    /** A plain button label (no colours of its own, as every dialog button). */
    private Component label(MessageKey key, Arg... args) {
        return Component.text(lang().plain(key, args));
    }

    private Arg item(String key) {
        return this.service.item("item", key);
    }

    private Button.Handler backTo(Runnable back) {
        return submission -> finish(submission, back);
    }

    // ------------------------------------------------------------------ the new-order form

    /**
     * Opens the new-order form with what the player typed before (kept for a while), or with the held item and its
     * suggested price for a fresh form.
     */
    void createForm(Player player, Runnable back) {
        if (!this.service.usable(player)) {
            return;
        }
        if (!player.hasPermission(OrderService.PERMISSION_CREATE)) {
            this.services.messenger().send(player, CoreMessages.NO_PERMISSION);
            return;
        }
        CreateDrafts.Draft draft = this.drafts.get(player.getUniqueId());
        if (draft == CreateDrafts.Draft.EMPTY) {
            String held = this.service.heldKey(player);
            if (held != null) {
                draft = draft.withItem(held, this.service.items().fieldText(held));
            }
        }
        this.services.dialogs().show(player, formView(player, draft, back));
    }

    /**
     * Opens the new-order form with the item set (the worth details' "Order it" button), keeping a typed quantity and
     * price. Tells the player and returns false when they can't order that item now.
     */
    boolean formFor(Player player, String key, Runnable back) {
        if (!this.service.usable(player)) {
            return false;
        }
        if (!player.hasPermission(OrderService.PERMISSION_CREATE)) {
            this.services.messenger().send(player, CoreMessages.NO_PERMISSION);
            return false;
        }
        if (key == null || !this.service.items().orderable(key)) {
            this.services.messenger().send(player, OrdersMessages.CREATE_BLOCKED, item(key == null ? "" : key));
            return false;
        }
        CreateDrafts.Draft draft = this.drafts.get(player.getUniqueId()).withItem(key, this.service.items().fieldText(key));
        this.drafts.put(player.getUniqueId(), draft);
        this.services.dialogs().show(player, formView(player, draft, back));
        return true;
    }

    private View formView(Player player, CreateDrafts.Draft draft, Runnable back) {
        Lang lang = lang();
        OrdersSettings s = this.service.settings();
        int limit = this.service.limit(player);
        int count = this.service.book().activeCount(player.getUniqueId());
        List<Body> body = new ArrayList<>();
        OrderItem chosen = draft.itemKey() == null ? null : this.service.items().resolve(draft.itemKey());
        if (chosen != null) {
            body.add(Body.item(chosen.prototype(), lang.get(OrdersMessages.CREATE_CHOSEN, Arg.component("item", chosen.name()))));
        }
        body.add(text(limit == Limits.UNLIMITED ? lang.lines(OrdersMessages.CREATE_BODY_UNLIMITED, Arg.number("count", count))
            : lang.lines(OrdersMessages.CREATE_BODY, Arg.number("count", count), Arg.number("limit", Math.max(0, limit)))));
        String price = draft.price();
        if (price.isEmpty() && draft.itemKey() != null) {
            long suggested = this.service.suggestedPrice(draft.itemKey());
            if (suggested > 0) {
                price = Long.toString(suggested);
            }
        }
        List<Input> inputs = List.of(
            Templates.text("item", lang.get(OrdersMessages.CREATE_ITEM), draft.itemText(), 64),
            Templates.text("quantity", lang.get(OrdersMessages.CREATE_QUANTITY, Arg.number("max", s.maxQuantity())), draft.quantity(),
                OrderInput.MAX_INPUT),
            Templates.text("price", lang.get(OrdersMessages.CREATE_PRICE), price, OrderInput.MAX_INPUT));
        CreateDrafts.Draft shown = draft;
        List<Button> buttons = List.of(
            Button.of(label(OrdersMessages.CREATE_NEXT), submission -> submitForm(submission, shown, back)).width(150),
            Button.of(label(OrdersMessages.CREATE_CHOOSE), submission -> choose(submission, shown, back)).width(150),
            Button.of(lang.get(back == null ? CoreMessages.UI_CANCEL : CoreMessages.UI_BACK), submission -> {
                this.drafts.forget(submission.player().getUniqueId());
                finish(submission, back);
            }).width(Templates.WIDE));
        return new View(View.Kind.FORM, lang.get(OrdersMessages.CREATE_TITLE), body, inputs, buttons, null, 2, true);
    }

    private CreateDrafts.Draft typed(Submission submission, CreateDrafts.Draft shown) {
        CreateDrafts.Draft typed = shown.withTyped(submission.values().text("item"), submission.values().text("quantity"),
            submission.values().text("price"));
        this.drafts.put(submission.player().getUniqueId(), typed);
        return typed;
    }

    private void submitForm(Submission submission, CreateDrafts.Draft shown, Runnable back) {
        Player player = submission.player();
        CreateDrafts.Draft typed = typed(submission, shown);
        OrderService.Prepared prepared = this.service.prepare(player, typed.itemText(), typed.itemKey(), typed.quantity(), typed.price());
        switch (prepared) {
            case OrderService.Problem problem -> submission.error(message(problem));
            case OrderService.Draft draft -> submission.show(confirmView(player, draft, back, () -> createForm(player, back)));
        }
    }

    private void choose(Submission submission, CreateDrafts.Draft shown, Runnable back) {
        typed(submission, shown);
        submission.close();
        this.menus.picker(submission.player(), back);
    }

    /** The picker chose a plain item (or a variant list chose one): back to the form with it set. */
    void chosen(Player player, String key, Runnable formBack) {
        CreateDrafts.Draft draft = this.drafts.get(player.getUniqueId()).withItem(key, this.service.items().fieldText(key));
        this.drafts.put(player.getUniqueId(), draft);
        this.services.dialogs().show(player, formView(player, draft, formBack));
    }

    /** Shows the confirmation of a prepared order straight away (from /orders create with every argument). */
    void confirm(Player player, OrderService.Draft draft) {
        this.services.dialogs().show(player, confirmView(player, draft, null, null));
    }

    /**
     * The confirmation before money is held: what is ordered at what price, the total held, how long the order runs,
     * what the server pays for the item, and a warning when /sell pays players more than the order would.
     */
    private View confirmView(Player player, OrderService.Draft draft, Runnable after, Runnable edit) {
        Lang lang = lang();
        OrdersSettings s = this.service.settings();
        OrderItem item = this.service.items().resolve(draft.key());
        List<Component> lines = new ArrayList<>(lang.lines(OrdersMessages.CONFIRM_BODY, Arg.number("quantity", draft.quantity()),
            item(draft.key()), this.service.exact("price", draft.priceEach()), this.service.exact("total", draft.total()),
            Arg.time("time", s.duration())));
        long worth = item == null ? 0 : this.service.worthEach(item);
        if (worth > 0) {
            lines.addAll(lang.lines(OrdersMessages.CONFIRM_WORTH, Arg.money("worth", worth), item(draft.key())));
            if (OrderMath.serverPaysMore(worth, this.service.highestMultiplier(), draft.priceEach(), s.taxBasisPoints())) {
                lines.addAll(lang.lines(OrdersMessages.CONFIRM_SELL_MORE));
            }
        }
        List<Body> body = new ArrayList<>();
        if (item != null) {
            body.add(Body.item(item.display(draft.quantity()), null));
        }
        body.add(text(lines));
        return this.services.templates().confirmWithBody(lang.get(OrdersMessages.CONFIRM_TITLE), body,
            label(OrdersMessages.CONFIRM_BUTTON), lang.get(CoreMessages.UI_BACK),
            yes -> {
                OrderService.Problem problem = this.service.create(yes.player(), draft);
                if (problem != null) {
                    send(yes.player(), problem);
                } else {
                    this.drafts.forget(yes.player().getUniqueId());
                }
                finish(yes, after);
            },
            no -> {
                if (edit != null) {
                    // The form replaces this dialog (when it can't open, the router closes this one).
                    edit.run();
                } else {
                    this.services.messenger().send(no.player(), OrdersMessages.CREATE_CANCELLED);
                    finish(no, after);
                }
            });
    }

    /** "Order again": the confirmation of a new order with the same item, quantity and price, after every check. */
    void orderAgain(Player player, Order old, Runnable back) {
        OrderService.Prepared prepared = this.service.check(player, old.key(), old.quantity(), old.priceEach());
        switch (prepared) {
            case OrderService.Problem problem -> send(player, problem);
            case OrderService.Draft draft -> this.services.dialogs().show(player, confirmView(player, draft, back, back));
        }
    }

    // ------------------------------------------------------------------ variant lists

    /**
     * The exact variants of a family the picker offered: enchantments (then levels) for books, base potion types for
     * potions, mobs for spawners.
     */
    void variants(Player player, String itemType, Runnable pickerBack, Runnable formBack) {
        OrderItems items = this.service.items();
        Lang lang = lang();
        List<Button> buttons = new ArrayList<>();
        MessageKey title;
        MessageKey bodyKey;
        if (OrderItems.ENCHANTED_BOOK.equals(itemType)) {
            title = OrdersMessages.ENCHANT_TITLE;
            bodyKey = OrdersMessages.ENCHANT_BODY;
            for (Enchantment enchantment : items.bookEnchantments()) {
                String key = enchantment.getKey().asString();
                buttons.add(Button.of(Component.text(items.enchantmentName(key)), submission -> {
                    if (enchantment.getMaxLevel() <= 1) {
                        chosenFromList(submission, OrderKeys.key(itemType, new Variant.Enchant(key, 1).id()), formBack);
                    } else {
                        submission.show(levels(enchantment, pickerBack, formBack));
                    }
                }).width(100));
            }
        } else if (OrderItems.POTIONS.contains(itemType)) {
            title = OrdersMessages.POTION_TITLE;
            bodyKey = OrdersMessages.POTION_BODY;
            for (PotionType type : items.potionTypes()) {
                String key = OrderKeys.key(itemType, new Variant.Potion(type.getKey().asString()).id());
                OrderItem item = items.resolve(key);
                if (item == null) {
                    continue;
                }
                buttons.add(Button.of(Component.text(item.plainName()), submission -> chosenFromList(submission, key, formBack)).width(100));
            }
        } else if (OrderItems.SPAWNER.equals(itemType)) {
            title = OrdersMessages.SPAWNER_TITLE;
            bodyKey = OrdersMessages.SPAWNER_BODY;
            for (String mob : items.spawnerMobs()) {
                String key = OrderKeys.key(itemType, new Variant.Spawner(mob).id());
                OrderItem item = items.resolve(key);
                if (item == null) {
                    continue;
                }
                buttons.add(Button.of(Component.text(item.plainName()), submission -> chosenFromList(submission, key, formBack)).width(100));
            }
        } else {
            return;
        }
        if (buttons.isEmpty()) {
            this.services.messenger().send(player, OrdersMessages.CREATE_BLOCKED, Arg.component("item", items.name(itemType)));
            return;
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(title), lang.lines(bodyKey), buttons, 3,
            submission -> {
                submission.close();
                pickerBack.run();
            }));
    }

    private View levels(Enchantment enchantment, Runnable pickerBack, Runnable formBack) {
        OrderItems items = this.service.items();
        String key = enchantment.getKey().asString();
        List<Button> buttons = new ArrayList<>();
        for (int level = 1; level <= enchantment.getMaxLevel(); level++) {
            String orderKey = OrderKeys.key(OrderItems.ENCHANTED_BOOK, new Variant.Enchant(key, level).id());
            OrderItem item = items.resolve(orderKey);
            if (item == null) {
                continue;
            }
            buttons.add(Button.of(Component.text(items.enchantmentName(key) + " " + Variant.roman(level)),
                submission -> chosenFromList(submission, orderKey, formBack)).width(100));
        }
        return this.services.templates().list(lang().get(OrdersMessages.LEVEL_TITLE),
            lang().lines(OrdersMessages.LEVEL_BODY, Arg.text("item", items.enchantmentName(key))), buttons, 3, submission -> {
                submission.close();
                pickerBack.run();
            });
    }

    private void chosenFromList(Submission submission, String key, Runnable formBack) {
        Player player = submission.player();
        CreateDrafts.Draft draft = this.drafts.get(player.getUniqueId()).withItem(key, this.service.items().fieldText(key));
        this.drafts.put(player.getUniqueId(), draft);
        submission.show(formView(player, draft, formBack));
    }

    // ------------------------------------------------------------------ the owner's dialog

    /** The owner's dialog of one of their orders, read fresh from the book. */
    void own(Player player, Order seen, Runnable back) {
        Order order = this.service.book().get(seen.id());
        if (order == null || !order.owner().equals(player.getUniqueId())) {
            this.services.messenger().send(player, OrdersMessages.CANCEL_GONE);
            return;
        }
        this.services.dialogs().show(player, ownView(player, order, back));
    }

    private View ownView(Player player, Order order, Runnable back) {
        Lang lang = lang();
        OrderItem item = this.service.items().of(order);
        long now = this.service.engine().now();
        List<Component> lines = order.active()
            ? new ArrayList<>(lang.lines(OrdersMessages.OWN_BODY, Arg.number("quantity", order.quantity()), item(order.key()),
                Arg.money("price", order.priceEach()), Arg.number("filled", order.filled()), Arg.number("waiting", order.waiting()),
                Arg.money("held", order.escrow()), Arg.time("time", Duration.ofMillis(order.millisLeft(now)))))
            : new ArrayList<>(lang.lines(OrdersMessages.OWN_BODY_ENDED, Arg.number("quantity", order.quantity()), item(order.key()),
                Arg.money("price", order.priceEach()), Arg.number("filled", order.filled()), Arg.number("waiting", order.waiting()),
                Arg.component("state", lang.get(this.service.stateLabel(order.state())))));
        if (item == null) {
            lines.addAll(lang.lines(OrdersMessages.OWN_UNAVAILABLE));
        }
        List<Body> body = new ArrayList<>();
        if (item != null) {
            body.add(Body.item(item.prototype(), null));
        }
        body.add(text(lines));
        long id = order.id();
        List<Button> buttons = new ArrayList<>();
        if (order.waiting() > 0 && item != null) {
            buttons.add(Button.of(label(OrdersMessages.OWN_COLLECT), s -> collect(s, id, OrderService.CollectMode.FITS, back)));
            if (order.waiting() > item.maxStack()) {
                buttons.add(Button.of(label(OrdersMessages.OWN_COLLECT_STACK), s -> collect(s, id, OrderService.CollectMode.ONE_STACK, back)));
            }
            buttons.add(Button.of(label(OrdersMessages.OWN_TO_CLAIM_BOX), s -> collect(s, id, OrderService.CollectMode.CLAIM_REST, back)));
        }
        if (order.active()) {
            buttons.add(Button.of(label(OrdersMessages.OWN_RAISE), s -> s.show(editView(s.player(), order, back))));
            buttons.add(Button.of(label(OrdersMessages.OWN_ADD), s -> s.show(editView(s.player(), order, back))));
            if (this.service.settings().extendEnabled() && this.service.extendedEnd(order) > 0) {
                buttons.add(Button.of(label(OrdersMessages.OWN_EXTEND), s -> {
                    OrderService.Problem problem = this.service.extend(s.player(), id);
                    if (problem != null) {
                        send(s.player(), problem);
                    }
                    reshowOwn(s, id, back);
                }));
            }
            buttons.add(Button.of(label(OrdersMessages.OWN_CANCEL), s -> s.show(cancelView(order, back))));
        } else if (item != null && this.service.items().orderable(order.key())) {
            // The confirmation replaces this dialog; after a refusal (action bar) the router closes it.
            buttons.add(Button.of(label(OrdersMessages.OWN_AGAIN), s -> orderAgain(s.player(), order, () -> own(s.player(), order, back))));
        }
        buttons.add(Button.of(label(OrdersMessages.OWN_DETAILS), s -> details(s, order, back)));
        return this.services.templates().listWithBody(lang.get(OrdersMessages.OWN_TITLE), body, buttons, 2,
            back == null ? null : backTo(back));
    }

    /** Shows the owner's dialog again with fresh numbers (or goes back when the order closed). */
    private void reshowOwn(Submission submission, long id, Runnable back) {
        Order fresh = this.service.book().get(id);
        if (fresh == null || !fresh.owner().equals(submission.player().getUniqueId())) {
            finish(submission, back);
            return;
        }
        submission.show(ownView(submission.player(), fresh, back));
    }

    private void collect(Submission submission, long id, OrderService.CollectMode mode, Runnable back) {
        OrderService.Problem problem = this.service.collect(submission.player(), id, mode);
        if (problem != null) {
            send(submission.player(), problem);
        }
        reshowOwn(submission, id, back);
    }

    private View cancelView(Order order, Runnable back) {
        Lang lang = lang();
        Order current = this.service.book().get(order.id());
        long refund = current == null ? 0 : current.escrow();
        return this.services.templates().confirm(lang.get(OrdersMessages.CANCEL_TITLE),
            lang.lines(OrdersMessages.CANCEL_BODY, Arg.number("quantity", order.quantity()), item(order.key()), this.service.exact("refund", refund)),
            label(OrdersMessages.CANCEL_YES), label(OrdersMessages.CANCEL_NO),
            yes -> {
                OrderService.Problem problem = this.service.cancel(yes.player(), order.id());
                if (problem != null) {
                    send(yes.player(), problem);
                }
                finish(yes, back);
            },
            no -> {
                this.services.messenger().send(no.player(), OrdersMessages.CANCEL_KEPT);
                reshowOwn(no, order.id(), back);
            });
    }

    /**
     * The details of an order, after two reads. The clicked dialog stays on screen until the details replace it
     * (marked as shown, so the router does not close it while the reads run); a failed read closes it.
     */
    private void details(Submission submission, Order order, Runnable back) {
        Player player = submission.player();
        this.services.dialogs().markShown(player);
        this.service.store().fills(order.id(), 8).thenCombine(this.service.store().paidOut(order.id()), (fills, paid) -> {
            this.services.scheduler().entity(player, () -> this.services.dialogs().show(player, detailsView(order, fills, paid, back)), null);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "Loading the details of order " + order.id() + " failed", error);
                this.services.messenger().send(player, CoreMessages.ACTION_FAILED);
                this.services.dialogs().close(player);
            }
        });
    }

    private View detailsView(Order order, List<OrderStore.Fill> fills, long paid, Runnable back) {
        Lang lang = lang();
        long now = System.currentTimeMillis();
        Order current = this.service.book().get(order.id());
        Order shown = current == null ? order : current;
        List<Component> lines = new ArrayList<>(lang.lines(OrdersMessages.DETAILS_BODY, Arg.text("id", Long.toString(shown.id())),
            Arg.time("ago", Duration.ofMillis(Math.max(0, now - shown.created()))), Arg.money("paid", paid),
            Arg.number("collected", shown.collected())));
        lines.add(Component.empty());
        lines.addAll(fillLines(fills, now));
        return this.services.templates().list(lang.get(OrdersMessages.DETAILS_TITLE), lines, List.of(), 1, submission -> {
            Order fresh = this.service.book().get(order.id());
            if (fresh != null && fresh.owner().equals(submission.player().getUniqueId())) {
                submission.show(ownView(submission.player(), fresh, back));
            } else {
                finish(submission, back);
            }
        });
    }

    private List<Component> fillLines(List<OrderStore.Fill> fills, long now) {
        Lang lang = lang();
        List<Component> lines = new ArrayList<>();
        if (fills.isEmpty()) {
            lines.addAll(lang.lines(OrdersMessages.DETAILS_NONE));
            return lines;
        }
        lines.addAll(lang.lines(OrdersMessages.DETAILS_HEADER));
        for (OrderStore.Fill fill : fills) {
            lines.addAll(lang.lines(OrdersMessages.DETAILS_LINE, Arg.text("name", this.service.name(fill.seller())),
                Arg.number("amount", fill.quantity()), Arg.money("paid", fill.paid()),
                Arg.time("ago", Duration.ofMillis(Math.max(0, now - fill.timestamp())))));
        }
        return lines;
    }

    // ------------------------------------------------------------------ changing an order

    private View editView(Player player, Order order, Runnable back) {
        Lang lang = lang();
        OrdersSettings s = this.service.settings();
        int room = Math.max(0, s.maxQuantity() - order.quantity());
        List<Input> inputs = List.of(
            Templates.text("price", lang.get(OrdersMessages.EDIT_PRICE), Long.toString(order.priceEach()), OrderInput.MAX_INPUT),
            Templates.text("add", lang.get(OrdersMessages.EDIT_ADD, Arg.number("max", room)), "0", OrderInput.MAX_INPUT));
        List<Component> lines = lang.lines(OrdersMessages.EDIT_BODY, Arg.number("quantity", order.quantity()), item(order.key()),
            Arg.money("price", order.priceEach()), Arg.number("filled", order.filled()));
        return this.services.templates().form(lang.get(OrdersMessages.EDIT_TITLE), lines, inputs, label(OrdersMessages.CREATE_NEXT),
            submission -> submitEdit(submission, order.id(), back),
            submission -> reshowOwn(submission, order.id(), back));
    }

    private void submitEdit(Submission submission, long id, Runnable back) {
        Player player = submission.player();
        Order order = this.service.book().get(id);
        OrderService.Prepared prepared = this.service.prepareEdit(player, order, submission.values().text("price"),
            submission.values().text("add"));
        switch (prepared) {
            case OrderService.Problem problem -> submission.error(message(problem));
            case OrderService.Draft terms -> {
                OrderEngine.Seen seen = OrderEngine.Seen.of(order);
                Lang lang = lang();
                submission.show(this.services.templates().confirm(lang.get(OrdersMessages.EDIT_TITLE),
                    lang.lines(OrdersMessages.EDIT_CONFIRM_BODY, this.service.exact("extra", terms.total()),
                        Arg.number("quantity", terms.quantity()), item(order.key()), Arg.money("price", terms.priceEach())),
                    label(OrdersMessages.EDIT_CONFIRM_BUTTON), lang.get(CoreMessages.UI_BACK),
                    yes -> {
                        OrderService.Problem problem = this.service.edit(yes.player(), id, seen, terms);
                        if (problem != null) {
                            send(yes.player(), problem);
                        }
                        reshowOwn(yes, id, back);
                    },
                    no -> {
                        Order fresh = this.service.book().get(id);
                        if (fresh == null) {
                            finish(no, back);
                        } else {
                            no.show(editView(no.player(), fresh, back));
                        }
                    }));
            }
        }
    }

    // ------------------------------------------------------------------ quick deliver

    /** Quick deliver: what the player carries for the order and what they get, delivered from the inventory. */
    void quick(Player player, Order order, Runnable back) {
        if (!this.service.usable(player)) {
            return;
        }
        OrderService.Problem problem = this.service.deliverable(player, order, order.priceEach());
        if (problem != null) {
            send(player, problem);
            return;
        }
        OrderItem item = this.service.items().of(order);
        this.services.dialogs().show(player, quickView(order, item, this.service.quickView(player, order, item), back, false));
    }

    private View quickView(Order order, OrderItem item, OrderService.QuickView view, Runnable back, boolean changed) {
        Lang lang = lang();
        Arg itemArg = Arg.component("item", item.name());
        List<Component> lines = new ArrayList<>();
        if (view.carried() > 0) {
            lines.addAll(lang.lines(OrdersMessages.QUICK_CARRY, Arg.number("count", view.carried()), itemArg, Arg.number("inner", view.inner())));
        } else {
            lines.addAll(lang.lines(OrdersMessages.QUICK_NOTHING, itemArg));
        }
        lines.addAll(lang.lines(OrdersMessages.QUICK_WANTED, Arg.number("remaining", view.remaining())));
        if (view.units() > 0) {
            lines.addAll(lang.lines(OrdersMessages.QUICK_PAYOUT, Arg.money("payout", view.payout()), Arg.money("tax", view.tax())));
        }
        if (view.serverPaysMore() && view.serverEach() > 0) {
            lines.addAll(lang.lines(OrdersMessages.QUICK_SERVER_MORE, Arg.money("price", view.serverEach())));
        }
        List<Body> body = new ArrayList<>();
        body.add(Body.item(item.prototype(), null));
        body.add(text(lines));
        if (changed) {
            body.add(Body.error(lang.get(OrdersMessages.QUICK_CHANGED)));
        }
        List<Button> buttons = new ArrayList<>();
        if (view.units() > 0) {
            buttons.add(Button.of(label(OrdersMessages.QUICK_BUTTON, Arg.number("units", view.units()),
                Arg.money("payout", view.payout())), s -> {
                    OrderService.QuickOutcome outcome = this.service.quickDeliver(s.player(), view);
                    switch (outcome) {
                        case null -> finish(s, back);
                        case OrderService.QuickChanged fresh -> s.show(quickView(order, item, fresh.fresh(), back, true));
                        case OrderService.Problem problem -> {
                            send(s.player(), problem);
                            finish(s, back);
                        }
                    }
                }).width(Templates.WIDE));
        }
        buttons.add(Button.of(label(OrdersMessages.QUICK_MENU), s -> {
            s.close();
            Order current = this.service.book().get(order.id());
            if (current == null) {
                this.services.messenger().send(s.player(), OrdersMessages.DELIVER_GONE);
                return;
            }
            this.menus.delivery(s.player(), current, back);
        }).width(Templates.WIDE));
        return new View(View.Kind.LIST, Component.text(lang.plain(OrdersMessages.QUICK_TITLE, Arg.text("item", item.plainName()))),
            body, List.of(), buttons, Button.of(lang.get(CoreMessages.UI_BACK), backTo(back)).width(Templates.WIDE), 1, true);
    }

    // ------------------------------------------------------------------ staff

    /** The staff dialog of any order: who, what, how far, money held, latest deliveries, and staff actions. */
    void staff(Player staff, Order order, Runnable back) {
        if (!staff.hasPermission(OrderService.PERMISSION_ADMIN)) {
            this.services.messenger().send(staff, CoreMessages.NO_PERMISSION);
            return;
        }
        this.service.store().fills(order.id(), 5).whenComplete((fills, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "Loading the deliveries of order " + order.id() + " failed", error);
                this.services.messenger().send(staff, CoreMessages.ACTION_FAILED);
                return;
            }
            this.services.scheduler().entity(staff, () -> {
                Order current = this.service.book().get(order.id());
                this.services.dialogs().show(staff, staffView(current == null ? order : current, fills, back));
            }, null);
        });
    }

    private View staffView(Order order, List<OrderStore.Fill> fills, Runnable back) {
        Lang lang = lang();
        long now = System.currentTimeMillis();
        String ownerName = this.service.name(order.owner());
        List<Component> lines = new ArrayList<>(lang.lines(OrdersMessages.STAFF_BODY, Arg.text("owner", ownerName), item(order.key()),
            Arg.number("filled", order.filled()), Arg.number("quantity", order.quantity()), Arg.money("held", order.escrow()),
            Arg.time("ago", Duration.ofMillis(Math.max(0, now - order.created()))),
            Arg.time("time", Duration.ofMillis(order.millisLeft(now))),
            Arg.component("state", lang.get(this.service.stateLabel(order.state())))));
        lines.add(Component.empty());
        lines.addAll(fillLines(fills, now));
        OrderItem item = this.service.items().of(order);
        List<Body> body = new ArrayList<>();
        if (item != null) {
            body.add(Body.item(item.prototype(), null));
        }
        body.add(text(lines));
        List<Button> buttons = new ArrayList<>();
        if (order.active()) {
            buttons.add(Button.of(label(OrdersMessages.STAFF_CANCEL), s -> s.show(reasonView(order, "", back))));
        }
        UUID owner = order.owner();
        buttons.add(Button.of(label(OrdersMessages.STAFF_OPEN, Arg.text("name", ownerName)), s -> {
            s.close();
            this.menus.own(s.player(), owner, back);
        }));
        return this.services.templates().listWithBody(Component.text(lang.plain(OrdersMessages.STAFF_TITLE,
            Arg.text("id", Long.toString(order.id())))), body, buttons, 1, back == null ? null : backTo(back));
    }

    private View reasonView(Order order, String reason, Runnable back) {
        Lang lang = lang();
        return this.services.templates().form(Component.text(lang.plain(OrdersMessages.STAFF_REASON_TITLE,
                Arg.text("id", Long.toString(order.id())))), List.of(),
            List.of(Templates.text("reason", lang.get(OrdersMessages.STAFF_REASON), reason, 64)), label(OrdersMessages.CREATE_NEXT),
            submission -> {
                String typed = submission.values().text("reason").strip();
                if (typed.isEmpty()) {
                    submission.error(lang.get(OrdersMessages.STAFF_REASON_MISSING));
                    return;
                }
                Order current = this.service.book().get(order.id());
                long refund = current == null ? 0 : current.escrow();
                submission.show(this.services.templates().confirm(Component.text(lang.plain(OrdersMessages.STAFF_REASON_TITLE,
                        Arg.text("id", Long.toString(order.id())))),
                    lang.lines(OrdersMessages.STAFF_CONFIRM_BODY, Arg.text("id", Long.toString(order.id())),
                        Arg.text("owner", this.service.name(order.owner())), this.service.exact("refund", refund), Arg.text("reason", typed)),
                    label(OrdersMessages.STAFF_CANCEL), lang.get(CoreMessages.UI_BACK),
                    yes -> {
                        if (yes.player().hasPermission(OrderService.PERMISSION_ADMIN)) {
                            this.service.staffCancel(yes.player(), order.id(), typed);
                        } else {
                            this.services.messenger().send(yes.player(), CoreMessages.NO_PERMISSION);
                        }
                        finish(yes, back);
                    },
                    no -> no.show(reasonView(order, typed, back))));
            },
            submission -> finish(submission, back));
    }
}
