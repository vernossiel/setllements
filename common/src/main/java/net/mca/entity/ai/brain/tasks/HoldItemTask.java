package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

public class HoldItemTask extends MultiTickTask<VillagerEntityMCA> {
    private final Hand hand;
    private final ItemStack item;

    public HoldItemTask(Hand hand, Item item) {
        this(hand, new ItemStack(item));
    }

    public HoldItemTask(Hand hand, ItemStack item) {
        super(ImmutableMap.of());

        this.hand = hand;
        this.item = item;
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA villager, long time) {
        villager.setStackInHand(hand, item);
    }
}
