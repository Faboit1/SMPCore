package net.siftvanilla.siftcore.feature.boosters;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.ServerBoosters;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.feature.admin.AdminFeature;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.entity.Player;

/**
 * Server-wide sell boosters: a booster raises what the server pays for items by a percent, for everyone online, for a
 * while. Boosters come from the store ({@code /sift store booster}, through {@link #boosters()}) and from staff
 * ({@code /sift booster start}); they run one after another and never stack. The running booster is announced in
 * chat (players filter the announcements with {@code booster-announcements}), shown on a boss bar (players can hide it
 * with the {@code booster-bar} switch), listed by {@code /booster} and offered as placeholders. Selling reads it through {@link ServerBoosters} and adds it to every sale price; the shop
 * prices itself against the largest booster allowed.
 */
public final class BoostersFeature implements Feature {

    /** The boss bar switch in /settings. */
    static final Toggle BAR = BoosterNews.BAR;
    /** The remaining time of the running booster is stored every this many seconds (and at shutdown). */
    private static final int STORE_EVERY = 60;

    private final Services services;
    private final Logger logger;
    private final Setting<BoostersSettings> settings;
    private final BoosterService service;
    private final BoosterBar bar;
    private final BoosterAnnouncer announcer;
    private final BoosterCommands commands;
    private final Object timerLock = new Object();
    private Task timer = Task.NONE;
    /** Seconds since the remaining time was last stored; global thread only. */
    private int sinceStored;

    /**
     * @param display the settings group the boss bar switch joins (what is shown on the player's screen)
     */
    public BoostersFeature(Services services, List<ConfigProblem> problems, AdminFeature admin, SettingCategory display) {
        this.services = services;
        this.logger = services.plugin().getLogger();
        AtomicReference<BoostersSettings> latest = new AtomicReference<>();
        this.settings = services.configs().register("features/boosters.yml", reader -> {
            BoostersSettings parsed = BoostersSettings.parse(reader);
            latest.set(parsed);
            return parsed;
        }, problems);
        services.lang().register(BoostersMessages.class);
        services.permissions().declare(BoosterCommands.COMMAND, "See the server sell booster and what comes next with /booster", true);
        services.permissions().declare(BoosterCommands.ADMIN, "Start, stop and list sell boosters with /sift booster", false);
        this.service = new BoosterService(services.ledger(), services.database(), this.settings::get, latest, this.logger,
            System::currentTimeMillis, System::nanoTime);
        this.bar = new BoosterBar(services.statusBars(), services.settings(), BAR);
        BoosterNews.register(services.settings(), display, this.settings::get, (player, before, now) -> this.bar.update(player));
        this.announcer = new BoosterAnnouncer(services.messenger(), services.directory(), services.settings(), this.settings::get,
            this.service);
        this.commands = new BoosterCommands(services, this.settings, this.service, this.announcer, BAR, this.bar::update);
        admin.addPart(this.commands.part());
    }

    @Override
    public String id() {
        return "boosters";
    }

    /** The boosters as selling and store delivery see them. The same object for the whole run. */
    public ServerBoosters boosters() {
        return this.service;
    }

    @Override
    public void enable() throws Exception {
        this.service.load();
        this.announcer.prime(this.service.view());
        BoosterService.View view = this.service.view();
        if (view.active() != null) {
            this.logger.info("Resumed the +" + this.service.paid(view.active()) + "% sell booster #" + view.active().id() + " with "
                + Durations.format(view.active().left()) + " left" + (view.waiting().isEmpty() ? "." : "; " + view.waiting().size()
                + " more waiting."));
        }
        placeholders();
        this.services.hub().register(new HubEntry("booster", 27, BoostersMessages.HUB_LABEL, BoostersMessages.HUB_DESCRIPTION,
            BoosterCommands.COMMAND, player -> this.commands.open(player, submission -> openMenu(submission.player()))));
        synchronized (this.timerLock) {
            this.timer = this.services.scheduler().globalTimer(this::tick, 20, 20);
        }
    }

    private void openMenu(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(player);
        } else {
            this.services.dialogs().close(player);
        }
    }

    /** Every second on the global thread: time passes, changes are announced, the bar follows, time left is stored. */
    private void tick() {
        try {
            this.service.tick();
            this.announcer.observe();
            refreshBar();
            if (++this.sinceStored >= STORE_EVERY) {
                this.sinceStored = 0;
                this.service.storeRemaining();
            }
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "The sell booster clock failed", e);
        }
    }

    /** Shows the running booster on the status bar, or takes it off (each player's bar changes on their own thread). Global thread. */
    private void refreshBar() {
        BoostersSettings s = this.settings.get();
        BoosterService.View view = this.service.view();
        Booster active = view.active();
        if (active == null || !s.bar().enabled()) {
            this.bar.refresh(null);
            return;
        }
        Lang lang = this.services.lang();
        Duration left = this.service.left();
        Arg percent = Arg.number("percent", this.service.percent());
        Arg time = Arg.time("time", left);
        Component title = active.owner() == null ? lang.get(BoostersMessages.BAR_SERVER, percent, time)
            : lang.get(BoostersMessages.BAR, percent, time, Arg.text("name", this.announcer.name(active.owner())));
        float progress = (float) Math.clamp((double) left.toMillis() / Math.max(1, active.seconds() * 1000L), 0.0, 1.0);
        this.bar.refresh(new BoosterBar.Frame(title, progress, s.bar().color(), s.bar().overlay()));
    }

    private void placeholders() {
        Placeholders placeholders = this.services.placeholders();
        placeholders.register("booster_active", "Whether a sell booster runs right now (true or false)",
            player -> Boolean.toString(this.service.view().active() != null));
        placeholders.register("booster_percent", "How much the running sell booster raises sell prices, like 10 (0 when none runs)",
            player -> Integer.toString(this.service.percent()));
        placeholders.register("booster_time_left", "Time the running sell booster has left, like 29m 41s (empty when none runs)",
            player -> this.service.view().active() == null ? "" : Durations.format(this.service.left()));
        placeholders.register("booster_by", "Who the running sell booster is from (empty when none runs)", player -> {
            Booster active = this.service.view().active();
            if (active == null) {
                return "";
            }
            return active.owner() == null ? this.services.lang().plain(BoostersMessages.SERVER) : this.announcer.name(active.owner());
        });
        placeholders.register("booster_queue", "How many sell boosters wait for the running one to end",
            player -> Integer.toString(this.service.view().waiting().size()));
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void disable() {
        synchronized (this.timerLock) {
            this.timer.cancel();
            this.timer = Task.NONE;
        }
        try {
            // The time up to now counts; then it is stored, so a restart resumes exactly here.
            this.service.storeRemaining();
            this.services.database().flush();
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Storing the sell booster's remaining time at shutdown failed", e);
        }
        // The status bars (the booster's line included) are taken off every player by the core at shutdown.
    }

    // ------------------------------------------------------------------ self-test

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "boosters run back to back", BoostersFeature::checkLine);
        test.check(id(), "the running booster stays within max-percent", () -> {
            int percent = this.service.percent();
            int max = this.service.maxPercent();
            return percent >= 0 && percent <= max ? null : "a booster raises prices by " + percent + "%, more than the " + max + "% allowed";
        });
        test.check(id(), "boosters are loaded", () -> this.service.available() ? null : "the boosters table was not read at startup");
        test.check(id(), "the store's booster packages arrive and pay in full", () -> this.settings.get().packagesProblem());
        test.check(id(), "every booster change is stored", () -> this.service.settled() ? null
            : "a booster change is still waiting for its database commit (announcements wait for it)");
        test.check(id(), "the booster bar switch is in /settings", () -> this.services.settings().toggle(BAR.id()) != null ? null
            : "the booster-bar switch is not registered");
        test.check(id(), "booster announcements follow each player's filter", () -> {
            if (this.services.settings().category(BoosterNews.ANNOUNCEMENTS) == null) {
                return "the booster-announcements setting is not registered";
            }
            boolean right = BoosterNews.shows(BoosterNews.Filter.STARTS, BoosterNews.Kind.STARTED, false)
                && !BoosterNews.shows(BoosterNews.Filter.STARTS, BoosterNews.Kind.ENDED, false)
                && !BoosterNews.shows(BoosterNews.Filter.OFF, BoosterNews.Kind.STARTED, false)
                && BoosterNews.shows(BoosterNews.Filter.OFF, BoosterNews.Kind.QUEUED, true);
            return right ? null : "the announcement filter decides wrongly";
        });
    }

    /** Two boosters run one after the other with no time lost or gained, and never add up. */
    private static String checkLine() {
        BoosterQueue queue = new BoosterQueue();
        long now = 1_000_000L;
        queue.add(Booster.queued(1, ServerBoosters.SELL, 10, Duration.ofMinutes(1), null, Booster.Source.STAFF, null, null, "test", now), now);
        queue.add(Booster.queued(2, ServerBoosters.SELL, 20, Duration.ofMinutes(1), null, Booster.Source.STAFF, null, null, "test", now), now);
        if (queue.percent() != 10) {
            return "two boosters raise prices by " + queue.percent() + "% instead of the first one's 10%";
        }
        queue.advance(90_000, now + 90_000);
        Optional<Booster> first = queue.finished(1);
        Booster second = queue.active();
        if (first.isEmpty() || second == null || second.id() != 2 || second.remaining() != 30_000 || queue.percent() != 20) {
            return "after 90s of two 1m boosters the second should run with 30s left, got " + second;
        }
        queue.advance(30_000, now + 120_000);
        return queue.active() == null && queue.percent() == 0 ? null : "the line did not empty after both boosters ran out";
    }
}
