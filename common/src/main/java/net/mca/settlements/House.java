package net.mca.settlements;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/** A house raised by the builders: exact geometry is known, so more floors can be added later. */
public record House(BlockPos origin, int w, int d, int floors, int rot) {
    /** Footprint after rotation. */
    public int fw() {
        return (rot & 1) == 0 ? w : d;
    }

    public int fd() {
        return (rot & 1) == 0 ? d : w;
    }

    public House withFloors(int f) {
        return new House(origin, w, d, f, rot);
    }

    public NbtCompound save() {
        NbtCompound n = new NbtCompound();
        n.putLong("o", origin.asLong());
        n.putInt("w", w);
        n.putInt("d", d);
        n.putInt("f", floors);
        n.putInt("r", rot);
        return n;
    }

    public static House load(NbtCompound n) {
        return new House(BlockPos.fromLong(n.getLong("o")), n.getInt("w"), n.getInt("d"), n.getInt("f"), n.getInt("r"));
    }
}
