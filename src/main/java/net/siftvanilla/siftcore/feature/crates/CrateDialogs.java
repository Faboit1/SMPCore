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
 * The crate screens: the list of crates with your keys (each with Open and Preview), a single crate (shown by a crate
 * block), the result of an opening, and the preview menu. Opening from a dialog keeps the dialog on its "waiting"
 * screen until the reward is stored and handed over, then shows the result; a refusal re-shows the screen with the
 * reason.
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
            ? Button.of(lang().get(CoreMessages.UI_CLOSE), null).width(Templates.WIDE)
            : Button.of(lang().get(CoreMessages.UI_BACK), s -> back.run()).width(Templates.WIDE);
    }

    // ------------------------------------------------------------------ list

    /** The crates list ({@code /crates}). {@code back} returns to the main menu, or null for a Close button. */
    void list(Player player, Runnable back) {
        this.services.dialogs().show(player, listView(player, back));
    }

    private View listView(Player player, Runnable back) {
        Lang lang = lang();
        CratesSettings settings = this.settings.get();
        List<Body> body = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        if (settings.crates().isEmpty()) {
            body.add(lines(lang.lines(CratesMessages.LIST_EMPTY)));
        }
        Runnable self = () -> list(player, back);
        for (Crate crate : settings.crates()) {
            int owned = this.keys.keys(player.getUniqueId(), crate.id());
            body.add(Body.item(RewardItems.crateIcon(crate), Component.join(JoinConfiguration.newlines(),
                lang.lines(CratesMessages.LIST_ENTRY, Arg.text("name", crate.name()), Arg.component("keys", this.text.count(owned))))));
            String crateId = crate.id();
            buttons.add(Button.of(lang.get(CratesMessages.LIST_OPEN, Arg.text("name", crate.name())),
                s -> open(s, crateId, self, () -> listView(player, back))).width(150));
            buttons.add(Button.of(lang.get(CratesMessages.LIST_PREVIEW, Arg.text("name", crate.name())),
                s -> preview(s.player(), crateId, self)).width(150));
        }
        if (this.keyall.enabled()) {
            Component reward = this.keyall.reward();
            if (reward != null) {
                body.add(lines(lang.lines(CratesMessages.LIST_KEYALL, Arg.time("time", this.keyall.remaining()),
                    Arg.component("keys", reward))));
            }
        }
        return new View(View.Kind.LIST, lang.get(CratesMessages.LIST_TITLE), body, List.of(), buttons, footer(back), 2, true);
    }

    // ------------------------------------------------------------------ one crate

    /** One crate with Open, Open n (with 2 or more keys) and Preview (right-clicking a crate block). */
    void crate(Player player, String crateId, Runnable back) {
        View view = crateView(player, crateId, back);
        if (view == null) {
            this.services.messenger().send(player, CratesMessages.UNKNOWN_CRATE, Arg.text("input", crateId));
            return;
        }
        this.services.dialogs().show(player, view);
    }

    private View crateView(Player player, String crateId, Runnable back) {
        Crate crate = this.settings.get().crate(crateId);
        if (crate == null) {
            return null;
        }
        Lang lang = lang();
        int owned = this.keys.keys(player.getUniqueId(), crate.id());
        List<Body> body = List.of(Body.item(RewardItems.crateIcon(crate), Component.join(JoinConfiguration.newlines(),
            lang.lines(CratesMessages.VIEW_BODY, Arg.component("keys", this.text.count(owned)),
                Arg.number("rewards", this.items.available(crate).size())))));
        Runnable self = () -> crate(player, crateId, back);
        Supplier<View> origin = () -> crateView(player, crateId, back);
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.of(lang.get(CratesMessages.VIEW_OPEN), s -> open(s, crateId, self, origin)).width(150));
        int many = bulk(owned);
        if (many >= 2) {
            buttons.add(Button.of(lang.get(CratesMessages.VIEW_OPEN_MANY, Arg.text("count", Integer.toString(many))),
                s -> openMany(s, crateId, many, self, origin)).width(150));
        }
        buttons.add(Button.of(lang.get(CratesMessages.VIEW_PREVIEW), s -> preview(s.player(), crateId, self)).width(150));
        return new View(View.Kind.LIST, lang.get(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())), body, List.of(), buttons,
            footer(back), 2, true);
    }

    // ------------------------------------------------------------------ opening

    /** How many keys an "Open n" button opens with this many keys: at most bulk-open, and 0 when it would be one. */
    private int bulk(int keys) {
        int many = Math.min(keys, this.settings.get().bulkOpen());
        return many >= 2 ? many : 0;
    }

    /** Shows a refusal on the screen the player opened from (or on the action bar when that screen is gone). */
    private void refuse(Player player, CrateOpener.Refused refused, Supplier<View> origin) {
        View view = origin.get();
        if (view == null) {
            this.services.dialogs().close(player);
            this.opener.report(player, refused);
            return;
        }
        this.services.dialogs().show(player, view.withError(lang().get(refused.key(), refused.argArray()), FormValues.EMPTY));
        this.services.messenger().feedback(player, Feedback.ERROR);
    }

    /**
     * Opens a key from a dialog button. The client stays on its waiting screen until the result is shown.
     *
     * @param returnTo what the result's Back button shows (the screen the player opened from)
     * @param origin   rebuilds that screen, to show a refusal on it
     */
    private void open(Submission submission, String crateId, Runnable returnTo, Supplier<View> origin) {
        Player player = submission.player();
        this.services.dialogs().markShown(player);
        this.opener.open(player, crateId, false, result -> {
            switch (result) {
                case CrateOpener.Won won -> this.services.dialogs().show(player, resultView(won, returnTo, origin));
                case CrateOpener.Refused refused -> refuse(player, refused, origin);
            }
        });
    }

    /**
     * Opens several keys in a row from a dialog button; the client stays on its waiting screen until the last one is
     * stored and handed over, then sees what they all won (and the chat receipt keeps it).
     */
    private void openMany(Submission submission, String crateId, int count, Runnable returnTo, Supplier<View> origin) {
        Player player = submission.player();
        this.services.dialogs().markShown(player);
        this.opener.openMany(player, crateId, count, false, batch -> {
            if (batch.wins().isEmpty()) {
                refuse(player, batch.stoppedBy() == null ? new CrateOpener.Refused(CratesMessages.OPEN_FAILED) : batch.stoppedBy(), origin);
                return;
            }
            this.opener.receipt(player, batch);
            this.services.dialogs().show(player, batch.wins().size() == 1
                ? resultView(batch.wins().getFirst(), returnTo, origin)
                : batchView(batch, returnTo, origin));
        });
    }

    private View resultView(CrateOpener.Won won, Runnable returnTo, Supplier<View> origin) {
        Lang lang = lang();
        Crate crate = won.crate();
        List<Body> body = new ArrayList<>();
        body.add(Body.item(this.items.icon(won.reward()), Component.join(JoinConfiguration.newlines(),
            lang.lines(CratesMessages.RESULT_WON, Arg.component("reward", this.text.reward(won.reward())),
                Arg.text("rarity", won.rarity().label())))));
        List<Component> lines = new ArrayList<>();
        if (won.inClaimBox() > 0) {
            lines.addAll(lang.lines(CratesMessages.RESULT_CLAIM_BOX));
        }
        lines.addAll(won.keysLeft() > 0
            ? lang.lines(CratesMessages.RESULT_LEFT, Arg.component("keys", this.text.count(won.keysLeft())))
            : lang.lines(CratesMessages.RESULT_LAST));
        body.add(lines(lines));
        return new View(View.Kind.LIST, lang.get(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())), body, List.of(),
            againButtons(crate.id(), won.keysLeft(), returnTo, origin), Button.of(lang.get(CoreMessages.UI_BACK), s -> returnTo.run())
                .width(Templates.WIDE), 2, true);
    }

    /** What several openings in a row won: the rarest reward's item, a line per reward, keys left. */
    private View batchView(CrateOpener.Batch batch, Runnable returnTo, Supplier<View> origin) {
        Lang lang = lang();
        Crate crate = batch.crate();
        CratesSettings settings = this.settings.get();
        CrateOpener.Won rarest = batch.wins().getFirst();
        for (CrateOpener.Won won : batch.wins()) {
            if (settings.rarities().indexOf(won.rarity()) > settings.rarities().indexOf(rarest.rarity())) {
                rarest = won;
            }
        }
        List<Component> won = new ArrayList<>(lang.lines(CratesMessages.RESULT_BATCH, Arg.number("count", batch.wins().size())));
        won.addAll(this.text.wins(batch.wins()));
        List<Body> body = new ArrayList<>();
        body.add(Body.item(this.items.icon(rarest.reward()), Component.join(JoinConfiguration.newlines(), won)));
        List<Component> lines = new ArrayList<>();
        if (batch.inClaimBox() > 0) {
            lines.addAll(lang.lines(CratesMessages.RESULT_CLAIM_BOX));
        }
        lines.addAll(batch.keysLeft() > 0
            ? lang.lines(CratesMessages.RESULT_LEFT, Arg.component("keys", this.text.count(batch.keysLeft())))
            : lang.lines(CratesMessages.RESULT_LAST));
        body.add(lines(lines));
        return new View(View.Kind.LIST, lang.get(CratesMessages.VIEW_TITLE, Arg.text("name", crate.name())), body, List.of(),
            againButtons(crate.id(), batch.keysLeft(), returnTo, origin), Button.of(lang.get(CoreMessages.UI_BACK), s -> returnTo.run())
                .width(Templates.WIDE), 2, true);
    }

    /** Open another, Open n more (while keys are left) and Preview, under a result. */
    private List<Button> againButtons(String crateId, int keysLeft, Runnable returnTo, Supplier<View> origin) {
        Lang lang = lang();
        List<Button> buttons = new ArrayList<>();
        if (keysLeft > 0) {
            buttons.add(Button.of(lang.get(CratesMessages.RESULT_AGAIN), s -> open(s, crateId, returnTo, origin)).width(150));
        }
        int many = bulk(keysLeft);
        if (many >= 2) {
            buttons.add(Button.of(lang.get(CratesMessages.RESULT_MANY, Arg.text("count", Integer.toString(many))),
                s -> openMany(s, crateId, many, returnTo, origin)).width(150));
        }
        buttons.add(Button.of(lang.get(CratesMessages.VIEW_PREVIEW), s -> preview(s.player(), crateId, returnTo)).width(150));
        return buttons;
    }

    // ------------------------------------------------------------------ preview

    /** The preview menu of a crate. {@code back} is what its back button shows, or null for none. */
    void preview(Player player, String crateId, Runnable back) {
        Crate crate = this.settings.get().crate(crateId);
        if (crate == null) {
            this.services.messenger().send(player, CratesMessages.UNKNOWN_CRATE, Arg.text("input", crateId));
            return;
        }
        new PreviewMenu(this.services.menus(), player, crate, this.settings, this.items, this.keys, this.opener, this.text,
            this.worth, back).open();
    }
}
