package net.siftvanilla.siftcore.feature.shards;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.event.ShardShopPurchaseEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The shard shop: a dialog listing the offers, a purchase dialog per offer (amount slider, a Buy button that names
 * what it buys, a confirmation for large purchases), and the purchase itself.
 * <p>
 * Everything is checked again when the player buys (the offer still exists, its price is the one they saw, it is
 * still available to them, the balance covers it), and the price once more inside the transaction. Items: the shards
 * are taken in one transaction that also puts what won't fit into the claim box; the rest is handed out only after
 * that transaction is stored. Crate keys: see {@link KeyGrants}. Shards never buy money (see features/shards.yml).
 * Combat-tagged players can't open the shop or buy ({@code shop.block-in-combat}), checked on every screen and
 * once more right before the purchase.
 */
final class ShardShop {

    private static final String AMOUNT = "amount";

    private final Services services;
    private final Setting<ShardsSettings> settings;
    private final CrateKeys crates;
    private final KeyGrants grants;
    private final ShardHandouts handouts;
    private final CombatStatus combat;

    ShardShop(Services services, Setting<ShardsSettings> settings, CrateKeys crates, KeyGrants grants, ShardHandouts handouts,
              CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.crates = crates;
        this.grants = grants;
        this.handouts = handouts;
        this.combat = combat;
    }

    /** True (after telling the player) when combat keeps them out of the shop. */
    boolean blocked(Player player) {
        UUID id = player.getUniqueId();
        if (!this.settings.get().blockInCombat() || !this.combat.tagged(id)) {
            return false;
        }
        this.services.messenger().send(player, ShardsMessages.IN_COMBAT, Arg.time("time", this.combat.remaining(id)));
        return true;
    }

    /** A dialog press while tagged: closes the dialog and says why. */
    private boolean blocked(Submission s) {
        if (blocked(s.player())) {
            s.close();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ offers

    /** True when the offer can be bought by this player right now. */
    boolean available(Player player, ShardOffer offer) {
        if (!offer.permission().isEmpty() && !player.hasPermission(offer.permission())) {
            return false;
        }
        return switch (offer.kind()) {
            case KEY -> this.crates.crates().contains(offer.target());
            case ITEM -> unit(offer).isPresent();
        };
    }

    /** Offers this player may see, in the configured order. */
    List<ShardOffer> visible(Player player) {
        List<ShardOffer> list = new ArrayList<>();
        for (ShardOffer offer : this.settings.get().offers()) {
            if (available(player, offer)) {
                list.add(offer);
            }
        }
        return list;
    }

    /** One unit of an item offer. */
    static Optional<ItemStack> unit(ShardOffer offer) {
        if (offer.kind() != ShardOffer.Kind.ITEM) {
            return Optional.empty();
        }
        Material material = Material.matchMaterial(offer.target());
        if (material == null || material.isAir() || !material.isItem()) {
            return Optional.empty();
        }
        return Optional.of(ItemStack.of(material, 1));
    }

    /** What an offer is called: its configured name, else the item's own name or "<crate> key". */
    Component name(ShardOffer offer) {
        if (!offer.name().isEmpty()) {
            return Component.text(offer.name());
        }
        if (offer.kind() == ShardOffer.Kind.KEY) {
            String crate = offer.target();
            String label = crate.isEmpty() ? crate : crate.substring(0, 1).toUpperCase(Locale.ROOT) + crate.substring(1).replace('_', ' ');
            return this.services.lang().get(ShardsMessages.KEY_NAME, Arg.text("crate", label));
        }
        Material material = Material.matchMaterial(offer.target());
        return material == null ? Component.text(offer.target()) : Component.translatable(material.translationKey());
    }

    // ------------------------------------------------------------------ the shop list

    /** Opens the shop. {@code back} null shows Close instead of Back. Player's thread. */
    void open(Player player, Runnable back) {
        if (blocked(player)) {
            return;
        }
        Lang lang = this.services.lang();
        long shards = this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS);
        List<Component> lines = new ArrayList<>(lang.lines(ShardsMessages.SHOP_BODY, Arg.number("shards", shards)));
        List<ShardOffer> offers = visible(player);
        if (offers.isEmpty()) {
            lines.add(lang.get(ShardsMessages.SHOP_EMPTY));
        }
        List<Button> buttons = new ArrayList<>();
        for (ShardOffer offer : offers) {
            Component tooltip = Component.join(JoinConfiguration.newlines(), lang.lines(ShardsMessages.SHOP_OFFER_TOOLTIP,
                Arg.text("description", offer.description()), Arg.number("max", offer.max())));
            String id = offer.id();
            Component label = offer.amount() > 1
                ? lang.get(ShardsMessages.SHOP_OFFER_MANY, Arg.number("amount", offer.amount()), Arg.component("name", name(offer)),
                    Arg.number("price", offer.price()))
                : lang.get(ShardsMessages.SHOP_OFFER, Arg.component("name", name(offer)), Arg.number("price", offer.price()));
            buttons.add(Button.of(label, offer.description().isEmpty() ? null : tooltip,
                s -> openOffer(s.player(), id, back)).width(Templates.WIDE));
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(ShardsMessages.SHOP_TITLE), lines, buttons, 1,
            back == null ? null : s -> back.run()));
    }

    /** Opens the purchase dialog of an offer. Player's thread. */
    void openOffer(Player player, String id, Runnable back) {
        if (blocked(player)) {
            return;
        }
        ShardOffer offer = this.settings.get().offer(id);
        if (offer == null) {
            this.services.messenger().send(player, ShardsMessages.NO_LONGER_SOLD);
            open(player, back);
            return;
        }
        if (!available(player, offer)) {
            this.services.messenger().send(player, ShardsMessages.UNAVAILABLE);
            open(player, back);
            return;
        }
        this.services.dialogs().show(player, offerView(player, offer, 1, null, back));
    }

    private View offerView(Player player, ShardOffer offer, int amount, Component note, Runnable back) {
        Lang lang = this.services.lang();
        long shards = this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS);
        long total = offer.total(amount).orElse(0);
        List<Body> body = new ArrayList<>();
        Optional<ItemStack> unit = unit(offer);
        unit.ifPresent(item -> body.add(Body.item(item.asQuantity(offer.amount()), null)));
        List<Component> lines = new ArrayList<>();
        if (!offer.description().isEmpty()) {
            lines.add(lang.get(ShardsMessages.BUY_DESCRIPTION, Arg.text("description", offer.description())));
        }
        if (offer.amount() > 1) {
            lines.add(offer.kind() == ShardOffer.Kind.KEY
                ? lang.get(ShardsMessages.BUY_KEYS, Arg.number("keys", offer.amount()))
                : lang.get(ShardsMessages.BUY_ITEMS, Arg.number("items", offer.amount())));
        }
        lines.addAll(lang.lines(ShardsMessages.BUY_BODY, Arg.number("price", offer.price()), Arg.number("shards", shards)));
        if (offer.max() > 1) {
            lines.add(lang.get(ShardsMessages.BUY_MAX, Arg.number("max", offer.max())));
        }
        if (offer.kind() == ShardOffer.Kind.KEY) {
            lines.add(lang.get(ShardsMessages.BUY_OWNED, Arg.number("keys", this.crates.keys(player.getUniqueId(), offer.target()))));
        }
        body.add(Body.text(Component.join(JoinConfiguration.newlines(), lines)));
        if (note != null) {
            body.add(Body.text(note));
        }
        List<Input> inputs = new ArrayList<>(1);
        if (offer.max() > 1) {
            inputs.add(Templates.range(AMOUNT, lang.get(ShardsMessages.BUY_AMOUNT), 1, offer.max(), 1, amount));
        }
        String id = offer.id();
        long price = offer.price();
        Button buy = Button.of(lang.get(ShardsMessages.BUY_BUTTON, Arg.number("amount", amount), Arg.number("total", total)),
            s -> onBuy(s, id, price, amount, back)).width(150);
        Button backButton = Button.of(lang.get(CoreMessages.UI_BACK), s -> open(s.player(), back)).width(150);
        return new View(View.Kind.FORM, lang.get(ShardsMessages.BUY_TITLE, Arg.component("name", name(offer))), body, inputs,
            List.of(buy, backButton), null, 2, true);
    }

    /** The Buy button: buys what it said, or shows the new total when the player moved the slider. */
    private void onBuy(Submission s, String id, long price, int amount, Runnable back) {
        if (blocked(s)) {
            return;
        }
        Player player = s.player();
        ShardOffer offer = current(s, id, back);
        if (offer == null) {
            return;
        }
        int wanted = offer.max() > 1 ? ShardOffer.clampUnits(s.values().number(AMOUNT), offer.max()) : 1;
        Lang lang = this.services.lang();
        if (offer.price() != price) {
            showError(s, offer, wanted, lang.get(ShardsMessages.BUY_PRICE_CHANGED, Arg.number("price", offer.price())), back);
            return;
        }
        if (wanted != amount) {
            s.show(offerView(player, offer, wanted, lang.get(ShardsMessages.BUY_CHANGED), back));
            return;
        }
        buy(s, offer, amount, false, back);
    }

    private View confirmView(Player player, ShardOffer offer, int amount, long total, Runnable back) {
        Lang lang = this.services.lang();
        long shards = this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS);
        List<Component> lines = lang.lines(ShardsMessages.CONFIRM_BODY, Arg.number("count", offer.given(amount)),
            Arg.component("name", name(offer)), Arg.number("total", total), Arg.number("left", ShardMath.left(shards, total)));
        String id = offer.id();
        long price = offer.price();
        return this.services.templates().confirm(lang.get(ShardsMessages.CONFIRM_TITLE), lines, lang.get(ShardsMessages.CONFIRM_BUTTON),
            lang.get(CoreMessages.UI_BACK), s -> onConfirm(s, id, price, amount, back),
            s -> {
                ShardOffer now = this.settings.get().offer(id);
                if (now == null) {
                    open(s.player(), back);
                } else {
                    s.show(offerView(s.player(), now, Math.min(amount, now.max()), null, back));
                }
            });
    }

    private void onConfirm(Submission s, String id, long price, int amount, Runnable back) {
        if (blocked(s)) {
            return;
        }
        ShardOffer offer = current(s, id, back);
        if (offer == null) {
            return;
        }
        if (offer.price() != price || amount > offer.max()) {
            showError(s, offer, Math.min(amount, offer.max()),
                this.services.lang().get(ShardsMessages.BUY_PRICE_CHANGED, Arg.number("price", offer.price())), back);
            return;
        }
        buy(s, offer, amount, true, back);
    }

    /** The offer as it is now, or null after telling the player it is gone or unavailable. */
    private ShardOffer current(Submission s, String id, Runnable back) {
        ShardOffer offer = this.settings.get().offer(id);
        if (offer == null || !available(s.player(), offer)) {
            this.services.messenger().send(s.player(), offer == null ? ShardsMessages.NO_LONGER_SOLD : ShardsMessages.UNAVAILABLE);
            open(s.player(), back);
            return null;
        }
        return offer;
    }

    private void showError(Submission s, ShardOffer offer, int amount, Component error, Runnable back) {
        s.show(offerView(s.player(), offer, amount, null, back).withError(error, FormValues.EMPTY));
        this.services.messenger().feedback(s.player(), Feedback.ERROR);
    }

    // ------------------------------------------------------------------ buying

    private void buy(Submission s, ShardOffer offer, int amount, boolean confirmed, Runnable back) {
        Player player = s.player();
        UUID uuid = player.getUniqueId();
        Lang lang = this.services.lang();
        OptionalLong maybeTotal = offer.total(amount);
        if (maybeTotal.isEmpty() || maybeTotal.getAsLong() > ShardsSettings.MAX_PRICE * ShardsSettings.MAX_UNITS) {
            showError(s, offer, amount, lang.get(ShardsMessages.BUY_TOO_EXPENSIVE), back);
            return;
        }
        long total = maybeTotal.getAsLong();
        long balance = this.services.ledger().balance(uuid, Currency.SHARDS);
        if (balance < total) {
            showError(s, offer, amount, lang.get(ShardsMessages.BUY_NOT_ENOUGH, Arg.number("total", total), Arg.number("shards", balance)), back);
            return;
        }
        if (!confirmed && ShardMath.needsConfirmation(total, this.settings.get().confirmAbove())) {
            s.show(confirmView(player, offer, amount, total, back));
            return;
        }
        if (!this.services.ledger().available()) {
            s.close();
            this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            return;
        }
        // Once more right before the purchase: the tag can start while the dialog is open.
        if (blocked(s)) {
            return;
        }
        if (!new ShardShopPurchaseEvent(player, offer.id(), offer.kind().id(), amount, total).callEvent()) {
            s.close();
            this.services.messenger().send(player, ShardsMessages.CANCELLED);
            return;
        }
        switch (offer.kind()) {
            case KEY -> buyKeys(s, offer, amount, total, back);
            case ITEM -> buyItems(s, offer, amount, total, back);
        }
    }

    /** Checks inside the transaction that the offer was not changed or removed by a reload meanwhile. */
    private LedgerTx.Builder priceCheck(LedgerTx.Builder tx, ShardOffer offer) {
        String id = offer.id();
        long price = offer.price();
        String target = offer.target();
        int perUnit = offer.amount();
        return tx.check(() -> {
            ShardOffer now = this.settings.get().offer(id);
            return now != null && now.price() == price && now.target().equals(target) && now.amount() == perUnit ? null : "offer_changed";
        });
    }

    private void buyKeys(Submission s, ShardOffer offer, int amount, long total, Runnable back) {
        Player player = s.player();
        UUID uuid = player.getUniqueId();
        int keys = (int) offer.given(amount);
        KeyGrants.Purchase purchase = new KeyGrants.Purchase(KeyGrants.newRef(), uuid, offer.id(), offer.target(), keys, total,
            System.currentTimeMillis());
        LedgerTx.Builder tx = LedgerTx.builder().actor(uuid).note(keys + " " + offer.target() + " key(s)");
        this.grants.purchase(tx, purchase);
        priceCheck(tx, offer);
        TransactionResult result = this.services.ledger().execute(tx.build());
        if (!handleFailure(s, offer, amount, total, result, back)) {
            return;
        }
        s.close();
        Component name = name(offer);
        this.services.messenger().send(player, ShardsMessages.GIVING);
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                // Not stored: the shards and the purchase were rolled back together.
                this.services.messenger().send(player, ShardsMessages.FAILED);
                return;
            }
            this.grants.grant(purchase).thenAccept(outcome -> tell(player, purchase, outcome, name));
        });
    }

    /** Tells the buyer how a key purchase ended (only once it did). */
    void tell(Player player, KeyGrants.Purchase purchase, KeyGrants.Outcome outcome, Component name) {
        if (!player.isOnline()) {
            return;
        }
        switch (outcome) {
            case GRANTED -> this.services.messenger().send(player, ShardsMessages.BOUGHT_KEYS, Arg.number("count", purchase.keys()),
                Arg.component("name", name), Arg.number("total", purchase.cost()));
            case REFUNDED -> this.services.messenger().send(player, ShardsMessages.REFUNDED, Arg.number("count", purchase.keys()),
                Arg.component("name", name), Arg.number("total", purchase.cost()));
            case PENDING, FINISHED -> {
            }
        }
    }

    private void buyItems(Submission s, ShardOffer offer, int amount, long total, Runnable back) {
        Player player = s.player();
        UUID uuid = player.getUniqueId();
        Optional<ItemStack> maybeUnit = unit(offer);
        if (maybeUnit.isEmpty()) {
            s.close();
            this.services.messenger().send(player, ShardsMessages.UNAVAILABLE);
            return;
        }
        ItemStack unit = maybeUnit.get();
        long items = offer.given(amount);
        String ref = KeyGrants.newRef();
        LedgerTx.Builder tx = LedgerTx.builder()
            .actor(uuid)
            .note(items + " " + offer.target())
            .sink(uuid, Currency.SHARDS, total, KeyGrants.SPEND_KIND, ref);
        priceCheck(tx, offer);
        // Everything goes into the claim box with the payment, so a crash at any moment loses neither; the hand-out
        // then moves what fits into the inventory.
        for (ItemStack stack : stacks(unit, items)) {
            this.services.deliveries().add(tx, uuid, ShardHandouts.SOURCE, ref, stack);
        }
        TransactionResult result = this.services.ledger().execute(tx.build());
        if (!handleFailure(s, offer, amount, total, result, back)) {
            return;
        }
        s.close();
        Component name = name(offer);
        result.committed().whenComplete((ignored, error) -> {
            if (error != null) {
                // Not stored: the shards and the claim box items were rolled back together.
                this.services.messenger().send(player, ShardsMessages.FAILED);
                return;
            }
            // When the buyer left meanwhile, everything simply waits in the claim box.
            this.services.scheduler().entity(player, () -> this.handouts.claim(player, ref, (given, left) -> {
                if (left > 0) {
                    this.services.messenger().send(player, ShardsMessages.BOUGHT_CLAIM_BOX, Arg.number("count", items),
                        Arg.component("name", name), Arg.number("total", total), Arg.number("left", left));
                } else {
                    this.services.messenger().send(player, ShardsMessages.BOUGHT, Arg.number("count", items), Arg.component("name", name),
                        Arg.number("total", total));
                }
            }), null);
        });
    }

    /** Handles a failed purchase transaction; returns true when it succeeded. */
    private boolean handleFailure(Submission s, ShardOffer offer, int amount, long total, TransactionResult result, Runnable back) {
        Player player = s.player();
        Lang lang = this.services.lang();
        switch (result.status()) {
            case SUCCESS -> {
                return true;
            }
            case INSUFFICIENT_FUNDS -> showError(s, offer, amount, lang.get(ShardsMessages.BUY_NOT_ENOUGH, Arg.number("total", total),
                Arg.number("shards", this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS))), back);
            case REJECTED -> {
                ShardOffer now = this.settings.get().offer(offer.id());
                if (now == null) {
                    s.close();
                    this.services.messenger().send(player, ShardsMessages.NO_LONGER_SOLD);
                } else {
                    showError(s, now, Math.min(amount, now.max()),
                        lang.get(ShardsMessages.BUY_PRICE_CHANGED, Arg.number("price", now.price())), back);
                }
            }
            case CANCELLED -> {
                s.close();
                this.services.messenger().send(player, ShardsMessages.CANCELLED);
            }
            case UNAVAILABLE -> {
                s.close();
                this.services.messenger().send(player, CoreMessages.ECONOMY_UNAVAILABLE);
            }
            case BALANCE_LIMIT -> {
                s.close();
                this.services.messenger().send(player, ShardsMessages.FAILED);
            }
        }
        return false;
    }

    /** {@code count} copies of {@code unit} in stacks no larger than the item's max stack size. */
    static List<ItemStack> stacks(ItemStack unit, long count) {
        int max = Math.max(1, unit.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        long left = count;
        while (left > 0) {
            int part = (int) Math.min(max, left);
            stacks.add(unit.asQuantity(part));
            left -= part;
        }
        return stacks;
    }
}
