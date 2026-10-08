package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.Deliveries;
import net.siftvanilla.siftcore.ui.gui.ClickContext;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The claim box: every item owed to the viewer, from any feature, oldest first. Click one to claim it, or claim
 * everything that fits with the button in slot 50. The menu is locked while a claim is being stored.
 */
final class ClaimBoxMenu extends PagedMenu<Deliveries.Delivery> {

    private final AuctionService service;

    ClaimBoxMenu(MenuContext ctx, Player viewer, AuctionService service, Runnable back) {
        super(ctx, viewer, Component.text(ctx.lang().plain(AuctionMessages.CLAIMS_TITLE)), null, null, back);
        this.service = service;
    }

    @Override
    protected List<Deliveries.Delivery> entries() {
        return this.service.claims().of(this.viewer.getUniqueId());
    }

    @Override
    protected ItemStack icon(Deliveries.Delivery delivery) {
        Lang lang = this.ctx.lang();
        String source = switch (delivery.source()) {
            case AuctionEngine.SOURCE -> lang.plain(AuctionMessages.CLAIMS_SOURCE_AUCTION);
            case ClaimBox.OVERFLOW -> lang.plain(AuctionMessages.CLAIMS_SOURCE_OVERFLOW);
            default -> lang.plain(AuctionMessages.CLAIMS_SOURCE_OTHER,
                Arg.text("source", delivery.source().replace('_', ' ').toLowerCase(Locale.ROOT)));
        };
        long waiting = Math.max(0, System.currentTimeMillis() - delivery.created());
        return AuctionItems.display(delivery.item(), lang.lines(AuctionMessages.CLAIMS_LORE,
            Arg.text("source", source), Arg.time("time", Duration.ofMillis(waiting))));
    }

    @Override
    protected void clicked(Deliveries.Delivery delivery, ClickContext click) {
        claim(List.of(delivery));
    }

    @Override
    protected void drawExtras() {
        List<Deliveries.Delivery> all = entries();
        if (all.isEmpty()) {
            clear(SLOT_EXTRA_1);
            return;
        }
        set(SLOT_EXTRA_1, Items.icon(Material.HOPPER_MINECART, this.ctx.lang().get(AuctionMessages.CLAIM_ALL),
            this.ctx.lang().lines(AuctionMessages.CLAIM_ALL_LORE, Arg.number("count", all.size()))), click -> claim(entries()));
    }

    private void claim(List<Deliveries.Delivery> deliveries) {
        Player player = this.viewer;
        if (!this.service.usable(player)) {
            return;
        }
        if (deliveries.isEmpty()) {
            this.ctx.messenger().send(player, AuctionMessages.CLAIM_EMPTY);
            return;
        }
        click(Feedback.CLICK);
        runBusy(this.service.claims().claim(player, deliveries), outcome -> {
            if (outcome != null) {
                report(player, outcome);
            }
            redraw();
        });
    }

    private void report(Player player, ClaimBox.Outcome outcome) {
        var messenger = this.ctx.messenger();
        if (outcome.failed()) {
            messenger.send(player, CoreMessages.ACTION_FAILED);
        } else if (outcome.claimed() == 0) {
            if (outcome.left() > 0) {
                messenger.send(player, CoreMessages.INVENTORY_FULL);
            } else if (this.service.claims().count(player.getUniqueId()) == 0) {
                messenger.send(player, AuctionMessages.CLAIM_EMPTY);
            }
            // Otherwise what was clicked had been claimed already; the redraw shows what is still waiting.
        } else if (outcome.left() > 0) {
            messenger.send(player, AuctionMessages.CLAIM_PARTIAL, Arg.number("count", outcome.left()));
        } else if (outcome.claimed() == 1 && outcome.first() != null) {
            messenger.send(player, AuctionMessages.CLAIMED_ONE, Arg.number("amount", outcome.first().getAmount()),
                Arg.text("item", AuctionItems.plainName(outcome.first())));
        } else {
            messenger.send(player, AuctionMessages.CLAIMED_MANY, Arg.number("count", outcome.claimed()));
        }
    }
}
