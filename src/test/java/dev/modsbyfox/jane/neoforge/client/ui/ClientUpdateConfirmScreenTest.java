package dev.modsbyfox.jane.neoforge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientUpdateConfirmScreenTest {
    @Test void scrollListAndButtonsRemainSeparatedAtSmallGuiSizes() {
        for (int height : new int[] {120, 160, 221, 480}) {
            for (boolean blocked : new boolean[] {false, true}) {
                ClientUpdateConfirmScreen.Layout layout =
                        ClientUpdateConfirmScreen.Layout.forSize(height, blocked);
                assertTrue(layout.listTop() >= 0);
                assertTrue(layout.listBottom() > layout.listTop());
                assertTrue(layout.listBottom() <= height - 52,
                        "The scrolling list must not overlap the confirmation button");
                assertTrue(layout.rowHeight() > 0);
                assertEquals(0, layout.maxScroll(0));
                assertTrue(layout.maxScroll(20) > 0,
                        "Selected physical JARs must remain reachable by scrolling");
            }
        }
    }
}
