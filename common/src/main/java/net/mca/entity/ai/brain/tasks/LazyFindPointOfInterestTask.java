package net.mca.entity.ai.brain.tasks;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.FindPointOfInterestTask;
import net.minecraft.entity.ai.brain.task.SingleTickTask;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.ai.brain.task.TaskTriggerer;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.DebugInfoSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import org.apache.commons.lang3.mutable.MutableLong;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A lazy version of {@link FindPointOfInterestTask} with longer cooldowns
 */
public class LazyFindPointOfInterestTask extends FindPointOfInterestTask {
    private static final int MIN_DELAY = 200;

    public static Task<PathAwareEntity> create(Predicate<RegistryEntry<PointOfInterestType>> poiPredicate, MemoryModuleType<GlobalPos> poiPosModule, MemoryModuleType<GlobalPos> potentialPoiPosModule, boolean onlyRunIfChild, Optional<Byte> entityStatus) {
        MutableLong cooldown = new MutableLong(0L);
        Long2ObjectMap<RetryMarker> long2ObjectMap = new Long2ObjectOpenHashMap<>();
        SingleTickTask<PathAwareEntity> singleTickTask = TaskTriggerer.task(taskContext -> {
            return taskContext.group(taskContext.queryMemoryAbsent(potentialPoiPosModule)).apply(taskContext, queryResult -> {
                return (world, entity, time) -> {
                    if (onlyRunIfChild && entity.isBaby()) {
                        return false;
                    } else if (cooldown.getValue() == 0L) {
                        cooldown.setValue(world.getTime() + (long) world.random.nextInt(MIN_DELAY));
                        return false;
                    } else if (world.getTime() < cooldown.getValue()) {
                        return false;
                    } else {
                        cooldown.setValue(time + MIN_DELAY + (long) world.getRandom().nextInt(MIN_DELAY));

                        PointOfInterestStorage pointOfInterestStorage = world.getPointOfInterestStorage();
                        long2ObjectMap.long2ObjectEntrySet().removeIf(entry -> {
                            return !entry.getValue().isAttempting(time);
                        });

                        Predicate<BlockPos> predicate2 = pos -> {
                            RetryMarker retryMarker = long2ObjectMap.get(pos.asLong());
                            if (retryMarker == null) {
                                return true;
                            } else if (!retryMarker.shouldRetry(time)) {
                                return false;
                            } else {
                                retryMarker.setAttemptTime(time);
                                return true;
                            }
                        };

                        Set<Pair<RegistryEntry<PointOfInterestType>, BlockPos>> set = pointOfInterestStorage.getSortedTypesAndPositions(poiPredicate, predicate2, entity.getBlockPos(), 48, PointOfInterestStorage.OccupationStatus.HAS_SPACE).limit(5L).collect(Collectors.toSet());
                        Path path = findPathToPoi(entity, set);
                        if (path != null && path.reachesTarget()) {
                            BlockPos blockPos = path.getTarget();
                            pointOfInterestStorage.getType(blockPos).ifPresent(poiType -> {
                                pointOfInterestStorage.getPosition(poiPredicate, (registryEntry, blockPos2) -> {
                                    return blockPos2.equals(blockPos);
                                }, blockPos, 1);
                                queryResult.remember(GlobalPos.create(world.getRegistryKey(), blockPos));
                                entityStatus.ifPresent(status -> {
                                    world.sendEntityStatus(entity, status);
                                });
                                long2ObjectMap.clear();
                                DebugInfoSender.sendPointOfInterest(world, blockPos);
                            });
                        } else {
                            for (Pair<RegistryEntry<PointOfInterestType>, BlockPos> registryEntryBlockPosPair : set) {
                                long2ObjectMap.computeIfAbsent(registryEntryBlockPosPair.getSecond().asLong(), m -> {
                                    return new RetryMarker(world.random, time);
                                });
                            }
                        }

                        return true;
                    }
                };
            });
        });

        return potentialPoiPosModule == poiPosModule ? singleTickTask : TaskTriggerer.task(context -> {
            return context.group(context.queryMemoryAbsent(poiPosModule)).apply(context, poiPos -> {
                return singleTickTask;
            });
        });
    }

    private static class RetryMarker {
        private static final int MIN_DELAY = 40;
        private static final int ATTEMPT_DURATION = 400;
        private final Random random;
        private long previousAttemptAt;
        private long nextScheduledAttemptAt;
        private int currentDelay;

        RetryMarker(Random random, long time) {
            this.random = random;
            this.setAttemptTime(time);
        }

        public void setAttemptTime(long time) {
            this.previousAttemptAt = time;
            int i = this.currentDelay + this.random.nextInt(MIN_DELAY) + MIN_DELAY;
            this.currentDelay = Math.min(i, ATTEMPT_DURATION);
            this.nextScheduledAttemptAt = time + (long) this.currentDelay;
        }

        public boolean isAttempting(long time) {
            return time - this.previousAttemptAt < ATTEMPT_DURATION;
        }

        public boolean shouldRetry(long time) {
            return time >= this.nextScheduledAttemptAt;
        }

        public String toString() {
            return "RetryMarker{, previousAttemptAt=" + this.previousAttemptAt + ", nextScheduledAttemptAt=" + this.nextScheduledAttemptAt + ", currentDelay=" + this.currentDelay + "}";
        }
    }
}
