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
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.AutoAccept;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
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
 * per-player settings (who may send requests and pull requests, whose /tpa comes without asking, the pop-up, the
 * confirmation before being pulled). The player who moves gets the warmup; the destination is wherever the other
 * player stands when the warmup ends.
 * <p>
 * Other features it consults: vanished staff can't be found (vanish), a player who ignores the sender never gets
 * the request (ignore lists), the sender is told when the target is AFK, and friends and teammates are looked up
 * through {@link Relations} for the "who can" settings and auto-accept. Combat-tagged players can't send or accept
 * requests, and nobody accepts a request while its sender is in combat, however they answer (command, chat dialog,
 * main menu form). When the warmup ends, nobody arrives at a player who got into a fight meanwhile.
 */
final class TpaService {

    static final String BYPASS = "siftcore.tpa.bypass";
    private static final String COOLDOWN_KEY = "tpa:request";

    /**
     * What the service asks other features. Every lookup is thread-safe and answers from memory. Friends and teammates
     * are not here: they are read through {@code services.relations()}, like every other "who can" setting.
     */
    record Links(VanishStatus vanish, AfkStatus afk, IgnoreLookup ignores, CombatStatus combat) {
    }

    private final Services services;
    private final Setting<TpaSettings> settings;
    private final TpaRequests requests;
    private final Links links;
    private final InventoryUse inventories;

    TpaService(Services services, Setting<TpaSettings> settings, TpaRequests requests, Links links, InventoryUse inventories) {
        this.services = services;
        this.settings = settings;
        this.requests = requests;
        this.links = links;
        this.inventories = inventories;
    }

    TpaRequests requests() {
        return this.requests;
    }

    /** Who is busy in their own inventory, for the pop-up (fed by the feature's inventory listeners). */
    InventoryUse inventories() {
        return this.inventories;
    }

    private Messenger messenger() {
        return this.services.messenger();
    }

    private PlayerSettings playerSettings() {
        return this.services.settings();
    }

    private String name(UUID player) {
        return this.services.directory().name(player);
    }

    /** Whether {@code viewer} may treat {@code target} as online (vanished staff stay hidden). */
    boolean visible(Player viewer, Player target) {
        return viewer.canSee(target) && (!this.links.vanish().vanished(target.getUniqueId()) || viewer.hasPermission(BYPASS));
    }

    /**
     * Whether {@code target} lets {@code sender} come without a request: their "Auto-accept /tpa from" (the shared
     * {@code friends-tpa}) takes the sender, the target doesn't ignore them and isn't in combat. Only a /tpa can skip.
     * Favourites are known to the friends feature only, so it answers for that choice.
     */
    private boolean skipsRequest(Kind kind, UUID target, UUID sender) {
        Relations relations = this.services.relations();
        if (kind != Kind.TO_TARGET || !relations.friendsAvailable()) {
            return false;
        }
        AutoAccept mode = playerSettings().get(target, SharedSettings.FRIENDS_TPA);
        if (mode == AutoAccept.NOBODY) {
            return false;
        }
        boolean friends = relations.areFriends(target, sender);
        boolean favourite = mode == AutoAccept.FAVOURITES && friends && relations.friends().autoAcceptTeleport(target, sender);
        boolean accepted = TpaGate.autoAccepts(mode, friends, favourite, relations.sameTeam(target, sender),
            this.links.ignores().ignores(target, sender));
        return TpaGate.skips(kind, accepted, this.links.combat().tagged(target));
    }

    /** True (after telling the player) when combat keeps them from sending or accepting a request. */
    private boolean inCombat(Player player) {
        UUID id = player.getUniqueId();
        if (!this.links.combat().tagged(id)) {
            return false;
        }
        messenger().send(player, TpaMessages.IN_COMBAT, Arg.text("time", Durations.format(this.links.combat().remaining(id))));
        return true;
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
        Relations relations = this.services.relations();
        boolean friends = !self && relations.areFriends(targetId, senderId);
        boolean sameTeam = !self && relations.sameTeam(targetId, senderId);
        boolean acceptsRequests = TpaGate.accepts(playerSettings().get(targetId, TpaFeature.REQUESTS), friends, sameTeam);
        boolean acceptsPulls = kind != Kind.TO_SENDER
            || TpaGate.accepts(playerSettings().get(targetId, TpaFeature.HERE_REQUESTS), friends, sameTeam);
        TpaGate.Verdict verdict = TpaGate.check(self, !self && target.isOnline() && visible(sender, target),
            sender.hasPermission(BYPASS), kind, this.links.ignores().ignores(targetId, senderId), acceptsRequests, acceptsPulls);
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
            case PULLS_OFF -> messenger().send(sender, TpaMessages.TARGET_DISABLED_HERE, targetName);
            case ALLOWED -> send(sender, target, kind);
        }
    }

    /** Sends a request that passed the gate: cooldown, the public event, then the request (or a friend's direct visit). */
    private void send(Player sender, Player target, Kind kind) {
        if (inCombat(sender)) {
            return;
        }
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
        messenger().send(sender, sent, Arg.text("name", target.getName()), Arg.text("time", Durations.format(s.expireAfter())));
        String senderName = sender.getName();
        messenger().send(target, kind == Kind.TO_TARGET ? TpaMessages.INCOMING : TpaMessages.INCOMING_HERE,
            Arg.text("name", senderName), Arg.component("answer", answerLink(target, request, senderName)));
        if (playerSettings().get(targetId, TpaFeature.POPUP)) {
            this.services.scheduler().entity(target, () -> popUp(target, request, senderName), null);
        }
    }

    /**
     * "Requests open a pop-up": opens the accept/deny window for a request that just arrived, unless the target is in
     * combat, AFK or busy in a window, and only while it still waits. The chat line with its "Click to answer" is there
     * either way. Target's thread.
     * <p>
     * A window the server opened (a chest, a SiftCore menu, an anvil...) is always seen. The player's own inventory
     * is opened by the client alone, so the server only sees it in use: a click in it within the last seconds
     * ({@link InventoryUse}). A player who just opened it, or who looks at another dialog, can't be detected.
     */
    private void popUp(Player target, Request request, String senderName) {
        UUID id = target.getUniqueId();
        boolean window = !InventoryUse.own(target.getOpenInventory().getType()) || this.inventories.inUse(id);
        if (target.isOnline() && TpaGate.popsUp(true, this.links.combat().tagged(id), this.links.afk().afk(id), window)
            && waiting(id, request.sender(), request.id())) {
            this.services.dialogs().show(target, answerView(request, senderName));
        }
    }

    /** A friend the target lets in without asking: no request, the sender's warmup starts at once. Sender's thread. */
    private void comeAsFriend(Player sender, Player target) {
        messenger().send(sender, TpaMessages.FRIEND_SENDER, Arg.text("name", target.getName()));
        messenger().send(target, TpaMessages.FRIEND_TARGET, Arg.text("name", sender.getName()));
        this.services.teleports().teleport(sender, "tpa", this.settings.get().warmup(), () -> meetingPoint(sender, target), ok -> {
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

    /**
     * The accept/deny window of one request (from chat, the pop-up, or the confirmation of /tpaccept on a /tpahere).
     * Both buttons finish, so the window closes as soon as one is clicked.
     */
    private View answerView(Request request, String senderName) {
        Lang lang = this.services.lang();
        Arg name = Arg.text("name", senderName);
        boolean toYou = request.kind() == Kind.TO_TARGET;
        // One line: who and which way. What each answer does, and when the request expires, are on the buttons.
        List<Component> lines = List.of(lang.get(toYou ? TpaMessages.ANSWER_BODY : TpaMessages.ANSWER_BODY_HERE, name));
        View confirm = this.services.templates().confirm(lang.get(TpaMessages.ANSWER_TITLE), lines, lang.get(TpaMessages.ACCEPT),
            lang.get(TpaMessages.DENY),
            yes -> accept(yes.player(), request.sender(), request.id()),
            no -> deny(no.player(), request.sender(), request.id()));
        Component acceptTooltip = Templates.lines(List.of(lang.get(toYou ? TpaMessages.ACCEPT_TOOLTIP : TpaMessages.ACCEPT_TOOLTIP_HERE, name),
            lang.get(TpaMessages.ANSWER_EXPIRES, Arg.text("time", Durations.format(this.settings.get().expireAfter())))));
        List<Button> closing = List.of(confirm.buttons().get(0).tooltip(acceptTooltip).closes(),
            confirm.buttons().get(1).tooltip(lang.get(TpaMessages.DENY_TOOLTIP, name)).closes());
        return new View(confirm.kind(), confirm.title(), confirm.body(), confirm.inputs(), closing, confirm.exit(), confirm.columns(),
            confirm.escapable());
    }

    // ------------------------------------------------------------------ answering

    /**
     * Accepts one request ({@code id} -1 = whatever request that sender has pending). Target's thread. Refused while
     * either player is in combat; the request then stays, to be accepted once the fight is over.
     */
    void accept(Player target, UUID sender, long id) {
        String senderName = name(sender);
        TpaGate.Fight fight = TpaGate.acceptBlocked(this.links.combat().tagged(target.getUniqueId()), this.links.combat().tagged(sender));
        if (fight == TpaGate.Fight.YOU) {
            inCombat(target);
            return;
        }
        if (fight == TpaGate.Fight.OTHER && waiting(target.getUniqueId(), sender, id)) {
            messenger().send(target, TpaMessages.OTHER_IN_COMBAT, Arg.text("name", senderName));
            return;
        }
        Optional<Request> taken = this.requests.take(target.getUniqueId(), sender, id);
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
            () -> meetingPoint(mover, other), ok -> {
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

    /** Whether {@code sender}'s request ({@code id} -1 = any) is still waiting for {@code target}. */
    private boolean waiting(UUID target, UUID sender, long id) {
        for (Request request : this.requests.incoming(target)) {
            if (request.sender().equals(sender) && (id < 0 || request.id() == id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the mover of an accepted request (or a friend's visit) goes: the other player's position when the warmup
     * ends, unless the other player is in combat by then (they may have been attacked during the warmup). The shared
     * teleports re-check only the mover's tag.
     */
    private CompletableFuture<Location> meetingPoint(Player mover, Player other) {
        return TpaGate.unlessFighting(locationOf(mover, other), () -> this.links.combat().tagged(other.getUniqueId()),
            () -> messenger().send(mover, TpaMessages.OTHER_FIGHTING, Arg.text("name", other.getName())));
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
        Request chosen;
        if (senderName != null) {
            Optional<Request> request = incomingFrom(target, senderName);
            if (request.isEmpty()) {
                messenger().send(target, TpaMessages.NO_REQUEST_FROM, Arg.text("name", senderName));
                return;
            }
            chosen = request.get();
        } else {
            List<Request> incoming = this.requests.incoming(target.getUniqueId());
            if (incoming.isEmpty()) {
                messenger().send(target, TpaMessages.NO_REQUESTS);
                return;
            }
            if (incoming.size() > 1) {
                openChoice(target, incoming, accept);
                return;
            }
            chosen = incoming.getFirst();
        }
        switch (answerOf(target, chosen, accept)) {
            case DENY -> deny(target, chosen.sender(), chosen.id());
            case ASK_FIRST -> this.services.dialogs().show(target, answerView(chosen, name(chosen.sender())));
            case ACCEPT -> accept(target, chosen.sender(), chosen.id());
        }
    }

    /**
     * What answering {@code request} with /tpaccept or /tpdeny does (typed, named, or picked in the window of several
     * requests): "Confirm before being pulled" asks once more before accepting a /tpahere that still waits.
     */
    private TpaGate.Answer answerOf(Player target, Request request, boolean accept) {
        return TpaGate.answer(accept, request.kind(), playerSettings().get(target, TpaFeature.CONFIRM_HERE),
            waiting(target.getUniqueId(), request.sender(), request.id()));
    }

    /**
     * The window of /tpaccept or /tpdeny with several requests waiting: one button per sender. Picking a /tpahere to
     * accept while "Confirm before being pulled" is on opens that request's own window (the second question), like a
     * typed /tpaccept of it would; every other pick finishes, so its button closes the window at once.
     */
    private void openChoice(Player target, List<Request> incoming, boolean accept) {
        Lang lang = this.services.lang();
        List<Button> buttons = new ArrayList<>();
        for (Request request : incoming) {
            Component tooltip = lang.get(request.kind() == Kind.TO_TARGET ? TpaMessages.CHOICE_TOOLTIP : TpaMessages.CHOICE_TOOLTIP_HERE);
            Button pick = Button.of(Component.text(name(request.sender())), tooltip, s -> {
                switch (answerOf(s.player(), request, accept)) {
                    case DENY -> deny(s.player(), request.sender(), request.id());
                    case ASK_FIRST -> s.show(answerView(request, name(request.sender())));
                    case ACCEPT -> accept(s.player(), request.sender(), request.id());
                }
            });
            // A pick that opens the next window keeps this one up until it does; a finishing pick closes at once.
            buttons.add(answerOf(target, request, accept) == TpaGate.Answer.ASK_FIRST ? pick : pick.closes());
        }
        if (!accept) {
            buttons.add(Button.of(lang.get(TpaMessages.DENY_ALL), lang.get(TpaMessages.DENY_ALL_TOOLTIP), s -> {
                for (Request request : this.requests.incoming(s.player().getUniqueId())) {
                    deny(s.player(), request.sender(), request.id());
                }
            }).closes());
        }
        // Nothing above the names: the title says whether a pick accepts or denies.
        this.services.dialogs().show(target, this.services.templates().grid(
            lang.get(accept ? TpaMessages.CHOICE_TITLE_ACCEPT : TpaMessages.CHOICE_TITLE_DENY), buttons, null));
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
            messenger().send(sender, TpaMessages.CANCELLED_ALL, Arg.text("count", Lang.number(cancelled.size())));
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
        this.inventories.forget(player);
    }

    /**
     * /tpatoggle: "Teleport requests from" goes to nobody (requests are declined), or back to everyone from nobody.
     * Any other choice (friends...) counts as on, so it goes to nobody. Says so when the server decides the setting.
     */
    void toggle(Player player) {
        boolean on = playerSettings().get(player, TpaFeature.REQUESTS) == Audience.NOBODY;
        SetResult result = playerSettings().set(player, TpaFeature.REQUESTS, on ? Audience.EVERYONE : Audience.NOBODY, Change.feature());
        report(player, result, TpaFeature.REQUESTS, on ? TpaMessages.TOGGLED_ON : TpaMessages.TOGGLED_OFF);
    }

    /**
     * /tpatoggle friends [choice]: "Auto-accept /tpa from" goes from nobody to all friends and from anything else back
     * to nobody; with a choice (nobody, favourites, all, friends-team) it is set to that.
     */
    void toggleFriends(Player player, String typed) {
        if (!this.services.relations().friendsAvailable()) {
            messenger().send(player, TpaMessages.NO_FRIENDS);
            return;
        }
        Choice<AutoAccept> setting = SharedSettings.FRIENDS_TPA;
        AutoAccept next = typed == null
            ? (playerSettings().get(player, setting) == AutoAccept.NOBODY ? AutoAccept.ALL : AutoAccept.NOBODY)
            : setting.decodeOrNull(typed);
        List<String> offered = autoAcceptOptions(player);
        Arg name = Arg.text("setting", this.services.lang().plain(setting.label()));
        boolean server = playerSettings().locked(setting) || playerSettings().hidden(setting);
        switch (TpaGate.typedChoice(server, next != null, !offered.isEmpty())) {
            case SERVER -> {
                messenger().send(player, TpaMessages.SETTING_FIXED, name);
                return;
            }
            case NOT_OFFERED -> {
                messenger().send(player, TpaMessages.SETTING_REFUSED, name);
                return;
            }
            case UNKNOWN -> {
                messenger().send(player, TpaMessages.FRIENDS_UNKNOWN, Arg.text("values", String.join(", ", offered)));
                return;
            }
            case CHANGE -> {
            }
        }
        SetResult result = playerSettings().set(player, setting, next, Change.feature());
        switch (next) {
            case NOBODY -> report(player, result, setting, TpaMessages.FRIENDS_OFF);
            case ALL -> report(player, result, setting, TpaMessages.FRIENDS_ON);
            default -> report(player, result, setting, TpaMessages.FRIENDS_SET,
                Arg.text("value", setting.display(this.services.lang(), next)));
        }
    }

    /** The "Auto-accept /tpa from" choices the player may pick now (for suggestions), as typed ids. */
    List<String> autoAcceptOptions(Player player) {
        Registry.Entry<?> entry = playerSettings().registry().entry(SharedSettings.FRIENDS_TPA.id());
        if (entry == null || !playerSettings().visible(entry, player::hasPermission)) {
            return List.of();
        }
        return optionIds(entry, player);
    }

    private <T> List<String> optionIds(Registry.Entry<T> entry, Player player) {
        List<String> ids = new ArrayList<>();
        for (Choice.Option<T> option : playerSettings().options(entry, player::hasPermission)) {
            ids.add(option.id());
        }
        return ids;
    }

    /**
     * Tells the player how a /tpatoggle went: the result line, "set by the server" when the server locked or hides the
     * setting, or that it couldn't be changed (an option not offered now, another plugin said no).
     */
    private void report(Player player, SetResult result, PlayerSetting<?> setting, MessageKey done, Arg... args) {
        if (result.succeeded()) {
            messenger().send(player, done, args);
            return;
        }
        Arg name = Arg.text("setting", this.services.lang().plain(setting.label()));
        boolean fixed = result == SetResult.LOCKED || playerSettings().hidden(setting);
        messenger().send(player, fixed ? TpaMessages.SETTING_FIXED : TpaMessages.SETTING_REFUSED, name);
    }

    // ------------------------------------------------------------------ hub form

    /** The request form from the main menu: a player name and which way to teleport. */
    void openForm(Player player, Button.Handler back) {
        Lang lang = this.services.lang();
        List<Input.Option> options = List.of(new Input.Option("to", lang.get(TpaMessages.FORM_TO_THEM)),
            new Input.Option("here", lang.get(TpaMessages.FORM_HERE)));
        // Only a short status above the inputs: requests waiting for this player.
        List<Component> lines = new ArrayList<>(1);
        int waiting = pending(player.getUniqueId());
        if (waiting > 0) {
            lines.add(lang.get(TpaMessages.FORM_WAITING, Arg.text("count", Lang.number(waiting))));
        }
        View form = this.services.templates().form(lang.get(TpaMessages.FORM_TITLE), lines,
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
            back);
        List<Button> buttons = List.of(form.buttons().get(0).tooltip(lang.get(TpaMessages.FORM_SUBMIT_TOOLTIP)), form.buttons().get(1));
        this.services.dialogs().show(player, new View(form.kind(), form.title(), form.body(), form.inputs(), buttons, form.exit(),
            form.columns(), form.escapable()));
    }
}
