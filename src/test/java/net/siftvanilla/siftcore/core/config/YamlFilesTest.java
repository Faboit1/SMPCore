package net.siftvanilla.siftcore.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class YamlFilesTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(text);
        return yaml;
    }

    private static final String JAR = """
        # How long a request lasts.
        expire-after: 60s
        # New in this version: the sound played.
        sound: true
        crates:
          basic:
            weight: 5
          rare:
            weight: 2
        messages:
          join: false
          # Shown to new players.
          first-join: true
        """;

    @Test
    void addsOnlyKeysNewInThisVersionAndKeepsTheServersValues() throws Exception {
        YamlConfiguration server = yaml("""
            expire-after: 10s
            crates:
              basic:
                weight: 9
            messages:
              join: true
            """);
        // The previous version shipped expire-after, both crates and messages.join: the admin deleted the rare crate.
        Set<String> known = Set.of("expire-after", "crates.basic.weight", "crates.rare.weight", "messages.join");
        List<String> added = YamlFiles.addNewKeys(server, yaml(JAR), known);
        assertEquals(List.of("sound", "messages.first-join"), added);
        assertEquals("10s", server.getString("expire-after"), "the admin's value stays");
        assertEquals(9, server.getInt("crates.basic.weight"));
        assertFalse(server.contains("crates.rare"), "a deleted entry is not brought back");
        assertTrue(server.getBoolean("sound"));
        assertTrue(server.getBoolean("messages.first-join"));
        String saved = server.saveToString();
        assertTrue(saved.contains("# New in this version: the sound played."), saved);
        assertTrue(saved.contains("# Shown to new players."), saved);
    }

    @Test
    void withoutARecordTheServersKeysCountAsKnown() throws Exception {
        YamlConfiguration server = yaml("""
            expire-after: 60s
            crates:
              basic:
                weight: 5
              rare:
                weight: 2
            messages:
              join: false
            """);
        List<String> added = YamlFiles.addNewKeys(server, yaml(JAR), YamlFiles.leafKeys(server));
        assertEquals(List.of("sound", "messages.first-join"), added);
    }

    @Test
    void neverOverwritesAValueWithASection() throws Exception {
        YamlConfiguration server = yaml("messages: none\n");
        List<String> added = YamlFiles.addNewKeys(server, yaml(JAR), Set.of());
        assertFalse(added.contains("messages.first-join"));
        assertEquals("none", server.getString("messages"));
    }

    @Test
    void entriesNobodyEditedFollowTheNewDefaults() throws Exception {
        YamlConfiguration previous = yaml("""
            rewards: "Shards every <time> (ranks pay more)"
            billboard: center
            limit: 5
            list:
              - a
              - b
            gone: 1
            """);
        YamlConfiguration server = yaml("""
            rewards: "Shards every <time> (ranks pay more)"
            billboard: fixed
            limit: 5
            list:
              - a
              - b
            gone: 1
            """);
        YamlConfiguration jar = yaml("""
            rewards: "Shards every <time>"
            billboard: vertical
            limit: 5
            list:
              - a
              - c
            """);
        List<String> updated = YamlFiles.updateUnedited(server, jar, previous);
        assertEquals(List.of("rewards", "list"), updated);
        assertEquals("Shards every <time>", server.getString("rewards"), "never edited: follows the new text");
        assertEquals("fixed", server.getString("billboard"), "the admin's choice stays");
        assertEquals(5, server.getInt("limit"), "unchanged default");
        assertEquals(List.of("a", "c"), server.getStringList("list"));
        assertEquals(1, server.getInt("gone"), "keys the jar no longer ships are left alone");
    }

    private static final String RETIRED_BEFORE = """
        # Longer groups get Previous page and Next page buttons.
        page-size: 8
        show-descriptions: true
        old:
          a: 1
          b: 2
        half:
          a: 1
        # Kept.
        kept: 1
        """;

    /** Settings a version retires leave the server's file when nobody edited them (whole sections only). */
    @Test
    void retiredSettingsNobodyEditedAreRemoved() throws Exception {
        YamlConfiguration previous = yaml(RETIRED_BEFORE);
        YamlConfiguration server = yaml(RETIRED_BEFORE.replace("show-descriptions: true", "show-descriptions: false")
            .replace("half:\n  a: 1", "half:\n  a: 1\n  mine: 3"));
        YamlConfiguration jar = yaml("""
            # Kept.
            kept: 1
            new: 2
            """);
        assertEquals(List.of("page-size", "old"), YamlFiles.removeRetired(server, jar, previous));
        assertFalse(server.contains("page-size"), "unedited and retired: gone");
        assertFalse(server.contains("old"), "a whole retired section nobody touched: gone");
        assertTrue(server.contains("show-descriptions"), "an edited value stays");
        assertEquals(3, server.getInt("half.mine"), "a section the admin added to stays whole");
        assertEquals(1, server.getInt("half.a"));
        assertEquals(1, server.getInt("kept"));
        assertEquals(List.of(), YamlFiles.removeRetired(yaml("kept: 1\n"), jar, previous), "nothing to remove");
    }

    /** Comments the server still has as last shipped follow the new text; the admin's own comments stay. */
    @Test
    void commentsNobodyEditedFollowTheNewText() throws Exception {
        String before = """
            # How many bounties /bounties shows (1 to 20).
            list-size: 10
            # Your note.
            tax: 0
            """;
        YamlConfiguration previous = yaml(before);
        // A server file written by an earlier sync (saved by the YAML library) and one the admin commented.
        YamlConfiguration synced = yaml(yaml(before).saveToString());
        YamlConfiguration commented = yaml(before.replace("# Your note.", "# We keep it low."));
        YamlConfiguration jar = yaml("""
            # How many bounties /bounties lists (1 to 100).
            list-size: 50
            # Your note.
            tax: 0
            """);
        assertEquals(List.of("list-size"), YamlFiles.refreshComments(synced, jar, previous));
        assertEquals(List.of("How many bounties /bounties lists (1 to 100)."), synced.getComments("list-size"));
        YamlFiles.refreshComments(commented, jar, previous);
        assertEquals(List.of("We keep it low."), commented.getComments("tax"), "the admin's comment stays");
        assertTrue(synced.saveToString().contains("# How many bounties /bounties lists (1 to 100)."), synced.saveToString());
    }
}
