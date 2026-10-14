package net.mca.entity.ai.brain.tasks;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.SingleTickTask;
import net.minecraft.entity.ai.brain.task.TaskTriggerer;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.DebugInfoSender;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.poi.PointOfInterestType;

import java.util.function.Consumer;
import java.util.function.Predicate;

public class ExtendedForgetCompletedPointOfInterestTask {
    private static final int MAX_RANGE = 16;

    public ExtendedForgetCompletedPointOfInterestTask() {
    }

    public static SingleTickTask<LivingEntity> create(Predicate<RegistryEntry<PointOfInterestType>> poiTypePredicate, MemoryModuleType<GlobalPos> poiPosModule, Consumer<LivingEntity> onFinish) {
        return TaskTriggerer.task((context) -> {
            return context.group(context.queryMemoryValue(poiPosModule)).apply(context, (poiPos) -> {
                return (world, entity, time) -> {
                    GlobalPos globalPos = context.getValue(poiPos);
                    BlockPos blockPos = globalPos.getPos();
                    if (world.getRegistryKey() == globalPos.getDimension() && blockPos.isWithinDistance(entity.getPos(), MAX_RANGE)) {
                        ServerWorld serverWorld = world.getServer().getWorld(globalPos.getDimension());
                        if (serverWorld != null && serverWorld.getPointOfInterestStorage().test(blockPos, poiTypePredicate)) {
                            if (isBedOccupiedByOthers(serverWorld, blockPos, entity)) {
                                poiPos.forget();
                                world.getPointOfInterestStorage().releaseTicket(blockPos);
                                DebugInfoSender.sendPointOfInterest(world, blockPos);
                            }
                        } else {
                            poiPos.forget();
                        }

                        return true;
                    } else {
                        // We're finished -- run callback
                        if (entity.getBrain().isMemoryInState(poiPosModule, MemoryModuleState.VALUE_ABSENT)) {
                            onFinish.accept(entity);
                        }
                        return false;
                    }
                };
            });
        });
    }

    private static boolean isBedOccupiedByOthers(ServerWorld world, BlockPos pos, LivingEntity entity) {
        BlockState blockState = world.getBlockState(pos);
        return blockState.isIn(BlockTags.BEDS) && blockState.get(BedBlock.OCCUPIED) && !entity.isSleeping();
    }
}
