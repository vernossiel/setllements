package net.mca.mixin;

import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Settlements: the night's monsters do not rise out of the ground inside a village. They come in from the dark
 * outside, where the guards and the towers meet them; raids still come. (Night after night the zombies were spawning
 * in the village itself, at doors and in half-built rooms, and the villages died before they could grow.)
 */
@Mixin(SpawnHelper.class)
public abstract class MixinSpawnHelper {
    @Inject(method = "canSpawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/SpawnGroup;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/world/biome/SpawnSettings$SpawnEntry;Lnet/minecraft/util/math/BlockPos$Mutable;D)Z",
            at = @At("HEAD"), cancellable = true)
    private static void settlements$noMonstersInVillages(ServerWorld world, SpawnGroup group, StructureAccessor structures, ChunkGenerator generator,
                                                         SpawnSettings.SpawnEntry entry, BlockPos.Mutable pos, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (group == SpawnGroup.MONSTER && net.mca.settlements.Safety.inVillage(world, pos)) cir.setReturnValue(false);
    }
}
