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
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
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
 * for first-time players.
 */
public final class ExtrasFeature implements Feature, Listener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final Services services;
    private final Setting<ExtrasSettings> settings;

    public ExtrasFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/extras.yml", ExtrasSettings::parse, problems);
        services.lang().register(ExtrasMessages.class);
        var perms = services.permissions();
        perms.declare("siftcore.command.rules", "Read the rules with /rules", true);
        perms.declare("siftcore.command.help", "Open the help with /help", true);
        perms.declare("siftcore.command.ping", "See your ping with /ping", true);
        perms.declare("siftcore.command.ping.others", "See other players' ping", true);
        perms.declare("siftcore.command.seen", "See when a player was last online with /seen", true);
        perms.declare("siftcore.command.links", "Open the server links with /links", true);
    }

    @Override
    public String id() {
        return "extras";
    }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, this.services.plugin());
        this.services.hub().register(new HubEntry("rules", 94, ExtrasMessages.RULES_LABEL, ExtrasMessages.RULES_DESCRIPTION,
            null, this::openRules));
    }

    private void openRules(Player player) {
        var lang = this.services.lang();
        this.services.dialogs().show(player, this.services.templates().notice(lang.get(ExtrasMessages.RULES_TITLE),
            lang.lines(ExtrasMessages.RULES_BODY), lang.get(ExtrasMessages.RULES_BUTTON), null));
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
                        openRules(player);
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
        String first = DATE.format(Instant.ofEpochMilli(known.get().firstJoin()).atZone(ZoneId.systemDefault()));
        this.services.messenger().chat(sender, ExtrasMessages.SEEN_OFFLINE, Arg.text("name", known.get().name()),
            Arg.time("ago", Duration.ofMillis(Math.max(0, now - known.get().lastSeen()))), Arg.text("first", first));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ExtrasSettings s = this.settings.get();
        if (!player.hasPlayedBefore() && s.firstJoinWelcome()) {
            event.joinMessage(this.services.lang().get(ExtrasMessages.FIRST_JOIN, Arg.text("name", player.getName()),
                Arg.number("number", this.services.directory().size())));
        } else if (s.joinMessages()) {
            event.joinMessage(this.services.lang().get(ExtrasMessages.JOIN, Arg.text("name", player.getName())));
        } else {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (this.settings.get().quitMessages()) {
            event.quitMessage(this.services.lang().get(ExtrasMessages.QUIT, Arg.text("name", event.getPlayer().getName())));
        } else {
            event.quitMessage(null);
        }
    }
}
