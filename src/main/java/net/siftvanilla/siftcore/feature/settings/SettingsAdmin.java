package net.siftvanilla.siftcore.feature.settings;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /sift settings} (node {@code siftcore.admin.settings}; console friendly): other players' settings, online or
 * not.
 * <pre>
 * /sift settings [player]                          what they changed, and other values stored for them
 * /sift settings [player] [setting]                the value, default, where it comes from and the permission
 * /sift settings [player] [setting] [value|reset]  changes it (no permission needed, but staff are warned)
 * /sift settings [player] reset [setting|group|all] puts settings back to the defaults
 * /sift settings catalog                           writes every setting as a table to plugins/SiftCore/docs/settings.md
 * </pre>
 * Every setting is reachable, also ones that need a permission or are not listed in the dialog. Reads go to the
 * database after every queued write; changes go through the registry ({@link Change.Cause#ADMIN}: reported to
 * {@code SettingChangeEvent} listeners, not cancellable) and are written to the audit log. A row under a setting's old
 * id ({@link LegacyRows}) counts as the setting's stored value, and a change or reset removes it first.
 */
final class SettingsAdmin {

    static final String PERMISSION = SharedSettings.ADMIN_NODE;
    private static final String RESET = "reset";

    private final Services services;
    private final Lang lang;
    private final Messenger messenger;
    private final CommandSupport support;

    SettingsAdmin(Services services) {
        this.services = services;
        this.lang = services.lang();
        this.messenger = services.messenger();
        this.support = services.commands();
    }

    private PlayerSettings settings() {
        return this.services.settings();
    }

    AdminFeature.AdminCommandPart part() {
        return () -> Commands.literal("settings")
            .requires(CommandSupport.permission(PERMISSION))
            .executes(ctx -> {
                this.messenger.chat(ctx.getSource().getSender(), SettingsMessages.ADMIN_HELP);
                return CommandSupport.OK;
            })
            .then(Commands.literal("catalog").executes(ctx -> catalog(ctx.getSource().getSender())))
            .then(this.support.knownPlayer("player")
                .executes(ctx -> withPlayer(ctx, this::list))
                .then(Commands.literal(RESET)
                    .executes(ctx -> withPlayer(ctx, (sender, player) -> reset(sender, player, "all")))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(this::suggestResetTarget)
                        .executes(ctx -> withPlayer(ctx, (sender, player) -> reset(sender, player, StringArgumentType.getString(ctx, "target"))))))
                .then(Commands.argument("setting", StringArgumentType.word())
                    .suggests(this::suggestSetting)
                    .executes(ctx -> withPlayer(ctx, (sender, player) -> detail(sender, player, StringArgumentType.getString(ctx, "setting"))))
                    .then(Commands.argument("value", StringArgumentType.greedyString())
                        .suggests(this::suggestValue)
                        .executes(ctx -> withPlayer(ctx, (sender, player) -> set(sender, player, StringArgumentType.getString(ctx, "setting"),
                            StringArgumentType.getString(ctx, "value")))))));
    }

    @FunctionalInterface
    private interface Action {
        void run(CommandSender sender, UUID player);
    }

    private int withPlayer(CommandContext<CommandSourceStack> ctx, Action action) {
        Optional<UUID> player = this.support.known(ctx, "player");
        player.ifPresent(uuid -> action.run(ctx.getSource().getSender(), uuid));
        return CommandSupport.OK;
    }

    private String name(UUID player) {
        return this.services.directory().name(player);
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    /** Reports a failed read or write to the sender and the log. */
    private void failed(CommandSender sender, Throwable error) {
        this.services.plugin().getLogger().log(Level.WARNING, "A /sift settings read or write failed", error);
        this.messenger.chat(sender, SettingsMessages.ADMIN_FAILED, Arg.text("reason", String.valueOf(error.getMessage())));
    }

    // ------------------------------------------------------------------ list

    /** What a player changed (stored rows that differ from the default), and the other values stored for them. */
    void list(CommandSender sender, UUID player) {
        String name = name(player);
        settings().stored(player).whenComplete((rows, error) -> {
            if (error != null) {
                failed(sender, error);
                return;
            }
            PlayerSettings settings = settings();
            Registry registry = settings.registry();
            List<Runnable> lines = new ArrayList<>();
            List<String> others = new ArrayList<>();
            int changed = 0;
            // A row under a setting's old id is their value of that setting until they log in and core moves it.
            for (Map.Entry<String, String> row : new TreeMap<>(LegacyRows.resolve(rows, registry).rows()).entrySet()) {
                Registry.Entry<?> entry = registry.entry(row.getKey());
                if (entry == null) {
                    others.add(row.getKey());
                    continue;
                }
                Row shown = row(entry, row.getValue());
                if (shown == null) {
                    continue;
                }
                if (shown.reason() == null) {
                    changed++;
                    lines.add(() -> this.messenger.chat(sender, SettingsMessages.ADMIN_ROW, Arg.text("id", entry.id()),
                        Arg.text("value", shown.value()), Arg.text("default", shown.fallback())));
                } else {
                    lines.add(() -> this.messenger.chat(sender, SettingsMessages.ADMIN_ROW_IGNORED, Arg.text("id", entry.id()),
                        Arg.text("value", shown.value()), Arg.text("reason", this.lang.plain(shown.reason()))));
                }
            }
            if (lines.isEmpty()) {
                this.messenger.chat(sender, SettingsMessages.ADMIN_NONE, Arg.text("name", name));
            } else {
                this.messenger.chat(sender, SettingsMessages.ADMIN_HEADER, Arg.text("name", name), Arg.number("count", changed));
                lines.forEach(Runnable::run);
            }
            if (!others.isEmpty()) {
                this.messenger.chat(sender, SettingsMessages.ADMIN_OTHER, Arg.text("keys", String.join(", ", others)));
            }
        });
    }

    /** One stored row as staff see it: its value, the default, and why it does not apply (null: it does). */
    private record Row(String value, String fallback, net.siftvanilla.siftcore.core.text.MessageKey reason) {
    }

    /** A stored row of a setting, or null when it changes nothing (it holds the default, or no value of the setting). */
    private <T> Row row(Registry.Entry<T> entry, String stored) {
        PlayerSetting<T> setting = entry.setting();
        T value = setting.decodeOrNull(stored);
        if (value == null) {
            return null;
        }
        PlayerSettings settings = settings();
        String encoded = setting.encode(value);
        if (settings.locked(setting)) {
            return new Row(encoded, setting.encode(settings.defaultValue(setting)), SettingsMessages.REASON_LOCKED);
        }
        if (settings.hidden(setting)) {
            return new Row(encoded, setting.encode(settings.defaultValue(setting)), SettingsMessages.REASON_HIDDEN);
        }
        T fallback = settings.defaultValue(setting);
        return setting.same(value, fallback) ? null : new Row(encoded, setting.encode(fallback), null);
    }

    // ------------------------------------------------------------------ one setting

    /** One setting of a player: its value, default and source, its group, kind and values, and its permission. */
    void detail(CommandSender sender, UUID player, String word) {
        Registry.Entry<?> entry = SettingsArgs.anySetting(settings().registry(), word);
        if (entry == null) {
            this.messenger.chat(sender, SettingsMessages.ADMIN_UNKNOWN, Arg.text("name", word.toLowerCase(Locale.ROOT)));
            return;
        }
        detail(sender, player, entry);
    }

    private <T> void detail(CommandSender sender, UUID player, Registry.Entry<T> entry) {
        PlayerSetting<T> setting = entry.setting();
        String name = name(player);
        settings().stored(player).thenCombine(settings().lookup(player, setting), (rows, value) -> {
            PlayerSettings settings = settings();
            String stored = LegacyRows.resolve(rows, settings.registry()).rows().get(entry.id());
            this.messenger.chat(sender, SettingsMessages.ADMIN_DETAIL, Arg.text("id", entry.id()), Arg.text("name", name),
                Arg.text("value", setting.encode(value)), Arg.text("default", setting.encode(settings.defaultValue(setting))),
                Arg.text("source", this.lang.plain(source(entry, stored))));
            this.messenger.chat(sender, SettingsMessages.ADMIN_DETAIL_GROUP, Arg.text("group", this.lang.plain(entry.category().label())),
                Arg.text("kind", setting.kind().name().toLowerCase(Locale.ROOT)),
                Arg.text("values", SettingsCommands.valuesText(this.lang, setting, List.of())));
            if (setting.permission() != null) {
                Player online = Bukkit.getPlayer(player);
                this.messenger.chat(sender, SettingsMessages.ADMIN_DETAIL_PERMISSION, Arg.text("permission", setting.permission()),
                    Arg.text("has", this.lang.plain(online == null ? SettingsMessages.HAS_OFFLINE
                        : online.hasPermission(setting.permission()) ? SettingsMessages.HAS_YES : SettingsMessages.HAS_NO)));
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                failed(sender, error);
            }
        });
    }

    /** Where a player's value of a setting comes from. */
    private <T> net.siftvanilla.siftcore.core.text.MessageKey source(Registry.Entry<T> entry, String stored) {
        PlayerSettings settings = settings();
        PlayerSetting<T> setting = entry.setting();
        if (settings.locked(setting)) {
            return SettingsMessages.SOURCE_LOCKED;
        }
        if (settings.hidden(setting)) {
            return SettingsMessages.SOURCE_HIDDEN;
        }
        if (stored != null && setting.decodeOrNull(stored) != null) {
            return SettingsMessages.SOURCE_STORED;
        }
        String configured = settings.overrides().defaults().get(entry.id());
        return configured != null && PlayerSettings.configValue(entry, configured) != null
            ? SettingsMessages.SOURCE_SERVER : SettingsMessages.SOURCE_BUILT_IN;
    }

    // ------------------------------------------------------------------ changing

    /** Changes one setting of a player ({@code reset} puts it back to the default). */
    void set(CommandSender sender, UUID player, String word, String input) {
        Registry.Entry<?> entry = SettingsArgs.anySetting(settings().registry(), word);
        if (entry == null) {
            this.messenger.chat(sender, SettingsMessages.ADMIN_UNKNOWN, Arg.text("name", word.toLowerCase(Locale.ROOT)));
            return;
        }
        if (RESET.equalsIgnoreCase(input.strip()) && !(entry.setting() instanceof Choice<?> choice && choice.option(RESET) != null)) {
            reset(sender, player, entry.id());
            return;
        }
        set(sender, player, entry, input);
    }

    private <T> void set(CommandSender sender, UUID player, Registry.Entry<T> entry, String input) {
        PlayerSetting<T> setting = entry.setting();
        String name = name(player);
        settings().lookup(player, setting).whenComplete((current, error) -> {
            if (error != null) {
                failed(sender, error);
                return;
            }
            List<Choice.Option<T>> every = setting instanceof Choice<T> choice ? choice.options() : List.of();
            SettingsArgs.Parsed<T> parsed = SettingsArgs.parse(setting, input, current, every, option -> option.text(this.lang));
            if (!parsed.ok()) {
                this.messenger.chat(sender, SettingsMessages.ADMIN_INVALID, Arg.text("id", entry.id()),
                    Arg.text("values", SettingsCommands.valuesText(this.lang, setting, List.of())));
                return;
            }
            T value = parsed.value();
            String old = setting.encode(current);
            if (setting.same(current, value)) {
                this.messenger.chat(sender, SettingsMessages.ADMIN_UNCHANGED, Arg.text("id", entry.id()), Arg.text("name", name),
                    Arg.text("value", old));
                return;
            }
            PlayerSettings settings = settings();
            if (!settings.locked(setting) && !settings.hidden(setting)) {
                // The change goes through (only a lock or the server hiding it refuses a staff change): first remove a
                // row under an old id, which would otherwise move over the change at the player's next login.
                LegacyRows.forget(this.services.database(), player, entry);
            }
            SetResult result = settings.set(player, setting, value, Change.admin(sender.getName()));
            switch (result) {
                case CHANGED -> {
                    String now = setting.encode(value);
                    this.services.audit().record(actor(sender), "settings.set", player.toString(), entry.id() + ": " + old + " -> " + now);
                    this.messenger.chat(sender, SettingsMessages.ADMIN_SET, Arg.text("id", entry.id()), Arg.text("name", name),
                        Arg.text("value", now), Arg.text("old", old));
                    Player online = Bukkit.getPlayer(player);
                    if (setting.permission() != null && online != null && !online.hasPermission(setting.permission())) {
                        this.messenger.chat(sender, SettingsMessages.ADMIN_NO_PERMISSION, Arg.text("name", name),
                            Arg.text("permission", setting.permission()));
                    }
                }
                case UNCHANGED -> this.messenger.chat(sender, SettingsMessages.ADMIN_UNCHANGED, Arg.text("id", entry.id()),
                    Arg.text("name", name), Arg.text("value", old));
                case LOCKED -> refused(sender, entry, SettingsMessages.REASON_LOCKED);
                case NOT_ALLOWED -> refused(sender, entry, SettingsMessages.REASON_HIDDEN);
                case CANCELLED -> refused(sender, entry, SettingsMessages.REASON_CANCELLED);
                case INVALID, UNKNOWN -> this.messenger.chat(sender, SettingsMessages.ADMIN_INVALID, Arg.text("id", entry.id()),
                    Arg.text("values", SettingsCommands.valuesText(this.lang, setting, List.of())));
            }
        });
    }

    private void refused(CommandSender sender, Registry.Entry<?> entry, net.siftvanilla.siftcore.core.text.MessageKey reason) {
        this.messenger.chat(sender, SettingsMessages.ADMIN_REFUSED, Arg.text("id", entry.id()), Arg.text("reason", this.lang.plain(reason)));
    }

    /**
     * Puts a player's settings back to the defaults: one setting, a group, or {@code all} (every registered setting;
     * remembered UI state such as sort orders stays). A locked setting is refused when named on its own; a group or
     * {@code all} leaves locked ones alone and names them.
     */
    void reset(CommandSender sender, UUID player, String target) {
        Registry registry = settings().registry();
        List<Registry.Entry<?>> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(registry.byId().values());
        } else {
            Registry.Entry<?> one = SettingsArgs.anySetting(registry, target);
            SettingCategory category = registry.category(target);
            if (one != null) {
                if (settings().locked(one.setting())) {
                    refused(sender, one, SettingsMessages.REASON_LOCKED);
                    return;
                }
                targets.add(one);
            } else if (category != null) {
                targets.addAll(registry.in(category.id()));
            } else {
                this.messenger.chat(sender, SettingsMessages.ADMIN_UNKNOWN, Arg.text("name", target.toLowerCase(Locale.ROOT)));
                return;
            }
        }
        String name = name(player);
        settings().stored(player).whenComplete((rows, error) -> {
            if (error != null) {
                failed(sender, error);
                return;
            }
            PlayerSettings settings = settings();
            ResetPlan plan = plan(targets, LegacyRows.resolve(rows, settings.registry()).rows().keySet(), settings::locked);
            if (!plan.reset().isEmpty()) {
                List<PlayerSetting<?>> reset = new ArrayList<>(plan.reset().size());
                for (Registry.Entry<?> entry : plan.reset()) {
                    // A row under an old id goes first, so the next login can't move it over the reset.
                    LegacyRows.forget(this.services.database(), player, entry);
                    reset.add(entry.setting());
                }
                settings.reset(player, reset, Change.admin(sender.getName()));
                this.services.audit().record(actor(sender), "settings.reset", player.toString(), String.join(", ", plan.ids()));
            }
            if (plan.locked().isEmpty()) {
                this.messenger.chat(sender, SettingsMessages.ADMIN_RESET, Arg.number("count", plan.reset().size()), Arg.text("name", name));
            } else {
                this.messenger.chat(sender, SettingsMessages.ADMIN_RESET_LOCKED, Arg.number("count", plan.reset().size()),
                    Arg.text("name", name), Arg.number("locked", plan.locked().size()), Arg.text("ids", String.join(", ", plan.locked())));
            }
        });
    }

    /**
     * What a staff reset does.
     *
     * @param reset  the settings it puts back (they have a stored row, under their id or an old one)
     * @param locked the ids of settings with a stored row that the server locked, left alone
     */
    record ResetPlan(List<Registry.Entry<?>> reset, List<String> locked) {
        List<String> ids() {
            return this.reset.stream().map(Registry.Entry::id).toList();
        }
    }

    /** Splits the targeted settings with a stored row ({@code stored}: ids, old-id rows already read as theirs). */
    static ResetPlan plan(List<Registry.Entry<?>> targets, Set<String> stored,
                          Predicate<PlayerSetting<?>> locked) {
        List<Registry.Entry<?>> reset = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Registry.Entry<?> entry : targets) {
            if (!stored.contains(entry.id())) {
                continue;
            }
            if (locked.test(entry.setting())) {
                skipped.add(entry.id());
            } else {
                reset.add(entry);
            }
        }
        return new ResetPlan(List.copyOf(reset), List.copyOf(skipped));
    }

    // ------------------------------------------------------------------ catalog

    /** Writes the settings catalog ({@link SettingsTable}) to {@code plugins/SiftCore/docs/settings.md}, off the thread. */
    private int catalog(CommandSender sender) {
        Registry registry = settings().registry();
        String table = SettingsTable.markdown(registry, key -> this.lang.plain(key), this.services.plugin().getPluginMeta().getVersion());
        java.nio.file.Path folder = this.services.plugin().getDataFolder().toPath().resolve("docs");
        this.services.scheduler().async(() -> {
            try {
                java.nio.file.Files.createDirectories(folder);
                java.nio.file.Files.writeString(folder.resolve("settings.md"), table, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                failed(sender, e);
                return;
            }
            this.messenger.chat(sender, SettingsMessages.ADMIN_CATALOG, Arg.number("count", registry.byId().size()),
                Arg.text("file", "plugins/" + this.services.plugin().getDataFolder().getName() + "/docs/settings.md"));
        });
        return CommandSupport.OK;
    }

    // ------------------------------------------------------------------ suggestions

    private CompletableFuture<Suggestions> suggestSetting(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        for (String id : settings().registry().byId().keySet()) {
            if (id.startsWith(prefix)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestValue(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        Registry.Entry<?> entry;
        try {
            entry = SettingsArgs.anySetting(settings().registry(), StringArgumentType.getString(ctx, "setting"));
        } catch (IllegalArgumentException e) {
            entry = null;
        }
        if (entry != null) {
            List<String> options = entry.setting() instanceof Choice<?> choice ? choice.optionIds() : List.of();
            SettingsArgs.suggestValues(entry.setting(), options, builder.getRemaining()).forEach(builder::suggest);
            if (RESET.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(RESET);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestResetTarget(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        if ("all".startsWith(prefix)) {
            builder.suggest("all");
        }
        Registry registry = settings().registry();
        for (SettingCategory category : registry.categories()) {
            if (category.id().startsWith(prefix)) {
                builder.suggest(category.id());
            }
        }
        if (prefix.length() >= SettingsArgs.IDS_AFTER) {
            for (String id : registry.byId().keySet()) {
                if (id.startsWith(prefix)) {
                    builder.suggest(id);
                }
            }
        }
        return builder.buildFuture();
    }
}
