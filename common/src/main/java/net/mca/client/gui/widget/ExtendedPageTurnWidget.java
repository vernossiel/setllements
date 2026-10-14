package net.mca.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PageTurnWidget;
import net.minecraft.util.Identifier;

public class ExtendedPageTurnWidget extends PageTurnWidget {
    private final Identifier texture;

    private final boolean isNextPageButton;

    public ExtendedPageTurnWidget(int x, int y, boolean isNextPageButton, PressAction action, boolean playPageTurnSound, Identifier texture) {
        super(x, y, isNextPageButton, action, playPageTurnSound);
        this.isNextPageButton = isNextPageButton;
        this.texture = texture;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        int i = 0;
        int j = 192;
        if (isHovered()) {
            i += 23;
        }

        if (!isNextPageButton) {
            j += 13;
        }

        context.drawTexture(texture, getX(), getY(), i, j, 23, 13);
    }
}
