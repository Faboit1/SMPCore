package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.gui.Items;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The kits dialogs in the dialog style: the list ({@code /kits}, the main menu's Kits button) is one button per kit
 * reading "Daily: ready" (the status in its colour, what the kit is in the tooltip), then Claim, Collect and Perks
 * when they apply; a kit's own dialog shows what it gives, one status line and Claim; the perks dialog is a button per
 * perk. Nothing shown is trusted: every button runs the full checks again when clicked.
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
        Templates templates = this.services.templates();
        List<Button> buttons = new ArrayList<>();
        List<Kit> visible = this.kits.visible(player);
        int ready = 0;
        Kit onlyReady = null;
        for (Kit kit : visible) {
            boolean permitted = this.kits.permitted(player, kit);
            KitStatus status = this.kits.status(player, kit);
            if (permitted && status.ready()) {
                ready++;
                onlyReady = kit;
            }
            buttons.add(templates.choiceButton(Component.text(kit.name()), this.kits.text().status(status, permitted),
                about(kit, lang.get(KitsMessages.LIST_OPEN_TOOLTIP)), s -> kit(s.player(), kit.id(), back)));
        }
        if (ready == 1) {
            Kit kit = onlyReady;
            buttons.add(Button.of(lang.get(KitsMessages.LIST_CLAIM_ONE, Arg.text("name", kit.name())),
                lang.get(KitsMessages.LIST_CLAIM_TOOLTIP), s -> {
                    KitService.Refusal refusal = this.kits.claim(s.player(), kit);
                    if (refusal != null) {
                        s.error(lang.get(refusal.key(), refusal.argArray()));
                    } else {
                        s.close();
                    }
                }));
        } else if (ready >= 2) {
            buttons.add(Button.of(lang.get(KitsMessages.LIST_CLAIM_READY, Arg.text("count", Lang.number(ready))),
                lang.get(KitsMessages.LIST_CLAIM_TOOLTIP), s -> {
                    KitService.Refusal refusal = this.kits.claimReady(s.player());
                    if (refusal != null) {
                        s.error(lang.get(refusal.key(), refusal.argArray()));
                    } else {
                        s.close();
                    }
                }));
        }
        if (this.kits.waitingStacks(player.getUniqueId()) > 0) {
            buttons.add(Button.of(lang.get(KitsMessages.LIST_COLLECT), lang.get(KitsMessages.LIST_WAITING), s -> {
                this.kits.collect(s.player());
                s.close();
            }));
        }
        if (!this.perks.available(player).isEmpty()) {
            buttons.add(Button.of(lang.get(KitsMessages.LIST_PERKS), lang.get(KitsMessages.LIST_PERKS_TOOLTIP), s -> perks(s.player(), back)));
        }
        List<Component> lines = visible.isEmpty() ? List.of(lang.get(KitsMessages.LIST_EMPTY)) : List.of();
        this.services.dialogs().show(player, templates.grid(lang.get(KitsMessages.LIST_TITLE), lines, buttons, back));
    }

    /** A kit's tooltip: its description (when it has one), how often it can be claimed, then {@code last}. */
    private Component about(Kit kit, Component last) {
        Lang lang = lang();
        List<Component> lines = new ArrayList<>(3);
        if (kit.description() != null) {
            lines.add(lang.get(KitsMessages.KIT_DESCRIPTION, Arg.text("description", kit.description())));
        }
        lines.add(kit.cooldown().once() ? lang.get(KitsMessages.KIT_ONCE)
            : lang.get(KitsMessages.KIT_EVERY, Arg.text("time", Durations.format(kit.cooldown().every()))));
        if (last != null) {
            lines.add(last);
        }
        return Templates.lines(lines);
    }

    /**
     * A kit's dialog: one status line, what it gives (the items and any keys), and Claim, whose tooltip says what the
     * kit is. Claiming a kit that isn't ready shows why in red.
     */
    void kit(Player player, String id, Button.Handler listBack) {
        Kit kit = this.kits.settings().kit(id);
        if (kit == null) {
            list(player, listBack);
            return;
        }
        Lang lang = lang();
        boolean permitted = this.kits.permitted(player, kit);
        KitStatus status = this.kits.status(player, kit);
        Component state;
        if (!permitted) {
            state = lang.get(KitsMessages.KIT_LOCKED);
        } else {
            state = switch (status) {
                case KitStatus.Ready ready -> lang.get(KitsMessages.KIT_READY);
                case KitStatus.Waiting waiting -> lang.get(KitsMessages.KIT_WAITING, Arg.text("time", Durations.format(waiting.shown())));
                case KitStatus.Claimed claimed -> lang.get(KitsMessages.KIT_CLAIMED);
            };
        }
        List<Body> body = new ArrayList<>();
        body.add(Body.text(state));
        for (KitItem item : kit.items()) {
            ItemStack stack = this.kits.items().build(item);
            int shown = Math.min(stack.getAmount(), Math.min(99, stack.getMaxStackSize()));
            Component name = Items.name(stack);
            Component description = shown < stack.getAmount()
                ? lang.get(KitsMessages.KIT_ITEM_MANY, Arg.component("name", name), Arg.text("amount", Lang.number(stack.getAmount())))
                : lang.get(KitsMessages.KIT_ITEM, Arg.component("name", name));
            body.add(Body.item(stack.asQuantity(shown), description));
        }
        if (!kit.keys().isEmpty()) {
            body.add(Body.text(lang.get(KitsMessages.KIT_KEYS, Arg.component("keys", this.kits.text().keys(kit.keys())))));
        }
        List<Button> buttons = new ArrayList<>(1);
        if (permitted) {
            boolean readyNow = status.ready();
            buttons.add(Button.of(lang.get(readyNow ? KitsMessages.KIT_CLAIM : KitsMessages.KIT_CLAIM_LATER), about(kit, null), s -> {
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

    /** The perks dialog: a button per perk the player has, its command in the tooltip. */
    void perks(Player player, Button.Handler listBack) {
        Lang lang = lang();
        List<Button> buttons = new ArrayList<>();
        for (Perk perk : this.perks.available(player)) {
            buttons.add(Button.of(lang.get(KitsMessages.name(perk)), lang.get(KitsMessages.PERK_COMMAND, Arg.text("command", perk.id())),
                s -> this.perks.use(s.player(), perk)).closes());
        }
        this.services.dialogs().show(player, this.services.templates().grid(lang.get(KitsMessages.PERKS_TITLE), buttons,
            s -> list(s.player(), listBack)));
    }
}
