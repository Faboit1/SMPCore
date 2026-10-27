package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.LinkedHashMap;
import java.util.Map;
import net.siftvanilla.siftcore.core.permission.Permissions;

/**
 * Permission nodes of the cosmetic perks. Rank nodes include the tier below them (child permissions), so a group
 * that only has the Tycoon node still has the Baron perks; with LuckPerms the rank groups inherit each other anyway.
 */
final class CosmeticsNodes {

    /** Baron: vanilla chat colours (/chatcolor). */
    static final String CHAT_COLOR = "siftcore.chat.color";
    /** Tycoon: hex chat colours and gradients. */
    static final String CHAT_COLOR_HEX = "siftcore.chat.color.hex";
    /** Baron: a nickname in a vanilla colour (/nick). */
    static final String NICK = "siftcore.command.nick";
    /** Tycoon: nicknames in hex colours and gradients. */
    static final String NICK_GRADIENT = "siftcore.nick.gradient";
    /** Staff: set and clear other players' nicknames. */
    static final String NICK_ADMIN = "siftcore.admin.nick";
    /** Baron: a rank join and leave line. */
    static final String JOIN = "siftcore.join.message";
    /** Tycoon: custom join and leave messages. */
    static final String JOIN_CUSTOM = "siftcore.join.message.custom";
    /** Tag tiers (the shipped tags use these). */
    static final String TAGS_PROSPECTOR = "siftcore.tags.prospector";
    static final String TAGS_BARON = "siftcore.tags.baron";
    static final String TAGS_TYCOON = "siftcore.tags.tycoon";
    /** Every kill effect. */
    static final String KILL_EFFECTS = "siftcore.killeffect.*";
    /** The cosmetics menu, /tags, /killeffect and /realname (everyone; locked perks say what unlocks them). */
    static final String MENU = "siftcore.command.cosmetics";
    static final String TAGS = "siftcore.command.tags";
    static final String KILL_EFFECT = "siftcore.command.killeffect";
    static final String REALNAME = "siftcore.command.realname";
    /** Staff: cosmetics status and resetting a player's cosmetics. */
    static final String ADMIN = "siftcore.admin.cosmetics";

    private CosmeticsNodes() {
    }

    /** The node of one kill effect. */
    static String killEffect(KillEffect effect) {
        return "siftcore.killeffect." + effect.id();
    }

    static void declare(Permissions permissions) {
        permissions.declare(MENU, "Open the cosmetics menu with /cosmetics", true);
        permissions.declare(TAGS, "Pick a chat tag with /tags", true);
        permissions.declare(KILL_EFFECT, "Pick a kill effect with /killeffect", true);
        permissions.declare(REALNAME, "See who uses a nickname with /realname", true);
        permissions.declare(CHAT_COLOR, "Chat in a vanilla colour of your choice (/chatcolor)", false);
        permissions.declare(CHAT_COLOR_HEX, "Chat in any hex colour or a gradient (/chatcolor)", false);
        permissions.children(CHAT_COLOR_HEX, Map.of(CHAT_COLOR, true));
        permissions.declare(NICK, "Set a nickname in a vanilla colour with /nick", false);
        permissions.declare(NICK_GRADIENT, "Give your nickname a hex colour or a gradient", false);
        permissions.children(NICK_GRADIENT, Map.of(NICK, true));
        permissions.declare(NICK_ADMIN, "Set and clear other players' nicknames with /nick <player>", false);
        permissions.declare(JOIN, "Your rank is announced when you join and leave", false);
        permissions.declare(JOIN_CUSTOM, "Write your own join and leave messages with /joinmessage and /leavemessage", false);
        permissions.children(JOIN_CUSTOM, Map.of(JOIN, true));
        permissions.declare(TAGS_PROSPECTOR, "Use the Prospector chat tags", false);
        permissions.declare(TAGS_BARON, "Use the Baron chat tags (and the Prospector ones)", false);
        permissions.children(TAGS_BARON, Map.of(TAGS_PROSPECTOR, true));
        permissions.declare(TAGS_TYCOON, "Use the Tycoon chat tags and monthly exclusives (and the lower tiers' tags)", false);
        permissions.children(TAGS_TYCOON, Map.of(TAGS_BARON, true, TAGS_PROSPECTOR, true));
        Map<String, Boolean> effects = new LinkedHashMap<>();
        for (KillEffect effect : KillEffect.values()) {
            permissions.declare(killEffect(effect), "Use the " + effect.id() + " kill effect", false);
            effects.put(killEffect(effect), true);
        }
        permissions.declare(KILL_EFFECTS, "Use every kill effect", false);
        permissions.children(KILL_EFFECTS, effects);
        permissions.declare(ADMIN, "See cosmetics status and reset a player's cosmetics with /cosmetics admin", false);
    }
}
