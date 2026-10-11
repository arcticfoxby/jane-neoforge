package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.ClientCompatibilityReport;
import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.PendingSyncContext;
import dev.modsbyfox.jane.core.SyncSelection;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client UI entry points. Connection authorization stays with the NeoForge client adapter. */
public final class JaneClientUi {
    public interface JoinActions {
        boolean reconnectWithExtras(PendingSyncContext context, ClientCompatibilityReport extras);
        boolean reconnectWithRequiredOverride(PendingSyncContext context, SyncSelection selection);
        default Component failureNotice() { return Component.translatable("jane.direct.reconnect_failed"); }
    }

    private JaneClientUi() { }

    public static boolean openReview(Screen parent, PendingSyncContext context,
                                     ClientCompatibilityReport extras, JoinActions actions) {
        return openReview(parent, context, extras, false, actions);
    }

    public static boolean openReview(Screen parent, PendingSyncContext context,
                                     ClientCompatibilityReport extras, boolean auditSaveFailed,
                                     JoinActions actions) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(actions, "actions");
        ClientCompatibilityReport report = extras == null
                ? new ClientCompatibilityReport(List.of(), List.of(), ClientCompatibilityReport.fingerprint(List.of()))
                : extras;
        Minecraft client = Minecraft.getInstance();
        try {
            Screen review = new EnvironmentReviewScreen(parent, context, report, auditSaveFailed, actions);
            client.setScreen(review);
            return client.screen == review;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static boolean openRecovery(Screen parent, PendingRecovery.Item item) {
        Minecraft client = Minecraft.getInstance();
        try {
            Screen recovery = new RecoveryScreen(parent, Objects.requireNonNull(item, "item"));
            client.setScreen(recovery);
            return client.screen == recovery;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
