package dev.modsbyfox.jane.neoforge.client.ui;

import dev.modsbyfox.jane.core.PendingRecovery;
import dev.modsbyfox.jane.core.UpdatePlan;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Retry or explicitly discard an interrupted, instance-local pending update. */
final class RecoveryScreen extends UiScreen {
    private final PendingRecovery.Item pending;
    private boolean busy;
    private boolean details;
    private boolean abandonArmed;
    private UpdatePlan ready;
    private Component notice;
    private Button retryButton;
    private Button abandonButton;

    RecoveryScreen(Screen parent, PendingRecovery.Item pending) {
        super(Component.translatable("jane.recovery.title"), parent);
        this.pending = pending;
    }

    @Override protected void init() {
        clearWidgets();
        int w = Math.min(100, Math.max(1, (width - 24) / 3));
        int x = (width - (w * 3 + 8)) / 2;
        int y = height - 29;
        retryButton = addRenderableWidget(Button.builder(Component.translatable(ready == null
                ? "jane.recovery.retry" : "jane.sync.finish"), button -> retry())
                .bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("jane.sync.details"),
                button -> details = !details).bounds(x + w + 4, y, w, 20).build());
        abandonButton = addRenderableWidget(Button.builder(Component.translatable("jane.recovery.abandon"),
                button -> abandon()).bounds(x + (w + 4) * 2, y, w, 20).build());
        retryButton.active = pending.plan() != null && !busy;
        abandonButton.active = !busy;
    }

    private void retry() {
        if (busy || pending.plan() == null) return;
        busy = true;
        notice = Component.translatable("jane.recovery.verifying");
        refreshButtons();
        Path gameDir = FMLPaths.GAMEDIR.get();
        if (ready == null) {
            CompletableFuture.supplyAsync(() -> {
                try { return PendingRecovery.prepareRetry(gameDir, pending); }
                catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
            }).whenComplete((plan, error) -> minecraft.execute(() -> {
                busy = false;
                if (error != null) notice = Component.translatable("jane.recovery.unsafe");
                else {
                    ready = plan;
                    notice = Component.translatable("jane.sync.confirm", plan.operations().size());
                }
                if (minecraft.screen == this) rebuildWidgets();
            }));
            return;
        }
        UpdatePlan selected = ready;
        CompletableFuture.runAsync(() -> {
            try { PendingRecovery.verifyForLaunch(gameDir, selected); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((unused, error) -> minecraft.execute(() -> {
            if (error == null && minecraft.screen == this) {
                try {
                    UpdateLauncher.launch(minecraft, gameDir, selected);
                    return;
                } catch (Exception exception) {
                    notice = Component.translatable("jane.sync.launch_failed");
                }
            } else if (error != null) notice = Component.translatable("jane.recovery.unsafe");
            busy = false;
            if (minecraft.screen == this) rebuildWidgets();
        }));
    }

    private void abandon() {
        if (busy) return;
        if (!abandonArmed) {
            abandonArmed = true;
            notice = Component.translatable("jane.recovery.abandon_confirm");
            return;
        }
        busy = true;
        refreshButtons();
        Path gameDir = FMLPaths.GAMEDIR.get();
        CompletableFuture.supplyAsync(() -> {
            try {
                PendingRecovery.abandon(gameDir, pending);
                return PendingRecovery.scan(gameDir);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((items, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) {
                notice = Component.translatable("jane.recovery.abandon_failed");
                if (minecraft.screen == this) rebuildWidgets();
            } else if (minecraft.screen == this) {
                minecraft.setScreen(items.isEmpty() ? parent : new RecoveryScreen(parent, items.get(0)));
            }
        }));
    }

    private void refreshButtons() {
        if (retryButton != null) retryButton.active = pending.plan() != null && !busy;
        if (abandonButton != null) abandonButton.active = !busy;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        centered(graphics, title, 14, 0xFFFFFF);
        centered(graphics, Component.translatable("jane.recovery.incomplete"), 37, 0xFFCC77);
        if (details && height >= 175) {
            centered(graphics, Component.literal("Sync ID: " + pending.syncId()), 58, 0xBBBBBB);
            centered(graphics, Component.literal("State: " + (pending.failed() ? "FAILED" : "INTERRUPTED")),
                    71, 0xBBBBBB);
            if (pending.plan() != null)
                centered(graphics, Component.literal("Operations: " + pending.plan().operations().size()),
                        84, 0xBBBBBB);
            if (pending.issue() != null)
                centered(graphics, Component.translatable("jane.recovery.corrupt"), 97, 0xFF7777);
        }
        if (ready != null) {
            centered(graphics, Component.translatable("jane.sync.confirm_line2"), height - 65, 0xBBBBBB);
            centered(graphics, Component.translatable("jane.sync.confirm_line3"), height - 52, 0xBBBBBB);
        } else if (notice != null) centered(graphics, notice, height - 48, 0xFFCC77);
        renderWidgets(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() {
        if (!busy) super.onClose();
    }
}
