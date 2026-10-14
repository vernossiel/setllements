package net.mca.client.book.pages;

import net.mca.client.gui.ExtendedBookScreen;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class TitlePage extends Page {
    final Text title;
    final Text subtitle;

    public TitlePage(String book) {
        this(book, Formatting.BLACK);
    }

    public TitlePage(String book, Formatting color) {
        this("item.mca.book_" + book, "mca.books." + book + ".author", color);
    }

    public TitlePage(String title, String subtitle) {
        this(title, subtitle, Formatting.BLACK);
    }

    public TitlePage(String title, String subtitle, Formatting color) {
        this(Text.translatable(title).formatted(color).formatted(Formatting.BOLD),
                Text.translatable(subtitle).formatted(color).formatted(Formatting.ITALIC));
    }

    public TitlePage(Text title, Text subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    private static void drawCenteredText(ExtendedBookScreen screen, DrawContext context, TextRenderer textRenderer, Text text, int centerX, int y, int color) {
        OrderedText orderedText = text.asOrderedText();
        drawCenteredText(screen, context, textRenderer, orderedText, centerX, y, color);
    }

    private static void drawCenteredText(ExtendedBookScreen screen, DrawContext context, TextRenderer textRenderer, OrderedText text, int centerX, int y, int color) {
        context.drawText(textRenderer, text, (centerX - textRenderer.getWidth(text) / 2), y, color, screen.getBook().hasTextShadow());
    }

    @Override
    public void render(ExtendedBookScreen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        List<OrderedText> texts = screen.getTextRenderer().wrapLines(title, 114);
        int y = 80 - 5 * texts.size();
        for (OrderedText t : texts) {
            drawCenteredText(screen, context, screen.getTextRenderer(), t, screen.width / 2 - 2, y, 0xFFFFFF);
            y += 10;
        }
        y = 82 + 5 * texts.size();
        drawCenteredText(screen, context, screen.getTextRenderer(), subtitle, screen.width / 2 - 2, y, 0xFFFFFF);
    }
}
