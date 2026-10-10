package net.siftvanilla.siftcore.feature.friends;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import org.bukkit.command.CommandSender;

/**
 * {@code /sift friends}: the staff tools. Reads run off the thread and show the stored truth, hidden and closed
 * requests included. Changes go through the same write units as players' actions, fire their events with the
 * {@code STAFF} cause, and are written to the audit log after they committed. Console friendly.
 */
final class FriendAdmin {

    static final String PERMISSION = "siftcore.admin.friends";
    static final int HISTORY_PAGE = 10;

    private final Services services;
    private final CommandSupport support;
    private final Messenger messenger;
    private final FriendService service;
    private final FriendStore store;
    private final ZoneId zone = ZoneId.systemDefault();

    FriendAdmin(Services services, FriendService service) {
        this.services = services;
        this.support = services.commands();
        this.messenger = services.messenger();
        this.service = service;
        this.store = service.store();
    }

    AdminFeature.AdminCommandPart part() {
        return () -> Commands.literal("friends")
            .requires(CommandSupport.permission(PERMISSION))
            .executes(ctx -> {
                this.messenger.chat(ctx.getSource().getSender(), FriendsMessages.STAFF_HELP);
                return CommandSupport.OK;
            })
            .then(Commands.literal("list").then(this.support.knownPlayer("player").executes(this::list)))
            .then(Commands.literal("requests").then(this.support.knownPlayer("player").executes(this::requests)))
            .then(Commands.literal("history").then(this.support.knownPlayer("player")
                .executes(ctx -> history(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 100_000))
                    .executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
            .then(Commands.literal("add").then(this.support.knownPlayer("a").then(this.support.knownPlayer("b").executes(this::add))))
            .then(Commands.literal("remove").then(this.support.knownPlayer("a").then(this.support.knownPlayer("b").executes(this::remove))))
            .then(Commands.literal("clear-requests").then(this.support.knownPlayer("player").executes(this::clear)));
    }

    private <T> void reply(CommandSender sender, CompletableFuture<T> read, Consumer<T> print) {
        read.whenComplete((value, error) -> {
            if (error != null) {
                this.services.plugin().getLogger().log(Level.WARNING, "A friends staff read failed", error);
                this.messenger.send(sender, FriendsMessages.STAFF_FAILED, Arg.text("reason", String.valueOf(error.getMessage())));
                return;
            }
            print.accept(value);
        });
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = this.support.known(ctx, "player");
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        UUID id = player.get();
        String name = this.service.name(id);
        reply(sender, this.store.staffFriends(id), view -> {
            List<FriendStore.FriendRow> rows = view.rows();
            // The limit the write units check: memory while the player is loaded, else their stored rank limit.
            int limit = this.service.limit(id, view.storedLimit());
            this.messenger.chat(sender, FriendsMessages.STAFF_LIST_HEADER, Arg.text("name", name), Arg.number("count", rows.size()),
                Arg.number("limit", limit));
            if (rows.isEmpty()) {
                this.messenger.chat(sender, FriendsMessages.STAFF_EMPTY);
            }
            for (FriendStore.FriendRow row : rows) {
                this.messenger.chat(sender, FriendsMessages.STAFF_LIST_ROW, Arg.text("friend", this.service.name(row.friend())),
                    Arg.text("date", TimeText.date(row.since(), this.zone)),
                    Arg.component("favourite", row.favourite()
                        ? this.services.lang().get(FriendsMessages.STAFF_LIST_FAVOURITE)
                        : net.kyori.adventure.text.Component.empty()));
            }
        });
        return CommandSupport.OK;
    }

    private int requests(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = this.support.known(ctx, "player");
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.service.name(player.get());
        reply(sender, this.store.requestRows(player.get()), rows -> {
            this.messenger.chat(sender, FriendsMessages.STAFF_REQUESTS_HEADER, Arg.text("name", name), Arg.number("count", rows.size()));
            if (rows.isEmpty()) {
                this.messenger.chat(sender, FriendsMessages.STAFF_EMPTY);
            }
            for (FriendStore.RequestRow row : rows) {
                this.messenger.chat(sender, FriendsMessages.STAFF_REQUESTS_ROW, Arg.text("sender", this.service.name(row.sender())),
                    Arg.text("target", this.service.name(row.target())), Arg.text("state", row.state().id()),
                    Arg.text("date", TimeText.dateTime(row.created(), this.zone)),
                    Arg.component("decided", row.decided() > 0
                        ? this.services.lang().get(FriendsMessages.STAFF_REQUESTS_DECIDED,
                            Arg.text("date", TimeText.dateTime(row.decided(), this.zone)))
                        : net.kyori.adventure.text.Component.empty()));
            }
        });
        return CommandSupport.OK;
    }

    private int history(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = this.support.known(ctx, "player");
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.service.name(player.get());
        reply(sender, this.store.history(player.get(), HISTORY_PAGE, (page - 1) * HISTORY_PAGE), rows -> {
            this.messenger.chat(sender, FriendsMessages.STAFF_HISTORY_HEADER, Arg.text("name", name), Arg.number("page", page));
            if (rows.isEmpty()) {
                this.messenger.chat(sender, FriendsMessages.STAFF_EMPTY);
            }
            for (FriendStore.LogRow row : rows) {
                this.messenger.chat(sender, FriendsMessages.STAFF_HISTORY_ROW, Arg.text("date", TimeText.dateTime(row.ts(), this.zone)),
                    Arg.text("player", this.service.name(row.player())), Arg.text("action", row.action()),
                    Arg.text("other", this.service.name(row.other())), Arg.text("actor", actorName(row.actor())));
            }
        });
        return CommandSupport.OK;
    }

    /** A history actor: a player's name, or the stored text (console). */
    private String actorName(String actor) {
        if (actor == null) {
            return "?";
        }
        try {
            return this.service.name(UUID.fromString(actor));
        } catch (IllegalArgumentException notUuid) {
            return actor;
        }
    }

    private int add(CommandContext<CommandSourceStack> ctx) {
        Optional<UUID> a = this.support.known(ctx, "a");
        Optional<UUID> b = a.isEmpty() ? Optional.empty() : this.support.known(ctx, "b");
        if (a.isPresent() && b.isPresent()) {
            this.service.staffAdd(ctx.getSource().getSender(), a.get(), b.get());
        }
        return CommandSupport.OK;
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        Optional<UUID> a = this.support.known(ctx, "a");
        Optional<UUID> b = a.isEmpty() ? Optional.empty() : this.support.known(ctx, "b");
        if (a.isPresent() && b.isPresent()) {
            this.service.staffRemove(ctx.getSource().getSender(), a.get(), b.get());
        }
        return CommandSupport.OK;
    }

    private int clear(CommandContext<CommandSourceStack> ctx) {
        Optional<UUID> player = this.support.known(ctx, "player");
        player.ifPresent(id -> this.service.staffClear(ctx.getSource().getSender(), id));
        return CommandSupport.OK;
    }
}
