package net.mca.client.gui.widget;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

public class NamedTextFieldWidget extends TextFieldWidget {
    private final TextRenderer textRenderer;

    public NamedTextFieldWidget(TextRenderer textRenderer, int x, int y, int width, int height, Text text) {
        super(textRenderer, x + width / 2, y, width / 2, height, text);
        this.textRenderer = textRenderer;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderButton(context, mouseX, mouseY, delta);

        OrderedText orderedText = getMessage().asOrderedText();
        context.drawTextWithShadow(textRenderer, orderedText, (getX() - textRenderer.getWidth(orderedText) - 4), getY() + (height - 8) / 2, 0xffffff);
    }
}
