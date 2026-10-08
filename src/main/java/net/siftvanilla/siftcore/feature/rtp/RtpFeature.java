package net.siftvanilla.siftcore.feature.rtp;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.feature.spawn.BorderSpec;
import net.siftvanilla.siftcore.feature.spawn.WorldBorders;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Random teleport: {@code /rtp} opens a region picker (overworld, nether, end, or whatever the config defines),
 * {@code /rtp <region>} goes straight there, staff can send others with {@code /rtp <region> <player>}. The search
 * never generates terrain and never runs on a world thread; the cost is charged only once a safe spot exists.
 */
public final class RtpFeature implements Feature, Listener {

    public static final String COMMAND = "siftcore.command.rtp";
    public static final String ADMIN = "siftcore.admin.rtp";
    private static final Duration SWEEP = Duration.ofMinutes(5);

    private final Services services;
    private final Setting<RtpSettings> settings;
    private final WorldBorders borders;
    private final RtpService service;
    private Task sweeper = Task.NONE;

    public RtpFeature(Services services, List<ConfigProblem> problems, SpawnArea spawn, WorldBorders borders) {
        this.services = services;
        this.borders = borders;
        this.settings = services.configs().register("features/rtp.yml",
            reader -> RtpSettings.parse(reader, services.core().get().money(), borders::planned, name -> Bukkit.getWorld(name) != null),
            problems);
        services.lang().register(RtpMessages.class);
        var perms = services.permissions();
        perms.declare(COMMAND, "Use /rtp", true);
        perms.declare(ADMIN, "Send other players to a random spot with /rtp <region> <player> (free, no cooldown)", false);
        this.service = new RtpService(services, this.settings, new RtpSearch(services.scheduler(), spawn));
    }

    @Override
    public String id() {
        return "rtp";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        this.sweeper = this.services.scheduler().asyncTimer(this.service::sweepCooldowns, SWEEP, SWEEP);
        this.services.hub().register(new HubEntry("rtp", 60, RtpMessages.HUB_LABEL, RtpMessages.HUB_DESCRIPTION, COMMAND,
            player -> this.service.openMenu(player, submission -> openMainMenu(submission.player()))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.service.forget(event.getPlayer().getUniqueId());
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

    // ------------------------------------------------------------------ commands

    @Override
    public List<SiftCommand> commands() {
        return List.of(new SimpleCommand("rtp", List.of("randomtp", "wild"), "Teleports you to a random safe spot", COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.permission(COMMAND))
                .executes(ctx -> {
                    Player player = this.services.commands().player(ctx);
                    if (player != null) {
                        this.service.openMenu(player, null);
                    }
                    return CommandSupport.OK;
                })
                .then(regionArgument()
                    .executes(ctx -> {
                        Player player = this.services.commands().player(ctx);
                        if (player != null) {
                            this.service.start(player, StringArgumentType.getString(ctx, "region"));
                        }
                        return CommandSupport.OK;
                    })
                    .then(CommandSupport.onlinePlayer("player")
                        .requires(CommandSupport.permission(ADMIN))
                        .executes(this::sendOther)))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> regionArgument() {
        return Commands.argument("region", StringArgumentType.word()).suggests((context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = context.getSource().getSender();
            for (RtpSettings.Region region : this.settings.get().regions().values()) {
                boolean visible = sender.hasPermission(ADMIN) || region.enabled()
                    && (region.permission() == null || sender.hasPermission(region.permission()));
                if (visible && region.id().startsWith(remaining)) {
                    builder.suggest(region.id());
                }
            }
            return builder.buildFuture();
        });
    }

    private int sendOther(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "region");
        RtpSettings.Region region = this.settings.get().find(name).orElse(null);
        if (region == null) {
            this.services.messenger().send(sender, RtpMessages.UNKNOWN, Arg.text("name", name));
            return CommandSupport.OK;
        }
        Player target = this.services.commands().online(ctx, "player");
        if (target != null) {
            this.service.send(sender, target, region);
            this.services.audit().record(sender instanceof Player p ? p.getUniqueId().toString() : "console", "rtp.send",
                target.getUniqueId().toString(), region.id());
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "every region's world is loaded", () -> {
            List<String> missing = new ArrayList<>();
            for (RtpSettings.Region region : this.settings.get().regions().values()) {
                if (region.enabled() && Bukkit.getWorld(region.world()) == null) {
                    missing.add(region.id() + " (" + region.world() + ")");
                }
            }
            return missing.isEmpty() ? null : "not loaded: " + String.join(", ", missing);
        });
        test.check(id(), "rings fit inside the world borders", () -> {
            RtpSettings s = this.settings.get();
            List<String> problems = new ArrayList<>();
            for (RtpSettings.Region region : s.regions().values()) {
                BorderSpec border = this.borders.current(region.world()).orElse(null);
                if (border == null) {
                    continue;
                }
                String problem = RtpGeometry.problem(region.world(), border, region.centerX(), region.centerZ(), region.minRadius(),
                    region.maxRadius(), s.borderMargin());
                if (problem != null) {
                    problems.add(region.id() + " max-radius " + problem);
                }
            }
            return problems.isEmpty() ? null : String.join("; ", problems);
        });
        test.check(id(), "ring sampling stays inside each ring", () -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (RtpSettings.Region region : this.settings.get().regions().values()) {
                for (int i = 0; i < 2_000; i++) {
                    RingSampler.Point point = RingSampler.sample(random, region.centerX(), region.centerZ(), region.minRadius(), region.maxRadius());
                    double dx = point.x() - region.centerX();
                    double dz = point.z() - region.centerZ();
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    if (distance < region.minRadius() - 2 || distance > region.maxRadius() + 2) {
                        return String.format(Locale.ROOT, "%s sampled %d, %d at distance %.1f", region.id(), point.x(), point.z(), distance);
                    }
                }
            }
            return null;
        });
        test.check(id(), "searches find spots", () -> {
            long searches = this.service.search().searches();
            long found = this.service.search().found();
            return searches < 20 || found * 4 >= searches ? null
                : found + " of " + searches + " searches found a spot; check that the rings lie in pre-generated land";
        });
    }
}
