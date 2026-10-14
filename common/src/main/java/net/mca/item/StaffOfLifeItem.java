package net.mca.item;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Rarity;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class StaffOfLifeItem extends TooltippedItem {
    public StaffOfLifeItem(Item.Settings properties) {
        super(properties);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        ActionResult result = ScytheItem.use(context, true);
        if (result == ActionResult.SUCCESS) {
            context.getStack().damage(1, context.getPlayer(), (x) -> {});
            return result;
        }
        return result;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable(getTranslationKey(stack) + ".uses", stack.getMaxDamage() - stack.getDamage()));
        tooltip.add(Text.literal(""));
        super.appendTooltip(stack, world, tooltip, context);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return Rarity.RARE;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }
}
