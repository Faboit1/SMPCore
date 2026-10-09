package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The kits dialogs: the list ({@code /kits}, the main menu's Kits button) with every kit and its status, a kit's own
 * dialog with what it gives and a Claim button, and the perks dialog with a button per perk the player has.
 * Nothing shown is trusted: every button runs the full checks again when clicked.
 */
final class KitDialogs {

    private final Services services;
    private final KitService kits;
    private final PerkService perks;

    KitDialogs(Services services, KitService kits, PerkService perks) {
        this.services = services;
        this.kits = kits;
        this.perks = perks;
    }

    private Lang lang() {
        return this.services.lang();
    }

    /** The kits dialog. {@code back} is what the footer does (null: Close). */
    void list(Player player, Button.Handler back) {
        Lang lang = lang();
        List<Component> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        List<Kit> visible = this.kits.visible(player);
        int ready = 0;
        Kit onlyReady = null;
        for (Kit kit : visible) {
            boolean permitted = this.kits.permitted(player, kit);
            KitStatus status = this.kits.status(player, kit);
            Component statusText = this.kits.text().status(status, permitted);
            if (permitted && status.ready()) {
                ready++;
                onlyReady = kit;
            }
            lines.add(lang.get(KitsMessages.LIST_LINE, Arg.text("name", kit.name()), Arg.component("status", statusText)));
            Component tooltip = kit.description() == null ? statusText
                : Component.join(JoinConfiguration.newlines(), lang.get(KitsMessages.KIT_DESCRIPTION, Arg.text("description", kit.description())),
                    statusText);
            buttons.add(Button.of(Component.text(kit.name()), tooltip, s -> kit(s.player(), kit.id(), back)).width(150));
        }
        if (visible.isEmpty()) {
            lines.add(lang.get(KitsMessages.LIST_EMPTY));
        }
        boolean waiting = this.kits.waitingStacks(player.getUniqueId()) > 0;
        if (waiting) {
            lines.add(Component.empty());
            lines.add(lang.get(KitsMessages.LIST_WAITING));
        }
        if (ready == 1) {
            Kit kit = onlyReady;
            buttons.add(Button.of(lang.get(KitsMessages.LIST_CLAIM_ONE, Arg.text("name", kit.name())), s -> {
                KitService.Refusal refusal = this.kits.claim(s.player(), kit);
                if (refusal != null) {
                    s.error(lang.get(refusal.key(), refusal.argArray()));
                } else {
                    s.close();
                }
            }).width(150));
        } else if (ready >= 2) {
            buttons.add(Button.of(lang.get(KitsMessages.LIST_CLAIM_READY, Arg.number("count", ready)), s -> {
                KitService.Refusal refusal = this.kits.claimReady(s.player());
                if (refusal != null) {
                    s.error(lang.get(refusal.key(), refusal.argArray()));
                } else {
                    s.close();
                }
            }).width(150));
        }
        if (waiting) {
            buttons.add(Button.of(lang.get(KitsMessages.LIST_COLLECT), s -> {
                this.kits.collect(s.player());
                s.close();
            }).width(150));
        }
        if (!this.perks.available(player).isEmpty()) {
            buttons.add(Button.of(lang.get(KitsMessages.LIST_PERKS), s -> perks(s.player(), back)).width(150));
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(KitsMessages.LIST_TITLE), lines, buttons, 2, back));
    }

    /** A kit's dialog: what it gives, its cooldown and status, and Claim when it is ready. */
    void kit(Player player, String id, Button.Handler listBack) {
        Kit kit = this.kits.settings().kit(id);
        if (kit == null) {
            list(player, listBack);
            return;
        }
        Lang lang = lang();
        boolean permitted = this.kits.permitted(player, kit);
        KitStatus status = this.kits.status(player, kit);
        List<Component> header = new ArrayList<>();
        if (kit.description() != null) {
            header.add(lang.get(KitsMessages.KIT_DESCRIPTION, Arg.text("description", kit.description())));
        }
        header.add(kit.cooldown().once() ? lang.get(KitsMessages.KIT_ONCE)
            : lang.get(KitsMessages.KIT_EVERY, Arg.time("time", kit.cooldown().every())));
        if (!permitted) {
            header.add(lang.get(KitsMessages.KIT_LOCKED));
        } else {
            header.add(switch (status) {
                case KitStatus.Ready ready -> lang.get(KitsMessages.KIT_READY);
                case KitStatus.Waiting waiting -> lang.get(KitsMessages.KIT_WAITING, Arg.time("time", waiting.shown()));
                case KitStatus.Claimed claimed -> lang.get(KitsMessages.KIT_CLAIMED);
            });
        }
        List<Body> body = new ArrayList<>();
        body.add(Body.text(Component.join(JoinConfiguration.newlines(), header)));
        for (KitItem item : kit.items()) {
            ItemStack stack = this.kits.items().build(item);
            int shown = Math.min(stack.getAmount(), Math.min(99, stack.getMaxStackSize()));
            Component name = Items.name(stack);
            Component description = shown < stack.getAmount()
                ? lang.get(KitsMessages.KIT_ITEM_MANY, Arg.component("name", name), Arg.number("amount", stack.getAmount()))
                : lang.get(KitsMessages.KIT_ITEM, Arg.component("name", name));
            body.add(Body.item(stack.asQuantity(shown), description));
        }
        if (!kit.keys().isEmpty()) {
            body.add(Body.text(lang.get(KitsMessages.KIT_KEYS, Arg.component("keys", this.kits.text().keys(kit.keys())))));
        }
        List<Button> buttons = new ArrayList<>();
        if (permitted && status.ready()) {
            buttons.add(Button.of(lang.get(KitsMessages.KIT_CLAIM), s -> {
                KitService.Refusal refusal = this.kits.claim(s.player(), kit);
                if (refusal != null) {
                    s.error(lang.get(refusal.key(), refusal.argArray()));
                } else {
                    s.close();
                }
            }).width(Templates.WIDE));
        }
        this.services.dialogs().show(player, this.services.templates().listWithBody(
            lang.get(KitsMessages.KIT_TITLE, Arg.text("name", kit.name())), body, buttons, 1, s -> list(s.player(), listBack)));
    }

    /** The perks dialog: a button per perk the player has. */
    void perks(Player player, Button.Handler listBack) {
        Lang lang = lang();
        List<Button> buttons = new ArrayList<>();
        for (Perk perk : this.perks.available(player)) {
            buttons.add(Button.of(lang.get(KitsMessages.name(perk)), lang.get(KitsMessages.PERK_COMMAND, Arg.text("command", perk.id())),
                s -> this.perks.use(s.player(), perk)).width(150).closes());
        }
        this.services.dialogs().show(player, this.services.templates().list(lang.get(KitsMessages.PERKS_TITLE),
            List.of(lang.get(KitsMessages.PERKS_BODY)), buttons, 2, s -> list(s.player(), listBack)));
    }
}
