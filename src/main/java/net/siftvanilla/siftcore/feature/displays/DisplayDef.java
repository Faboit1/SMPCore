package net.siftvanilla.siftcore.feature.displays;

import java.util.Objects;

/**
 * One display as it currently stands: displays.yml and the in-game position merged. Immutable; a new set of these
 * is built whenever the file is reloaded or a display is placed, moved or deleted.
 *
 * @param id           the display name
 * @param templateId   the template it uses (may name a template that no longer exists)
 * @param template     the compiled template, or null when {@code templateId} is missing from displays.yml
 * @param position     where it stands, or null when it has not been placed yet
 * @param options      looks and refresh interval
 * @param clickCommand the command a right-click runs, or null for no click box
 * @param inConfig     defined in displays.yml
 * @param placed       has a position set in-game (a row in the displays table)
 * @param generation   bumped whenever the display moves; entities of an older generation are stale
 */
public record DisplayDef(String id, String templateId, DisplayTemplate template, DisplayPosition position,
                         DisplayOptions options, String clickCommand, boolean inConfig, boolean placed, int generation) {

    public DisplayDef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(options);
    }

    /** Has a position and a template, so it can be put in the world. */
    public boolean showable() {
        return this.position != null && this.template != null;
    }

    public DisplayDef withGeneration(int generation) {
        return new DisplayDef(this.id, this.templateId, this.template, this.position, this.options, this.clickCommand,
            this.inConfig, this.placed, generation);
    }
}
