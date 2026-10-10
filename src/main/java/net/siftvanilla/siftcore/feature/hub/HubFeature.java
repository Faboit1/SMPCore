package net.siftvanilla.siftcore.feature.hub;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.Feature;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.ServerLinks;
import org.bukkit.entity.Player;

/**
 * The main menu: one dialog linking every system, opened with /menu (/hub), from the pause screen, or with the
 * quick actions key. Each feature contributes its entry to the {@link net.siftvanilla.siftcore.ui.hub.HubRegistry};
 * the menu shows the ones the player may use. Also publishes the server links shown in the pause screen.
 */
public final class HubFeature implements Feature {

    /** The pause-menu id of the spawn entry, the one entry that shows no screen of its own (it starts a teleport). */
    private static final String SPAWN = "spawn";

    private final Services services;
    private final Setting<HubSettings> settings;
    private final List<ServerLinks.ServerLink> addedLinks = new ArrayList<>();

    public HubFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/hub.yml", HubSettings::parse, problems);
        services.lang().register(HubMessages.class);
        services.permissions().declare("siftcore.command.menu", "Open the main menu with /menu", true);
    }

    @Override
    public String id() {
        return "hub";
    }

    @Override
    public void enable() {
        this.services.hub().register(new HubEntry("menu", 0, HubMessages.MENU_LABEL, HubMessages.MENU_DESCRIPTION, null, this::open));
        this.services.hub().register(new HubEntry("links", 95, HubMessages.LINKS, HubMessages.LINKS_DESCRIPTION, null,
            player -> {
                this.services.dialogs().markShown(player);
                player.showDialog(Dialog.SERVER_LINKS);
            }));
        for (String id : this.settings.get().pauseEntries()) {
            this.services.dialogs().route("hub/" + id, player -> openEntry(player, id));
        }
        this.services.dialogs().route("hub/menu", this::open);
        this.services.scheduler().global(this::publishLinks);
        this.settings.onReload(s -> this.services.scheduler().global(this::publishLinks));
    }

    private void openEntry(Player player, String id) {
        HubEntry entry = this.services.hub().get(id);
        if (entry == null || (entry.permission() != null && !player.hasPermission(entry.permission()))) {
            open(player);
            return;
        }
        entry.open().accept(player);
    }

    private void publishLinks() {
        ServerLinks links = Bukkit.getServer().getServerLinks();
        for (ServerLinks.ServerLink link : this.addedLinks) {
            links.removeLink(link);
        }
        this.addedLinks.clear();
        for (HubSettings.Link link : this.settings.get().links()) {
            this.addedLinks.add(links.addLink(Component.text(link.label()), link.url()));
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendLinks(links);
        }
    }

    /**
     * Opens the main menu: the player's money and shards on one line, then a short button per entry they may use, in
     * the entry's colour with its icon; what an entry opens is in its button's tooltip.
     */
    public void open(Player player) {
        Lang lang = this.services.lang();
        HubSettings settings = this.settings.get();
        Icons icons = lang.style().icons();
        List<Button> buttons = new ArrayList<>();
        for (HubEntry entry : this.services.hub().visibleTo(player)) {
            if (entry.id().equals("menu")) {
                continue;
            }
            Component label = MenuButtons.label(lang.plain(entry.label()), settings.look(entry.id()),
                name -> icons.has(name) ? icons.component(name) : Component.empty(), lang.style().palette().primary());
            Button button = Button.of(label, lang.get(entry.description()), submission -> entry.open().accept(submission.player()))
                .width(Templates.HALF);
            // Every entry opens its own screen except Spawn, which starts the teleport and shows nothing next.
            buttons.add(SPAWN.equals(entry.id()) ? button.closes() : button);
        }
        var body = lang.lines(HubMessages.BODY,
            Arg.money("balance", this.services.ledger().balance(player.getUniqueId(), Currency.MONEY)),
            Arg.shards("amount", this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS)));
        this.services.dialogs().show(player, this.services.templates().list(lang.get(HubMessages.TITLE), body, buttons,
            settings.columns(), null));
    }

    @Override
    public List<SiftCommand> commands() {
        return List.of(new SimpleCommand("menu", List.of("hub", "m"), "Opens the main menu", "siftcore.command.menu",
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission("siftcore.command.menu"))
                .executes(ctx -> {
                    Player player = this.services.commands().player(ctx);
                    if (player != null) {
                        open(player);
                    }
                    return CommandSupport.OK;
                })));
    }

    @Override
    public void disable() {
        if (!this.addedLinks.isEmpty()) {
            ServerLinks links = Bukkit.getServer().getServerLinks();
            for (ServerLinks.ServerLink link : this.addedLinks) {
                links.removeLink(link);
            }
            this.addedLinks.clear();
        }
    }

    @Override
    public void selfTest(SelfTest test) {
        test.check(id(), "pause menu entries exist", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : this.settings.get().pauseEntries()) {
                if (this.services.hub().get(id) == null) {
                    missing.add(id);
                }
            }
            return missing.isEmpty() ? null : "no feature provides " + String.join(", ", missing);
        });
        test.check(id(), "menu button icons resolve", () -> {
            Icons icons = this.services.lang().style().icons();
            List<String> unknown = new ArrayList<>();
            this.settings.get().buttons().forEach((id, look) -> {
                if (look.icon() != null && !icons.has(look.icon())) {
                    unknown.add(id + " (" + look.icon() + ")");
                }
            });
            return unknown.isEmpty() ? null : "unknown icons in features/hub.yml buttons: " + String.join(", ", unknown);
        });
        test.check(id(), "pause menu dialog is registered and tagged", () -> {
            var registry = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.DIALOG);
            var key = io.papermc.paper.registry.TypedKey.create(io.papermc.paper.registry.RegistryKey.DIALOG,
                net.siftvanilla.siftcore.SiftCoreBootstrap.HUB_DIALOG);
            if (registry.get(net.siftvanilla.siftcore.SiftCoreBootstrap.HUB_DIALOG) == null) {
                return "siftcore:hub is not in the dialog registry";
            }
            for (var tag : java.util.List.of(io.papermc.paper.registry.keys.tags.DialogTagKeys.PAUSE_SCREEN_ADDITIONS,
                io.papermc.paper.registry.keys.tags.DialogTagKeys.QUICK_ACTIONS)) {
                if (!registry.hasTag(tag) || !registry.getTag(tag).contains(key)) {
                    return "siftcore:hub is missing from " + tag.key().asString();
                }
            }
            return null;
        });
    }
}
