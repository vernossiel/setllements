package net.mca.client.gui;

import com.google.common.collect.Lists;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

public class ExtendedScreen extends Screen {
    protected ExtendedScreen(Text title) {
        super(title);
    }

    public int getTooltipWidth(List<Text> lines_) {
        List<? extends OrderedText> lines = Lists.transform(lines_, Text::asOrderedText);

        int w = 0;
        if (!lines.isEmpty()) {
            for (OrderedText orderedText : lines) {
                int j = textRenderer.getWidth(orderedText);
                if (j > w) {
                    w = j;
                }
            }
        }
        return w;
    }

    public int getTooltipHeight(List<Text> lines_) {
        List<? extends OrderedText> lines = Lists.transform(lines_, Text::asOrderedText);

        int h = 8;
        if (!lines.isEmpty()) {
            if (lines.size() > 1) {
                h += 2 + (lines.size() - 1) * 10;
            }
        }
        return h;
    }
}
