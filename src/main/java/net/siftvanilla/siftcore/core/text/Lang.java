package net.siftvanilla.siftcore.core.text;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * All player-facing text. Strings live in {@code lang.yml} keyed by {@link MessageKey#path()}; a value is one
 * MiniMessage string or a list of them (one per line). On load every registered key is validated: it must exist,
 * and may only use design-system tags and its declared placeholders. A missing or broken entry falls back to the
 * bundled default and is reported precisely.
 * <p>
 * Money follows its reader's "Money format" setting ({@link MoneyDisplay}): text rendered inside
 * {@link #viewing(UUID, Supplier) a viewer's scope} writes {@link Arg#money} and {@link #money(long)} the way that
 * player chose (the server's way, in full or short); outside any scope it is the server's way. The scope belongs to the
 * thread and lasts for the call, so set it where the text is made for one player: the messenger does per recipient,
 * menus for their viewer, dialog clicks and commands for the player who clicked or typed. Text made in one player's
 * scope and sent to another player as it is would carry the first player's choice: render it per recipient
 * ({@link #perViewer}) or in the server's way ({@link #asServer}). A scope does not last into a callback (a
 * {@code whenComplete}, a scheduler task): text built there for one player goes in {@link #viewing} again (dialogs:
 * {@code Dialogs.show(player, () -> view)}), and placeholders use {@link #moneyFor}. Confirmations always write every
 * digit ({@link MessageKey#confirmation()}, {@link Arg#exact}), and so do buttons that pay or charge at once.
 */
public final class Lang {

    private final TextStyle style;
    private final Supplier<MoneyFormat> money;
    private final Map<String, MessageKey> registered = new ConcurrentHashMap<>();
    private volatile Map<String, List<String>> entries = Map.of();
    /** The money format of the text this thread renders now; null outside any viewer's scope (the server's way). */
    private final ThreadLocal<MoneyStyle> scope = new ThreadLocal<>();
    /** Each player's chosen money format (core binds the money-format setting). */
    private volatile Function<UUID, MoneyStyle> viewers = player -> MoneyStyle.SERVER;

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
                        + " (allowed: <primary> <secondary> <money> <error> <shards> <on> <off> <accent>, colours such as <red> or <#3CC4EE>, <bold>, <shadow:#000000>, <icon:name> <!italic> <newline>"
                        + (key.placeholders().isEmpty() ? "" : " and " + placeholderList(key)) + ")"));
                    chosen = bundledValue;
                } else {
                    String stale = formerPlaceholder(key, chosen, bundledValue);
                    if (stale != null) {
                        problems.add(new ConfigProblem(fileName, key.path(), stale));
                        chosen = bundledValue;
                    }
                }
            }
            loaded.put(key.path(), List.copyOf(chosen));
        }
        this.entries = Map.copyOf(loaded);
        return problems;
    }

    /**
     * Tag names that were placeholders in earlier versions and are palette tags now: {@code <shards>} was the shard
     * amount in many messages, which now name it {@code <amount>} (or {@code <balance>}) and use {@code <shards>} for
     * the colour.
     */
    static final Set<String> FORMER_PLACEHOLDERS = Set.of("shards");

    /**
     * Why a server's edited text is from before one of its placeholders became a palette tag, or null when it isn't.
     * Such a text uses {@code <shards>} as a value (followed by a space, punctuation or the end, where a colour would
     * be followed by what it colours: "You have <shards> shards.") and leaves out a placeholder the shipped text uses,
     * so it would load cleanly and show no amount. Texts that leave out a value on purpose are fine.
     */
    static String formerPlaceholder(MessageKey key, List<String> user, List<String> bundled) {
        String userText = String.join("\n", user);
        String bundledText = String.join("\n", bundled);
        for (String former : FORMER_PLACEHOLDERS) {
            if (key.placeholders().contains(former)
                || !java.util.regex.Pattern.compile("<" + former + ">(?=$|[\\s.,!?;:)\\]])").matcher(userText).find()) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String name : key.placeholders()) {
                if (uses(bundledText, name) && !uses(userText, name)) {
                    missing.add("<" + name + ">");
                }
            }
            if (!missing.isEmpty()) {
                missing.sort(null);
                return "uses <" + former + ">, which is the " + former + " colour now, and leaves out " + String.join(" ", missing)
                    + " (the value it showed before); using the default text. Write " + missing.getFirst() + " where the number goes.";
            }
        }
        return null;
    }

    private static boolean uses(String text, String placeholder) {
        return text.contains("<" + placeholder + ">") || text.contains("<" + placeholder + ":");
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

    // ------------------------------------------------------------------ money format per reader

    /** Binds how each player's chosen money format is read (core: the {@code money-format} setting). */
    public void viewers(Function<UUID, MoneyStyle> styles) {
        this.viewers = styles == null ? player -> MoneyStyle.SERVER : styles;
    }

    /** The money format a player chose; the server's way when it can't be read. Thread-safe. */
    public MoneyStyle styleOf(UUID player) {
        if (player == null) {
            return MoneyStyle.SERVER;
        }
        try {
            MoneyStyle chosen = this.viewers.apply(player);
            return chosen == null ? MoneyStyle.SERVER : chosen;
        } catch (RuntimeException e) {
            return MoneyStyle.SERVER;
        }
    }

    /** The money format of a reader: a player's choice; the server's way for the console and anything else. */
    public MoneyStyle styleOf(Audience reader) {
        return reader instanceof Player player ? styleOf(player.getUniqueId()) : MoneyStyle.SERVER;
    }

    /** The money format text rendered on this thread uses now: the current viewer's, or the server's way outside any. */
    public MoneyStyle moneyStyle() {
        MoneyStyle current = this.scope.get();
        return current == null ? MoneyStyle.SERVER : current;
    }

    /** Whether this thread renders for a particular viewer now (inside {@link #viewing} or {@link #within}). */
    public boolean inScope() {
        return this.scope.get() != null;
    }

    /** Renders text for one player: money in it is written the way they chose. Nests; the innermost scope wins. */
    public <T> T viewing(UUID viewer, Supplier<T> render) {
        return within(styleOf(viewer), render);
    }

    /** {@link #viewing(UUID, Supplier)} for a reader: a player's choice, the server's way for the console. */
    public <T> T viewing(Audience reader, Supplier<T> render) {
        return within(styleOf(reader), render);
    }

    /** Runs code that renders text for one reader (a menu draw, a click handler, a command). */
    public void viewing(Audience reader, Runnable action) {
        within(styleOf(reader), () -> {
            action.run();
            return null;
        });
    }

    /** Renders text with money in one style (null: the server's way). */
    public <T> T within(MoneyStyle style, Supplier<T> render) {
        try (Scope _ = open(style)) {
            return render.get();
        }
    }

    /** A viewer's scope opened with {@link #open}; closing it restores the scope it replaced. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Opens a reader's scope on this thread until it is closed (try-with-resources), for code that can't be passed as a
     * lambda (it throws checked exceptions). Close it on the same thread.
     */
    public Scope open(Audience reader) {
        return open(styleOf(reader));
    }

    private Scope open(MoneyStyle style) {
        MoneyStyle outer = this.scope.get();
        this.scope.set(style == null ? MoneyStyle.SERVER : style);
        return () -> {
            if (outer == null) {
                this.scope.remove();
            } else {
                this.scope.set(outer);
            }
        };
    }

    /** Renders text the server's way, whoever's scope this thread is in: for a line sent to many players as it is. */
    public <T> T asServer(Supplier<T> render) {
        return within(MoneyStyle.SERVER, render);
    }

    /**
     * Text for many readers: {@code render} runs at most once per money format (three at most), and each reader gets
     * the copy in their format. For a line sent to everyone (an announcement). Thread-safe.
     */
    public <T> Function<Audience, T> perViewer(Supplier<T> render) {
        Map<MoneyStyle, T> rendered = new EnumMap<>(MoneyStyle.class);
        return reader -> {
            MoneyStyle style = styleOf(reader);
            synchronized (rendered) {
                return rendered.computeIfAbsent(style, s -> within(s, render));
            }
        };
    }

    // ------------------------------------------------------------------ rendering

    /** The message as one component (multiple lines joined with newlines), in the primary colour by default. */
    public Component get(MessageKey key, Arg... args) {
        List<String> lines = raw(key);
        TagResolver resolver = resolver(key, args);
        Component text;
        if (lines.size() == 1) {
            text = this.style.parse(lines.getFirst(), resolver).colorIfAbsent(this.style.palette().primary());
        } else {
            List<Component> parts = new ArrayList<>(lines.size());
            for (String line : lines) {
                parts.add(this.style.parse(line, resolver));
            }
            text = Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), parts)
                .colorIfAbsent(this.style.palette().primary());
        }
        return key.feedback() == Feedback.ERROR ? this.style.palette().asError(text) : text;
    }

    /** The message as separate lines, each primary by default and with italics off (for lore and bodies). */
    public List<Component> lines(MessageKey key, Arg... args) {
        List<String> lines = raw(key);
        TagResolver resolver = resolver(key, args);
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

    /** Formats money the way the current reader chose ({@link #moneyStyle()}; the server's way outside any scope). */
    public String money(long amount) {
        return money(amount, moneyStyle());
    }

    /** Formats money in one style (null: the server's way). */
    public String money(long amount, MoneyStyle style) {
        return format().format(amount, style);
    }

    /**
     * Money as one player reads it, whatever scope this thread is in: their money format (the server's way for null).
     * For placeholders, which PlaceholderAPI asks for one player on any thread. Thread-safe.
     */
    public String moneyFor(OfflinePlayer reader, long amount) {
        return money(amount, reader == null ? MoneyStyle.SERVER : styleOf(reader.getUniqueId()));
    }

    /** Money with every digit ({@code $1,234,567}), whoever reads it: an amount to agree to. */
    public String moneyExact(long amount) {
        return format().formatExact(amount);
    }

    /** A money amount as a component in the money colour, the way the current reader chose. */
    public Component moneyComponent(long amount) {
        return moneyComponent(amount, moneyStyle());
    }

    /** A money amount as a component in the money colour, in one style (null: the server's way). */
    public Component moneyComponent(long amount, MoneyStyle style) {
        return Component.text(money(amount, style), this.style.palette().money());
    }

    /** The server's money format now (the defaults until the config is read): what {@link MoneyStyle#SERVER} writes. */
    public MoneyFormat moneyFormat() {
        return format();
    }

    /** The server's money format (the defaults until the config is read). */
    private MoneyFormat format() {
        MoneyFormat format = this.money.get();
        return format == null ? DEFAULT_MONEY : format;
    }

    private static final MoneyFormat DEFAULT_MONEY = MoneyFormat.defaults();

    /** A whole number with grouping. */
    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * The money format of a message's amounts: every digit in a confirmation, otherwise the current reader's choice.
     * An {@link Arg.Money} with its own style keeps it.
     */
    static MoneyStyle styleFor(MessageKey key, MoneyStyle reader) {
        return key != null && key.confirmation() ? MoneyStyle.FULL : reader;
    }

    private TagResolver resolver(MessageKey key, Arg[] args) {
        if (args.length == 0) {
            return TagResolver.empty();
        }
        TagResolver.Builder builder = TagResolver.builder();
        Palette palette = this.style.palette();
        MoneyStyle money = styleFor(key, moneyStyle());
        MoneyFormat format = format();
        for (Arg arg : args) {
            builder.resolver(switch (arg) {
                case Arg.Money m -> Placeholder.component(m.name(),
                    Component.text(format.format(m.amount(), m.style() != null ? m.style() : money), palette.money()));
                case Arg.Amount a -> a.currency() == Currency.MONEY
                    ? Placeholder.component(a.name(), Component.text(format.format(a.amount(), money), palette.money()))
                    : Placeholder.component(a.name(), Component.text(number(a.amount()), palette.shards()));
                case Arg.Number n -> Placeholder.component(n.name(), Component.text(number(n.value()), palette.primary()));
                case Arg.Decimal d -> Placeholder.component(d.name(), Component.text(decimal(d.value()), palette.primary()));
                case Arg.Text t -> Placeholder.unparsed(t.name(), t.value());
                case Arg.Rich r -> Placeholder.component(r.name(), r.value());
                case Arg.Time t -> Placeholder.component(t.name(), Component.text(Durations.format(t.value()), palette.primary()));
            });
        }
        return builder.build();
    }

    public static String decimal(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
