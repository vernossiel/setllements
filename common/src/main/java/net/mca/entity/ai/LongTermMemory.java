package net.mca.entity.ai;

import com.google.gson.JsonObject;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * The long term memory stored String keys for a given amount of time
 * While not powerful in terms of features it allows adding more intelligence to villager interactions
 */
public class LongTermMemory {
    final HashMap<String, Long> memories = new HashMap<>();

    private final VillagerEntityMCA entity;

    public LongTermMemory(VillagerEntityMCA entity) {
        this.entity = entity;
    }

    public void writeToNbt(NbtCompound nbt) {
        NbtCompound memory = new NbtCompound();
        for (Map.Entry<String, Long> entry : memories.entrySet()) {
            memory.putLong(entry.getKey(), entry.getValue());
        }
        nbt.put("longTermMemory", memory);
    }

    public void readFromNbt(NbtCompound nbt) {
        NbtCompound memory = nbt.getCompound("longTermMemory");
        memories.clear();
        for (String key : memory.getKeys()) {
            memories.put(key, memory.getLong(key));
        }
    }

    //remember forever
    public void remember(String id) {
        remember(id, Integer.MAX_VALUE);
    }

    public void remember(String id, long time) {
        long currentTime = entity.getWorld().getTime();
        if (memories.containsKey(id)) {
            currentTime = Math.max(currentTime, memories.get(id));
        }
        memories.put(id, currentTime + time);
    }

    public long getMemory(String id) {
        if (memories.containsKey(id)) {
            if (entity.getWorld().getTime() > memories.get(id)) {
                memories.remove(id);
            } else {
                return memories.get(id) - entity.getWorld().getTime();
            }
        }
        return 0;
    }

    public boolean hasMemory(String id) {
        return getMemory(id) > 0;
    }

    public static String parseId(JsonObject json, ServerPlayerEntity player) {
        String id = json.get("id").getAsString();
        if (json.has("var")) {
            switch (json.get("var").getAsString()) {
                case "player" -> {
                    assert player != null;
                    id += "." + player.getUuid().toString();
                }
            }
        }
        return id;
    }
}
