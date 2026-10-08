package net.siftvanilla.siftcore.feature.tpa;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.api.event.TeleportRequestEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Kind;
import net.siftvanilla.siftcore.feature.tpa.TpaRequests.Request;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Teleport requests: sending (with the clickable answer in chat), accepting, denying, cancelling, expiry and the
 * per-player switches (requests on or off, friends without asking). The player who moves gets the warmup; the
 * destination is wherever the other player stands when the warmup ends.
 * <p>
 * Other features it consults: vanished staff can't be found (vanish), a player who ignores the sender never gets
 * the request (ignore lists), the sender is told when the target is AFK, and friends skip the request when the
 * target allows it (friends).
 */
final class TpaService {

    static final String BYPASS = "siftcore.tpa.bypass";
    private static final String COOLDOWN_KEY = "tpa:request";

    /** What the service asks other features. Every lookup is thread-safe and answers from memory. */
    record Links(VanishStatus vanish, AfkStatus afk, FriendLookup friends, IgnoreLookup ignores) {
    }

    private final Services services;
    private final Setting<TpaSettings> settings;
    private final TpaRequests requests;
    private final Toggle toggle;
    private final Toggle friendsToggle;
    private final Links links;

    /**
     * @param toggle        the "accept teleport requests" switch
     * @param friendsToggle the "friends come without asking" switch, or null when no friends system is installed
     */
    TpaService(Services services, Setting<TpaSettings> settings, TpaRequests requests, Toggle toggle, Toggle friendsToggle,
               Links links) {
        this.services = services;
        this.settings = settings;
        this.requests = requests;
        this.toggle = toggle;
        this.friendsToggle = friendsToggle;
        this.links = links;
    }

    TpaRequests requests() {
        return this.requests;
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private String name(UUID player) {
        return this.services.directory().name(player);
    }

    /** Whether {@code viewer} may treat {@code target} as online (vanished staff stay hidden). */
    boolean visible(Player viewer, Player target) {
        return viewer.canSee(target) && (!this.links.vanish().vanished(target.getUniqueId()) || viewer.hasPermission(BYPASS));
    }

    /** Whether {@code target} lets {@code sender} come without a request: they are friends and the target allows it. */
    private boolean skipsRequest(Kind kind, UUID target, UUID sender) {
        return this.friendsToggle != null && TpaGate.friendSkips(kind, this.links.friends().friends(target, sender),
            this.services.settings().enabled(target, this.friendsToggle));
    }

    /** Live requests waiting for the player (placeholder). */
    int pending(UUID player) {
        return this.requests.incoming(player).size();
    }

    // ------------------------------------------------------------------ sending

    /** /tpa and /tpahere. Runs on the sender's thread. */
    void request(Player sender, Player target, Kind kind) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();
        boolean self = sender.equals(target);
        TpaGate.Verdict verdict = TpaGate.check(self, !self && target.isOnline() && visible(sender, target),
            sender.hasPermission(BYPASS), kind, this.links.ignores().ignores(targetId, senderId),
            this.services.settings().enabled(targetId, this.toggle));
        Arg targetName = Arg.text("name", target.getName());
        switch (verdict) {
            case SELF -> messenger().send(sender, CoreMessages.NOT_YOURSELF);
            case NOT_ONLINE -> messenger().send(sender, CoreMessages.PLAYER_NOT_ONLINE, targetName);
            case STAFF_INSTANT -> {
                messenger().send(sender, TpaMessages.INSTANT, targetName);
                this.services.teleports().teleport(sender, "tpa-staff", Duration.ZERO, () -> locationOf(sender, target), null);
            }
            case IGNORED -> messenger().send(sender, TpaMessages.BLOCKED, targetName);
            case REQUESTS_OFF -> messenger().send(sender, TpaMessages.TARGET_DISABLED, targetName);
            case ALLOWED -> send(sender, target, kind);
        }
    }

    /** Sends a request that passed the gate: cooldown, the public event, then the request (or a friend's direct visit). */
    private void send(Player sender, Player target, Kind kind) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();
        TpaSettings s = this.settings.get();
        if (!this.services.commands().cooldown(sender, COOLDOWN_KEY, s.requestCooldown())) {
            return;
        }
        if (!new TeleportRequestEvent(senderId, targetId, kind == Kind.TO_SENDER).callEvent()) {
            messenger().send(sender, TpaMessages.BLOCKED, Arg.text("name", target.getName()));
            return;
        }
        if (skipsRequest(kind, targetId, senderId)) {
            comeAsFriend(sender, target);
            return;
        }
        Request request = this.requests.add(senderId, targetId, kind, s.expireAfter()).request();
        boolean afk = this.links.afk().afk(targetId);
        MessageKey sent = kind == Kind.TO_TARGET
            ? (afk ? TpaMessages.SENT_AFK : TpaMessages.SENT)
            : (afk ? TpaMessages.SENT_HERE_AFK : TpaMessages.SENT_HERE);
        messenger().send(sender, sent, Arg.text("name", target.getName()), Arg.time("time", s.expireAfter()));
        messenger().send(target, kind == Kind.TO_TARGET ? TpaMessages.INCOMING : TpaMessages.INCOMING_HERE,
            Arg.text("name", sender.getName()), Arg.component("answer", answerLink(target, request, sender.getName())));
    }

    /** A friend the target lets in without asking: no request, the sender's warmup starts at once. Sender's thread. */
    private void comeAsFriend(Player sender, Player target) {
        messenger().send(sender, TpaMessages.FRIEND_SENDER, Arg.text("name", target.getName()));
        messenger().send(target, TpaMessages.FRIEND_TARGET, Arg.text("name", sender.getName()));
        this.services.teleports().teleport(sender, "tpa", this.settings.get().warmup(), () -> locationOf(sender, target), ok -> {
            if (!ok && target.isOnline()) {
                messenger().send(target, TpaMessages.NOT_MOVED, Arg.text("name", sender.getName()));
            }
        });
    }

    /** "Click to answer": opens the accept/deny dialog right from chat. */
    private Component answerLink(Player target, Request request, String senderName) {
        Lang lang = this.services.lang();
        return lang.get(TpaMessages.ANSWER_LINK)
            .clickEvent(ClickEvent.showDialog(this.services.dialogs().inline(target, answerView(request, senderName))))
            .hoverEvent(HoverEvent.showText(lang.get(TpaMessages.ANSWER_HOVER, Arg.text("name", senderName))));
    }

    private View answerView(Request request, String senderName) {
        Lang lang = this.services.lang();
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(request.kind() == Kind.TO_TARGET ? TpaMessages.ANSWER_BODY : TpaMessages.ANSWER_BODY_HERE,
            Arg.text("name", senderName)));
        lines.add(lang.get(TpaMessages.ANSWER_EXPIRES, Arg.time("time", this.settings.get().expireAfter())));
        return this.services.templates().confirm(lang.get(TpaMessages.ANSWER_TITLE), lines, lang.get(TpaMessages.ACCEPT),
            lang.get(TpaMessages.DENY),
            yes -> accept(yes.player(), request.sender(), request.id()),
            no -> deny(no.player(), request.sender(), request.id()));
    }

    // ------------------------------------------------------------------ answering

    /** Accepts one request ({@code id} -1 = whatever request that sender has pending). Target's thread. */
    void accept(Player target, UUID sender, long id) {
        Optional<Request> taken = this.requests.take(target.getUniqueId(), sender, id);
        String senderName = name(sender);
        if (taken.isEmpty()) {
            messenger().send(target, TpaMessages.NO_REQUEST_FROM, Arg.text("name", senderName));
            return;
        }
        Request request = taken.get();
        Player senderPlayer = Bukkit.getPlayer(sender);
        if (senderPlayer == null) {
            messenger().send(target, TpaMessages.OTHER_LEFT, Arg.text("name", senderName));
            return;
        }
        messenger().send(target, TpaMessages.ACCEPTED, Arg.text("name", senderPlayer.getName()));
        messenger().send(senderPlayer, TpaMessages.ACCEPTED_BY, Arg.text("name", target.getName()));
        Player mover = request.kind() == Kind.TO_TARGET ? senderPlayer : target;
        Player other = mover == target ? senderPlayer : target;
        Runnable start = () -> this.services.teleports().teleport(mover, "tpa", this.settings.get().warmup(),
            () -> locationOf(mover, other), ok -> {
                if (!ok && other.isOnline()) {
                    messenger().send(other, TpaMessages.NOT_MOVED, Arg.text("name", mover.getName()));
                }
            });
        if (this.services.scheduler().owns(mover)) {
            start.run();
        } else {
            this.services.scheduler().entity(mover, start, null);
        }
    }

    /** Where the mover goes: the other player's position when the warmup ends, read on their thread. */
    private CompletableFuture<Location> locationOf(Player mover, Player other) {
        if (!other.isOnline()) {
            messenger().send(mover, TpaMessages.OTHER_LEFT, Arg.text("name", other.getName()));
            return CompletableFuture.completedFuture(null);
        }
        return this.services.scheduler().supplyOnEntity(other, other::getLocation).handle((location, error) -> {
            if (error != null || location == null) {
                messenger().send(mover, TpaMessages.OTHER_LEFT, Arg.text("name", other.getName()));
                return null;
            }
            return location;
        });
    }

    /** Denies one request. Target's thread. */
    void deny(Player target, UUID sender, long id) {
        Optional<Request> taken = this.requests.take(target.getUniqueId(), sender, id);
        String senderName = name(sender);
        if (taken.isEmpty()) {
            messenger().send(target, TpaMessages.NO_REQUEST_FROM, Arg.text("name", senderName));
            return;
        }
        messenger().send(target, TpaMessages.DENIED, Arg.text("name", senderName));
        Player senderPlayer = Bukkit.getPlayer(sender);
        if (senderPlayer != null) {
            messenger().send(senderPlayer, TpaMessages.DENIED_BY, Arg.text("name", target.getName()));
        }
    }

    private Optional<Request> incomingFrom(Player target, String senderName) {
        for (Request request : this.requests.incoming(target.getUniqueId())) {
            if (name(request.sender()).equalsIgnoreCase(senderName)) {
                return Optional.of(request);
            }
        }
        return Optional.empty();
    }

    /** /tpaccept [player]: the only request, the named one, or a choice when there are several. */
    void acceptCommand(Player target, String senderName) {
        answerCommand(target, senderName, true);
    }

    /** /tpdeny [player]. */
    void denyCommand(Player target, String senderName) {
        answerCommand(target, senderName, false);
    }

    private void answerCommand(Player target, String senderName, boolean accept) {
        if (senderName != null) {
            Optional<Request> request = incomingFrom(target, senderName);
            if (request.isEmpty()) {
                messenger().send(target, TpaMessages.NO_REQUEST_FROM, Arg.text("name", senderName));
            } else if (accept) {
                accept(target, request.get().sender(), request.get().id());
            } else {
                deny(target, request.get().sender(), request.get().id());
            }
            return;
        }
        List<Request> incoming = this.requests.incoming(target.getUniqueId());
        if (incoming.isEmpty()) {
            messenger().send(target, TpaMessages.NO_REQUESTS);
        } else if (incoming.size() == 1) {
            Request only = incoming.getFirst();
            if (accept) {
                accept(target, only.sender(), only.id());
            } else {
                deny(target, only.sender(), only.id());
            }
        } else {
            openChoice(target, incoming, accept);
        }
    }

    private void openChoice(Player target, List<Request> incoming, boolean accept) {
        Lang lang = this.services.lang();
        List<Button> buttons = new ArrayList<>();
        for (Request request : incoming) {
            Component tooltip = lang.get(request.kind() == Kind.TO_TARGET ? TpaMessages.CHOICE_TOOLTIP : TpaMessages.CHOICE_TOOLTIP_HERE);
            buttons.add(Button.of(Component.text(name(request.sender())), tooltip, s -> {
                if (accept) {
                    accept(s.player(), request.sender(), request.id());
                } else {
                    deny(s.player(), request.sender(), request.id());
                }
            }).width(150));
        }
        if (!accept) {
            buttons.add(Button.of(lang.get(TpaMessages.DENY_ALL), s -> {
                for (Request request : this.requests.incoming(s.player().getUniqueId())) {
                    deny(s.player(), request.sender(), request.id());
                }
            }).width(150));
        }
        this.services.dialogs().show(target, this.services.templates().list(lang.get(TpaMessages.CHOICE_TITLE),
            lang.lines(accept ? TpaMessages.CHOICE_ACCEPT_BODY : TpaMessages.CHOICE_DENY_BODY), buttons, 2, null));
    }

    /** /tpacancel [player]: cancels the named outgoing request, or all of them. Sender's thread. */
    void cancelCommand(Player sender, String targetName) {
        List<Request> cancelled = new ArrayList<>();
        if (targetName == null) {
            cancelled.addAll(this.requests.cancelAll(sender.getUniqueId()));
            if (cancelled.isEmpty()) {
                messenger().send(sender, TpaMessages.NO_OUTGOING);
                return;
            }
        } else {
            for (Request request : this.requests.outgoing(sender.getUniqueId())) {
                if (name(request.target()).equalsIgnoreCase(targetName)) {
                    this.requests.take(request.target(), sender.getUniqueId(), request.id()).ifPresent(cancelled::add);
                }
            }
            if (cancelled.isEmpty()) {
                messenger().send(sender, TpaMessages.NO_OUTGOING_TO, Arg.text("name", targetName));
                return;
            }
        }
        if (cancelled.size() == 1) {
            messenger().send(sender, TpaMessages.CANCELLED, Arg.text("name", name(cancelled.getFirst().target())));
        } else {
            messenger().send(sender, TpaMessages.CANCELLED_ALL, Arg.number("count", cancelled.size()));
        }
        for (Request request : cancelled) {
            Player target = Bukkit.getPlayer(request.target());
            if (target != null) {
                messenger().send(target, TpaMessages.CANCELLED_BY, Arg.text("name", sender.getName()));
            }
        }
    }

    // ------------------------------------------------------------------ expiry, quitting, the switches

    /** Removes expired requests and tells their senders. Any thread. */
    void expire() {
        for (Request request : this.requests.expire()) {
            Player sender = Bukkit.getPlayer(request.sender());
            if (sender != null) {
                messenger().send(sender, TpaMessages.EXPIRED, Arg.text("name", name(request.target())));
            }
        }
    }

    void forget(UUID player) {
        this.requests.removeAll(player);
    }

    /** /tpatoggle: turns incoming requests off (declined automatically) or back on. */
    void toggle(Player player) {
        boolean on = !this.services.settings().enabled(player.getUniqueId(), this.toggle);
        this.services.settings().set(player.getUniqueId(), this.toggle, on);
        messenger().send(player, on ? TpaMessages.TOGGLED_ON : TpaMessages.TOGGLED_OFF);
    }

    /** /tpatoggle friends: lets friends come without a request, or makes them ask again. */
    void toggleFriends(Player player) {
        if (this.friendsToggle == null) {
            messenger().send(player, TpaMessages.NO_FRIENDS);
            return;
        }
        boolean on = !this.services.settings().enabled(player.getUniqueId(), this.friendsToggle);
        this.services.settings().set(player.getUniqueId(), this.friendsToggle, on);
        messenger().send(player, on ? TpaMessages.FRIENDS_ON : TpaMessages.FRIENDS_OFF);
    }

    // ------------------------------------------------------------------ hub form

    /** The request form from the main menu: a player name and which way to teleport. */
    void openForm(Player player, Button.Handler back) {
        Lang lang = this.services.lang();
        List<Input.Option> options = List.of(new Input.Option("to", lang.get(TpaMessages.FORM_TO_THEM)),
            new Input.Option("here", lang.get(TpaMessages.FORM_HERE)));
        List<Component> lines = new ArrayList<>(lang.lines(TpaMessages.FORM_BODY));
        int waiting = pending(player.getUniqueId());
        if (waiting > 0) {
            lines.add(lang.get(TpaMessages.FORM_WAITING, Arg.number("count", waiting)));
        }
        this.services.dialogs().show(player, this.services.templates().form(lang.get(TpaMessages.FORM_TITLE), lines,
            List.of(Templates.text("player", lang.get(TpaMessages.FORM_PLAYER), "", 16),
                Templates.choice("direction", lang.get(TpaMessages.FORM_DIRECTION), options, "to")),
            lang.get(TpaMessages.FORM_SUBMIT),
            submission -> {
                String typed = submission.values().text("player");
                Player target = Bukkit.getPlayerExact(typed);
                if (target == null || !visible(submission.player(), target)) {
                    submission.error(lang.get(CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", typed)));
                    return;
                }
                if (target.equals(submission.player())) {
                    submission.error(lang.get(CoreMessages.NOT_YOURSELF));
                    return;
                }
                submission.close();
                request(submission.player(), target, "here".equals(submission.values().choice("direction")) ? Kind.TO_SENDER : Kind.TO_TARGET);
            },
            back));
    }
}
