package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Looks into another player's inventory or ender chest without ever touching it from the wrong thread: the items
 * are copied on the target's thread, shown read-only on the staff member's thread, and every change goes back to the
 * target's thread and re-checks the live slot first.
 * <p>
 * Taking an item is remove-before-grant: the item leaves the target's slot (only if that slot still holds exactly
 * what staff saw), then it is given to the staff member on their thread; what does not fit, or anything left over
 * because they logged out meanwhile, goes to their claim box.
 */
final class Inspector {

    /** What staff see: the items plus a few stats, all read on the target's thread at the same moment. */
    record View(InspectSnapshot<ItemStack> items, double health, int food, int level, GameMode mode) {
    }

    /** How a take ended. */
    enum TakeOutcome {
        TAKEN,
        CHANGED,
        GONE
    }

    private final Services services;
    private final StaffHierarchy hierarchy;
    private final Logger logger;

    Inspector(Services services, StaffHierarchy hierarchy, Logger logger) {
        this.services = services;
        this.hierarchy = hierarchy;
        this.logger = logger;
    }

    Services services() {
        return this.services;
    }

    static String viewNode(InspectLayout.Kind kind) {
        return kind == InspectLayout.Kind.INVENTORY ? StaffNodes.INVSEE : StaffNodes.ECSEE;
    }

    /**
     * Whether {@code staff} may take and delete items in the view: the edit permission, and the staff hierarchy (no
     * emptying the inventory of staff of the same or a higher weight). Any thread.
     */
    boolean mayEdit(Player staff, Player target, InspectLayout.Kind kind) {
        return staff.hasPermission(editNode(kind)) && this.hierarchy.allowsNow(staff, target);
    }

    static String editNode(InspectLayout.Kind kind) {
        return kind == InspectLayout.Kind.INVENTORY ? StaffNodes.INVSEE_EDIT : StaffNodes.ECSEE_EDIT;
    }

    private static String auditPrefix(InspectLayout.Kind kind) {
        return kind == InspectLayout.Kind.INVENTORY ? "staff.invsee." : "staff.ecsee.";
    }

    private static Inventory container(Player target, InspectLayout.Kind kind) {
        return kind == InspectLayout.Kind.INVENTORY ? target.getInventory() : target.getEnderChest();
    }

    /** Copies the target's items on the target's thread. Fails if the target leaves first. */
    CompletableFuture<View> snapshot(Player target, InspectLayout.Kind kind) {
        return this.services.scheduler().supplyOnEntity(target, () -> {
            Inventory container = container(target, kind);
            List<ItemStack> items = new ArrayList<>(kind.sourceSize());
            for (int i = 0; i < kind.sourceSize(); i++) {
                ItemStack item = container.getItem(i);
                items.add(item == null || item.isEmpty() ? null : item.clone());
            }
            return new View(new InspectSnapshot<>(kind, items, System.currentTimeMillis()), target.getHealth(),
                target.getFoodLevel(), target.getLevel(), target.getGameMode());
        });
    }

    /** Opens the read-only GUI for {@code staff} (audited). */
    void open(Player staff, Player target, InspectLayout.Kind kind) {
        this.services.audit().record(staff.getUniqueId().toString(), auditPrefix(kind) + "open", target.getUniqueId().toString(), null);
        reopen(staff, target, kind);
    }

    /** Opens the GUI again with fresh items, after a change made from it. */
    void reopen(Player staff, Player target, InspectLayout.Kind kind) {
        snapshot(target, kind).whenComplete((view, error) -> {
            if (error != null) {
                this.services.messenger().send(staff, StaffMessages.INSPECT_GONE, Arg.text("name", target.getName()));
                return;
            }
            this.services.scheduler().entity(staff, () -> new InspectMenu(this.services.menus(), staff, this, target, kind, view).open(), null);
        });
    }

    /**
     * Takes the item in {@code index} if it is still exactly {@code expected}. {@code done} runs on the staff
     * member's thread when they are still online.
     */
    void take(Player staff, Player target, InspectLayout.Kind kind, int index, ItemStack expected, Consumer<TakeOutcome> done) {
        UUID staffId = staff.getUniqueId();
        CompletableFuture<ItemStack> removal = this.services.scheduler().supplyOnEntity(target, () -> {
            Inventory container = container(target, kind);
            ItemStack taken = InspectSnapshot.takeIfUnchanged(expected, container.getItem(index),
                () -> container.setItem(index, null), ItemStack::equals, ItemStack::isEmpty);
            if (taken != null && this.services.core().get().savePlayerAfterTrade()) {
                target.saveData();
            }
            return taken == null ? null : taken.clone();
        });
        removal.whenComplete((taken, error) -> {
            if (error != null) {
                this.services.scheduler().entity(staff, () -> {
                    this.services.messenger().send(staff, StaffMessages.INSPECT_GONE, Arg.text("name", target.getName()));
                    done.accept(TakeOutcome.GONE);
                }, null);
                return;
            }
            if (taken == null) {
                this.services.scheduler().entity(staff, () -> {
                    this.services.messenger().send(staff, StaffMessages.INSPECT_CHANGED);
                    done.accept(TakeOutcome.CHANGED);
                }, null);
                return;
            }
            String details = taken.getType().getKey().asString() + " x" + taken.getAmount() + " from slot " + index;
            this.services.audit().record(staffId.toString(), auditPrefix(kind) + "take", target.getUniqueId().toString(), details);
            Task task = this.services.scheduler().entity(staff, () -> {
                give(staff, taken);
                this.services.messenger().send(staff, StaffMessages.INSPECT_TAKEN, Arg.number("amount", taken.getAmount()),
                    Arg.component("item", StaffText.itemName(taken)), Arg.text("name", target.getName()));
                done.accept(TakeOutcome.TAKEN);
            }, () -> toClaimBox(staffId, taken));
            if (task == Task.NONE) {
                toClaimBox(staffId, taken);
            }
        });
    }

    /** On the staff member's thread: into their inventory, the rest into their claim box. */
    private void give(Player staff, ItemStack item) {
        Map<Integer, ItemStack> rest = staff.getInventory().addItem(item.clone());
        if (!rest.isEmpty()) {
            for (ItemStack left : rest.values()) {
                toClaimBox(staff.getUniqueId(), left);
            }
            this.services.messenger().send(staff, StaffMessages.INSPECT_CLAIM_BOX);
        }
        if (this.services.core().get().savePlayerAfterTrade()) {
            staff.saveData();
        }
    }

    private void toClaimBox(UUID owner, ItemStack item) {
        TransactionResult result = this.services.deliveries().give(owner, "staff", "invsee", item, owner.toString());
        if (!result.success()) {
            this.logger.log(Level.SEVERE, "An item taken with /invsee could not be put in the claim box of " + owner
                + " (" + result.status() + "); item data: " + Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
    }

    /** Deletes everything in the target's inventory or ender chest on their thread; completes with the stack count. */
    CompletableFuture<Integer> clear(Player staff, Player target, InspectLayout.Kind kind) {
        return this.services.scheduler().supplyOnEntity(target, () -> {
            Inventory container = container(target, kind);
            int stacks = 0;
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < kind.sourceSize(); i++) {
                ItemStack item = container.getItem(i);
                if (item != null && !item.isEmpty()) {
                    stacks++;
                    if (summary.length() < 900) {
                        summary.append(item.getType().getKey().getKey()).append(" x").append(item.getAmount()).append(", ");
                    }
                    container.setItem(i, null);
                }
            }
            if (stacks > 0 && this.services.core().get().savePlayerAfterTrade()) {
                target.saveData();
            }
            this.services.audit().record(staff.getUniqueId().toString(), auditPrefix(kind) + "clear", target.getUniqueId().toString(),
                stacks + " stacks: " + summary);
            return stacks;
        });
    }
}
