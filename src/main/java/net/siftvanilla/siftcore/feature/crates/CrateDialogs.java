package net.siftvanilla.siftcore.feature.crates;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.WorthLookup;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * The crate screens, in the dialog style (buttons, explanations in their tooltips): the list of crates with your keys
 * (one button per crate, from the lowest tier up), a single crate (Open, Open n, Preview; also what a crate block
 * shows), the result of an opening, and the preview menu.
 * <p>
 * Open shows the opening animation when the server has it on; the dialog waits on the client's waiting screen until
 * the animation's window opens, and the result shows once it closed. Without the animation the result shows as soon
 * as the reward is stored and handed over. A refusal shows the screen again with the reason in red.
 */
final class CrateDialogs {

    private final Services services;
    private final Setting<CratesSettings> settings;
    private final KeyService keys;
    private final RewardItems items;
    private final CrateOpener opener;
    private final CrateText text;
    private final WorthLookup worth;
    private final Keyall keyall;

    CrateDialogs(Services services, Setting<CratesSettings> settings, KeyService keys, RewardItems items, CrateOpener opener,
                 CrateText text, WorthLookup worth, Keyall keyall) {
        this.services = services;
        this.settings = settings;
        this.keys = keys;
        this.items = items;
        this.opener = opener;
        this.text = text;
        this.worth = worth;
        this.keyall = keyall;
    }

    private Lang lang() {
        return this.services.lang();
    }

    private static Body lines(List<Component> lines) {
        return Body.text(Component.join(JoinConfiguration.newlines(), lines));
    }

    private Button footer(Runnable back) {
        return back == null
            ? Button.of(lang().get(CoreMessages.UI_CLOSE), null).width(Templates.LONG)
            : Button.of(lang().get(CoreMessages.UI_BACK), s -> back.run()).width(Templates.LONG);
    }

    /** How an opening from a crate screen is shown: animated when the server has the animation on. */
    private CrateOpener.Show show(BlockKey block) {
        return new CrateOpener.Show(this.settings.get().effects().animation().enabled(), block);
    }

    // ------------------------------------------------------------------ list

    /** The crates list ({@code /crates}). {@code back} returns to the main menu, or null for a Close button. */
    void list(Player player, Runnable back) {
        this.services.dialogs().show(player, listView(player, back));
    }

    private View listView(Player player, Runnable back) {
        Lang lang = lang();
        CratesSettings settings = this.settings.get();
        List<Component> lines = new ArrayList<>();
        if (settings.crates().isEmpty()) {
            lines.addAll(lang.lines(CratesMessages.LIST_EMPTY));
        }
        if (this.keyall.enabled()) {
            Component reward = this.keyall.reward();
            if (reward != null) {
                lines.add(lang.get(CratesMessages.LIST_KEYALL, Arg.value("time", this.keyall.remaining()), Arg.component("keys", reward)));
            }
        }
        Runnable self = () -> list(player, back);
        List<Button> buttons = new ArrayList<>();
        for (Crate crate : settings.crates()) {
            int owned = this.keys.keys(player.getUniqueId(), crate.id());
            String crateId = crate.id();
            buttons.add(this.services.templates().choiceButton(lang.get(CratesMessages.LIST_CRATE, CrateText.nameArg(crate)), keyCount(owned),
                crateTooltip(crate), s -> {
                    View view = crateView(s.player(), crateId, self, null);
                    if (view == null) {
                        s.show(listView(s.player(), back));
                    } else {
                        s.show(view);
                    }
                }));
        }
        return this.services.templates().column(lang.get(CratesMessages.LIST_TITLE), lines, buttons, back == null ? null : s -> back.run());
    }

    /** "3 keys" for a crate button (in the accent colour), "no keys" in gray. */
    private Component keyCount(int owned) {
        Lang lang = lang();
        if (owned <= 0) {
            return lang.get(CratesMessages.LIST_KEYS_NONE);
        }
        return owned == 1 ? lang.get(CratesMessages.LIST_KEYS_ONE) : lang.get(CratesMessages.LIST_KEYS_MANY, Arg.value("count", owned));
    }

    /** A crate button's tooltip: how many rewards it has and the rarest of them, then what a click does. */
    private Component crateTooltip(Crate crate) {
        List<Reward> available = this.items.available(crate);
        CratesSettings settings = this.settings.get();
        Rarity best = null;
        for (Reward reward : available) {
            Rarity rarity = settings.rarity(reward.rarity());
            if (best == null || settings.rank(rarity.id()) > settings.rank(best.id())) {
                best = rarity;
            }
        }
        List<Component> lines = new ArrayList<>();
        if (best != null) {
            lines.add(lang().get(CratesMessages.LIST_TOOLTIP_REWARDS, Arg.number("rewards", available.size()),
                Arg.component("rarity", CrateText.rarity(best))));
        }
        lines.addAll(lang().lines(CratesMessages.LIST_TOOLTIP));
        return Templates.lines(lines);
    }

    // ------------------------------------------------------------------ one crate

    /**
     * One crate with Open, Open n (with 2 or more keys) and Preview (right-clicking a crate block shows it).
     *
     * @param block the crate block it was opened at (the reward spins up out of it), or null
     */
    void crate(Player player, String crateId, Runnable back, BlockKey block) {
        View view = crateView(player, crateId, back, block);
        if (view == null) {
            this.services.messenger().send(player, CratesMessages.UNKNOWN_CRATE, Arg.text("input", crateId));
            return;
        }
        this.services.dialogs().show(player, view);
    }

    private View crateView(Player player, String crateId, Runnable back, BlockKey block) {
        Crate crate = this.settings.get().crate(crateId);
        if (crate == null) {
            return null;
        }
        Lang lang = lang();
        int owned = this.keys.keys(player.getUniqueId(), crate.id());
        List<Body> body = List.of(Body.item(RewardItems.crateIcon(crate),
            lang.get(CratesMessages.VIEW_BODY, Arg.component("keys", this.text.count(owned)))));
        Runnable self = () -> crate(player, crateId, back, block);
        Supplier<View> origin = () -> crateView(player, crateId, back, block);
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.of(lang.get(CratesMessages.VIEW_OPEN), openTooltip(), s -> open(s, crateId, self, origin, block))
            .width(Templates.HALF).waits());
        int many = bulk(player, owned);
        if (many >= 2) {
            buttons.add(Button.of(lang.get(CratesMessages.VIEW_OPEN_MANY, Arg.text("count", Integer.toString(many))),
                lang.get(CratesMessages.VIEW_OPEN_MANY_TOOLTIP, Arg.number("count", many)),
                s -> openMany(s, crateId, many, self, origin)).width(Templates.HALF).waits());
        }
        buttons.add(previewButton(crateId, self, block));
        return this.services.templates().listWithBody(lang.get(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())), body, buttons,
            2, back == null ? null : s -> back.run());
    }

    private Component openTooltip() {
        return lang().get(this.settings.get().effects().animation().enabled() ? CratesMessages.VIEW_OPEN_TOOLTIP_ANIMATED
            : CratesMessages.VIEW_OPEN_TOOLTIP);
    }

    private Button previewButton(String crateId, Runnable returnTo, BlockKey block) {
        return Button.of(lang().get(CratesMessages.VIEW_PREVIEW), lang().get(CratesMessages.VIEW_PREVIEW_TOOLTIP),
            s -> preview(s.player(), crateId, returnTo, block)).width(Templates.HALF);
    }

    // ------------------------------------------------------------------ opening

    /**
     * How many keys an "Open n" button (or a bulk quick-open) opens for a player with this many keys: their Keys per
     * bulk open, at most bulk-open, and 0 when it would be one.
     */
    int bulk(Player player, int keys) {
        return CratePlayerSettings.bulkAmount(this.services.settings().get(player, CratePlayerSettings.BULK_AMOUNT), keys,
            this.settings.get().bulkOpen());
    }

    /** Shows a refusal on the screen the player opened from (or on the action bar when that screen is gone). */
    private void refuse(Player player, CrateOpener.Refused refused, Supplier<View> origin) {
        View view = origin.get();
        if (view == null) {
            this.services.dialogs().closeScreen(player);
            this.opener.report(player, refused);
            return;
        }
        this.services.dialogs().show(player, view.withError(lang().get(refused.key(), refused.argArray()), FormValues.EMPTY));
        this.services.messenger().feedback(player, Feedback.ERROR);
    }

    /**
     * Opens a key from a dialog button. The client stays on its waiting screen until the animation's window opens (or,
     * without it, until the result is shown).
     *
     * @param returnTo what the result's Back button shows (the screen the player opened from)
     * @param origin   rebuilds that screen, to show a refusal on it
     */
    private void open(Submission submission, String crateId, Runnable returnTo, Supplier<View> origin, BlockKey block) {
        Player player = submission.player();
        this.services.dialogs().markShown(player);
        // The result comes after the opening is stored, outside the click: built for the player, so a money reward
        // reads in their money format like the chat line of the same win.
        this.opener.open(player, crateId, false, show(block), result -> lang().viewing(player, () -> {
            switch (result) {
                case CrateOpener.Won won -> this.services.dialogs().show(player, resultView(player, won, returnTo, origin, block));
                case CrateOpener.Refused refused -> refuse(player, refused, origin);
            }
        }));
    }

    /**
     * Opens several keys in a row from a dialog button (never animated); the client stays on its waiting screen until
     * the last one is stored and handed over, then sees what they all won (and the chat receipt keeps it).
     */
    private void openMany(Submission submission, String crateId, int count, Runnable returnTo, Supplier<View> origin) {
        Player player = submission.player();
        this.services.dialogs().markShown(player);
        this.opener.openMany(player, crateId, count, false, batch -> lang().viewing(player, () -> {
            if (batch.wins().isEmpty()) {
                refuse(player, batch.stoppedBy() == null ? new CrateOpener.Refused(CratesMessages.OPEN_FAILED) : batch.stoppedBy(), origin);
                return;
            }
            this.opener.receipt(player, batch);
            this.services.dialogs().show(player, batch.wins().size() == 1
                ? resultView(player, batch.wins().getFirst(), returnTo, origin, null)
                : batchView(player, batch, returnTo, origin));
        }));
    }

    private View resultView(Player player, CrateOpener.Won won, Runnable returnTo, Supplier<View> origin, BlockKey block) {
        Lang lang = lang();
        Crate crate = won.crate();
        List<Body> body = new ArrayList<>();
        body.add(Body.item(this.items.icon(won.reward()), Component.join(JoinConfiguration.newlines(),
            lang.lines(CratesMessages.RESULT_WON, Arg.component("reward", this.text.reward(won.reward())),
                Arg.component("rarity", CrateText.rarity(won.rarity()))))));
        body.add(lines(status(won.inClaimBox(), won.keysLeft())));
        return this.services.templates().listWithBody(lang.get(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())), body,
            againButtons(player, crate.id(), won.keysLeft(), returnTo, origin, block), 2, s -> returnTo.run());
    }

    /** The keys left (or that it was the last), and where the reward went when it didn't fit. */
    private List<Component> status(int inClaimBox, int keysLeft) {
        Lang lang = lang();
        List<Component> lines = new ArrayList<>();
        if (inClaimBox > 0) {
            lines.addAll(lang.lines(CratesMessages.RESULT_CLAIM_BOX));
        }
        lines.addAll(keysLeft > 0
            ? lang.lines(CratesMessages.RESULT_LEFT, Arg.component("keys", this.text.count(keysLeft)))
            : lang.lines(CratesMessages.RESULT_LAST));
        return lines;
    }

    /** What several openings in a row won: the rarest reward's item, a line per reward, keys left. */
    private View batchView(Player player, CrateOpener.Batch batch, Runnable returnTo, Supplier<View> origin) {
        Lang lang = lang();
        Crate crate = batch.crate();
        CrateOpener.Won rarest = this.opener.rarest(batch);
        List<Component> won = new ArrayList<>(lang.lines(CratesMessages.RESULT_BATCH, Arg.number("count", batch.wins().size())));
        won.addAll(this.text.wins(batch.wins()));
        List<Body> body = new ArrayList<>();
        body.add(Body.item(this.items.icon(rarest.reward()), Component.join(JoinConfiguration.newlines(), won)));
        body.add(lines(status(batch.inClaimBox(), batch.keysLeft())));
        return this.services.templates().listWithBody(lang.get(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())), body,
            againButtons(player, crate.id(), batch.keysLeft(), returnTo, origin, null), 2, s -> returnTo.run());
    }

    /** Open another, Open n more (while keys are left) and Preview, under a result. */
    private List<Button> againButtons(Player player, String crateId, int keysLeft, Runnable returnTo, Supplier<View> origin,
                                      BlockKey block) {
        Lang lang = lang();
        List<Button> buttons = new ArrayList<>();
        if (keysLeft > 0) {
            buttons.add(Button.of(lang.get(CratesMessages.RESULT_AGAIN), openTooltip(), s -> open(s, crateId, returnTo, origin, block))
                .width(Templates.HALF).waits());
        }
        int many = bulk(player, keysLeft);
        if (many >= 2) {
            buttons.add(Button.of(lang.get(CratesMessages.RESULT_MANY, Arg.text("count", Integer.toString(many))),
                lang.get(CratesMessages.VIEW_OPEN_MANY_TOOLTIP, Arg.number("count", many)),
                s -> openMany(s, crateId, many, returnTo, origin)).width(Templates.HALF).waits());
        }
        buttons.add(previewButton(crateId, returnTo, block));
        return buttons;
    }

    // ------------------------------------------------------------------ preview

    /**
     * The preview menu of a crate. {@code back} is what its back button shows, or null for none; {@code block} the
     * crate block it was opened at, or null.
     */
    void preview(Player player, String crateId, Runnable back, BlockKey block) {
        Crate crate = this.settings.get().crate(crateId);
        if (crate == null) {
            this.services.messenger().send(player, CratesMessages.UNKNOWN_CRATE, Arg.text("input", crateId));
            return;
        }
        new PreviewMenu(this.services.menus(), player, crate, this.settings, this.items, this.keys, this.opener, this.text,
            this.worth, this.services.settings(), back, block).open();
    }
}
