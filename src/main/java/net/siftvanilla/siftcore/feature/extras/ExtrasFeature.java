package net.siftvanilla.siftcore.feature.extras;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.dialog.Dialog;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Small things every SMP has: /rules, /help, /ping, /seen, /links, and quiet join/leave messages with a welcome
 * for first-time players. Vanished staff join and leave without a message. Players with a rank join line (Baron) or
 * their own join message (Tycoon) are announced with it, even when the plain messages are off ({@link Cosmetics});
 * a brand-new player always gets the welcome instead.
 * <p>
 * Two player settings: which join and leave lines a player reads ({@code join-leave-messages}, Server announcements),
 * and who sees in {@code /seen} when a player was last online (the shared {@code seen-privacy}, Privacy).
 */
public final class ExtrasFeature implements Feature, Listener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /**
     * Which of other players' join and leave lines a player reads: every line, only the welcome of brand-new players,
     * or none (Server announcements group). An unquoted {@code off} in {@code features/settings.yml} is the YAML
     * boolean {@code false}, so {@code false} reads as off too.
     */
    public static final Choice<JoinLines> JOIN_LEAVE_MESSAGES = Choice.ofEnum("join-leave-messages", JoinLines.class, JoinLines::id,
            JoinLines.ALL)
        .option(JoinLines.ALL, JoinLines.ALL.label())
        .option(JoinLines.FIRST_JOINS, JoinLines.FIRST_JOINS.label(), null, JoinLines.OFF.id())
        .option(JoinLines.OFF, JoinLines.OFF.label())
        .legacyValue("false", JoinLines.OFF.id())
        .text(ExtrasMessages.SETTING_JOIN_LINES, ExtrasMessages.SETTING_JOIN_LINES_DESCRIPTION).build();
    /** Staff who always see when a player was last online (the staff tools' {@code /whois} node). */
    static final String SEEN_BYPASS = "siftcore.staff.whois";

    private final Services services;
    private final Setting<ExtrasSettings> settings;
    private final VanishStatus vanish;
    private final Cosmetics cosmetics;

    /**
     * @param vanish    vanished staff join and leave without a message (staff tools)
     * @param cosmetics rank and custom join and leave lines, nicknames (cosmetics)
     */
    public ExtrasFeature(Services services, List<ConfigProblem> problems, VanishStatus vanish, Cosmetics cosmetics) {
        this.services = services;
        this.vanish = vanish;
        this.cosmetics = cosmetics;
        this.settings = services.configs().register("features/extras.yml", ExtrasSettings::parse, problems);
        services.lang().register(ExtrasMessages.class);
        registerSettings(services.settings(), this.settings::get, cosmetics::joinLines);
        var perms = services.permissions();
        perms.declare("siftcore.command.rules", "Read the rules with /rules", true);
        perms.declare("siftcore.command.help", "Open the help with /help", true);
        perms.declare("siftcore.command.ping", "See your ping with /ping", true);
        perms.declare("siftcore.command.ping.others", "See other players' ping", true);
        perms.declare("siftcore.command.seen", "See when a player was last online with /seen", true);
        perms.declare("siftcore.command.links", "Open the server links with /links", true);
    }

    /**
     * Registers the join and leave setting in Server announcements (offered while the server can show any join or
     * leave line: a plain one, a welcome, or a rank or custom line of the cosmetics; "new players only" while it
     * welcomes them) and declares that {@code /seen} acts on seen privacy.
     *
     * @param rankLines whether rank and custom join and leave lines are on (cosmetics)
     */
    static Registry.Entry<JoinLines> registerSettings(PlayerSettings registry, Supplier<ExtrasSettings> config,
                                                      BooleanSupplier rankLines) {
        Registry.Entry<JoinLines> entry = registry.register(SettingCategories.ANNOUNCEMENTS, JOIN_LEAVE_MESSAGES,
            SettingOptions.<JoinLines>builder().order(2)
                .availableWhen(() -> config.get().offersSetting(rankLines.getAsBoolean()))
                .optionAvailableWhen(JoinLines.FIRST_JOINS.id(), () -> config.get().firstJoinWelcome())
                .build());
        registry.reads(SharedSettings.SEEN_PRIVACY);
        return entry;
    }

    @Override
    public String id() {
        return "extras";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        // From the main menu, the rules' button returns to it; /rules just closes.
        this.services.hub().register(new HubEntry("rules", 94, ExtrasMessages.RULES_LABEL, ExtrasMessages.RULES_DESCRIPTION,
            null, player -> openRules(player, submission -> {
                HubEntry menu = this.services.hub().get("menu");
                if (menu != null) {
                    menu.open().accept(submission.player());
                }
            })));
    }

    /** The rules notice; {@code after} runs when its button is clicked, or null to close. */
    private void openRules(Player player, Button.Handler after) {
        var lang = this.services.lang();
        this.services.dialogs().show(player, this.services.templates().notice(lang.get(ExtrasMessages.RULES_TITLE),
            lang.lines(ExtrasMessages.RULES_BODY), lang.get(ExtrasMessages.RULES_BUTTON), after));
    }

    private void openHelp(Player player) {
        var lang = this.services.lang();
        List<Button> buttons = List.of(Button.of(lang.get(ExtrasMessages.HELP_MENU), submission -> {
            HubEntry menu = this.services.hub().get("menu");
            if (menu != null) {
                menu.open().accept(submission.player());
            }
        }).width(250));
        this.services.dialogs().show(player, this.services.templates().list(lang.get(ExtrasMessages.HELP_TITLE),
            lang.lines(ExtrasMessages.HELP_BODY), buttons, 1, null));
    }

    @Override
    public List<SiftCommand> commands() {
        CommandSupport support = this.services.commands();
        return List.of(
            new SimpleCommand("rules", List.of(), "Shows the server rules", "siftcore.command.rules",
                label -> Commands.literal(label).requires(CommandSupport.playerPermission("siftcore.command.rules")).executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        openRules(player, null);
                    }
                    return CommandSupport.OK;
                })),
            new SimpleCommand("help", List.of("?"), "Shows how to get started", "siftcore.command.help",
                label -> Commands.literal(label).requires(CommandSupport.playerPermission("siftcore.command.help")).executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        openHelp(player);
                    }
                    return CommandSupport.OK;
                })),
            new SimpleCommand("ping", List.of(), "Shows your connection latency", "siftcore.command.ping",
                label -> Commands.literal(label).requires(CommandSupport.permission("siftcore.command.ping"))
                    .executes(ctx -> {
                        Player player = support.player(ctx);
                        if (player != null) {
                            this.services.messenger().send(player, ExtrasMessages.PING_SELF, Arg.number("ping", player.getPing()));
                        }
                        return CommandSupport.OK;
                    })
                    .then(CommandSupport.onlinePlayer("player").requires(CommandSupport.permission("siftcore.command.ping.others"))
                        .executes(ctx -> {
                            Player target = support.online(ctx, "player");
                            if (target != null) {
                                this.services.messenger().send(ctx.getSource().getSender(), ExtrasMessages.PING_OTHER,
                                    Arg.text("name", target.getName()), Arg.number("ping", target.getPing()));
                            }
                            return CommandSupport.OK;
                        }))),
            new SimpleCommand("seen", List.of("lastseen"), "Shows when a player was last online", "siftcore.command.seen",
                label -> Commands.literal(label).requires(CommandSupport.permission("siftcore.command.seen"))
                    .then(support.knownPlayer("player").executes(ctx -> {
                        Optional<UUID> target = support.known(ctx, "player");
                        target.ifPresent(uuid -> seen(ctx.getSource().getSender(), uuid));
                        return CommandSupport.OK;
                    }))),
            new SimpleCommand("links", List.of("discord", "store", "website"), "Opens the server links", "siftcore.command.links",
                label -> Commands.literal(label).requires(CommandSupport.playerPermission("siftcore.command.links")).executes(ctx -> {
                    Player player = support.player(ctx);
                    if (player != null) {
                        player.showDialog(Dialog.SERVER_LINKS);
                    }
                    return CommandSupport.OK;
                })));
    }

    private void seen(org.bukkit.command.CommandSender sender, UUID uuid) {
        PlayerDirectory directory = this.services.directory();
        Optional<PlayerDirectory.Known> known = directory.get(uuid);
        if (known.isEmpty()) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        boolean visible = online != null && (!(sender instanceof Player viewer) || viewer.canSee(online));
        long now = System.currentTimeMillis();
        if (visible) {
            this.services.messenger().chat(sender, ExtrasMessages.SEEN_ONLINE, Arg.text("name", known.get().name()),
                Arg.time("since", Duration.ofMillis(Math.max(0, now - known.get().lastSeen()))));
            return;
        }
        String name = known.get().name();
        String first = DATE.format(Instant.ofEpochMilli(known.get().firstJoin()).atZone(ZoneId.systemDefault()));
        Duration ago = Duration.ofMillis(Math.max(0, now - known.get().lastSeen()));
        if (!(sender instanceof Player viewer) || seenShown(false, viewer.getUniqueId().equals(uuid), viewer.hasPermission(SEEN_BYPASS), false)) {
            this.services.messenger().chat(sender, ExtrasMessages.SEEN_OFFLINE, Arg.text("name", name), Arg.time("ago", ago), Arg.text("first", first));
            return;
        }
        // Who may see it is the player's choice (seen-privacy), read from storage when they are offline.
        UUID viewerId = viewer.getUniqueId();
        this.services.settings().lookup(uuid, SharedSettings.SEEN_PRIVACY).whenComplete((audience, error) -> {
            boolean allowed = error == null && audience != null && this.services.relations().allows(audience, uuid, viewerId);
            if (seenShown(false, false, false, allowed)) {
                this.services.messenger().chat(viewer, ExtrasMessages.SEEN_OFFLINE, Arg.text("name", name), Arg.time("ago", ago),
                    Arg.text("first", first));
            } else {
                this.services.messenger().chat(viewer, ExtrasMessages.SEEN_HIDDEN, Arg.text("name", name));
            }
        });
    }

    /**
     * Whether {@code /seen} tells when a player was last online: always to the console, the player themselves and staff
     * with {@link #SEEN_BYPASS}; to anyone else when they are in the player's {@code seen-privacy} audience.
     */
    static boolean seenShown(boolean console, boolean self, boolean staff, boolean allowed) {
        return console || self || staff || allowed;
    }

    /**
     * Join lines go to each online player by their {@code join-leave-messages} choice instead of the server's broadcast:
     * the welcome of a brand-new player (in the "new players only" choice too), the rank or custom join line of the
     * cosmetics, or the plain join line when the server shows it. The joining player always reads their own line and
     * the console logs every line. Vanished staff join without a line.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ExtrasSettings s = this.settings.get();
        event.joinMessage(null);
        if (this.vanish.vanished(player.getUniqueId())) {
            return;
        }
        boolean welcome = !player.hasPlayedBefore() && s.firstJoinWelcome();
        Component line;
        if (welcome) {
            line = this.services.lang().get(ExtrasMessages.FIRST_JOIN, Arg.text("name", player.getName()),
                Arg.number("number", this.services.directory().size()));
        } else {
            line = this.cosmetics.joinLine(player);
            if (line == null && s.joinMessages()) {
                line = this.services.lang().get(ExtrasMessages.JOIN, Arg.component("name", this.cosmetics.name(player)));
            }
        }
        announce(player, line, welcome, true);
    }

    /** Leave lines, like join lines: to each other player by their choice, and to the console. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(null);
        if (this.vanish.vanished(player.getUniqueId())) {
            return;
        }
        Component line = this.cosmetics.quitLine(player);
        if (line == null && this.settings.get().quitMessages()) {
            line = this.services.lang().get(ExtrasMessages.QUIT, Arg.component("name", this.cosmetics.name(player)));
        }
        announce(player, line, false, false);
    }

    /**
     * Sends a join or leave line to the console and to every online player whose choice shows it ({@code subject}
     * itself only when {@code toSubject}). The choice is read as the settings resolve it, whether or not the setting
     * is offered right now: a server lock wins, and "new players only" reads as off while the server welcomes nobody.
     * Sending is a packet per player, fine from the subject's thread.
     */
    private void announce(Player subject, Component line, boolean welcome, boolean toSubject) {
        if (line == null) {
            return;
        }
        Bukkit.getConsoleSender().sendMessage(line);
        PlayerSettings prefs = this.services.settings();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean self = viewer.getUniqueId().equals(subject.getUniqueId());
            if (receives(self, toSubject, prefs.get(viewer.getUniqueId(), JOIN_LEAVE_MESSAGES), welcome)) {
                viewer.sendMessage(line);
            }
        }
    }

    /**
     * Whether an online player gets a join or leave line: the player it is about only for their own join
     * ({@code toSubject}), everyone else by their {@code join-leave-messages} choice.
     *
     * @param welcome true for the welcome of a brand-new player
     */
    static boolean receives(boolean self, boolean toSubject, JoinLines choice, boolean welcome) {
        return self ? toSubject : choice.shows(welcome);
    }
}
