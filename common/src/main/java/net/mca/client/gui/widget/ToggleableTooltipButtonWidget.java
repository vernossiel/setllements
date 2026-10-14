package net.mca.client.gui.widget;

import net.minecraft.text.MutableText;

public class ToggleableTooltipButtonWidget extends TooltipButtonWidget {
    public boolean toggle;

    public ToggleableTooltipButtonWidget(int x, int y, int width, int height, boolean toggle, MutableText message, MutableText tooltip, PressAction onPress) {
        super(x, y, width, height, message, tooltip, onPress);

        this.toggle = toggle;
    }

    protected int getYImage(boolean hovered) {
        int i = 1;
        if (this.toggle) {
            i = 0;
        } else if (hovered) {
            i = 2;
        }
        return i;
    }
}
