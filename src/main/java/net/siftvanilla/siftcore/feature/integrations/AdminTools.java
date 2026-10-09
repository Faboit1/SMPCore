package net.siftvanilla.siftcore.feature.integrations;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The admin tools under {@code /sift}: integration status, backups, exports, the audit log, the permission and
 * placeholder registries and the generated reference docs. Every tool works from the console; anything slow runs off
 * the server threads and reports back when done.
 */
final class AdminTools {

    static final String STATUS = "siftcore.admin.integrations";
    static final String BACKUP = "siftcore.admin.backup";
    static final String EXPORT = "siftcore.admin.export";
    static final String AUDIT = "siftcore.admin.audit";
    static final String REGISTRY = "siftcore.admin.registry";

    private static final String ANY = "any";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(java.time.ZoneOffset.UTC);

    /** The integration states shown by {@code /sift integrations}. */
    interface StatusSource {
        List<StatusLine> lines();
    }

    /** One line of {@code /sift integrations}: a name and its state. */
    record StatusLine(String name, Component state) {
    }

    private final Services services;
    private final Setting<IntegrationsSettings> settings;
    private final BackupService backups;
    private final ExportService exports;
    private final StatusSource status;
    private final Supplier<String> version;
    private volatile Set<String> auditActions = Set.of();

    AdminTools(Services services, Setting<IntegrationsSettings> settings, BackupService backups, ExportService exports,
               StatusSource status, Supplier<String> version) {
        this.services = services;
        this.settings = settings;
        this.backups = backups;
        this.exports = exports;
        this.status = status;
        this.version = version;
    }

    static void declare(Permissions perms) {
        perms.declare(STATUS, "See which plugin integrations are active (/sift integrations)", false);
        perms.declare(BACKUP, "Back up the database and list backups (/sift backup)", false);
        perms.declare(EXPORT, "Export balances and the ledger as CSV (/sift export)", false);
        perms.declare(AUDIT, "Read the audit log of staff and store actions (/sift audit)", false);
        perms.declare(REGISTRY, "List permissions and placeholders and write the reference docs (/sift permissions, placeholders, docs)", false);
    }

    List<AdminFeature.AdminCommandPart> parts() {
        return List.of(
            () -> Commands.literal("integrations").requires(CommandSupport.permission(STATUS))
                .executes(ctx -> status(ctx.getSource().getSender())),
            () -> Commands.literal("backup").requires(CommandSupport.permission(BACKUP))
                .executes(ctx -> backup(ctx.getSource().getSender()))
                .then(Commands.literal("list").executes(ctx -> listBackups(ctx.getSource().getSender()))),
            () -> Commands.literal("export").requires(CommandSupport.permission(EXPORT))
                .executes(ctx -> export(ctx.getSource().getSender(), 0))
                .then(Commands.argument("days", IntegerArgumentType.integer(1, 36_500))
                    .executes(ctx -> export(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "days")))),
            this::auditTree,
            () -> registry("permissions", false),
            () -> registry("placeholders", true),
            () -> Commands.literal("docs").requires(CommandSupport.permission(REGISTRY))
                .executes(ctx -> docs(ctx.getSource().getSender())));
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private Lang lang() {
        return this.services.lang();
    }

    // ------------------------------------------------------------------ status

    private int status(CommandSender sender) {
        messenger().chat(sender, IntegrationsMessages.STATUS_HEADER, Arg.text("version", this.version.get()));
        for (StatusLine line : this.status.lines()) {
            messenger().chat(sender, IntegrationsMessages.STATUS_LINE, Arg.text("name", line.name()), Arg.component("state", line.state()));
        }
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ backups

    private int backup(CommandSender sender) {
        if (!this.backups.sqlite()) {
            messenger().chat(sender, IntegrationsMessages.BACKUP_MYSQL,
                Arg.text("database", this.services.core().get().storage().database()));
            return CommandSupport.OK;
        }
        if (this.backups.running()) {
            messenger().chat(sender, IntegrationsMessages.BACKUP_RUNNING);
            return CommandSupport.OK;
        }
        messenger().chat(sender, IntegrationsMessages.BACKUP_STARTED);
        String actor = actor(sender);
        this.backups.backup().whenComplete((result, error) -> {
            if (error != null) {
                String message = message(error);
                messenger().chat(sender, message.contains("already running") ? IntegrationsMessages.BACKUP_RUNNING
                    : IntegrationsMessages.BACKUP_FAILED, Arg.text("detail", message));
                return;
            }
            messenger().chat(sender, IntegrationsMessages.BACKUP_DONE, Arg.text("file", relative(result.file())),
                Arg.text("size", FileNames.size(result.bytes())), Arg.time("time", result.took()));
            if (!result.deleted().isEmpty()) {
                messenger().chat(sender, IntegrationsMessages.BACKUP_PRUNED, Arg.number("count", result.deleted().size()),
                    Arg.number("keep", this.settings.get().backups().keep()));
            }
            this.services.audit().record(actor, "admin.backup", null, result.file().getFileName() + " " + result.bytes() + " bytes");
        });
        return CommandSupport.OK;
    }

    private int listBackups(CommandSender sender) {
        if (!this.backups.sqlite()) {
            messenger().chat(sender, IntegrationsMessages.BACKUP_MYSQL,
                Arg.text("database", this.services.core().get().storage().database()));
            return CommandSupport.OK;
        }
        this.services.scheduler().async(() -> {
            List<BackupService.BackupFile> files;
            try {
                files = this.backups.list();
            } catch (IOException e) {
                messenger().chat(sender, IntegrationsMessages.BACKUP_FAILED, Arg.text("detail", String.valueOf(e.getMessage())));
                return;
            }
            if (files.isEmpty()) {
                messenger().chat(sender, IntegrationsMessages.BACKUP_LIST_EMPTY);
            } else {
                messenger().chat(sender, IntegrationsMessages.BACKUP_LIST_HEADER, Arg.number("count", files.size()),
                    Arg.text("folder", relative(this.backups.folder())));
                Instant now = Instant.now();
                for (BackupService.BackupFile file : files) {
                    messenger().chat(sender, IntegrationsMessages.BACKUP_LIST_LINE, Arg.text("file", file.name()),
                        Arg.text("size", FileNames.size(file.bytes())), Arg.time("age", positive(Duration.between(file.modified(), now))));
                }
            }
            Instant next = this.backups.nextAutomatic();
            if (next == null) {
                messenger().chat(sender, IntegrationsMessages.BACKUP_AUTOMATIC_OFF);
            } else {
                messenger().chat(sender, IntegrationsMessages.BACKUP_NEXT, Arg.time("time", positive(Duration.between(Instant.now(), next))),
                    Arg.number("keep", this.settings.get().backups().keep()));
            }
        });
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ exports

    private int export(CommandSender sender, int days) {
        messenger().chat(sender, IntegrationsMessages.EXPORT_STARTED);
        String actor = actor(sender);
        this.exports.export(days).whenComplete((result, error) -> {
            if (error != null) {
                String message = message(error);
                messenger().chat(sender, message.contains("already running") ? IntegrationsMessages.EXPORT_RUNNING
                    : IntegrationsMessages.EXPORT_FAILED, Arg.text("detail", message));
                return;
            }
            messenger().chat(sender, IntegrationsMessages.EXPORT_DONE, Arg.number("balances", result.balanceRows()),
                Arg.number("rows", result.ledgerRows()), Arg.text("folder", relative(this.exports.folder())), Arg.time("time", result.took()));
            this.services.audit().record(actor, "admin.export", null, result.balanceRows() + " balances, " + result.ledgerRows()
                + " ledger rows" + (days > 0 ? " (last " + days + " days)" : ""));
        });
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ audit

    private LiteralArgumentBuilder<CommandSourceStack> auditTree() {
        RequiredArgumentBuilder<CommandSourceStack, String> action = Commands.argument("action", StringArgumentType.word())
            .suggests((context, builder) -> {
                String remaining = builder.getRemainingLowerCase();
                Set<String> options = new TreeSet<>();
                options.add(ANY);
                for (String known : this.auditActions) {
                    int dot = known.indexOf('.');
                    options.add(dot > 0 ? known.substring(0, dot + 1) : known);
                    options.add(known);
                }
                for (String option : options) {
                    if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(option);
                    }
                }
                return builder.buildFuture();
            })
            .executes(ctx -> audit(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "action"), ANY, 1))
            .then(this.services.commands().knownPlayer("player")
                .executes(ctx -> audit(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "action"),
                    StringArgumentType.getString(ctx, "player"), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> audit(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "action"),
                        StringArgumentType.getString(ctx, "player"), IntegerArgumentType.getInteger(ctx, "page")))));
        return Commands.literal("audit").requires(CommandSupport.permission(AUDIT))
            .executes(ctx -> audit(ctx.getSource().getSender(), ANY, ANY, 1))
            .then(action);
    }

    /** Refreshes the action names suggested by {@code /sift audit}. */
    void refreshAuditActions() {
        this.services.database().read(c -> {
            Set<String> actions = new TreeSet<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT DISTINCT action FROM audit_log")) {
                while (rs.next() && actions.size() < 500) {
                    actions.add(rs.getString(1));
                }
            }
            return actions;
        }).thenAccept(actions -> this.auditActions = Set.copyOf(actions));
    }

    private int audit(CommandSender sender, String actionInput, String playerInput, int page) {
        String action = ANY.equals(actionInput) ? null : actionInput;
        String target = null;
        if (!ANY.equals(playerInput)) {
            Player online = Bukkit.getPlayerExact(playerInput);
            target = online != null ? online.getUniqueId().toString()
                : this.services.directory().uuid(playerInput).map(UUID::toString).orElse(playerInput);
        }
        int size = this.settings.get().auditPageSize();
        int limit = page * size + 1;
        this.services.audit().recent(action, target, limit).whenComplete((rows, error) -> {
            if (error != null) {
                messenger().chat(sender, IntegrationsMessages.AUDIT_FAILED, Arg.text("detail", message(error)));
                return;
            }
            Component any = lang().get(IntegrationsMessages.AUDIT_ANY);
            messenger().chat(sender, IntegrationsMessages.AUDIT_HEADER,
                action == null ? Arg.component("action", any) : Arg.text("action", action),
                ANY.equals(playerInput) ? Arg.component("player", any) : Arg.text("player", playerInput), Arg.number("page", page));
            int from = (page - 1) * size;
            if (rows.size() <= from) {
                messenger().chat(sender, IntegrationsMessages.AUDIT_EMPTY);
                return;
            }
            Instant now = Instant.now();
            List<AuditLog.Entry> shown = rows.subList(from, Math.min(rows.size(), from + size));
            for (AuditLog.Entry entry : shown) {
                Instant at = Instant.ofEpochMilli(entry.timestamp());
                Component time = lang().get(IntegrationsMessages.AUDIT_AGO, Arg.time("time", positive(Duration.between(at, now))))
                    .hoverEvent(HoverEvent.showText(Component.text(WHEN.format(at))));
                Arg details = Arg.text("details", entry.details() == null ? "" : entry.details());
                if (entry.target() == null || entry.target().isEmpty()) {
                    messenger().chat(sender, IntegrationsMessages.AUDIT_LINE_NO_TARGET, Arg.component("time", time),
                        Arg.text("actor", who(entry.actor())), Arg.text("action", entry.action()), details);
                } else {
                    messenger().chat(sender, IntegrationsMessages.AUDIT_LINE, Arg.component("time", time),
                        Arg.text("actor", who(entry.actor())), Arg.text("action", entry.action()),
                        Arg.text("target", who(entry.target())), details);
                }
            }
            if (rows.size() > from + size) {
                String command = "/sift audit " + actionInput + " " + playerInput + " " + (page + 1);
                messenger().chat(sender, IntegrationsMessages.AUDIT_MORE, Arg.component("command",
                    Component.text(command).clickEvent(ClickEvent.runCommand(command))));
            }
            this.refreshAuditActions();
        });
        return CommandSupport.OK;
    }

    /** A player name for a UUID string, otherwise the text itself ({@code console}, {@code vault:Shop}, an id). */
    private String who(String value) {
        if (value == null) {
            return "";
        }
        try {
            UUID uuid = UUID.fromString(value);
            return this.services.directory().get(uuid).map(known -> known.name()).orElse(value);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    // ------------------------------------------------------------------ registries

    private LiteralArgumentBuilder<CommandSourceStack> registry(String name, boolean placeholders) {
        return Commands.literal(name).requires(CommandSupport.permission(REGISTRY))
            .executes(ctx -> list(ctx.getSource().getSender(), name, placeholders, ANY, 1))
            .then(Commands.argument("filter", StringArgumentType.word())
                .executes(ctx -> list(ctx.getSource().getSender(), name, placeholders, StringArgumentType.getString(ctx, "filter"), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> list(ctx.getSource().getSender(), name, placeholders, StringArgumentType.getString(ctx, "filter"),
                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private int list(CommandSender sender, String command, boolean placeholders, String filter, int page) {
        String needle = ANY.equals(filter) ? "" : filter.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, String>> rows = new ArrayList<>();
        if (placeholders) {
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(this.services.placeholders().documentation()).entrySet()) {
                if (matches(needle, entry.getKey(), entry.getValue())) {
                    rows.add(entry);
                }
            }
        } else {
            for (Permissions.Node node : new java.util.TreeMap<>(this.services.permissions().all()).values()) {
                if (matches(needle, node.name(), node.description())) {
                    rows.add(Map.entry(node.name(), node.description() + "\u0000" + node.defaultValue().name()));
                }
            }
        }
        if (rows.isEmpty()) {
            messenger().chat(sender, IntegrationsMessages.REGISTRY_EMPTY, Arg.text("filter", filter));
            return CommandSupport.OK;
        }
        boolean console = !(sender instanceof Player);
        int size = console ? rows.size() : this.settings.get().listPageSize();
        int pages = Math.max(1, (rows.size() + size - 1) / size);
        int shownPage = Math.min(page, pages);
        messenger().chat(sender, placeholders ? IntegrationsMessages.PLACEHOLDERS_HEADER : IntegrationsMessages.PERMISSIONS_HEADER,
            Arg.number("count", rows.size()), Arg.number("page", shownPage), Arg.number("pages", pages));
        int from = (shownPage - 1) * size;
        for (Map.Entry<String, String> row : rows.subList(from, Math.min(rows.size(), from + size))) {
            if (placeholders) {
                messenger().chat(sender, IntegrationsMessages.PLACEHOLDER_LINE, Arg.text("name", row.getKey()),
                    Arg.text("description", row.getValue()), Arg.text("value", value(sender, row.getKey())));
            } else {
                String[] parts = row.getValue().split("\u0000", 2);
                messenger().chat(sender, IntegrationsMessages.PERMISSION_LINE, Arg.text("node", row.getKey()),
                    Arg.text("description", parts[0]), Arg.text("default", RegistryDocs.who(parts[1])));
            }
        }
        if (shownPage < pages) {
            String next = "/sift " + command + " " + filter + " " + (shownPage + 1);
            messenger().chat(sender, IntegrationsMessages.REGISTRY_MORE, Arg.component("command",
                Component.text(next).clickEvent(ClickEvent.runCommand(next))));
        }
        return CommandSupport.OK;
    }

    private static boolean matches(String needle, String name, String description) {
        return needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
            || (description != null && description.toLowerCase(Locale.ROOT).contains(needle));
    }

    /** The placeholder's current value for the sender ({@code -} when it has none or needs a player). */
    private String value(CommandSender sender, String usage) {
        if (usage.contains("<")) {
            return "-";
        }
        try {
            String value = this.services.placeholders().resolve(sender instanceof Player player ? player : null, usage);
            return value == null || value.isEmpty() ? "-" : value;
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private int docs(CommandSender sender) {
        String version = this.version.get();
        List<RegistryDocs.Node> nodes = new ArrayList<>();
        for (Permissions.Node node : this.services.permissions().all().values()) {
            nodes.add(new RegistryDocs.Node(node.name(), node.description(), node.defaultValue().name()));
        }
        Map<String, String> placeholders = this.services.placeholders().documentation();
        Path folder = this.services.plugin().getDataFolder().toPath().resolve("docs");
        String actor = actor(sender);
        this.services.scheduler().async(() -> {
            try {
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("permissions.md"), RegistryDocs.permissions(nodes, version), StandardCharsets.UTF_8);
                Files.writeString(folder.resolve("placeholders.md"), RegistryDocs.placeholders(placeholders, version), StandardCharsets.UTF_8);
            } catch (IOException e) {
                messenger().chat(sender, IntegrationsMessages.DOCS_FAILED, Arg.text("detail", String.valueOf(e.getMessage())));
                return;
            }
            messenger().chat(sender, IntegrationsMessages.DOCS_DONE, Arg.text("folder", relative(folder)),
                Arg.number("permissions", nodes.size()), Arg.number("placeholders", placeholders.size()));
            this.services.audit().record(actor, "admin.docs", null, nodes.size() + " permissions, " + placeholders.size() + " placeholders");
        });
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ helpers

    /** A path relative to the server folder, as admins see it ({@code plugins/SiftCore/backups/...}). */
    private String relative(Path path) {
        Path server = this.services.plugin().getDataFolder().toPath().toAbsolutePath().getParent().getParent();
        Path absolute = path.toAbsolutePath();
        return server != null && absolute.startsWith(server) ? server.relativize(absolute).toString() : absolute.toString();
    }

    static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static Duration positive(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
