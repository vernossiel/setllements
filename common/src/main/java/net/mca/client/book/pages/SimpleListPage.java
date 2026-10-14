package net.mca.client.book.pages;

import net.mca.client.gui.ExtendedBookScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

public class SimpleListPage extends ListPage {
    public SimpleListPage(List<Text> text) {
        super(text);
    }

    @Override
    int getEntriesPerPage() {
        return 14;
    }

    @Override
    public void render(ExtendedBookScreen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        int y = 20;
        for (int i = page * getEntriesPerPage(); i < Math.min(text.size(), (page + 1) * getEntriesPerPage()); i++) {
            context.drawText(screen.getTextRenderer(), text.get(i), (screen.width - 192) / 2 + 36, y, 0xFF000000, screen.getBook().hasTextShadow());
            y += 10;
        }
    }
}
