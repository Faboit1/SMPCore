package net.siftvanilla.siftcore.ui.dialog;

import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.link.FreezeStatus;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * Shows {@link View}s and routes every click back to its handler through one namespaced router.
 * <p>
 * Each shown view gets a random session token; its buttons send {@code siftcore:ui/<token>/<button>}. The router
 * accepts a click only for a live, unconsumed token of that same player, consumes it (so double clicks and replays
 * do nothing), re-validates every input against the view's own definition, and runs the handler on the player's
 * thread. Invalid input re-opens the view with an error and the typed values. Bedrock players get the same views as
 * forms when a bridge is installed.
 * <p>
 * Dialogs stay on screen after a click until the server shows the next one (no "waiting for response" screen), so
 * moving between screens feels instant. Every click therefore ends in a new dialog or a close: when a handler shows
 * nothing (within a short grace, for screens that load data first), the router closes the dialog. A button that
 * {@link Button#closes() closes} finishes something: when its handler shows nothing the dialog closes at once, without
 * the grace, also in a dialog whose other buttons lead on. A dialog with a {@link Button#waits() waiting} button
 * (searches) shows the client's waiting screen instead, and one whose buttons all close does so on the client at once.
 * A second click that lands before the next dialog arrives hits a consumed session and is ignored without a message.
 * A click on a dialog whose session is gone (it aged out, or the plugin reloaded) is told the menu expired and
 * closes every screen, so a waiting dialog never leaves the player on its waiting screen.
 * <p>
 * Sessions of dialogs on screen and of dialogs embedded in chat are kept apart ({@link DialogSessions}): browsing
 * menus never expires a teleport request's answer waiting in chat, and a burst of requests never expires the dialog
 * on screen. A dialog in chat also stays clickable as long as the longest request it can answer (an hour).
 * <p>
 * A player frozen by staff can't use any of it: every click except a plain close button is refused (and the dialog
 * closed) before a route or handler runs, so the pause-menu hub and dialogs opened from chat can't pay, trade or
 * teleport for them. The session is left unused, so a dialog from chat still works once they are unfrozen.
 * <p>
 * Click handlers and routes run as the clicking player reads ({@link net.siftvanilla.siftcore.core.text.Lang#viewing}):
 * the next screen they build writes money in that player's money format.
 */
public final class Dialogs implements Listener {

    public static final String NAMESPACE = "siftcore";
    private static final String UI_PREFIX = "ui/";
    /** How long a dialog put on screen stays clickable. */
    static final long SCREEN_TTL_MILLIS = 15 * 60 * 1000L;
    /**
     * How long a dialog embedded in chat stays clickable: as long as the longest request one answers can be configured
     * to last (a team invite's {@code invites.expire-after}, at most an hour), so the answer never expires before the
     * request. The handler still checks the request itself; {@link DialogSessions#MAX_CHAT} bounds the memory.
     */
    static final long CHAT_TTL_MILLIS = 60 * 60 * 1000L;
    /**
     * How long the router waits for a handler's screen before closing the dialog it was clicked in. Many handlers
     * show their next screen after loading something (a database read), so the close waits a moment instead of
     * flashing the world between two screens.
     */
    private static final long CLOSE_GRACE_TICKS = 6;
    /** How long after a click a second click on the same dialog counts as a double click (ignored silently). */
    private static final long DOUBLE_CLICK_MILLIS = 5_000L;

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final Logger logger;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final DialogSessions sessions;
    private final Map<String, Consumer<Player>> routes = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> shown = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong handled = new AtomicLong();
    private volatile FormBridge bedrock;
    private volatile FreezeStatus freezes = FreezeStatus.NONE;

    public Dialogs(Scheduler scheduler, Messenger messenger, Logger logger) {
        this(scheduler, messenger, logger, System::currentTimeMillis);
    }

    /** @param clock the wall clock in milliseconds (tests age sessions with their own) */
    Dialogs(Scheduler scheduler, Messenger messenger, Logger logger, LongSupplier clock) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.logger = logger;
        this.clock = clock;
        this.sessions = new DialogSessions(SCREEN_TTL_MILLIS, CHAT_TTL_MILLIS, clock);
    }

    /** Installs who is frozen by staff (the staff feature, wired once at startup): frozen players can't use menus. */
    public void freezes(FreezeStatus freezes) {
        this.freezes = freezes;
    }

    /** Installs the Bedrock form bridge (only when Floodgate is present). */
    public void bedrock(FormBridge bridge) {
        this.bedrock = bridge;
    }

    /**
     * Registers a static route {@code siftcore:<path>} that needs no session, used by the hub dialog registered in
     * the pause menu. The action runs on the player's thread and must check permissions itself.
     */
    public void route(String path, Consumer<Player> action) {
        if (path.startsWith(UI_PREFIX)) {
            throw new IllegalArgumentException("Reserved path " + path);
        }
        this.routes.put(path, action);
    }

    public long handledCount() {
        return this.handled.get();
    }

    public long rejectedCount() {
        return this.rejected.get();
    }

    /**
     * Records that something new was put on the player's screen (a dialog or a menu), so the router does not
     * close it after the handler that opened it returns.
     */
    public void markShown(Player player) {
        this.shown.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong()).incrementAndGet();
    }

    private long shownCount(Player player) {
        AtomicLong counter = this.shown.get(player.getUniqueId());
        return counter == null ? 0 : counter.get();
    }

    /** Shows a view, replacing whatever dialog is open. Safe from any thread. */
    public void show(Player player, View view) {
        markShown(player);
        FormBridge bridge = this.bedrock;
        if (bridge != null && bridge.handles(player)) {
            long token = register(player, view, false);
            bridge.show(player, view, (buttonIndex, values) -> dispatch(player, token, buttonIndex, values));
            return;
        }
        Dialog dialog = render(view, register(player, view, false), this.messenger.lang().style().palette());
        if (this.scheduler.owns(player)) {
            player.showDialog(dialog);
        } else {
            this.scheduler.entity(player, () -> player.showDialog(dialog), null);
        }
    }

    /**
     * Builds a view for the player and shows it ({@link #show(Player, View)}): the view is built as they read, so money
     * in it follows their money format. For screens built after a database read or another wait, which run outside the
     * click or command that asked for them (the scope a click handler or command has does not last into a callback).
     */
    public void show(Player player, Supplier<View> view) {
        show(player, this.messenger == null ? view.get() : this.messenger.lang().viewing(player, view));
    }

    /**
     * Builds a dialog to embed in a chat click event ({@code ClickEvent.showDialog}). The session stays valid for an
     * hour (as long as the longest request it can answer), so the player can open it from chat later, however many
     * other menus they open meanwhile; the handler tells them if the request itself is over.
     */
    public Dialog inline(Player viewer, View view) {
        return render(view, register(viewer, view, true), this.messenger.lang().style().palette());
    }

    public void close(Player player) {
        if (this.scheduler.owns(player)) {
            player.closeDialog();
        } else {
            this.scheduler.entity(player, player::closeDialog, null);
        }
    }

    /**
     * Closes every screen a click may have left the player on, including the "waiting for response" screen of a
     * {@link Button#waits() waiting} dialog, which {@link #close(Player)} does not leave. For handlers that answer a
     * waiting click later without a new screen. Safe from any thread.
     */
    public void closeScreen(Player player) {
        closeAfterClick(player);
    }

    /**
     * Opens a session for a view and returns its token (the buttons send {@code siftcore:ui/<token>/<button>}).
     *
     * @param inline whether the view is embedded in chat rather than put on screen
     */
    long register(Player player, View view, boolean inline) {
        long token = this.random.nextLong() & Long.MAX_VALUE;
        this.sessions.add(player.getUniqueId(), token, view, inline);
        return token;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.sessions.forget(event.getPlayer().getUniqueId());
        this.shown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!NAMESPACE.equals(id.namespace())) {
            return;
        }
        Player player = player(event.getCommonConnection());
        if (player == null) {
            return;
        }
        String path = id.value();
        if (!path.startsWith(UI_PREFIX)) {
            Consumer<Player> route = this.routes.get(path);
            if (route == null) {
                this.rejected.incrementAndGet();
                return;
            }
            if (this.freezes.frozen(player.getUniqueId())) {
                refuseFrozen(player);
                return;
            }
            this.handled.incrementAndGet();
            this.scheduler.entity(player, () -> runRoute(player, route), null);
            return;
        }
        String[] parts = path.substring(UI_PREFIX.length()).split("/");
        if (parts.length != 2) {
            this.rejected.incrementAndGet();
            return;
        }
        long token;
        int button;
        try {
            token = Long.parseLong(parts[0], 36);
            button = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            this.rejected.incrementAndGet();
            return;
        }
        DialogSessions.Session session = this.sessions.get(player.getUniqueId(), token);
        if (session == null) {
            this.rejected.incrementAndGet();
            this.messenger.send(player, CoreMessages.UI_EXPIRED);
            // The session is gone, so how the client took the click is unknown: a waiting dialog left it on its
            // waiting screen, which only the container close of closeAfterClick leaves.
            closeAfterClick(player);
            return;
        }
        long consumedAt = session.consumedAt().get();
        if (consumedAt != 0) {
            this.rejected.incrementAndGet();
            if (this.clock.getAsLong() - consumedAt > DOUBLE_CLICK_MILLIS) {
                // Not a double click: an old dialog opened again (from chat, say) whose one click was already used.
                this.messenger.send(player, CoreMessages.UI_EXPIRED);
                if (afterAction(session.view().allButtons()) == DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE) {
                    closeAfterClick(player);
                } else {
                    close(player);
                }
            }
            // Otherwise a second click while the first click's answer is on its way: nothing to do.
            return;
        }
        List<Button> buttons = session.view().allButtons();
        if (button < 0 || button >= buttons.size()) {
            this.rejected.incrementAndGet();
            return;
        }
        Map<String, Object> raw = read(session.view(), event.getDialogResponseView());
        dispatch(player, token, button, raw);
    }

    /** Runs a static route's action on the player's thread, then closes the dialog unless something new is shown. */
    void runRoute(Player player, Consumer<Player> route) {
        long shownBefore = shownCount(player);
        Inventory screenBefore = openContainer(player);
        try {
            asPlayer(player, () -> route.accept(player));
        } catch (Throwable t) {
            this.logger.log(Level.SEVERE, "A menu route failed for " + player.getName(), t);
            this.messenger.send(player, CoreMessages.ACTION_FAILED);
        }
        closeUnlessAnswered(player, shownBefore, screenBefore);
    }

    /** Validates and runs one click. {@code values} holds raw client values (String, Boolean or Float). */
    void dispatch(Player player, long token, int buttonIndex, Map<String, Object> values) {
        DialogSessions.Session session = this.sessions.get(player.getUniqueId(), token);
        if (session != null && this.freezes.frozen(player.getUniqueId()) && !closesOnly(session.view(), buttonIndex)) {
            refuseFrozen(player);
            return;
        }
        if (session == null || !session.consumedAt().compareAndSet(0, Math.max(1, this.clock.getAsLong()))) {
            this.rejected.incrementAndGet();
            return;
        }
        // The consumed session stays (until it ages out or is pushed out by newer ones of its pool), so a late second
        // click on the same dialog is recognised and ignored instead of being answered with "this menu expired".
        View view = session.view();
        List<Button> buttons = view.allButtons();
        if (buttonIndex < 0 || buttonIndex >= buttons.size()) {
            this.rejected.incrementAndGet();
            return;
        }
        Button button = buttons.get(buttonIndex);
        Validation validation = validate(view, values);
        this.handled.incrementAndGet();
        // The handler renders the next screen for the player who clicked: money in their money format.
        Runnable run = () -> asPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (button.handler() == null) {
                closeAfterClick(player);
                return;
            }
            if (validation.invalidLabel() != null) {
                show(player, view.withError(this.messenger.lang().get(CoreMessages.UI_INVALID_INPUT,
                    Arg.component("field", validation.invalidLabel())), validation.values()));
                return;
            }
            SubmissionImpl submission = new SubmissionImpl(player, validation.values(), view);
            long shownBefore = shownCount(player);
            Inventory screenBefore = openContainer(player);
            try {
                button.handler().handle(submission);
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "A dialog handler failed for " + player.getName(), t);
                this.messenger.send(player, CoreMessages.ACTION_FAILED);
                submission.close();
            }
            if (!submission.responded) {
                if (button.after() == Button.After.CLOSE) {
                    closeNowUnlessAnswered(player, shownBefore, screenBefore);
                } else {
                    closeUnlessAnswered(player, shownBefore, screenBefore);
                }
            }
        });
        if (this.scheduler.owns(player)) {
            run.run();
        } else {
            this.scheduler.entity(player, run, null);
        }
    }

    /** Runs click code as the player reads: the screens and lines it renders write money in their money format. */
    private void asPlayer(Player player, Runnable action) {
        if (this.messenger == null) {
            action.run();
        } else {
            this.messenger.lang().viewing(player, action);
        }
    }

    /** Whether the button only closes the dialog (no handler), which is all a frozen player may click. */
    private static boolean closesOnly(View view, int buttonIndex) {
        List<Button> buttons = view.allButtons();
        return buttonIndex >= 0 && buttonIndex < buttons.size() && buttons.get(buttonIndex).handler() == null;
    }

    /** Refuses a frozen player's click: tells them, and closes the dialog (and any screen) they clicked in. */
    private void refuseFrozen(Player player) {
        this.rejected.incrementAndGet();
        this.messenger.send(player, CoreMessages.FROZEN);
        closeAfterClick(player);
    }

    /**
     * Closes the dialog a click came from unless something new is shown within {@link #CLOSE_GRACE_TICKS}: a screen
     * the handler opens after loading data replaces the dialog directly instead of after a close. Player's thread.
     *
     * @param screenBefore the container that was open when the click arrived (see {@link #closeAfterClick(Player, Inventory)})
     */
    private void closeUnlessAnswered(Player player, long shownBefore, Inventory screenBefore) {
        if (shownCount(player) != shownBefore) {
            return;
        }
        this.scheduler.entityLater(player, () -> {
            if (player.isOnline() && shownCount(player) == shownBefore) {
                closeAfterClick(player, screenBefore);
            }
        }, null, CLOSE_GRACE_TICKS);
    }

    /**
     * Closes the dialog a {@link Button#closes() closing} button was clicked in right away, unless its handler showed
     * something new: the button finished what the dialog was for, so there is no next screen to wait for (a dialog
     * whose other buttons lead on renders without a client-side close, so the server closes it). Player's thread.
     */
    private void closeNowUnlessAnswered(Player player, long shownBefore, Inventory screenBefore) {
        if (shownCount(player) == shownBefore) {
            closeAfterClick(player, screenBefore);
        }
    }

    private void closeAfterClick(Player player) {
        closeAfterClick(player, null);
    }

    /**
     * Closes the screen a click left the client on: the dialog itself, or the "waiting for response" screen of a
     * waiting dialog. That screen ignores the clear-dialog packet (it only closes a dialog screen), so a container
     * close is sent as well: the client handles that by closing whatever screen is open. Runs on the player's thread.
     *
     * @param screenBefore the container open when the click arrived, or null to close any container. When another
     *                     container is open by now, the click opened it without telling the router (another plugin's
     *                     menu, such as AxAuctions' after a command ran), so it stays open: only the dialog is
     *                     cleared, which the client ignores while it shows a container.
     */
    private void closeAfterClick(Player player, Inventory screenBefore) {
        Runnable close = () -> {
            if (player.isOnline()) {
                player.closeDialog();
                if (screenBefore == null || screenBefore.equals(openContainer(player))) {
                    player.closeInventory();
                }
            }
        };
        if (this.scheduler.owns(player)) {
            close.run();
        } else {
            this.scheduler.entity(player, close, null);
        }
    }

    /** The top inventory of the player's open view: a container, or their own crafting grid when none is open. */
    private static Inventory openContainer(Player player) {
        return player.getOpenInventory().getTopInventory();
    }

    private record Validation(FormValues values, Component invalidLabel) {
    }

    private static Validation validate(View view, Map<String, Object> raw) {
        Map<String, Object> clean = new HashMap<>();
        Component invalid = null;
        for (Input input : view.inputs()) {
            Object value = raw.get(input.key());
            switch (input) {
                case Input.Text t -> {
                    String text = value instanceof String s ? s : null;
                    if (text == null) {
                        invalid = invalid == null ? t.label() : invalid;
                        continue;
                    }
                    text = sanitize(text, t.lines() > 1);
                    if (text.length() > t.maxLength()) {
                        invalid = invalid == null ? t.label() : invalid;
                        text = text.substring(0, t.maxLength());
                    }
                    clean.put(t.key(), text);
                }
                case Input.Toggle t -> {
                    if (value instanceof Boolean b) {
                        clean.put(t.key(), b);
                    } else {
                        invalid = invalid == null ? t.label() : invalid;
                    }
                }
                case Input.Choice c -> {
                    if (value instanceof String s && c.allows(s)) {
                        clean.put(c.key(), s);
                    } else {
                        invalid = invalid == null ? c.label() : invalid;
                    }
                }
                case Input.Range r -> {
                    if (value instanceof Float f && Float.isFinite(f)) {
                        long rounded = Math.round(f);
                        if (r.allows(rounded)) {
                            clean.put(r.key(), rounded);
                            continue;
                        }
                    }
                    invalid = invalid == null ? r.label() : invalid;
                }
            }
        }
        return new Validation(new FormValues(clean), invalid);
    }

    /** Removes control characters (and newlines unless multiline) from client text. */
    static String sanitize(String text, boolean multiline) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' && multiline) {
                sb.append(c);
            } else if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != '§') {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static Map<String, Object> read(View view, DialogResponseView response) {
        Map<String, Object> values = new HashMap<>();
        if (response == null) {
            return values;
        }
        for (Input input : view.inputs()) {
            Object value = switch (input) {
                case Input.Text t -> response.getText(t.key());
                case Input.Choice c -> response.getText(c.key());
                case Input.Toggle t -> response.getBoolean(t.key());
                case Input.Range r -> response.getFloat(r.key());
            };
            if (value != null) {
                values.put(input.key(), value);
            }
        }
        return values;
    }

    private static Player player(PlayerCommonConnection connection) {
        if (connection instanceof PlayerGameConnection game) {
            return game.getPlayer();
        }
        return null;
    }

    // ---------------------------------------------------------------- rendering

    private static Dialog render(View view, long token, net.siftvanilla.siftcore.core.text.Palette palette) {
        String tokenText = Long.toString(token, 36);
        List<DialogBody> bodies = new ArrayList<>();
        for (Body body : view.body()) {
            bodies.add(switch (body) {
                case Body.Text text -> DialogBody.plainMessage(text.error() ? palette.asError(text.text()) : text.text(), text.width());
                case Body.Item item -> DialogBody.item(item.item())
                    .description(item.description() == null ? null : DialogBody.plainMessage(item.description()))
                    .showDecorations(true)
                    .showTooltip(item.showTooltip())
                    .width(16)
                    .height(16)
                    .build();
            });
        }
        List<DialogInput> inputs = new ArrayList<>();
        for (Input input : view.inputs()) {
            inputs.add(switch (input) {
                case Input.Text t -> DialogInput.text(t.key(), t.label())
                    .width(t.width())
                    .labelVisible(true)
                    .initial(t.initial())
                    .maxLength(t.maxLength())
                    .multiline(t.lines() > 1 ? TextDialogInput.MultilineOptions.create(t.lines(), null) : null)
                    .build();
                case Input.Toggle t -> DialogInput.bool(t.key(), t.label()).initial(t.initial()).build();
                case Input.Choice c -> {
                    List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                    for (Input.Option option : c.options()) {
                        entries.add(SingleOptionDialogInput.OptionEntry.create(option.id(), option.label(), option.id().equals(c.initial())));
                    }
                    yield DialogInput.singleOption(c.key(), c.label(), entries).width(c.width()).labelVisible(true).build();
                }
                case Input.Range r -> DialogInput.numberRange(r.key(), r.label(), r.min(), r.max())
                    .width(r.width())
                    .labelFormat(r.labelFormat())
                    .initial((float) r.initial())
                    .step((float) r.step())
                    .build();
            });
        }
        List<Button> all = view.allButtons();
        List<ActionButton> rendered = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            Button button = all.get(i);
            rendered.add(ActionButton.builder(button.label())
                .tooltip(button.tooltip())
                .width(button.width())
                .action(DialogAction.customClick(Key.key(NAMESPACE, UI_PREFIX + tokenText + "/" + i), null))
                .build());
        }
        DialogBase base = DialogBase.builder(view.title())
            .canCloseWithEscape(view.escapable())
            .pause(false)
            .afterAction(afterAction(all))
            .body(bodies)
            .inputs(inputs)
            .build();
        DialogType type = switch (view.kind()) {
            case NOTICE -> DialogType.notice(rendered.getFirst());
            case CONFIRM -> DialogType.confirmation(rendered.get(0), rendered.get(1));
            case LIST, FORM -> {
                List<ActionButton> actions = view.exit() == null ? rendered : rendered.subList(0, rendered.size() - 1);
                ActionButton exit = view.exit() == null ? null : rendered.getLast();
                if (actions.isEmpty()) {
                    // Vanilla needs at least one action in a multi-action dialog; a lone footer is a notice.
                    yield DialogType.notice(exit);
                }
                yield DialogType.multiAction(actions).exitAction(exit).columns(view.columns()).build();
            }
        };
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    /**
     * What the client does after any click, for a whole dialog (Minecraft has no per-button setting): wait when a
     * button asks for it, close at once when every button closes, otherwise keep the dialog until the next one.
     */
    static DialogBase.DialogAfterAction afterAction(List<Button> buttons) {
        boolean allClose = true;
        for (Button button : buttons) {
            if (button.after() == Button.After.WAIT) {
                return DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE;
            }
            allClose &= button.closesOnClient();
        }
        return allClose ? DialogBase.DialogAfterAction.CLOSE : DialogBase.DialogAfterAction.NONE;
    }

    /** Renders a plain text component for logs and Bedrock forms. */
    public static String plain(Component component) {
        return TextStyle.plain(component);
    }

    private final class SubmissionImpl implements Submission {

        private final Player player;
        private final FormValues values;
        private final View view;
        private boolean responded;

        SubmissionImpl(Player player, FormValues values, View view) {
            this.player = player;
            this.values = values;
            this.view = view;
        }

        @Override
        public Player player() {
            return this.player;
        }

        @Override
        public FormValues values() {
            return this.values;
        }

        @Override
        public View view() {
            return this.view;
        }

        @Override
        public void show(View next) {
            this.responded = true;
            Dialogs.this.show(this.player, next);
        }

        @Override
        public void error(Component message) {
            this.responded = true;
            Dialogs.this.show(this.player, this.view.withError(message, this.values));
            Dialogs.this.messenger.feedback(this.player, net.siftvanilla.siftcore.core.text.Feedback.ERROR);
        }

        @Override
        public void close() {
            this.responded = true;
            closeAfterClick(this.player);
        }
    }

    /** Called by the plugin on disable so no stale sessions survive a reload. */
    public void clear() {
        this.sessions.clear();
    }

    /** Number of live sessions (metrics). */
    public int sessionCount() {
        return this.sessions.size();
    }
}
