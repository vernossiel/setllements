package net.mca.client.book.pages;

import net.mca.client.gui.ExtendedBookScreen;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;

public class CenteredTextPage extends TextPage {
    public CenteredTextPage(String name, int page) {
        super(name, page);
    }

    public CenteredTextPage(String content) {
        super(content);
    }

    @Override
    public void render(ExtendedBookScreen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        //prepare page
        if (content != null) {
            TextRenderer textRenderer = screen.getTextRenderer();

            // text
            int l = Math.min(128 / 9, getCachedPage(screen).size());
            int i = (screen.width - 192) / 2;
            for (int m = 0; m < l; ++m) {
                OrderedText orderedText = getCachedPage(screen).get(m);
                int x = i + 36;
                context.drawText(textRenderer, orderedText, x + 114 / 2 - textRenderer.getWidth(orderedText) / 2, (32 + (m + 7 - (l / 2)) * 9), 0, screen.getBook().hasTextShadow());
            }
        }
    }
}
