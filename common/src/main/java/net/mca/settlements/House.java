package net.mca.settlements;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/** A house raised by the builders: exact geometry is known, so more floors can be added later. */
public record House(BlockPos origin, int w, int d, int floors) {
    public NbtCompound save() {
        NbtCompound n = new NbtCompound();
        n.putLong("o", origin.asLong());
        n.putInt("w", w);
        n.putInt("d", d);
        n.putInt("f", floors);
        return n;
    }

    public static House load(NbtCompound n) {
        return new House(BlockPos.fromLong(n.getLong("o")), n.getInt("w"), n.getInt("d"), n.getInt("f"));
    }
}
