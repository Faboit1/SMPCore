package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** The size limit measures what clients receive, not what compression makes of it. */
class ItemDataTest {

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(data);
        }
        return out.toByteArray();
    }

    @Test
    void measuresTheUncompressedSize() throws IOException {
        assertEquals(0, ItemData.uncompressedSize(gzip(new byte[0]), 100));
        assertEquals(1_000, ItemData.uncompressedSize(gzip(new byte[1_000]), 1_000));
    }

    @Test
    void compressionCannotHideAHugeItem() throws IOException {
        byte[] repeated = "a long lore line that repeats ".repeat(20_000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed = gzip(repeated);
        assertTrue(compressed.length < 16 * 1024, "repetitive data compresses to " + compressed.length + " bytes");
        assertEquals(128 * 1024 + 1, ItemData.uncompressedSize(compressed, 128 * 1024));
        assertEquals(repeated.length, ItemData.uncompressedSize(compressed, Long.MAX_VALUE - 1));
    }

    @Test
    void stopsReadingOnceOverTheLimit() throws IOException {
        assertEquals(11, ItemData.uncompressedSize(gzip(new byte[5_000_000]), 10));
    }

    @Test
    void unreadableDataIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> ItemData.uncompressedSize(new byte[] {1, 2, 3}, 100));
    }
}
