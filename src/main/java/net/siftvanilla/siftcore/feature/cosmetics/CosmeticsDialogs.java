package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.Submission;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * The cosmetics dialogs: the menu, chat colours (vanilla, ready-made and mixed), the nickname form, chat tags, join
 * and leave messages and kill effects. Colour and tag buttons show the colour or tag itself, so players see what
 * they pick. Locked perks stay visible and say which rank unlocks them. Every click goes through
 * {@link CosmeticsActions}, which checks it again; picking something finishes and closes the dialog, moving between
 * screens replaces the dialog.
 */
final class CosmeticsDialogs {

    /** Tag buttons per page. */
    static final int TAGS_PER_PAGE = 16;

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

    private void show(Player player, View view) {
        this.services.dialogs().show(player, view);
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

    /** Shows the outcome of a pick: success on the action bar (the dialog closes), a refusal re-opens it with the reason. */
    private void finish(Submission submission, CosmeticsActions.Outcome outcome) {
        if (outcome.ok()) {
            this.actions.tell(submission.player(), outcome);
            submission.close();
        } else {
            submission.error(this.lang.get(outcome.key(), outcome.args()));
        }
    }

    private static List<Body> body(List<Component> lines) {
        return List.of(Body.text(Component.join(JoinConfiguration.newlines(), lines)));
    }

    private Component tooltip(MessageKey key, Arg... args) {
        return this.lang.get(key, args);
    }

    // ------------------------------------------------------------------ menu

    void menu(Player player) {
        if (refused(player)) {
            return;
        }
        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.MENU_BODY));
        lines.add(Component.empty());
        ChatStyle chat = this.cosmetics.chatStyle(player);
        lines.add(line(CosmeticsMessages.MENU_CHAT_COLOR, chat.none() ? this.lang.get(CosmeticsMessages.NONE) : this.cosmetics.styleName(chat)));
        String nick = this.cosmetics.nick(player);
        lines.add(line(CosmeticsMessages.MENU_NICK, nick == null ? this.lang.get(CosmeticsMessages.NONE) : this.cosmetics.name(player)));
        ChatTag tag = this.cosmetics.currentTag(player);
        lines.add(line(CosmeticsMessages.MENU_TAG, tag == null ? this.lang.get(CosmeticsMessages.NONE) : tag.display()));
        lines.add(line(CosmeticsMessages.MENU_JOIN, this.lang.get(joinState(player))));
        KillEffect effect = this.cosmetics.killEffect(player);
        lines.add(line(CosmeticsMessages.MENU_KILL, this.lang.get(effect == null ? CosmeticsMessages.NONE : CosmeticsMessages.name(effect))));

        boolean colours = player.hasPermission(CosmeticsNodes.CHAT_COLOR) || player.hasPermission(CosmeticsNodes.CHAT_COLOR_HEX);
        boolean nicks = player.hasPermission(CosmeticsNodes.NICK);
        boolean joins = player.hasPermission(CosmeticsNodes.JOIN);
        List<Button> buttons = List.of(
            entry(CosmeticsMessages.MENU_CHAT_COLOR, colours, CosmeticsMessages.RANK_BARON, this::chatColor),
            entry(CosmeticsMessages.MENU_NICK, nicks, CosmeticsMessages.RANK_BARON, this::nick),
            entry(CosmeticsMessages.MENU_TAG, true, CosmeticsMessages.RANK_PROSPECTOR, p -> tags(p, 0)),
            entry(CosmeticsMessages.MENU_JOIN, joins, CosmeticsMessages.RANK_BARON, this::joinMessages),
            entry(CosmeticsMessages.MENU_KILL, true, CosmeticsMessages.RANK_TYCOON, this::killEffects));
        show(player, this.services.templates().listWithBody(this.lang.get(CosmeticsMessages.MENU_TITLE), body(lines), buttons, 2, null));
    }

    private Component line(MessageKey label, Component value) {
        return this.lang.get(CosmeticsMessages.MENU_LINE, Arg.component("label", this.lang.get(label)), Arg.component("value", value));
    }

    private MessageKey joinState(Player player) {
        if (!player.hasPermission(CosmeticsNodes.JOIN) || !this.cosmetics.settings().join().enabled()) {
            return CosmeticsMessages.MENU_JOIN_DEFAULT;
        }
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());
        boolean custom = player.hasPermission(CosmeticsNodes.JOIN_CUSTOM) && (profile.joinMessage() != null || profile.leaveMessage() != null);
        return custom ? CosmeticsMessages.MENU_JOIN_CUSTOM : CosmeticsMessages.MENU_JOIN_RANK;
    }

    /** A menu button: opens the screen, or (locked) says which rank unlocks it. */
    private Button entry(MessageKey label, boolean unlocked, MessageKey rank, java.util.function.Consumer<Player> open) {
        Component tooltip = unlocked ? tooltip(CosmeticsMessages.MENU_OPEN)
            : tooltip(CosmeticsMessages.MENU_UNLOCK, Arg.component("unlock", this.lang.get(rank)));
        return Button.of(this.lang.get(label), tooltip, submission -> {
            if (unlocked) {
                open.accept(submission.player());
            } else {
                CosmeticsActions.Outcome locked = this.actions.locked(rank);
                submission.error(this.lang.get(locked.key(), locked.args()));
            }
        }).width(150);
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
        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.COLOR_BODY));
        lines.add(this.lang.get(CosmeticsMessages.COLOR_CURRENT, Arg.component("preview", sample(current))));
        List<Button> buttons = new ArrayList<>();
        for (NamedTextColor color : this.cosmetics.settings().colors().basic()) {
            ChatStyle style = ChatStyle.vanilla(color);
            buttons.add(styleButton(style, this.lang.plain(CosmeticsMessages.colorName(color)), current,
                submission -> finish(submission, this.actions.setChatStyle(submission.player(), style))));
        }
        boolean premium = player.hasPermission(CosmeticsNodes.CHAT_COLOR_HEX);
        buttons.add(Button.of(this.lang.get(CosmeticsMessages.COLOR_MORE),
            premium ? tooltip(CosmeticsMessages.COLOR_MORE_TOOLTIP) : tooltip(CosmeticsMessages.MENU_UNLOCK,
                Arg.component("unlock", this.lang.get(CosmeticsMessages.RANK_TYCOON))),
            submission -> {
                if (submission.player().hasPermission(CosmeticsNodes.CHAT_COLOR_HEX)) {
                    premiumColors(submission.player());
                } else {
                    CosmeticsActions.Outcome locked = this.actions.locked(CosmeticsMessages.RANK_TYCOON);
                    submission.error(this.lang.get(locked.key(), locked.args()));
                }
            }).width(150));
        buttons.add(Button.of(this.lang.get(CosmeticsMessages.COLOR_RESET_BUTTON),
            submission -> finish(submission, this.actions.setChatStyle(submission.player(), ChatStyle.NONE))).width(150).closes());
        show(player, this.services.templates().listWithBody(this.lang.get(CosmeticsMessages.COLOR_TITLE), body(lines), buttons, 2,
            submission -> menu(submission.player())));
    }

    /** "This is how your messages look" in a style. */
    private Component sample(ChatStyle style) {
        String text = this.lang.plain(CosmeticsMessages.COLOR_SAMPLE);
        return style.none() ? Component.text(text, this.lang.style().palette().primary()) : style.apply(text);
    }

    /** A button labelled in its own colour; the tooltip shows a sample (or that it is the current one). */
    private Button styleButton(ChatStyle style, String name, ChatStyle current, Button.Handler handler) {
        Component tooltip = style.serialize().equals(current.serialize()) ? tooltip(CosmeticsMessages.COLOR_SELECTED_TOOLTIP)
            : style instanceof ChatStyle.Gradient ? Component.join(JoinConfiguration.newlines(), sample(style),
                tooltip(CosmeticsMessages.COLOR_GRADIENT_TOOLTIP))
            : sample(style);
        return Button.of(style.apply(name), tooltip, handler).width(150).closes();
    }

    void premiumColors(Player player) {
        if (refused(player)) {
            return;
        }
        ChatStyle current = this.cosmetics.chatStyle(player);
        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.COLOR_PREMIUM_BODY));
        lines.add(this.lang.get(CosmeticsMessages.COLOR_CURRENT, Arg.component("preview", sample(current))));
        List<Button> buttons = new ArrayList<>();
        ColorRules rules = this.cosmetics.rules();
        for (CosmeticsSettings.Preset preset : this.cosmetics.settings().colors().presets()) {
            if (!rules.check(preset.style()).allowed()) {
                continue;
            }
            buttons.add(styleButton(preset.style(), preset.name(), current,
                submission -> finish(submission, this.actions.setChatStyle(submission.player(), preset.style()))));
        }
        buttons.add(Button.of(this.lang.get(CosmeticsMessages.COLOR_CUSTOM_BUTTON), submission -> customColor(submission.player())).width(150));
        show(player, this.services.templates().listWithBody(this.lang.get(CosmeticsMessages.COLOR_PREMIUM_TITLE), body(lines), buttons, 2,
            submission -> chatColor(submission.player())));
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
            Templates.text("from", this.lang.get(CosmeticsMessages.COLOR_CUSTOM_FROM), from, 7),
            Templates.text("to", this.lang.get(CosmeticsMessages.COLOR_CUSTOM_TO), to, 7));
        show(player, this.services.templates().form(this.lang.get(CosmeticsMessages.COLOR_CUSTOM_TITLE),
            this.lang.lines(CosmeticsMessages.COLOR_CUSTOM_BODY), inputs, this.lang.get(CosmeticsMessages.COLOR_CUSTOM_SUBMIT), submission -> {
                String typedFrom = submission.values().text("from");
                String typedTo = submission.values().text("to");
                ChatStyle style = CosmeticsActions.typed(typedFrom, typedTo);
                if (style == null) {
                    String input = typedFrom.isBlank() ? typedTo : (ChatStyle.hex(typedFrom) == null ? typedFrom : typedTo);
                    submission.error(this.lang.get(CosmeticsMessages.COLOR_INVALID, Arg.text("input", input.isBlank() ? "-" : input)));
                    return;
                }
                finish(submission, this.actions.setChatStyle(submission.player(), style));
            }, submission -> premiumColors(submission.player())));
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
        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.NICK_BODY, Arg.number("min", rules.minLength()),
            Arg.number("max", rules.maxLength())));
        String shown = this.cosmetics.nick(player);
        lines.add(shown == null ? this.lang.get(CosmeticsMessages.NICK_CURRENT_NONE)
            : this.lang.get(CosmeticsMessages.NICK_CURRENT, Arg.component("nick", this.cosmetics.name(player))));

        List<Input.Option> options = new ArrayList<>();
        options.add(new Input.Option("default", this.lang.get(CosmeticsMessages.NICK_STYLE_DEFAULT)));
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
            options.add(new Input.Option("custom", this.lang.get(CosmeticsMessages.NICK_STYLE_CUSTOM)));
        }
        if (stored instanceof ChatStyle.Solid solid && solid.vanilla()
            && this.cosmetics.settings().colors().basic().contains((NamedTextColor) solid.color())) {
            initial = "c:" + NamedTextColor.NAMES.key((NamedTextColor) solid.color());
        } else if (premium && !stored.none() && initial.equals("default")) {
            initial = "custom";
            customInitial = stored.serialize().replace(":", " ");
        }
        List<Input> inputs = new ArrayList<>();
        inputs.add(Templates.text("nick", this.lang.get(CosmeticsMessages.NICK_INPUT), profile.nick() == null ? "" : profile.nick(),
            rules.maxLength()));
        inputs.add(Templates.choice("style", this.lang.get(CosmeticsMessages.NICK_STYLE_INPUT), options, initial));
        if (premium) {
            inputs.add(Templates.text("custom", this.lang.get(CosmeticsMessages.NICK_CUSTOM_INPUT), customInitial, 15));
        }
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.of(this.lang.get(CosmeticsMessages.NICK_SAVE), submission -> saveNick(submission)).width(150).closes());
        if (profile.nick() != null) {
            buttons.add(Button.of(this.lang.get(CosmeticsMessages.NICK_REMOVE),
                submission -> finish(submission, this.actions.clearNick(submission.player()))).width(150).closes());
        }
        Button back = Button.of(this.lang.get(CoreMessages.UI_BACK), submission -> menu(submission.player())).width(Templates.WIDE);
        show(player, new View(View.Kind.FORM, this.lang.get(CosmeticsMessages.NICK_TITLE), body(lines), inputs, buttons, back, 2, true));
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
                submission.error(this.lang.get(CosmeticsMessages.COLOR_INVALID, Arg.text("input", typed.isBlank() ? "-" : typed)));
                return;
            }
        }
        finish(submission, this.actions.setNick(player, nick, style));
    }

    // ------------------------------------------------------------------ tags

    void tags(Player player, int page) {
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
        int pages = Math.max(1, (all.size() + TAGS_PER_PAGE - 1) / TAGS_PER_PAGE);
        int shownPage = Math.clamp(page, 0, pages - 1);
        ChatTag current = this.cosmetics.currentTag(player);
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());

        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.TAGS_BODY, Arg.number("count", usable.size()),
            Arg.number("total", all.size())));
        lines.add(current == null ? this.lang.get(CosmeticsMessages.TAGS_CURRENT_NONE)
            : this.lang.get(CosmeticsMessages.TAGS_CURRENT, Arg.component("tag", current.display())));
        if (all.isEmpty()) {
            lines.add(this.lang.get(CosmeticsMessages.TAGS_EMPTY));
        }
        if (pages > 1) {
            lines.add(this.lang.get(CosmeticsMessages.PAGE, Arg.number("page", shownPage + 1), Arg.number("pages", pages)));
        }
        List<Button> buttons = new ArrayList<>();
        for (ChatTag tag : all.subList(shownPage * TAGS_PER_PAGE, Math.min(all.size(), (shownPage + 1) * TAGS_PER_PAGE))) {
            boolean open = usable.contains(tag);
            List<Component> tip = new ArrayList<>();
            if (!tag.description().isEmpty()) {
                tip.add(this.lang.get(CosmeticsMessages.TAG_HOVER, Arg.text("description", tag.description())));
            }
            if (current != null && current.id().equals(tag.id())) {
                tip.add(this.lang.get(CosmeticsMessages.TAG_TOOLTIP_SELECTED));
            } else if (!open) {
                tip.add(this.lang.get(CosmeticsMessages.TAG_TOOLTIP_LOCKED, Arg.component("unlock", this.actions.unlock(tag))));
            }
            if (tag.claimable(month) && !profile.ownedTags().contains(tag.id())) {
                tip.add(this.lang.get(CosmeticsMessages.TAG_TOOLTIP_MONTHLY));
            } else if (profile.ownedTags().contains(tag.id())) {
                tip.add(this.lang.get(CosmeticsMessages.TAG_TOOLTIP_OWNED));
            }
            Button button = Button.of(tag.display(), tip.isEmpty() ? null : Component.join(JoinConfiguration.newlines(), tip),
                submission -> finish(submission, this.actions.setTag(submission.player(), tag.id()))).width(150);
            buttons.add(open ? button.closes() : button);
        }
        if (current != null) {
            buttons.add(Button.of(this.lang.get(CosmeticsMessages.TAGS_REMOVE),
                submission -> finish(submission, this.actions.clearTag(submission.player()))).width(150).closes());
        }
        if (shownPage > 0) {
            buttons.add(Button.of(this.lang.get(CosmeticsMessages.PREVIOUS_PAGE), submission -> tags(submission.player(), shownPage - 1)).width(150));
        }
        if (shownPage < pages - 1) {
            buttons.add(Button.of(this.lang.get(CosmeticsMessages.NEXT_PAGE), submission -> tags(submission.player(), shownPage + 1)).width(150));
        }
        show(player, this.services.templates().listWithBody(this.lang.get(CosmeticsMessages.TAGS_TITLE), body(lines), buttons, 2,
            submission -> menu(submission.player())));
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
            List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.JOINMSG_RANK_BODY));
            lines.add(Component.empty());
            lines.add(this.cosmetics.render(player, true));
            lines.add(this.cosmetics.render(player, false));
            show(player, this.services.templates().listWithBody(this.lang.get(CosmeticsMessages.JOINMSG_RANK_TITLE), body(lines),
                List.of(), 1, submission -> menu(submission.player())));
            return;
        }
        joinForm(player, null, null);
    }

    /** The custom message form, with typed values (null: the stored ones). */
    private void joinForm(Player player, String joinText, String leaveText) {
        Profile profile = this.cosmetics.profiles().get(player.getUniqueId());
        int max = this.cosmetics.settings().join().maxLength();
        String join = joinText != null ? joinText : profile.joinMessage() == null ? "" : profile.joinMessage();
        String leave = leaveText != null ? leaveText : profile.leaveMessage() == null ? "" : profile.leaveMessage();
        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.JOINMSG_BODY, Arg.number("max", max)));
        lines.add(Component.empty());
        lines.add(this.cosmetics.render(player, true, join.isBlank() ? null : JoinText.clean(join)));
        lines.add(this.cosmetics.render(player, false, leave.isBlank() ? null : JoinText.clean(leave)));
        List<Input> inputs = List.of(
            Templates.text("join", this.lang.get(CosmeticsMessages.JOINMSG_JOIN_INPUT), join, max),
            Templates.text("leave", this.lang.get(CosmeticsMessages.JOINMSG_LEAVE_INPUT), leave, max));
        List<Button> buttons = List.of(
            Button.of(this.lang.get(CosmeticsMessages.JOINMSG_SAVE), this::saveMessages).width(150).closes(),
            Button.of(this.lang.get(CosmeticsMessages.JOINMSG_PREVIEW), this::previewMessages).width(150),
            Button.of(this.lang.get(CosmeticsMessages.JOINMSG_RESET), submission -> {
                Player p = submission.player();
                CosmeticsActions.Outcome first = this.actions.setMessage(p, true, null);
                if (!first.ok()) {
                    submission.error(this.lang.get(first.key(), first.args()));
                    return;
                }
                this.actions.setMessage(p, false, null);
                this.actions.tell(p, CosmeticsActions.Outcome.ok(CosmeticsMessages.JOINMSG_RESET_DONE));
                submission.close();
            }).width(150).closes());
        Button back = Button.of(this.lang.get(CoreMessages.UI_BACK), submission -> menu(submission.player())).width(Templates.WIDE);
        show(player, new View(View.Kind.FORM, this.lang.get(CosmeticsMessages.JOINMSG_TITLE), body(lines), inputs, buttons, back, 2, true));
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

    private void saveMessages(Submission submission) {
        Player player = submission.player();
        String join = submission.values().text("join");
        String leave = submission.values().text("leave");
        CosmeticsActions.Outcome blocked = this.actions.customBlocked(player);
        CosmeticsActions.Outcome refused = blocked != null ? blocked : checkTyped(join, leave);
        if (refused != null) {
            submission.error(this.lang.get(refused.key(), refused.args()));
            return;
        }
        this.actions.setMessage(player, true, join.isBlank() ? null : join);
        this.actions.setMessage(player, false, leave.isBlank() ? null : leave);
        this.actions.tell(player, CosmeticsActions.Outcome.ok(CosmeticsMessages.JOINMSG_SAVED));
        submission.close();
    }

    private void previewMessages(Submission submission) {
        Player player = submission.player();
        String join = submission.values().text("join");
        String leave = submission.values().text("leave");
        CosmeticsActions.Outcome refused = checkTyped(join, leave);
        if (refused != null) {
            submission.error(this.lang.get(refused.key(), refused.args()));
            return;
        }
        joinForm(player, join, leave);
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
        List<Component> lines = new ArrayList<>(this.lang.lines(CosmeticsMessages.KILL_BODY));
        lines.add(current == null ? this.lang.get(CosmeticsMessages.KILL_CURRENT_NONE)
            : this.lang.get(CosmeticsMessages.KILL_CURRENT, Arg.component("effect", this.lang.get(CosmeticsMessages.name(current)))));
        List<Button> buttons = new ArrayList<>();
        for (KillEffect effect : this.cosmetics.settings().killEffects().effects()) {
            boolean open = this.cosmetics.usable(player, effect);
            List<Component> tip = new ArrayList<>();
            tip.add(this.lang.get(CosmeticsMessages.description(effect)));
            if (effect == current) {
                tip.add(this.lang.get(CosmeticsMessages.COLOR_SELECTED_TOOLTIP));
            } else if (!open) {
                tip.add(this.lang.get(CosmeticsMessages.MENU_UNLOCK, Arg.component("unlock", this.lang.get(CosmeticsMessages.RANK_TYCOON))));
            }
            Button button = Button.of(this.lang.get(CosmeticsMessages.name(effect)), Component.join(JoinConfiguration.newlines(), tip),
                submission -> finish(submission, this.actions.setKillEffect(submission.player(), effect))).width(150);
            buttons.add(open ? button.closes() : button);
        }
        if (current != null) {
            buttons.add(Button.of(this.lang.get(CosmeticsMessages.KILL_NONE_BUTTON),
                submission -> finish(submission, this.actions.setKillEffect(submission.player(), null))).width(150).closes());
        }
        show(player, this.services.templates().listWithBody(this.lang.get(CosmeticsMessages.KILL_TITLE), body(lines), buttons, 2,
            submission -> menu(submission.player())));
    }
}
