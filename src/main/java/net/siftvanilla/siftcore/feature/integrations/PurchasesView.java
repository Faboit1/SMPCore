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
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
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
    /** Purchases per dialog page (two lines each). */
    static final int PAGE_SIZE = 6;
    /** The most purchases listed. */
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
                        open(player, player.getUniqueId(), 1);
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
                            open(viewer, target.get(), 1);
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

    /** One page of {@code owner}'s purchases for {@code viewer} (their own, or anyone's for staff). */
    void open(Player viewer, UUID owner, int page) {
        Lang lang = this.services.lang();
        boolean own = viewer.getUniqueId().equals(owner);
        if (!own && !viewer.hasPermission(StoreCommands.PERMISSION)) {
            owner = viewer.getUniqueId();
            own = true;
        }
        String name = name(owner);
        List<Delivery> purchases = this.store.history(owner, LIMIT);
        List<Component> lines = new ArrayList<>();
        int pages = PurchaseText.pages(purchases.size(), PAGE_SIZE);
        int current = Math.clamp(page, 1, pages);
        if (purchases.isEmpty()) {
            lines.addAll(own ? lang.lines(IntegrationsMessages.PURCHASES_EMPTY)
                : lang.lines(IntegrationsMessages.PURCHASES_EMPTY_OTHER, Arg.text("name", name)));
        } else {
            lines.addAll(lang.lines(IntegrationsMessages.PURCHASES_PAGE, Arg.number("page", current), Arg.number("pages", pages),
                Arg.number("count", purchases.size())));
            for (Delivery delivery : PurchaseText.page(purchases, current, PAGE_SIZE)) {
                lines.add(Component.empty());
                lines.addAll(entry(delivery));
            }
        }
        lines.add(Component.empty());
        lines.addAll(lang.lines(IntegrationsMessages.PURCHASES_HELP));
        UUID shown = owner;
        List<Button> buttons = new ArrayList<>(2);
        if (current > 1) {
            buttons.add(Button.of(lang.get(IntegrationsMessages.PURCHASES_PREVIOUS), s -> open(s.player(), shown, current - 1)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(lang.get(IntegrationsMessages.PURCHASES_NEXT), s -> open(s.player(), shown, current + 1)).width(150));
        }
        Component title = own ? lang.get(IntegrationsMessages.PURCHASES_TITLE)
            : lang.get(IntegrationsMessages.PURCHASES_TITLE_OTHER, Arg.text("name", name));
        this.services.dialogs().show(viewer, this.services.templates().list(title, lines, buttons, 2, null));
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
        for (Delivery delivery : purchases) {
            for (Component line : entry(delivery)) {
                sender.sendMessage(line);
            }
        }
    }

    /** One purchase: what it gave, then when, what became of it and its reference. */
    private List<Component> entry(Delivery delivery) {
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>(2);
        lines.addAll(lang.lines(IntegrationsMessages.PURCHASES_WHAT, Arg.component("what", this.what.apply(delivery))));
        Duration age = Duration.between(Instant.ofEpochMilli(delivery.time()), Instant.now());
        lines.addAll(lang.lines(IntegrationsMessages.PURCHASES_DETAIL, Arg.text("date", PurchaseText.date(delivery.time())),
            Arg.time("time", age.isNegative() ? Duration.ZERO : age), Arg.component("state", state(delivery)),
            Arg.text("ref", PurchaseText.shortRef(delivery.ref()))));
        return lines;
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
                        ? lang.get(IntegrationsMessages.PURCHASES_STATE_RUNNING, Arg.time("time", booster.get().left()))
                        : lang.get(IntegrationsMessages.PURCHASES_STATE_QUEUED, Arg.number("position", booster.get().position()));
                }
                yield lang.get(IntegrationsMessages.PURCHASES_STATE_DELIVERED);
            }
        };
    }
}
