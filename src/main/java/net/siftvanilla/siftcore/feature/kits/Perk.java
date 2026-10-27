package net.siftvanilla.siftcore.feature.kits;

import java.util.List;
import java.util.Locale;

/**
 * A rank perk command. Each has its own permission node {@code siftcore.perk.<id>} so ranks can be given exactly the
 * perks they should have. {@code /feed} and {@code /heal} are deliberately not perks: on a PvP server they would let
 * paying players refill health and hunger between fights.
 */
public enum Perk {
    EC("ec", List.of("enderchest", "echest"), "Opens your ender chest anywhere"),
    CRAFT("craft", List.of("workbench", "wb"), "Opens a crafting table anywhere"),
    ANVIL("anvil", List.of(), "Opens an anvil anywhere"),
    STONECUTTER("stonecutter", List.of(), "Opens a stonecutter anywhere"),
    GRINDSTONE("grindstone", List.of(), "Opens a grindstone anywhere"),
    SMITHING("smithing", List.of("smithingtable"), "Opens a smithing table anywhere"),
    LOOM("loom", List.of(), "Opens a loom anywhere"),
    CARTOGRAPHY("cartography", List.of("cartographytable"), "Opens a cartography table anywhere"),
    TRASH("trash", List.of("disposal"), "Opens a bin that deletes what you put in it"),
    HAT("hat", List.of(), "Wears the item in your hand");

    /** The node that lets staff look into other players' ender chests with {@code /ec <player>}. */
    static final String EC_OTHERS = "siftcore.perk.ec.others";

    private final String id;
    private final List<String> aliases;
    private final String description;

    Perk(String id, List<String> aliases, String description) {
        this.id = id;
        this.aliases = aliases;
        this.description = description;
    }

    /** The command name and config id. */
    public String id() {
        return this.id;
    }

    /** The command's default aliases. */
    List<String> aliases() {
        return this.aliases;
    }

    /** One line for /help and the permission list. */
    String description() {
        return this.description;
    }

    public String node() {
        return "siftcore.perk." + this.id;
    }

    /** True for the perks that open a screen (everything but the hat). */
    boolean opensScreen() {
        return this != HAT;
    }

    /** The perk with this config id, or null. */
    static Perk byId(String id) {
        String wanted = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        for (Perk perk : values()) {
            if (perk.id.equals(wanted)) {
                return perk;
            }
        }
        return null;
    }

    /** Every config id, for problem messages. */
    static String ids() {
        StringBuilder sb = new StringBuilder();
        for (Perk perk : values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(perk.id);
        }
        return sb.toString();
    }
}
