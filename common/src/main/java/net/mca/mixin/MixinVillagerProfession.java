package net.mca.mixin;

import com.google.common.collect.ImmutableSet;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

@Mixin(VillagerProfession.class)
public interface MixinVillagerProfession {
    @Invoker("<init>")
    static VillagerProfession init(String id, Predicate<RegistryEntry<PointOfInterestType>> predicate, Predicate<RegistryEntry<PointOfInterestType>> predicate2, ImmutableSet<Item> immutableSet, ImmutableSet<Block> immutableSet2, @Nullable SoundEvent soundEvent) {
        return null;
    }
}
