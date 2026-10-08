package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /alts} (accounts whose last address matches, from the salted address hashes the player directory keeps) and
 * {@code /whois} (identity, where they are, money, alts and what they are punished with right now).
 */
final class Lookups {

    /** Most accounts listed by /alts. */
    static final int MAX_ALTS = 50;

    /** Where an online player is, read on their thread. */
    private record Whereabouts(String world, int x, int y, int z, int ping, GameMode mode) {
    }

    private final Services services;
    private final StaffStore store;
    private final Punishments punishments;
    private final FreezeService freeze;
    private final VanishService vanish;
    private final HistoryView history;
    private final Inspector inspector;
    private final Logger logger;

    Lookups(Services services, StaffStore store, Punishments punishments, FreezeService freeze, VanishService vanish,
            HistoryView history, Inspector inspector, Logger logger) {
        this.services = services;
        this.store = store;
        this.punishments = punishments;
        this.freeze = freeze;
        this.vanish = vanish;
        this.history = history;
        this.inspector = inspector;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ /alts

    void alts(CommandSender sender, UUID target) {
        PlayerDirectory directory = this.services.directory();
        String name = directory.name(target);
        this.services.audit().record(Actor.of(sender).id(), "staff.alts", target.toString(), null);
        String hash = directory.ipHash(target);
        if (hash == null) {
            this.services.messenger().chat(sender, StaffMessages.ALTS_UNKNOWN, Arg.text("name", name));
            return;
        }
        this.store.sharingAddress(hash, MAX_ALTS + 1).whenComplete((rows, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not look up the accounts of " + name, error);
                this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            List<PlayerDirectory.Known> others = new ArrayList<>();
            for (PlayerDirectory.Known known : rows) {
                if (!known.uuid().equals(target) && others.size() < MAX_ALTS) {
                    others.add(known);
                }
            }
            if (others.isEmpty()) {
                this.services.messenger().chat(sender, StaffMessages.ALTS_NONE, Arg.text("name", name));
                return;
            }
            this.services.messenger().chat(sender, StaffMessages.ALTS_HEADER, Arg.text("name", name), Arg.number("count", others.size()));
            long now = System.currentTimeMillis();
            for (PlayerDirectory.Known known : others) {
                this.services.messenger().chat(sender, StaffMessages.ALTS_LINE, Arg.text("name", known.name()),
                    Arg.component("seen", seen(known.uuid(), known.lastSeen(), now)), Arg.component("status", status(known.uuid())));
            }
        });
    }

    private Component seen(UUID player, long lastSeen, long now) {
        Lang lang = this.services.lang();
        return Bukkit.getPlayer(player) != null
            ? lang.get(StaffMessages.SEEN_NOW)
            : lang.get(StaffMessages.SEEN_AGO, Arg.time("ago", StaffText.since(lastSeen, now)));
    }

    private Component status(UUID player) {
        Lang lang = this.services.lang();
        List<Component> parts = new ArrayList<>(2);
        if (this.punishments.activeBan(player).isPresent()) {
            parts.add(lang.get(StaffMessages.STATUS_BANNED));
        }
        if (this.punishments.activeMute(player).isPresent()) {
            parts.add(lang.get(StaffMessages.STATUS_MUTED));
        }
        return Component.join(JoinConfiguration.noSeparators(), parts);
    }

    // ------------------------------------------------------------------ /whois

    void whois(CommandSender sender, UUID target) {
        PlayerDirectory directory = this.services.directory();
        Optional<PlayerDirectory.Known> known = directory.get(target);
        String name = directory.name(target);
        this.services.audit().record(Actor.of(sender).id(), "staff.whois", target.toString(), null);
        Player online = Bukkit.getPlayer(target);
        CompletableFuture<Whereabouts> where = online == null
            ? CompletableFuture.completedFuture(null)
            : this.services.scheduler().supplyOnEntity(online, () -> {
                Location location = online.getLocation();
                return new Whereabouts(location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ(), online.getPing(), online.getGameMode());
            }).exceptionally(error -> null);
        String hash = directory.ipHash(target);
        CompletableFuture<Integer> alts = hash == null
            ? CompletableFuture.completedFuture(0)
            : this.store.sharingAddress(hash, 1000).thenApply(rows -> (int) rows.stream().filter(k -> !k.uuid().equals(target)).count())
                .exceptionally(error -> 0);
        where.thenCombine(alts, (whereabouts, altCount) -> lines(target, name, known.orElse(null), whereabouts, altCount))
            .whenComplete((lines, error) -> {
                if (error != null) {
                    this.logger.log(Level.WARNING, "Could not look up " + name, error);
                    this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                    return;
                }
                if (sender instanceof Player viewer) {
                    dialog(viewer, target, name, lines);
                } else {
                    this.services.messenger().chat(sender, StaffMessages.WHOIS_HEADER, Arg.text("name", name));
                    for (Component line : lines) {
                        sender.sendMessage(line);
                    }
                }
            });
    }

    private List<Component> lines(UUID target, String name, PlayerDirectory.Known known, Whereabouts where, int alts) {
        Lang lang = this.services.lang();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        Component seen = where != null || Bukkit.getPlayer(target) != null
            ? lang.get(StaffMessages.SEEN_NOW)
            : lang.get(StaffMessages.SEEN_AGO, Arg.time("ago", StaffText.since(known == null ? now : known.lastSeen(), now)));
        lines.addAll(lang.lines(StaffMessages.WHOIS_IDENTITY, Arg.text("uuid", target.toString()),
            Arg.text("first", known == null ? "-" : StaffText.date(known.firstJoin())), Arg.component("seen", seen)));
        if (where != null) {
            lines.addAll(lang.lines(StaffMessages.WHOIS_ONLINE, Arg.text("world", where.world()), Arg.text("x", Integer.toString(where.x())),
                Arg.text("y", Integer.toString(where.y())), Arg.text("z", Integer.toString(where.z())), Arg.number("ping", where.ping()),
                Arg.component("mode", StaffText.gameMode(where.mode()))));
        }
        lines.add(lang.get(StaffMessages.WHOIS_MONEY, Arg.money("balance", this.services.ledger().balance(target, Currency.MONEY)),
            Arg.number("shards", this.services.ledger().balance(target, Currency.SHARDS))));
        lines.add(lang.get(StaffMessages.WHOIS_ALTS, Arg.number("count", alts)));
        lines.add(lang.get(StaffMessages.WHOIS_NOW, Arg.component("list", now(target, now))));
        return lines;
    }

    /** What the player is under right now: ban, mute, freeze, vanish; or "no punishments". */
    private Component now(UUID target, long now) {
        Lang lang = this.services.lang();
        List<Component> parts = new ArrayList<>();
        this.punishments.activeBan(target).ifPresent(ban -> parts.add(lang.get(StaffMessages.WHOIS_BANNED, Arg.component("time", left(ban, now)))));
        this.punishments.activeMute(target).ifPresent(mute -> parts.add(lang.get(StaffMessages.WHOIS_MUTED, Arg.component("time", left(mute, now)))));
        if (this.freeze.frozen(target)) {
            parts.add(lang.get(StaffMessages.WHOIS_FROZEN));
        }
        if (this.vanish.vanished(target)) {
            parts.add(lang.get(StaffMessages.WHOIS_VANISHED));
        }
        if (parts.isEmpty()) {
            return lang.get(StaffMessages.WHOIS_CLEAN);
        }
        return Component.join(JoinConfiguration.separator(Component.text(", ")), parts);
    }

    private Component left(Punishment punishment, long now) {
        Lang lang = this.services.lang();
        return punishment.permanent()
            ? lang.get(StaffMessages.PERMANENT)
            : lang.get(StaffMessages.WHOIS_LEFT, Arg.time("time", punishment.remaining(now)));
    }

    private void dialog(Player viewer, UUID target, String name, List<Component> lines) {
        Lang lang = this.services.lang();
        List<Button> buttons = new ArrayList<>();
        if (viewer.hasPermission(StaffNodes.HISTORY)) {
            buttons.add(Button.of(lang.get(StaffMessages.WHOIS_HISTORY), s -> {
                if (s.player().hasPermission(StaffNodes.HISTORY)) {
                    this.history.show(s.player(), target, name);
                }
            }).width(150));
        }
        Player online = Bukkit.getPlayer(target);
        if (online != null && !online.equals(viewer)) {
            if (viewer.hasPermission(StaffNodes.INVSEE)) {
                buttons.add(Button.of(lang.get(StaffMessages.WHOIS_INVENTORY), s -> inspect(s.player(), target, InspectLayout.Kind.INVENTORY)).width(150));
            }
            if (viewer.hasPermission(StaffNodes.ECSEE)) {
                buttons.add(Button.of(lang.get(StaffMessages.WHOIS_ENDER), s -> inspect(s.player(), target, InspectLayout.Kind.ENDER_CHEST)).width(150));
            }
        }
        this.services.dialogs().show(viewer, this.services.templates().list(lang.get(StaffMessages.WHOIS_TITLE, Arg.text("name", name)),
            lines, buttons, 2, null));
    }

    private void inspect(Player viewer, UUID target, InspectLayout.Kind kind) {
        Player online = Bukkit.getPlayer(target);
        if (!viewer.hasPermission(Inspector.viewNode(kind))) {
            return;
        }
        if (online == null) {
            this.services.messenger().send(viewer, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", this.services.directory().name(target)));
            return;
        }
        this.inspector.open(viewer, online, kind);
    }
}
