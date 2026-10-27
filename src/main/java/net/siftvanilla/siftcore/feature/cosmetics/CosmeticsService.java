package net.siftvanilla.siftcore.feature.cosmetics;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.TextChecks;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The cosmetics as the rest of SiftCore sees them ({@link Cosmetics}): what each player shows right now. Every
 * answer comes from the in-memory profiles, the config and permission checks, so it is safe on the async chat
 * thread. A stored choice is only shown while the player has its permission and it still passes the colour rules;
 * otherwise the default is shown and the choice is kept.
 */
final class CosmeticsService implements Cosmetics {

    /** Read stats for the staff status and the e2e tests. */
    record Counters(long played, long limited) {
    }

    /** How often the nickname hold of a player who stays online is renewed. */
    static final Duration HOLD_RENEWAL = Duration.ofHours(1);

    private final Services services;
    private final Lang lang;
    private final Setting<CosmeticsSettings> settings;
    private final Profiles profiles;
    private final Ranks ranks;
    private final TextChecks checks;
    private final SpawnArea spawn;
    private final Toggle chatColours;
    private final Toggle killEffects;
    private final RateGate killGate = new RateGate();
    private final RateGate previewGate = new RateGate();
    private final Map<UUID, Long> lastJoinLine = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastQuitLine = new ConcurrentHashMap<>();
    private final AtomicLong played = new AtomicLong();
    private final AtomicLong limited = new AtomicLong();

    CosmeticsService(Services services, Setting<CosmeticsSettings> settings, Profiles profiles, Ranks ranks, TextChecks checks,
                     SpawnArea spawn, Toggle chatColours, Toggle killEffects) {
        this.services = services;
        this.lang = services.lang();
        this.settings = settings;
        this.profiles = profiles;
        this.ranks = ranks;
        this.checks = checks;
        this.spawn = spawn;
        this.chatColours = chatColours;
        this.killEffects = killEffects;
    }

    CosmeticsSettings settings() {
        return this.settings.get();
    }

    Profiles profiles() {
        return this.profiles;
    }

    TextChecks checks() {
        return this.checks;
    }

    boolean on() {
        return this.settings.get().enabled();
    }

    /** The colour rules with the current palette. */
    ColorRules rules() {
        return this.settings.get().colors().rules(this.lang.style().palette());
    }

    Counters counters() {
        return new Counters(this.played.get(), this.limited.get());
    }

    /** The month monthly exclusive tags are checked against (the server's time zone). */
    static YearMonth month() {
        return YearMonth.now();
    }

    // ------------------------------------------------------------------ styles

    /** Whether a player may show a style with these permissions right now (and it passes the colour rules). */
    boolean usable(Player player, ChatStyle style, String basicNode, String premiumNode) {
        if (style.none() || !rules().check(style).allowed()) {
            return false;
        }
        if (style.premium()) {
            return player.hasPermission(premiumNode);
        }
        NamedTextColor named = (NamedTextColor) ((ChatStyle.Solid) style).color();
        return player.hasPermission(premiumNode) || player.hasPermission(basicNode) && this.settings.get().colors().basic().contains(named);
    }

    /** The chat colour a player shows now ({@link ChatStyle#NONE} without one). */
    ChatStyle chatStyle(Player player) {
        if (!on()) {
            return ChatStyle.NONE;
        }
        ChatStyle style = this.profiles.get(player.getUniqueId()).chatStyle();
        return usable(player, style, CosmeticsNodes.CHAT_COLOR, CosmeticsNodes.CHAT_COLOR_HEX) ? style : ChatStyle.NONE;
    }

    /** The colour a player's nickname shows in now. */
    ChatStyle nickStyle(Player player) {
        ChatStyle style = this.profiles.get(player.getUniqueId()).nickStyle();
        return usable(player, style, CosmeticsNodes.NICK, CosmeticsNodes.NICK_GRADIENT) ? style : ChatStyle.NONE;
    }

    /** The name of a style for menus: the vanilla colour's or preset's name, otherwise "Custom". */
    Component styleName(ChatStyle style) {
        if (style.none()) {
            return this.lang.get(CosmeticsMessages.NONE);
        }
        for (CosmeticsSettings.Preset preset : this.settings.get().colors().presets()) {
            if (preset.style().serialize().equals(style.serialize())) {
                return style.apply(preset.name());
            }
        }
        if (style instanceof ChatStyle.Solid solid && solid.vanilla()) {
            return style.apply(this.lang.plain(CosmeticsMessages.colorName((NamedTextColor) solid.color())));
        }
        return style.apply(this.lang.plain(style instanceof ChatStyle.Gradient ? CosmeticsMessages.COLOR_GRADIENT_NAME
            : CosmeticsMessages.COLOR_CUSTOM_NAME));
    }

    // ------------------------------------------------------------------ names

    @Override
    public String nick(Player player) {
        if (!on()) {
            return null;
        }
        String nick = this.profiles.get(player.getUniqueId()).nick();
        if (nick == null || !player.hasPermission(CosmeticsNodes.NICK)) {
            return null;
        }
        // A player who joined later with this name as their real name wins: the nickname is not shown.
        return shadowed(player.getUniqueId(), nick) ? null : nick;
    }

    @Override
    public Component name(Player player) {
        String nick = nick(player);
        if (nick == null) {
            return Component.text(player.getName());
        }
        return nickStyle(player).apply(nick)
            .hoverEvent(HoverEvent.showText(this.lang.get(CosmeticsMessages.NAME_HOVER, Arg.text("name", player.getName()))));
    }

    @Override
    public Component name(UUID player, String name) {
        Player online = Bukkit.getPlayer(player);
        return online == null ? Component.text(name) : name(online);
    }

    @Override
    public Optional<Player> byNick(String nick) {
        Optional<UUID> owner = this.profiles.nickOwner(nick);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayer(owner.get());
        return online != null && nick.equalsIgnoreCase(nick(online)) ? Optional.of(online) : Optional.empty();
    }

    /** Whether another player has a nickname as their real name (the real name wins; the nickname can't show). */
    private boolean shadowed(UUID holder, String nick) {
        Optional<UUID> named = this.services.directory().uuid(nick);
        return named.isPresent() && !named.get().equals(holder);
    }

    /**
     * Whether a player's stored nickname still keeps others from taking it ({@link NickRules#held}): they can show it
     * now (online with the permission), or could within {@code nicknames.hold}. Safe from any thread.
     */
    boolean holdsNick(UUID holder) {
        Profile profile = this.profiles.get(holder);
        if (profile.nick() == null) {
            return false;
        }
        Player online = Bukkit.getPlayer(holder);
        boolean showsNow = online != null && online.hasPermission(CosmeticsNodes.NICK);
        return NickRules.held(shadowed(holder, profile.nick()), showsNow, profile.nickSeen(), System.currentTimeMillis(),
            this.settings.get().nicknames().hold());
    }

    /** Who holds a nickname now (ignoring case): a stored nickname whose hold ran out is free. */
    Optional<UUID> nickHolder(String nick) {
        return this.profiles.nickOwner(nick).filter(this::holdsNick);
    }

    /**
     * Sets or clears a nickname (rules already checked). A nickname another player stored but no longer holds passes
     * to {@code player}: the other player loses it, is told (now or when they next join) and it is audited.
     *
     * @param actor who made the change, for the audit log ({@code player} itself, staff, or "console")
     * @return false when someone else still holds it
     */
    boolean claimNick(UUID player, String nick, ChatStyle style, String actor) {
        Profiles.Claim claim = this.profiles.setNick(player, nick, style, System.currentTimeMillis(), this::holdsNick);
        if (claim.displaced() != null) {
            this.services.audit().record(actor, "cosmetics.nick", claim.displaced().toString(),
                "lost '" + claim.lost() + "' to " + this.services.directory().name(player) + " (no longer held)");
            Player online = Bukkit.getPlayer(claim.displaced());
            if (online != null) {
                tellLostNick(online);
            }
        }
        return claim.ok();
    }

    /**
     * Records that an online player is able to show their nickname now, when they are. Joining and leaving always
     * record it ({@code always}); while they play it is renewed at most every {@link #HOLD_RENEWAL}, so a long
     * session doesn't let the hold run out underneath them.
     */
    void seen(Player player, boolean always) {
        Profile profile = this.profiles.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (profile.nick() == null || !always && now - profile.nickSeen() < HOLD_RENEWAL.toMillis()) {
            return;
        }
        if (player.hasPermission(CosmeticsNodes.NICK) && !shadowed(player.getUniqueId(), profile.nick())) {
            this.profiles.seeNick(player.getUniqueId(), now);
        }
    }

    /** Tells a player that another player took over their nickname, once. */
    void tellLostNick(Player player) {
        String lost = this.profiles.get(player.getUniqueId()).nickLost();
        if (lost == null) {
            return;
        }
        this.services.messenger().send(player, CosmeticsMessages.NICK_LOST, Arg.text("nick", lost));
        this.profiles.update(player.getUniqueId(), profile -> profile.withNickLost(null));
    }

    /** The nickname a player shows as MiniMessage (escaped text), or their name. */
    String displayNameMiniMessage(Player player) {
        String nick = nick(player);
        return nick == null ? ChatStyle.NONE.miniMessage(player.getName()) : nickStyle(player).miniMessage(nick);
    }

    // ------------------------------------------------------------------ tags

    /** Whether a player may pick a tag now. */
    boolean usable(Player player, ChatTag tag) {
        return tag.usable(player.hasPermission(tag.permission()), this.profiles.get(player.getUniqueId()).ownedTags(), month());
    }

    /** The tag a player shows now, or null. */
    ChatTag currentTag(Player player) {
        if (!on()) {
            return null;
        }
        String id = this.profiles.get(player.getUniqueId()).tag();
        ChatTag tag = id == null ? null : this.settings.get().tags().get(id);
        return tag != null && usable(player, tag) ? tag : null;
    }

    @Override
    public Component tag(Player player) {
        ChatTag tag = currentTag(player);
        if (tag == null) {
            return null;
        }
        return tag.description().isEmpty() ? tag.display()
            : tag.display().hoverEvent(HoverEvent.showText(this.lang.get(CosmeticsMessages.TAG_HOVER, Arg.text("description", tag.description()))));
    }

    // ------------------------------------------------------------------ chat colour

    @Override
    public Component paint(Player sender, Component message) {
        ChatStyle style = chatStyle(sender);
        return style.none() ? message : style.paint(message, this.lang.style().palette().primary());
    }

    @Override
    public boolean showsChatColours(UUID viewer) {
        return this.services.settings().enabled(viewer, this.chatColours);
    }

    // ------------------------------------------------------------------ join and leave lines

    @Override
    public Component joinLine(Player player) {
        return line(player, true);
    }

    @Override
    public Component quitLine(Player player) {
        return line(player, false);
    }

    @Override
    public boolean joinLines() {
        CosmeticsSettings settings = this.settings.get();
        return settings.enabled() && settings.join().enabled();
    }

    private Component line(Player player, boolean join) {
        CosmeticsSettings settings = this.settings.get();
        if (!settings.enabled() || !settings.join().enabled() || !player.hasPermission(CosmeticsNodes.JOIN)) {
            return null;
        }
        Map<UUID, Long> last = join ? this.lastJoinLine : this.lastQuitLine;
        long now = System.currentTimeMillis();
        long cooldown = settings.join().cooldown().toMillis();
        Long previous = last.get(player.getUniqueId());
        if (previous != null && now - previous < cooldown) {
            return null;
        }
        last.put(player.getUniqueId(), now);
        return render(player, join);
    }

    /** The line a player's join or leave shows now (no cooldown; also used for the preview). */
    Component render(Player player, boolean join) {
        Profile profile = this.profiles.get(player.getUniqueId());
        return render(player, join, join ? profile.joinMessage() : profile.leaveMessage());
    }

    /** Whether a custom message may show for a player now: they have the permission and it passes today's checks. */
    private boolean showable(Player player, String custom) {
        return custom != null && player.hasPermission(CosmeticsNodes.JOIN_CUSTOM)
            && JoinText.check(custom, this.settings.get().join().maxLength(), this.checks::link, this.checks::filtered) == JoinText.Problem.OK;
    }

    /**
     * A player's custom join or leave message (with {@code {name}}) when it would show now: cosmetics and join lines
     * are on, they have the permission and it passes today's word filter and link check. Null otherwise.
     */
    String customMessage(Player player, boolean join) {
        CosmeticsSettings settings = this.settings.get();
        if (!settings.enabled() || !settings.join().enabled()) {
            return null;
        }
        Profile profile = this.profiles.get(player.getUniqueId());
        String custom = join ? profile.joinMessage() : profile.leaveMessage();
        return showable(player, custom) ? custom : null;
    }

    /** The line with this custom message ({@code custom} null: the rank line), as everyone would see it. */
    Component render(Player player, boolean join, String custom) {
        Component rank = rank(player);
        Component name = name(player).colorIfAbsent(this.lang.style().palette().primary());
        if (showable(player, custom)) {
            Component message = JoinText.render(custom, name);
            return rank == null ? this.lang.get(CosmeticsMessages.CUSTOM_LINE, Arg.component("message", message))
                : this.lang.get(CosmeticsMessages.CUSTOM_RANK_LINE, Arg.component("rank", rank), Arg.component("message", message));
        }
        if (rank == null) {
            return this.lang.get(join ? CosmeticsMessages.JOIN_LINE : CosmeticsMessages.QUIT_LINE, Arg.component("name", name));
        }
        return this.lang.get(join ? CosmeticsMessages.JOIN_RANK_LINE : CosmeticsMessages.QUIT_RANK_LINE, Arg.component("rank", rank),
            Arg.component("name", name));
    }

    /** The player's rank in its colour, or null without a rank label. */
    private Component rank(Player player) {
        String label = this.ranks.label(player.getUniqueId());
        if (label == null || label.isBlank()) {
            return null;
        }
        Component styled = this.ranks.component(player);
        return styled == null || PlainTextComponentSerializer.plainText().serialize(styled).isBlank() ? Component.text(label) : styled;
    }

    // ------------------------------------------------------------------ kill effects

    /** The kill effect a player has picked and may use now, or null (also while cosmetics or kill effects are off). */
    KillEffect killEffect(Player player) {
        CosmeticsSettings settings = this.settings.get();
        if (!settings.enabled() || !settings.killEffects().enabled()) {
            return null;
        }
        KillEffect effect = KillEffect.byId(this.profiles.get(player.getUniqueId()).killEffect());
        return effect != null && usable(player, effect) ? effect : null;
    }

    /** Whether a player may pick an effect now. */
    boolean usable(Player player, KillEffect effect) {
        return this.settings.get().killEffects().effects().contains(effect) && player.hasPermission(CosmeticsNodes.killEffect(effect));
    }

    @Override
    public void kill(Player killer, Player victim, Location at) {
        CosmeticsSettings settings = this.settings.get();
        if (!settings.enabled() || !settings.killEffects().enabled() || at == null || at.getWorld() == null) {
            return;
        }
        KillEffect effect = killEffect(killer);
        if (effect == null || this.spawn.contains(at)) {
            return;
        }
        RateGate.Outcome outcome = this.killGate.tryAcquire(killer.getUniqueId(), System.currentTimeMillis(),
            settings.killEffects().cooldown().toMillis(), settings.killEffects().maxPerSecond());
        if (outcome != RateGate.Outcome.OK) {
            this.limited.incrementAndGet();
            return;
        }
        Location where = at.clone();
        int range = settings.killEffects().range();
        // A tick later, on the thread that owns the spot, so the effect starts with the death animation.
        this.services.scheduler().regionLater(where, () -> {
            Set<Player> near = new LinkedHashSet<>(where.getNearbyPlayers(range));
            // The victim sees the spot behind the death screen, whatever the search counts as nearby.
            if (victim.isOnline() && where.getWorld().equals(victim.getWorld())) {
                near.add(victim);
            }
            List<Player> receivers = new ArrayList<>(near.size());
            for (Player player : near) {
                if (wantsKillEffects(player)) {
                    receivers.add(player);
                }
            }
            // The visual bolt is seen by every client that tracks it, further than the effect range: it only strikes
            // when nobody who could see it turned kill effects off. The victim always counts (while respawning they
            // may be missing from the world's player list).
            boolean bolt = effect == KillEffect.LIGHTNING && receivers.size() == near.size() && boltClear(where, range);
            effect.play(where, receivers, bolt);
            this.played.incrementAndGet();
        }, 2);
    }

    private boolean wantsKillEffects(Player player) {
        return this.services.settings().enabled(player.getUniqueId(), this.killEffects);
    }

    /**
     * Whether nobody who could see a lightning bolt at {@code where} turned kill effects off (see {@link BoltRule}).
     * Reads the positions of the world's players without touching their regions; players far enough away to be in
     * another region are outside the reach anyway.
     */
    private boolean boltClear(Location where, int range) {
        World world = where.getWorld();
        double reach = BoltRule.reach(range, Math.max(world.getViewDistance(), world.getSendViewDistance()));
        return BoltRule.clear(where.getX(), where.getZ(), reach, world.getPlayers(), player -> player.getLocation().getX(),
            player -> player.getLocation().getZ(), this::wantsKillEffects);
    }

    /**
     * Shows a kill effect to the player alone, where they stand (picking one in /killeffect). Rate limited like
     * kills; returns false when it was held back.
     */
    boolean preview(Player player, KillEffect effect) {
        CosmeticsSettings settings = this.settings.get();
        if (this.previewGate.tryAcquire(player.getUniqueId(), System.currentTimeMillis(), 1_500L,
            Math.max(1, settings.killEffects().maxPerSecond())) != RateGate.Outcome.OK) {
            return false;
        }
        this.services.scheduler().entity(player, () -> effect.play(player.getLocation(), List.of(player), false), null);
        return true;
    }

    // ------------------------------------------------------------------ upkeep

    /**
     * Drops memory of players who are gone and whose limits have run out, and renews the nickname holds of players who
     * have been online for a while. Runs on the async timer: it reads memory and permissions only and queues writes.
     */
    void sweep() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            seen(player, false);
        }
        long now = System.currentTimeMillis();
        long keep = Math.max(this.settings.get().join().cooldown().toMillis(), this.settings.get().killEffects().cooldown().toMillis()) + 60_000L;
        this.lastJoinLine.values().removeIf(at -> now - at > keep);
        this.lastQuitLine.values().removeIf(at -> now - at > keep);
        this.killGate.sweep(now, keep);
        this.previewGate.sweep(now, keep);
    }
}
