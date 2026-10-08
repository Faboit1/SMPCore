package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Combat: the combat tag (hits between players put both in combat; commands, teleports, elytra and spawn are
 * refused while it runs), combat logging, kill credit with anti-farm rules (teams, friends, alt accounts, repeated
 * pairs), clean death messages and kill streak announcements. Kills and deaths go to the stats; bounties hook into
 * counted kills through {@link net.siftvanilla.siftcore.api.event.PlayerKillCreditEvent}. Vanished staff take no
 * part in combat and are never named to players who can't see them.
 */
public final class CombatFeature implements Feature {

    public static final Toggle DEATH_MESSAGES = new Toggle("death-messages", true,
        CombatMessages.SETTING_DEATH_MESSAGES, CombatMessages.SETTING_DEATH_MESSAGES_DESCRIPTION, null);

    private final Services services;
    private final Setting<CombatSettings> settings;
    private final CombatTags tags;
    private final SpawnArea spawn;
    private final RecentPairs pairs = new RecentPairs();
    private final KillLog killLog;
    private final CombatTimer timer;
    private final CombatListener listener;
    private final CombatCommands commands;
    private Task timerTask = Task.NONE;

    public CombatFeature(Services services, List<ConfigProblem> problems, CombatTags tags, StatsRecorder stats, TeamLookup teams,
                         FriendLookup friends, VanishStatus vanish, SpawnArea spawn) {
        this.services = services;
        this.tags = tags;
        this.spawn = spawn;
        this.settings = services.configs().register("features/combat.yml", CombatSettings::parse, problems);
        services.lang().register(CombatMessages.class);
        services.settings().register(DEATH_MESSAGES);
        var perms = services.permissions();
        perms.declare(CombatCommands.USE, "See whether you are in combat with /combat", true);
        perms.declare(CombatCommands.ADMIN, "Inspect, tag and untag players and read the kill log with /combat", false);
        perms.declare(CombatTagger.BYPASS, "Never be put in combat", PermissionDefault.FALSE);

        HitLog<ItemStack> hits = new HitLog<>();
        TagTicker ticker = new TagTicker();
        Participants participants = new Participants(vanish);
        this.killLog = new KillLog(services.database());
        CombatTagger tagger = new CombatTagger(this.settings, tags, hits, ticker, services.scheduler(), services.messenger());
        KillTracker kills = new KillTracker(this.settings, hits, this.pairs, this.killLog, stats, teams, friends, services.directory(),
            participants, services.scheduler(), services.plugin().getLogger());
        DeathMessages deathMessages = new DeathMessages(services.lang(), services.settings(), DEATH_MESSAGES, participants);
        CombatLogs logs = new CombatLogs(this.settings, tags, tagger, deathMessages, services.audit(), services.directory(), participants);
        this.listener = new CombatListener(this.settings, tags, tagger, kills, deathMessages, logs, spawn, services.directory(),
            participants, services.messenger());
        this.timer = new CombatTimer(this.settings, tags, ticker, hits, this.pairs, services.messenger());
        this.commands = new CombatCommands(services, tags, tagger, this.killLog);
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public void enable() throws Exception {
        long since = System.currentTimeMillis() - CombatSettings.MAX_PAIR_COOLDOWN.toMillis();
        for (KillLog.CountedPair pair : this.killLog.countedSince(since).get()) {
            this.pairs.record(pair.killer(), pair.victim(), pair.at());
        }
        Bukkit.getPluginManager().registerEvents(this.listener, this.services.plugin());
        if (this.spawn != SpawnArea.NONE) {
            AsyncTeleportGuard.install(this.services.plugin(), this.listener, this.listener::refuseTeleportIntoSpawn);
        }
        this.timerTask = this.services.scheduler().asyncTimer(this.timer::tick, Duration.ofSeconds(1), Duration.ofSeconds(1));
        registerPlaceholders();
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("combat_tagged", "Whether you are in combat (true or false)",
            p -> Boolean.toString(this.tags.tagged(p.getUniqueId())));
        placeholders.register("combat_time", "Whole seconds of combat left (0 when not in combat)",
            p -> Long.toString(TagTicker.secondsLeft(this.tags.remaining(p.getUniqueId()).toMillis(), 0)));
    }

    @Override
    public void disable() {
        this.timerTask.cancel();
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "anti-farm rules", () -> {
            AntiFarm.Rules rules = new AntiFarm.Rules(true, true, true, Duration.ofMinutes(10));
            long now = 1_000_000_000L;
            OptionalLong never = OptionalLong.empty();
            if (!AntiFarm.decide(rules, AntiFarm.Facts.FAIR, now).counted()) {
                return "a fair kill was not counted";
            }
            if (AntiFarm.decide(rules, new AntiFarm.Facts(true, false, false, never), now).reason() != AntiFarm.Reason.SAME_TEAM
                || AntiFarm.decide(rules, new AntiFarm.Facts(false, true, false, never), now).reason() != AntiFarm.Reason.FRIENDS
                || AntiFarm.decide(rules, new AntiFarm.Facts(false, false, true, never), now).reason() != AntiFarm.Reason.SAME_IP
                || AntiFarm.decide(rules, new AntiFarm.Facts(false, false, false, OptionalLong.of(now - 60_000)), now).reason()
                    != AntiFarm.Reason.REPEATED_PAIR) {
                return "a farmed kill was counted";
            }
            return null;
        });
        test.check(id(), "combat timer is running", () -> {
            long age = System.currentTimeMillis() - this.timer.lastTick();
            return age <= 5_000 ? null : "the combat timer last ran " + age + " ms ago";
        });
        test.check(id(), "only online players are in combat", () -> {
            long now = System.currentTimeMillis();
            int stale = 0;
            for (var entry : this.tags.snapshot().entrySet()) {
                if (entry.getValue().until() > now && Bukkit.getPlayer(entry.getKey()) == null) {
                    stale++;
                }
            }
            return stale == 0 ? null : stale + " offline player(s) are still tagged";
        });
        test.checkAsync(id(), "kill log is readable", () -> this.killLog.count().handle((count, error) ->
            error == null ? null : "reading the kill log failed: " + error));
    }
}
