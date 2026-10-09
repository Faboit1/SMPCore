package net.siftvanilla.siftcore.feature.chat;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.event.PrivateMessageEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Private messages: {@code /msg}, {@code /r}, social spy and the console log.
 * <p>
 * A message from a player goes through these checks, in the order a player can meet them: empty, muted, yourself,
 * the receiver is offline or hidden (vanished staff), you ignore them, they ignore you (unless you are staff), they
 * turned private messages off (unless you are staff), anti-spam and the word filter, then the cancellable
 * {@link PrivateMessageEvent}. Someone who wrote to you recently can always be answered, even if they are hidden or
 * turned messages off: they started the conversation.
 * <p>
 * Runs on the sender's thread (commands); every call it makes is a packet send or a thread-safe lookup.
 */
final class PrivateMessages {

    private final Services services;
    private final Messenger messenger;
    private final Lang lang;
    private final Setting<ChatSettings> settings;
    private final IgnoreList ignores;
    private final Conversations conversations;
    private final MessageScreen screen;
    private final PlayerCards cards;
    private final HeldItems items;
    private final ChatListener.Links links;
    private final Toggle privateToggle;
    private final Toggle spyToggle;

    PrivateMessages(Services services, Setting<ChatSettings> settings, IgnoreList ignores, Conversations conversations,
                    MessageScreen screen, PlayerCards cards, HeldItems items, ChatListener.Links links, Toggle privateToggle,
                    Toggle spyToggle) {
        this.services = services;
        this.messenger = services.messenger();
        this.lang = services.lang();
        this.settings = settings;
        this.ignores = ignores;
        this.conversations = conversations;
        this.screen = screen;
        this.cards = cards;
        this.items = items;
        this.links = links;
        this.privateToggle = privateToggle;
        this.spyToggle = spyToggle;
    }

    Conversations conversations() {
        return this.conversations;
    }

    /** {@code /msg <name> <message>} from a player or the console; {@code name} may be a nickname a player shows. */
    void message(CommandSender sender, String targetName, String raw) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = this.links.cosmetics().byNick(targetName).orElse(null);
        }
        if (sender instanceof Player player) {
            if (target != null && target.equals(player)) {
                this.messenger.send(player, ChatMessages.PM_SELF);
                return;
            }
            if (target == null || !reachable(player, target)) {
                this.messenger.send(player, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", targetName));
                return;
            }
            send(player, target, raw);
            return;
        }
        if (target == null) {
            this.messenger.send(sender, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", targetName));
            return;
        }
        fromConsole(sender, target, raw);
    }

    /** {@code /r <message>}: answers whoever the player talked to last. */
    void reply(Player player, String raw) {
        Optional<UUID> partner = this.conversations.replyTarget(player.getUniqueId());
        if (partner.isEmpty()) {
            this.messenger.send(player, ChatMessages.PM_NO_REPLY);
            return;
        }
        UUID other = partner.get();
        if (other.equals(Conversations.CONSOLE)) {
            send(player, null, raw);
            return;
        }
        Player target = Bukkit.getPlayer(other);
        if (target == null || !reachable(player, target)) {
            this.messenger.send(player, CoreMessages.PLAYER_NOT_ONLINE, Arg.text("name", this.services.directory().name(other)));
            return;
        }
        send(player, target, raw);
    }

    /** Whether the sender may know the target is online: visible, or they wrote to the sender recently. */
    private boolean reachable(Player sender, Player target) {
        return !this.links.vanish().vanished(target.getUniqueId()) || sender.canSee(target)
            || this.conversations.wroteTo(target.getUniqueId(), sender.getUniqueId());
    }

    /** Sends from a player to a player, or to the console when {@code target} is null. */
    private void send(Player sender, Player target, String raw) {
        String text = ChatText.clean(raw);
        if (text.isEmpty()) {
            this.messenger.send(sender, ChatMessages.PM_EMPTY);
            return;
        }
        UUID from = sender.getUniqueId();
        Optional<MuteStatus.Mute> mute = this.links.mutes().mute(from);
        if (mute.isPresent()) {
            MuteNotice.tell(this.messenger, sender, mute.get());
            return;
        }
        String targetName = target == null ? this.lang.plain(ChatMessages.PM_CONSOLE) : target.getName();
        UUID to = target == null ? Conversations.CONSOLE : target.getUniqueId();
        if (target != null) {
            boolean answering = this.conversations.wroteTo(to, from);
            if (this.ignores.ignores(from, to)) {
                this.messenger.send(sender, ChatMessages.PM_IGNORING, Arg.text("name", targetName));
                return;
            }
            if (this.ignores.ignores(to, from) && !sender.hasPermission(ChatNodes.UNIGNORABLE)) {
                this.messenger.send(sender, ChatMessages.PM_BLOCKED, Arg.text("name", targetName));
                return;
            }
            if (!answering && !this.services.settings().enabled(to, this.privateToggle) && !sender.hasPermission(ChatNodes.MSG_BYPASS)) {
                this.messenger.send(sender, ChatMessages.PM_DISABLED, Arg.text("name", targetName));
                return;
            }
        }
        MessageScreen.Verdict verdict = this.screen.screen(sender, text, System.currentTimeMillis());
        if (verdict.refused()) {
            this.messenger.send(sender, verdict.refusal(), verdict.args());
            return;
        }
        String finalText = verdict.text();
        if (!new PrivateMessageEvent(from, target == null ? null : to, finalText).callEvent()) {
            this.messenger.send(sender, ChatMessages.PM_BLOCKED, Arg.text("name", targetName));
            return;
        }
        Component message = Component.text(finalText);
        if (this.settings.get().itemTag() && ItemTag.present(finalText) && sender.hasPermission(ChatNodes.ITEM)) {
            Component item = this.items.held(sender);
            if (item != null) {
                message = ItemTag.replaceFirst(message, item);
            }
        }
        deliver(sender, sender.getName(), from, target, targetName, to, message);
        if (target != null && this.links.afk().afk(to)) {
            this.messenger.send(sender, ChatMessages.PM_AFK, Arg.text("name", targetName));
        }
    }

    /** {@code /msg} typed in the console: staff speak, so no ignore, setting or anti-spam checks apply. */
    private void fromConsole(CommandSender console, Player target, String raw) {
        String text = ChatText.clean(raw);
        if (text.isEmpty()) {
            this.messenger.send(console, ChatMessages.PM_EMPTY);
            return;
        }
        if (!new PrivateMessageEvent(null, target.getUniqueId(), text).callEvent()) {
            this.messenger.send(console, ChatMessages.PM_BLOCKED, Arg.text("name", target.getName()));
            return;
        }
        deliver(console, this.lang.plain(ChatMessages.PM_CONSOLE), Conversations.CONSOLE, target, target.getName(),
            target.getUniqueId(), Component.text(text));
    }

    /**
     * Sends the message both ways (names as the players show them, the sender's chat colour for the sender and for a
     * receiver who sees chat colours), to social spy and to the console log (both plain).
     */
    private void deliver(CommandSender sender, String senderName, UUID from, Player target, String targetName, UUID to,
                         Component message) {
        boolean senderIsConsole = from.equals(Conversations.CONSOLE);
        boolean targetIsConsole = target == null;
        Player senderPlayer = sender instanceof Player player ? player : null;
        Component painted = senderPlayer == null ? message : this.links.cosmetics().paint(senderPlayer, message);
        this.messenger.send(sender, ChatMessages.PM_TO,
            Arg.component("name", this.cards.messageName(targetName, target, targetIsConsole)), Arg.component("message", painted));
        CommandSender receiver = targetIsConsole ? Bukkit.getConsoleSender() : target;
        boolean colours = target == null || this.links.cosmetics().showsChatColours(target.getUniqueId());
        this.messenger.send(receiver, ChatMessages.PM_FROM,
            Arg.component("name", this.cards.messageName(senderName, senderPlayer, senderIsConsole)),
            Arg.component("message", colours ? painted : message));
        Component spy = this.lang.get(ChatMessages.PM_SPY, Arg.text("from", senderName), Arg.text("to", targetName),
            Arg.component("message", message));
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (!id.equals(from) && !id.equals(to) && online.hasPermission(ChatNodes.SOCIALSPY)
                && this.services.settings().enabled(id, this.spyToggle)) {
                online.sendMessage(spy);
            }
        }
        if (this.settings.get().logPrivate() && !senderIsConsole && !targetIsConsole) {
            Bukkit.getConsoleSender().sendMessage(spy);
        }
        this.conversations.record(from, to);
    }
}
