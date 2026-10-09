package net.siftvanilla.siftcore.feature.admin;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.CoreControl;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * /sift: reload, debug, metrics, self-test and version. Console friendly. Admins who may reload are told on join when
 * the last startup or reload found config problems ("Config problem alerts" in the Staff settings group).
 */
public final class AdminFeature implements Feature, Listener {

    /** The permission of /sift reload; also who gets the config problem alert. */
    static final String RELOAD = "siftcore.admin.reload";
    /** A short wait after joining, so the alert isn't lost among the join messages. */
    private static final long ALERT_DELAY_TICKS = 40;
    /** How many problems the alert's hover lists. */
    private static final int HOVER_LINES = 8;

    /** The config problem alert on join. */
    public static final Toggle CONFIG_ALERTS = new Toggle("admin-config-alerts", true, AdminMessages.SETTING_CONFIG_ALERTS,
        AdminMessages.SETTING_CONFIG_ALERTS_DESCRIPTION, RELOAD);

    private final Services services;
    private final CoreControl control;
    private final List<AdminCommandPart> parts = new ArrayList<>();
    private final ConfigProblemLog problems = new ConfigProblemLog();
    private final Logger logger;

    /** A subcommand of /sift contributed by another feature (store delivery, backups, ...). */
    public interface AdminCommandPart {
        LiteralArgumentBuilder<CommandSourceStack> build();
    }

    public AdminFeature(Services services, CoreControl control) {
        this.services = services;
        this.control = control;
        services.lang().register(AdminMessages.class);
        registerSettings(services.settings());
        // Built first, so every startup problem the log reports later is seen.
        this.logger = services.plugin().getLogger();
        this.logger.addHandler(this.problems);
        var perms = services.permissions();
        perms.declare("siftcore.admin", "Use /sift", false);
        perms.declare(RELOAD, "Reload SiftCore's files", false);
        perms.declare("siftcore.admin.debug", "Toggle debug logging", false);
        perms.declare("siftcore.admin.metrics", "See internal metrics", false);
        perms.declare("siftcore.admin.selftest", "Run the self-test", false);
        perms.declare("siftcore.bypass.cooldown", "Skip command cooldowns", false);
        perms.declare("siftcore.teleport.bypass-warmup", "Teleport without a warmup", false);
    }

    /** Adds a /sift subcommand; call from a feature constructor. */
    public void addPart(AdminCommandPart part) {
        this.parts.add(part);
    }

    /** Registers "Config problem alerts" in the Staff group (catalog order: last). */
    static void registerSettings(PlayerSettings prefs) {
        prefs.register(SettingCategories.STAFF, CONFIG_ALERTS, SettingOptions.<Boolean>builder().order(13).build());
    }

    @Override
    public String id() {
        return "admin";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
    }

    @Override
    public void disable() {
        this.logger.removeHandler(this.problems);
    }

    /** Admins who may reload hear about config problems a moment after joining (unless they turned it off). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (problems().count() == 0 || !player.hasPermission(RELOAD)
            || !this.services.settings().get(player.getUniqueId(), CONFIG_ALERTS)) {
            return;
        }
        this.services.scheduler().entityLater(player, () -> alert(player), null, ALERT_DELAY_TICKS);
    }

    /**
     * The problems of the last startup or reload, with the startup's own list taken over once the plugin keeps one
     * ({@link CoreControl#startupProblems}); SiftCore keeps that list, and the log is read as well (problems count once).
     */
    private ConfigProblemLog problems() {
        this.control.startupProblems().ifPresent(this.problems::startup);
        return this.problems;
    }

    /** One line with the count; hovering lists the problems (as plain text) and clicking fills in /sift reload. */
    private void alert(Player player) {
        int count = problems().count();
        if (count == 0 || !player.isOnline()) {
            return;
        }
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>();
        List<String> first = this.problems.first(HOVER_LINES);
        for (String problem : first) {
            lines.add(Component.text(problem));
        }
        if (count > first.size()) {
            lines.add(lang.get(AdminMessages.CONFIG_ALERT_HOVER_MORE, Arg.number("count", count - first.size())));
        }
        player.sendMessage(lang.get(AdminMessages.CONFIG_ALERT, Arg.number("count", count))
            .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), lines)))
            .clickEvent(ClickEvent.suggestCommand("/sift reload")));
        this.services.messenger().feedback(player, AdminMessages.CONFIG_ALERT.feedback());
    }

    /** How many config problems the last startup or reload found (for tests). */
    int configProblems() {
        return problems().count();
    }

    @Override
    public List<SiftCommand> commands() {
        String perm = "siftcore.admin";
        return List.of(new SimpleCommand("sift", List.of("siftcore"), "SiftCore administration", perm, label -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(label)
                .requires(CommandSupport.permission(perm))
                .executes(ctx -> version(ctx.getSource().getSender()))
                .then(Commands.literal("version").executes(ctx -> version(ctx.getSource().getSender())))
                .then(Commands.literal("reload").requires(CommandSupport.permission(RELOAD))
                    .executes(ctx -> reload(ctx.getSource().getSender())))
                .then(Commands.literal("debug").requires(CommandSupport.permission("siftcore.admin.debug"))
                    .executes(ctx -> debug(ctx.getSource().getSender(), !this.control.debug()))
                    .then(Commands.argument("on", BoolArgumentType.bool())
                        .executes(ctx -> debug(ctx.getSource().getSender(), BoolArgumentType.getBool(ctx, "on")))))
                .then(Commands.literal("metrics").requires(CommandSupport.permission("siftcore.admin.metrics"))
                    .executes(ctx -> metrics(ctx.getSource().getSender())))
                .then(Commands.literal("selftest").requires(CommandSupport.permission("siftcore.admin.selftest"))
                    .executes(ctx -> selfTest(ctx.getSource().getSender())));
            for (AdminCommandPart part : this.parts) {
                root.then(part.build());
            }
            return root;
        }));
    }

    private int version(CommandSender sender) {
        this.services.messenger().chat(sender, AdminMessages.VERSION,
            Arg.text("version", this.services.plugin().getPluginMeta().getVersion()),
            Arg.text("server", Bukkit.getName() + " " + Bukkit.getVersion()),
            Arg.text("threading", this.services.scheduler().regionized() ? "region threads" : "main thread"));
        return CommandSupport.OK;
    }

    private int reload(CommandSender sender) {
        long start = System.nanoTime();
        this.problems.reloading();
        List<ConfigProblem> problems = this.control.reload();
        this.problems.reloaded(problems);
        if (problems.isEmpty()) {
            this.services.messenger().chat(sender, AdminMessages.RELOADED,
                Arg.number("files", this.services.configs().fileNames().size()),
                Arg.time("time", Duration.ofNanos(System.nanoTime() - start)));
            this.services.audit().record(actor(sender), "admin.reload", null, null);
            return CommandSupport.OK;
        }
        if (this.control.lastReloadApplied()) {
            this.services.messenger().chat(sender, AdminMessages.RELOAD_PARTIAL,
                Arg.number("files", this.services.configs().fileNames().size()),
                Arg.time("time", Duration.ofNanos(System.nanoTime() - start)), Arg.number("count", problems.size()));
            this.services.audit().record(actor(sender), "admin.reload", null, problems.size() + " problems");
        } else {
            this.services.messenger().chat(sender, AdminMessages.RELOAD_FAILED, Arg.number("count", problems.size()));
        }
        for (ConfigProblem problem : problems) {
            this.services.messenger().chat(sender, AdminMessages.PROBLEM, Arg.text("file", problem.file()),
                Arg.text("path", problem.path()), Arg.text("message", problem.message()));
        }
        return CommandSupport.OK;
    }

    private int debug(CommandSender sender, boolean on) {
        this.control.debug(on);
        this.services.messenger().chat(sender, AdminMessages.DEBUG,
            Arg.component("state", this.services.lang().get(on ? AdminMessages.ON : AdminMessages.OFF)));
        return CommandSupport.OK;
    }

    private int metrics(CommandSender sender) {
        CoreControl.Metrics m = this.control.metrics();
        var messenger = this.services.messenger();
        messenger.chat(sender, AdminMessages.METRICS_HEADER, Arg.time("uptime", Duration.ofMillis(m.uptimeMillis())));
        line(sender, "Pending writes", Long.toString(m.pendingWrites()));
        line(sender, "Committed writes", Long.toString(m.committedWrites()));
        line(sender, "Failed writes", Long.toString(m.failedWrites()));
        line(sender, "Average commit", String.format(java.util.Locale.ROOT, "%.2f ms", m.avgGroupMicros() / 1000.0));
        line(sender, "Transactions", Long.toString(m.transactions()));
        line(sender, "Storage failures", Long.toString(m.storeFailures()));
        line(sender, "Accounts", Integer.toString(m.accounts()));
        line(sender, "Dialog sessions", Integer.toString(m.dialogSessions()));
        line(sender, "Dialog clicks", m.dialogClicks() + " handled, " + m.dialogRejected() + " rejected");
        line(sender, "Features", Integer.toString(m.features()));
        line(sender, "Command labels", Integer.toString(m.commandLabels()));
        line(sender, "Pending deliveries", Integer.toString(this.services.deliveries().totalPending()));
        return CommandSupport.OK;
    }

    private void line(CommandSender sender, String name, String value) {
        this.services.messenger().chat(sender, AdminMessages.METRICS_LINE, Arg.text("name", name), Arg.text("value", value));
    }

    private int selfTest(CommandSender sender) {
        SelfTest test = this.control.selfTest();
        long start = System.nanoTime();
        this.services.messenger().chat(sender, AdminMessages.SELFTEST_START, Arg.number("count", test.size()));
        test.run().whenComplete((results, error) -> {
            if (error != null) {
                this.services.messenger().chat(sender, AdminMessages.SELFTEST_FAIL, Arg.text("feature", "selftest"),
                    Arg.text("name", "runner"), Arg.text("detail", String.valueOf(error)));
                return;
            }
            int passed = 0;
            int failed = 0;
            for (SelfTest.Result result : results) {
                if (result.passed()) {
                    passed++;
                    this.services.messenger().chat(sender, AdminMessages.SELFTEST_PASS, Arg.text("feature", result.feature()),
                        Arg.text("name", result.name()), Arg.text("time", String.format(java.util.Locale.ROOT, "%.2f ms", result.micros() / 1000.0)));
                } else {
                    failed++;
                    this.services.messenger().chat(sender, AdminMessages.SELFTEST_FAIL, Arg.text("feature", result.feature()),
                        Arg.text("name", result.name()), Arg.text("detail", result.detail()));
                }
            }
            this.services.messenger().chat(sender, AdminMessages.SELFTEST_DONE, Arg.number("passed", passed),
                Arg.number("failed", failed), Arg.time("time", Duration.ofNanos(System.nanoTime() - start)));
        });
        return CommandSupport.OK;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player ? player.getUniqueId().toString() : "console";
    }
}
