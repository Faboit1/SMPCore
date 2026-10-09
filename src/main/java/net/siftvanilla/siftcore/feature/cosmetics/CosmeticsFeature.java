package net.siftvanilla.siftcore.feature.cosmetics;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.link.TextChecks;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Cosmetic rank perks: chat colours ({@code /chatcolor}), nicknames ({@code /nick}, {@code /realname}), chat tags
 * ({@code /tags}, with monthly exclusives), rank and custom join and leave lines ({@code /joinmessage},
 * {@code /leavemessage}) and kill effects ({@code /killeffect}), all in one menu ({@code /cosmetics}). Each tier gets
 * more without any gameplay edge: Prospector gets tags, Baron vanilla colours, a nickname, more tags and a rank join
 * line, Tycoon hex colours and gradients, its own join messages, every tag and kill effects.
 * <p>
 * Other features show the perks through {@link Cosmetics} ({@link #cosmetics()}): chat (names, tags, message colour,
 * private messages, mentions by nickname), combat (names in death messages, kill effects) and the join and leave
 * messages of the extras feature. It uses chat's word filter and link check for player-written text
 * ({@link TextChecks}), the rank labels, the protected spawn (no kill effects there) and combat (every perk is
 * refused in combat). Two player settings let viewers turn other players' chat colours and kill effects off.
 */
public final class CosmeticsFeature implements Feature {

    /** Viewers see other players' chat colours (Chat group of the settings). */
    public static final Toggle CHAT_COLORS = new Toggle("show-chat-colors", true, CosmeticsMessages.SETTING_CHAT_COLORS,
        CosmeticsMessages.SETTING_CHAT_COLORS_DESCRIPTION, null);
    /** Viewers see kill effects (Display group of the settings). */
    public static final Toggle KILL_EFFECTS = new Toggle("show-kill-effects", true, CosmeticsMessages.SETTING_KILL_EFFECTS,
        CosmeticsMessages.SETTING_KILL_EFFECTS_DESCRIPTION, null);

    /** The chat colour switch's place in the Chat group: after chat's ten settings. */
    static final int CHAT_COLORS_ORDER = 11;

    private static final Duration SWEEP = Duration.ofMinutes(2);
    private static final UUID SELF_TEST_PLAYER = new UUID(0L, 7L);

    private final Services services;
    private final Setting<CosmeticsSettings> settings;
    private final Profiles profiles;
    private final CosmeticsService service;
    private final CosmeticsDialogs dialogs;
    private final CosmeticsCommands commands;
    private Task sweeper = Task.NONE;

    /**
     * @param ranks           rank labels in join lines (integrations)
     * @param checks          the chat word filter and link check, for nicknames and join messages (chat)
     * @param spawn           no kill effects in the protected spawn (spawn)
     * @param combat          every perk is refused in combat
     * @param chatCategory    the settings group of the chat colour switch (chat's group)
     * @param displayCategory the settings group of the kill effect switch (the display group)
     */
    public CosmeticsFeature(Services services, List<ConfigProblem> problems, Ranks ranks, TextChecks checks, SpawnArea spawn,
                            CombatStatus combat, SettingCategory chatCategory, SettingCategory displayCategory) {
        this.services = services;
        this.settings = services.configs().register("features/cosmetics.yml", CosmeticsSettings::parse, problems);
        services.lang().register(CosmeticsMessages.class);
        CosmeticsNodes.declare(services.permissions());
        // Offered while the server has chat colours: the chat colour switch after chat's own settings.
        services.settings().register(chatCategory == null ? SettingCategories.CHAT : chatCategory, CHAT_COLORS,
            SettingOptions.<Boolean>builder().order(CHAT_COLORS_ORDER).availableWhen(() -> this.settings.get().enabled()).build());
        if (displayCategory != null) {
            services.settings().register(displayCategory, KILL_EFFECTS);
        } else {
            services.settings().register(KILL_EFFECTS);
        }
        this.profiles = new Profiles(services.database(), services.plugin().getLogger());
        this.service = new CosmeticsService(services, this.settings, this.profiles, ranks, checks, spawn, CHAT_COLORS, KILL_EFFECTS);
        CosmeticsActions actions = new CosmeticsActions(services, this.service, combat);
        this.dialogs = new CosmeticsDialogs(services, this.service, actions);
        this.commands = new CosmeticsCommands(services, this.service, actions, this.dialogs);
    }

    @Override
    public String id() {
        return "cosmetics";
    }

    /** The perks as other features show them. */
    public Cosmetics cosmetics() {
        return this.service;
    }

    @Override
    public void enable() throws Exception {
        this.profiles.load();
        Bukkit.getPluginManager().registerEvents(new CosmeticsListener(this.services, this.service), this.services.plugin());
        this.services.hub().register(new HubEntry("cosmetics", 82, CosmeticsMessages.HUB_LABEL, CosmeticsMessages.HUB_DESCRIPTION,
            CosmeticsNodes.MENU, this.dialogs::menu));
        this.sweeper = this.services.scheduler().asyncTimer(this.service::sweep, SWEEP, SWEEP);
        registerPlaceholders();
    }

    private void registerPlaceholders() {
        var placeholders = this.services.placeholders();
        placeholders.register("nick", "Your nickname as plain text, empty without one",
            player -> online(player) instanceof Player p ? orEmpty(this.service.nick(p)) : "");
        placeholders.register("display_name", "Your nickname, or your name without one",
            player -> online(player) instanceof Player p ? plain(this.service.name(p)) : name(player));
        placeholders.register("display_name_mm", "Your nickname (or name) in its colours as MiniMessage, for the tab list",
            player -> online(player) instanceof Player p ? this.service.displayNameMiniMessage(p) : ChatStyle.NONE.miniMessage(name(player)));
        placeholders.register("tag", "Your chat tag as MiniMessage (colours and gradients), empty without one",
            player -> online(player) instanceof Player p && this.service.currentTag(p) instanceof ChatTag tag ? tag.source() : "");
        placeholders.register("tag_plain", "Your chat tag as plain text, empty without one",
            player -> online(player) instanceof Player p && this.service.currentTag(p) instanceof ChatTag tag ? tag.plain() : "");
        placeholders.register("tag_id", "The id of your chat tag, empty without one",
            player -> online(player) instanceof Player p && this.service.currentTag(p) instanceof ChatTag tag ? tag.id() : "");
        placeholders.register("chat_color", "Your chat colour: a colour name, #RRGGBB or #RRGGBB:#RRGGBB for a gradient; empty without one",
            player -> online(player) instanceof Player p ? this.service.chatStyle(p).serialize() : "");
        placeholders.register("kill_effect", "Your kill effect (hearts, flames...), empty without one",
            player -> online(player) instanceof Player p && this.service.killEffect(p) instanceof KillEffect effect ? effect.id() : "");
        placeholders.register("join_message", "Your custom join message as it shows now (with {name}), empty without one",
            player -> online(player) instanceof Player p ? orEmpty(this.service.customMessage(p, true)) : "");
        placeholders.register("leave_message", "Your custom leave message as it shows now (with {name}), empty without one",
            player -> online(player) instanceof Player p ? orEmpty(this.service.customMessage(p, false)) : "");
    }

    private static Player online(OfflinePlayer player) {
        return player == null ? null : player.getPlayer();
    }

    private static String name(OfflinePlayer player) {
        return player == null || player.getName() == null ? "" : player.getName();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Override
    public void disable() {
        // Every change is written through when it is made; the database flushes its queue when it closes. Players get
        // no quit event at shutdown, so the nickname holds of everyone online are renewed here.
        this.sweeper.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                this.service.seen(player, true);
            } catch (RuntimeException e) {
                this.services.plugin().getLogger().log(Level.WARNING, "Could not renew the nickname hold of " + player.getName(), e);
            }
        }
    }

    @Override
    public List<SiftCommand> commands() {
        return this.commands.all();
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "colour rules keep red and green", () -> {
            ColorRules rules = ColorRules.defaults(Palette.DEFAULT_ERROR, TextColor.color(0x1AFF1A));
            if (rules.check(TextColor.color(0xFF4B4B)).allowed() || rules.check(TextColor.color(0x00AA00)).allowed()
                || rules.check(TextColor.color(0x3CB371)).allowed() || rules.check(NamedTextColor.DARK_GRAY).allowed()) {
                return "a red, green or dark colour was allowed";
            }
            if (!rules.check(NamedTextColor.GOLD).allowed() || !rules.check(TextColor.color(0xFF6AD5), TextColor.color(0xB26BFF)).allowed()) {
                return "gold or the Tycoon gradient was refused";
            }
            return rules.check(TextColor.color(0xFFAA00), TextColor.color(0xFF55FF)).allowed() ? "a gradient through red was allowed" : null;
        });
        test.check(id(), "configured colours pass the rules", () -> {
            ColorRules rules = this.service.rules();
            for (NamedTextColor color : this.settings.get().colors().basic()) {
                if (!rules.check(color).allowed()) {
                    return NamedTextColor.NAMES.key(color) + " breaks the colour rules with this palette";
                }
            }
            for (CosmeticsSettings.Preset preset : this.settings.get().colors().presets()) {
                if (!rules.check(preset.style()).allowed()) {
                    return "the preset " + preset.id() + " breaks the colour rules with this palette";
                }
            }
            return null;
        });
        test.check(id(), "nickname rules", () -> {
            NickRules rules = this.settings.get().nicknames().rules();
            NickRules.Verdict ok = rules.check(SELF_TEST_PLAYER, "Night_Rider", name -> java.util.Optional.empty(),
                nick -> java.util.Optional.empty(), text -> false);
            NickRules.Verdict staff = rules.check(SELF_TEST_PLAYER, "xX_Admin_Xx", name -> java.util.Optional.empty(),
                nick -> java.util.Optional.empty(), text -> false);
            return ok.ok() && staff.problem() == NickRules.Problem.RESERVED ? null : "got " + ok + " and " + staff;
        });
        test.check(id(), "nickname holds", () -> {
            Duration hold = this.settings.get().nicknames().hold();
            long now = System.currentTimeMillis();
            boolean recent = NickRules.held(false, false, now - 1_000L, now, hold) || hold.isZero();
            boolean expired = NickRules.held(false, false, now - hold.toMillis() - 1_000L, now, hold);
            boolean shadowed = NickRules.held(true, true, now, now, hold);
            return recent && !expired && !shadowed ? null : "recent " + recent + ", expired " + expired + ", shadowed " + shadowed;
        });
        test.check(id(), "nicknames are unique", () -> {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String nick = this.service.nick(online);
                if (nick != null && !seen.add(nick.toLowerCase(java.util.Locale.ROOT))) {
                    return "two online players show the nickname " + nick;
                }
            }
            return null;
        });
        test.checkAsync(id(), "stored cosmetics match memory", () -> {
            int inMemory = this.profiles.size();
            return this.profiles.countRows().thenApply(rows -> rows == inMemory || this.services.database().pendingWrites() > 0
                ? null : rows + " rows in the table but " + inMemory + " in memory");
        });
    }
}
