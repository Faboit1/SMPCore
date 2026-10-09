package net.siftvanilla.siftcore.core.text;

import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;

/**
 * The "Money format" setting (Display group, {@code money-format}): how amounts of money are written for a player,
 * the server's way ({@code server}, the default), always in full ({@code full}, $1,234,567) or short ({@code short},
 * $1.2m). {@link Lang} applies it to every amount rendered for that player ({@link Lang#viewing}); confirmations keep
 * every digit. Its text lives in {@code lang/core.yml} under {@code money-format}.
 */
public final class MoneyDisplay {

    /** The amount each option's label shows as a sample, in that option's format. */
    public static final long SAMPLE = 1_234_567L;
    /** The setting's place in the Display group (after the sidebar lines). */
    public static final int ORDER = 4;

    public static final MessageKey LABEL = MessageKey.ui("money-format.label");
    public static final MessageKey DESCRIPTION = MessageKey.ui("money-format.description");
    public static final MessageKey KEYWORDS = MessageKey.ui("money-format.keywords");
    public static final MessageKey OPTION_SERVER = MessageKey.ui("money-format.options.server", "sample");
    public static final MessageKey OPTION_FULL = MessageKey.ui("money-format.options.full", "sample");
    public static final MessageKey OPTION_SHORT = MessageKey.ui("money-format.options.short", "sample");

    /**
     * How amounts of money are written for the player. Each option's label carries a sample amount written in that
     * option's format (pinned, so every reader sees the same three samples, and a changed server format shows at once).
     */
    public static final Choice<MoneyStyle> MONEY_FORMAT = Choice.builder("money-format", MoneyStyle.SERVER, MoneyStyle::id)
        .option(MoneyStyle.SERVER.id(), MoneyStyle.SERVER, OPTION_SERVER, Arg.money("sample", SAMPLE, MoneyStyle.SERVER))
        .option(MoneyStyle.FULL.id(), MoneyStyle.FULL, OPTION_FULL, Arg.money("sample", SAMPLE, MoneyStyle.FULL))
        .option(MoneyStyle.SHORT.id(), MoneyStyle.SHORT, OPTION_SHORT, Arg.money("sample", SAMPLE, MoneyStyle.SHORT))
        .text(LABEL, DESCRIPTION).build();

    private MoneyDisplay() {
    }

    /**
     * Registers the setting in the Display group and binds it to {@code lang}, which reads it for every viewer it
     * renders for. Core calls it once at startup, after the shared settings.
     * <p>
     * An option that would write every amount the server's way under the current money format is not offered (in
     * full while {@code currency.compact-from} is 0, short while the server already shortens from {@code k} with one
     * decimal), and the setting is hidden when neither is; a player who stored one reads the server's way meanwhile.
     * Both follow {@code /sift reload} of the currency section.
     */
    public static void register(PlayerSettings settings, Lang lang) {
        settings.register(SettingCategories.DISPLAY, MONEY_FORMAT, SettingOptions.<MoneyStyle>builder().order(ORDER)
            .keywords(KEYWORDS)
            .availableWhen(() -> offered(lang.moneyFormat(), MoneyStyle.FULL) || offered(lang.moneyFormat(), MoneyStyle.SHORT))
            .optionAvailableWhen(MoneyStyle.FULL.id(), () -> offered(lang.moneyFormat(), MoneyStyle.FULL))
            .optionAvailableWhen(MoneyStyle.SHORT.id(), () -> offered(lang.moneyFormat(), MoneyStyle.SHORT))
            .build());
        lang.viewers(player -> settings.get(player, MONEY_FORMAT));
    }

    /** Whether an option changes anything under the server's money format (the server's way always does). */
    static boolean offered(MoneyFormat format, MoneyStyle style) {
        return style == MoneyStyle.SERVER || style.differsFromServer(format);
    }
}
