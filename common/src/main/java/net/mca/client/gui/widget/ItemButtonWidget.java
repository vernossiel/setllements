package net.mca.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public class ItemButtonWidget extends TooltipButtonWidget {
    final ItemStack item;

    public ItemButtonWidget(int x, int y, int size, MutableText message, ItemStack item, PressAction onPress) {
        super(x, y, size, size, Text.literal(""), message, onPress);

        this.item = item;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderButton(context, mouseX, mouseY, delta);

        int size = 16;

        context.drawItem(item, getX() + (width - size) / 2, getY() + (height - size) / 2);
    }
}
