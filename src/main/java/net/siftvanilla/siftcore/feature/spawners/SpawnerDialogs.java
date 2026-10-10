package net.siftvanilla.siftcore.feature.spawners;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.WorldNames;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * {@code /spawners}, in the dialog style: one line with the totals, then a button per spawner ("Zombie x12: 28% full";
 * where it is and what it stores in the tooltip), all on one page (the dialog scrolls, up to {@code list-limit}), and a
 * switch to the teammates' spawners the player may use too. A spawner's page shows its stack, storage, XP and status,
 * with Open storage (its tooltip says where the spawner is, who owns it and what it makes), which opens the storage
 * when the player is close enough.
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

    /** Shows the list of the player's own spawners; {@code back} null means the footer button closes. */
    void openList(Player player, Button.Handler back) {
        this.services.dialogs().show(player, list(player, false, back));
    }

    /** The list: the player's own spawners, or ({@code team}) those of their teammates, which they may use too. */
    View list(Player player, boolean team, Button.Handler back) {
        Lang lang = lang();
        boolean inTeam = this.service.inTeam(player.getUniqueId());
        boolean showTeam = team && inTeam;
        List<ManagedSpawner> shown = showTeam ? this.service.teamSpawners(player.getUniqueId())
            : this.service.registry().ownedBy(player.getUniqueId());
        Component title = lang.get(showTeam ? SpawnersMessages.TEAM_LIST_TITLE : SpawnersMessages.LIST_TITLE);
        List<Component> lines = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (shown.isEmpty()) {
            lines.addAll(lang.lines(showTeam ? SpawnersMessages.TEAM_LIST_EMPTY : SpawnersMessages.LIST_EMPTY));
        } else {
            long stacked = 0;
            long stored = 0;
            long xp = 0;
            for (ManagedSpawner spawner : shown) {
                stacked += spawner.stack();
                stored += spawner.storage.used();
                xp += spawner.xp();
            }
            lines.add(lang.get(showTeam ? SpawnersMessages.TEAM_LIST_SUMMARY : SpawnersMessages.LIST_SUMMARY,
                Arg.value("count", shown.size()), Arg.value("stacked", stacked), Arg.value("stored", stored), Arg.value("xp", xp)));
            int limit = this.service.settings().listLimit();
            if (shown.size() > limit) {
                lines.add(lang.get(SpawnersMessages.LIST_LIMIT, Arg.value("limit", limit), Arg.value("count", shown.size())));
            }
            Button.Handler self = sub -> sub.show(list(sub.player(), showTeam, back));
            for (ManagedSpawner spawner : shown.subList(0, Math.min(shown.size(), limit))) {
                buttons.add(spawnerButton(spawner, showTeam, self));
            }
        }
        if (inTeam) {
            buttons.add(Button.of(lang.get(showTeam ? SpawnersMessages.LIST_SHOW_OWN : SpawnersMessages.LIST_SHOW_TEAM),
                lang.get(showTeam ? SpawnersMessages.LIST_SHOW_OWN_TOOLTIP : SpawnersMessages.LIST_SHOW_TEAM_TOOLTIP),
                sub -> sub.show(list(sub.player(), !showTeam, back))));
        }
        return this.services.templates().column(title, lines, buttons, back);
    }

    /** "Zombie x12: 28% full" (full in red), with where it is, whose it is and what it stores in the tooltip. */
    private Button spawnerButton(ManagedSpawner spawner, boolean team, Button.Handler back) {
        Lang lang = lang();
        long id = spawner.id;
        String name = this.service.name(spawner.mob);
        long capacity = this.service.capacity(spawner, spawner.stack());
        long used = spawner.storage.used();
        Component label = lang.get(SpawnersMessages.LIST_BUTTON, Arg.text("name", name), Arg.text("stack", Lang.number(spawner.stack())));
        Component fullness = used >= capacity && capacity > 0
            ? lang.get(SpawnersMessages.LIST_FULL)
            : lang.get(SpawnersMessages.LIST_FULLNESS, Arg.value("percent", percent(used, capacity)));
        List<Component> tooltip = new ArrayList<>();
        if (team) {
            tooltip.add(lang.get(SpawnersMessages.LIST_TOOLTIP_OWNER, Arg.text("owner", this.service.ownerName(spawner.owner))));
        }
        tooltip.addAll(lang.lines(SpawnersMessages.LIST_TOOLTIP, Arg.text("location", spawner.pos.coordinates()),
            Arg.text("world", WorldNames.of(lang, spawner.pos.world())), Arg.value("used", used), Arg.value("capacity", capacity),
            Arg.value("xp", spawner.xp())));
        return this.services.templates().choiceButton(label, fullness, Templates.lines(tooltip), sub -> showDetails(sub, id, back));
    }

    /** How full a storage is, in whole percent (rounded down, so 100 means really full). */
    static long percent(long used, long capacity) {
        if (capacity <= 0) {
            return 0;
        }
        if (used >= capacity) {
            return 100;
        }
        return Math.max(0, used / (double) capacity >= 0.995 ? 99 : (long) Math.floor(used * 100.0 / capacity));
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

    /**
     * A spawner's page: its stack, storage (and what it sells for), XP and status, then Open storage, whose tooltip
     * says who owns it, where it is and how much it makes.
     */
    View details(Player player, ManagedSpawner spawner, Button.Handler back) {
        Lang lang = lang();
        SpawnersSettings s = this.service.settings();
        ManagedSpawner.State state = this.service.state(spawner);
        MobDef def = s.mob(spawner.mob);
        SpawnerService.Sale sale = this.service.price(state.items(), this.worth.rate(player));
        long value = sale == null ? 0 : sale.total();
        double rate = def == null ? 0 : def.itemsPerHour(s.interval().toMillis() / 1000.0) * state.stack();
        String name = this.service.name(spawner.mob);
        List<Component> lines = new ArrayList<>(lang.lines(SpawnersMessages.DETAILS_BODY,
            Arg.text("name", name),
            Arg.value("stack", state.stack()),
            Arg.value("cap", this.service.stackCap(player, spawner.mob)),
            Arg.value("used", state.used()),
            Arg.value("capacity", this.service.capacity(spawner, state.stack())),
            Arg.value("xp", state.xp()),
            Arg.value("xp-cap", this.service.xpCapacity(state.stack())),
            Arg.money("value", value)));
        lines.add(status(spawner, def, s));
        Component tooltip = Templates.lines(lang.lines(SpawnersMessages.DETAILS_OPEN_TOOLTIP,
            Arg.text("owner", this.service.ownerName(spawner.owner)),
            Arg.text("location", spawner.pos.coordinates()),
            Arg.text("world", WorldNames.of(lang, spawner.pos.world())),
            Arg.value("rate", Math.round(rate)),
            Arg.value("range", Math.max(s.remoteRange(), 6))));
        Button open = Button.of(lang.get(SpawnersMessages.DETAILS_OPEN), tooltip, sub -> openStorage(sub, spawner, back));
        return this.services.templates().column(lang.get(SpawnersMessages.DETAILS_TITLE, Arg.text("name", name)), lines, List.of(open),
            back);
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
        return lang.get(SpawnersMessages.STATUS_IDLE, Arg.value("radius", s.radius()));
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
                this.service.tell(player, SpawnersMessages.TOO_FAR, Arg.value("range", Math.max(range, 6)));
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
