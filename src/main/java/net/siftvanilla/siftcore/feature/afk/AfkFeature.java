package net.siftvanilla.siftcore.feature.afk;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/**
 * AFK: notices players who are away from the keyboard (with anti-bypass rules), marks them for the tab list and
 * placeholders ({@link AfkStatus}), kicks those who stay away outside the AFK zone, and runs the AFK zone where
 * players earn shards just by being there (one account per connection, no combat, an optional daily limit).
 * <p>
 * Player settings (AFK &amp; shards): where the zone countdown and the payouts show, how the kick warning shows, the
 * "now AFK" and "welcome back" lines, and the welcome-back summary.
 */
public final class AfkFeature implements Feature {

    /** Where the time to the next shard shows in the zone. Was a switch: on reads as the hotbar, off as off. */
    public static final Choice<AlertStyle> STATUS = Choices.alert("afk-zone-status", AlertStyle.ACTIONBAR,
            AlertStyle.ACTIONBAR, AlertStyle.BOSSBAR, AlertStyle.OFF)
        .legacyValue("true", AlertStyle.ACTIONBAR.id()).legacyValue("false", AlertStyle.OFF.id())
        .text(AfkMessages.SETTING_STATUS, AfkMessages.SETTING_STATUS_DESCRIPTION).build();
    /** How a player is told about shards earned in the zone (reaching the daily limit always shows). */
    public static final Choice<AlertStyle> PAYOUTS = Choices.alert("afk-zone-payouts", AlertStyle.ACTIONBAR,
            AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.OFF)
        .text(AfkMessages.SETTING_PAYOUTS, AfkMessages.SETTING_PAYOUTS_DESCRIPTION).build();
    /** How the warning before an AFK kick shows: a chat line or a title. It can't be turned off. */
    public static final Choice<AlertStyle> KICK_WARNING = Choices.alert("afk-kick-warning", AlertStyle.CHAT,
            AlertStyle.CHAT, AlertStyle.TITLE)
        .text(AfkMessages.SETTING_KICK_WARNING, AfkMessages.SETTING_KICK_WARNING_DESCRIPTION).build();
    /** Where "You are now AFK" and "Welcome back" show ({@code /afk} always answers). */
    public static final Choice<AlertStyle> STATUS_MESSAGES = Choices.alert("afk-status-messages", AlertStyle.ACTIONBAR,
            AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.OFF)
        .text(AfkMessages.SETTING_STATUS_MESSAGES, AfkMessages.SETTING_STATUS_MESSAGES_DESCRIPTION).build();
    /** A chat line on coming back: how long the player was away and the shards they earned meanwhile. */
    public static final Toggle RETURN_SUMMARY = new Toggle("afk-return-summary", true, AfkMessages.SETTING_RETURN_SUMMARY,
        AfkMessages.SETTING_RETURN_SUMMARY_DESCRIPTION, null);
    private static final Duration TICK = Duration.ofSeconds(1);

    private final Services services;
    private final Setting<AfkSettings> settings;
    private final VanishStatus vanish;
    private final AfkService service;
    private final AfkCommands commands;
    private Task ticker = Task.NONE;

    /**
     * @param combat    combat-tagged players earn nothing in the zone
     * @param spawnArea tells staff whether the zone lies in the protected spawn area
     * @param vanish    vanished staff are left out of AFK lists and zone counts
     */
    public AfkFeature(Services services, List<ConfigProblem> problems, CombatTags combat, SpawnArea spawnArea, VanishStatus vanish) {
        this.services = services;
        this.vanish = vanish;
        this.settings = services.configs().register("features/afk.yml", AfkSettings::parse, problems);
        services.lang().register(AfkMessages.class);
        registerSettings(services.settings(), this.settings::get);
        var perms = services.permissions();
        perms.declare(AfkService.COMMAND_AFK, "Mark yourself AFK with /afk", true);
        perms.declare(AfkService.COMMAND_ZONE, "Teleport to the AFK zone with /afkzone", true);
        perms.declare(AfkService.BYPASS_KICK, "Never be kicked for being AFK", false);
        perms.declare(AfkService.ADMIN, "See who is AFK (/afk list) and set up the AFK zone (/afkzone info, pos1, pos2, arrival, set, reset)",
            false);
        for (Map.Entry<String, Long> tier : this.settings.get().rankShards().entrySet()) {
            perms.declare(AfkService.TIER_PREFIX + tier.getKey(), "Earn " + tier.getValue()
                + " shards per interval in the AFK zone (the highest granted tier wins)", PermissionDefault.FALSE);
        }
        ZoneStore store = new ZoneStore(services.plugin().getDataFolder().toPath().resolve("data/afk-zone.yml"),
            services.scheduler().asyncExecutor(), services.plugin().getLogger());
        this.service = new AfkService(services, this.settings, combat, spawnArea, vanish, store);
        this.commands = new AfkCommands(services, this.service, this.settings, vanish);
    }

    /**
     * Registers the AFK settings in the AFK &amp; shards group, in the catalog's order. The zone settings are offered
     * while the zone is on (the countdown also needs {@code rewards.status-every} above 0s), the kick warning while
     * the kick and its warning are on.
     */
    static void registerSettings(PlayerSettings prefs, Supplier<AfkSettings> config) {
        prefs.register(SettingCategories.AFK, STATUS, SettingOptions.<AlertStyle>builder().order(1)
            .availableWhen(() -> config.get().zoneEnabled() && !config.get().statusEvery().isZero()).build());
        prefs.register(SettingCategories.AFK, PAYOUTS, SettingOptions.<AlertStyle>builder().order(2)
            .availableWhen(() -> config.get().zoneEnabled()).build());
        prefs.register(SettingCategories.AFK, KICK_WARNING, SettingOptions.<AlertStyle>builder().order(3)
            .availableWhen(() -> warnsBeforeKick(config.get())).build());
        prefs.register(SettingCategories.AFK, STATUS_MESSAGES, SettingOptions.<AlertStyle>builder().order(4).build());
        prefs.register(SettingCategories.AFK, RETURN_SUMMARY, SettingOptions.<Boolean>builder().order(5).build());
    }

    /** Whether the config kicks AFK players and warns them first (the kick warning setting needs both). */
    static boolean warnsBeforeKick(AfkSettings settings) {
        AfkClock.Timing timing = settings.timing();
        return timing.kickAfterMillis() > 0 && timing.warnBeforeMillis() > 0;
    }

    @Override
    public String id() {
        return "afk";
    }

    /** Who is AFK, for the rest of SiftCore (stats playtime, teleport requests, the tab list). Thread-safe. */
    public AfkStatus status() {
        return this.service;
    }

    /** The AFK zone as the shards page shows it. */
    public AfkZoneInfo zone() {
        return this.service;
    }

    @Override
    public void enable() {
        this.service.loadOverride();
        Bukkit.getPluginManager().registerEvents(new AfkListener(this.service, this.settings::get, this.services.messenger()),
            this.services.plugin());
        for (Player online : Bukkit.getOnlinePlayers()) {
            this.service.join(online);
        }
        this.ticker = this.services.scheduler().asyncTimer(this.service::tickAll, TICK, TICK);
        this.settings.onReload(s -> this.service.refreshZone());
        registerPlaceholders();
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        var lang = this.services.lang();
        placeholders.register("afk_status", "AFK for a player who is away from the keyboard, empty otherwise",
            p -> p != null && this.service.afk(p.getUniqueId()) ? lang.plain(AfkMessages.PLACEHOLDER) : "");
        placeholders.register("afk_time", "How long the player has been AFK (like 5m), empty when not AFK",
            p -> {
                PlayerAfk state = state(p);
                long since = state == null ? -1 : state.afkSince();
                return since < 0 ? "" : Durations.format(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - since)));
            });
        placeholders.register("afk_zone_next", "Seconds until the player's next shard in the AFK zone, empty when not earning",
            p -> {
                PlayerAfk state = state(p);
                int seconds = state == null ? -1 : state.nextShardSeconds();
                return seconds < 0 ? "" : Integer.toString(seconds);
            });
        placeholders.register("afk_zone_today", "Shards the player earned in the AFK zone today",
            p -> p == null ? "0" : Long.toString(this.service.earnedToday(p.getUniqueId())));
        placeholders.register("afk_zone_players", "Players in the AFK zone (vanished staff not counted)",
            p -> Integer.toString(this.service.visibleInside()));
        placeholders.register("afk_count", "Players who are AFK right now (vanished staff not counted)",
            p -> {
                int count = 0;
                for (PlayerAfk state : this.service.tracked()) {
                    if (state.afk() && !this.vanish.vanished(state.player())) {
                        count++;
                    }
                }
                return Integer.toString(count);
            });
    }

    private PlayerAfk state(OfflinePlayer player) {
        return player == null ? null : this.service.state(player.getUniqueId());
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void disable() {
        this.ticker.cancel();
        this.service.flush();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "activity classifier rejects pushes and macros", AfkFeature::classifierCheck);
        test.check(id(), "AFK zone is open", () -> {
            AfkSettings s = this.settings.get();
            if (!s.zoneEnabled()) {
                return null;
            }
            ZoneBox box = this.service.box();
            if (box == null) {
                return "the zone's world " + this.service.spec().world() + " is not loaded";
            }
            if (box.arrival() != null && !box.contains(box.world(), box.arrival().x(), box.arrival().y(), box.arrival().z())) {
                return "the arrival point is outside the zone";
            }
            return null;
        });
        test.check(id(), "AFK zone keeps resting players safe", () -> {
            ZoneBox box = this.service.box();
            if (box == null || this.settings.get().zoneSafe() || this.service.insideSpawn(box)) {
                return null;
            }
            return "the zone is outside the protected spawn area and zone.safe is off, so players resting there can be attacked";
        });
        test.check(id(), "AFK zone slots are consistent", () -> this.service.sessions().check());
        test.check(id(), "only online players are tracked", () -> {
            for (PlayerAfk state : this.service.tracked()) {
                UUID id = state.player();
                if (Bukkit.getPlayer(id) == null) {
                    return "a player who left is still tracked (" + id + ")";
                }
            }
            return null;
        });
    }

    /** A scripted water push, jump macro and pacing macro must not count; a short walk must. */
    static String classifierCheck() {
        ActivityClassifier classifier = new ActivityClassifier(ActivityClassifier.Settings.DEFAULTS);
        long now = 1_000_000;
        // Walking into new ground counts.
        if (classifier.move(new ActivityClassifier.Move(0.5, 64, 0.5, 1.5, 64, 0.5, 0, 0, 0, 0, Float.NaN, false, false, false, now))
            != ActivityClassifier.Verdict.ACTIVE) {
            return "walking to a new block did not count";
        }
        // Water pushing the player along does not.
        for (int i = 1; i <= 5; i++) {
            if (classifier.move(new ActivityClassifier.Move(i + 0.5, 64, 0.5, i + 1.5, 64, 0.5, 0, 0, 0, 0, Float.NaN, true, false, false,
                now + i * 50L)) == ActivityClassifier.Verdict.ACTIVE) {
                return "a water push counted as activity";
            }
        }
        // Jumping in place does not.
        for (int i = 0; i < 10; i++) {
            double y = i % 2 == 0 ? 64 : 65.2;
            if (classifier.move(new ActivityClassifier.Move(6.5, y, 0.5, 6.5, 65.2 - (y - 64), 0.5, 0, 0, 0, 0, Float.NaN, false, false, false,
                now + 1_000 + i * 50L)) == ActivityClassifier.Verdict.ACTIVE) {
                return "a jump in place counted as activity";
            }
        }
        // Pacing back and forth over the same blocks does not, once the first pass has been walked.
        for (int lap = 0; lap < 4; lap++) {
            for (int step = 0; step < 4; step++) {
                double fromX = lap % 2 == 0 ? 20.5 + step : 24.5 - step;
                double toX = lap % 2 == 0 ? fromX + 1 : fromX - 1;
                ActivityClassifier.Verdict verdict = classifier.move(new ActivityClassifier.Move(fromX, 64, 0.5, toX, 64, 0.5, 0, 0, 0, 0,
                    Float.NaN, false, false, false, now + 2_000 + (lap * 4L + step) * 200L));
                if (lap > 0 && verdict == ActivityClassifier.Verdict.ACTIVE) {
                    return "pacing over the same blocks counted as activity";
                }
            }
        }
        return null;
    }
}
