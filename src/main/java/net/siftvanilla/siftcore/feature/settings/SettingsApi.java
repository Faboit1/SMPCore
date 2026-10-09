package net.siftvanilla.siftcore.feature.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.SettingsView;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.text.Lang;

/**
 * The public {@link SettingsView}: plain strings over the settings registry, so other plugins never see core types.
 * Thread-safe and non-blocking like {@link PlayerSettings} itself.
 * <p>
 * Groups and settings come in the dialog's order with the dialog's icons (the server's {@code categories} overrides
 * applied). Changes and resets are written to the audit log ({@code settings.set} and {@code settings.reset}, actor
 * {@code api} or {@code api:<actor>}, details {@code id: old -> new}); the value before is read before the change is
 * queued, from the database for a player who is not loaded, so the row names what they really had. The rows are
 * written in the order of the calls.
 */
final class SettingsApi implements SettingsView {

    /** The audit log's actor column holds this many characters. */
    static final int ACTOR_LENGTH = 36;
    private static final String API = "api";

    private final PlayerSettings settings;
    private final Lang lang;
    private final AuditLog audit;
    private final Supplier<Map<String, SettingsConfig.CategoryOverride>> overrides;
    private final Logger logger;
    private final InOrder audits;

    /**
     * @param overrides the server's group overrides as they are now ({@code categories} in {@code features/settings.yml})
     */
    SettingsApi(PlayerSettings settings, Lang lang, AuditLog audit, Supplier<Map<String, SettingsConfig.CategoryOverride>> overrides,
                Logger logger) {
        this.settings = settings;
        this.lang = lang;
        this.audit = audit;
        this.overrides = overrides;
        this.logger = logger;
        this.audits = new InOrder(logger);
    }

    @Override
    public List<CategoryInfo> categories() {
        Map<String, SettingsConfig.CategoryOverride> overrides = this.overrides.get();
        List<CategoryInfo> list = new ArrayList<>();
        for (SettingCategory category : SettingsGroups.ordered(this.settings.registry().categories(), overrides)) {
            list.add(new CategoryInfo(category.id(), SettingsGroups.order(category, overrides), this.lang.plain(category.label()),
                this.lang.plain(category.description()), SettingsGroups.icon(category, overrides)));
        }
        return list;
    }

    @Override
    public List<SettingInfo> settings() {
        Registry registry = this.settings.registry();
        List<SettingInfo> list = new ArrayList<>();
        for (SettingCategory category : SettingsGroups.ordered(registry.categories(), this.overrides.get())) {
            for (Registry.Entry<?> entry : registry.in(category.id())) {
                list.add(info(entry));
            }
        }
        return list;
    }

    @Override
    public Optional<SettingInfo> setting(String id) {
        Registry.Entry<?> entry = this.settings.registry().entry(id);
        return entry == null ? Optional.empty() : Optional.of(info(entry));
    }

    private <T> SettingInfo info(Registry.Entry<T> entry) {
        PlayerSetting<T> setting = entry.setting();
        String fallback = setting.encode(this.settings.defaultValue(setting));
        List<String> options = setting instanceof Choice<T> choice ? choice.optionIds() : List.of();
        long min = 0;
        long max = 0;
        long step = 0;
        String unit = "";
        if (setting instanceof NumberSetting number) {
            min = number.min();
            max = number.max();
            step = number.step();
            unit = number.unit() == null ? "" : this.lang.plain(number.unit()).strip();
        }
        return new SettingInfo(entry.id(), Type.valueOf(setting.kind().name()), entry.category().id(), this.lang.plain(setting.label()),
            this.lang.plain(setting.description()), fallback, options, min, max, step, unit, setting.permission(),
            this.settings.locked(setting), this.settings.hidden(setting));
    }

    @Override
    public String value(UUID player, String id) {
        return id == null ? null : this.settings.encoded(player, id);
    }

    @Override
    public CompletableFuture<Map<String, String>> stored(UUID player) {
        return this.settings.stored(player).thenApply(rows -> {
            Registry registry = this.settings.registry();
            Map<String, String> registered = new LinkedHashMap<>();
            // A row under a setting's old id is the player's value of it until they log in and core moves it.
            LegacyRows.resolve(rows, registry).rows().forEach((id, value) -> {
                if (registry.entry(id) != null) {
                    registered.put(id, value);
                }
            });
            return Map.copyOf(registered);
        });
    }

    @Override
    public Result set(UUID player, String id, String value, String actor) {
        Registry.Entry<?> entry = id == null ? null : this.settings.registry().entry(id);
        return entry == null ? Result.UNKNOWN : set(player, entry, value, actor == null || actor.isBlank() ? API : actor.strip());
    }

    private <T> Result set(UUID player, Registry.Entry<T> entry, String value, String actor) {
        PlayerSetting<T> setting = entry.setting();
        List<Choice.Option<T>> every = setting instanceof Choice<T> choice ? choice.options() : List.of();
        SettingsArgs.Parsed<T> parsed = SettingsArgs.parse(setting, value, this.settings.get(player, setting), every,
            option -> option.text(this.lang));
        if (!parsed.ok()) {
            return Result.INVALID;
        }
        // Refusals first, so an old-id row is only removed when the change goes through.
        if (this.settings.locked(setting)) {
            return Result.LOCKED;
        }
        if (this.settings.hidden(setting)) {
            return Result.NOT_ALLOWED;
        }
        T now = parsed.value();
        CompletableFuture<T> before = this.settings.lookup(player, setting);
        SetResult result = this.settings.set(player, setting, now, Change.api(actor));
        if (result == SetResult.CHANGED) {
            whenRead(before, old -> {
                if (!setting.same(old, now)) {
                    audit(actor, "settings.set", player, entry.id() + ": " + setting.encode(old) + " -> " + setting.encode(now));
                }
            });
        }
        return result(result);
    }

    @Override
    public Result reset(UUID player, String id) {
        Registry.Entry<?> entry = id == null ? null : this.settings.registry().entry(id);
        return entry == null ? Result.UNKNOWN : reset(player, entry);
    }

    private <T> Result reset(UUID player, Registry.Entry<T> entry) {
        PlayerSetting<T> setting = entry.setting();
        if (this.settings.locked(setting)) {
            return Result.LOCKED;
        }
        if (this.settings.hidden(setting)) {
            return Result.NOT_ALLOWED;
        }
        boolean loaded = this.settings.loaded(player);
        boolean changed = !loaded || this.settings.changed(player, setting);
        CompletableFuture<T> before = this.settings.lookup(player, setting);
        // Also removes a leftover row that holds the default (and, for a player who is not loaded, rows under old ids).
        this.settings.reset(player, List.of(setting), Change.reset(API));
        if (!changed) {
            return Result.UNCHANGED;
        }
        T now = this.settings.defaultValue(setting);
        whenRead(before, old -> {
            if (!setting.same(old, now)) {
                audit(API, "settings.reset", player, entry.id() + ": " + setting.encode(old) + " -> " + setting.encode(now));
            }
        });
        return Result.CHANGED;
    }

    /**
     * Runs {@code then} with the value before a change once it is read (logged when the read fails), after the steps of
     * earlier calls: the reads of a player who is not loaded complete on any of the database's callback threads, and
     * the audit rows must still land in the order of the changes.
     */
    private <T> void whenRead(CompletableFuture<T> before, Consumer<T> then) {
        // Already started: the change was queued right after this read, so the read must not wait for earlier steps.
        this.audits.then(() -> before, (old, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not read a setting's previous value for the audit log", error);
            } else {
                then.accept(old);
            }
        });
    }

    private void audit(String actor, String action, UUID player, String details) {
        this.audit.record(auditActor(actor), action, player.toString(), details).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not write a settings change to the audit log", error);
            }
        });
    }

    /** The audit log's actor for an API caller: {@code api}, or {@code api:<actor>} cut to the column's length. */
    static String auditActor(String actor) {
        String who = actor == null || actor.isBlank() || API.equals(actor) ? API : API + ":" + actor.strip();
        return who.length() > ACTOR_LENGTH ? who.substring(0, ACTOR_LENGTH) : who;
    }

    static Result result(SetResult result) {
        return Result.valueOf(result.name());
    }
}
