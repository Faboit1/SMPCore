package net.siftvanilla.siftcore.feature.displays;

import java.util.Map;
import java.util.TreeMap;

/**
 * Merges displays.yml with the positions set in-game into the displays that exist right now. Pure.
 * <ul>
 *   <li>A display in the file uses the file's template and looks; an in-game position wins over the file's.</li>
 *   <li>A display created in-game uses its own template and the default looks.</li>
 *   <li>A row for a display that was moved in-game and then removed from the file has no template; it is listed
 *       (so it can be deleted) but never shown.</li>
 * </ul>
 */
final class DisplayCatalog {

    private DisplayCatalog() {
    }

    /** Every display by name, sorted by name. Generations are 0; the caller assigns them. */
    static Map<String, DisplayDef> merge(DisplaysSettings settings, Map<String, Placement> placements) {
        Map<String, DisplayDef> result = new TreeMap<>();
        for (DisplaysSettings.ConfigDisplay display : settings.displays().values()) {
            Placement placement = placements.get(display.id());
            DisplayPosition position = placement != null ? placement.position() : display.position();
            String templateId = display.template();
            result.put(display.id(), new DisplayDef(display.id(), templateId, settings.templates().get(templateId), position,
                display.options(), clickCommand(settings, templateId), true, placement != null, 0));
        }
        for (Placement placement : placements.values()) {
            if (result.containsKey(placement.id())) {
                continue;
            }
            String templateId = placement.template();
            DisplayTemplate template = templateId == null ? null : settings.templates().get(templateId);
            result.put(placement.id(), new DisplayDef(placement.id(), templateId, template, placement.position(),
                settings.defaults(), clickCommand(settings, templateId), false, true, 0));
        }
        return result;
    }

    private static String clickCommand(DisplaysSettings settings, String templateId) {
        if (!settings.interactionEnabled() || templateId == null) {
            return null;
        }
        return settings.clickCommands().get(templateId);
    }
}
