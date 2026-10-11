package dev.modsbyfox.jane.neoforge.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modsbyfox.jane.core.ClientInstallCategory;
import dev.modsbyfox.jane.core.Comparison;
import dev.modsbyfox.jane.core.ManifestEntry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncSelectionScreenTest {
    private static final ManifestEntry TARGET = new ManifestEntry("example", "Example", "1", 4,
            "a".repeat(128));

    @Test void onlyHealthyUncertainUnmatchedCandidatesCanBeToggled() {
        Comparison.Result missing = new Comparison.Result(TARGET, null, Comparison.Status.MISSING, null);
        Comparison.Result error = new Comparison.Result(TARGET, null, Comparison.Status.FILE_ERROR, null);
        Comparison.Result matched = new Comparison.Result(TARGET,
                new Comparison.LocalMod("example", "1", Path.of("example.jar")),
                Comparison.Status.OK, TARGET.sha512());
        Comparison.Result ambiguous = new Comparison.Result(TARGET, null, Comparison.Status.MISSING, null,
                null, List.of(new Comparison.LocalMod("example", "1", Path.of("other.jar"))));

        assertTrue(SyncSelectionScreen.adjustable(missing, ClientInstallCategory.CLIENT_OPTIONAL));
        assertFalse(SyncSelectionScreen.adjustable(missing, ClientInstallCategory.SERVER_REQUIRED));
        assertFalse(SyncSelectionScreen.adjustable(error, ClientInstallCategory.CLIENT_OPTIONAL));
        assertFalse(SyncSelectionScreen.adjustable(matched, ClientInstallCategory.CLIENT_OPTIONAL));
        assertFalse(SyncSelectionScreen.adjustable(ambiguous, ClientInstallCategory.CLIENT_OPTIONAL));
    }

    @Test void declarationAndListStayInsideScaledScreen() {
        for (int[] size : new int[][] {{854, 480}, {426, 240}, {284, 160}, {214, 120},
                {1280, 720}, {330, 221}, {640, 360}}) {
            SyncSelectionScreen.Layout layout = SyncSelectionScreen.Layout.forSize(size[0], size[1]);
            assertTrue(layout.left() >= 0);
            assertTrue(layout.right() < size[0]);
            assertTrue(layout.declarationTop() >= 0);
            assertTrue(layout.declarationBottom() < layout.listTop());
            assertTrue(layout.listTop() < layout.listBottom());
            assertTrue(layout.listBottom() <= size[1] - 24);
        }
    }
}
