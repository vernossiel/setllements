package net.mca.util.network.datasync;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

public class NbtCompoundDefaultGetters {

    public static int getInt(NbtCompound nbt, String key, int def) {
        try {
            if (nbt.contains(key, 99)) {
                return nbt.getInt(key);
            }
        } catch (ClassCastException ignored) {
        }
        return def;
    }

    public static float getFloat(NbtCompound nbt, String key, float def) {
        try {
            if (nbt.contains(key, 99)) {
                return nbt.getFloat(key);
            }
        } catch (ClassCastException ignored) {
        }
        return def;
    }

    public static String getString(NbtCompound nbt, String key, String def) {
        try {
            if (nbt.contains(key, 8)) {
                return nbt.getString(key);
            }
        } catch (ClassCastException ignored) {
        }
        return def;
    }

    public static NbtCompound getCompound(NbtCompound nbt, String key, NbtCompound def) {
        try {
            if (nbt.contains(key, 10)) {
                return nbt.getCompound(key);
            }
        } catch (ClassCastException ignored) {
        }
        return def.copy();
    }

    public static ItemStack getItemStack(NbtCompound nbt, String key, ItemStack def) {
        try {
            if (nbt.contains(key, 10)) {
                return ItemStack.fromNbt(nbt.getCompound(key));
            }
        } catch (ClassCastException ignored) {
        }
        return def;
    }
}
