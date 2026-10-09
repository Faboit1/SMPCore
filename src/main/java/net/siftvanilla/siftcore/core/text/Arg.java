package net.siftvanilla.siftcore.core.text;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.money.MoneyStyle;

/**
 * A typed placeholder value. Text is always inserted literally (never parsed), so player-supplied strings cannot
 * inject formatting, click or hover events. Money renders in the money colour, shards in the shards colour; every other
 * number renders in the primary colour. Formatting happens in {@link Lang}.
 * <p>
 * A placeholder wins over a palette tag of the same name: in a message that declares {@code <shards>} as a placeholder,
 * {@code <shards>} is that value, not the colour. Pass shard amounts with {@link #shards}, which colours them.
 * <p>
 * Money is written the way its reader chose (their "Money format" setting, see {@link Lang#viewing}): the server's
 * way, in full or short. {@link #exact} pins an amount to every digit, and messages of a confirmation (a key path with a
 * {@code confirm} part, {@link MessageKey#confirmation()}) write every amount in full.
 */
public sealed interface Arg {

    String name();

    /**
     * A money amount, e.g. {@code $1,500}, in the money colour.
     *
     * @param style the format it is always written in, or null for its reader's choice
     */
    record Money(String name, long amount, MoneyStyle style) implements Arg {

        /** An amount written the way its reader chose. */
        public Money(String name, long amount) {
            this(name, amount, null);
        }
    }

    /** A currency amount: money renders like {@link Money}; shards render as a number in the shards colour. */
    record Amount(String name, Currency currency, long amount) implements Arg {
    }

    /** A whole number with grouping, e.g. {@code 1,500}, in the primary colour. */
    record Number(String name, long value) implements Arg {
    }

    /** A decimal shown with up to two decimals, e.g. a KDR of {@code 1.25}. */
    record Decimal(String name, double value) implements Arg {
    }

    /** Untrusted or plain text, inserted literally and inheriting the surrounding colour. */
    record Text(String name, String value) implements Arg {
    }

    /** A pre-built component (already safe), e.g. a player name with a hover card. */
    record Rich(String name, Component value) implements Arg {
    }

    /** A duration such as {@code 1h 5m}, in the primary colour. */
    record Time(String name, Duration value) implements Arg {
    }

    /** Money written the way the reader chose ({@code $1,500}, {@code $1.2m} or {@code $1,234,567}). */
    static Arg money(String name, long amount) {
        return new Money(name, amount);
    }

    /** Money always written in one style, whoever reads it (for example a sample of each style). */
    static Arg money(String name, long amount, MoneyStyle style) {
        return new Money(name, amount, style);
    }

    /** Money with every digit ({@code $1,234,567}) whoever reads it: an amount the player must agree to. */
    static Arg exact(String name, long amount) {
        return new Money(name, amount, MoneyStyle.FULL);
    }

    static Arg amount(String name, Currency currency, long amount) {
        return new Amount(name, currency, amount);
    }

    /** A shard amount with grouping, e.g. {@code 1,500}, in the shards colour. */
    static Arg shards(String name, long amount) {
        return new Amount(name, Currency.SHARDS, amount);
    }

    static Arg number(String name, long value) {
        return new Number(name, value);
    }

    static Arg decimal(String name, double value) {
        return new Decimal(name, value);
    }

    static Arg text(String name, String value) {
        return new Text(name, value == null ? "" : value);
    }

    static Arg component(String name, Component value) {
        return new Rich(name, value);
    }

    static Arg time(String name, Duration value) {
        return new Time(name, value);
    }
}
