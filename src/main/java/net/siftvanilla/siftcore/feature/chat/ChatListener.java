package net.siftvanilla.siftcore.feature.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.AfkStatus;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
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
 *       low priority). Chat lock and slow mode apply; players who ignore the sender, who turned public chat off, or
 *       who hide brand-new players when the sender is one stop being viewers; {@code [item]} becomes the held item;
 *       and the line gets its format: rank, chat tag, the name (or nickname) with its hover card and profile click,
 *       and the message. Each reader gets their own version of the line ({@link Lines}).</li>
 *   <li>{@link EventPriority#MONITOR}: once nothing can cancel it any more, mentioned viewers are alerted the way
 *       they chose, if the sender may ping them.</li>
 * </ol>
 * Player text is only ever inserted as plain text components, never parsed.
 */
final class ChatListener implements Listener {

    /** Staff who may read every balance (the economy feature's admin node). */
    static final String BALANCE_BYPASS = "siftcore.admin.eco";

    /** The links to other features that chat consults. */
    record Links(MuteStatus mutes, AfkStatus afk, VanishStatus vanish, Cosmetics cosmetics, StatsRecorder stats) {
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
    /** Players told this session that their public chat is off. */
    private final Set<UUID> reminded = ConcurrentHashMap.newKeySet();

    ChatListener(Services services, Setting<ChatSettings> settings, MessageScreen screen, MessageScreen privateScreen,
                 ChatModeration moderation, IgnoreList ignores, PlayerCards cards, HeldItems items, Links links) {
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
        ChatSettings settings = this.settings.get();
        PlayerSettings prefs = this.services.settings();
        boolean unignorable = player.hasPermission(ChatNodes.UNIGNORABLE);
        if (!unignorable) {
            event.viewers().removeIf(viewer -> viewer instanceof Player other && this.ignores.ignores(other.getUniqueId(), id));
        }
        StatsRecorder stats = this.links.stats();
        boolean senderNew = ChatRules.newPlayer(stats.get(id, StatsRecorder.Stat.PLAYTIME_SECONDS), settings.newPlayerPlaytime(),
            stats == StatsRecorder.NONE || unignorable || bypass);
        event.viewers().removeIf(viewer -> viewer instanceof Player other && ChatRules.hides(other.getUniqueId().equals(id),
            prefs.get(other.getUniqueId(), ChatFeature.PUBLIC_CHAT), prefs.get(other.getUniqueId(), ChatFeature.CHAT_HIDE_NEW), senderNew));
        if (!prefs.get(id, ChatFeature.PUBLIC_CHAT) && this.reminded.add(id)) {
            this.services.messenger().send(player, ChatRules.publicOffReminder(prefs.locked(ChatFeature.PUBLIC_CHAT),
                prefs.hidden(ChatFeature.PUBLIC_CHAT)));
        }
        Component message = event.message();
        if (settings.itemTag() && ItemTag.present(ChatText.plain(message)) && player.hasPermission(ChatNodes.ITEM)) {
            Component item = this.items.held(player);
            if (item != null) {
                message = ItemTag.replaceFirst(message, item);
                event.message(message);
            }
        }
        Component rank = this.cards.rankComponent(id, player);
        net.siftvanilla.siftcore.core.player.options.Audience balance = prefs.get(id, SharedSettings.BALANCE_PRIVACY);
        event.renderer(new Lines(id, rank, settings.hoverCard(), balance, this.cards.profileCommand(), settings));
    }

    /**
     * Renders the public line for each reader: the strict filter for readers who turned it on, the reader's own name
     * bold or underlined where the line mentions them, the sender's chat colour unless the reader turned chat colours
     * off, the hover card with or without the balance (the sender's balance privacy), and a name that opens the
     * sender's profile for readers who may open profiles (a private message for the others). Lines that come out the
     * same are rendered once per message and shared; a line that highlights a reader is theirs alone. The console gets
     * the full line. The message is painted at render time, so changes later listeners made to it are kept.
     */
    private final class Lines implements ChatRenderer {

        /**
         * What decides a reader's line, apart from a highlight.
         *
         * @param money the money format of the balance on the card (the server's way when the card shows none)
         */
        private record Variant(boolean colours, boolean strict, boolean balance, boolean profile, MoneyStyle money) {
        }

        /** What decides the sender's name for a reader: the balance on the card, its format, and the click. */
        private record NameKind(boolean balance, boolean profile, MoneyStyle money) {
        }

        private final UUID sender;
        private final Component rank;
        private final boolean withCard;
        private final net.siftvanilla.siftcore.core.player.options.Audience balance;
        private final String profileCommand;
        private final ChatSettings settings;
        private final Map<Variant, Component> rendered = new HashMap<>();
        /** The sender's name per kind of reader, built when first needed. */
        private final Map<NameKind, Component> names = new HashMap<>();
        private Component lastMessage;
        private Component strictMessage;
        private String typed;
        private String typedStrict;

        /**
         * @param withCard       whether names carry the hover card
         * @param balance        who may see the sender's balance on the card
         * @param profileCommand what a click on the name runs before the name, or null without profiles
         */
        Lines(UUID sender, Component rank, boolean withCard, net.siftvanilla.siftcore.core.player.options.Audience balance,
              String profileCommand, ChatSettings settings) {
            this.sender = sender;
            this.rank = rank;
            this.withCard = withCard;
            this.balance = balance;
            this.profileCommand = profileCommand;
            this.settings = settings;
        }

        @Override
        public synchronized Component render(Player source, Component sourceDisplayName, Component message, Audience viewer) {
            if (message != this.lastMessage) {
                this.lastMessage = message;
                this.rendered.clear();
                this.strictMessage = null;
                this.typed = null;
                this.typedStrict = null;
            }
            if (!(viewer instanceof Player reader)) {
                return this.rendered.computeIfAbsent(new Variant(true, false, true, this.profileCommand != null, MoneyStyle.SERVER),
                    v -> build(source, v, null, false, null));
            }
            UUID id = reader.getUniqueId();
            boolean profile = ChatRules.readerProfileCommand(this.profileCommand, reader.hasPermission(PlayerCards.PROFILE_PERMISSION)) != null;
            if (id.equals(this.sender)) {
                return this.rendered.computeIfAbsent(new Variant(true, false, true, profile, cardMoney(true, id)),
                    v -> build(source, v, null, false, null));
            }
            PlayerSettings prefs = ChatListener.this.services.settings();
            boolean colours = ChatListener.this.links.cosmetics().showsChatColours(id);
            boolean strict = this.settings.strictFilter().size() > 0 && prefs.get(id, ChatFeature.CHAT_FILTER_STRICT);
            // Readers outside the sender's balance audience get a card without the balance line.
            boolean balance = !this.withCard || this.balance == net.siftvanilla.siftcore.core.player.options.Audience.EVERYONE
                || ChatRules.showsBalance(false, ChatListener.this.services.relations().allows(this.balance, this.sender, id),
                    reader.hasPermission(BALANCE_BYPASS));
            Variant variant = new Variant(colours, strict, balance, profile, cardMoney(balance, id));
            if (this.settings.mentions()) {
                TextDecoration decoration = prefs.get(id, ChatFeature.MENTION_HIGHLIGHT).decoration();
                if (decoration != null) {
                    List<String> names = names(reader);
                    boolean plain = this.settings.plainNameMentions() && prefs.get(id, ChatFeature.MENTION_PLAIN_NAMES);
                    if (!Mentions.matches(typed(strict), names, plain, this.settings.minPlainLength()).isEmpty()) {
                        return build(source, variant, names, plain, decoration);
                    }
                }
            }
            return this.rendered.computeIfAbsent(variant, v -> build(source, v, null, false, null));
        }

        private Component build(Player source, Variant variant, List<String> highlight, boolean plain, TextDecoration decoration) {
            Component body = variant.strict() ? strictMessage() : this.lastMessage;
            if (highlight != null) {
                body = ReaderText.highlight(body, highlight, plain, this.settings.minPlainLength(), decoration);
            }
            Component painted = variant.colours() ? ChatListener.this.links.cosmetics().paint(source, body) : body;
            return line(this.rank, name(source, variant.balance(), variant.profile(), variant.money()), painted);
        }

        /**
         * The money format of the balance on a reader's card: theirs (the card is rendered for each reader), or the
         * server's way when the card shows no balance, so those readers keep sharing one line.
         */
        private MoneyStyle cardMoney(boolean balance, UUID reader) {
            return this.withCard && balance ? ChatListener.this.services.lang().styleOf(reader) : MoneyStyle.SERVER;
        }

        /** The sender's name for a kind of reader (the same for every message of this line). */
        private Component name(Player source, boolean balance, boolean profile, MoneyStyle money) {
            return this.names.computeIfAbsent(new NameKind(balance, profile, money), kind -> ChatListener.this.services.lang().within(
                money, () -> ChatListener.this.cards.taggedName(source, this.withCard, balance, profile ? this.profileCommand : null)));
        }

        private Component strictMessage() {
            if (this.strictMessage == null) {
                this.strictMessage = ReaderText.filterTyped(this.lastMessage, this.settings::strict);
            }
            return this.strictMessage;
        }

        /** The typed text of the message a reader gets (items left out), for finding mentions of them. */
        private String typed(boolean strict) {
            if (strict) {
                if (this.typedStrict == null) {
                    this.typedStrict = ItemTag.typedText(strictMessage());
                }
                return this.typedStrict;
            }
            if (this.typed == null) {
                this.typed = ItemTag.typedText(this.lastMessage);
            }
            return this.typed;
        }
    }

    /** The names a player can be mentioned by: their name and the nickname they show. */
    private List<String> names(Player player) {
        List<String> names = new ArrayList<>(2);
        names.add(player.getName());
        String nick = this.links.cosmetics().nick(player);
        if (nick != null) {
            names.add(nick);
        }
        return names;
    }

    /** One formatted chat line. */
    Component line(Component rank, Component name, Component message) {
        return rank == null
            ? this.lang.get(ChatMessages.FORMAT_UNRANKED, Arg.component("name", name), Arg.component("message", message))
            : this.lang.get(ChatMessages.FORMAT, Arg.component("rank", rank), Arg.component("name", name), Arg.component("message", message));
    }

    /**
     * Alerts the viewers the message mentions, each the way they chose ({@code mentions}: above the hotbar, in chat,
     * as a title or not at all) with their mention sound, when the mention was written with {@code @} or they want
     * pings on their bare name too, and the sender is in their "who can ping me" audience. One sender alerts the same
     * player at most once per {@code mentions.cooldown}. The sender is told when someone they mentioned is AFK.
     */
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
        // Players can be mentioned by the nickname they show, too.
        for (Player viewer : List.copyOf(viewers.values())) {
            String nick = this.links.cosmetics().nick(viewer);
            if (nick != null) {
                viewers.putIfAbsent(nick, viewer);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        // Each mentioned player once, in order, remembering whether any of their mentions had an @.
        Map<UUID, Boolean> withAt = new LinkedHashMap<>();
        Map<UUID, Player> targets = new HashMap<>();
        for (Mentions.Match match : Mentions.matches(ItemTag.typedText(event.message()), viewers.keySet(), settings.plainNameMentions(),
            settings.minPlainLength())) {
            Player target = viewers.get(match.name());
            withAt.merge(target.getUniqueId(), match.at(), Boolean::logicalOr);
            targets.putIfAbsent(target.getUniqueId(), target);
        }
        PlayerSettings prefs = this.services.settings();
        UUID senderId = sender.getUniqueId();
        for (Map.Entry<UUID, Boolean> mention : withAt.entrySet()) {
            UUID targetId = mention.getKey();
            Player target = targets.get(targetId);
            AlertStyle style = prefs.get(targetId, ChatFeature.MENTIONS);
            boolean allowed = this.services.relations().allows(prefs.get(targetId, ChatFeature.MENTION_FROM), targetId, senderId);
            if (ChatRules.alerts(style, mention.getValue(), prefs.get(targetId, ChatFeature.MENTION_PLAIN_NAMES), allowed)
                && this.services.cooldowns().tryUse(senderId, "chat-mention:" + targetId, settings.mentionCooldown()).isZero()) {
                this.services.messenger().alert(target, style, ChatMessages.MENTIONED, Arg.component("name", this.links.cosmetics().name(sender)));
                this.services.messenger().sounds().ping(target, prefs.get(targetId, SharedSettings.SOUND_MENTION));
            }
            if (this.links.afk().afk(targetId) && visible(sender, target)) {
                this.services.messenger().send(sender, ChatMessages.MENTION_AFK, Arg.component("name", this.links.cosmetics().name(target)));
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
        this.reminded.remove(id);
    }
}
