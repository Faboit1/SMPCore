package net.siftvanilla.siftcore.core.text;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.api.economy.Currency;

/**
 * A typed placeholder value. Text is always inserted literally (never parsed), so player-supplied strings cannot
 * inject formatting, click or hover events. Money renders in the money colour; every other number renders in the
 * primary colour. Formatting happens in {@link Lang}.
 */
public sealed interface Arg {

    String name();

    /** A money amount, e.g. {@code $1,500}, in the money colour. */
    record Money(String name, long amount) implements Arg {
    }

    /** A currency amount: money renders like {@link Money}; shards render as a plain number. */
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

    static Arg money(String name, long amount) {
        return new Money(name, amount);
    }

    static Arg amount(String name, Currency currency, long amount) {
        return new Amount(name, currency, amount);
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
