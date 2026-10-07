package net.siftvanilla.siftcore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.event.EconomyTransactionCommittedEvent;
import net.siftvanilla.siftcore.api.event.EconomyTransactionEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.CoreSettings;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.CommandService;
import net.siftvanilla.siftcore.core.command.CommandSettings;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Configs;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.config.YamlFiles;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.PlayerLifecycle;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.scheduler.RegionizedScheduler;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.teleport.Teleports;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.LangFiles;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.economy.CommittedTx;
import net.siftvanilla.siftcore.economy.Deliveries;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.LedgerHooks;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.storage.ConnectionSource;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.MysqlSource;
import net.siftvanilla.siftcore.storage.SqliteSource;
import net.siftvanilla.siftcore.ui.dialog.Dialogs;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.gui.MenuContext;
import net.siftvanilla.siftcore.ui.gui.MenuListener;
import net.siftvanilla.siftcore.ui.hub.HubRegistry;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The composition root. Builds every service in dependency order, constructs the features with what they need,
 * and owns startup, reload and shutdown. It holds references, not logic.
 */
public final class SiftCore implements CoreControl {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final List<Feature> features = new ArrayList<>();
    private final List<Feature> enabled = new ArrayList<>();
    private final SelfTest selfTest = new SelfTest();
    private Scheduler scheduler;
    private Configs configs;
    private Setting<CoreSettings> core;
    private Setting<IconSettings> iconSettings;
    private JdbcDatabase database;
    private Ledger ledger;
    private Icons icons;
    private TextStyle style;
    private Lang lang;
    private LangFiles langFiles;
    private Sounds sounds;
    private Dialogs dialogs;
    private MenuListener menuListener;
    private CommandService commandService;
    private Placeholders placeholders;
    private Services services;
    private volatile boolean debug;
    private long startedAt;

    public SiftCore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Starts everything. Throws if SiftCore cannot run safely. */
    public void start() throws Exception {
        long start = System.nanoTime();
        this.startedAt = System.currentTimeMillis();
        List<ConfigProblem> problems = new ArrayList<>();

        YamlFiles files = new YamlFiles(this.plugin);
        this.configs = new Configs(files);
        this.core = this.configs.register("config.yml", CoreSettings::parse, problems);
        Setting<CommandSettings> commandSettings = this.configs.register("commands.yml", CommandSettings::parse, problems);
        this.iconSettings = this.configs.register("icons.yml", IconSettings::parse, problems);
        this.debug = this.core.get().debug();

        this.scheduler = new RegionizedScheduler(this.plugin);

        this.database = openDatabase(this.core.get().storage());
        int version = new Migrations(this.database, this.logger, this.plugin::getResource, MigrationList.ALL).migrate();
        this.ledger = new Ledger(this.database, this.logger, this.core.get().money().maxAmount());
        this.ledger.load();
        this.core.onReload(settings -> this.ledger.maxBalance(settings.money().maxAmount()));
        PlayerDirectory directory = new PlayerDirectory(this.database, ipSalt());
        directory.load();
        PlayerSettings playerSettings = new PlayerSettings(this.database);
        AuditLog audit = new AuditLog(this.database);
        Deliveries deliveries = new Deliveries(this.database, this.ledger, this.logger);
        deliveries.load();
        Cooldowns cooldowns = new Cooldowns();

        this.icons = new Icons(Icons.readIndex(this.plugin.getResource("atlas-index.txt")));
        reportInvalidIcons(this.icons.load(this.iconSettings.get().icons()), problems);
        this.style = new TextStyle(this.core.get().palette(), this.icons);
        this.lang = new Lang(this.style, () -> this.core.get().money());
        this.lang.register(CoreMessages.class);
        this.lang.register(TeleportMessages.class);
        this.sounds = new Sounds();
        this.sounds.load(this.core.get().sounds());
        Messenger messenger = new Messenger(this.lang, this.sounds);

        this.dialogs = new Dialogs(this.scheduler, messenger, this.logger);
        Templates templates = new Templates(this.lang);
        MenuContext menus = new MenuContext(this.scheduler, messenger, this.lang, this.dialogs, templates);
        this.menuListener = new MenuListener(this.core.get().guiClickInterval().toMillis());
        HubRegistry hub = new HubRegistry();

        CommandSupport commandSupport = new CommandSupport(messenger, directory, cooldowns, commandSettings,
            () -> this.core.get().money());
        this.commandService = new CommandService(this.plugin, commandSettings);
        this.placeholders = new Placeholders();
        CombatTags combatTags = new CombatTags();
        Teleports teleports = new Teleports(this.scheduler, messenger, combatTags);

        this.services = new Services(this.plugin, this.scheduler, this.configs, this.core, this.database, this.ledger,
            deliveries, directory, playerSettings, audit, cooldowns, this.lang, messenger, this.dialogs, templates,
            menus, hub, commandSupport, this.placeholders, teleports);

        this.features.addAll(new FeatureCatalog(this.services, combatTags, this, problems).create());

        List<String> langIds = new ArrayList<>();
        langIds.add("core");
        for (Feature feature : this.features) {
            langIds.add(feature.id());
        }
        this.langFiles = new LangFiles(files, langIds);
        problems.addAll(this.langFiles.load(this.lang));

        installLedgerHooks();
        for (Feature feature : this.features) {
            try {
                feature.enable();
                this.enabled.add(feature);
            } catch (Exception e) {
                throw new IllegalStateException("Feature " + feature.id() + " failed to start", e);
            }
            for (var command : feature.commands()) {
                this.commandService.add(command);
            }
            feature.selfTest(this.selfTest);
        }
        registerCoreSelfTests();

        listen(new PlayerLifecycle(directory, playerSettings, cooldowns, this.logger));
        listen(this.dialogs);
        listen(this.menuListener);
        listen(teleports);
        this.commandService.install();
        this.core.onReload(settings -> {
            this.sounds.load(settings.sounds());
            this.menuListener.minInterval(settings.guiClickInterval().toMillis());
            this.debug = settings.debug();
        });
        this.scheduler.asyncTimer(cooldowns::sweep, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5));

        for (ConfigProblem problem : problems) {
            this.logger.severe("Config problem: " + problem);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        this.logger.info("SiftCore " + this.plugin.getPluginMeta().getVersion() + " enabled in " + millis + " ms: "
            + this.enabled.size() + " features, " + this.commandService.all().size() + " commands, "
            + this.ledger.accountCount() + " accounts, schema v" + version + ", "
            + this.core.get().storage().type() + " storage, " + (this.scheduler.regionized() ? "region threading" : "single main thread") + ".");
    }

    private JdbcDatabase openDatabase(CoreSettings.Storage storage) throws Exception {
        ConnectionSource source;
        if (storage.type().equals("mysql")) {
            source = new MysqlSource(storage.host(), storage.port(), storage.database(), storage.username(),
                storage.password(), storage.poolSize(), storage.ssl());
        } else {
            Path file = this.plugin.getDataFolder().toPath().resolve(storage.sqliteFile());
            source = new SqliteSource(file, 3);
        }
        return new JdbcDatabase(source, this.logger);
    }

    private byte[] ipSalt() throws IOException {
        Path file = this.plugin.getDataFolder().toPath().resolve("data/ip-salt.bin");
        if (Files.exists(file)) {
            byte[] salt = Files.readAllBytes(file);
            if (salt.length >= 16) {
                return salt;
            }
        }
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        Files.createDirectories(file.getParent());
        Files.write(file, salt);
        return salt;
    }

    private void reportInvalidIcons(java.util.Set<String> invalid, List<ConfigProblem> problems) {
        for (String name : invalid) {
            var sprite = this.iconSettings.get().icons().get(name);
            problems.add(new ConfigProblem("icons.yml", "icons." + name, "points to " + sprite
                + ", which does not exist in that atlas in this Minecraft version (it would show as a magenta square)"));
        }
    }

    private void installLedgerHooks() {
        this.ledger.hooks(new LedgerHooks() {
            @Override
            public boolean allow(LedgerTx tx) {
                if (EconomyTransactionEvent.getHandlerList().getRegisteredListeners().length == 0) {
                    return true;
                }
                return new EconomyTransactionEvent(tx.id(), tx.kind(), tx.actor(), tx.postings()).callEvent();
            }

            @Override
            public void committed(CommittedTx tx) {
                if (EconomyTransactionCommittedEvent.getHandlerList().getRegisteredListeners().length == 0) {
                    return;
                }
                new EconomyTransactionCommittedEvent(tx.id(), tx.kind(), tx.actor(), tx.postings(), tx.balancesAfter()).callEvent();
            }
        });
    }

    private void registerCoreSelfTests() {
        this.selfTest.checkAsync("core", "ledger invariants", () -> this.ledger.audit().thenApply(report ->
            report.healthy() ? null : String.join("; ", report.problems())));
        this.selfTest.check("core", "every message has text", () -> {
            for (var key : this.lang.registered().values()) {
                String plain = this.lang.plain(key);
                if (plain.equals(key.path())) {
                    return key.path() + " has no text";
                }
            }
            return null;
        });
        this.selfTest.check("core", "icons resolve", () -> {
            int expected = this.iconSettings.get().icons().size();
            int loaded = this.icons.all().size();
            return expected == loaded ? null : (expected - loaded) + " icon(s) point to missing sprites";
        });
        this.selfTest.check("core", "database writer is healthy", () ->
            this.database.failedWrites() == 0 ? null : this.database.failedWrites() + " write(s) failed since start");
        this.selfTest.check("core", "economy accepts transactions", () ->
            this.ledger.available() ? null : "the economy is read-only after storage failures");
    }

    private void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, this.plugin);
    }

    /** Reloads every config and lang file; nothing changes unless everything is valid. */
    @Override
    public List<ConfigProblem> reload() {
        List<ConfigProblem> problems = new ArrayList<>(this.configs.reload());
        if (!problems.isEmpty()) {
            return problems;
        }
        java.util.Set<String> invalid = this.icons.load(this.iconSettings.get().icons());
        reportInvalidIcons(invalid, problems);
        this.style.update(this.core.get().palette(), this.icons);
        problems.addAll(this.langFiles.load(this.lang));
        return problems;
    }

    @Override
    public SelfTest selfTest() {
        return this.selfTest;
    }

    @Override
    public boolean debug() {
        return this.debug;
    }

    @Override
    public void debug(boolean on) {
        this.debug = on;
    }

    @Override
    public Metrics metrics() {
        return new Metrics(System.currentTimeMillis() - this.startedAt, this.database.pendingWrites(),
            this.database.committedWrites(), this.database.failedWrites(), this.database.averageGroupMicros(),
            this.ledger.executedCount(), this.ledger.storeFailures(), this.ledger.accountCount(),
            this.dialogs.sessionCount(), this.dialogs.handledCount(), this.dialogs.rejectedCount(),
            this.enabled.size(), this.commandService.registeredLabels().size());
    }

    @Override
    public Database database() {
        return this.database;
    }

    public Services services() {
        return this.services;
    }

    /** Stops features in reverse order, then flushes and closes storage. */
    public void stop() {
        for (int i = this.enabled.size() - 1; i >= 0; i--) {
            Feature feature = this.enabled.get(i);
            try {
                feature.disable();
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "Feature " + feature.id() + " failed to stop cleanly", t);
            }
        }
        this.enabled.clear();
        if (this.dialogs != null) {
            this.dialogs.clear();
        }
        if (this.scheduler != null) {
            this.scheduler.cancelAll();
        }
        if (this.database != null) {
            this.database.close();
            this.logger.info("Storage flushed and closed (" + this.database.committedWrites() + " writes this session).");
        }
    }
}
