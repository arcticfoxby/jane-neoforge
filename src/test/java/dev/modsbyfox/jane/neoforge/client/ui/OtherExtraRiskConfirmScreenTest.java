package dev.modsbyfox.jane.neoforge.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OtherExtraRiskConfirmScreenTest {
    @Test void acknowledgementRequiresEnoughSpaceAndAReadableScrollArea() {
        for (int height : new int[] {120, 160, 221, 480}) {
            var layout = OtherExtraRiskConfirmScreen.Layout.forSize(320, height);
            assertTrue(layout.readable());
            assertTrue(layout.contentBottom() <= layout.confirmY() - 4);
            assertTrue(layout.confirmY() + 20 <= layout.cancelY());
            assertTrue(layout.maxScroll(1000) > 0);
        }
        assertFalse(OtherExtraRiskConfirmScreen.Layout.forSize(160, 221).readable());
        assertFalse(OtherExtraRiskConfirmScreen.Layout.forSize(320, 100).readable());
    }
}
