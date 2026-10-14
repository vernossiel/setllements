package net.mca.util.compat;

import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

public class ButtonWidget extends net.minecraft.client.gui.widget.ButtonWidget {
    /**
     * Creates a 1.19.2 and lower button implementation.
     *
     * @since MC 1.19.3
     */
    public ButtonWidget(int x, int y, int width, int height, Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    public ButtonWidget(int x, int y, int width, int height, Text message, PressAction onPress, Text tooltip) {
        this(x, y, width, height, message, onPress);
        setTooltip(Tooltip.of(tooltip));
    }
}
