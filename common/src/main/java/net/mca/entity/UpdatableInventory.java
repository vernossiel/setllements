package net.mca.entity;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.SimpleInventory;

public class UpdatableInventory extends SimpleInventory {
    public UpdatableInventory(int size) {
        super(size);
    }

    public void update(Entity entity) {
        for (int slot = 0; slot < size(); slot++) {
            if (!getStack(slot).isEmpty()) {
                getStack(slot).inventoryTick(entity.getWorld(), entity, slot, false);
            }
        }
    }
}
