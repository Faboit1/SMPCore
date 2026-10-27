package net.siftvanilla.siftcore.core.player.options;

import java.util.ArrayList;
import java.util.List;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.text.Arg;

/**
 * Builders for choices over the shared vocabularies, so every notification, "who can", confirmation, announcement
 * and ping setting offers the same option ids and labels. Each returns a {@link Choice.Builder}: add
 * {@code text(label, description)} (and legacy values for a toggle that became a choice), then {@code build()}.
 */
public final class Choices {

    private Choices() {
    }

    /** A notification style choice offering {@code styles} in that order (at least two). */
    public static Choice.Builder<AlertStyle> alert(String id, AlertStyle defaultValue, AlertStyle... styles) {
        Choice.Builder<AlertStyle> builder = Choice.ofEnum(id, AlertStyle.class, AlertStyle::id, defaultValue);
        for (AlertStyle style : styles) {
            builder.option(style, style.label());
        }
        return builder;
    }

    /** A "who can" choice offering {@code audiences} in that order (at least two). */
    public static Choice.Builder<Audience> audience(String id, Audience defaultValue, Audience... audiences) {
        Choice.Builder<Audience> builder = Choice.ofEnum(id, Audience.class, Audience::id, defaultValue);
        for (Audience audience : audiences) {
            builder.option(audience, audience.label());
        }
        return builder;
    }

    /**
     * A ping sound choice offering {@code sounds} in that order, or every sound ({@code default}, {@code bell},
     * {@code pling}, {@code chime}, {@code off}) when none are given.
     */
    public static Choice.Builder<PingSound> ping(String id, PingSound defaultValue, PingSound... sounds) {
        Choice.Builder<PingSound> builder = Choice.ofEnum(id, PingSound.class, PingSound::id, defaultValue);
        for (PingSound sound : sounds.length == 0 ? PingSound.values() : sounds) {
            builder.option(sound, sound.label());
        }
        return builder;
    }

    /**
     * A "confirm from" choice: {@code server}, {@code always}, the presets (for example {@code 10k}, {@code 1m}) and,
     * when {@code never} is true, {@code never}. The default is {@code server}. Preset labels read "From $10,000" for
     * money (in the money colour) and "From 1,000" for shards (in the shards colour).
     */
    public static Choice.Builder<ConfirmAbove> confirmAbove(String id, Currency currency, boolean never, String... presets) {
        Choice.Builder<ConfirmAbove> builder = Choice.builder(id, ConfirmAbove.SERVER);
        builder.option(ConfirmAbove.SERVER.id(), ConfirmAbove.SERVER, OptionTexts.CONFIRM_SERVER);
        builder.option(ConfirmAbove.ALWAYS.id(), ConfirmAbove.ALWAYS, OptionTexts.CONFIRM_ALWAYS);
        for (ConfirmAbove preset : presets(presets, ConfirmAbove::preset)) {
            builder.option(preset.id(), preset, OptionTexts.FROM_AMOUNT, amount(currency, preset.amount()));
        }
        if (never) {
            builder.option(ConfirmAbove.NEVER.id(), ConfirmAbove.NEVER, OptionTexts.CONFIRM_NEVER);
        }
        return builder;
    }

    /**
     * An announcement filter: {@code all}, the presets (for example {@code 1m}, {@code 10m}) and {@code off}. The
     * default is {@code all}.
     */
    public static Choice.Builder<Announce> announce(String id, Currency currency, String... presets) {
        Choice.Builder<Announce> builder = Choice.builder(id, Announce.ALL);
        builder.option(Announce.ALL.id(), Announce.ALL, OptionTexts.ANNOUNCE_ALL);
        for (Announce preset : presets(presets, Announce::preset)) {
            builder.option(preset.id(), preset, OptionTexts.FROM_AMOUNT, amount(currency, preset.minimum()));
        }
        builder.option(Announce.OFF.id(), Announce.OFF, OptionTexts.ANNOUNCE_OFF);
        return builder;
    }

    private static <T> List<T> presets(String[] presets, java.util.function.Function<String, T> parse) {
        List<T> list = new ArrayList<>(presets.length);
        for (String preset : presets) {
            list.add(parse.apply(preset));
        }
        return list;
    }

    private static Arg amount(Currency currency, long amount) {
        return currency == Currency.MONEY ? Arg.money("amount", amount) : Arg.shards("amount", amount);
    }
}
