package net.siftvanilla.siftcore.feature.staff;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.gui.Items;
import net.siftvanilla.siftcore.ui.gui.Menu;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The read-only view of a target's inventory or ender chest (see {@link InspectLayout}). Nothing can be moved in or
 * out by hand: clicking an item asks the {@link Inspector} to take it, which re-checks the live slot on the target's
 * thread. The view is a snapshot; it refreshes after every change and with the refresh button.
 */
final class InspectMenu extends Menu {

    private final Inspector inspector;
    private final Player target;
    private final InspectLayout.Kind kind;
    private Inspector.View view;

    InspectMenu(MenuContext ctx, Player viewer, Inspector inspector, Player target, InspectLayout.Kind kind, Inspector.View view) {
        super(ctx, viewer, Component.text(ctx.lang().plain(kind == InspectLayout.Kind.INVENTORY
            ? StaffMessages.INSPECT_TITLE_INVENTORY : StaffMessages.INSPECT_TITLE_ENDER, Arg.text("name", target.getName()))), kind.rows());
        this.inspector = inspector;
        this.target = target;
        this.kind = kind;
        this.view = view;
    }

    private boolean canEdit() {
        return this.viewer.hasPermission(Inspector.editNode(this.kind));
    }

    @Override
    protected void draw() {
        Lang lang = this.ctx.lang();
        boolean edit = canEdit();
        for (int index = 0; index < this.kind.sourceSize(); index++) {
            int slot = InspectLayout.guiSlot(this.kind, index);
            ItemStack item = this.view.items().item(index);
            if (item == null || item.isEmpty()) {
                clear(slot);
                continue;
            }
            List<Component> lore = new ArrayList<>(lang.lines(slotLabel(index), Arg.number("slot", index + 1)));
            if (edit) {
                lore.addAll(lang.lines(StaffMessages.INSPECT_TAKE_HINT));
            }
            int source = index;
            set(slot, Items.display(item, lore), click -> take(source));
        }
        set(this.kind.infoSlot(), info(lang), null);
        InspectLayout.Kind other = this.kind.other();
        if (this.viewer.hasPermission(Inspector.viewNode(other))) {
            boolean toEnder = other == InspectLayout.Kind.ENDER_CHEST;
            set(this.kind.switchSlot(), Items.icon(toEnder ? Material.ENDER_CHEST : Material.CHEST,
                lang.get(toEnder ? StaffMessages.INSPECT_SWITCH_ENDER : StaffMessages.INSPECT_SWITCH_INVENTORY),
                lang.lines(toEnder ? StaffMessages.INSPECT_SWITCH_ENDER_LORE : StaffMessages.INSPECT_SWITCH_INVENTORY_LORE,
                    Arg.text("name", this.target.getName()))), click -> {
                    click(Feedback.CLICK);
                    this.inspector.open(this.viewer, this.target, other);
                });
        } else {
            clear(this.kind.switchSlot());
        }
        set(this.kind.refreshSlot(), Items.icon(Material.CLOCK, lang.get(StaffMessages.INSPECT_REFRESH),
            lang.lines(StaffMessages.INSPECT_REFRESH_LORE)), click -> {
                click(Feedback.CLICK);
                refresh();
            });
        if (edit) {
            set(this.kind.clearSlot(), Items.icon(Material.LAVA_BUCKET, lang.get(this.kind == InspectLayout.Kind.INVENTORY
                ? StaffMessages.INSPECT_CLEAR_INVENTORY : StaffMessages.INSPECT_CLEAR_ENDER), lang.lines(StaffMessages.INSPECT_CLEAR_LORE)),
                click -> confirmClear());
        } else {
            clear(this.kind.clearSlot());
        }
    }

    private ItemStack info(Lang lang) {
        ItemStack head = Items.icon(Material.PLAYER_HEAD, lang.get(StaffMessages.INSPECT_INFO_NAME, Arg.text("name", this.target.getName())),
            lang.lines(StaffMessages.INSPECT_INFO_LORE, Arg.decimal("health", this.view.health()), Arg.number("food", this.view.food()),
                Arg.number("level", this.view.level()), Arg.component("mode", StaffText.gameMode(this.view.mode()))));
        head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile()
            .uuid(this.target.getUniqueId()).name(this.target.getName()).build());
        return head;
    }

    private MessageKey slotLabel(int index) {
        return switch (InspectLayout.slotKind(this.kind, index)) {
            case HOTBAR -> StaffMessages.INSPECT_SLOT_HOTBAR;
            case STORAGE -> StaffMessages.INSPECT_SLOT_STORAGE;
            case HELMET -> StaffMessages.INSPECT_SLOT_HELMET;
            case CHESTPLATE -> StaffMessages.INSPECT_SLOT_CHESTPLATE;
            case LEGGINGS -> StaffMessages.INSPECT_SLOT_LEGGINGS;
            case BOOTS -> StaffMessages.INSPECT_SLOT_BOOTS;
            case OFF_HAND -> StaffMessages.INSPECT_SLOT_OFF_HAND;
            case ENDER_CHEST -> StaffMessages.INSPECT_SLOT_ENDER;
        };
    }

    /** Click on an item (viewer's thread): take it if the viewer may and nothing else is running. */
    private void take(int index) {
        if (!canEdit()) {
            this.ctx.messenger().send(this.viewer, StaffMessages.INSPECT_VIEW_ONLY);
            return;
        }
        ItemStack expected = this.view.items().item(index);
        if (expected == null || expected.isEmpty() || !lock()) {
            return;
        }
        this.inspector.take(this.viewer, this.target, this.kind, index, expected, outcome -> {
            unlock();
            if (outcome == Inspector.TakeOutcome.GONE) {
                this.viewer.closeInventory();
            } else {
                refresh();
            }
        });
    }

    /** Loads the target's items again and redraws (viewer's thread). */
    private void refresh() {
        if (!lock()) {
            return;
        }
        this.inspector.snapshot(this.target, this.kind).whenComplete((fresh, error) -> this.ctx.scheduler().entity(this.viewer, () -> {
            unlock();
            if (error != null) {
                this.ctx.messenger().send(this.viewer, StaffMessages.INSPECT_GONE, Arg.text("name", this.target.getName()));
                this.viewer.closeInventory();
                return;
            }
            this.view = fresh;
            redraw();
        }, this::unlock));
    }

    private void confirmClear() {
        Lang lang = this.ctx.lang();
        click(Feedback.CLICK);
        MessageKey body = this.kind == InspectLayout.Kind.INVENTORY
            ? StaffMessages.INSPECT_CLEAR_BODY_INVENTORY : StaffMessages.INSPECT_CLEAR_BODY_ENDER;
        this.ctx.dialogs().show(this.viewer, this.ctx.templates().confirm(lang.get(StaffMessages.INSPECT_CLEAR_TITLE),
            lang.lines(body, Arg.text("name", this.target.getName())),
            lang.get(StaffMessages.INSPECT_CLEAR_CONFIRM), lang.get(CoreMessages.UI_CANCEL),
            submission -> {
                submission.close();
                if (!canEdit()) {
                    this.ctx.messenger().send(this.viewer, StaffMessages.INSPECT_VIEW_ONLY);
                    return;
                }
                this.inspector.clear(this.viewer, this.target, this.kind).whenComplete((stacks, error) -> {
                    if (error != null) {
                        this.ctx.messenger().send(this.viewer, StaffMessages.INSPECT_GONE, Arg.text("name", this.target.getName()));
                        return;
                    }
                    this.ctx.messenger().send(this.viewer, StaffMessages.INSPECT_CLEARED, Arg.number("count", stacks),
                        Arg.text("name", this.target.getName()));
                    this.inspector.reopen(this.viewer, this.target, this.kind);
                });
            },
            submission -> {
                submission.close();
                this.inspector.reopen(this.viewer, this.target, this.kind);
            }));
    }
}
