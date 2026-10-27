package net.siftvanilla.siftcore.feature.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Ignoring players, from {@code /ignore} and its dialogs: the list of ignored players, one button per name (picking one
 * asks to stop ignoring them), nothing paged (the list is bounded by {@code ignore.max}, the dialog scrolls), and a form
 * to ignore someone new. Commands tell the outcome in chat; the dialogs show it (the list again, or the reason in red)
 * without a message. Every change re-checks its rules when it runs (the list may have changed since the dialog was
 * shown). Runs on the player's thread.
 */
final class IgnoreViews {

    /** What a change did: its line, and whether the list changed. */
    record Outcome(boolean changed, boolean refused, MessageKey key, Arg... args) {
    }

    private final Services services;
    private final Lang lang;
    private final Setting<ChatSettings> settings;
    private final IgnoreList ignores;

    IgnoreViews(Services services, Setting<ChatSettings> settings, IgnoreList ignores) {
        this.services = services;
        this.lang = services.lang();
        this.settings = settings;
        this.ignores = ignores;
    }

    // ------------------------------------------------------------------ changes

    /** Ignores the player, or stops ignoring them when already ignored (a command: the outcome goes to chat). */
    void toggle(Player player, UUID target) {
        if (this.ignores.ignores(player.getUniqueId(), target)) {
            remove(player, target);
        } else {
            add(player, target);
        }
    }

    /** Starts ignoring {@code target} from a command and tells the player. Returns true when the list changed. */
    boolean add(Player player, UUID target) {
        return tell(player, adding(player, target));
    }

    /** Stops ignoring {@code target} from a command and tells the player. Returns true when the list changed. */
    boolean remove(Player player, UUID target) {
        return tell(player, removing(player, target));
    }

    private boolean tell(Player player, Outcome outcome) {
        this.services.messenger().send(player, outcome.key(), outcome.args());
        return outcome.changed();
    }

    /** Starts ignoring {@code target}; says what happened without telling anyone. */
    Outcome adding(Player player, UUID target) {
        String name = this.services.directory().name(target);
        if (target.equals(player.getUniqueId())) {
            return new Outcome(false, true, CoreMessages.NOT_YOURSELF);
        }
        // Staff the player can't see (vanished) are treated like offline staff: the entry is stored and simply has no
        // effect, so the answer never tells that they are online.
        Player online = Bukkit.getPlayer(target);
        if (online != null && this.services.commands().canSee(player, online) && online.hasPermission(ChatNodes.UNIGNORABLE)) {
            return new Outcome(false, true, ChatMessages.IGNORE_STAFF, Arg.text("name", name));
        }
        int max = this.settings.get().maxIgnores();
        return switch (this.ignores.add(player.getUniqueId(), target, max)) {
            case ADDED -> new Outcome(true, false, ChatMessages.IGNORE_ADDED, Arg.text("name", name));
            case FULL -> new Outcome(false, true, ChatMessages.IGNORE_FULL, Arg.number("max", max));
            default -> new Outcome(false, false, ChatMessages.IGNORE_ADDED, Arg.text("name", name));
        };
    }

    /** Stops ignoring {@code target}; says what happened without telling anyone. */
    Outcome removing(Player player, UUID target) {
        String name = this.services.directory().name(target);
        if (this.ignores.remove(player.getUniqueId(), target) == IgnoreList.Change.REMOVED) {
            return new Outcome(true, false, ChatMessages.IGNORE_REMOVED, Arg.text("name", name));
        }
        return new Outcome(false, true, ChatMessages.IGNORE_NOT_IGNORED, Arg.text("name", name));
    }

    // ------------------------------------------------------------------ dialogs

    /** The ignore list: one status line, a button per ignored player (it asks to stop ignoring them), Ignore a player. */
    void openList(Player player) {
        openList(player, null);
    }

    /** The ignore list with a refusal in red ({@code error}), or none. */
    private void openList(Player player, Component error) {
        List<UUID> ignored = new ArrayList<>(this.ignores.ignored(player.getUniqueId()));
        var directory = this.services.directory();
        ignored.sort(Comparator.comparing(uuid -> directory.name(uuid).toLowerCase(Locale.ROOT)));
        int max = this.settings.get().maxIgnores();
        List<Component> lines = List.of(ignored.isEmpty() ? this.lang.get(ChatMessages.IGNORE_LIST_EMPTY)
            : this.lang.get(ChatMessages.IGNORE_LIST_COUNT, Arg.text("count", Lang.number(ignored.size())),
                Arg.text("max", Lang.number(max))));
        List<Button> buttons = new ArrayList<>();
        for (UUID uuid : ignored) {
            String name = directory.name(uuid);
            buttons.add(Button.of(Component.text(name), this.lang.get(ChatMessages.IGNORE_LIST_NAME_TOOLTIP, Arg.text("name", name)),
                s -> confirmRemove(s.player(), uuid, name)));
        }
        buttons.add(Button.of(this.lang.get(ChatMessages.IGNORE_LIST_ADD), this.lang.get(ChatMessages.IGNORE_LIST_ADD_TOOLTIP),
            s -> openAddForm(s.player(), "")));
        View view = this.services.templates().grid(this.lang.get(ChatMessages.IGNORE_LIST_TITLE), lines, buttons, null);
        this.services.dialogs().show(player, error == null ? view : view.withError(error, FormValues.EMPTY));
    }

    private void confirmRemove(Player player, UUID target, String name) {
        this.services.dialogs().show(player, this.services.templates().confirm(this.lang.get(ChatMessages.IGNORE_CONFIRM_TITLE),
            List.of(this.lang.get(ChatMessages.IGNORE_CONFIRM_BODY, Arg.text("name", name))),
            this.lang.get(ChatMessages.IGNORE_CONFIRM_BUTTON), this.lang.get(CoreMessages.UI_BACK),
            s -> {
                Outcome outcome = removing(s.player(), target);
                openList(s.player(), outcome.refused() ? this.lang.get(outcome.key(), outcome.args()) : null);
            },
            s -> openList(s.player())));
    }

    /** A form to ignore a player by name; a refusal (unknown name, staff, a full list) shows in red on the form. */
    void openAddForm(Player player, String name) {
        View form = this.services.templates().form(this.lang.get(ChatMessages.IGNORE_FORM_TITLE), List.of(),
            List.of(Templates.text("player", this.lang.get(ChatMessages.IGNORE_FORM_NAME), name, 16)),
            this.lang.get(ChatMessages.IGNORE_FORM_SUBMIT),
            s -> {
                String typed = s.values().text("player");
                Player online = Bukkit.getPlayerExact(typed);
                Optional<UUID> target = online != null ? Optional.of(online.getUniqueId()) : this.services.directory().uuid(typed);
                if (target.isEmpty()) {
                    s.error(this.lang.get(CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", typed)));
                    return;
                }
                Outcome outcome = adding(s.player(), target.get());
                if (outcome.refused()) {
                    s.error(this.lang.get(outcome.key(), outcome.args()));
                    return;
                }
                openList(s.player());
            },
            s -> openList(s.player()));
        Button submit = form.buttons().getFirst().tooltip(this.lang.get(ChatMessages.IGNORE_FORM_SUBMIT_TOOLTIP));
        List<Button> buttons = new ArrayList<>(form.buttons());
        buttons.set(0, submit);
        this.services.dialogs().show(player, new View(form.kind(), form.title(), form.body(), form.inputs(), buttons, form.exit(),
            form.columns(), form.escapable()));
    }
}
