package net.siftvanilla.siftcore.feature.friends;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.object.ObjectContents;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The friends dialogs: the list, the requests and one request, adding a friend, the friend profile, the player card,
 * the settings, and the small forms and confirmations around them. Every screen is a {@link View}, so Bedrock players
 * get the same screens as forms.
 * <p>
 * A screen is built on the viewer's thread, where it is decided who looks online (vanish and {@code canSee}) and which
 * buttons exist; what memory can't answer (notes, mutual friends of offline players, a stored rank label) is read off
 * the thread first and the dialog is shown when it arrives. Such a screen tells the dialog router up front that an
 * answer is on its way ({@code markShown}), so the dialog that was clicked stays on screen until the next one replaces
 * it instead of closing and popping up again; a read that fails closes it. Button handlers run on the viewer's thread
 * and never trust what the screen showed: every action goes through {@link FriendService}, which checks again. After
 * an action the screen is built again, fresh, with the reason inside it if something was refused.
 */
final class FriendViews {

    /**
     * Where the list was opened from and where it was, so every Back returns to the same place.
     *
     * @param fromMenu whether the list was opened from the main menu or the pause menu (its Back goes to the menu)
     * @param page     the list page (1-based)
     * @param filter   the name prefix of a Find, or empty
     */
    record Nav(boolean fromMenu, int page, String filter) {

        static Nav command() {
            return new Nav(false, 1, "");
        }

        static Nav menu() {
            return new Nav(true, 1, "");
        }

        Nav page(int page) {
            return new Nav(this.fromMenu, Math.max(1, page), this.filter);
        }

        Nav filter(String filter) {
            return new Nav(this.fromMenu, 1, filter == null ? "" : filter);
        }
    }

    /** Requests per page in the requests dialog (each section). */
    static final int REQUESTS_PAGE = 10;
    /** "Deny all" is offered from this many visible requests. */
    static final int DENY_ALL_FROM = 5;
    /** Mutual friend names shown before "and N more". */
    static final int MUTUAL_NAMES = 2;
    /** Longest message typed in a profile's message form. */
    static final int MESSAGE_LENGTH = 256;
    /** Longest name prefix in the Find form. */
    static final int FIND_LENGTH = 16;

    private static final int ROW_WIDTH = 150;

    private final Services services;
    private final Setting<FriendsSettings> settings;
    private final FriendService service;
    private final FriendGraph graph;
    private final FriendPrefs prefs;
    private final FriendLinks links;
    private final Presence presence;
    private final ProfileButtons buttons;
    private final SeenPrivacy seen;
    private final Lang lang;
    private final Templates templates;
    private final PlayerDirectory directory;
    private final Logger logger;
    private final ZoneId zone = ZoneId.systemDefault();

    FriendViews(Services services, Setting<FriendsSettings> settings, FriendService service, Presence presence,
                ProfileButtons buttons, SeenPrivacy seen) {
        this.services = services;
        this.settings = settings;
        this.service = service;
        this.graph = service.graph();
        this.prefs = service.prefs();
        this.links = service.links();
        this.presence = presence;
        this.buttons = buttons;
        this.seen = seen;
        this.lang = services.lang();
        this.templates = services.templates();
        this.directory = services.directory();
        this.logger = services.plugin().getLogger();
    }

    // ------------------------------------------------------------------ plumbing

    private void show(Player player, View view, Component notice) {
        this.services.dialogs().show(player, notice == null ? view : view.withError(notice, null));
    }

    /** Runs on the player's thread (now when already there). */
    private void onThread(Player player, Runnable task) {
        if (this.services.scheduler().owns(player)) {
            task.run();
        } else {
            this.services.scheduler().entity(player, task, null);
        }
    }

    /**
     * Tells the dialog router that a screen is coming for this player, so the dialog they clicked stays on screen
     * while it loads instead of being closed after the router's short grace (a database write or read can take
     * longer under load). Outside a click it changes nothing.
     */
    private void answerComing(Player player) {
        this.services.dialogs().markShown(player);
    }

    /** Closes the dialog left on screen by {@link #answerComing} when no screen will come after all. */
    private void closeWaiting(Player player) {
        if (player.isOnline()) {
            this.services.dialogs().close(player);
        }
    }

    /** After an action: builds the next screen on the player's thread, with a refusal inside it. */
    private void after(Player player, CompletableFuture<FriendService.Reply> action, Consumer<Component> next) {
        answerComing(player);
        action.whenComplete((reply, error) -> {
            if (!player.isOnline()) {
                return;
            }
            Component notice = reply == null || reply.ok() ? null : reply.message();
            onThread(player, () -> next.accept(notice));
        });
    }

    /**
     * Shows an async-built screen. The clicked dialog stays on screen meanwhile; a failed read or build closes it and
     * tells the player.
     */
    private <T> void whenRead(Player player, CompletableFuture<T> read, Consumer<T> build) {
        answerComing(player);
        read.whenComplete((value, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Reading friends data for " + player.getName() + " failed", error);
                this.services.messenger().send(player, CoreMessages.ACTION_FAILED);
                closeWaiting(player);
                return;
            }
            if (player.isOnline()) {
                try {
                    build.accept(value);
                } catch (Throwable t) {
                    this.logger.log(Level.SEVERE, "Building a friends dialog for " + player.getName() + " failed", t);
                    this.services.messenger().send(player, CoreMessages.ACTION_FAILED);
                    closeWaiting(player);
                }
            }
        });
    }

    private boolean ready(Player player) {
        if (this.graph.isLoaded(player.getUniqueId())) {
            return true;
        }
        this.services.messenger().send(player, FriendsMessages.LOADING);
        return false;
    }

    private Component ui(MessageKey key, Arg... args) {
        return this.lang.get(key, args);
    }

    private static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** The head of a player as an inline object, on its own body line (Bedrock forms drop it). */
    private static Component head(UUID player) {
        return Component.object(ObjectContents.playerHead(player));
    }

    /** The back handler of a list: to the main menu when it was opened from there, else none (Close). */
    private Button.Handler listBack(Nav nav) {
        if (!nav.fromMenu()) {
            return null;
        }
        return submission -> {
            HubEntry menu = this.services.hub().get("menu");
            if (menu != null) {
                menu.open().accept(submission.player());
            } else {
                submission.close();
            }
        };
    }

    // ------------------------------------------------------------------ status

    /** What a player looks like to the viewer: online, AFK or offline. Call on the viewer's thread. */
    ListOrder.Status status(Player viewer, UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !this.presence.visible(viewer, online)) {
            return ListOrder.Status.OFFLINE;
        }
        return this.links.afk().afk(player) ? ListOrder.Status.AFK : ListOrder.Status.ONLINE;
    }

    /**
     * The status word of a row ("online", "AFK", "seen 3d ago"); "seen" comes from the same place as /seen. An offline
     * row without a last-seen time (unknown, or kept from the viewer by {@code seen-privacy}) says "offline".
     */
    String statusWord(ListOrder.Status status, long lastSeen, long now) {
        return switch (status) {
            case ONLINE -> this.lang.plain(FriendsMessages.STATUS_ONLINE);
            case AFK -> this.lang.plain(FriendsMessages.STATUS_AFK);
            case OFFLINE -> lastSeen <= 0 ? this.lang.plain(FriendsMessages.STATUS_OFFLINE)
                : this.lang.plain(FriendsMessages.STATUS_SEEN, Arg.text("ago", TimeText.ago(lastSeen, now)));
        };
    }

    private long lastSeen(UUID player) {
        return this.directory.get(player).map(PlayerDirectory.Known::lastSeen).orElse(0L);
    }

    /**
     * The viewer's friends as list rows, in the order they picked ({@code friends-list-order}), with the last-seen
     * time of friends who keep it from them taken out. The rows are built on the viewer's thread (call it there);
     * the result may complete on the database thread (one read of offline friends' privacy).
     */
    CompletableFuture<List<ListOrder.Row>> rows(Player viewer, FriendGraph.Node node) {
        boolean favouritesOn = this.settings.get().favouritesOn();
        List<ListOrder.Row> rows = new ArrayList<>(node.friends().size());
        List<UUID> offline = new ArrayList<>();
        for (Map.Entry<UUID, FriendGraph.Edge> entry : node.friends().entrySet()) {
            UUID friend = entry.getKey();
            ListOrder.Status status = status(viewer, friend);
            if (!status.online()) {
                offline.add(friend);
            }
            rows.add(new ListOrder.Row(friend, this.service.name(friend), favouritesOn && entry.getValue().favourite(),
                status, lastSeen(friend), entry.getValue().since()));
        }
        ListOrder.Sort sort = this.prefs.listOrder(viewer.getUniqueId());
        return this.seen.hidden(viewer, offline).thenApply(hidden -> ListOrder.sort(ListOrder.hideSeen(rows, hidden), sort));
    }

    // ------------------------------------------------------------------ the list

    /** The friends list. Call on the player's thread. */
    void openList(Player player, Nav nav, Component notice) {
        FriendGraph.Node node = this.graph.loaded(player.getUniqueId());
        if (node == null) {
            this.services.messenger().send(player, FriendsMessages.LOADING);
            return;
        }
        CompletableFuture<List<ListOrder.Row>> rows = rows(player, node);
        int incoming = this.service.incoming(player.getUniqueId()).size();
        int limit = this.service.limit(player.getUniqueId());
        long now = this.service.now();
        whenRead(player, this.service.store().notes(player.getUniqueId()).thenCombine(rows, Map::entry),
            read -> show(player, listView(player, nav, read.getValue(), read.getKey(), incoming, limit, now), notice));
    }

    private View listView(Player player, Nav nav, List<ListOrder.Row> all, Map<UUID, String> notes, int incoming, int limit,
                          long now) {
        FriendsSettings s = this.settings.get();
        List<ListOrder.Row> shown = ListOrder.filter(all, nav.filter());
        int pages = ListOrder.pages(shown.size(), s.pageSize());
        int page = Math.clamp(nav.page(), 1, pages);
        Nav here = nav.page(page);
        List<Component> body = new ArrayList<>();
        body.add(ui(FriendsMessages.LIST_SUMMARY, Arg.number("online", ListOrder.online(all)), Arg.number("total", all.size()),
            Arg.number("limit", limit)));
        if (all.size() >= limit && this.service.rankCanRaise(limit)) {
            body.add(ui(FriendsMessages.LIST_FULL));
        }
        if (all.isEmpty()) {
            body.add(ui(FriendsMessages.LIST_EMPTY));
        }
        if (!nav.filter().isEmpty()) {
            body.add(ui(FriendsMessages.LIST_FILTERED, Arg.text("query", nav.filter()), Arg.number("count", shown.size())));
        }
        if (pages > 1) {
            body.add(ui(FriendsMessages.PAGE, Arg.number("page", page), Arg.number("pages", pages)));
        }
        List<Button> list = new ArrayList<>();
        for (ListOrder.Row row : ListOrder.page(shown, page, s.pageSize())) {
            Component label = ui(FriendsMessages.LIST_ROW, Arg.text("name", row.name()),
                Arg.text("status", statusWord(row.status(), row.lastSeen(), now)));
            List<Component> tooltip = new ArrayList<>();
            if (row.favourite()) {
                tooltip.add(ui(FriendsMessages.LIST_TOOLTIP_FAVOURITE));
            }
            tooltip.add(ui(FriendsMessages.LIST_TOOLTIP_SINCE, Arg.text("date", TimeText.date(row.since(), this.zone))));
            String note = notes.get(row.id());
            if (note != null && !note.isEmpty()) {
                tooltip.add(ui(FriendsMessages.LIST_TOOLTIP_NOTE, Arg.text("note", note)));
            }
            UUID friend = row.id();
            list.add(Button.of(label, lines(tooltip), submission -> openProfile(submission.player(), friend, here, null)).width(ROW_WIDTH));
        }
        list.add(Button.of(ui(FriendsMessages.LIST_ADD), submission -> openAdd(submission.player(), here, null)).width(ROW_WIDTH));
        list.add(Button.of(ui(FriendsMessages.LIST_REQUESTS, Arg.number("count", incoming)),
            submission -> openRequests(submission.player(), 1, here, null)).width(ROW_WIDTH));
        list.add(Button.of(ui(FriendsMessages.LIST_SETTINGS), submission -> openSettings(submission.player(), here, true))
            .width(ROW_WIDTH));
        if (!nav.filter().isEmpty()) {
            list.add(Button.of(ui(FriendsMessages.LIST_SHOW_ALL), submission -> openList(submission.player(), here.filter(""), null))
                .width(ROW_WIDTH));
        } else if (all.size() > s.pageSize()) {
            list.add(Button.of(ui(FriendsMessages.LIST_FIND), submission -> openFind(submission.player(), here)).width(ROW_WIDTH));
        }
        if (page > 1) {
            list.add(Button.of(ui(FriendsMessages.LIST_PREVIOUS), submission -> openList(submission.player(), here.page(page - 1), null))
                .width(ROW_WIDTH));
        }
        if (page < pages) {
            list.add(Button.of(ui(FriendsMessages.LIST_NEXT), submission -> openList(submission.player(), here.page(page + 1), null))
                .width(ROW_WIDTH));
        }
        return this.templates.list(ui(FriendsMessages.LIST_TITLE), body, list, 2, listBack(nav));
    }

    /** The Find form: a name prefix that filters the list. */
    void openFind(Player player, Nav nav) {
        show(player, this.templates.form(ui(FriendsMessages.FIND_TITLE), List.of(),
            List.of(Templates.text("query", ui(FriendsMessages.FIND_INPUT), nav.filter(), FIND_LENGTH)),
            ui(FriendsMessages.FIND_SUBMIT),
            submission -> openList(submission.player(), nav.filter(submission.values().text("query")), null),
            submission -> openList(submission.player(), nav, null)), null);
    }

    // ------------------------------------------------------------------ requests

    /** The requests dialog: incoming and sent, each paged. Call on the player's thread. */
    void openRequests(Player player, int page, Nav nav, Component notice) {
        if (!ready(player)) {
            return;
        }
        UUID self = player.getUniqueId();
        Map<UUID, Long> incoming = this.service.incoming(self);
        Map<UUID, Long> outgoing = this.service.outgoing(self);
        int pages = Math.max(ListOrder.pages(incoming.size(), REQUESTS_PAGE), ListOrder.pages(outgoing.size(), REQUESTS_PAGE));
        int current = Math.clamp(page, 1, pages);
        List<UUID> pageIncoming = ListOrder.page(new ArrayList<>(incoming.keySet()), current, REQUESTS_PAGE);
        List<UUID> pageOutgoing = ListOrder.page(new ArrayList<>(outgoing.keySet()), current, REQUESTS_PAGE);
        long now = this.service.now();
        whenRead(player, this.service.store().mutual(self, pageIncoming), mutual -> {
            List<Button> list = new ArrayList<>();
            for (UUID sender : pageIncoming) {
                String name = this.service.name(sender);
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(ui(FriendsMessages.REQUESTS_TOOLTIP_MUTUAL, Arg.number("count", mutual.getOrDefault(sender, List.of()).size())));
                this.links.teams().teamName(sender).ifPresent(team ->
                    tooltip.add(ui(FriendsMessages.REQUESTS_TOOLTIP_TEAM, Arg.text("team", team))));
                list.add(Button.of(ui(FriendsMessages.REQUESTS_INCOMING_ROW, Arg.text("name", name),
                        Arg.text("ago", TimeText.ago(incoming.get(sender), now))), lines(tooltip),
                    submission -> openRequest(submission.player(), sender, current, nav)).width(ROW_WIDTH));
            }
            for (UUID target : pageOutgoing) {
                String name = this.service.name(target);
                list.add(Button.of(ui(FriendsMessages.REQUESTS_OUTGOING_ROW, Arg.text("name", name),
                        Arg.text("ago", TimeText.ago(outgoing.get(target), now))), ui(FriendsMessages.REQUESTS_TOOLTIP_CANCEL),
                    submission -> confirmCancel(submission.player(), target, current, nav)).width(ROW_WIDTH));
            }
            if (incoming.size() >= DENY_ALL_FROM) {
                list.add(Button.of(ui(FriendsMessages.REQUESTS_DENY_ALL),
                    submission -> confirmDenyAll(submission.player(), incoming.size(), nav)).width(ROW_WIDTH));
            }
            if (current > 1) {
                list.add(Button.of(ui(FriendsMessages.REQUESTS_PREVIOUS),
                    submission -> openRequests(submission.player(), current - 1, nav, null)).width(ROW_WIDTH));
            }
            if (current < pages) {
                list.add(Button.of(ui(FriendsMessages.REQUESTS_NEXT),
                    submission -> openRequests(submission.player(), current + 1, nav, null)).width(ROW_WIDTH));
            }
            List<Component> body = new ArrayList<>();
            body.add(ui(FriendsMessages.REQUESTS_BODY, Arg.number("incoming", incoming.size()), Arg.number("sent", outgoing.size())));
            if (!pageOutgoing.isEmpty()) {
                // Sent rows follow the incoming ones in the same grid: say which is which.
                body.add(ui(FriendsMessages.REQUESTS_SENT_HINT));
            }
            if (pages > 1) {
                body.add(ui(FriendsMessages.PAGE, Arg.number("page", current), Arg.number("pages", pages)));
            }
            show(player, this.templates.list(ui(FriendsMessages.REQUESTS_TITLE), body, list, 2,
                submission -> openList(submission.player(), nav, null)), notice);
        });
    }

    /** One incoming request: who, shared friends, team, and Accept / Deny / Deny and ignore. */
    void openRequest(Player player, UUID sender, int page, Nav nav) {
        if (!ready(player)) {
            return;
        }
        UUID self = player.getUniqueId();
        Long created = this.service.incoming(self).get(sender);
        String name = this.service.name(sender);
        if (created == null) {
            openRequests(player, page, nav, ui(FriendsMessages.REQUEST_GONE, Arg.text("name", name)));
            return;
        }
        boolean canIgnore = ProfileButtons.registered("ignore") && player.hasPermission("siftcore.command.ignore");
        long now = this.service.now();
        whenRead(player, this.service.store().mutual(self, List.of(sender)), mutual -> {
            List<Component> body = new ArrayList<>();
            body.add(head(sender));
            body.add(ui(FriendsMessages.REQUEST_VIEW_WANTS, Arg.text("name", name)));
            body.add(mutualLine(mutual.getOrDefault(sender, List.of()), FriendsMessages.REQUEST_VIEW_MUTUAL,
                FriendsMessages.REQUEST_VIEW_MUTUAL_NAMES));
            this.links.teams().teamName(sender).ifPresent(team -> body.add(ui(FriendsMessages.REQUEST_VIEW_TEAM, Arg.text("team", team))));
            body.add(ui(FriendsMessages.REQUEST_VIEW_SENT, Arg.text("ago", TimeText.ago(created, now))));
            List<Button> list = new ArrayList<>();
            list.add(Button.of(ui(FriendsMessages.REQUEST_VIEW_ACCEPT), submission -> after(submission.player(),
                this.service.accept(submission.player(), sender, FriendService.Via.DIALOG),
                refused -> openRequests(submission.player(), page, nav, refused))).width(ROW_WIDTH));
            list.add(Button.of(ui(FriendsMessages.REQUEST_VIEW_DENY), submission -> after(submission.player(),
                this.service.deny(submission.player(), sender, FriendService.Via.DIALOG),
                refused -> openRequests(submission.player(), page, nav, refused))).width(ROW_WIDTH));
            if (canIgnore) {
                list.add(Button.of(ui(FriendsMessages.REQUEST_VIEW_DENY_IGNORE), submission -> {
                    Player clicker = submission.player();
                    after(clicker, this.service.deny(clicker, sender, FriendService.Via.DIALOG), refused -> {
                        this.buttons.perform(clicker, "ignore " + name);
                        openRequests(clicker, page, nav, refused);
                    });
                }).width(ROW_WIDTH));
            }
            show(player, this.templates.listWithBody(ui(FriendsMessages.REQUEST_VIEW_TITLE), textBody(body), list, 2,
                submission -> openRequests(submission.player(), page, nav, null)), null);
        });
    }

    /** "Cancel your request to Cara?": a sent row asks first, so a click meant for an incoming row costs nothing. */
    private void confirmCancel(Player player, UUID target, int page, Nav nav) {
        String name = this.service.name(target);
        show(player, this.templates.confirm(ui(FriendsMessages.REQUESTS_CANCEL_TITLE),
            List.of(ui(FriendsMessages.REQUESTS_CANCEL_BODY, Arg.text("name", name))),
            ui(FriendsMessages.REQUESTS_CANCEL_YES), this.lang.get(CoreMessages.UI_BACK),
            submission -> after(submission.player(), this.service.cancel(submission.player(), target, FriendService.Via.DIALOG),
                refused -> openRequests(submission.player(), page, nav, refused)),
            submission -> openRequests(submission.player(), page, nav, null)), null);
    }

    private void confirmDenyAll(Player player, int count, Nav nav) {
        show(player, this.templates.confirm(ui(FriendsMessages.REQUESTS_DENY_ALL_TITLE),
            this.lang.lines(FriendsMessages.REQUESTS_DENY_ALL_BODY, Arg.number("count", count)),
            ui(FriendsMessages.REQUESTS_DENY_ALL_YES), this.lang.get(CoreMessages.UI_CANCEL),
            submission -> after(submission.player(), this.service.denyAll(submission.player(), FriendService.Via.DIALOG),
                refused -> openRequests(submission.player(), 1, nav, refused)),
            submission -> openRequests(submission.player(), 1, nav, null)), null);
    }

    /** "Mutual friends: 2 (Bob, Cara)": at most two names, then "and N more". */
    private Component mutualLine(List<UUID> mutual, MessageKey countOnly, MessageKey withNames) {
        if (mutual.isEmpty()) {
            return ui(countOnly, Arg.number("count", 0));
        }
        List<String> names = new ArrayList<>();
        for (UUID friend : mutual) {
            names.add(this.service.name(friend));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return ui(withNames, Arg.number("count", mutual.size()), Arg.text("names", this.presence.plainNames(names, MUTUAL_NAMES)));
    }

    private static List<Body> textBody(List<Component> lines) {
        List<Body> body = new ArrayList<>();
        for (Component line : lines) {
            body.add(Body.text(line));
        }
        return body;
    }

    // ------------------------------------------------------------------ adding

    /**
     * A name typed or picked: sends a request, accepts theirs when they already asked, or opens their profile when
     * they are already friends. Call on the player's thread. {@code done} gets the reply of a request or accept.
     */
    void smartAdd(Player player, UUID target, Nav nav, FriendService.Via via, Consumer<FriendService.Reply> done) {
        if (via == FriendService.Via.DIALOG) {
            answerComing(player);
        }
        UUID self = player.getUniqueId();
        boolean other = !self.equals(target);
        if (other && this.graph.isLoaded(self) && this.graph.friends(self, target)) {
            openProfile(player, target, nav, null);
            return;
        }
        // The request path refuses yourself and a list that is still loading with its own messages.
        CompletableFuture<FriendService.Reply> action = other && this.service.incoming(self).containsKey(target)
            ? this.service.accept(player, target, via)
            : this.service.request(player, target, via);
        action.whenComplete((reply, error) -> done.accept(reply == null ? FriendService.Reply.SILENT_REFUSAL : reply));
    }

    /** The add dialog: "Enter a name" and up to six people you may know. Call on the player's thread. */
    void openAdd(Player player, Nav nav, Component notice) {
        if (!ready(player)) {
            return;
        }
        UUID self = player.getUniqueId();
        int max = this.settings.get().suggestions();
        Set<UUID> viewerFriends = this.graph.friendsOf(self);
        List<Suggestions.Candidate> candidates = new ArrayList<>();
        if (max > 0) {
            Set<UUID> mine = new HashSet<>(this.graph.outgoing(self, 0).keySet());
            mine.addAll(this.graph.incoming(self, 0).keySet());
            for (Player online : Bukkit.getOnlinePlayers()) {
                UUID id = online.getUniqueId();
                if (id.equals(self) || viewerFriends.contains(id) || !this.presence.visible(player, online)) {
                    continue;
                }
                FriendGraph.Node node = this.graph.loaded(id);
                if (node == null) {
                    continue;
                }
                boolean blocked = mine.contains(id) || node.outgoing().containsKey(self) || this.links.ignoredEitherWay(self, id);
                candidates.add(new Suggestions.Candidate(id, online.getName(), node.friends().keySet(),
                    this.links.teams().sameTeam(self, id), this.prefs.privacy(id), blocked));
            }
        }
        CompletableFuture<List<Suggestions.Suggestion>> ranked = new CompletableFuture<>();
        this.services.scheduler().async(() -> ranked.complete(Suggestions.rank(self, viewerFriends, candidates, max)));
        whenRead(player, ranked, suggestions -> {
            List<Button> list = new ArrayList<>();
            list.add(Button.of(ui(FriendsMessages.ADD_ENTER), submission -> openAddForm(submission.player(), nav, "", null))
                .width(ROW_WIDTH));
            for (Suggestions.Suggestion suggestion : suggestions) {
                Component label = suggestion.mutual() == 0
                    ? ui(FriendsMessages.ADD_SUGGESTION_TEAM, Arg.text("name", suggestion.name()))
                    : suggestion.mutual() == 1
                        ? ui(FriendsMessages.ADD_SUGGESTION_ONE, Arg.text("name", suggestion.name()))
                        : ui(FriendsMessages.ADD_SUGGESTION, Arg.text("name", suggestion.name()), Arg.number("count", suggestion.mutual()));
                UUID target = suggestion.id();
                list.add(Button.of(label, ui(FriendsMessages.ADD_SUGGESTION_TOOLTIP, Arg.text("name", suggestion.name())),
                    submission -> smartAdd(submission.player(), target, nav, FriendService.Via.DIALOG, reply ->
                        onThread(submission.player(), () -> openAdd(submission.player(), nav, reply.ok() ? null : reply.message()))))
                    .width(ROW_WIDTH));
            }
            List<Component> body = List.of(ui(suggestions.isEmpty() ? FriendsMessages.ADD_BODY_EMPTY : FriendsMessages.ADD_BODY));
            show(player, this.templates.list(ui(FriendsMessages.ADD_TITLE), body, list, 2,
                submission -> openList(submission.player(), nav, null)), notice);
        });
    }

    /** The name form of the add dialog. */
    void openAddForm(Player player, Nav nav, String typed, Component notice) {
        View form = this.templates.form(ui(FriendsMessages.ADD_FORM_TITLE), List.of(ui(FriendsMessages.ADD_FORM_BODY)),
            List.of(Templates.text("name", ui(FriendsMessages.ADD_FORM_INPUT), typed, PlayerNames.MAX_LENGTH)),
            ui(FriendsMessages.ADD_FORM_SUBMIT),
            submission -> {
                Player clicker = submission.player();
                String name = submission.values().text("name");
                if (!PlayerNames.valid(name)) {
                    submission.error(ui(FriendsMessages.INVALID_NAME));
                    return;
                }
                UUID target = resolve(name);
                if (target == null) {
                    submission.error(ui(CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", name)));
                    return;
                }
                smartAdd(clicker, target, nav, FriendService.Via.DIALOG, reply -> onThread(clicker, () -> {
                    if (reply.ok()) {
                        openAdd(clicker, nav, null);
                    } else {
                        openAddForm(clicker, nav, name, reply.message());
                    }
                }));
            },
            submission -> openAdd(submission.player(), nav, null));
        show(player, form, notice);
    }

    /** A typed name to a player who has joined before (exact online name first, then the directory), or null. */
    UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return this.directory.uuid(name).orElse(null);
    }

    // ------------------------------------------------------------------ profiles

    /**
     * A friend's profile, or the player card of anyone else. Call on the viewer's thread.
     *
     * @param notice a refusal to show inside it (after an action), or null
     */
    void openProfile(Player viewer, UUID target, Nav nav, Component notice) {
        if (!ready(viewer)) {
            return;
        }
        UUID self = viewer.getUniqueId();
        boolean friend = this.graph.friends(self, target);
        ListOrder.Status status = self.equals(target) ? ListOrder.Status.ONLINE : status(viewer, target);
        boolean visibleOnline = status.online();
        List<ProfileButtons.Action> actions = this.buttons.available(viewer, target, visibleOnline);
        Player online = Bukkit.getPlayer(target);
        String liveRank = online != null && visibleOnline ? this.links.ranks().label(target) : null;
        FriendGraph.Node targetNode = this.graph.loaded(target);
        boolean needFriends = friend && targetNode == null;
        Card card = friend || self.equals(target) ? Card.NONE : cardState(viewer, target, online != null && visibleOnline);
        FriendGraph.Edge edge = friend ? this.graph.edge(self, target) : null;
        Set<UUID> viewerFriends = this.graph.friendsOf(self);
        boolean favouritesOn = this.settings.get().favouritesOn();
        long now = this.service.now();
        // The last-seen time only for viewers the player shows it to (seen-privacy).
        CompletableFuture<Set<UUID>> hiddenSeen = status.online() ? CompletableFuture.completedFuture(Set.of())
            : this.seen.hidden(viewer, List.of(target));
        long lastSeen = lastSeen(target);
        whenRead(viewer, this.service.store().profile(self, target, needFriends).thenCombine(hiddenSeen, Map::entry), read -> {
            FriendStore.ProfileData data = read.getKey();
            List<Component> body = new ArrayList<>();
            body.add(head(target));
            body.add(statusLine(status, read.getValue().contains(target) ? 0 : lastSeen, now));
            if (friend && edge != null) {
                body.add(ui(FriendsMessages.PROFILE_SINCE, Arg.text("date", TimeText.date(edge.since(), this.zone))));
            }
            this.links.teams().teamName(target).ifPresent(team -> body.add(ui(FriendsMessages.PROFILE_TEAM, Arg.text("team", team))));
            String rank = liveRank != null ? liveRank : data.rankLabel();
            if (rank != null && !rank.isBlank()) {
                body.add(ui(FriendsMessages.PROFILE_RANK, Arg.text("rank", rank)));
            }
            if (friend) {
                Set<UUID> theirs = targetNode != null ? targetNode.friends().keySet() : data.friends();
                List<UUID> mutual = new ArrayList<>();
                for (UUID id : theirs) {
                    if (viewerFriends.contains(id)) {
                        mutual.add(id);
                    }
                }
                body.add(mutualLine(mutual, FriendsMessages.PROFILE_MUTUAL, FriendsMessages.PROFILE_MUTUAL_NAMES));
                if (data.note() != null && !data.note().isEmpty()) {
                    body.add(ui(FriendsMessages.PROFILE_NOTE, Arg.text("note", data.note())));
                }
            }
            String name = this.service.name(target);
            List<Button> list = new ArrayList<>();
            switch (card) {
                case ADD -> list.add(button(FriendsMessages.PROFILE_ADD_FRIEND, submission -> {
                    Player clicker = submission.player();
                    smartAdd(clicker, target, nav, FriendService.Via.DIALOG,
                        reply -> onThread(clicker, () -> openProfile(clicker, target, nav, reply.ok() ? null : reply.message())));
                }));
                case ACCEPT -> list.add(button(FriendsMessages.PROFILE_ACCEPT_REQUEST, submission -> after(submission.player(),
                    this.service.accept(submission.player(), target, FriendService.Via.DIALOG),
                    refused -> openProfile(submission.player(), target, nav, refused))));
                case CANCEL -> list.add(button(FriendsMessages.PROFILE_CANCEL_REQUEST, submission -> after(submission.player(),
                    this.service.cancel(submission.player(), target, FriendService.Via.DIALOG),
                    refused -> openProfile(submission.player(), target, nav, refused))));
                case NONE -> {
                }
            }
            for (ProfileButtons.Action action : actions) {
                list.add(actionButton(action, target, name, nav));
            }
            if (friend) {
                if (favouritesOn) {
                    boolean favourite = edge != null && edge.favourite();
                    list.add(button(favourite ? FriendsMessages.PROFILE_UNFAVOURITE : FriendsMessages.PROFILE_FAVOURITE,
                        submission -> after(submission.player(),
                            this.service.favourite(submission.player(), target, !favourite, FriendService.Via.DIALOG),
                            refused -> openProfile(submission.player(), target, nav, refused))));
                }
                list.add(button(FriendsMessages.PROFILE_EDIT_NOTE, submission -> openNoteForm(submission.player(), target, nav,
                    data.note() == null ? "" : data.note())));
                list.add(button(FriendsMessages.PROFILE_REMOVE, submission -> confirmRemove(submission.player(), target, nav)));
            }
            Button.Handler back = friend ? submission -> openList(submission.player(), nav, null) : null;
            show(viewer, this.templates.listWithBody(ui(FriendsMessages.PROFILE_TITLE, Arg.text("name", name)), textBody(body),
                list, 2, back), notice);
        });
    }

    /** The state-dependent friendship button of a player card. */
    enum Card {
        NONE,
        ADD,
        ACCEPT,
        CANCEL
    }

    /** Which friendship button a card gets. Call on the viewer's thread. */
    private Card cardState(Player viewer, UUID target, boolean onlineVisible) {
        UUID self = viewer.getUniqueId();
        if (this.service.outgoing(self).containsKey(target)) {
            return Card.CANCEL;
        }
        if (this.service.incoming(self).containsKey(target)) {
            return Card.ACCEPT;
        }
        if (onlineVisible) {
            // Privacy is a public choice: an online player's setting is shown honestly by hiding the button.
            Privacy privacy = this.prefs.privacy(target);
            boolean known = this.links.teams().sameTeam(self, target) || shareFriend(self, target);
            if (!privacy.allows(known)) {
                return Card.NONE;
            }
        }
        return Card.ADD;
    }

    private boolean shareFriend(UUID a, UUID b) {
        Set<UUID> mine = this.graph.friendsOf(a);
        for (UUID friend : this.graph.friendsOf(b)) {
            if (mine.contains(friend)) {
                return true;
            }
        }
        return false;
    }

    /** The profile's status line; offline without a last-seen time (unknown or kept from the viewer) says "Offline". */
    private Component statusLine(ListOrder.Status status, long lastSeen, long now) {
        return switch (status) {
            case ONLINE -> ui(FriendsMessages.PROFILE_STATUS_ONLINE);
            case AFK -> ui(FriendsMessages.PROFILE_STATUS_AFK);
            case OFFLINE -> lastSeen <= 0 ? ui(FriendsMessages.PROFILE_STATUS_OFFLINE)
                : ui(FriendsMessages.PROFILE_STATUS_SEEN, Arg.text("ago", TimeText.ago(lastSeen, now)));
        };
    }

    private Button button(MessageKey label, Button.Handler handler) {
        return Button.of(ui(label), handler).width(ROW_WIDTH);
    }

    private Button actionButton(ProfileButtons.Action action, UUID target, String name, Nav nav) {
        MessageKey label = switch (action) {
            case MESSAGE -> FriendsMessages.PROFILE_MESSAGE;
            case TELEPORT -> FriendsMessages.PROFILE_TELEPORT;
            case INVITE -> FriendsMessages.PROFILE_INVITE;
            case PAY -> FriendsMessages.PROFILE_PAY;
            case STATS -> FriendsMessages.PROFILE_STATS;
        };
        if (action == ProfileButtons.Action.MESSAGE) {
            return button(label, submission -> openMessageForm(submission.player(), target, name, nav));
        }
        return button(label, submission -> this.buttons.run(submission.player(), action, target, name));
    }

    /** The message form of a profile: the text goes to {@code /msg}, which does the delivery and its own checks. */
    private void openMessageForm(Player player, UUID target, String name, Nav nav) {
        show(player, this.templates.form(ui(FriendsMessages.PROFILE_MESSAGE_TITLE, Arg.text("name", name)), List.of(),
            List.of(new Input.Text("message", ui(FriendsMessages.PROFILE_MESSAGE_INPUT), "", MESSAGE_LENGTH, 3, 300)),
            ui(FriendsMessages.PROFILE_MESSAGE_SUBMIT),
            submission -> {
                Player clicker = submission.player();
                if (this.links.mutes().mute(clicker.getUniqueId()).isPresent()) {
                    submission.error(ui(FriendsMessages.PROFILE_MUTED));
                    return;
                }
                String text = submission.values().text("message").replace('\n', ' ').strip();
                if (text.isEmpty()) {
                    openProfile(clicker, target, nav, null);
                    return;
                }
                submission.close();
                this.buttons.perform(clicker, "msg " + name + " " + text);
            },
            submission -> openProfile(submission.player(), target, nav, null)), null);
    }

    /** The note form: private text on a friend, prefilled with the current note. */
    private void openNoteForm(Player player, UUID friend, Nav nav, String current) {
        String name = this.service.name(friend);
        show(player, this.templates.form(ui(FriendsMessages.PROFILE_NOTE_TITLE, Arg.text("name", name)),
            List.of(ui(FriendsMessages.PROFILE_NOTE_BODY)),
            List.of(Templates.text("note", ui(FriendsMessages.PROFILE_NOTE_INPUT), current, NoteText.MAX_LENGTH)),
            ui(FriendsMessages.PROFILE_NOTE_SUBMIT),
            submission -> after(submission.player(),
                this.service.note(submission.player(), friend, submission.values().text("note"), FriendService.Via.DIALOG),
                refused -> openProfile(submission.player(), friend, nav, refused)),
            submission -> openProfile(submission.player(), friend, nav, null)), null);
    }

    /** The note form opened by {@code /friend note <player>}: reads the current note first. */
    void openNote(Player player, UUID friend, Nav nav) {
        whenRead(player, this.service.store().profile(player.getUniqueId(), friend, false),
            data -> onThread(player, () -> openNoteForm(player, friend, nav, data.note() == null ? "" : data.note())));
    }

    /** "Remove Alex? They won't be told." */
    void confirmRemove(Player player, UUID friend, Nav nav) {
        String name = this.service.name(friend);
        show(player, this.templates.confirm(ui(FriendsMessages.PROFILE_REMOVE_TITLE),
            List.of(ui(FriendsMessages.PROFILE_REMOVE_BODY, Arg.text("name", name))),
            ui(FriendsMessages.PROFILE_REMOVE_YES), this.lang.get(CoreMessages.UI_CANCEL),
            submission -> after(submission.player(), this.service.remove(submission.player(), friend, FriendService.Via.DIALOG),
                refused -> openList(submission.player(), nav, refused)),
            submission -> openProfile(submission.player(), friend, nav, null)), null);
    }


    // ------------------------------------------------------------------ settings

    /**
     * The friends settings: the Friends &amp; teams group of the settings dialog, where they sit with the teams
     * settings (one place for every setting). Back returns to the friends list when it was opened from there. When the
     * player can change none of them (the server hides them all), says so instead.
     *
     * @param fromList whether the friends list's Settings button opened it (Back returns there); else Close
     */
    void openSettings(Player player, Nav nav, boolean fromList) {
        boolean shown = this.services.settings().screens().open(player, SettingCategories.SOCIAL.id(),
            fromList ? back -> openList(back, nav, null) : null);
        if (!shown) {
            if (fromList) {
                openList(player, nav, ui(FriendsMessages.SETTINGS_NONE));
            } else {
                this.services.messenger().send(player, FriendsMessages.SETTINGS_NONE);
            }
        }
    }
}
