package net.mca.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import static net.mca.client.gui.InteractScreen.ICON_TEXTURES;

public class ToggleableTooltipIconButtonWidget extends ToggleableTooltipButtonWidget {
    private final int u;
    private final int v;

    public ToggleableTooltipIconButtonWidget(int x, int y, int u, int v, boolean toggle, MutableText tooltip, PressAction onPress) {
        super(x, y, 16, 16, toggle, Text.literal(""), tooltip, onPress);

        this.u = u;
        this.v = v;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        drawIcon(context);
    }

    private void drawIcon(DrawContext context) {
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, this.alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        int offset = this.toggle ? 0 : 16;
        context.drawTexture(ICON_TEXTURES, this.getX(), this.getY(), u, v + offset, this.width, this.height);
    }
}
