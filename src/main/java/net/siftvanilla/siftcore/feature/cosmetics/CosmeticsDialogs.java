package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.FormValues;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * The cosmetics dialogs, in the dialog style: the menu (one button per perk showing what the player has now, in its own
 * colours: "Chat colour: Gold" in gold, "Chat tag: [VIP]", then switches for other players' chat colours and kill
 * effects), the chat colour pickers (every colour a button in its own colour), the nickname form, the tags (every tag in
 * its own look), the join and leave messages (with the lines as everyone sees them) and the kill effects. What a button
 * does is in its tooltip; nothing is paged (the dialogs scroll). Picking something uses it at once and shows the same
 * dialog again with the choice marked "(now)", without a message; a refusal shows in red on it. Locked perks stay
 * visible and say which rank unlocks them. Every click goes through {@link CosmeticsActions}, which checks it again.
 */
final class CosmeticsDialogs {

    private final Services services;
    private final Lang lang;
    private final CosmeticsService cosmetics;
    private final CosmeticsActions actions;

    CosmeticsDialogs(Services services, CosmeticsService cosmetics, CosmeticsActions actions) {
        this.services = services;
        this.lang = services.lang();
        this.cosmetics = cosmetics;
        this.actions = actions;
    }

    private void show(Player player, View view, Component error) {
        this.services.dialogs().show(player, error == null ? view : view.withError(error, FormValues.EMPTY));
    }

    /** Opens a screen, or tells the player why not (feature off, in combat). */
    private boolean refused(Player player) {
        CosmeticsActions.Outcome blocked = this.actions.blocked(player);
        if (blocked == null) {
            return false;
        }
        this.actions.tell(player, blocked);
        return true;
    }

    /**
     * After a pick in a dialog: the dialog shows again with the new choice (no message: the dialog shows it), or the
     * reason in red.
     */
    private void picked(Submission submission, CosmeticsActions.Outcome outcome, Consumer<Player> again) {
        if (outcome.ok()) {
            again.accept(submission.player());
        } else {
            submission.error(text(outcome));
        }
    }

    private Component text(CosmeticsActions.Outcome outcome) {
        return this.lang.get(outcome.key(), outcome.args());
    }

    private static List<Body> body(List<Component> lines) {
        return lines.isEmpty() ? List.of() : List.of(Body.text(Templates.lines(lines)));
    }

    private Component ui(MessageKey key, Arg... args) {
        return this.lang.get(key, args);
    }

    /** "none" in the off colour, for a perk the player doesn't use. */
    private Component none() {
        return ui(CosmeticsMessages.NONE).color(this.lang.style().palette().off());
    }

    /** An option's label, marked when it is the one chosen now ("Gold (now)"). */
    private Component option(Component label, boolean current) {
        return current ? ui(CosmeticsMessages.CURRENT_OPTION, Arg.component("option", label)) : label;
    }

    /** A copy of a dialog whose first buttons get these tooltips, in order (null keeps a button as it is). */
    private static View tooltips(View view, Component... tooltips) {
        List<Button> buttons = new ArrayList<>(view.buttons());
        for (int i = 0; i < tooltips.length && i < buttons.size(); i++) {
            if (tooltips[i] != null) {
                buttons.set(i, buttons.get(i).tooltip(tooltips[i]));
            }
        }
        return new View(view.kind(), view.title(), view.body(), view.inputs(), buttons, view.exit(), view.columns(), view.escapable());
    }

    // ------------------------------------------------------------------ menu

    void menu(Player player) {
        menu(player, null);
    }

    private void menu(Player player, Component error) {
        if (refused(player)) {
            return;
        }
        boolean colours = player.hasPermission(CosmeticsNodes.CHAT_COLOR) || player.hasPermission(CosmeticsNodes.CHAT_COLOR_HEX);
        boolean nicks = player.hasPermission(CosmeticsNodes.NICK);
        boolean joins = player.hasPermission(CosmeticsNodes.JOIN);
        ChatStyle chat = this.cosmetics.chatStyle(player);
        String nick = this.cosmetics.nick(player);
        ChatTag tag = this.cosmetics.currentTag(player);
        KillEffect effect = this.cosmetics.killEffect(player);
        List<Button> buttons = new ArrayList<>();
        buttons.add(perk(CosmeticsMessages.MENU_CHAT_COLOR, chat.none() ? none() : this.cosmetics.styleName(chat),
            CosmeticsMessages.MENU_CHAT_COLOR_TOOLTIP, colours, CosmeticsMessages.RANK_BARON, this::chatColor));
        buttons.add(perk(CosmeticsMessages.MENU_NICK, nick == null ? none() : this.cosmetics.name(player).hoverEvent(null),
            CosmeticsMessages.MENU_NICK_TOOLTIP, nicks, CosmeticsMessages.RANK_BARON, this::nick));
        buttons.add(perk(CosmeticsMessages.MENU_TAG, tag == null ? none() : tag.display(), CosmeticsMessages.MENU_TAG_TOOLTIP, true,
            CosmeticsMessages.RANK_PROSPECTOR, this::tags));
        buttons.add(perk(CosmeticsMessages.MENU_JOIN, ui(joinState(player)), CosmeticsMessages.MENU_JOIN_TOOLTIP, joins,
            CosmeticsMessages.RANK_BARON, this::joinMessages));
        buttons.add(perk(CosmeticsMessages.MENU_KILL, effect == null ? none() : ui(CosmeticsMessages.name(effect)),
            CosmeticsMessages.MENU_KILL_TOOLTIP, true, CosmeticsMessages.RANK_TYCOON, this::killEffects));
        CosmeticsSettings settings = this.cosmetics.settings();
        if (settings.enabled()) {
            viewerSwitch(buttons, player, CosmeticsFeature.CHAT_COLORS, CosmeticsMessages.MENU_SHOW_COLORS,
                CosmeticsMessages.SETTING_CHAT_COLORS_DESCRIPTION);
        }
        if (CosmeticsFeature.killEffectsPlay(settings)) {
            viewerSwitch(buttons, player, CosmeticsFeature.KILL_EFFECTS, CosmeticsMessages.MENU_SHOW_EFFECTS,
                CosmeticsMessages.SETTING_KILL_EFFECTS_DESCRIPTION);
        }
        show(player, this.services.templates().column(ui(CosmeticsMessages.MENU_TITLE), buttons, null), error);
    }

    private MessageKey joinState(Player player) {
        if (!player.hasPermission(CosmeticsNodes.JOIN) || !this.cosmetics.settings().join().enabled()) {
            return CosmeticsMessages.MENU_JOIN_DEFAULT;
        }
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());
        boolean custom = player.hasPermission(CosmeticsNodes.JOIN_CUSTOM) && (profile.joinMessage() != null || profile.leaveMessage() != null);
        return custom ? CosmeticsMessages.MENU_JOIN_CUSTOM : CosmeticsMessages.MENU_JOIN_RANK;
    }

    /**
     * A perk's button, "Chat colour: Gold" with the value in its own colours: opens the perk's dialog, or (locked) shows
     * "locked" and says in red which rank unlocks it.
     */
    private Button perk(MessageKey label, Component value, MessageKey tooltip, boolean unlocked, MessageKey rank, Consumer<Player> open) {
        Component tip = unlocked ? ui(tooltip)
            : Templates.lines(List.of(ui(tooltip), ui(CosmeticsMessages.MENU_UNLOCK, Arg.component("unlock", ui(rank)))));
        return this.services.templates().choiceButton(ui(label), unlocked ? value : ui(CosmeticsMessages.MENU_LOCKED), tip, submission -> {
            if (unlocked) {
                open.accept(submission.player());
            } else {
                submission.error(text(this.actions.locked(rank)));
            }
        });
    }

    /**
     * A switch for what the player sees of others (the same setting as in Settings): flips at once and shows the menu
     * again. Left out while the server locks or hides the setting (a hidden setting can't be changed, so every click
     * would only show the refusal).
     */
    private void viewerSwitch(List<Button> buttons, Player player, Toggle toggle, MessageKey label, MessageKey description) {
        var settings = this.services.settings();
        if (!offersSwitch(settings, toggle)) {
            return;
        }
        boolean on = Boolean.TRUE.equals(settings.get(player, toggle));
        buttons.add(this.services.templates().switchButton(ui(label), on, ui(description), submission -> {
            Player clicker = submission.player();
            SetResult result = settings.set(clicker, toggle, !on, Change.dialog(clicker.getName()));
            menu(clicker, result.succeeded() ? null : ui(CosmeticsMessages.MENU_SWITCH_REFUSED, Arg.component("label", ui(label))));
        }));
    }

    /** Whether the cosmetics menu shows a viewer switch: only while the server neither locks nor hides it. */
    static boolean offersSwitch(net.siftvanilla.siftcore.core.player.PlayerSettings settings, Toggle toggle) {
        return !settings.locked(toggle) && !settings.hidden(toggle);
    }

    // ------------------------------------------------------------------ chat colour

    void chatColor(Player player) {
        if (refused(player)) {
            return;
        }
        if (!player.hasPermission(CosmeticsNodes.CHAT_COLOR) && !player.hasPermission(CosmeticsNodes.CHAT_COLOR_HEX)) {
            this.actions.tell(player, this.actions.locked(CosmeticsMessages.RANK_BARON));
            return;
        }
        ChatStyle current = this.cosmetics.chatStyle(player);
        List<Component> lines = List.of(ui(CosmeticsMessages.COLOR_CURRENT, Arg.component("preview", sample(current))));
        List<Button> buttons = new ArrayList<>();
        for (NamedTextColor color : this.cosmetics.settings().colors().basic()) {
            ChatStyle style = ChatStyle.vanilla(color);
            buttons.add(styleButton(style, this.lang.plain(CosmeticsMessages.colorName(color)), current,
                submission -> picked(submission, this.actions.setChatStyle(submission.player(), style), this::chatColor)));
        }
        boolean premium = player.hasPermission(CosmeticsNodes.CHAT_COLOR_HEX);
        buttons.add(Button.of(ui(CosmeticsMessages.COLOR_MORE),
            premium ? ui(CosmeticsMessages.COLOR_MORE_TOOLTIP) : Templates.lines(List.of(ui(CosmeticsMessages.COLOR_MORE_TOOLTIP),
                ui(CosmeticsMessages.MENU_UNLOCK, Arg.component("unlock", ui(CosmeticsMessages.RANK_TYCOON))))),
            submission -> {
                if (submission.player().hasPermission(CosmeticsNodes.CHAT_COLOR_HEX)) {
                    premiumColors(submission.player());
                } else {
                    submission.error(text(this.actions.locked(CosmeticsMessages.RANK_TYCOON)));
                }
            }));
        buttons.add(Button.of(option(ui(CosmeticsMessages.COLOR_RESET_BUTTON), current.none()), ui(CosmeticsMessages.COLOR_RESET_TOOLTIP),
            submission -> picked(submission, this.actions.setChatStyle(submission.player(), ChatStyle.NONE), this::chatColor)));
        show(player, this.services.templates().grid(ui(CosmeticsMessages.COLOR_TITLE), lines, buttons,
            submission -> menu(submission.player())), null);
    }

    /** "This is how your messages look" in a style. */
    private Component sample(ChatStyle style) {
        String text = this.lang.plain(CosmeticsMessages.COLOR_SAMPLE);
        return style.none() ? Component.text(text, this.lang.style().palette().primary()) : style.apply(text);
    }

    /**
     * A colour's button, labelled in its own colour ("Gold (now)" for the colour used now); the tooltip shows a sample in
     * it, or that it is the colour now.
     */
    private Button styleButton(ChatStyle style, String name, ChatStyle current, Button.Handler handler) {
        boolean chosen = style.serialize().equals(current.serialize());
        List<Component> tooltip = new ArrayList<>();
        if (chosen) {
            tooltip.add(ui(CosmeticsMessages.COLOR_SELECTED_TOOLTIP));
        } else {
            tooltip.add(sample(style));
            if (style instanceof ChatStyle.Gradient) {
                tooltip.add(ui(CosmeticsMessages.COLOR_GRADIENT_TOOLTIP));
            }
            tooltip.add(ui(CosmeticsMessages.COLOR_PICK_TOOLTIP));
        }
        return Button.of(option(style.apply(name), chosen), Templates.lines(tooltip), handler);
    }

    void premiumColors(Player player) {
        if (refused(player)) {
            return;
        }
        ChatStyle current = this.cosmetics.chatStyle(player);
        List<Component> lines = List.of(ui(CosmeticsMessages.COLOR_CURRENT, Arg.component("preview", sample(current))));
        List<Button> buttons = new ArrayList<>();
        ColorRules rules = this.cosmetics.rules();
        for (CosmeticsSettings.Preset preset : this.cosmetics.settings().colors().presets()) {
            if (!rules.check(preset.style()).allowed()) {
                continue;
            }
            buttons.add(styleButton(preset.style(), preset.name(), current,
                submission -> picked(submission, this.actions.setChatStyle(submission.player(), preset.style()), this::premiumColors)));
        }
        buttons.add(Button.of(ui(CosmeticsMessages.COLOR_CUSTOM_BUTTON), ui(CosmeticsMessages.COLOR_CUSTOM_TOOLTIP),
            submission -> customColor(submission.player())));
        show(player, this.services.templates().grid(ui(CosmeticsMessages.COLOR_PREMIUM_TITLE), lines, buttons,
            submission -> chatColor(submission.player())), null);
    }

    void customColor(Player player) {
        if (refused(player)) {
            return;
        }
        ChatStyle current = this.cosmetics.profiles().get(player.getUniqueId()).chatStyle();
        String from = "";
        String to = "";
        if (current instanceof ChatStyle.Solid solid && !solid.vanilla()) {
            from = ChatStyle.hexText(solid.color());
        } else if (current instanceof ChatStyle.Gradient gradient) {
            from = ChatStyle.hexText(gradient.from());
            to = ChatStyle.hexText(gradient.to());
        }
        List<Input> inputs = List.of(
            Templates.text("from", ui(CosmeticsMessages.COLOR_CUSTOM_FROM), from, 7),
            Templates.text("to", ui(CosmeticsMessages.COLOR_CUSTOM_TO), to, 7));
        View form = this.services.templates().form(ui(CosmeticsMessages.COLOR_CUSTOM_TITLE), List.of(), inputs,
            ui(CosmeticsMessages.COLOR_CUSTOM_SUBMIT), submission -> {
                String typedFrom = submission.values().text("from");
                String typedTo = submission.values().text("to");
                ChatStyle style = CosmeticsActions.typed(typedFrom, typedTo);
                if (style == null) {
                    String input = typedFrom.isBlank() ? typedTo : (ChatStyle.hex(typedFrom) == null ? typedFrom : typedTo);
                    submission.error(ui(CosmeticsMessages.COLOR_INVALID, Arg.text("input", input.isBlank() ? "-" : input)));
                    return;
                }
                picked(submission, this.actions.setChatStyle(submission.player(), style), this::premiumColors);
            }, submission -> premiumColors(submission.player()));
        show(player, tooltips(form, ui(CosmeticsMessages.COLOR_CUSTOM_SUBMIT_TOOLTIP)), null);
    }

    // ------------------------------------------------------------------ nickname

    void nick(Player player) {
        if (refused(player)) {
            return;
        }
        if (!player.hasPermission(CosmeticsNodes.NICK)) {
            this.actions.tell(player, this.actions.locked(CosmeticsMessages.RANK_BARON));
            return;
        }
        CosmeticsSettings.Nicknames rules = this.cosmetics.settings().nicknames();
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());
        boolean premium = player.hasPermission(CosmeticsNodes.NICK_GRADIENT);
        String shown = this.cosmetics.nick(player);
        List<Component> lines = List.of(shown == null ? ui(CosmeticsMessages.NICK_CURRENT_NONE)
            : ui(CosmeticsMessages.NICK_CURRENT, Arg.component("nick", this.cosmetics.name(player))));

        List<Input.Option> options = new ArrayList<>();
        options.add(new Input.Option("default", ui(CosmeticsMessages.NICK_STYLE_DEFAULT)));
        for (NamedTextColor color : this.cosmetics.settings().colors().basic()) {
            options.add(new Input.Option("c:" + NamedTextColor.NAMES.key(color), ChatStyle.vanilla(color).apply(
                this.lang.plain(CosmeticsMessages.colorName(color)))));
        }
        String initial = "default";
        ChatStyle stored = profile.nickStyle();
        String customInitial = "";
        if (premium) {
            ColorRules colourRules = this.cosmetics.rules();
            for (CosmeticsSettings.Preset preset : this.cosmetics.settings().colors().presets()) {
                if (colourRules.check(preset.style()).allowed()) {
                    options.add(new Input.Option("p:" + preset.id(), preset.style().apply(preset.name())));
                    if (preset.style().serialize().equals(stored.serialize())) {
                        initial = "p:" + preset.id();
                    }
                }
            }
            options.add(new Input.Option("custom", ui(CosmeticsMessages.NICK_STYLE_CUSTOM)));
        }
        if (stored instanceof ChatStyle.Solid solid && solid.vanilla()
            && this.cosmetics.settings().colors().basic().contains((NamedTextColor) solid.color())) {
            initial = "c:" + NamedTextColor.NAMES.key((NamedTextColor) solid.color());
        } else if (premium && !stored.none() && initial.equals("default")) {
            initial = "custom";
            customInitial = stored.serialize().replace(":", " ");
        }
        List<Input> inputs = new ArrayList<>();
        inputs.add(Templates.text("nick", ui(CosmeticsMessages.NICK_INPUT), profile.nick() == null ? "" : profile.nick(),
            rules.maxLength()));
        inputs.add(Templates.choice("style", ui(CosmeticsMessages.NICK_STYLE_INPUT), options, initial));
        if (premium) {
            inputs.add(Templates.text("custom", ui(CosmeticsMessages.NICK_CUSTOM_INPUT), customInitial, 15));
        }
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.of(ui(CosmeticsMessages.NICK_SAVE), ui(CosmeticsMessages.NICK_SAVE_TOOLTIP,
            Arg.text("min", Lang.number(rules.minLength())), Arg.text("max", Lang.number(rules.maxLength()))), this::saveNick).width(150));
        if (profile.nick() != null) {
            buttons.add(Button.of(ui(CosmeticsMessages.NICK_REMOVE), ui(CosmeticsMessages.NICK_REMOVE_TOOLTIP),
                submission -> picked(submission, this.actions.clearNick(submission.player()), this::menu)).width(150));
        }
        Button back = Button.of(ui(CoreMessages.UI_BACK), submission -> menu(submission.player())).width(Templates.WIDE);
        show(player, new View(View.Kind.FORM, ui(CosmeticsMessages.NICK_TITLE), body(lines), inputs, buttons, back, 2, true), null);
    }

    private void saveNick(Submission submission) {
        Player player = submission.player();
        String nick = submission.values().text("nick").strip();
        String choice = submission.values().choice("style");
        ChatStyle style;
        if (choice.equals("default")) {
            style = ChatStyle.NONE;
        } else if (choice.startsWith("c:")) {
            NamedTextColor color = NamedTextColor.NAMES.value(choice.substring(2).toLowerCase(Locale.ROOT));
            style = color == null ? ChatStyle.NONE : ChatStyle.vanilla(color);
        } else if (choice.startsWith("p:")) {
            String id = choice.substring(2);
            style = this.cosmetics.settings().colors().presets().stream().filter(p -> p.id().equals(id))
                .map(CosmeticsSettings.Preset::style).findFirst().orElse(ChatStyle.NONE);
        } else {
            String typed = submission.values().text("custom");
            style = CosmeticsActions.typed(typed, "");
            if (style == null) {
                submission.error(ui(CosmeticsMessages.COLOR_INVALID, Arg.text("input", typed.isBlank() ? "-" : typed)));
                return;
            }
        }
        picked(submission, this.actions.setNick(player, nick, style), this::menu);
    }

    // ------------------------------------------------------------------ tags

    void tags(Player player) {
        if (refused(player)) {
            return;
        }
        List<ChatTag> usable = new ArrayList<>();
        List<ChatTag> locked = new ArrayList<>();
        java.time.YearMonth month = CosmeticsService.month();
        for (ChatTag tag : this.cosmetics.settings().tags().values()) {
            if (this.cosmetics.usable(player, tag)) {
                usable.add(tag);
            } else if (tag.shownLocked(month)) {
                locked.add(tag);
            }
        }
        List<ChatTag> all = new ArrayList<>(usable);
        all.addAll(locked);
        ChatTag current = this.cosmetics.currentTag(player);
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());

        List<Component> lines = new ArrayList<>();
        lines.add(current == null ? ui(CosmeticsMessages.TAGS_CURRENT_NONE)
            : ui(CosmeticsMessages.TAGS_CURRENT, Arg.component("tag", current.display())));
        if (all.isEmpty()) {
            lines.add(ui(CosmeticsMessages.TAGS_EMPTY));
        }
        List<Button> buttons = new ArrayList<>();
        for (ChatTag tag : all) {
            boolean open = usable.contains(tag);
            boolean chosen = current != null && current.id().equals(tag.id());
            List<Component> tip = new ArrayList<>();
            if (!tag.description().isEmpty()) {
                tip.add(ui(CosmeticsMessages.TAG_HOVER, Arg.text("description", tag.description())));
            }
            if (chosen) {
                tip.add(ui(CosmeticsMessages.TAG_TOOLTIP_SELECTED));
            } else if (!open) {
                tip.add(ui(CosmeticsMessages.TAG_TOOLTIP_LOCKED, Arg.component("unlock", this.actions.unlock(tag))));
            }
            if (tag.claimable(month) && !profile.ownedTags().contains(tag.id())) {
                tip.add(ui(CosmeticsMessages.TAG_TOOLTIP_MONTHLY));
            } else if (profile.ownedTags().contains(tag.id())) {
                tip.add(ui(CosmeticsMessages.TAG_TOOLTIP_OWNED));
            }
            if (open && !chosen) {
                tip.add(ui(CosmeticsMessages.TAGS_PICK_TOOLTIP));
            }
            buttons.add(Button.of(option(tag.display(), chosen), Templates.lines(tip),
                submission -> picked(submission, this.actions.setTag(submission.player(), tag.id()), this::tags)));
        }
        if (current != null) {
            buttons.add(Button.of(ui(CosmeticsMessages.TAGS_REMOVE), ui(CosmeticsMessages.TAGS_REMOVE_TOOLTIP),
                submission -> picked(submission, this.actions.clearTag(submission.player()), this::tags)));
        }
        show(player, this.services.templates().grid(ui(CosmeticsMessages.TAGS_TITLE), lines, buttons,
            submission -> menu(submission.player())), null);
    }

    // ------------------------------------------------------------------ join and leave messages

    void joinMessages(Player player) {
        if (refused(player)) {
            return;
        }
        if (!player.hasPermission(CosmeticsNodes.JOIN)) {
            this.actions.tell(player, this.actions.locked(CosmeticsMessages.RANK_BARON));
            return;
        }
        if (!this.cosmetics.settings().join().enabled()) {
            this.actions.tell(player, CosmeticsActions.Outcome.refused(CosmeticsMessages.JOINMSG_OFF));
            return;
        }
        if (!player.hasPermission(CosmeticsNodes.JOIN_CUSTOM)) {
            List<Component> lines = List.of(this.cosmetics.render(player, true), this.cosmetics.render(player, false));
            Button own = Button.of(ui(CosmeticsMessages.JOINMSG_WRITE_OWN),
                ui(CosmeticsMessages.MENU_UNLOCK, Arg.component("unlock", ui(CosmeticsMessages.RANK_TYCOON))),
                submission -> submission.error(text(this.actions.locked(CosmeticsMessages.RANK_TYCOON))));
            show(player, this.services.templates().column(ui(CosmeticsMessages.JOINMSG_RANK_TITLE), lines, List.of(own),
                submission -> menu(submission.player())), null);
            return;
        }
        joinForm(player, null, null, null);
    }

    /** The custom message form, with typed values (null: the stored ones) and a refusal in red, or none. */
    private void joinForm(Player player, String joinText, String leaveText, Component error) {
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());
        int max = this.cosmetics.settings().join().maxLength();
        String join = joinText != null ? joinText : profile.joinMessage() == null ? "" : profile.joinMessage();
        String leave = leaveText != null ? leaveText : profile.leaveMessage() == null ? "" : profile.leaveMessage();
        List<Component> lines = List.of(
            this.cosmetics.render(player, true, join.isBlank() ? null : JoinText.clean(join)),
            this.cosmetics.render(player, false, leave.isBlank() ? null : JoinText.clean(leave)));
        List<Input> inputs = List.of(
            Templates.text("join", ui(CosmeticsMessages.JOINMSG_JOIN_INPUT), join, max),
            Templates.text("leave", ui(CosmeticsMessages.JOINMSG_LEAVE_INPUT), leave, max));
        List<Button> buttons = List.of(
            Button.of(ui(CosmeticsMessages.JOINMSG_SAVE), ui(CosmeticsMessages.JOINMSG_SAVE_TOOLTIP, Arg.text("max", Lang.number(max))),
                this::saveMessages).width(150),
            Button.of(ui(CosmeticsMessages.JOINMSG_PREVIEW), ui(CosmeticsMessages.JOINMSG_PREVIEW_TOOLTIP), this::previewMessages)
                .width(150),
            Button.of(ui(CosmeticsMessages.JOINMSG_RESET), ui(CosmeticsMessages.JOINMSG_RESET_TOOLTIP), submission -> {
                Player p = submission.player();
                CosmeticsActions.Outcome first = this.actions.setMessage(p, true, null);
                if (!first.ok()) {
                    submission.error(text(first));
                    return;
                }
                this.actions.setMessage(p, false, null);
                joinForm(p, null, null, null);
            }).width(150));
        Button back = Button.of(ui(CoreMessages.UI_BACK), submission -> menu(submission.player())).width(Templates.WIDE);
        show(player, new View(View.Kind.FORM, ui(CosmeticsMessages.JOINMSG_TITLE), body(lines), inputs, buttons, back, 2, true), error);
    }

    /** Checks typed messages (empty means "the rank line"); returns the first refusal or null. */
    private CosmeticsActions.Outcome checkTyped(String join, String leave) {
        for (String text : new String[] {join, leave}) {
            if (!text.isBlank()) {
                CosmeticsActions.Outcome refused = this.actions.checkMessage(JoinText.clean(text));
                if (refused != null) {
                    return refused;
                }
            }
        }
        return null;
    }

    /** Saves both messages and shows the form again with them (the lines above show how everyone sees them). */
    private void saveMessages(Submission submission) {
        Player player = submission.player();
        String join = submission.values().text("join");
        String leave = submission.values().text("leave");
        CosmeticsActions.Outcome blocked = this.actions.customBlocked(player);
        CosmeticsActions.Outcome refused = blocked != null ? blocked : checkTyped(join, leave);
        if (refused != null) {
            submission.error(text(refused));
            return;
        }
        this.actions.setMessage(player, true, join.isBlank() ? null : join);
        this.actions.setMessage(player, false, leave.isBlank() ? null : leave);
        joinForm(player, null, null, null);
    }

    private void previewMessages(Submission submission) {
        Player player = submission.player();
        String join = submission.values().text("join");
        String leave = submission.values().text("leave");
        CosmeticsActions.Outcome refused = checkTyped(join, leave);
        if (refused != null) {
            submission.error(text(refused));
            return;
        }
        joinForm(player, join, leave, null);
    }

    // ------------------------------------------------------------------ kill effects

    void killEffects(Player player) {
        if (refused(player)) {
            return;
        }
        if (!this.cosmetics.settings().killEffects().enabled()) {
            this.actions.tell(player, CosmeticsActions.Outcome.refused(CosmeticsMessages.KILL_OFF));
            return;
        }
        KillEffect current = this.cosmetics.killEffect(player);
        List<Component> lines = List.of(current == null ? ui(CosmeticsMessages.KILL_CURRENT_NONE)
            : ui(CosmeticsMessages.KILL_CURRENT, Arg.component("effect", ui(CosmeticsMessages.name(current)))));
        List<Button> buttons = new ArrayList<>();
        for (KillEffect effect : this.cosmetics.settings().killEffects().effects()) {
            boolean open = this.cosmetics.usable(player, effect);
            List<Component> tip = new ArrayList<>();
            tip.add(ui(CosmeticsMessages.description(effect)));
            if (effect == current) {
                tip.add(ui(CosmeticsMessages.COLOR_SELECTED_TOOLTIP));
            } else if (!open) {
                tip.add(ui(CosmeticsMessages.MENU_UNLOCK, Arg.component("unlock", ui(CosmeticsMessages.RANK_TYCOON))));
            } else {
                tip.add(ui(CosmeticsMessages.KILL_PICK_TOOLTIP));
            }
            buttons.add(Button.of(option(ui(CosmeticsMessages.name(effect)), effect == current), Templates.lines(tip),
                submission -> picked(submission, this.actions.setKillEffect(submission.player(), effect), this::killEffects)));
        }
        if (current != null) {
            buttons.add(Button.of(ui(CosmeticsMessages.KILL_NONE_BUTTON), ui(CosmeticsMessages.KILL_NONE_TOOLTIP),
                submission -> picked(submission, this.actions.setKillEffect(submission.player(), null), this::killEffects)));
        }
        show(player, this.services.templates().grid(ui(CosmeticsMessages.KILL_TITLE), lines, buttons,
            submission -> menu(submission.player())), null);
    }
}
