package net.siftvanilla.siftcore.core.text;

import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code icons.yml}: semantic icon name to atlas sprite. */
public record IconSettings(Map<String, Icons.Sprite> icons) {

    public static IconSettings parse(ConfigReader reader) {
        Map<String, Icons.Sprite> icons = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigReader> entry : reader.children("icons").entrySet()) {
            String name = entry.getKey();
            ConfigReader icon = entry.getValue();
            if (!name.matches("[a-z0-9_]{1,32}")) {
                icon.problem("", "icon names must be lowercase letters, digits or _");
                continue;
            }
            Key atlas = icon.key("atlas", Key.key("minecraft:gui"));
            String sprite = icon.string("sprite", "hud/heart/full");
            Key spriteKey;
            try {
                spriteKey = sprite.contains(":") ? Key.key(sprite) : Key.key(Key.MINECRAFT_NAMESPACE, sprite);
            } catch (RuntimeException e) {
                icon.problem("sprite", "is not a valid sprite path, got '" + sprite + "'");
                continue;
            }
            String tint = icon.optionalString("tint", "keep");
            if (!tint.equals("keep") && !tint.equals("inherit")) {
                icon.problem("tint", "must be keep or inherit, got '" + tint + "'");
            }
            icons.put(name, new Icons.Sprite(atlas, spriteKey, !tint.equals("inherit")));
        }
        return new IconSettings(Map.copyOf(icons));
    }
}
