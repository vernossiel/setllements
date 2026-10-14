package net.mca.item;

import net.mca.util.localization.FlowingText;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class TooltippedItem extends Item {
    public TooltippedItem(Item.Settings properties) {
        super(properties);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.addAll(FlowingText.wrap(Text.translatable(getTranslationKey(stack) + ".tooltip").formatted(Formatting.GRAY), 160));
    }
}
