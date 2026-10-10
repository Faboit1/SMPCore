package net.siftvanilla.siftcore.feature.integrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.SiftCoreApi;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.api.event.StoreDeliveryEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.integration.floodgate.BedrockText;
import net.siftvanilla.siftcore.integration.floodgate.FloodgateForms;
import net.siftvanilla.siftcore.integration.floodgate.FormPlan;
import net.siftvanilla.siftcore.integration.luckperms.LuckPermsHook;
import net.siftvanilla.siftcore.integration.placeholderapi.SiftCoreExpansion;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.FormBridge;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

/**
 * Optional plugins and the public API: the PlaceholderAPI expansion, LuckPerms rank labels ({@link #ranks()}), store
 * rank grants and joining a full server ({@link FullServerJoins}), Bedrock forms through Floodgate, and
 * {@link SiftCoreApi} in the services manager. Also the admin tools under {@code /sift}: integration status, backups,
 * exports, the audit log, the permission and placeholder registries with their generated docs, and store delivery
 * (boosters included); and {@code /purchases}, every player's own store history.
 * <p>
 * Each plugin hook is connected in {@link #enable()} only when its plugin is enabled and its setting is on, and is
 * connected or disconnected again when {@code /sift reload} changes the setting. The classes that touch an optional
 * plugin's API are only loaded after that check, so SiftCore runs the same without any of them.
 * <p>
 * Players with {@code siftcore.settings.hide-rank} can turn {@link #SHOW_MY_RANK} off (Privacy settings): their rank
 * label is then left out wherever it comes from {@link #ranks()} (chat, chat cards, join lines, friend profiles, the
 * rank placeholders, the public API), and the scoreboard leaves it out of the tab list, nametags and sidebar.
 */
public final class IntegrationsFeature implements Feature {

    /**
     * Show my rank: off hides the player's rank tag everywhere SiftCore shows it (and in the placeholders other plugins
     * such as TAB read). Cosmetic only: perks and limits read LuckPerms groups and permissions. Offered while LuckPerms
     * is connected, to players with {@link SharedSettings#HIDE_RANK_NODE}; read by {@link SwitchableRanks} and by the
     * scoreboard (both in the display settings package).
     */
    public static final Toggle SHOW_MY_RANK = new Toggle("show-my-rank", true, IntegrationsMessages.SETTING_SHOW_RANK,
        IntegrationsMessages.SETTING_SHOW_RANK_DESCRIPTION, SharedSettings.HIDE_RANK_NODE);
    /** Its place in the Privacy group (after the shared privacy settings and the big-order switch). */
    static final int SHOW_MY_RANK_ORDER = 5;
    /**
     * The rank placeholders and their descriptions (the registry text behind {@code /sift placeholders} and the
     * generated docs/placeholders.md). All three read as unranked with {@link #SHOW_MY_RANK} off.
     */
    static final Map<String, String> RANK_PLACEHOLDERS = Map.of(
        "rank", "Your rank as plain text (LuckPerms; empty for the default group or with Show my rank off)",
        "rank_group", "Your primary LuckPerms group in lowercase (default without LuckPerms or with Show my rank off)",
        "rank_color", "Your rank's colour as #RRGGBB (the first colour of a gradient), empty without one or with Show my rank off");

    /** How a hook stands, for {@code /sift integrations} and the self-test. */
    enum HookState {
        ACTIVE,
        MISSING,
        OFF,
        FAILED
    }

    private static final String VAULT = "Vault";

    private final Services services;
    private final Setting<IntegrationsSettings> settings;
    private final CombatTags combat;
    private final EconomyApi economy;
    private final Logger logger;
    private final SwitchableRanks ranks;
    private final StoreService store;
    private final BackupService backups;
    private final AdminTools tools;
    private final PurchasesView purchases;
    private final FullServerJoins fullJoins;

    private volatile LuckPermsHook luckPerms;
    private volatile RankAccess rankAccess = RankAccess.NONE;
    private volatile Runnable papi;
    private volatile FormBridge bedrock;
    private volatile SiftCoreApi api;
    private volatile HookState papiState = HookState.MISSING;
    private volatile HookState luckPermsState = HookState.MISSING;
    private volatile HookState floodgateState = HookState.MISSING;

    /**
     * @param boosters the server sell boosters, which the store delivers ({@code /sift store booster})
     */
    public IntegrationsFeature(Services services, List<ConfigProblem> problems, AdminFeature admin, CombatTags combat, CrateKeys keys,
                               EconomyApi economy, ServerBoosters boosters) {
        this.services = services;
        this.combat = combat;
        this.economy = economy;
        this.logger = services.plugin().getLogger();
        this.settings = services.configs().register("features/integrations.yml",
            reader -> IntegrationsSettings.parse(reader, services.core().get().money()), problems);
        services.lang().register(IntegrationsMessages.class);
        this.ranks = new SwitchableRanks(this::rankShown);
        registerSettings(services.settings(), this.ranks::connected);
        AdminTools.declare(services.permissions());
        StoreCommands.declare(services.permissions());
        PurchasesView.declare(services.permissions());
        FullServerJoins.declare(services.permissions());

        Path data = services.plugin().getDataFolder().toPath();
        var storage = services.core().get().storage();
        Path sqlite = storage.type().equals("sqlite") ? data.resolve(storage.sqliteFile()) : null;
        this.store = new StoreService(services.ledger(), services.database(), keys, boosters, () -> this.rankAccess,
            () -> this.settings.get().store(), IntegrationsFeature::allowDelivery, System::currentTimeMillis);
        this.backups = new BackupService(services.database(), sqlite, data.resolve("backups"), () -> this.settings.get().backups(),
            services.scheduler(), this.logger);
        ExportService exports = new ExportService(services.database(), data.resolve("exports"), services.directory()::names);
        this.tools = new AdminTools(services, this.settings, this.backups, exports, this::statusLines, this::version);
        for (AdminFeature.AdminCommandPart part : this.tools.parts()) {
            admin.addPart(part);
        }
        StoreCommands storeCommands = new StoreCommands(services, this.settings, this.store, keys);
        admin.addPart(storeCommands.part());
        this.purchases = new PurchasesView(services, this.store, storeCommands::what);
        this.fullJoins = new FullServerJoins(() -> this.rankAccess, () -> this.settings.get().joinFull(), this.logger,
            System::currentTimeMillis);
    }

    @Override
    public String id() {
        return "integrations";
    }

    /**
     * Rank labels and groups for chat, the scoreboard and other features: from LuckPerms once it is connected,
     * {@link Ranks#NONE} answers until then and without it. The same object for the whole run.
     */
    public Ranks ranks() {
        return this.ranks;
    }

    /**
     * Registers Show my rank in the Privacy group: offered while rank labels come from LuckPerms ({@code connected}),
     * never exposed as a placeholder (a privacy setting).
     */
    static void registerSettings(PlayerSettings settings, java.util.function.BooleanSupplier connected) {
        settings.register(SettingCategories.PRIVACY, SHOW_MY_RANK, SettingOptions.<Boolean>builder().order(SHOW_MY_RANK_ORDER)
            .availableWhen(connected).placeholder(false).build());
    }

    /**
     * Whether a player shows their rank tag: {@link #SHOW_MY_RANK} with their permission applied for online players
     * (without the permission they read the default, so losing it shows the rank again). Any thread.
     */
    private boolean rankShown(java.util.UUID player) {
        Player online = Bukkit.getPlayer(player);
        return online != null ? this.services.settings().get(online, SHOW_MY_RANK) : this.services.settings().get(player, SHOW_MY_RANK);
    }

    /** The installed Bedrock form bridge, or null without Floodgate. */
    public FormBridge bedrockBridge() {
        return this.bedrock;
    }

    private String version() {
        return this.services.plugin().getPluginMeta().getVersion();
    }

    private static boolean allowDelivery(java.util.UUID player, StoreDeliveryEvent.Kind kind, String item, long amount, Duration duration,
                                         String ref, String actor) {
        if (StoreDeliveryEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return true;
        }
        return new StoreDeliveryEvent(player, kind, item, amount, duration, ref, actor).callEvent();
    }

    @Override
    public List<SiftCommand> commands() {
        return List.of(this.purchases.command());
    }

    @Override
    public void enable() throws Exception {
        List<Delivery> pending = this.store.load();
        registerPlaceholders();
        applyHooks();
        Bukkit.getPluginManager().registerEvents(this.fullJoins, this.services.plugin());
        this.settings.onReload(reloaded -> {
            applyHooks();
            LuckPermsHook hook = this.luckPerms;
            if (hook != null) {
                hook.invalidate();
            }
            this.backups.schedule();
        });
        PublicApi api = new PublicApi(version(), this.economy, this.combat, this.services.placeholders(), this.ranks);
        Bukkit.getServicesManager().register(SiftCoreApi.class, api, this.services.plugin(), ServicePriority.Normal);
        this.api = api;
        this.backups.schedule();
        this.tools.refreshAuditActions();
        if (!pending.isEmpty()) {
            resume(pending.size());
        }
    }

    /** Connects the hooks whose plugin is enabled and setting on, and disconnects those turned off. */
    private synchronized void applyHooks() {
        IntegrationsSettings settings = this.settings.get();
        var plugins = Bukkit.getPluginManager();

        boolean papiInstalled = plugins.isPluginEnabled(SiftCoreExpansion.PLUGIN);
        if (papiInstalled && settings.placeholderApi()) {
            if (this.papi == null) {
                try {
                    this.papi = SiftCoreExpansion.register(this.services.plugin(), this.services.placeholders(), this.logger);
                    this.papiState = this.papi == null ? HookState.FAILED : HookState.ACTIVE;
                } catch (RuntimeException | LinkageError e) {
                    this.papiState = HookState.FAILED;
                    this.logger.log(Level.SEVERE, "Could not register SiftCore's placeholders with PlaceholderAPI", e);
                }
            }
        } else {
            closePapi();
            this.papiState = papiInstalled ? HookState.OFF : HookState.MISSING;
        }

        boolean lpInstalled = plugins.isPluginEnabled(LuckPermsHook.PLUGIN);
        if (lpInstalled && settings.luckPerms().enabled()) {
            if (this.luckPerms == null) {
                try {
                    LuckPermsHook hook = LuckPermsHook.connect(this.services.plugin(),
                        () -> options(this.settings.get().luckPerms()),
                        () -> this.services.core().get().palette().secondary(), this.logger);
                    this.luckPerms = hook;
                    this.rankAccess = new LuckPermsAccess(hook);
                    this.ranks.use(hook.ranks());
                    this.luckPermsState = HookState.ACTIVE;
                } catch (RuntimeException | LinkageError e) {
                    this.luckPermsState = HookState.FAILED;
                    this.logger.log(Level.SEVERE, "Could not connect to LuckPerms; rank labels and store ranks are off", e);
                }
            }
        } else {
            closeLuckPerms();
            this.luckPermsState = lpInstalled ? HookState.OFF : HookState.MISSING;
        }

        boolean floodgateInstalled = plugins.isPluginEnabled(FloodgateForms.PLUGIN);
        if (floodgateInstalled && settings.floodgate()) {
            if (this.bedrock == null) {
                try {
                    Lang lang = this.services.lang();
                    FormBridge bridge = FloodgateForms.create(() -> lang.get(CoreMessages.UI_CLOSE),
                        () -> lang.get(IntegrationsMessages.FORM_ACTION), this.logger);
                    this.services.dialogs().bedrock(bridge);
                    this.bedrock = bridge;
                    this.floodgateState = HookState.ACTIVE;
                    this.logger.info("Floodgate found: dialogs are shown to Bedrock players as forms.");
                } catch (RuntimeException | LinkageError e) {
                    this.floodgateState = HookState.FAILED;
                    this.logger.log(Level.SEVERE, "Could not connect to Floodgate; Bedrock players get no dialogs", e);
                }
            }
        } else {
            closeFloodgate();
            this.floodgateState = floodgateInstalled ? HookState.OFF : HookState.MISSING;
        }
    }

    private static LuckPermsHook.Options options(IntegrationsSettings.LuckPerms settings) {
        return new LuckPermsHook.Options(settings.labelMeta(), settings.colorMeta(), settings.gradientMeta(), settings.hiddenGroups());
    }

    private void closePapi() {
        Runnable close = this.papi;
        this.papi = null;
        if (close != null) {
            try {
                close.run();
            } catch (RuntimeException | LinkageError e) {
                this.logger.log(Level.WARNING, "Could not unregister the PlaceholderAPI expansion", e);
            }
        }
    }

    private void closeLuckPerms() {
        LuckPermsHook hook = this.luckPerms;
        this.luckPerms = null;
        this.rankAccess = RankAccess.NONE;
        this.ranks.use(Ranks.NONE);
        if (hook != null) {
            try {
                hook.unregister();
            } catch (RuntimeException | LinkageError e) {
                this.logger.log(Level.WARNING, "Could not unsubscribe from LuckPerms events", e);
            }
        }
    }

    private void closeFloodgate() {
        if (this.bedrock != null) {
            this.services.dialogs().bedrock(null);
            this.bedrock = null;
        }
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        // A player who turned Show my rank off reads as unranked in all three, so a tab list plugin can't give it away.
        placeholders.register("rank", RANK_PLACEHOLDERS.get("rank"),
            player -> player == null ? "" : this.ranks.label(player.getUniqueId()));
        placeholders.register("rank_group", RANK_PLACEHOLDERS.get("rank_group"),
            player -> player == null ? "default" : this.ranks.shownGroup(player.getUniqueId()));
        placeholders.register("rank_color", RANK_PLACEHOLDERS.get("rank_color"),
            player -> {
                LuckPermsHook hook = this.luckPerms;
                return player == null || hook == null || !this.ranks.shown(player.getUniqueId()) ? "" : hook.colorHex(player.getUniqueId());
            });
    }

    /** Finishes rank deliveries that were interrupted (a crash, or LuckPerms failing) before the restart. */
    private void resume(int count) {
        if (!this.rankAccess.available()) {
            this.logger.warning(count + " store rank deliveries or revokes are waiting for LuckPerms, which is not connected. "
                + "They finish on the next start with LuckPerms, or with the same /sift store command again.");
            return;
        }
        this.logger.info("Finishing " + count + " interrupted store rank deliveries or revokes.");
        for (CompletableFuture<StoreService.Outcome> result : this.store.resumePending()) {
            result.whenComplete((outcome, error) -> {
                if (error != null || outcome.status() == StoreService.Status.FAILED) {
                    String detail = error != null ? AdminTools.message(error) : outcome.reason() + (outcome.detail() == null ? "" : ": " + outcome.detail());
                    this.logger.warning("A store rank delivery or revoke could not be finished (" + detail + "); it stays waiting.");
                    return;
                }
                Delivery delivery = outcome.delivery();
                boolean revoked = outcome.status() == StoreService.Status.REVOKED;
                this.services.audit().record("system", revoked ? "store.revoke.resumed" : "store.resumed", delivery.player().toString(),
                    "ref " + delivery.ref() + ": " + delivery.item());
                this.logger.info("Finished " + (revoked ? "revoking" : "the store rank delivery") + " " + delivery.ref() + " ("
                    + delivery.item() + " for " + this.services.directory().name(delivery.player()) + ").");
            });
        }
    }

    @Override
    public void disable() {
        SiftCoreApi api = this.api;
        this.api = null;
        if (api != null) {
            Bukkit.getServicesManager().unregister(SiftCoreApi.class, api);
        }
        this.backups.stop();
        closePapi();
        closeFloodgate();
        closeLuckPerms();
    }

    // ------------------------------------------------------------------ status

    private List<AdminTools.StatusLine> statusLines() {
        Lang lang = this.services.lang();
        List<AdminTools.StatusLine> lines = new ArrayList<>();
        lines.add(new AdminTools.StatusLine("Vault economy", vaultState(lang)));
        lines.add(new AdminTools.StatusLine("PlaceholderAPI", this.papiState == HookState.ACTIVE
            ? lang.get(IntegrationsMessages.STATE_ACTIVE_DETAIL, Arg.text("detail",
                this.services.placeholders().documentation().size() + " placeholders as %siftcore_<name>%"))
            : state(lang, this.papiState)));
        lines.add(new AdminTools.StatusLine("LuckPerms", this.luckPermsState == HookState.ACTIVE
            ? lang.get(IntegrationsMessages.STATE_ACTIVE_DETAIL, Arg.text("detail", "rank labels and store ranks"))
            : state(lang, this.luckPermsState)));
        lines.add(new AdminTools.StatusLine("Floodgate", this.floodgateState == HookState.ACTIVE
            ? lang.get(IntegrationsMessages.STATE_ACTIVE_DETAIL, Arg.text("detail", "dialogs as Bedrock forms"))
            : state(lang, this.floodgateState)));
        lines.add(new AdminTools.StatusLine("Public API", apiRegistered()
            ? lang.get(IntegrationsMessages.STATE_ACTIVE_DETAIL, Arg.text("detail", "SiftCoreApi in the services manager"))
            : lang.get(IntegrationsMessages.STATE_FAILED)));
        lines.add(new AdminTools.StatusLine("Store", lang.get(IntegrationsMessages.STATE_ACTIVE_DETAIL, Arg.text("detail",
            Lang.number(this.store.size()) + " references delivered, " + this.store.pendingCount() + " pending"))));
        lines.add(new AdminTools.StatusLine("Backups", Component.text(backupState())));
        return lines;
    }

    private static Component state(Lang lang, HookState state) {
        return lang.get(switch (state) {
            case ACTIVE -> IntegrationsMessages.STATE_ACTIVE;
            case MISSING -> IntegrationsMessages.STATE_MISSING;
            case OFF -> IntegrationsMessages.STATE_OFF;
            case FAILED -> IntegrationsMessages.STATE_FAILED;
        });
    }

    /** VaultUnlocked's registration of the classic economy, found by name so Vault classes are never loaded here. */
    private Component vaultState(Lang lang) {
        if (!Bukkit.getPluginManager().isPluginEnabled(VAULT)) {
            return lang.get(IntegrationsMessages.STATE_MISSING);
        }
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (service.getName().equals("net.milkbowl.vault.economy.Economy")) {
                RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(service);
                if (registration != null && registration.getPlugin() == this.services.plugin()) {
                    return lang.get(IntegrationsMessages.STATE_ACTIVE_DETAIL, Arg.text("detail", "other plugins pay and charge the server's money"));
                }
                return lang.get(IntegrationsMessages.STATE_OVERRIDDEN);
            }
        }
        return lang.get(IntegrationsMessages.STATE_FAILED);
    }

    private boolean apiRegistered() {
        RegisteredServiceProvider<SiftCoreApi> registration = Bukkit.getServicesManager().getRegistration(SiftCoreApi.class);
        return registration != null && registration.getProvider() == this.api;
    }

    private String backupState() {
        if (!this.backups.sqlite()) {
            return "the database is " + this.services.core().get().storage().type() + ": use mysqldump on the database server";
        }
        StringBuilder text = new StringBuilder();
        try {
            List<BackupService.BackupFile> files = this.backups.list();
            if (files.isEmpty()) {
                text.append("none yet");
            } else {
                text.append(files.size()).append(" kept, newest ")
                    .append(net.siftvanilla.siftcore.core.config.Durations.format(Duration.between(files.getFirst().modified(), Instant.now())))
                    .append(" ago");
            }
        } catch (java.io.IOException e) {
            text.append("the backups folder can't be read");
        }
        Instant next = this.backups.nextAutomatic();
        text.append(next == null ? ", automatic backups off" : ", next in "
            + net.siftvanilla.siftcore.core.config.Durations.format(Duration.between(Instant.now(), next)));
        return text.toString();
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "public API is registered", () -> apiRegistered() ? null : "SiftCoreApi is not in the services manager");
        test.check(id(), "optional plugins are connected", () -> {
            List<String> failed = new ArrayList<>();
            if (this.papiState == HookState.FAILED) {
                failed.add("PlaceholderAPI");
            }
            if (this.luckPermsState == HookState.FAILED) {
                failed.add("LuckPerms");
            }
            if (this.floodgateState == HookState.FAILED) {
                failed.add("Floodgate");
            }
            return failed.isEmpty() ? null : String.join(", ", failed) + " failed to connect (see the startup log)";
        });
        test.check(id(), "no store delivery is left pending", () -> {
            int pending = this.store.pendingCount();
            return pending == 0 ? null : pending + " store rank deliveries are waiting for LuckPerms (/sift integrations)";
        });
        test.check(id(), "backups folder is usable", () -> {
            if (!this.backups.sqlite()) {
                return null;
            }
            Path folder = this.backups.folder();
            try {
                Files.createDirectories(folder);
            } catch (java.io.IOException e) {
                return "cannot create " + folder + ": " + e.getMessage();
            }
            return Files.isWritable(folder) ? null : folder + " is not writable";
        });
        test.check(id(), "dialogs map to Bedrock forms", this::checkFormMapping);
        test.check(id(), "registry docs render", () -> {
            Map<String, String> placeholders = this.services.placeholders().documentation();
            String md = RegistryDocs.placeholders(placeholders, version());
            return placeholders.containsKey("rank") && md.contains("%siftcore_rank%") ? null : "the rank placeholder is missing from the docs";
        });
    }

    /** A pay-style form must become a custom form whose closing presses cancel, and a list a simple form. */
    private String checkFormMapping() {
        Lang lang = this.services.lang();
        FormPlan.Renderer renderer = new FormPlan.Renderer() {
            @Override
            public String text(Component component) {
                return TextStyle.plain(component);
            }

            @Override
            public String item(Body.Item item) {
                return "";
            }

            @Override
            public String closeLabel() {
                return lang.plain(CoreMessages.UI_CLOSE);
            }

            @Override
            public String actionLabel() {
                return lang.plain(IntegrationsMessages.FORM_ACTION);
            }
        };
        View form = this.services.templates().form(Component.text("Pay"), List.of(Component.text("Who and how much")),
            List.of(Templates.text("player", Component.text("Player"), "", 16), Templates.range("amount", Component.text("Amount"), 1, 64, 1, 1)),
            submission -> { }, submission -> { });
        FormPlan plan = FormPlan.of(form, renderer);
        if (plan.type() != FormPlan.Type.CUSTOM || plan.fields().size() != 3) {
            return "a form became " + plan.type() + " with " + plan.fields().size() + " components";
        }
        var answer = plan.submitted(java.util.Arrays.asList(null, "Alex", 5f));
        if (answer.isEmpty() || answer.get().button() != 0 || !"Alex".equals(answer.get().values().get("player"))) {
            return "submitting a form does not press its first button with the typed values";
        }
        if (plan.closed().map(FormPlan.Answer::button).orElse(-1) != 1) {
            return "closing a form does not press its back button";
        }
        View list = this.services.templates().list(Component.text("Menu"), List.of(), List.of(), 1, null);
        FormPlan simple = FormPlan.of(list, renderer);
        if (simple.type() != FormPlan.Type.SIMPLE || simple.clicked(0).map(FormPlan.Answer::button).orElse(-1) != 0) {
            return "a list does not become a simple form";
        }
        return BedrockText.render(Component.text("ok"), java.util.Locale.US).equals("ok") ? null : "form text does not render";
    }
}
