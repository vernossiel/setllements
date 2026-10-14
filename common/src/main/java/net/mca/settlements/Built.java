package net.mca.settlements;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * A finished building of the village, with its plan parameters, so it can be found, used and repaired later.
 * {@code w, d, f} are the plan parameters the blueprint was built with.
 */
public record Built(String type, BlockPos origin, int w, int d, int f, int rot) {
    public int[] footprint() {
        return Blueprints.footprint(type, w, d);
    }

    public int fw() {
        int[] fp = footprint();
        return (rot & 1) == 0 ? fp[0] : fp[1];
    }

    public int fd() {
        int[] fp = footprint();
        return (rot & 1) == 0 ? fp[1] : fp[0];
    }

    /** World position of a plan coordinate. */
    public BlockPos point(int dx, int dy, int dz) {
        int[] fp = footprint();
        int[] r = Blueprints.rotate(dx, dz, fp[0], fp[1], rot);
        return origin.add(r[0], dy, r[1]);
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= origin.getX() - 1 && p.getX() <= origin.getX() + fw() && p.getZ() >= origin.getZ() - 1 && p.getZ() <= origin.getZ() + fd()
                && p.getY() >= origin.getY() - 1 && p.getY() <= origin.getY() + Math.max(1, f) * Blueprints.LEVEL_HEIGHT + 12;
    }

    public List<Blueprints.Step> steps() {
        return Builder.stepsFor(type, w, d, f);
    }

    public Built withFloors(int floors) {
        return new Built(type, origin, w, d, floors, rot);
    }

    public NbtCompound save() {
        NbtCompound n = new NbtCompound();
        n.putString("t", type);
        n.putLong("o", origin.asLong());
        n.putInt("w", w);
        n.putInt("d", d);
        n.putInt("f", f);
        n.putInt("r", rot);
        return n;
    }

    public static Built load(NbtCompound n) {
        return new Built(n.getString("t"), BlockPos.fromLong(n.getLong("o")), n.getInt("w"), n.getInt("d"), n.getInt("f"), n.getInt("r"));
    }
}
