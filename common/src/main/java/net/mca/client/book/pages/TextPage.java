package net.mca.client.book.pages;

import net.mca.client.gui.ExtendedBookScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.*;

import java.util.LinkedList;
import java.util.List;

public class TextPage extends Page {
    protected final String content;
    private Style style = Style.EMPTY;
    private List<OrderedText> cachedPage;

    public TextPage(String name, int page) {
        content = "{ \"translate\": \"mca.books." + name + "." + page + "\" }";
    }

    public TextPage(String content) {
        this.content = content;
    }

    protected List<OrderedText> getCachedPage(ExtendedBookScreen screen) {
        if (cachedPage == null) {
            StringVisitable stringVisitable = StringVisitable.styled(content, style);
            try {
                MutableText text = Text.Serializer.fromJson(content);
                if (text != null) {
                    text.fillStyle(style);
                }
                stringVisitable = text;
            } catch (Exception ignored) {
            }
            if (stringVisitable == null) {
                cachedPage = new LinkedList<>();
            } else {
                cachedPage = screen.getTextRenderer().wrapLines(stringVisitable, 114);
            }
        }
        return cachedPage;
    }

    public void render(ExtendedBookScreen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        //prepare page
        if (content != null) {
            // text
            int l = Math.min(128 / 9, getCachedPage(screen).size());
            int i = (screen.width - 192) / 2;
            for (int m = 0; m < l; ++m) {
                OrderedText orderedText = getCachedPage(screen).get(m);
                int x = i + 36;
                context.drawText(screen.getTextRenderer(), orderedText, x, (32 + m * 9), 0, screen.getBook().hasTextShadow());
            }
        }
    }

    public TextPage setStyle(Style style) {
        this.style = style;
        return this;
    }
}
