package net.siftvanilla.siftcore.feature.tpa;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Request;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Teleport requests: {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny}, {@code /tpacancel} and
 * {@code /tpatoggle}. Several requests can wait for one player at once (one per sender), each expiring on its own.
 */
public final class TpaFeature implements Feature, Listener {

    /**
     * "Teleport requests from": who may send the player /tpa and /tpahere requests (staff with the bypass always can).
     * Was the {@code tpa-requests} switch: on reads as everyone, off as nobody. The friend options read as nobody
     * while the server has no friends system (and no teams, for friends and teammates).
     */
    public static final Choice<Audience> REQUESTS = whoCan("tpa-requests", TpaMessages.SETTING_LABEL, TpaMessages.SETTING_DESCRIPTION)
        .legacyValue("true", Audience.EVERYONE.id()).legacyValue("false", Audience.NOBODY.id()).build();
    /** "Pull requests from": who may ask the player to come to them with /tpahere, on top of {@link #REQUESTS}. */
    public static final Choice<Audience> HERE_REQUESTS = whoCan("tpahere-requests", TpaMessages.SETTING_HERE,
        TpaMessages.SETTING_HERE_DESCRIPTION).build();
    /** "Requests open a pop-up": an incoming request also opens the accept/deny window (not in combat, AFK or a menu). */
    public static final Toggle POPUP = new Toggle("tpa-popup", false, TpaMessages.SETTING_POPUP, TpaMessages.SETTING_POPUP_DESCRIPTION,
        null);
    /** "Confirm before being pulled": /tpaccept on a /tpahere asks once more before the player is moved. */
    public static final Toggle CONFIRM_HERE = new Toggle("tpaccept-confirm-here", true, TpaMessages.SETTING_CONFIRM_HERE,
        TpaMessages.SETTING_CONFIRM_HERE_DESCRIPTION, null);
    public static final String TPA = "siftcore.command.tpa";
    public static final String TPAHERE = "siftcore.command.tpahere";
    public static final String TPACCEPT = "siftcore.command.tpaccept";
    public static final String TPDENY = "siftcore.command.tpdeny";
    public static final String TPACANCEL = "siftcore.command.tpacancel";
    public static final String TPATOGGLE = "siftcore.command.tpatoggle";
    private static final Duration SWEEP = Duration.ofSeconds(1);

    private final Services services;
    private final Setting<TpaSettings> settings;
    private final TpaService service;
    private Task sweeper = Task.NONE;

    /**
     * @param vanish  vanished staff can't be asked (staff module)
     * @param afk     the sender is told when the target is AFK (AFK module)
     * @param friends not read: friendships, favourites and teams for the "who can" settings and auto-accept come from
     *                {@code services.relations()}, which the composition root binds to this same lookup. Kept so the
     *                composition root's call stays as it is
     * @param ignores players who ignore the sender never get the request (chat module)
     * @param combat  combat-tagged players can't send or accept requests (command, chat dialog or menu form)
     */
    public TpaFeature(Services services, List<ConfigProblem> problems, VanishStatus vanish, AfkStatus afk, FriendLookup friends,
                      IgnoreLookup ignores, CombatStatus combat) {
        this.services = services;
        this.settings = services.configs().register("features/tpa.yml", TpaSettings::parse, problems);
        services.lang().register(TpaMessages.class);
        registerSettings(services.settings(), services.relations());
        var perms = services.permissions();
        perms.declare(TPA, "Use /tpa", true);
        perms.declare(TPAHERE, "Use /tpahere", true);
        perms.declare(TPACCEPT, "Use /tpaccept", true);
        perms.declare(TPDENY, "Use /tpdeny", true);
        perms.declare(TPACANCEL, "Use /tpacancel", true);
        perms.declare(TPATOGGLE, "Use /tpatoggle", true);
        perms.declare(TpaService.BYPASS, "Staff: /tpa teleports at once without a request, and /tpahere reaches players who turned requests off", false);
        this.service = new TpaService(services, this.settings, new TpaRequests(System::currentTimeMillis),
            new TpaService.Links(vanish, afk, ignores, combat), new InventoryUse(System::currentTimeMillis));
    }

    /** A "who can" choice: everyone, friends and teammates, friends, nobody (the friend options fall back to nobody). */
    private static Choice.Builder<Audience> whoCan(String id, MessageKey label, MessageKey description) {
        return Choice.ofEnum(id, Audience.class, Audience::id, Audience.EVERYONE)
            .option(Audience.EVERYONE, Audience.EVERYONE.label())
            .option(Audience.FRIENDS_TEAM, Audience.FRIENDS_TEAM.label(), null, Audience.NOBODY.id())
            .option(Audience.FRIENDS, Audience.FRIENDS.label(), null, Audience.NOBODY.id())
            .option(Audience.NOBODY, Audience.NOBODY.label())
            .text(label, description);
    }

    /**
     * Registers the teleport request settings in Settings &gt; Teleports &amp; homes, in the catalog's order (the shared
     * {@code friends-tpa} is second and {@code teleport-display} fourth; homes and random teleport add theirs). The
     * friend options are offered only while the server has friends (friends and teammates: or teams); the retired
     * {@code tpa-friends} switch is no longer registered, so its rows move to {@code friends-tpa}.
     */
    static void registerSettings(PlayerSettings settings, Relations relations) {
        BooleanSupplier friendsOrTeams = () -> relations.friendsAvailable() || relations.teams() != TeamLookup.NONE;
        settings.register(SettingCategories.TELEPORT, REQUESTS, SettingOptions.<Audience>builder().order(1)
            .optionAvailableWhen(Audience.FRIENDS_TEAM.id(), friendsOrTeams)
            .optionAvailableWhen(Audience.FRIENDS.id(), relations::friendsAvailable)
            .placeholder(false).build());
        settings.register(SettingCategories.TELEPORT, HERE_REQUESTS, SettingOptions.<Audience>builder().order(6)
            .optionAvailableWhen(Audience.FRIENDS_TEAM.id(), friendsOrTeams)
            .optionAvailableWhen(Audience.FRIENDS.id(), relations::friendsAvailable)
            .placeholder(false).build());
        settings.register(SettingCategories.TELEPORT, POPUP, SettingOptions.<Boolean>builder().order(7).build());
        settings.register(SettingCategories.TELEPORT, CONFIRM_HERE, SettingOptions.<Boolean>builder().order(8).build());
        // Auto-accept (/tpa without asking) is the shared friends-tpa, decided in TpaService#skipsRequest.
        settings.reads(SharedSettings.FRIENDS_TPA);
    }

    @Override
    public String id() {
        return "tpa";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        this.sweeper = this.services.scheduler().asyncTimer(this.service::expire, SWEEP, SWEEP);
        this.services.hub().register(new HubEntry("tpa", 62, TpaMessages.HUB_LABEL, TpaMessages.HUB_DESCRIPTION, TPA,
            player -> this.service.openForm(player, submission -> openMainMenu(submission.player()))));
        this.services.placeholders().register("tpa_requests", "Teleport requests waiting for your answer",
            player -> Integer.toString(player == null ? 0 : this.service.pending(player.getUniqueId())));
    }

    private void openMainMenu(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(player);
        } else {
            this.services.dialogs().close(player);
        }
    }

    @Override
    public void disable() {
        this.sweeper.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.service.forget(event.getPlayer().getUniqueId());
    }

    /** A click in the player's own inventory: the pop-up waits a moment (see {@link InventoryUse}). Player's thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        this.service.inventories().clicked(event.getWhoClicked().getUniqueId(), InventoryUse.own(event.getView().getType()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        this.service.inventories().closed(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    /** A name argument suggesting the players in the sender's own requests (incoming or outgoing). */
    private RequiredArgumentBuilder<CommandSourceStack, String> requestName(boolean incoming) {
        return Commands.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
            if (context.getSource().getSender() instanceof Player player) {
                String remaining = builder.getRemainingLowerCase();
                List<Request> requests = incoming ? this.service.requests().incoming(player.getUniqueId())
                    : this.service.requests().outgoing(player.getUniqueId());
                for (Request request : requests) {
                    UUID other = incoming ? request.sender() : request.target();
                    String name = this.services.directory().name(other);
                    if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private SiftCommand send(String name, List<String> aliases, String description, String permission, Kind kind) {
        CommandSupport support = this.services.commands();
        return new SimpleCommand(name, aliases, description, permission,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(permission))
                .then(CommandSupport.onlinePlayer("player").executes(ctx -> {
                    Player player = support.player(ctx);
                    Player target = player == null ? null : support.online(ctx, "player");
                    if (target != null) {
                        this.service.request(player, target, kind);
                    }
                    return CommandSupport.OK;
                })));
    }

    private SiftCommand answer(String name, List<String> aliases, String description, String permission, boolean incoming,
                               BiConsumer<Player, String> action) {
        CommandSupport support = this.services.commands();
        Function<CommandContext<CommandSourceStack>, Integer> withName = ctx -> {
            Player player = support.player(ctx);
            if (player != null) {
                action.accept(player, StringArgumentType.getString(ctx, "player"));
            }
            return CommandSupport.OK;
        };
        return new SimpleCommand(name, aliases, description, permission,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(permission))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        action.accept(player, null);
                    }
                    return CommandSupport.OK;
                })
                .then(requestName(incoming).executes(withName::apply)));
    }

    @Override
    public List<SiftCommand> commands() {
        CommandSupport support = this.services.commands();
        SiftCommand toggle = new SimpleCommand("tpatoggle", List.of("tptoggle"), "Turns teleport requests to you on or off", TPATOGGLE,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(TPATOGGLE))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.toggle(player);
                    }
                    return CommandSupport.OK;
                })
                .then(Commands.literal("friends").executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.toggleFriends(player, null);
                    }
                    return CommandSupport.OK;
                }).then(Commands.argument("choice", StringArgumentType.word()).suggests((context, builder) -> {
                    if (context.getSource().getSender() instanceof Player player) {
                        String remaining = builder.getRemainingLowerCase();
                        for (String option : this.service.autoAcceptOptions(player)) {
                            if (option.startsWith(remaining)) {
                                builder.suggest(option);
                            }
                        }
                    }
                    return builder.buildFuture();
                }).executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.toggleFriends(player, StringArgumentType.getString(ctx, "choice"));
                    }
                    return CommandSupport.OK;
                }))));
        return List.of(
            send("tpa", List.of("tpask"), "Asks to teleport to a player", TPA, Kind.TO_TARGET),
            send("tpahere", List.of(), "Asks a player to teleport to you", TPAHERE, Kind.TO_SENDER),
            answer("tpaccept", List.of("tpyes"), "Accepts a teleport request", TPACCEPT, true, this.service::acceptCommand),
            answer("tpdeny", List.of("tpno"), "Denies a teleport request", TPDENY, true, this.service::denyCommand),
            answer("tpacancel", List.of("tpcancel"), "Cancels a teleport request you sent", TPACANCEL, false, this.service::cancelCommand),
            toggle);
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "requests expire and replace", () -> {
            long[] now = {1_000};
            TpaRequests store = new TpaRequests(() -> now[0]);
            UUID a = new UUID(0, 1);
            UUID b = new UUID(0, 2);
            UUID c = new UUID(0, 3);
            store.add(a, c, Kind.TO_TARGET, Duration.ofSeconds(60));
            store.add(b, c, Kind.TO_SENDER, Duration.ofSeconds(60));
            if (store.incoming(c).size() != 2) {
                return "two senders should give two pending requests";
            }
            store.add(a, c, Kind.TO_SENDER, Duration.ofSeconds(60));
            if (store.incoming(c).size() != 2) {
                return "a second request from the same sender should replace the first";
            }
            now[0] += 60_000;
            return store.incoming(c).isEmpty() && store.expire().size() == 2 && store.size() == 0 ? null : "requests did not expire";
        });
        test.check(id(), "pending requests are bounded", () -> {
            int size = this.service.requests().size();
            return size <= Math.max(100, Bukkit.getOnlinePlayers().size() * Bukkit.getOnlinePlayers().size())
                ? null : size + " requests are stored for " + Bukkit.getOnlinePlayers().size() + " players";
        });
    }
}
