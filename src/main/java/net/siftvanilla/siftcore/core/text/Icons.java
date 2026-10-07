package net.siftvanilla.siftcore.core.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * Semantic icon names mapped to vanilla atlas sprites, e.g. {@code money -> minecraft:gui hud/...}. Every sprite is
 * checked against {@code atlas-index.txt}, generated from the real client assets of this Minecraft version, because a
 * wrong path renders as a missing-texture square that the server cannot detect.
 */
public final class Icons {

    /**
     * A sprite in an atlas. Sprites are multiplied by the text colour: {@code keepColors} renders the sprite's own
     * colours (by colouring it white); otherwise it inherits the surrounding text colour (for grey/white sprites).
     */
    public record Sprite(Key atlas, Key sprite, boolean keepColors) {
        @Override
        public String toString() {
            return this.atlas.asString() + " " + this.sprite.asString();
        }
    }

    private final Set<String> index;
    private volatile Map<String, Sprite> icons = Map.of();
    private volatile Map<String, Component> components = Map.of();

    public Icons(Set<String> atlasIndex) {
        this.index = Set.copyOf(atlasIndex);
    }

    /** Loads {@code atlas-index.txt} lines of the form {@code <atlas> <sprite>}. */
    public static Set<String> readIndex(InputStream in) throws IOException {
        Set<String> result = new HashSet<>();
        if (in == null) {
            throw new IOException("atlas-index.txt is missing from the jar");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length == 2) {
                    result.add(normalize(parts[0]) + " " + normalizeSprite(parts[1]));
                }
            }
        }
        return result;
    }

    private static String normalize(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    private static String normalizeSprite(String sprite) {
        String lower = sprite.toLowerCase(Locale.ROOT);
        return lower.startsWith("minecraft:") ? lower.substring("minecraft:".length()) : lower;
    }

    /** True if the atlas contains the sprite. */
    public boolean exists(Key atlas, Key sprite) {
        String spritePath = sprite.namespace().equals(Key.MINECRAFT_NAMESPACE) ? sprite.value() : sprite.asString();
        return this.index.contains(atlas.asString() + " " + spritePath);
    }

    /**
     * Replaces the icon set. Returns the names whose sprites do not exist in the atlas index (those are skipped and
     * must be reported as configuration errors).
     */
    public Set<String> load(Map<String, Sprite> definitions) {
        Map<String, Sprite> valid = new HashMap<>();
        Map<String, Component> rendered = new HashMap<>();
        Set<String> invalid = new HashSet<>();
        definitions.forEach((name, sprite) -> {
            if (exists(sprite.atlas(), sprite.sprite())) {
                valid.put(name, sprite);
                Component component = Component.object(ObjectContents.sprite(sprite.atlas(), sprite.sprite()));
                rendered.put(name, sprite.keepColors() ? component.color(NamedTextColor.WHITE) : component);
            } else {
                invalid.add(name);
            }
        });
        this.icons = Map.copyOf(valid);
        this.components = Map.copyOf(rendered);
        return invalid;
    }

    public boolean has(String name) {
        return this.components.containsKey(name);
    }

    /** The icon as an inline component, or empty if unknown (unknown names are rejected when lang loads). */
    public Component component(String name) {
        Component component = this.components.get(name);
        return component == null ? Component.empty() : component;
    }

    public Map<String, Sprite> all() {
        return this.icons;
    }

    public int indexSize() {
        return this.index.size();
    }
}
