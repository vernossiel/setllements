package net.mca.entity.interaction.gifts;

import net.mca.Config;
import net.mca.util.NbtHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedList;
import java.util.List;

public class GiftSaturation {
    private List<Identifier> values = new LinkedList<>();

    public void add(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        // add to queue
        Identifier id = Registries.ITEM.getId(stack.getItem());
        values.add(id);

        // clear old values if limit is reached
        while (values.size() > Config.getInstance().giftDesaturationQueueLength) {
            pop();
        }
    }

    public int get(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return (int)values.stream().filter(v -> v.equals(id)).count();
    }

    public void readFromNbt(NbtList nbt) {
        values = NbtHelper.toList(nbt, v -> new Identifier(v.asString()));
    }

    public NbtList toNbt() {
        return NbtHelper.fromList(values, v -> NbtString.of(v.toString()));
    }

    public void pop() {
        if (!values.isEmpty()) {
            values.remove(0);
        }
    }
}
