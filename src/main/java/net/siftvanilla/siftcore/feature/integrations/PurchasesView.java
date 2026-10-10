package net.siftvanilla.siftcore.feature.integrations;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /purchases}: a player's own store purchases, newest first, read only: when, what (a rank and its length, a
 * booster, money, shards or keys), the store reference (shortened) and what became of it (delivered, being delivered,
 * taken back, or for a booster whether it runs or waits). Staff with {@code siftcore.admin.store} can look at anyone's
 * with {@code /purchases <player>}, also from the console; players only ever see their own.
 */
final class PurchasesView {

    static final String COMMAND = "siftcore.command.purchases";
    /** The most purchases the dialog lists (the newest; the console lists up to {@link #LIMIT}). */
    static final int SHOWN = 100;
    /** The most purchases listed in the console. */
    static final int LIMIT = 200;

    private final Services services;
    private final StoreService store;
    private final Function<Delivery, Component> what;

    /**
     * @param what what a delivery gave, as the store commands word it
     */
    PurchasesView(Services services, StoreService store, Function<Delivery, Component> what) {
        this.services = services;
        this.store = store;
        this.what = what;
    }

    static void declare(Permissions perms) {
        perms.declare(COMMAND, "See your store purchases with /purchases", true);
    }

    SiftCommand command() {
        return new SimpleCommand("purchases", List.of("mypurchases"), "Shows your store purchases", COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(COMMAND))
                .executes(ctx -> {
                    Player player = this.services.commands().player(ctx);
                    if (player != null) {
                        open(player, player.getUniqueId());
                    }
                    return CommandSupport.OK;
                })
                .then(this.services.commands().knownPlayer("player")
                    .requires(CommandSupport.permission(StoreCommands.PERMISSION))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        // A UUID works too, for buyers who have not joined yet (their deliveries wait as pending).
                        Optional<UUID> target = StoreRules.uuid(StringArgumentType.getString(ctx, "player"));
                        if (target.isEmpty()) {
                            target = this.services.commands().known(ctx, "player");
                        }
                        if (target.isEmpty()) {
                            return CommandSupport.OK;
                        }
                        if (sender instanceof Player viewer) {
                            open(viewer, target.get());
                        } else {
                            print(sender, target.get());
                        }
                        return CommandSupport.OK;
                    })));
    }

    /** A buyer's name, or "the server" for boosters from the server itself (the console, community goals). */
    private String name(UUID owner) {
        return StoreService.SERVER.equals(owner) ? this.services.lang().plain(IntegrationsMessages.WHO_SERVER)
            : this.services.directory().name(owner);
    }

    /**
     * {@code owner}'s purchases for {@code viewer} (their own, or anyone's for staff), newest first: one button per
     * purchase showing what it gave, with when, what became of it and its order id in the tooltip. No pages: the
     * newest {@value #SHOWN} show and the dialog scrolls.
     */
    void open(Player viewer, UUID owner) {
        Lang lang = this.services.lang();
        boolean own = viewer.getUniqueId().equals(owner);
        if (!own && !viewer.hasPermission(StoreCommands.PERMISSION)) {
            owner = viewer.getUniqueId();
            own = true;
        }
        String name = name(owner);
        List<Delivery> purchases = this.store.history(owner, SHOWN + 1);
        List<Component> lines = new ArrayList<>(1);
        if (purchases.isEmpty()) {
            lines.addAll(own ? lang.lines(IntegrationsMessages.PURCHASES_EMPTY)
                : lang.lines(IntegrationsMessages.PURCHASES_EMPTY_OTHER, Arg.text("name", name)));
        } else if (purchases.size() > SHOWN) {
            lines.addAll(lang.lines(IntegrationsMessages.PURCHASES_NEWEST, Arg.text("count", Lang.number(SHOWN))));
        } else {
            lines.addAll(lang.lines(IntegrationsMessages.PURCHASES_COUNT, Arg.text("count", Lang.number(purchases.size()))));
        }
        UUID shown = owner;
        List<Button> buttons = new ArrayList<>(Math.min(SHOWN, purchases.size()));
        for (Delivery delivery : PurchaseText.newest(purchases, SHOWN)) {
            // Nothing to do with a purchase: clicking shows the list again.
            buttons.add(Button.of(lang.get(IntegrationsMessages.PURCHASES_WHAT, Arg.component("what", this.what.apply(delivery))),
                Templates.lines(details(delivery)), s -> open(s.player(), shown)));
        }
        Component title = own ? lang.get(IntegrationsMessages.PURCHASES_TITLE)
            : lang.get(IntegrationsMessages.PURCHASES_TITLE_OTHER, Arg.text("name", name));
        this.services.dialogs().show(viewer, this.services.templates().column(title, lines, buttons, null));
    }

    /** Every purchase of a player in chat (the console). */
    private void print(CommandSender sender, UUID owner) {
        String name = name(owner);
        List<Delivery> purchases = this.store.history(owner, LIMIT);
        if (purchases.isEmpty()) {
            sender.sendMessage(this.services.lang().get(IntegrationsMessages.PURCHASES_EMPTY_OTHER, Arg.text("name", name)));
            return;
        }
        this.services.messenger().chat(sender, IntegrationsMessages.PURCHASES_HEADER, Arg.text("name", name),
            Arg.number("count", purchases.size()));
        Lang lang = this.services.lang();
        for (Delivery delivery : purchases) {
            sender.sendMessage(lang.get(IntegrationsMessages.PURCHASES_WHAT, Arg.component("what", this.what.apply(delivery))));
            sender.sendMessage(lang.get(IntegrationsMessages.PURCHASES_DETAIL, detailArgs(delivery)));
        }
    }

    /** A purchase's tooltip: when, what became of it, its order id, and what to do when something is missing. */
    private List<Component> details(Delivery delivery) {
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>(4);
        Arg[] args = detailArgs(delivery);
        lines.add(lang.get(IntegrationsMessages.PURCHASES_WHEN, args[0], args[1]));
        lines.add(lang.get(IntegrationsMessages.PURCHASES_STATUS, args[2]));
        lines.add(lang.get(IntegrationsMessages.PURCHASES_REF, args[3]));
        lines.add(lang.get(IntegrationsMessages.PURCHASES_HELP));
        return lines;
    }

    /** The day, how long ago, the state and the shortened reference of a purchase. */
    private Arg[] detailArgs(Delivery delivery) {
        Duration age = Duration.between(Instant.ofEpochMilli(delivery.time()), Instant.now());
        return new Arg[] {
            Arg.text("date", PurchaseText.date(delivery.time())),
            Arg.text("time", Durations.format(age.isNegative() ? Duration.ZERO : age)),
            Arg.component("state", state(delivery)),
            Arg.text("ref", PurchaseText.shortRef(delivery.ref()))
        };
    }

    private Component state(Delivery delivery) {
        Lang lang = this.services.lang();
        return switch (delivery.state()) {
            case PENDING -> lang.get(IntegrationsMessages.PURCHASES_STATE_PENDING);
            case REVOKING -> lang.get(IntegrationsMessages.PURCHASES_STATE_REVOKING);
            case REVOKED -> lang.get(IntegrationsMessages.PURCHASES_STATE_REVOKED,
                Arg.text("reason", delivery.note() == null ? "-" : delivery.note()));
            case DONE -> {
                Optional<ServerBoosters.Status> booster = this.store.boosterStatus(delivery.ref());
                if (booster.isPresent()) {
                    yield booster.get().running()
                        ? lang.get(IntegrationsMessages.PURCHASES_STATE_RUNNING, Arg.text("time", Durations.format(booster.get().left())))
                        : lang.get(IntegrationsMessages.PURCHASES_STATE_QUEUED, Arg.text("position", Lang.number(booster.get().position())));
                }
                yield lang.get(IntegrationsMessages.PURCHASES_STATE_DELIVERED);
            }
        };
    }
}
