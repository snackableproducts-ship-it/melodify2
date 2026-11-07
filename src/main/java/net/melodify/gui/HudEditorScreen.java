package net.melodify.gui;

import net.melodify.MelodifyCore;
import net.melodify.config.MelodifyConfig;
import net.melodify.hud.HudRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Full-screen editor: drag the live HUD preview anywhere. Esc/Enter saves, R resets. */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private final MelodifyConfig cfg = MelodifyCore.config();
    private boolean dragging;
    private float grabDx, grabDy;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Move lyrics HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MelodifyCore.setHudEditing(true);
    }

    @Override
    public void removed() {
        MelodifyCore.setHudEditing(false);
        cfg.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x55000000);

        MelodifyCore.hud().render(g, MelodifyCore.lyrics().previewData());

        float[] b = HudRenderer.bounds(width, height);
        int x0 = Math.round(b[0]) - 2, y0 = Math.round(b[1]) - 2;
        int x1 = Math.round(b[0] + b[2]) + 2, y1 = Math.round(b[1] + b[3]) + 2;
        int col = dragging ? 0xFFFFFFFF : 0xAA6C7BFF;
        g.fill(x0, y0, x1, y0 + 1, col);
        g.fill(x0, y1 - 1, x1, y1, col);
        g.fill(x0, y0, x0 + 1, y1, col);
        g.fill(x1 - 1, y0, x1, y1, col);

        g.drawCenteredString(font, "Drag the lyrics HUD to move it", width / 2, height - 36, 0xFFE8EAF0);
        g.drawCenteredString(font, "Esc / Enter: save and go back   -   R: reset position", width / 2, height - 24, 0xFF8A90A0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            float[] b = HudRenderer.bounds(width, height);
            double mx = event.x(), my = event.y();
            if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
                dragging = true;
                grabDx = (float) (mx - b[0]);
                grabDy = (float) (my - b[1]);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            float[] b = HudRenderer.bounds(width, height);
            float freeW = Math.max(1f, width - b[2]);
            float freeH = Math.max(1f, height - b[3]);
            cfg.hudX = clamp01((float) (event.x() - grabDx) / freeW);
            cfg.hudY = clamp01((float) (event.y() - grabDy) / freeH);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            cfg.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_R) {
            cfg.resetPosition();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
