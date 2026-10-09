package net.siftvanilla.siftcore.feature.boosters;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.event.SellBoosterEvent;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells everyone what happened to the boosters: compares the line with how it looked a second ago ({@link LineWatch})
 * and announces a booster that started, one that joined the line and one that ended, and fires
 * {@link SellBoosterEvent}. Working from the published line (not from each change) means a booster is announced when
 * players can see it in the prices, whichever path changed it (a store delivery, staff, a refund or the clock); a
 * change is only announced once its transaction is stored, so one that was taken back is never mentioned. Every
 * percent shown is what sales pay ({@link BoosterService#paid(Booster)}). Each player sees the announcements their
 * {@code booster-announcements} filter lets through (always the ones about their own booster); the console sees all.
 * Global thread only.
 */
final class BoosterAnnouncer {

    private final Messenger messenger;
    private final Lang lang;
    private final PlayerDirectory directory;
    private final PlayerSettings prefs;
    private final Supplier<BoostersSettings> settings;
    private final BoosterService service;
    private final LineWatch watch = new LineWatch();

    BoosterAnnouncer(Messenger messenger, PlayerDirectory directory, PlayerSettings prefs, Supplier<BoostersSettings> settings,
                     BoosterService service) {
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.directory = directory;
        this.prefs = prefs;
        this.settings = settings;
        this.service = service;
    }

    /** Takes the line as loaded at startup as already announced. */
    void prime(BoosterService.View view) {
        this.watch.prime(view.active(), view.waiting());
    }

    /** Announces what changed since the last settled look. Reads the view before asking whether it is settled. */
    void observe() {
        BoosterService.View view = this.service.view();
        boolean settled = this.service.settled();
        for (LineWatch.Change change : this.watch.look(view.active(), view.waiting(), settled)) {
            switch (change) {
                case LineWatch.Ended ended -> this.service.finished(ended.id()).ifPresent(this::ended);
                case LineWatch.Started started -> started(started.booster());
                case LineWatch.Queued queued -> queued(queued.booster(), queued.position());
            }
        }
    }

    private void started(Booster booster) {
        int paid = this.service.paid(booster);
        BoostersSettings.Announce announce = this.settings.get().announce();
        if (announce.started()) {
            Arg percent = Arg.number("percent", paid);
            Arg time = Arg.text("time", words(booster.duration()));
            if (booster.owner() == null) {
                announce(BoosterNews.Kind.STARTED, null, BoostersMessages.ANNOUNCE_STARTED_SERVER, percent, time);
            } else {
                MessageKey key = booster.source() == Booster.Source.STORE ? BoostersMessages.ANNOUNCE_STARTED
                    : BoostersMessages.ANNOUNCE_STARTED_STAFF;
                announce(BoosterNews.Kind.STARTED, booster.owner(), key, Arg.text("name", name(booster.owner())), percent, time);
            }
            if (booster.reason() != null && !booster.reason().isBlank()) {
                announce(BoosterNews.Kind.STARTED, booster.owner(), BoostersMessages.ANNOUNCE_REASON, Arg.text("reason", booster.reason()));
            }
        }
        new SellBoosterEvent(SellBoosterEvent.Phase.STARTED, booster.id(), paid, booster.duration(), booster.left(),
            booster.owner(), booster.ref(), false).callEvent();
    }

    private void queued(Booster booster, int position) {
        if (!this.settings.get().announce().queued()) {
            return;
        }
        Arg percent = Arg.number("percent", this.service.paid(booster));
        Arg time = Arg.text("time", words(booster.duration()));
        Arg place = Arg.number("position", position);
        if (booster.owner() != null && booster.source() == Booster.Source.STORE) {
            announce(BoosterNews.Kind.QUEUED, booster.owner(), BoostersMessages.ANNOUNCE_QUEUED, Arg.text("name", name(booster.owner())),
                percent, time, place);
        } else {
            announce(BoosterNews.Kind.QUEUED, booster.owner(), BoostersMessages.ANNOUNCE_QUEUED_OTHER, percent, time, place);
        }
    }

    private void ended(Booster booster) {
        int paid = this.service.paid(booster);
        boolean early = booster.state() != Booster.State.ENDED;
        if (this.settings.get().announce().ended()) {
            announce(BoosterNews.Kind.ENDED, booster.owner(), early ? BoostersMessages.ANNOUNCE_ENDED_EARLY : BoostersMessages.ANNOUNCE_ENDED,
                Arg.number("percent", paid));
        }
        new SellBoosterEvent(SellBoosterEvent.Phase.ENDED, booster.id(), paid, booster.duration(), booster.left(),
            booster.owner(), booster.ref(), early).callEvent();
    }

    /**
     * Sends an announcement to every online player whose {@code booster-announcements} filter shows it (the owner of the
     * booster always sees it), with its sound, and to the console.
     */
    private void announce(BoosterNews.Kind kind, UUID owner, MessageKey key, Arg... args) {
        Component text = this.lang.get(key, args);
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (BoosterNews.shows(this.prefs.get(id, BoosterNews.ANNOUNCEMENTS), kind, id.equals(owner))) {
                player.sendMessage(text);
                this.messenger.feedback(player, key.feedback());
            }
        }
        Bukkit.getConsoleSender().sendMessage(text);
    }

    /** A player's name, or "Someone" when they never joined (a purchase for an account id). */
    String name(UUID owner) {
        return this.directory.get(owner).map(PlayerDirectory.Known::name).orElseGet(() -> this.lang.plain(BoostersMessages.SOMEONE));
    }

    /** A length in words: "30 minutes", "1 hour 30 minutes". */
    String words(Duration duration) {
        return BoosterTime.words(duration, (unit, count) -> this.lang.plain(switch (unit) {
            case DAY -> count == 1 ? BoostersMessages.DAY_ONE : BoostersMessages.DAY_MANY;
            case HOUR -> count == 1 ? BoostersMessages.HOUR_ONE : BoostersMessages.HOUR_MANY;
            case MINUTE -> count == 1 ? BoostersMessages.MINUTE_ONE : BoostersMessages.MINUTE_MANY;
            case SECOND -> count == 1 ? BoostersMessages.SECOND_ONE : BoostersMessages.SECOND_MANY;
        }, Arg.number("count", count)));
    }
}
