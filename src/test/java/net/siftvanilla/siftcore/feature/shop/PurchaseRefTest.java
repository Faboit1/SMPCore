package net.siftvanilla.siftcore.feature.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The part of a purchase that goes into the inventory waits in the claim box under a reference of its own, so the claim
 * after the commit takes exactly that purchase's items and nothing else that is waiting there.
 */
class PurchaseRefTest {

    @Test
    void everyPurchaseHasItsOwnReferenceThatFitsTheClaimBox() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String ref = PurchaseFlow.handRef();
            assertTrue(ref.startsWith("shop:"), ref);
            assertTrue(ref.length() <= 64, "deliveries.ref is VARCHAR(64): " + ref);
            assertTrue(seen.add(ref), "repeated " + ref);
        }
        assertEquals(10_000, seen.size());
    }
}
