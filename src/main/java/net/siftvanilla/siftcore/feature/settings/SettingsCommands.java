package net.siftvanilla.siftcore.feature.settings;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.command.CommandSupport;
import net.siftvanilla.siftcore.core.command.SiftCommand;
import net.siftvanilla.siftcore.core.command.SimpleCommand;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.feature.settings.SettingsGroups.Shown;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;

/**
 * {@code /settings} (aliases {@code /options}, {@code /preferences}; players only):
 * <pre>
 * /settings                              the group list
 * /settings &lt;group&gt;                      a group's first page
 * /settings &lt;group&gt; &lt;setting&gt;            the setting's value, default and values (click opens its page)
 * /settings &lt;group&gt; &lt;setting&gt; &lt;value&gt;    changes it ("toggle" flips a switch)
 * /settings &lt;setting&gt; [value]            the same without the group (id, input key or a unique short name)
 * /settings search &lt;words&gt;              search results
 * /settings changed                      the settings you changed
 * /settings reset &lt;group|all&gt;           the reset confirmation
 * </pre>
 * Words resolve through {@link SettingsArgs}; suggestions name only what the player may change (never locked or hidden
 * settings). Changes go through the registry with the player's permissions, and the result is reported: changed,
 * already so, locked by the server, an option they can't pick, a refused change, or the values it takes.
 */
final class SettingsCommands {

    private final Services services;
    private final SettingsDialogs dialogs;
    private final Lang lang;
    private final Messenger messenger;

    SettingsCommands(Services services, SettingsDialogs dialogs) {
        this.services = services;
        this.dialogs = dialogs;
        this.lang = services.lang();
        this.messenger = services.messenger();
    }

    private PlayerSettings settings() {
        return this.services.settings();
    }

    SiftCommand command() {
        return new SimpleCommand("settings", List.of("options", "preferences"), "Opens your settings", SettingsFeature.COMMAND,
            label -> Commands.literal(label)
                .requires(CommandSupport.playerPermission(SettingsFeature.COMMAND))
                .executes(ctx -> run(ctx, player -> this.dialogs.open(player, null)))
                .then(Commands.literal("search")
                    .executes(ctx -> run(ctx, player -> this.dialogs.openSearch(player, "", null)))
                    .then(Commands.argument("words", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, player -> this.dialogs.openSearch(player, StringArgumentType.getString(ctx, "words"), null)))))
                .then(Commands.literal("changed")
                    .executes(ctx -> run(ctx, player -> this.dialogs.openChanged(player, null))))
                .then(Commands.literal("reset")
                    .executes(ctx -> run(ctx, player -> reset(player, "all")))
                    .then(Commands.argument("group", StringArgumentType.word())
                        .suggests(this::suggestReset)
                        .executes(ctx -> run(ctx, player -> reset(player, StringArgumentType.getString(ctx, "group"))))))
                .then(Commands.argument("first", StringArgumentType.word())
                    .suggests(this::suggestFirst)
                    .executes(ctx -> run(ctx, player -> one(player, StringArgumentType.getString(ctx, "first"))))
                    .then(Commands.argument("second", StringArgumentType.word())
                        .suggests(this::suggestSecond)
                        .executes(ctx -> run(ctx, player -> two(player, StringArgumentType.getString(ctx, "first"),
                            StringArgumentType.getString(ctx, "second"))))
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                            .suggests(this::suggestThird)
                            .executes(ctx -> run(ctx, player -> three(player, StringArgumentType.getString(ctx, "first"),
                                StringArgumentType.getString(ctx, "second"), StringArgumentType.getString(ctx, "value"))))))));
    }

    private int run(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = this.services.commands().player(ctx);
        if (player != null) {
            action.accept(player);
        }
        return CommandSupport.OK;
    }

    private SettingsArgs.Target first(Viewer viewer, String word) {
        return SettingsArgs.first(settings().registry(), entry -> this.dialogs.visible(entry, viewer), word);
    }

    private SettingsArgs.Target inGroup(Viewer viewer, SettingCategory category, String word) {
        return SettingsArgs.inCategory(settings().registry(), entry -> this.dialogs.visible(entry, viewer), category, word);
    }

    // ------------------------------------------------------------------ running

    /** {@code /settings <word>}: a group's page, a setting's value, or the command's own words. */
    void one(Player player, String word) {
        Viewer viewer = this.dialogs.viewer(player);
        switch (first(viewer, word)) {
            case SettingsArgs.Reserved reserved -> {
                switch (reserved.word()) {
                    case "search" -> this.dialogs.openSearch(player, "", null);
                    case "changed" -> this.dialogs.openChanged(player, null);
                    case "reset" -> reset(player, "all");
                    default -> this.dialogs.open(player, null);
                }
            }
            case SettingsArgs.Category category -> this.dialogs.openGroup(player, category.category().id(), null);
            case SettingsArgs.One one -> info(player, viewer, one.entry());
            case SettingsArgs.Unknown unknown -> unknownGroup(player, word);
        }
    }

    /** {@code /settings <group> <setting>} or {@code /settings <setting> <value>}. */
    void two(Player player, String a, String b) {
        Viewer viewer = this.dialogs.viewer(player);
        switch (first(viewer, a)) {
            case SettingsArgs.Category category -> {
                if (inGroup(viewer, category.category(), b) instanceof SettingsArgs.One one) {
                    info(player, viewer, one.entry());
                } else {
                    unknownIn(player, b, category.category());
                }
            }
            case SettingsArgs.One one -> change(player, viewer, one.entry(), b);
            case SettingsArgs.Reserved reserved -> reserved(player, reserved.word(), b);
            case SettingsArgs.Unknown unknown -> unknownWord(player, a);
        }
    }

    /** {@code /settings <group> <setting> <value>} or {@code /settings <setting> <value with spaces>}. */
    void three(Player player, String a, String b, String c) {
        Viewer viewer = this.dialogs.viewer(player);
        switch (first(viewer, a)) {
            case SettingsArgs.Category category -> {
                if (inGroup(viewer, category.category(), b) instanceof SettingsArgs.One one) {
                    change(player, viewer, one.entry(), c);
                } else {
                    unknownIn(player, b, category.category());
                }
            }
            case SettingsArgs.One one -> change(player, viewer, one.entry(), b + " " + c);
            case SettingsArgs.Reserved reserved -> reserved(player, reserved.word(), b + " " + c);
            case SettingsArgs.Unknown unknown -> unknownWord(player, a);
        }
    }

    /**
     * The command's own words followed by more words, typed in another case than the literal ({@code /settings Search
     * coords}): what the literal does. {@code all} is no command of its own.
     */
    private void reserved(Player player, String word, String rest) {
        switch (word) {
            case "search" -> this.dialogs.openSearch(player, rest, null);
            case "changed" -> this.dialogs.openChanged(player, null);
            case "reset" -> reset(player, rest.strip());
            default -> unknownWord(player, word);
        }
    }

    private void unknownGroup(Player player, String word) {
        this.messenger.send(player, SettingsMessages.UNKNOWN_GROUP, Arg.text("name", word.toLowerCase(Locale.ROOT)));
    }

    /** The first of several words names nothing the player sees: it could have been a group or a setting. */
    private void unknownWord(Player player, String word) {
        this.messenger.send(player, SettingsMessages.UNKNOWN_WORD, Arg.text("name", word.toLowerCase(Locale.ROOT)));
    }

    private void unknownIn(Player player, String word, SettingCategory category) {
        this.messenger.send(player, SettingsMessages.UNKNOWN_IN_GROUP, Arg.text("name", word.toLowerCase(Locale.ROOT)),
            Arg.text("group", this.lang.plain(category.label())));
    }

    /** {@code /settings reset <group|all>}: the confirmation, or why there is nothing to reset. */
    private void reset(Player player, String target) {
        if (this.dialogs.openReset(player, target, null)) {
            return;
        }
        if ("all".equalsIgnoreCase(target)) {
            this.messenger.send(player, SettingsMessages.RESET_NOTHING_ALL);
            return;
        }
        Shown group = SettingsGroups.find(this.dialogs.groups(player), target);
        if (group == null) {
            unknownGroup(player, target);
        } else {
            this.messenger.send(player, SettingsMessages.RESET_NOTHING, Arg.text("label", this.lang.plain(group.category().label())));
        }
    }

    /** A setting's value, default and allowed values in chat; clicking the line opens the page holding it. */
    private void info(Player player, Viewer viewer, Registry.Entry<?> entry) {
        String label = this.lang.plain(entry.setting().label());
        if (settings().locked(entry.setting())) {
            this.messenger.chat(player, SettingsMessages.INFO_LOCKED, Arg.text("label", label),
                Arg.text("value", this.dialogs.displayed(viewer, entry)));
            return;
        }
        Component line = this.lang.get(SettingsMessages.INFO, Arg.text("label", label), Arg.text("value", this.dialogs.displayed(viewer, entry)),
            Arg.text("default", this.dialogs.defaultText(entry)), Arg.text("values", values(viewer, entry)));
        View page = this.dialogs.settingPage(viewer, entry.id(), null);
        if (page != null) {
            line = line.clickEvent(ClickEvent.showDialog(this.services.dialogs().inline(player, page)))
                .hoverEvent(HoverEvent.showText(this.lang.get(SettingsMessages.INFO_HOVER)));
        }
        player.sendMessage(line);
    }

    /** Changes a setting from typed text and says how it went. */
    private <T> void change(Player player, Viewer viewer, Registry.Entry<T> entry, String input) {
        PlayerSetting<T> setting = entry.setting();
        String label = this.lang.plain(setting.label());
        if (settings().locked(setting)) {
            this.messenger.send(player, SettingsMessages.LOCKED, Arg.text("label", label));
            return;
        }
        SettingsArgs.Parsed<T> parsed = SettingsArgs.parse(setting, input, viewer.value(setting),
            settings().options(entry, viewer::has), option -> option.text(this.lang));
        if (!parsed.ok()) {
            if (parsed.problem() == SettingsArgs.Problem.NOT_OFFERED) {
                this.messenger.send(player, SettingsMessages.NOT_OFFERED, Arg.text("label", label), Arg.text("value", input.strip()));
            } else {
                this.messenger.send(player, SettingsMessages.INVALID_VALUE, Arg.text("label", label), Arg.text("values", values(viewer, entry)));
            }
            return;
        }
        SetResult result = settings().set(player, setting, parsed.value(), Change.command(player.getName()));
        switch (result) {
            case CHANGED -> this.dialogs.confirm(player, entry);
            case UNCHANGED -> this.messenger.send(player, SettingsMessages.ALREADY, Arg.text("label", label),
                Arg.text("value", this.dialogs.displayed(viewer, entry)));
            case LOCKED -> this.messenger.send(player, SettingsMessages.LOCKED, Arg.text("label", label));
            case NOT_ALLOWED -> this.messenger.send(player, SettingsMessages.NOT_OFFERED, Arg.text("label", label),
                Arg.text("value", input.strip()));
            case INVALID -> this.messenger.send(player, SettingsMessages.INVALID_VALUE, Arg.text("label", label),
                Arg.text("values", values(viewer, entry)));
            case CANCELLED -> this.messenger.send(player, SettingsMessages.REFUSED, Arg.text("label", label));
            case UNKNOWN -> this.messenger.send(player, SettingsMessages.UNKNOWN_SETTING, Arg.text("name", entry.id()));
        }
    }

    /** The values a setting takes for the viewer, as text: "on, off or toggle", option ids, or a range. */
    String values(Viewer viewer, Registry.Entry<?> entry) {
        return valuesText(this.lang, entry.setting(), offeredIds(viewer, entry));
    }

    static String valuesText(Lang lang, PlayerSetting<?> setting, List<String> offered) {
        return switch (setting) {
            case Toggle toggle -> lang.plain(SettingsMessages.VALUES_TOGGLE);
            case Choice<?> choice -> String.join(", ", offered.isEmpty() ? choice.optionIds() : offered);
            case NumberSetting number -> number.step() == 1
                ? lang.plain(SettingsMessages.VALUES_RANGE, Arg.number("min", number.min()), Arg.number("max", number.max()))
                : lang.plain(SettingsMessages.VALUES_STEPS, Arg.number("min", number.min()), Arg.number("max", number.max()),
                    Arg.number("step", number.step()));
        };
    }

    private <T> List<String> offeredIds(Viewer viewer, Registry.Entry<T> entry) {
        List<String> ids = new ArrayList<>();
        for (Choice.Option<T> option : settings().options(entry, viewer::has)) {
            ids.add(option.id());
        }
        return ids;
    }

    // ------------------------------------------------------------------ suggestions (any thread: snapshot reads only)

    private CompletableFuture<Suggestions> suggestFirst(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            Viewer viewer = this.dialogs.viewer(player);
            List<Shown> groups = this.dialogs.groups(viewer);
            List<SettingCategory> categories = groups.stream().map(Shown::category).toList();
            List<Registry.Entry<?>> changeable = this.dialogs.all(groups).stream().filter(e -> this.dialogs.changeable(e, viewer)).toList();
            SettingsArgs.suggestFirst(categories, changeable, builder.getRemaining()).forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestSecond(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            Viewer viewer = this.dialogs.viewer(player);
            String a = argument(ctx, "first");
            switch (first(viewer, a)) {
                case SettingsArgs.Category category -> {
                    List<Registry.Entry<?>> changeable = new ArrayList<>();
                    for (Registry.Entry<?> entry : settings().registry().in(category.category().id())) {
                        if (this.dialogs.changeable(entry, viewer)) {
                            changeable.add(entry);
                        }
                    }
                    SettingsArgs.suggestSettings(changeable, builder.getRemaining()).forEach(builder::suggest);
                }
                case SettingsArgs.One one -> suggestValues(viewer, one.entry(), builder);
                default -> {
                }
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestThird(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            Viewer viewer = this.dialogs.viewer(player);
            if (first(viewer, argument(ctx, "first")) instanceof SettingsArgs.Category category
                && inGroup(viewer, category.category(), argument(ctx, "second")) instanceof SettingsArgs.One one) {
                suggestValues(viewer, one.entry(), builder);
            }
        }
        return builder.buildFuture();
    }

    private void suggestValues(Viewer viewer, Registry.Entry<?> entry, SuggestionsBuilder builder) {
        if (this.dialogs.changeable(entry, viewer)) {
            SettingsArgs.suggestValues(entry.setting(), offeredIds(viewer, entry), builder.getRemaining()).forEach(builder::suggest);
        }
    }

    private CompletableFuture<Suggestions> suggestReset(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            Viewer viewer = this.dialogs.viewer(player);
            String prefix = builder.getRemainingLowerCase();
            for (Shown group : this.dialogs.groups(viewer)) {
                if (group.id().startsWith(prefix) && !this.dialogs.changed(viewer, group.entries()).isEmpty()) {
                    builder.suggest(group.id());
                }
            }
            if ("all".startsWith(prefix)) {
                builder.suggest("all");
            }
        }
        return builder.buildFuture();
    }

    private static String argument(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
