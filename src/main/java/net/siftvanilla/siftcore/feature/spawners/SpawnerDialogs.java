package net.siftvanilla.siftcore.feature.spawners;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * {@code /spawners}: a dialog listing the player's spawners (mob, stack, where, how full), or their teammates' spawners
 * they may use too, a details page per spawner with its status, and a button that opens its storage when the player
 * is close enough.
 */
final class SpawnerDialogs {

    private final Services services;
    private final SpawnerService service;
    private final StorageMenus menus;
    private final WorthLookup worth;

    SpawnerDialogs(Services services, SpawnerService service, StorageMenus menus, WorthLookup worth) {
        this.services = services;
        this.service = service;
        this.menus = menus;
        this.worth = worth;
    }

    private Lang lang() {
        return this.services.lang();
    }

    /** Shows the list of the player's own spawners (page from 1); {@code back} null means the footer button closes. */
    void openList(Player player, int page, Button.Handler back) {
        this.services.dialogs().show(player, list(player, false, page, back));
    }

    /**
     * The list: the player's own spawners, or ({@code team}) those of their teammates, which they may use too. A
     * summary, then one row per spawner of the page (its item, stack, where it is and how full), a button per
     * spawner for its details, paging, and a switch between the two lists while the player is in a team.
     */
    View list(Player player, boolean team, int page, Button.Handler back) {
        Lang lang = lang();
        boolean inTeam = this.service.inTeam(player.getUniqueId());
        boolean showTeam = team && inTeam;
        List<ManagedSpawner> shown = showTeam ? this.service.teamSpawners(player.getUniqueId())
            : this.service.registry().ownedBy(player.getUniqueId());
        int pageSize = this.service.settings().pageSize();
        int pages = Math.max(1, (shown.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        Component title = lang.get(showTeam ? SpawnersMessages.TEAM_LIST_TITLE : SpawnersMessages.LIST_TITLE);
        List<Body> body = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (shown.isEmpty()) {
            for (Component line : lang.lines(showTeam ? SpawnersMessages.TEAM_LIST_EMPTY : SpawnersMessages.LIST_EMPTY)) {
                body.add(Body.text(line));
            }
        } else {
            long stacked = 0;
            long stored = 0;
            long xp = 0;
            for (ManagedSpawner spawner : shown) {
                stacked += spawner.stack();
                stored += spawner.storage.used();
                xp += spawner.xp();
            }
            List<Component> summary = new ArrayList<>(lang.lines(showTeam ? SpawnersMessages.TEAM_LIST_SUMMARY : SpawnersMessages.LIST_SUMMARY,
                Arg.number("count", shown.size()), Arg.number("stacked", stacked), Arg.number("stored", stored), Arg.number("xp", xp)));
            if (pages > 1) {
                summary.add(lang.get(SpawnersMessages.LIST_PAGE, Arg.number("page", current), Arg.number("pages", pages)));
            }
            body.add(Body.text(Component.join(JoinConfiguration.newlines(), summary)));
            Button.Handler self = sub -> sub.show(list(sub.player(), showTeam, current, back));
            int from = (current - 1) * pageSize;
            for (ManagedSpawner spawner : shown.subList(from, Math.min(shown.size(), from + pageSize))) {
                long id = spawner.id;
                String name = this.service.name(spawner.mob);
                long capacity = this.service.capacity(spawner, spawner.stack());
                Component row = showTeam
                    ? lang.get(SpawnersMessages.TEAM_LIST_ENTRY, Arg.text("name", name), Arg.number("stack", spawner.stack()),
                        Arg.text("owner", this.service.ownerName(spawner.owner)), Arg.text("location", spawner.pos.coordinates()),
                        Arg.text("world", spawner.pos.world()), Arg.number("used", spawner.storage.used()), Arg.number("capacity", capacity))
                    : lang.get(SpawnersMessages.LIST_ENTRY, Arg.text("name", name), Arg.number("stack", spawner.stack()),
                        Arg.text("location", spawner.pos.coordinates()), Arg.text("world", spawner.pos.world()),
                        Arg.number("used", spawner.storage.used()), Arg.number("capacity", capacity));
                body.add(Body.item(this.service.items().item(spawner.mob, (int) Math.min(64, spawner.stack())), row));
                Component label = lang.get(SpawnersMessages.LIST_BUTTON, Arg.text("name", name), Arg.text("stack", Lang.number(spawner.stack())));
                Component tooltip = lang.get(SpawnersMessages.LIST_TOOLTIP, Arg.text("location", spawner.pos.coordinates()),
                    Arg.text("world", spawner.pos.world()), Arg.number("used", spawner.storage.used()), Arg.number("capacity", capacity));
                buttons.add(Button.of(label, tooltip, sub -> showDetails(sub, id, self)).width(150));
            }
            if (current > 1) {
                buttons.add(Button.of(lang.get(SpawnersMessages.PAGE_PREVIOUS), sub -> sub.show(list(sub.player(), showTeam, current - 1, back)))
                    .width(150));
            }
            if (current < pages) {
                buttons.add(Button.of(lang.get(SpawnersMessages.PAGE_NEXT), sub -> sub.show(list(sub.player(), showTeam, current + 1, back)))
                    .width(150));
            }
        }
        if (inTeam) {
            buttons.add(Button.of(lang.get(showTeam ? SpawnersMessages.LIST_SHOW_OWN : SpawnersMessages.LIST_SHOW_TEAM),
                sub -> sub.show(list(sub.player(), !showTeam, 1, back))).width(150));
        }
        return this.services.templates().listWithBody(title, body, buttons, 2, back);
    }

    private void showDetails(Submission submission, long id, Button.Handler back) {
        ManagedSpawner spawner = this.service.registry().byId(id);
        if (spawner == null || spawner.removed()) {
            this.service.tell(submission.player(), SpawnersMessages.GONE);
            back.handle(submission);
            return;
        }
        submission.show(details(submission.player(), spawner, back));
    }

    View details(Player player, ManagedSpawner spawner, Button.Handler back) {
        Lang lang = lang();
        SpawnersSettings s = this.service.settings();
        ManagedSpawner.State state = this.service.state(spawner);
        MobDef def = s.mob(spawner.mob);
        double multiplier = this.worth.multiplier(player);
        SpawnerService.Sale sale = this.service.price(state.items(), multiplier);
        long value = sale == null ? 0 : sale.total();
        double rate = def == null ? 0 : def.itemsPerHour(s.interval().toMillis() / 1000.0) * state.stack();
        List<Component> lines = new ArrayList<>(lang.lines(SpawnersMessages.DETAILS_BODY,
            Arg.text("name", this.service.name(spawner.mob)),
            Arg.number("stack", state.stack()),
            Arg.number("cap", this.service.stackCap(player, spawner.mob)),
            Arg.text("owner", this.service.ownerName(spawner.owner)),
            Arg.text("location", spawner.pos.coordinates()),
            Arg.text("world", spawner.pos.world()),
            Arg.number("used", state.used()),
            Arg.number("capacity", this.service.capacity(spawner, state.stack())),
            Arg.number("xp", state.xp()),
            Arg.number("xp-cap", this.service.xpCapacity(state.stack())),
            Arg.money("value", value),
            Arg.number("rate", Math.round(rate))));
        lines.add(status(spawner, def, s));
        Button open = Button.of(lang.get(SpawnersMessages.DETAILS_OPEN), sub -> openStorage(sub, spawner, back)).width(Templates.WIDE);
        return this.services.templates().list(lang.get(SpawnersMessages.DETAILS_TITLE, Arg.text("name", this.service.name(spawner.mob))),
            lines, List.of(open), 1, back);
    }

    private Component status(ManagedSpawner spawner, MobDef def, SpawnersSettings s) {
        Lang lang = lang();
        if (def == null || !def.enabled()) {
            return lang.get(SpawnersMessages.STATUS_DISABLED);
        }
        long recent = s.interval().toMillis() * 2 + 2_000;
        if (System.currentTimeMillis() - spawner.lastActive() <= recent) {
            return lang.get(spawner.lastFull() ? SpawnersMessages.STATUS_FULL : SpawnersMessages.STATUS_ACTIVE);
        }
        return lang.get(SpawnersMessages.STATUS_IDLE, Arg.number("radius", s.radius()));
    }

    /** Opens the storage from the dialog: staff anywhere, others within the configured range in the same world. */
    private void openStorage(Submission submission, ManagedSpawner spawner, Button.Handler back) {
        Player player = submission.player();
        if (spawner.removed()) {
            this.service.tell(player, SpawnersMessages.GONE);
            submission.close();
            return;
        }
        if (!this.service.allowed(player, spawner)) {
            submission.close();
            return;
        }
        if (!this.service.outOfCombat(player)) {
            submission.show(details(player, spawner, back));
            return;
        }
        int range = this.service.settings().remoteRange();
        if (!player.hasPermission(SpawnerService.BYPASS)) {
            Location at = player.getLocation();
            boolean near = at.getWorld().getName().equals(spawner.pos.world())
                && spawner.pos.distanceSquared(at.getX(), at.getY(), at.getZ()) <= (double) Math.max(range, 6) * Math.max(range, 6);
            if (!near) {
                this.service.tell(player, SpawnersMessages.TOO_FAR, Arg.number("range", Math.max(range, 6)));
                submission.show(details(player, spawner, back));
                return;
            }
        }
        this.menus.open(player, spawner, () -> {
            player.closeInventory();
            if (spawner.removed()) {
                this.service.tell(player, SpawnersMessages.GONE);
                return;
            }
            this.services.dialogs().show(player, details(player, spawner, back));
        });
    }
}
