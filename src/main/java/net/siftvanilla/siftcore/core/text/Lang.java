package net.siftvanilla.siftcore.core.text;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import org.bukkit.configuration.ConfigurationSection;

/**
 * All player-facing text. Strings live in {@code lang.yml} keyed by {@link MessageKey#path()}; a value is one
 * MiniMessage string or a list of them (one per line). On load every registered key is validated: it must exist,
 * and may only use design-system tags and its declared placeholders. A missing or broken entry falls back to the
 * bundled default and is reported precisely.
 */
public final class Lang {

    private final TextStyle style;
    private final Supplier<MoneyFormat> money;
    private final Map<String, MessageKey> registered = new ConcurrentHashMap<>();
    private volatile Map<String, List<String>> entries = Map.of();

    public Lang(TextStyle style, Supplier<MoneyFormat> money) {
        this.style = style;
        this.money = money;
    }

    public TextStyle style() {
        return this.style;
    }

    /** Registers every {@code static final MessageKey} field of a messages class. */
    public void register(Class<?> messagesClass) {
        for (Field field : messagesClass.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) && Modifier.isFinal(mods) && field.getType() == MessageKey.class) {
                try {
                    field.setAccessible(true);
                    MessageKey key = (MessageKey) field.get(null);
                    MessageKey previous = this.registered.putIfAbsent(key.path(), key);
                    if (previous != null && !previous.equals(key)) {
                        throw new IllegalStateException("Message path " + key.path() + " is declared twice with different placeholders");
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public Map<String, MessageKey> registered() {
        return Map.copyOf(this.registered);
    }

    /**
     * Loads and validates text. {@code user} is the server's lang.yml, {@code bundled} the jar default.
     * Returns every problem; entries with problems use the bundled text.
     */
    public List<ConfigProblem> load(ConfigurationSection user, ConfigurationSection bundled, String fileName) {
        List<ConfigProblem> problems = new ArrayList<>();
        Map<String, List<String>> loaded = new HashMap<>();
        for (MessageKey key : this.registered.values()) {
            List<String> userValue = read(user, key.path());
            List<String> bundledValue = read(bundled, key.path());
            if (bundledValue == null) {
                problems.add(new ConfigProblem("(jar) " + fileName, key.path(), "is missing from the bundled lang file (developer error)"));
                bundledValue = List.of(key.path());
            }
            List<String> chosen = userValue;
            if (chosen == null) {
                problems.add(new ConfigProblem(fileName, key.path(), "is missing; using the default text"));
                chosen = bundledValue;
            } else {
                List<String> bad = new ArrayList<>();
                for (String line : chosen) {
                    bad.addAll(this.style.findDisallowedTags(line, key.placeholders()));
                }
                if (!bad.isEmpty()) {
                    problems.add(new ConfigProblem(fileName, key.path(), "uses tags that are not allowed: " + String.join(", ", bad)
                        + " (allowed: <primary> <secondary> <money> <icon:name> <!italic> <newline>"
                        + (key.placeholders().isEmpty() ? "" : " and " + placeholderList(key)) + ")"));
                    chosen = bundledValue;
                }
            }
            loaded.put(key.path(), List.copyOf(chosen));
        }
        this.entries = Map.copyOf(loaded);
        return problems;
    }

    private static String placeholderList(MessageKey key) {
        List<String> names = new ArrayList<>();
        for (String name : key.placeholders()) {
            names.add("<" + name + ">");
        }
        names.sort(null);
        return String.join(" ", names);
    }

    private static List<String> read(ConfigurationSection section, String path) {
        if (section == null || !section.contains(path)) {
            return null;
        }
        if (section.isList(path)) {
            List<String> list = new ArrayList<>();
            for (Object o : section.getList(path, List.of())) {
                list.add(String.valueOf(o));
            }
            return list;
        }
        if (section.isConfigurationSection(path)) {
            return null;
        }
        return List.of(String.valueOf(section.get(path)));
    }

    private List<String> raw(MessageKey key) {
        List<String> value = this.entries.get(key.path());
        if (value == null) {
            this.registered.putIfAbsent(key.path(), key);
            return List.of(key.path());
        }
        return value;
    }

    /** The message as one component (multiple lines joined with newlines), in the primary colour by default. */
    public Component get(MessageKey key, Arg... args) {
        List<String> lines = raw(key);
        TagResolver resolver = resolver(args);
        if (lines.size() == 1) {
            return this.style.parse(lines.getFirst(), resolver).colorIfAbsent(this.style.palette().primary());
        }
        List<Component> parts = new ArrayList<>(lines.size());
        for (String line : lines) {
            parts.add(this.style.parse(line, resolver));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), parts)
            .colorIfAbsent(this.style.palette().primary());
    }

    /** The message as separate lines, each primary by default and with italics off (for lore and bodies). */
    public List<Component> lines(MessageKey key, Arg... args) {
        List<String> lines = raw(key);
        TagResolver resolver = resolver(args);
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(this.style.parse(line, resolver)
                .colorIfAbsent(this.style.palette().primary())
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return result;
    }

    /** The message with italics off, for item names. */
    public Component item(MessageKey key, Arg... args) {
        return get(key, args).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Plain text without formatting (for logs, console and Bedrock forms). */
    public String plain(MessageKey key, Arg... args) {
        return TextStyle.plain(get(key, args));
    }

    /** Formats money the way the server displays it. */
    public String money(long amount) {
        return this.money.get().format(amount);
    }

    /** A money amount as a component in the money colour. */
    public Component moneyComponent(long amount) {
        return Component.text(this.money.get().format(amount), this.style.palette().money());
    }

    /** A whole number with grouping. */
    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private TagResolver resolver(Arg[] args) {
        if (args.length == 0) {
            return TagResolver.empty();
        }
        TagResolver.Builder builder = TagResolver.builder();
        Palette palette = this.style.palette();
        for (Arg arg : args) {
            builder.resolver(switch (arg) {
                case Arg.Money m -> Placeholder.component(m.name(), Component.text(this.money.get().format(m.amount()), palette.money()));
                case Arg.Amount a -> a.currency() == Currency.MONEY
                    ? Placeholder.component(a.name(), Component.text(this.money.get().format(a.amount()), palette.money()))
                    : Placeholder.component(a.name(), Component.text(number(a.amount()), palette.primary()));
                case Arg.Number n -> Placeholder.component(n.name(), Component.text(number(n.value()), palette.primary()));
                case Arg.Decimal d -> Placeholder.component(d.name(), Component.text(decimal(d.value()), palette.primary()));
                case Arg.Text t -> Placeholder.unparsed(t.name(), t.value());
                case Arg.Rich r -> Placeholder.component(r.name(), r.value());
                case Arg.Time t -> Placeholder.component(t.name(), Component.text(Durations.format(t.value()), palette.primary()));
            });
        }
        return builder.build();
    }

    static String decimal(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
