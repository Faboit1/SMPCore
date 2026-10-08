package net.siftvanilla.siftcore.feature.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Public chat, in three steps on the async chat thread (everything read here is thread-safe):
 * <ol>
 *   <li>{@link EventPriority#LOWEST}: anti-spam and the word filter. Running first means a refused message also
 *       never reaches team chat or staff chat, and filtered words are replaced before anyone reads the text. Muted
 *       players are left to the staff tools, which refuse the message with the mute's reason.</li>
 *   <li>{@link EventPriority#NORMAL}: what is left is public chat (team and staff chat modes cancel earlier, at
 *       low priority). Chat lock and slow mode apply, players who ignore the sender stop being viewers,
 *       {@code [item]} becomes the held item, and the line gets its format: rank, the name with its hover card, and
 *       the message.</li>
 *   <li>{@link EventPriority#MONITOR}: once nothing can cancel it any more, mentioned viewers are pinged.</li>
 * </ol>
 * Player text is only ever inserted as plain text components, never parsed.
 */
final class ChatListener implements Listener {

    /** The links to other features that chat consults. */
    record Links(MuteStatus mutes, AfkStatus afk, VanishStatus vanish) {
    }

    private final Services services;
    private final Lang lang;
    private final Setting<ChatSettings> settings;
    private final MessageScreen screen;
    private final MessageScreen privateScreen;
    private final ChatModeration moderation;
    private final IgnoreList ignores;
    private final PlayerCards cards;
    private final HeldItems items;
    private final Links links;
    private final Toggle mentionsToggle;

    ChatListener(Services services, Setting<ChatSettings> settings, MessageScreen screen, MessageScreen privateScreen,
                 ChatModeration moderation, IgnoreList ignores, PlayerCards cards, HeldItems items, Links links,
                 Toggle mentionsToggle) {
        this.services = services;
        this.lang = services.lang();
        this.settings = settings;
        this.screen = screen;
        this.privateScreen = privateScreen;
        this.moderation = moderation;
        this.ignores = ignores;
        this.cards = cards;
        this.items = items;
        this.links = links;
        this.mentionsToggle = mentionsToggle;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void screen(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (this.links.mutes().mute(player.getUniqueId()).isPresent()) {
            return;
        }
        String raw = ChatText.plain(event.message());
        String text = ChatText.clean(raw);
        if (text.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        MessageScreen.Verdict verdict = this.screen.screen(player, text, System.currentTimeMillis());
        if (verdict.refused()) {
            event.setCancelled(true);
            this.services.messenger().send(player, verdict.refusal(), verdict.args());
            return;
        }
        if (!verdict.text().equals(raw)) {
            event.message(Component.text(verdict.text()));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void format(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Optional<MuteStatus.Mute> mute = this.links.mutes().mute(id);
        if (mute.isPresent()) {
            // The staff tools refuse muted chat earlier; this only matters if something let it through.
            event.setCancelled(true);
            MuteNotice.tell(this.services.messenger(), player, mute.get());
            return;
        }
        boolean bypass = player.hasPermission(ChatNodes.BYPASS);
        if (this.moderation.locked() && !bypass) {
            event.setCancelled(true);
            this.services.messenger().send(player, ChatMessages.LOCKED);
            return;
        }
        Duration slow = this.moderation.slow();
        if (!slow.isZero() && !bypass) {
            Duration left = this.services.cooldowns().tryUse(id, "chat-slow", slow);
            if (!left.isZero()) {
                event.setCancelled(true);
                this.services.messenger().send(player, ChatMessages.SLOW, Arg.time("time", Duration.ofSeconds(Math.max(1, (left.toMillis() + 999) / 1000))));
                return;
            }
        }
        if (!player.hasPermission(ChatNodes.UNIGNORABLE)) {
            event.viewers().removeIf(viewer -> viewer instanceof Player other && this.ignores.ignores(other.getUniqueId(), id));
        }
        ChatSettings settings = this.settings.get();
        Component message = event.message();
        if (settings.itemTag() && ItemTag.present(ChatText.plain(message)) && player.hasPermission(ChatNodes.ITEM)) {
            Component item = this.items.held(player);
            if (item != null) {
                message = ItemTag.replaceFirst(message, item);
                event.message(message);
            }
        }
        String rank = this.cards.rank(id);
        Component name = this.cards.chatName(id, player.getName(), settings.hoverCard());
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, text) -> line(rank, name, text)));
    }

    /** One formatted chat line. */
    Component line(String rank, Component name, Component message) {
        return rank.isEmpty()
            ? this.lang.get(ChatMessages.FORMAT_UNRANKED, Arg.component("name", name), Arg.component("message", message))
            : this.lang.get(ChatMessages.FORMAT, Arg.text("rank", rank), Arg.component("name", name), Arg.component("message", message));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mentions(AsyncChatEvent event) {
        ChatSettings settings = this.settings.get();
        if (!settings.mentions()) {
            return;
        }
        Player sender = event.getPlayer();
        Map<String, Player> viewers = new HashMap<>();
        for (Audience audience : event.viewers()) {
            if (audience instanceof Player viewer && !viewer.equals(sender)) {
                viewers.put(viewer.getName(), viewer);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        Set<String> mentioned = Mentions.find(ItemTag.typedText(event.message()), viewers.keySet(), settings.plainNameMentions(),
            settings.minPlainLength());
        for (String name : mentioned) {
            Player target = viewers.get(name);
            UUID targetId = target.getUniqueId();
            if (this.services.settings().enabled(targetId, this.mentionsToggle)
                && this.services.cooldowns().tryUse(sender.getUniqueId(), "chat-mention:" + targetId, settings.mentionCooldown()).isZero()) {
                this.services.messenger().send(target, ChatMessages.MENTIONED, Arg.text("name", sender.getName()));
            }
            if (this.links.afk().afk(targetId) && visible(sender, target)) {
                this.services.messenger().send(sender, ChatMessages.MENTION_AFK, Arg.text("name", target.getName()));
            }
        }
    }

    /** Whether {@code viewer} may know that {@code target} is online (vanished staff stay hidden). */
    boolean visible(Player viewer, Player target) {
        return !this.links.vanish().vanished(target.getUniqueId()) || viewer.canSee(target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        this.screen.guard().forget(id);
        this.privateScreen.guard().forget(id);
    }
}
