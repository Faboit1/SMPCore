package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * What one sidebar shows, slot by slot, and the changes that bring it to new text. Each slot is a fixed score entry
 * ({@link #entry(int)}); its text is the score's custom name and its score orders the slots top to bottom. Only slots
 * whose text changed produce a change, so a refresh where nothing changed sends nothing. Pure: no server calls.
 */
public final class SidebarLines {

    /** The most lines a sidebar shows. */
    public static final int MAX_LINES = 15;
    /** Legacy colour codes as entries: unique, never a player name, and blank on clients too old for custom names. */
    private static final String ENTRY_CODES = "0123456789abcde";

    /** One slot to change: show {@code text} in it, or remove it when {@code text} is null. */
    public record Change(int slot, Component text) {
    }

    private final Component[] shown;

    public SidebarLines(int size) {
        if (size < 0 || size > MAX_LINES) {
            throw new IllegalArgumentException("a sidebar has 0 to " + MAX_LINES + " lines, not " + size);
        }
        this.shown = new Component[size];
    }

    public int size() {
        return this.shown.length;
    }

    /** The score entry of a slot. */
    public static String entry(int slot) {
        return "§" + ENTRY_CODES.charAt(slot);
    }

    /** The score of a slot: the top slot has the highest. */
    public int score(int slot) {
        return this.shown.length - slot;
    }

    /** The text a slot shows now, or null when it is empty. */
    public Component shown(int slot) {
        return this.shown[slot];
    }

    /**
     * Compares the new text with what is shown and records it as shown. {@code next} has one entry per slot: the text,
     * or null to leave the slot out.
     */
    public List<Change> update(List<Component> next) {
        if (next.size() != this.shown.length) {
            throw new IllegalArgumentException("expected " + this.shown.length + " lines, got " + next.size());
        }
        List<Change> changes = new ArrayList<>(2);
        for (int slot = 0; slot < this.shown.length; slot++) {
            Component text = next.get(slot);
            if (!Objects.equals(this.shown[slot], text)) {
                this.shown[slot] = text;
                changes.add(new Change(slot, text));
            }
        }
        return changes;
    }

    /** Forgets what is shown, so the next update resends every line. */
    public void forget() {
        java.util.Arrays.fill(this.shown, null);
    }

    /** The lines shown now, top to bottom, without empty slots. */
    public List<Component> visible() {
        List<Component> lines = new ArrayList<>(this.shown.length);
        for (Component line : this.shown) {
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
