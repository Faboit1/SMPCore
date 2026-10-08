package net.siftvanilla.siftcore.feature.auction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Measures stored item data. {@code ItemStack#serializeAsBytes()} is GZIP-compressed NBT, but what reaches clients
 * (and what a menu packet of 45 listings has to carry) is the uncompressed size, which compression can hide: a
 * shulker box of written books or a stick with thousands of repeated lore characters compresses to almost nothing.
 * Pure; reads at most {@code limit + 1} bytes.
 */
final class ItemData {

    private ItemData() {
    }

    /**
     * The uncompressed size of GZIP data, or {@code limit + 1} as soon as it is known to be larger than {@code limit}.
     *
     * @throws IllegalArgumentException when the data is not valid GZIP
     */
    static long uncompressedSize(byte[] gzip, long limit) {
        long counted = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                counted += read;
                if (counted > limit) {
                    return limit + 1;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Item data is not readable", e);
        }
        return counted;
    }
}
