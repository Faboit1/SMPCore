package net.siftvanilla.siftcore.feature.homes;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Homes: {@code /sethome}, {@code /home}, {@code /delhome} and {@code /homes}. Limits come from rank permission
 * nodes ({@code siftcore.homes.<n>}); homes live in memory while their owner is online and are written through to
 * the {@code homes} table.
 */
public final class HomesFeature implements Feature, Listener {

    public static final String SETHOME = "siftcore.command.sethome";
    public static final String HOME = "siftcore.command.home";
    public static final String DELHOME = "siftcore.command.delhome";
    public static final String HOMES = "siftcore.command.homes";
    public static final String ADMIN = "siftcore.admin.homes";
    private static final Duration SWEEP = Duration.ofMinutes(5);
    private static final long LOGIN_LOAD_SECONDS = 10;

    private final Services services;
    private final Logger logger;
    private final Setting<HomesSettings> settings;
    private final HomeStore store;
    private final HomesService service;
    private Task sweeper = Task.NONE;

    /**
     * @param spawn  no homes inside the protected spawn area
     * @param combat no homes are set in combat (the /sethome command and the menu's form alike)
     */
    public HomesFeature(Services services, List<ConfigProblem> problems, SpawnArea spawn, CombatStatus combat) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        this.settings = services.configs().register("features/homes.yml",
            reader -> HomesSettings.parse(reader, name -> Bukkit.getWorld(name) != null), problems);
        services.lang().register(HomesMessages.class);
        var perms = services.permissions();
        perms.declare(SETHOME, "Use /sethome", true);
        perms.declare(HOME, "Use /home", true);
        perms.declare(DELHOME, "Use /delhome", true);
        perms.declare(HOMES, "Use /homes", true);
        perms.declare(ADMIN, "See, use and delete other players' homes with /homes <player>", false);
        this.store = new HomeStore(services.database());
        this.service = new HomesService(services, this.settings, this.store, spawn, combat);
    }

    /** Whether homes are turned off in a world ({@code disabled-worlds}, follows reloads). Any thread. */
    public Predicate<String> disabledWorlds() {
        return world -> this.settings.get().disabled(world);
    }

    @Override
    public String id() {
        return "homes";
    }

    @Override
    public void enable() throws Exception {
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.store.put(online.getUniqueId(), this.store.fetch(online.getUniqueId()).get(LOGIN_LOAD_SECONDS, TimeUnit.SECONDS));
        }
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        this.sweeper = this.services.scheduler().asyncTimer(() -> this.store.retain(uuid -> Bukkit.getPlayer(uuid) != null), SWEEP, SWEEP);
        this.services.hub().register(new HubEntry("homes", 55, HomesMessages.HUB_LABEL, HomesMessages.HUB_DESCRIPTION, HOMES,
            player -> this.service.openList(player, 1, submission -> openMainMenu(submission.player()))));
        var placeholders = this.services.placeholders();
        placeholders.register("homes_count", "How many homes you have set",
            player -> Integer.toString(player == null ? 0 : this.store.count(player.getUniqueId())));
        placeholders.register("homes_limit", "How many homes you may set (unlimited for no limit)", this::limitText);
    }

    private String limitText(OfflinePlayer offline) {
        Player player = offline == null ? null : offline.getPlayer();
        int limit = player == null ? this.settings.get().defaultLimit() : this.service.limit(player);
        return limit == Limits.UNLIMITED ? "unlimited" : Integer.toString(limit);
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

    // ------------------------------------------------------------------ loading

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID id = event.getUniqueId();
        try {
            this.store.put(id, this.store.fetch(id).get(LOGIN_LOAD_SECONDS, TimeUnit.SECONDS));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            this.logger.log(Level.WARNING, "Could not load the homes of " + event.getName() + " at login; retrying after they join", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (this.store.isLoaded(id)) {
            return;
        }
        this.store.fetch(id).whenComplete((homes, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not load the homes of " + player.getName(), error);
            } else if (player.isOnline() && !this.store.isLoaded(id)) {
                this.store.put(id, homes);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.store.forget(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private RequiredArgumentBuilder<CommandSourceStack, String> ownHome(String argument) {
        return Commands.argument(argument, StringArgumentType.word()).suggests((context, builder) -> {
            if (context.getSource().getSender() instanceof Player player) {
                String remaining = builder.getRemainingLowerCase();
                for (String name : this.store.homes(player.getUniqueId()).map(Map::keySet).orElse(java.util.Set.of())) {
                    if (name.startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    @Override
    public List<SiftCommand> commands() {
        CommandSupport support = this.services.commands();
        SiftCommand sethome = new SimpleCommand("sethome", List.of("createhome"), "Sets a home where you stand", SETHOME,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(SETHOME))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.setHome(player, HomeNames.DEFAULT);
                    }
                    return CommandSupport.OK;
                })
                .then(ownHome("name").executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.setHome(player, StringArgumentType.getString(ctx, "name"));
                    }
                    return CommandSupport.OK;
                })));
        SiftCommand home = new SimpleCommand("home", List.of("h"), "Teleports you to a home", HOME,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(HOME))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.home(player);
                    }
                    return CommandSupport.OK;
                })
                .then(ownHome("name").executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.teleport(player, StringArgumentType.getString(ctx, "name"));
                    }
                    return CommandSupport.OK;
                })));
        SiftCommand delhome = new SimpleCommand("delhome", List.of("deletehome", "removehome"), "Deletes a home", DELHOME,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(DELHOME))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.openList(player, 1, null);
                    }
                    return CommandSupport.OK;
                })
                .then(ownHome("name").executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.confirmDelete(player, StringArgumentType.getString(ctx, "name"), null);
                    }
                    return CommandSupport.OK;
                })));
        SiftCommand homes = new SimpleCommand("homes", List.of(), "Lists your homes", HOMES,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(HOMES))
                .executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        this.service.openList(player, 1, null);
                    }
                    return CommandSupport.OK;
                })
                .then(support.knownPlayer("player")
                    .requires(CommandSupport.permission(ADMIN))
                    .executes(ctx -> {
                        Optional<UUID> target = support.known(ctx, "player");
                        if (target.isEmpty()) {
                            return CommandSupport.OK;
                        }
                        String name = this.services.directory().name(target.get());
                        CommandSender sender = ctx.getSource().getSender();
                        // Every home with its position is shown: written down like invsee and whois are.
                        this.services.audit().record(sender instanceof Player p ? p.getUniqueId().toString() : "console", "homes.view",
                            target.get().toString(), null);
                        if (sender instanceof Player staff) {
                            this.service.openOther(staff, target.get(), name, 1);
                        } else {
                            this.service.listOther(sender, target.get(), name);
                        }
                        return CommandSupport.OK;
                    })
                    .then(Commands.literal("delete").then(Commands.argument("home", StringArgumentType.word()).executes(ctx -> {
                        Optional<UUID> target = support.known(ctx, "player");
                        target.ifPresent(uuid -> this.service.deleteOther(ctx.getSource().getSender(), uuid,
                            this.services.directory().name(uuid), StringArgumentType.getString(ctx, "home"), null));
                        return CommandSupport.OK;
                    })))));
        return List.of(sethome, home, delhome, homes);
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "home names follow the rules", () -> {
            if (!HomeNames.normalize("Base_2").equals(Optional.of("base_2"))) {
                return "Base_2 was not accepted as base_2";
            }
            for (String bad : List.of("", "with space", "seventeen-chars-x", "a.b", "<b>")) {
                if (HomeNames.normalize(bad).isPresent()) {
                    return "'" + bad + "' was accepted";
                }
            }
            return null;
        });
        test.check(id(), "limits stop new homes only", () -> {
            if (HomeStore.decide(2, false, 2) != HomeStore.Outcome.LIMIT || HomeStore.decide(2, true, 2) != HomeStore.Outcome.MOVED
                || HomeStore.decide(1, false, 2) != HomeStore.Outcome.CREATED) {
                return "the limit decision is wrong";
            }
            return null;
        });
        test.check(id(), "homes of online players are loaded", () -> {
            long missing = Bukkit.getOnlinePlayers().stream().filter(p -> !this.store.isLoaded(p.getUniqueId())).count();
            return missing == 0 ? null : missing + " online player(s) have no homes loaded";
        });
        test.checkAsync(id(), "homes table is readable", () -> this.services.database().read(connection -> {
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT COUNT(*) FROM homes")) {
                return rs.next() ? null : "no result";
            }
        }).exceptionally(error -> "the homes table can't be read: " + error.getMessage()));
    }
}
