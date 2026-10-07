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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Shows {@link View}s and routes every click back to its handler through one namespaced router.
 * <p>
 * Each shown view gets a random session token; its buttons send {@code siftcore:ui/<token>/<button>}. The router
 * accepts a click only for a live, unconsumed token of that same player, consumes it (so double clicks and replays
 * do nothing), re-validates every input against the view's own definition, and runs the handler on the player's
 * thread. Dialogs wait for the server's response, so the client cannot click twice. Invalid input re-opens the view
 * with an error and the typed values. Bedrock players get the same views as forms when a bridge is installed.
 */
public final class Dialogs implements Listener {

    public static final String NAMESPACE = "siftcore";
    private static final String UI_PREFIX = "ui/";
    private static final int MAX_SESSIONS_PER_PLAYER = 8;
    private static final long SESSION_TTL_MILLIS = 15 * 60 * 1000L;

    private record Session(long token, View view, long created, AtomicBoolean consumed) {
    }

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Map<Long, Session>> sessions = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Player>> routes = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> shown = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong handled = new AtomicLong();
    private volatile FormBridge bedrock;

    public Dialogs(Scheduler scheduler, Messenger messenger, Logger logger) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.logger = logger;
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
            long token = register(player, view);
            bridge.show(player, view, (buttonIndex, values) -> dispatch(player, token, buttonIndex, values));
            return;
        }
        Dialog dialog = render(view, register(player, view));
        if (this.scheduler.owns(player)) {
            player.showDialog(dialog);
        } else {
            this.scheduler.entity(player, () -> player.showDialog(dialog), null);
        }
    }

    /**
     * Builds a dialog to embed in a chat click event ({@code ClickEvent.showDialog}). The session stays valid for a
     * while, so the player can open it from chat later.
     */
    public Dialog inline(Player viewer, View view) {
        return render(view, register(viewer, view));
    }

    public void close(Player player) {
        if (this.scheduler.owns(player)) {
            player.closeDialog();
        } else {
            this.scheduler.entity(player, player::closeDialog, null);
        }
    }

    private long register(Player player, View view) {
        long token = this.random.nextLong() & Long.MAX_VALUE;
        Map<Long, Session> map = this.sessions.computeIfAbsent(player.getUniqueId(), k -> java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Session> eldest) {
                    return size() > MAX_SESSIONS_PER_PLAYER;
                }
            }));
        map.put(token, new Session(token, view, System.currentTimeMillis(), new AtomicBoolean()));
        return token;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.sessions.remove(event.getPlayer().getUniqueId());
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
            this.handled.incrementAndGet();
            this.scheduler.entity(player, () -> route.accept(player), null);
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
        Session session = session(player, token);
        if (session == null) {
            this.rejected.incrementAndGet();
            this.messenger.send(player, CoreMessages.UI_EXPIRED);
            close(player);
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

    private Session session(Player player, long token) {
        Map<Long, Session> map = this.sessions.get(player.getUniqueId());
        if (map == null) {
            return null;
        }
        Session session = map.get(token);
        if (session == null || System.currentTimeMillis() - session.created() > SESSION_TTL_MILLIS) {
            return null;
        }
        return session;
    }

    /** Validates and runs one click. {@code values} holds raw client values (String, Boolean or Float). */
    void dispatch(Player player, long token, int buttonIndex, Map<String, Object> values) {
        Session session = session(player, token);
        if (session == null || !session.consumed().compareAndSet(false, true)) {
            this.rejected.incrementAndGet();
            return;
        }
        Map<Long, Session> map = this.sessions.get(player.getUniqueId());
        if (map != null) {
            map.remove(token);
        }
        View view = session.view();
        List<Button> buttons = view.allButtons();
        if (buttonIndex < 0 || buttonIndex >= buttons.size()) {
            this.rejected.incrementAndGet();
            return;
        }
        Button button = buttons.get(buttonIndex);
        Validation validation = validate(view, values);
        this.handled.incrementAndGet();
        Runnable run = () -> {
            if (!player.isOnline()) {
                return;
            }
            if (button.handler() == null) {
                player.closeDialog();
                return;
            }
            if (validation.invalidLabel() != null) {
                show(player, view.withError(this.messenger.lang().get(CoreMessages.UI_INVALID_INPUT,
                    Arg.component("field", validation.invalidLabel())), validation.values()));
                return;
            }
            SubmissionImpl submission = new SubmissionImpl(player, validation.values(), view);
            long shownBefore = shownCount(player);
            try {
                button.handler().handle(submission);
            } catch (Throwable t) {
                this.logger.log(Level.SEVERE, "A dialog handler failed for " + player.getName(), t);
                this.messenger.send(player, CoreMessages.ACTION_FAILED);
                submission.close();
            }
            if (!submission.responded && shownCount(player) == shownBefore) {
                player.closeDialog();
            }
        };
        if (this.scheduler.owns(player)) {
            run.run();
        } else {
            this.scheduler.entity(player, run, null);
        }
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

    private static Dialog render(View view, long token) {
        String tokenText = Long.toString(token, 36);
        List<DialogBody> bodies = new ArrayList<>();
        for (Body body : view.body()) {
            bodies.add(switch (body) {
                case Body.Text text -> DialogBody.plainMessage(text.text(), text.width());
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
            .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
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
            this.player.closeDialog();
        }
    }

    /** Called by the plugin on disable so no stale sessions survive a reload. */
    public void clear() {
        this.sessions.clear();
    }

    /** Number of live sessions (metrics). */
    public int sessionCount() {
        int count = 0;
        for (Map<Long, Session> map : this.sessions.values()) {
            count += map.size();
        }
        return count;
    }
}
