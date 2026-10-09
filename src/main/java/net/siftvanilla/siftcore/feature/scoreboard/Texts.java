package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * The scoreboard's text, parsed from the lang file once and kept until the text changes. Every refresh asks
 * {@link #refresh} first; it compares the current lang text with what was compiled (a handful of parses per refresh,
 * not per player) and starts a new epoch when anything changed, after {@code /sift reload}. Used on the global region
 * thread only.
 */
final class Texts {

    /** Example values used to notice changes of messages that take placeholders. */
    private static final Arg[] SAMPLE = {Arg.text("rank", "r"), Arg.text("name", "n"), Arg.component("afk", Component.empty())};

    private final Lang lang;
    private long epoch;
    private List<Component> fingerprint = List.of();
    private Component title = Component.empty();
    private final Map<String, LineTemplate> lines = new HashMap<>();
    private LineTemplate header = LineTemplate.of(Component.empty());
    private LineTemplate footer = LineTemplate.of(Component.empty());
    /** Read on players' threads too (tab list names set while joining). */
    private volatile Component afk = Component.empty();
    private final Map<String, Component> prefixes = new HashMap<>();

    Texts(Lang lang) {
        this.lang = lang;
    }

    /** Re-reads the text when it changed; returns true when it did (a new epoch started). */
    boolean refresh() {
        List<Component> now = new ArrayList<>(ScoreboardMessages.LINES.size() + 8);
        now.add(this.lang.get(ScoreboardMessages.SIDEBAR_TITLE));
        for (MessageKey key : ScoreboardMessages.LINES.values()) {
            now.add(this.lang.get(key));
        }
        now.add(this.lang.get(ScoreboardMessages.TAB_HEADER));
        now.add(this.lang.get(ScoreboardMessages.TAB_FOOTER));
        now.add(this.lang.get(ScoreboardMessages.TAB_AFK));
        now.add(this.lang.get(ScoreboardMessages.TAB_NAME, SAMPLE));
        now.add(this.lang.get(ScoreboardMessages.TAB_NAME_RANKED, SAMPLE));
        now.add(this.lang.get(ScoreboardMessages.NAMETAG_PREFIX, SAMPLE));
        if (now.equals(this.fingerprint)) {
            return false;
        }
        this.fingerprint = List.copyOf(now);
        this.epoch++;
        int index = 0;
        this.title = now.get(index++);
        this.lines.clear();
        for (String name : ScoreboardMessages.LINES.keySet()) {
            this.lines.put(name, LineTemplate.of(now.get(index++)));
        }
        this.header = LineTemplate.of(now.get(index++));
        this.footer = LineTemplate.of(now.get(index++));
        this.afk = now.get(index);
        this.prefixes.clear();
        return true;
    }

    /** Goes up whenever the text changed. */
    long epoch() {
        return this.epoch;
    }

    Component title() {
        return this.title;
    }

    /** The template of a sidebar line by its config name; null for {@link ScoreboardSettings#BLANK}. */
    LineTemplate line(String name) {
        return this.lines.get(name);
    }

    LineTemplate header() {
        return this.header;
    }

    LineTemplate footer() {
        return this.footer;
    }

    /** A tab list name: "rank name" or just the name, with the AFK marker when away. */
    Component tabName(String label, String name, boolean away) {
        Arg afkArg = Arg.component("afk", away ? this.afk : Component.empty());
        return label.isEmpty()
            ? this.lang.get(ScoreboardMessages.TAB_NAME, Arg.text("name", name), afkArg)
            : this.lang.get(ScoreboardMessages.TAB_NAME_RANKED, Arg.text("rank", label), Arg.text("name", name), afkArg);
    }

    /** The nametag prefix of a rank label (empty for no label). */
    Component prefix(String label) {
        if (label.isEmpty()) {
            return Component.empty();
        }
        return this.prefixes.computeIfAbsent(label, l -> this.lang.get(ScoreboardMessages.NAMETAG_PREFIX, Arg.text("rank", l)));
    }

    /** Every template that has placeholders, for the self-test: name to placeholder names. */
    Map<String, List<String>> placeholderUse() {
        Map<String, List<String>> use = new HashMap<>();
        this.lines.forEach((name, template) -> use.put("sidebar line " + name, template.tokens()));
        use.put("tab header", this.header.tokens());
        use.put("tab footer", this.footer.tokens());
        return use;
    }
}
