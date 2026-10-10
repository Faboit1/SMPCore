package net.siftvanilla.siftcore.feature.bounties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import net.siftvanilla.siftcore.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The bounty dialogs, in the dialog style (buttons, explanations in tooltips, nothing paged): the list of the biggest
 * bounties, one button per target ("Name: $5,000", tooltip: who put it up), a target's details (the amounts the player
 * needs to decide) and the form to place or add to a bounty. The claim tax is named only while there is one. Every
 * dialog shows the state at the moment it opens; actions check again.
 */
final class BountyViews {

    private final Services services;
    private final Setting<BountiesSettings> settings;
    private final BountyActions actions;

    BountyViews(Services services, Setting<BountiesSettings> settings, BountyActions actions) {
        this.services = services;
        this.settings = settings;
        this.actions = actions;
    }

    private Lang lang() {
        return this.services.lang();
    }

    private BountyBook book() {
        return this.actions.service().book();
    }

    /** "1 player" or "3 players". */
    String sponsors(int count) {
        return count == 1 ? lang().plain(BountiesMessages.SPONSORS_ONE)
            : lang().plain(BountiesMessages.SPONSORS_MANY, Arg.number("count", count));
    }

    /** The ranked lines of the biggest bounties (the console's /bounties). */
    List<Component> ranking(List<BountyBook.Bounty> top) {
        List<Component> lines = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            BountyBook.Bounty bounty = top.get(i);
            lines.add(lang().get(BountiesMessages.LIST_LINE, Arg.number("rank", i + 1),
                Arg.text("name", this.services.directory().name(bounty.target())), Arg.money("total", bounty.total()),
                Arg.text("sponsors", sponsors(bounty.sponsors()))));
        }
        return lines;
    }

    /**
     * The list of the biggest bounties: a button per target showing the total, the viewer's own bounty as the one
     * status line, and Place a bounty. {@code fromHub} makes Back return to the main menu.
     */
    void openList(Player viewer, boolean fromHub) {
        BountiesSettings s = this.settings.get();
        List<BountyBook.Bounty> top = book().top(s.listSize());
        List<Component> lines = new ArrayList<>();
        if (top.isEmpty()) {
            lines.add(lang().get(BountiesMessages.LIST_EMPTY));
        } else if (book().targets() > top.size()) {
            lines.add(lang().get(BountiesMessages.LIST_CAPPED, Arg.text("count", Lang.number(top.size()))));
        }
        long own = book().total(viewer.getUniqueId());
        if (own > 0) {
            lines.add(lang().get(BountiesMessages.LIST_YOURS, Arg.money("total", own)));
        }
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            BountyBook.Bounty bounty = top.get(i);
            UUID target = bounty.target();
            Component tooltip = lang().get(BountiesMessages.LIST_TOOLTIP, Arg.text("rank", Lang.number(i + 1L)),
                Arg.text("sponsors", sponsors(bounty.sponsors())));
            buttons.add(this.services.templates().choiceButton(Component.text(this.services.directory().name(target)),
                lang().moneyComponent(bounty.total()), tooltip, submission -> openDetails(submission.player(), target, fromHub)));
        }
        if (viewer.hasPermission(BountyCommands.PLACE)) {
            buttons.add(Button.of(lang().get(BountiesMessages.LIST_PLACE), lang().get(BountiesMessages.LIST_PLACE_TOOLTIP),
                submission -> openForm(submission.player(), "", "", fromHub)));
        }
        this.services.dialogs().show(viewer, this.services.templates().grid(lang().get(BountiesMessages.LIST_TITLE), lines,
            buttons, fromHub ? submission -> openMenu(submission.player()) : null));
    }

    /** One target's bounty: total, sponsors, the viewer's part, when it starts running out. */
    void openDetails(Player viewer, UUID target, boolean fromHub) {
        BountiesSettings s = this.settings.get();
        String name = this.services.directory().name(target);
        BountyBook.Bounty bounty = book().get(target);
        boolean self = viewer.getUniqueId().equals(target);
        List<Component> lines = new ArrayList<>();
        if (bounty == null) {
            lines.add(lang().get(BountiesMessages.DETAILS_NONE, Arg.text("name", name)));
        } else {
            lines.add(lang().get(BountiesMessages.DETAILS_TOTAL, Arg.money("total", bounty.total())));
            lines.add(lang().get(BountiesMessages.DETAILS_SPONSORS, Arg.text("sponsors", sponsors(bounty.sponsors()))));
            long mine = bounty.from(viewer.getUniqueId());
            if (mine > 0) {
                lines.add(lang().get(BountiesMessages.DETAILS_YOURS, Arg.money("amount", mine)));
            }
            long runsOut = BountyMath.expiresAt(bounty.oldest(), s.expireAfter()) - System.currentTimeMillis();
            lines.add(lang().get(BountiesMessages.DETAILS_EXPIRY, time(Duration.ofMillis(Math.max(0, runsOut)))));
        }
        List<Button> buttons = new ArrayList<>();
        if (!self && viewer.hasPermission(BountyCommands.PLACE)) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(lang().get(BountiesMessages.DETAILS_CLAIM_TOOLTIP, Arg.text("name", name)));
            if (s.taxPercent() > 0) {
                tooltip.add(lang().get(BountiesMessages.DETAILS_TAX_TOOLTIP, Arg.text("tax", Lang.number(s.taxPercent()))));
            }
            buttons.add(Button.of(lang().get(bounty == null ? BountiesMessages.DETAILS_PLACE : BountiesMessages.DETAILS_ADD),
                Templates.lines(tooltip), submission -> openForm(submission.player(), name, "", fromHub)));
        }
        this.services.dialogs().show(viewer, this.services.templates().column(
            lang().get(BountiesMessages.DETAILS_TITLE, Arg.text("name", name)), lines, buttons,
            submission -> openList(submission.player(), fromHub)));
    }

    /** The form to place a bounty (or add to one), with the name and amount pre-filled. */
    void openForm(Player viewer, String name, String amount, boolean fromHub) {
        BountiesSettings s = this.settings.get();
        View form = this.services.templates().form(
            lang().get(BountiesMessages.FORM_TITLE), List.of(),
            List.of(Templates.text("player", lang().get(BountiesMessages.FORM_PLAYER), name, 16),
                Templates.text("amount", lang().get(BountiesMessages.FORM_AMOUNT, Arg.text("minimum", lang().money(s.minimum()))),
                    amount, 24)),
            lang().get(BountiesMessages.FORM_SUBMIT),
            submission -> submit(submission, fromHub),
            submission -> openList(submission.player(), fromHub));
        this.services.dialogs().show(viewer, tooltips(form,
            lang().get(BountiesMessages.FORM_SUBMIT_TOOLTIP, time(s.expireAfter()))));
    }

    /** A dialog whose buttons get these tooltips, in order (fewer tooltips than buttons leave the rest as they are). */
    static View tooltips(View view, Component... tooltips) {
        List<Button> buttons = new ArrayList<>(view.buttons());
        for (int i = 0; i < tooltips.length && i < buttons.size(); i++) {
            if (tooltips[i] != null) {
                buttons.set(i, buttons.get(i).tooltip(tooltips[i]));
            }
        }
        return new View(view.kind(), view.title(), view.body(), view.inputs(), buttons, view.exit(), view.columns(), view.escapable());
    }

    /** A time for an {@code <accent>} placeholder ("13d 4h"). */
    static Arg time(Duration duration) {
        return Arg.text("time", Durations.format(duration));
    }

    private void submit(Submission submission, boolean fromHub) {
        Player player = submission.player();
        String targetName = submission.values().text("player");
        String amountText = submission.values().text("amount");
        Optional<UUID> target = resolve(targetName);
        if (target.isEmpty()) {
            submission.error(lang().get(CoreMessages.PLAYER_NOT_FOUND, Arg.text("name", targetName)));
            return;
        }
        var parsed = this.services.money().get().parse(amountText);
        if (!parsed.ok()) {
            submission.error(lang().get(CoreMessages.INVALID_AMOUNT, Arg.text("input", amountText)));
            return;
        }
        Component problem = this.actions.problem(player, target.get(), parsed.amount());
        if (problem != null) {
            submission.error(problem);
            return;
        }
        // The confirmation or, once placed, the bounty's details replace the form; a late refusal goes to the action
        // bar and the router closes the form.
        UUID chosen = target.get();
        this.actions.request(player, chosen, parsed.amount(), placed -> openDetails(placed, chosen, fromHub));
    }

    private Optional<UUID> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? Optional.of(online.getUniqueId()) : this.services.directory().uuid(name);
    }

    private void openMenu(Player player) {
        HubEntry menu = this.services.hub().get("menu");
        if (menu != null) {
            menu.open().accept(player);
        } else {
            this.services.dialogs().close(player);
        }
    }
}
