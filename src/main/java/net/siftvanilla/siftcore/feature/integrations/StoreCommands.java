package net.siftvanilla.siftcore.feature.integrations;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.event.StoreDeliveryEvent;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.integration.luckperms.RankText;
import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code /sift store ...}: what a web store's console commands run to deliver purchases, plus lookups for support.
 * Every delivery names the store's reference (order or transaction id) and is applied at most once per reference, so
 * a store that retries is harmless. The player may be a name (anyone who joined) or an account id, so a purchase can
 * be delivered before the buyer ever joins; a booster may also go to {@code console} (a booster from the server
 * itself, such as a community goal). Delivered purchases are audited as {@code store.<kind>}, refusals as
 * {@code store.failed}. Deliveries and revokes are console only ({@link #console}); lookups are for staff too.
 */
final class StoreCommands {

    static final String PERMISSION = "siftcore.admin.store";
    /** The buyer word for a booster from the server itself (a community goal). */
    static final String CONSOLE = "console";
    private static final List<String> PERMANENT = List.of("permanent", "perm", "forever", "lifetime");

    private final Services services;
    private final Setting<IntegrationsSettings> settings;
    private final StoreService store;
    private final CrateKeys keys;

    StoreCommands(Services services, Setting<IntegrationsSettings> settings, StoreService store, CrateKeys keys) {
        this.services = services;
        this.settings = settings;
        this.store = store;
        this.keys = keys;
    }

    static void declare(Permissions perms) {
        perms.declare(PERMISSION,
            "Deliver store purchases, take them back after refunds and look them up (/sift store)", false);
    }

    AdminFeature.AdminCommandPart part() {
        return () -> Commands.literal("store").requires(CommandSupport.permission(PERMISSION))
            .then(Commands.literal("money").requires(StoreCommands::console).then(player()
                .then(CommandSupport.amount("amount").then(ref().executes(ctx -> currency(ctx, Currency.MONEY))))))
            .then(Commands.literal("shards").requires(StoreCommands::console).then(player()
                .then(CommandSupport.amount("amount").then(ref().executes(ctx -> currency(ctx, Currency.SHARDS))))))
            .then(Commands.literal("keys").requires(StoreCommands::console).then(player()
                .then(crate()
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                        .then(ref().executes(this::keys))))))
            .then(Commands.literal("rank").requires(StoreCommands::console).then(player()
                .then(group()
                    .then(Commands.argument("durationOrRef", StringArgumentType.word())
                        .executes(ctx -> rank(ctx, null, StringArgumentType.getString(ctx, "durationOrRef")))
                        .then(ref().executes(ctx -> rank(ctx, StringArgumentType.getString(ctx, "durationOrRef"),
                            StringArgumentType.getString(ctx, "ref"))))))))
            .then(Commands.literal("booster").requires(StoreCommands::console).then(boosterTarget()
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        if (ServerBoosters.SELL.startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(ServerBoosters.SELL);
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("percent", IntegerArgumentType.integer(1, 100))
                        .then(Commands.argument("length", StringArgumentType.word())
                            .then(ref().executes(this::booster)))))))
            .then(Commands.literal("revoke").requires(StoreCommands::console).then(Commands.argument("ref", StringArgumentType.word())
                .executes(ctx -> revoke(ctx, null))
                .then(Commands.argument("reason", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        for (String reason : List.of("refund", "chargeback", "mistake")) {
                            if (reason.startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(reason);
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> revoke(ctx, StringArgumentType.getString(ctx, "reason"))))))
            .then(Commands.literal("check").then(Commands.argument("ref", StringArgumentType.word()).executes(this::check)))
            .then(Commands.literal("history").then(player().executes(this::history)));
    }

    /**
     * Deliveries and revokes run from the console only (the web store's console commands, RCON, or staff at the server
     * console): never from a player, a command block or an entity, so nobody in game can give out purchases under any
     * name and reference. Lookups ({@code check}, {@code history}) stay open to staff with the permission.
     */
    static boolean console(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        return !(sender instanceof Entity) && !(sender instanceof BlockCommandSender);
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return this.services.commands().knownPlayer("player");
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> ref() {
        return Commands.argument("ref", StringArgumentType.word());
    }

    /** A buyer for a booster: a player as for every delivery, or {@code console} for a booster from the server itself. */
    private RequiredArgumentBuilder<CommandSourceStack, String> boosterTarget() {
        return Commands.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            if (CONSOLE.startsWith(remaining)) {
                builder.suggest(CONSOLE);
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(online.getName());
                }
            }
            return builder.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> crate() {
        return Commands.argument("crate", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String crate : this.keys.crates()) {
                if (crate.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(crate);
                }
            }
            return builder.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> group() {
        return Commands.argument("group", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String group : this.settings.get().store().rankGroups()) {
                if (group.startsWith(remaining)) {
                    builder.suggest(group);
                }
            }
            return builder.buildFuture();
        });
    }

    // ------------------------------------------------------------------ deliveries

    private int currency(CommandContext<CommandSourceStack> ctx, Currency currency) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = player(sender, StringArgumentType.getString(ctx, "player"));
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        OptionalLong amount = this.services.commands().money(sender, StringArgumentType.getString(ctx, "amount"));
        if (amount.isEmpty()) {
            return CommandSupport.OK;
        }
        String ref = StringArgumentType.getString(ctx, "ref");
        report(sender, player.get(), ref, this.store.currency(player.get(), currency, amount.getAsLong(), ref, AdminTools.actor(sender)));
        return CommandSupport.OK;
    }

    private int keys(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = player(sender, StringArgumentType.getString(ctx, "player"));
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String ref = StringArgumentType.getString(ctx, "ref");
        report(sender, player.get(), ref, this.store.keys(player.get(), StringArgumentType.getString(ctx, "crate"),
            IntegerArgumentType.getInteger(ctx, "amount"), ref, AdminTools.actor(sender)));
        return CommandSupport.OK;
    }

    private int rank(CommandContext<CommandSourceStack> ctx, String durationInput, String ref) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = player(sender, StringArgumentType.getString(ctx, "player"));
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        Duration duration = null;
        if (durationInput != null && !PERMANENT.contains(durationInput.toLowerCase(Locale.ROOT))) {
            try {
                duration = Durations.parse(durationInput);
            } catch (IllegalArgumentException | ArithmeticException e) {
                this.services.messenger().chat(sender, IntegrationsMessages.STORE_BAD_DURATION_INPUT, Arg.text("input", durationInput));
                return CommandSupport.OK;
            }
        }
        report(sender, player.get(), ref, this.store.rank(player.get(), StringArgumentType.getString(ctx, "group"), duration, ref,
            AdminTools.actor(sender)));
        return CommandSupport.OK;
    }

    private int booster(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String input = StringArgumentType.getString(ctx, "player");
        Optional<UUID> player = CONSOLE.equalsIgnoreCase(input) ? Optional.of(StoreService.SERVER) : player(sender, input);
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String lengthInput = StringArgumentType.getString(ctx, "length");
        Duration length;
        try {
            length = Durations.parse(lengthInput);
        } catch (IllegalArgumentException | ArithmeticException e) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_BAD_BOOSTER_LENGTH, Arg.text("input", lengthInput));
            return CommandSupport.OK;
        }
        String ref = StringArgumentType.getString(ctx, "ref");
        report(sender, player.get(), ref, this.store.booster(player.get(), StringArgumentType.getString(ctx, "kind").toLowerCase(Locale.ROOT),
            IntegerArgumentType.getInteger(ctx, "percent"), length, ref, AdminTools.actor(sender)));
        return CommandSupport.OK;
    }

    /** A player's name, or "the server" for a booster from the server itself. */
    private String name(UUID player) {
        return StoreService.SERVER.equals(player) ? this.services.lang().plain(IntegrationsMessages.WHO_SERVER)
            : this.services.directory().name(player);
    }

    /** A player by account id, online name or known name; tells the sender when there is none. */
    private Optional<UUID> player(CommandSender sender, String input) {
        Optional<UUID> uuid = StoreRules.uuid(input);
        if (uuid.isPresent()) {
            return uuid;
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        Optional<UUID> known = this.services.directory().uuid(input);
        if (known.isEmpty()) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_UNKNOWN_PLAYER, Arg.text("name", input));
        }
        return known;
    }

    private void report(CommandSender sender, UUID player, String ref, CompletableFuture<StoreService.Outcome> outcome) {
        String actor = AdminTools.actor(sender);
        outcome.whenComplete((result, error) -> {
            if (error != null) {
                result = StoreService.Outcome.failed("other", null, AdminTools.message(error));
            }
            String name = name(player);
            switch (result.status()) {
                case DELIVERED -> delivered(sender, result.delivery(), actor);
                case ALREADY -> {
                    if (result.delivery() != null && result.delivery().revoked()) {
                        this.services.messenger().chat(sender, IntegrationsMessages.STORE_WAS_REVOKED, Arg.text("ref", ref));
                    } else {
                        already(sender, result.delivery(), ref, name);
                    }
                }
                // A delivery never answers REVOKED; anything but a delivery is reported as not delivered.
                case REVOKED, FAILED -> failed(sender, player, ref, result, actor);
            }
        });
    }

    // ------------------------------------------------------------------ revoke

    private int revoke(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSender sender = ctx.getSource().getSender();
        String ref = StringArgumentType.getString(ctx, "ref");
        String actor = AdminTools.actor(sender);
        String why = StoreRules.reason(reason);
        this.store.revoke(ref, why, actor).whenComplete((result, error) -> {
            if (error != null) {
                result = StoreService.Outcome.failed("other", null, AdminTools.message(error));
            }
            switch (result.status()) {
                case REVOKED -> revoked(sender, result, why, actor);
                case ALREADY -> this.services.messenger().chat(sender, IntegrationsMessages.STORE_ALREADY_REVOKED, Arg.text("ref", ref));
                case DELIVERED, FAILED -> {
                    if ("unknown_ref".equals(result.reason())) {
                        this.services.messenger().chat(sender, IntegrationsMessages.STORE_UNKNOWN_REF, Arg.text("ref", ref));
                    } else if (result.delivery() != null && result.delivery().state() == Delivery.State.REVOKING) {
                        this.services.messenger().chat(sender, IntegrationsMessages.STORE_REVOKE_PENDING, Arg.text("ref", ref),
                            Arg.text("detail", result.detail() == null ? result.reason() : result.detail()));
                    } else {
                        this.services.messenger().chat(sender, IntegrationsMessages.STORE_FAILED, Arg.component("reason", reason(result)));
                    }
                    this.services.audit().record(actor, "store.revoke.failed", result.delivery() == null ? null
                        : result.delivery().player().toString(), "ref " + ref + " (" + why + "): " + result.reason()
                        + (result.detail() == null ? "" : " (" + result.detail() + ")"));
                }
            }
        });
        return CommandSupport.OK;
    }

    private void revoked(CommandSender sender, StoreService.Outcome outcome, String why, String actor) {
        Delivery delivery = outcome.delivery();
        String name = name(delivery.player());
        Component what = what(delivery);
        Component detail = revokeDetail(delivery, outcome, name);
        this.services.messenger().chat(sender, IntegrationsMessages.STORE_REVOKED, Arg.text("ref", delivery.ref()),
            Arg.component("what", what), Arg.text("name", name), Arg.component("detail", detail));
        this.services.audit().record(actor, "store.revoke", delivery.player().toString(), "ref " + delivery.ref() + " (" + why + "): "
            + TextStyle.plain(what) + "; " + TextStyle.plain(detail));
        Player online = Bukkit.getPlayer(delivery.player());
        if (online != null && this.settings.get().store().notifyPlayer()) {
            this.services.messenger().send(online, IntegrationsMessages.NOTIFY_REVOKED, Arg.component("what", what));
        }
    }

    /** What a revoke did: how much was taken, or what happened to the rank or the booster. */
    private Component revokeDetail(Delivery delivery, StoreService.Outcome outcome, String name) {
        Lang lang = this.services.lang();
        long taken = outcome.taken();
        return switch (delivery.kind()) {
            case BOOSTER -> lang.get(switch (outcome.reason() == null ? "" : outcome.reason()) {
                case "ended" -> IntegrationsMessages.REVOKE_BOOSTER_ENDED;
                case "removed" -> IntegrationsMessages.REVOKE_BOOSTER_REMOVED;
                default -> IntegrationsMessages.REVOKE_BOOSTER_OVER;
            });
            case MONEY, SHARDS -> taken == delivery.amount()
                ? lang.get(IntegrationsMessages.REVOKE_TOOK, Arg.component("taken", amount(delivery, taken)))
                : lang.get(IntegrationsMessages.REVOKE_TOOK_PART, Arg.component("taken", amount(delivery, taken)),
                    Arg.component("total", amount(delivery, delivery.amount())));
            case KEYS -> lang.get(IntegrationsMessages.REVOKE_KEYS, Arg.text("command",
                "/crates take " + name + " " + delivery.item() + " " + delivery.amount()));
            case RANK -> {
                Arg rank = Arg.text("rank", RankText.fromGroup(delivery.item()));
                if (delivery.revokeUntil() > 0) {
                    Duration left = Duration.between(Instant.now(), Instant.ofEpochSecond(delivery.revokeUntil()));
                    yield lang.get(IntegrationsMessages.REVOKE_RANK_CUT, rank, Arg.time("time", left.isNegative() ? Duration.ZERO : left));
                }
                yield delivery.revokeUntil() == 0 || delivery.duration() == 0
                    ? lang.get(IntegrationsMessages.REVOKE_RANK_REMOVED, rank)
                    : lang.get(IntegrationsMessages.REVOKE_RANK_KEPT, rank);
            }
        };
    }

    private Component amount(Delivery delivery, long amount) {
        Lang lang = this.services.lang();
        return delivery.kind() == StoreDeliveryEvent.Kind.MONEY
            ? lang.get(IntegrationsMessages.WHAT_MONEY, Arg.money("amount", amount))
            : lang.get(IntegrationsMessages.WHAT_SHARDS, Arg.number("amount", amount));
    }

    private void delivered(CommandSender sender, Delivery delivery, String actor) {
        String name = name(delivery.player());
        Component what = what(delivery);
        this.services.messenger().chat(sender, IntegrationsMessages.STORE_DELIVERED, Arg.component("what", what),
            Arg.text("name", name), Arg.text("ref", delivery.ref()));
        this.services.audit().record(actor, "store." + delivery.kind().name().toLowerCase(Locale.ROOT), delivery.player().toString(),
            "ref " + delivery.ref() + ": " + TextStyle.plain(what));
        if (delivery.kind() == StoreDeliveryEvent.Kind.BOOSTER) {
            int bought = (int) delivery.amount();
            int paid = this.store.boosterPaid(bought);
            if (paid < bought) {
                // Delivered all the same (a purchase is never lost to a config change), but staff should know.
                this.services.messenger().chat(sender, IntegrationsMessages.STORE_BOOSTER_CAPPED, Arg.number("percent", bought),
                    Arg.number("paid", paid));
                this.services.plugin().getLogger().warning("Store booster " + delivery.ref() + " was bought for +" + bought
                    + "%, above sell.max-percent in boosters.yml: it pays +" + paid + "% while the limit is lower.");
            }
        }
        IntegrationsSettings.Store settings = this.settings.get().store();
        Player online = Bukkit.getPlayer(delivery.player());
        if (online != null && settings.notifyPlayer()) {
            notifyBuyer(online, delivery);
        }
        // A booster announces itself when it starts (and when it has to wait), so it is not announced twice.
        if (settings.announce() && delivery.kind() != StoreDeliveryEvent.Kind.BOOSTER) {
            this.services.messenger().broadcast(IntegrationsMessages.ANNOUNCE, Arg.text("name", name), Arg.component("what", what));
        }
    }

    private void already(CommandSender sender, Delivery delivery, String ref, String fallbackName) {
        if (delivery == null) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_ALREADY, Arg.text("what", "-"), Arg.text("name", fallbackName),
                Arg.text("ref", ref), Arg.time("time", Duration.ZERO));
            return;
        }
        this.services.messenger().chat(sender, IntegrationsMessages.STORE_ALREADY, Arg.component("what", what(delivery)),
            Arg.text("name", name(delivery.player())), Arg.text("ref", delivery.ref()), Arg.time("time", age(delivery)));
    }

    private void failed(CommandSender sender, UUID player, String ref, StoreService.Outcome outcome, String actor) {
        if (outcome.delivery() != null && outcome.delivery().pending()) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_RANK_PENDING, Arg.text("ref", ref),
                Arg.text("detail", outcome.detail() == null ? outcome.reason() : outcome.detail()));
        } else {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_FAILED, Arg.component("reason", reason(outcome)));
        }
        this.services.audit().record(actor, "store.failed", player.toString(), "ref " + ref + ": " + outcome.reason()
            + (outcome.detail() == null ? "" : " (" + outcome.detail() + ")"));
    }

    private void notifyBuyer(Player player, Delivery delivery) {
        var messenger = this.services.messenger();
        switch (delivery.kind()) {
            case MONEY -> messenger.send(player, IntegrationsMessages.NOTIFY_MONEY, Arg.money("amount", delivery.amount()));
            case SHARDS -> messenger.send(player, IntegrationsMessages.NOTIFY_SHARDS, Arg.number("amount", delivery.amount()));
            case KEYS -> messenger.send(player, IntegrationsMessages.NOTIFY_KEYS, keysArgs(delivery));
            case RANK -> {
                String rank = RankText.fromGroup(delivery.item());
                if (delivery.permanent()) {
                    messenger.send(player, IntegrationsMessages.NOTIFY_RANK_PERMANENT, Arg.text("rank", rank));
                } else {
                    messenger.send(player, IntegrationsMessages.NOTIFY_RANK, Arg.text("rank", rank),
                        Arg.time("time", Duration.ofSeconds(delivery.duration())));
                }
            }
            case BOOSTER -> {
                // What it pays now, like the announcement and the bar (a purchase above sell.max-percent pays the limit).
                Arg percent = Arg.number("percent", this.store.boosterPaid((int) delivery.amount()));
                Arg time = Arg.time("time", Duration.ofSeconds(delivery.duration()));
                Optional<ServerBoosters.Status> status = this.store.boosterStatus(delivery.ref());
                if (status.isPresent() && !status.get().running()) {
                    messenger.send(player, IntegrationsMessages.NOTIFY_BOOSTER_QUEUED, percent, time,
                        Arg.number("position", status.get().position()));
                } else {
                    messenger.send(player, IntegrationsMessages.NOTIFY_BOOSTER, percent, time);
                }
            }
        }
    }

    /** The arguments of a key delivery's text: {@code <keys>} as the crates feature words it ("1 Basic key"). */
    private Arg[] keysArgs(Delivery delivery) {
        return new Arg[] {Arg.component("keys", this.keys.keysText(delivery.item(), delivery.amount())),
            Arg.number("amount", delivery.amount()), Arg.text("crate", delivery.item())};
    }

    /** What a delivery gave, e.g. {@code $10,000}, {@code 3 Vote keys}, {@code Elite for 30d}. */
    Component what(Delivery delivery) {
        Lang lang = this.services.lang();
        return switch (delivery.kind()) {
            case MONEY -> lang.get(IntegrationsMessages.WHAT_MONEY, Arg.money("amount", delivery.amount()));
            case SHARDS -> lang.get(IntegrationsMessages.WHAT_SHARDS, Arg.number("amount", delivery.amount()));
            case KEYS -> lang.get(IntegrationsMessages.WHAT_KEYS, keysArgs(delivery));
            case RANK -> delivery.permanent()
                ? lang.get(IntegrationsMessages.WHAT_RANK_PERMANENT, Arg.text("rank", RankText.fromGroup(delivery.item())))
                : lang.get(IntegrationsMessages.WHAT_RANK, Arg.text("rank", RankText.fromGroup(delivery.item())),
                    Arg.time("time", Duration.ofSeconds(delivery.duration())));
            case BOOSTER -> lang.get(IntegrationsMessages.WHAT_BOOSTER, Arg.number("percent", delivery.amount()),
                Arg.time("time", Duration.ofSeconds(delivery.duration())));
        };
    }

    private Component reason(StoreService.Outcome outcome) {
        Lang lang = this.services.lang();
        IntegrationsSettings.Store limits = this.settings.get().store();
        String reason = outcome.reason() == null ? "other" : outcome.reason();
        return switch (reason) {
            case "bad_ref" -> lang.get(IntegrationsMessages.REASON_BAD_REF, Arg.number("max", StoreRules.MAX_REF));
            case "bad_amount" -> lang.get(IntegrationsMessages.REASON_BAD_AMOUNT);
            case "too_much" -> lang.get(IntegrationsMessages.REASON_TOO_MUCH, Arg.text("max",
                this.services.money().get().format(limits.maxMoney()) + ", " + Lang.number(limits.maxShards()) + " shards, "
                    + Lang.number(limits.maxKeys()) + " keys"));
            case "unknown_crate" -> lang.get(IntegrationsMessages.REASON_UNKNOWN_CRATE, Arg.text("crates", String.join(", ", this.keys.crates())));
            case "no_luckperms" -> lang.get(Bukkit.getPluginManager().isPluginEnabled("LuckPerms")
                ? IntegrationsMessages.REASON_LUCKPERMS_OFF : IntegrationsMessages.REASON_NO_LUCKPERMS);
            case "group_not_allowed" -> lang.get(IntegrationsMessages.REASON_GROUP_NOT_ALLOWED,
                Arg.text("groups", limits.rankGroups().isEmpty() ? "any" : String.join(", ", limits.rankGroups())));
            case "unknown_group" -> lang.get(IntegrationsMessages.REASON_UNKNOWN_GROUP);
            case "bad_duration" -> lang.get(IntegrationsMessages.REASON_BAD_DURATION, Arg.time("min", limits.minRankDuration()),
                Arg.time("max", limits.maxRankDuration()));
            case "cancelled" -> lang.get(IntegrationsMessages.REASON_CANCELLED);
            case "balance_limit" -> lang.get(IntegrationsMessages.REASON_BALANCE_LIMIT);
            case "unavailable" -> lang.get(IntegrationsMessages.REASON_UNAVAILABLE);
            case "storage" -> lang.get(IntegrationsMessages.REASON_STORAGE);
            case "unknown_booster" -> lang.get(IntegrationsMessages.REASON_UNKNOWN_BOOSTER);
            case "bad_booster_percent" -> lang.get(IntegrationsMessages.REASON_BAD_BOOSTER_PERCENT, Arg.number("max", ServerBoosters.PERCENT_CAP));
            case "bad_booster_duration" -> lang.get(IntegrationsMessages.REASON_BAD_BOOSTER_DURATION,
                Arg.time("min", ServerBoosters.MIN_LENGTH), Arg.time("max", ServerBoosters.MAX_LENGTH));
            case "no_boosters" -> lang.get(IntegrationsMessages.REASON_NO_BOOSTERS);
            default -> lang.get(IntegrationsMessages.REASON_OTHER, Arg.text("reason",
                outcome.detail() == null ? reason.replace('_', ' ') : reason.replace('_', ' ') + ": " + outcome.detail()));
        };
    }

    // ------------------------------------------------------------------ lookups

    private int check(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String ref = StringArgumentType.getString(ctx, "ref");
        Delivery delivery = this.store.find(ref);
        if (delivery == null) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_CHECK_NONE, Arg.text("ref", ref));
            return CommandSupport.OK;
        }
        this.services.messenger().chat(sender, IntegrationsMessages.STORE_CHECK, Arg.text("ref", ref), Arg.component("what", what(delivery)),
            Arg.text("name", name(delivery.player())), Arg.time("time", age(delivery)),
            Arg.component("state", state(delivery)), Arg.text("actor", actorName(delivery.actor())));
        return CommandSupport.OK;
    }

    private int history(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<UUID> player = player(sender, StringArgumentType.getString(ctx, "player"));
        if (player.isEmpty()) {
            return CommandSupport.OK;
        }
        String name = this.services.directory().name(player.get());
        List<Delivery> deliveries = this.store.history(player.get(), 20);
        if (deliveries.isEmpty()) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_HISTORY_EMPTY, Arg.text("name", name));
            return CommandSupport.OK;
        }
        this.services.messenger().chat(sender, IntegrationsMessages.STORE_HISTORY_HEADER, Arg.text("name", name),
            Arg.number("count", deliveries.size()));
        for (Delivery delivery : deliveries) {
            this.services.messenger().chat(sender, IntegrationsMessages.STORE_HISTORY_LINE, Arg.time("time", age(delivery)),
                Arg.component("what", what(delivery)), Arg.text("ref", delivery.ref()), Arg.component("state", state(delivery)));
        }
        return CommandSupport.OK;
    }

    private Component state(Delivery delivery) {
        Lang lang = this.services.lang();
        return switch (delivery.state()) {
            case DONE -> lang.get(IntegrationsMessages.STATE_DONE);
            case PENDING -> lang.get(IntegrationsMessages.STATE_PENDING);
            case REVOKING -> lang.get(IntegrationsMessages.STATE_REVOKING);
            case REVOKED -> lang.get(IntegrationsMessages.STATE_REVOKED, Arg.text("reason", delivery.note() == null ? "-" : delivery.note()));
        };
    }

    private String actorName(String actor) {
        if (actor == null) {
            return "-";
        }
        return StoreRules.uuid(actor).map(uuid -> this.services.directory().name(uuid)).orElse(actor);
    }

    private static Duration age(Delivery delivery) {
        Duration age = Duration.between(Instant.ofEpochMilli(delivery.time()), Instant.now());
        return age.isNegative() ? Duration.ZERO : age;
    }
}
