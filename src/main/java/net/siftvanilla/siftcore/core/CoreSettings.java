package net.siftvanilla.siftcore.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Palette;

/** Parsed {@code config.yml}. */
public record CoreSettings(
    Storage storage,
    MoneyFormat money,
    String shardsSuffix,
    Palette palette,
    Map<Feedback, Sound> sounds,
    Duration guiClickInterval,
    boolean savePlayerAfterTrade,
    boolean debug) {

    /** Where data lives. */
    public record Storage(String type, String sqliteFile, String host, int port, String database, String username,
                          String password, int poolSize, boolean ssl) {
    }

    public static CoreSettings parse(ConfigReader r) {
        ConfigReader s = r.section("storage");
        String type = s.custom("type", v -> {
            String t = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.equals("sqlite") && !t.equals("mysql") && !t.equals("mariadb")) {
                throw new IllegalArgumentException("must be sqlite, mysql or mariadb");
            }
            return t.equals("mariadb") ? "mysql" : t;
        }, "sqlite, mysql or mariadb", "sqlite");
        ConfigReader sqlite = s.section("sqlite");
        ConfigReader mysql = s.section("mysql");
        Storage storage = new Storage(type,
            sqlite.string("file", "data/siftcore.db"),
            mysql.string("host", "localhost"),
            mysql.integer("port", 1, 65535, 3306),
            mysql.string("database", "siftcore"),
            mysql.string("username", "siftcore"),
            mysql.string("password", ""),
            mysql.integer("pool-size", 2, 64, 6),
            mysql.bool("ssl", false));

        ConfigReader c = r.section("currency");
        String pattern = c.string("format", "$<amount>");
        if (!pattern.contains("<amount>")) {
            c.problem("format", "must contain <amount>");
            pattern = "$<amount>";
        }
        List<MoneyFormat.Suffix> suffixes = new ArrayList<>();
        ConfigReader suffixSection = c.section("suffixes");
        for (String symbol : suffixSection.keys()) {
            long multiplier = suffixSection.longValue(symbol, 10, 1_000_000_000_000_000L, 1000);
            if (!symbol.matches("[a-zA-Z]{1,2}")) {
                suffixSection.problem(symbol, "suffix symbols must be one or two letters");
                continue;
            }
            suffixes.add(new MoneyFormat.Suffix(symbol, multiplier));
        }
        if (suffixes.isEmpty()) {
            suffixes = MoneyFormat.DEFAULT_SUFFIXES;
        }
        long maxBalance = c.longValue("max-balance", 1_000, 1_000_000_000_000_000_000L, 1_000_000_000_000_000L);
        MoneyFormat money = new MoneyFormat(pattern, c.bool("grouping", true),
            c.longValue("compact-from", 0, Long.MAX_VALUE, 1_000_000L),
            c.integer("compact-decimals", 0, 3, 2), suffixes, maxBalance);
        String shards = c.string("shards-suffix", " shards");

        ConfigReader p = r.section("palette");
        Palette palette = new Palette(
            color(p, "primary", "#FFFFFF"),
            color(p, "secondary", "#AAAAAA"),
            color(p, "money", "#1AFF1A"),
            color(p, "error", "#FF5555"),
            color(p, "error-secondary", "#FF9E9E"));

        Map<Feedback, Sound> sounds = new EnumMap<>(Feedback.class);
        ConfigReader sound = r.section("sounds");
        for (Feedback feedback : Feedback.values()) {
            if (feedback == Feedback.NONE) {
                continue;
            }
            String name = feedback.name().toLowerCase(java.util.Locale.ROOT);
            ConfigReader entry = sound.section(name);
            if (!entry.bool("enabled", true)) {
                continue;
            }
            Key key = entry.key("sound", Key.key("ui.button.click"));
            float volume = (float) entry.decimal("volume", 0.0, 2.0, 0.3);
            float pitch = (float) entry.decimal("pitch", 0.5, 2.0, 1.0);
            sounds.put(feedback, Sound.sound(key, Sound.Source.MASTER, volume, pitch));
        }

        ConfigReader gui = r.section("gui");
        Duration click = gui.duration("click-interval", Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(75));
        boolean save = r.section("crash-safety").bool("save-player-after-trade", true);
        boolean debug = r.bool("debug", false);
        return new CoreSettings(storage, money, shards, palette, Map.copyOf(sounds), click, save, debug);
    }

    private static TextColor color(ConfigReader r, String path, String fallback) {
        return r.custom(path, v -> {
            TextColor color = TextColor.fromHexString(v.trim());
            if (color == null) {
                throw new IllegalArgumentException("is not a hex colour");
            }
            return color;
        }, "a hex colour like #FFFFFF", TextColor.fromHexString(fallback));
    }
}
