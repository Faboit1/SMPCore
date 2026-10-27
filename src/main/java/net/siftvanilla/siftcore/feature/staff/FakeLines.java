package net.siftvanilla.siftcore.feature.staff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * "Fake join/leave on vanish" ({@link StaffPreferences#FAKE_MESSAGES}): a leave line when a staff member vanishes and
 * a join line when they reappear, looking exactly like the real ones.
 * <p>
 * A real join or leave shows the player's rank line (cosmetics, {@code siftcore.join.message}) when they have one and
 * it is not on its cooldown, otherwise the plain line when {@code features/extras.yml} shows plain lines
 * ({@code messages.join} / {@code messages.quit}), otherwise nothing; the fake follows the same rule, with the same
 * text: the plain lines are the extras feature's own lang entries ({@link #JOIN}, {@link #QUIT}: same path and
 * placeholders, so no import of that feature). Players who chose to see fewer join and leave lines
 * ({@code join-leave-messages}, read by id) don't get the fake ones either. Safe from any thread.
 * <p>
 * The setting is offered only while there is a line to imitate ({@link #available()}): the plain lines are on, or the
 * rank lines are linked ({@link #cosmetics(Cosmetics)}) and switched on in {@code features/cosmetics.yml}. Both files
 * are read here (again whenever they change), so neither feature is imported.
 */
final class FakeLines {

    /** The plain join line of the extras feature ({@code lang/extras.yml}). */
    static final MessageKey JOIN = MessageKey.ui("extras.join", "name");
    /** The plain leave line of the extras feature ({@code lang/extras.yml}). */
    static final MessageKey QUIT = MessageKey.ui("extras.quit", "name");
    /** The extras feature's config, read for its {@code messages.join} and {@code messages.quit} switches. */
    static final String EXTRAS_FILE = "features/extras.yml";
    /** The cosmetics feature's config, read for its {@code enabled} and {@code join-messages.enabled} switches. */
    static final String COSMETICS_FILE = "features/cosmetics.yml";
    /** The viewer setting of the extras feature that filters join and leave lines (all, first-joins, off). */
    static final String JOIN_LEAVE_SETTING = "join-leave-messages";

    /** The plain-line switches of features/extras.yml (both off when the file is missing, as shipped). */
    record PlainLines(boolean join, boolean quit) {

        static final PlainLines NONE = new PlainLines(false, false);

        static PlainLines read(YamlConfiguration yaml) {
            return new PlainLines(yaml.getBoolean("messages.join", false), yaml.getBoolean("messages.quit", false));
        }

        boolean any() {
            return this.join || this.quit;
        }
    }

    /**
     * Whether features/cosmetics.yml shows rank join and leave lines: the feature and its join lines are on (both
     * default to on, as in the cosmetics feature, when the file or the key is missing).
     */
    static boolean rankLinesOn(YamlConfiguration yaml) {
        return yaml.getBoolean("enabled", true) && yaml.getBoolean("join-messages.enabled", true);
    }

    private final Lang lang;
    private final PlayerSettings settings;
    private final WatchedYaml<PlainLines> plainLines;
    private final WatchedYaml<Boolean> rankLines;
    private volatile Cosmetics cosmetics = Cosmetics.NONE;

    FakeLines(Lang lang, PlayerSettings settings, Path dataFolder, Logger logger) {
        this.lang = lang;
        this.settings = settings;
        this.plainLines = new WatchedYaml<>(dataFolder.resolve(EXTRAS_FILE), PlainLines::read, PlainLines.NONE, PlainLines.NONE, logger);
        // A broken cosmetics file: nothing offered until it is fixed (the cosmetics feature reports it).
        this.rankLines = new WatchedYaml<>(dataFolder.resolve(COSMETICS_FILE), FakeLines::rankLinesOn, true, false, logger);
    }

    /** Rank join and leave lines and nicknames, once the cosmetics feature is built. */
    void cosmetics(Cosmetics cosmetics) {
        this.cosmetics = cosmetics == null ? Cosmetics.NONE : cosmetics;
    }

    /**
     * Whether the server shows any join or leave line a fake one could imitate (the setting is offered then): the
     * plain lines of the extras feature, or rank lines when they are linked and switched on.
     */
    boolean available() {
        return this.plainLines.get().any() || this.cosmetics != Cosmetics.NONE && this.rankLines.get();
    }

    /**
     * Shows the line a real leave ({@code vanished} true) or join of {@code subject} would show now, if they turned
     * the setting on. Returns false when they did but this server shows no such line for them (nothing was sent).
     */
    boolean announce(Player subject, boolean vanished) {
        if (!this.settings.get(subject.getUniqueId(), StaffPreferences.FAKE_MESSAGES)) {
            return true;
        }
        Component line = vanished ? this.cosmetics.quitLine(subject) : this.cosmetics.joinLine(subject);
        if (line == null) {
            PlainLines now = this.plainLines.get();
            if (!(vanished ? now.quit() : now.join())) {
                return false;
            }
            line = this.lang.get(vanished ? QUIT : JOIN, Arg.component("name", this.cosmetics.name(subject)));
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (seesJoinLines(this.settings.encoded(viewer.getUniqueId(), JOIN_LEAVE_SETTING))) {
                viewer.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
        return true;
    }

    /**
     * Whether a viewer sees a (non first-join) join or leave line: yes without the extras feature's viewer setting
     * ({@code null}) and when it is {@code all}; {@code first-joins} and {@code off} leave it out.
     */
    static boolean seesJoinLines(String joinLeaveSetting) {
        return joinLeaveSetting == null || joinLeaveSetting.equals("all");
    }

    /**
     * A value read from another feature's YAML file, read again when the file's modification time changes. Any
     * thread (a racing re-read only reads the file twice).
     */
    static final class WatchedYaml<T> {

        private record Read<T>(long modified, T value) {
        }

        private final Path file;
        private final Function<YamlConfiguration, T> reader;
        private final T missing;
        private final T broken;
        private final Logger logger;
        private volatile Read<T> last;

        /**
         * @param missing the value while the file does not exist
         * @param broken  the value while the file can't be read or parsed
         */
        WatchedYaml(Path file, Function<YamlConfiguration, T> reader, T missing, T broken, Logger logger) {
            this.file = file;
            this.reader = reader;
            this.missing = missing;
            this.broken = broken;
            this.logger = logger;
        }

        T get() {
            Read<T> current = this.last;
            File handle = this.file.toFile();
            long modified = handle.lastModified();
            if (current != null && modified == current.modified()) {
                return current.value();
            }
            T value = this.missing;
            if (handle.isFile()) {
                try {
                    YamlConfiguration yaml = new YamlConfiguration();
                    yaml.loadFromString(Files.readString(this.file, StandardCharsets.UTF_8));
                    value = this.reader.apply(yaml);
                } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                    // The feature that owns the file reports it broken itself.
                    this.logger.log(Level.FINE, "Could not read " + this.file.getFileName() + " for fake join and leave lines", e);
                    value = this.broken;
                }
            }
            this.last = new Read<>(modified, value);
            return value;
        }
    }
}
