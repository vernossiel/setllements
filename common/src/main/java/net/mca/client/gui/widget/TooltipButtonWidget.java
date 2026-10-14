package net.mca.client.gui.widget;

import net.mca.util.compat.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public class TooltipButtonWidget extends ButtonWidget {
    public TooltipButtonWidget(int x, int y, int width, int height, String message, PressAction onPress) {
        super(x, y, width, height, Text.translatable(message), onPress, Text.translatable(message + ".tooltip"));
    }

    public TooltipButtonWidget(int x, int y, int width, int height, MutableText message, MutableText tooltip, PressAction onPress) {
        super(x, y, width, height, message, onPress, tooltip);
    }

    public void setMessage(String message) {
        super.setMessage(Text.translatable(message));
        super.setTooltip(Tooltip.of(Text.translatable(message + ".tooltip")));
    }
}
