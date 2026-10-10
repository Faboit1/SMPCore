package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The steps behind {@code /sift settings} ({@link SettingsAdmin}): changes, resets and the reads that show a player's
 * settings, run one after another in the order staff typed them.
 * <p>
 * Each step reads the player's value (or stored rows) only once every earlier step ran and queued its writes
 * ({@link InOrder}), so two commands for the same player who is not online, typed within one database round trip (a
 * console script, {@code dispatchCommand} in one tick), see each other: a change compares with the value the one
 * before wrote and names it in the reply and the audit log, and a reset after a change finds the changed row.
 */
final class StaffChanges {

    private final PlayerSettings settings;
    private final Lang lang;
    private final Messenger messenger;
    private final AuditLog audit;
    private final Logger logger;
    private final Function<UUID, String> names;
    private final Function<UUID, Player> online;
    private final InOrder steps;

    /**
     * @param names  a player's name for replies
     * @param online the player if they are online (only to warn staff when they lack a setting's permission)
     */
    StaffChanges(PlayerSettings settings, Lang lang, Messenger messenger, AuditLog audit, Logger logger,
                 Function<UUID, String> names, Function<UUID, Player> online) {
        this.settings = settings;
        this.lang = lang;
        this.messenger = messenger;
        this.audit = audit;
        this.logger = logger;
        this.names = names;
        this.online = online;
        this.steps = new InOrder(logger);
    }

    /**
     * Starts {@code read} once every earlier step ran (so it sees their writes), then runs {@code step} with its value
     * (or its failure). For the reads that only show settings, so they follow the changes typed before them.
     */
    <T> void after(Supplier<CompletableFuture<T>> read, BiConsumer<? super T, ? super Throwable> step) {
        this.steps.then(read, step);
    }

    /** Completes once every step queued so far has run. */
    CompletableFuture<Void> idle() {
        return this.steps.idle();
    }

    static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    /** Reports a failed read or write to the sender and the log. */
    void failed(CommandSender sender, Throwable error) {
        this.logger.log(Level.WARNING, "A /sift settings read or write failed", error);
        this.messenger.chat(sender, SettingsMessages.ADMIN_FAILED, Arg.text("reason", String.valueOf(error.getMessage())));
    }

    void refused(CommandSender sender, Registry.Entry<?> entry, MessageKey reason) {
        this.messenger.chat(sender, SettingsMessages.ADMIN_REFUSED, Arg.text("id", entry.id()), Arg.text("reason", this.lang.plain(reason)));
    }

    // ------------------------------------------------------------------ changing

    /** Changes one setting of a player to typed text, once the steps typed before ran. */
    <T> void set(CommandSender sender, UUID player, Registry.Entry<T> entry, String input) {
        PlayerSetting<T> setting = entry.setting();
        String name = this.names.apply(player);
        this.steps.then(() -> this.settings.lookup(player, setting), (current, error) -> {
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
            // For a player who is not loaded, core deletes a row under an old id along with the change.
            SetResult result = this.settings.set(player, setting, value, Change.admin(sender.getName()));
            switch (result) {
                case CHANGED -> {
                    String now = setting.encode(value);
                    this.audit.record(actor(sender), "settings.set", player.toString(), entry.id() + ": " + old + " -> " + now);
                    this.messenger.chat(sender, SettingsMessages.ADMIN_SET, Arg.text("id", entry.id()), Arg.text("name", name),
                        Arg.text("value", now), Arg.text("old", old));
                    Player online = this.online.apply(player);
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

    /**
     * Puts the targeted settings of a player that have a stored row back to the default, once the steps typed before
     * ran; locked ones are left alone and named.
     */
    void reset(CommandSender sender, UUID player, List<Registry.Entry<?>> targets) {
        String name = this.names.apply(player);
        this.steps.then(() -> this.settings.stored(player), (rows, error) -> {
            if (error != null) {
                failed(sender, error);
                return;
            }
            ResetPlan plan = plan(targets, LegacyRows.resolve(rows, this.settings.registry()).rows().keySet(), this.settings::locked);
            if (!plan.reset().isEmpty()) {
                List<PlayerSetting<?>> reset = new ArrayList<>(plan.reset().size());
                for (Registry.Entry<?> entry : plan.reset()) {
                    // For a player who is not loaded, core deletes rows under old ids along with the reset.
                    reset.add(entry.setting());
                }
                this.settings.reset(player, reset, Change.admin(sender.getName()));
                this.audit.record(actor(sender), "settings.reset", player.toString(), String.join(", ", plan.ids()));
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

    /** A player's stored rows and their value of one setting, read together. */
    record Detail<T>(Map<String, String> rows, T value) {
    }
}
