package net.mca.server.world.data;

import net.mca.Config;
import net.mca.util.MaxSizeHashMap;
import net.mca.util.NbtHelper;
import net.mca.util.WorldUtils;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;

import java.util.Map;
import java.util.UUID;

public class VillagerTrackerManager extends PersistentState {
    private final static int MAP_SIZE = 1024 * 16;

    private final Map<UUID, GlobalPos> entries;

    public static VillagerTrackerManager get(ServerWorld world) {
        return WorldUtils.loadData(world.getServer().getOverworld(), VillagerTrackerManager::new, VillagerTrackerManager::new, "mca_villager_tracker");
    }

    VillagerTrackerManager(ServerWorld world) {
        entries = new MaxSizeHashMap<>(MAP_SIZE);
    }

    VillagerTrackerManager(NbtCompound nbt) {
        entries = new MaxSizeHashMap<>(MAP_SIZE);
        entries.putAll(NbtHelper.toMap(nbt, UUID::fromString, (id, element) -> NbtHelper.decodeGlobalPos(element)));
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        return NbtHelper.fromMap(nbt, entries, UUID::toString, NbtHelper::encodeGlobalPosition);
    }

    public void remove(UUID id) {
        entries.remove(id);
        markDirty();
    }

    public static void update(Entity entity) {
        if (Config.getInstance().trackVillagerPosition && entity.getWorld() instanceof ServerWorld serverWorld) {
            get(serverWorld).set(entity);
        }
    }

    public void set(Entity entity) {
        entries.put(entity.getUuid(), GlobalPos.create(entity.getWorld().getRegistryKey(), entity.getBlockPos()));
    }

    public GlobalPos get(UUID id) {
        return entries.get(id);
    }
}
