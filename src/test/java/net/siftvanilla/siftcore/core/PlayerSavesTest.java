package net.siftvanilla.siftcore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Dupe audit R11: an item grid's crash copy lags one tick behind a click into it, so a player save in that tick must
 * have the open grid write its copy first. {@link Services#saveAfterTrade} does; this keeps every save going through it.
 */
class PlayerSavesTest {

    private static final Path SOURCES = Path.of("src/main/java/net/siftvanilla/siftcore");

    @Test
    void everyPlayerSaveGoesThroughSaveAfterTrade() throws IOException {
        List<String> direct = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(".saveData()")) {
                        direct.add(SOURCES.relativize(path) + ":" + (i + 1));
                    }
                }
            });
        }
        assertEquals(List.of("core/Services.java:" + saveLine()), direct,
            "player files are saved only by Services.saveAfterTrade, which writes the open grid's copy first");
    }

    private static int saveLine() throws IOException {
        List<String> lines = Files.readAllLines(SOURCES.resolve("core/Services.java"), StandardCharsets.UTF_8);
        int persist = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Menu.persistOpen(player);")) {
                persist = i;
            }
            if (lines.get(i).contains("player.saveData();")) {
                assertTrue(persist >= 0 && persist == i - 1, "the open grid writes its copy right before the save");
                return i + 1;
            }
        }
        return -1;
    }
}
