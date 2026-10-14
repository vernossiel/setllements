package net.mca.server.world.data;

import net.mca.util.NbtHelper;
import net.mca.util.WorldUtils;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.PersistentState;

import java.util.LinkedList;
import java.util.List;

public class CivilRegistryManager extends PersistentState {
    private final LinkedList<Text> entries = new LinkedList<>();

    public static CivilRegistryManager get(ServerWorld world, Village village) {
        return WorldUtils.loadData(world.getServer().getOverworld(), CivilRegistryManager::new, CivilRegistryManager::new, "mca_civil_registry_" + village.getId());
    }

    CivilRegistryManager(ServerWorld world) {

    }

    CivilRegistryManager(NbtCompound nbt) {
        entries.addAll(NbtHelper.toList(nbt.get("entries"), element -> Text.Serializer.fromJson(element.asString())));
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList elements = NbtHelper.fromList(entries, a -> NbtString.of(Text.Serializer.toJson(a)));
        NbtCompound compound = new NbtCompound();
        compound.put("entries", elements);
        return compound;
    }

    public void addText(Text text) {
        entries.addFirst(text);
        markDirty();
    }

    public List<Text> getPage(int from, int to) {
        to = Math.min(entries.size(), to);
        return to <= from ? List.of() : entries.subList(from, to);
    }
}
