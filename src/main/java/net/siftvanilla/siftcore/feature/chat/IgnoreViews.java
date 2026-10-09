package net.siftvanilla.siftcore.feature.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Ignoring players, from {@code /ignore} and its dialogs: the list of ignored players (pick one to stop ignoring
 * them, after a confirmation), and a form to ignore someone new. Every change re-checks its rules when it runs
 * (the list may have changed since the dialog was shown). Runs on the player's thread.
 */
final class IgnoreViews {

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

    /** Ignores the player, or stops ignoring them when already ignored. */
    void toggle(Player player, UUID target) {
        if (this.ignores.ignores(player.getUniqueId(), target)) {
            remove(player, target);
        } else {
            add(player, target);
        }
    }

    /** Starts ignoring {@code target}; tells the player the outcome. Returns true when the list changed. */
    boolean add(Player player, UUID target) {
        String name = this.services.directory().name(target);
        if (target.equals(player.getUniqueId())) {
            this.services.messenger().send(player, CoreMessages.NOT_YOURSELF);
            return false;
        }
        // Staff the player can't see (vanished) are treated like offline staff: the entry is stored and simply has no
        // effect, so the answer never tells that they are online.
        Player online = Bukkit.getPlayer(target);
        if (online != null && this.services.commands().canSee(player, online) && online.hasPermission(ChatNodes.UNIGNORABLE)) {
            this.services.messenger().send(player, ChatMessages.IGNORE_STAFF, Arg.text("name", name));
            return false;
        }
        int max = this.settings.get().maxIgnores();
        switch (this.ignores.add(player.getUniqueId(), target, max)) {
            case ADDED -> {
                this.services.messenger().send(player, ChatMessages.IGNORE_ADDED, Arg.text("name", name));
                return true;
            }
            case FULL -> this.services.messenger().send(player, ChatMessages.IGNORE_FULL, Arg.number("max", max));
            default -> this.services.messenger().send(player, ChatMessages.IGNORE_ADDED, Arg.text("name", name));
        }
        return false;
    }

    /** Stops ignoring {@code target}; tells the player the outcome. */
    boolean remove(Player player, UUID target) {
        String name = this.services.directory().name(target);
        if (this.ignores.remove(player.getUniqueId(), target) == IgnoreList.Change.REMOVED) {
            this.services.messenger().send(player, ChatMessages.IGNORE_REMOVED, Arg.text("name", name));
            return true;
        }
        this.services.messenger().send(player, ChatMessages.IGNORE_NOT_IGNORED, Arg.text("name", name));
        return false;
    }

    // ------------------------------------------------------------------ dialogs

    /** The ignore list, one page of names; picking a name asks to stop ignoring them. */
    void openList(Player player, int page) {
        List<UUID> ignored = new ArrayList<>(this.ignores.ignored(player.getUniqueId()));
        var directory = this.services.directory();
        ignored.sort(Comparator.comparing(uuid -> directory.name(uuid).toLowerCase(java.util.Locale.ROOT)));
        int pageSize = this.settings.get().ignorePageSize();
        int pages = Math.max(1, (ignored.size() + pageSize - 1) / pageSize);
        int current = Math.clamp(page, 1, pages);
        List<Component> lines = new ArrayList<>();
        if (ignored.isEmpty()) {
            lines.add(this.lang.get(ChatMessages.IGNORE_LIST_EMPTY));
        } else if (ignored.size() == 1) {
            lines.add(this.lang.get(ChatMessages.IGNORE_LIST_ONE));
        } else {
            lines.add(this.lang.get(ChatMessages.IGNORE_LIST_MANY, Arg.number("count", ignored.size())));
        }
        if (pages > 1) {
            lines.add(this.lang.get(ChatMessages.IGNORE_LIST_PAGE, Arg.number("page", current), Arg.number("pages", pages)));
        }
        List<Button> buttons = new ArrayList<>();
        int from = (current - 1) * pageSize;
        for (UUID uuid : ignored.subList(Math.min(from, ignored.size()), Math.min(from + pageSize, ignored.size()))) {
            String name = directory.name(uuid);
            buttons.add(Button.of(Component.text(name), s -> confirmRemove(s.player(), uuid, name, current)).width(150));
        }
        if (current > 1) {
            buttons.add(Button.of(this.lang.get(ChatMessages.IGNORE_LIST_PREVIOUS), s -> openList(s.player(), current - 1)).width(150));
        }
        if (current < pages) {
            buttons.add(Button.of(this.lang.get(ChatMessages.IGNORE_LIST_NEXT), s -> openList(s.player(), current + 1)).width(150));
        }
        buttons.add(Button.of(this.lang.get(ChatMessages.IGNORE_LIST_ADD), s -> openAddForm(s.player(), "")).width(150));
        this.services.dialogs().show(player, this.services.templates().list(this.lang.get(ChatMessages.IGNORE_LIST_TITLE), lines,
            buttons, 2, null));
    }

    private void confirmRemove(Player player, UUID target, String name, int page) {
        this.services.dialogs().show(player, this.services.templates().confirm(this.lang.get(ChatMessages.IGNORE_CONFIRM_TITLE),
            List.of(this.lang.get(ChatMessages.IGNORE_CONFIRM_BODY, Arg.text("name", name))),
            this.lang.get(ChatMessages.IGNORE_CONFIRM_BUTTON), this.lang.get(CoreMessages.UI_BACK),
            s -> {
                remove(s.player(), target);
                openList(s.player(), page);
            },
            s -> openList(s.player(), page)));
    }

    /** A form to ignore a player by name. */
    void openAddForm(Player player, String name) {
        this.services.dialogs().show(player, this.services.templates().form(this.lang.get(ChatMessages.IGNORE_FORM_TITLE),
            List.of(this.lang.get(ChatMessages.IGNORE_FORM_BODY)),
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
                add(s.player(), target.get());
                openList(s.player(), 1);
            },
            s -> openList(s.player(), 1)));
    }
}
