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
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
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

    private final Services services;
    private final Setting<HubSettings> settings;
    private final List<ServerLinks.ServerLink> addedLinks = new ArrayList<>();

    public HubFeature(Services services, List<ConfigProblem> problems) {
        this.services = services;
        this.settings = services.configs().register("features/hub.yml", HubSettings::parse, problems);
        services.lang().register(HubMessages.class);
    }

    @Override
    public String id() {
        return "hub";
    }

    @Override
    public void enable() {
        this.services.hub().register(new HubEntry("menu", 0, HubMessages.MENU_LABEL, HubMessages.MENU_DESCRIPTION, null, this::open));
        this.services.hub().register(new HubEntry("links", 95, HubMessages.LINKS, HubMessages.LINKS_DESCRIPTION, null,
            player -> player.showDialog(Dialog.SERVER_LINKS)));
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
    }

    /** Opens the main menu. */
    public void open(Player player) {
        Lang lang = this.services.lang();
        List<Button> buttons = new ArrayList<>();
        for (HubEntry entry : this.services.hub().visibleTo(player)) {
            if (entry.id().equals("menu")) {
                continue;
            }
            buttons.add(Button.of(lang.get(entry.label()), lang.get(entry.description()), submission -> entry.open().accept(submission.player()))
                .width(150));
        }
        var body = lang.lines(HubMessages.BODY,
            Arg.text("name", player.getName()),
            Arg.money("balance", this.services.ledger().balance(player.getUniqueId(), Currency.MONEY)),
            Arg.number("shards", this.services.ledger().balance(player.getUniqueId(), Currency.SHARDS)));
        this.services.dialogs().show(player, this.services.templates().list(lang.get(HubMessages.TITLE), body, buttons,
            this.settings.get().columns(), null));
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
        test.check(id(), "pause menu dialog is registered", () -> {
            var registry = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.DIALOG);
            return registry.get(net.siftvanilla.siftcore.SiftCoreBootstrap.HUB_DIALOG) != null ? null : "siftcore:hub is not in the dialog registry";
        });
    }
}
