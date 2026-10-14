package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.DebugInfoSender;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.minecraft.entity.ai.brain.task.FindPointOfInterestTask.findPathToPoi;

public class ExtendedFindPointOfInterestTask extends MultiTickTask<VillagerEntityMCA> {
    private static final int MAX_POSITIONS_PER_RUN = 5;
    private static final int POSITION_EXPIRE_INTERVAL = 200;
    public static final int POI_SORTING_RADIUS = 48;

    private final Consumer<VillagerEntityMCA> onFinish;
    private final BiPredicate<VillagerEntityMCA, BlockPos> predicate;

    private final Predicate<RegistryEntry<PointOfInterestType>> poiType;
    private final MemoryModuleType<GlobalPos> targetMemoryModuleType;
    private final boolean onlyRunIfAdult;
    private final Optional<Byte> entityStatus;
    private long positionExpireTimeLimit;
    private final Long2ObjectMap<RetryMarker> foundPositionsToExpiry = new Long2ObjectOpenHashMap<>();

    public ExtendedFindPointOfInterestTask(Predicate<RegistryEntry<PointOfInterestType>> poiType, MemoryModuleType<GlobalPos> moduleType, boolean onlyRunIfAdult, Optional<Byte> entityStatus, Consumer<VillagerEntityMCA> onFinish) {
        this(poiType, moduleType, onlyRunIfAdult, entityStatus, onFinish, (e, p) -> true);
    }

    public ExtendedFindPointOfInterestTask(Predicate<RegistryEntry<PointOfInterestType>> poiType, MemoryModuleType<GlobalPos> moduleType, boolean onlyRunIfAdult, Optional<Byte> entityStatus, Consumer<VillagerEntityMCA> onFinish, BiPredicate<VillagerEntityMCA, BlockPos> predicate) {
        super(create(moduleType));

        this.onFinish = onFinish;
        this.predicate = predicate;
        this.poiType = poiType;
        this.targetMemoryModuleType = moduleType;
        this.onlyRunIfAdult = onlyRunIfAdult;
        this.entityStatus = entityStatus;
    }

    private static ImmutableMap<MemoryModuleType<?>, MemoryModuleState> create(MemoryModuleType<GlobalPos> firstModule) {
        ImmutableMap.Builder<MemoryModuleType<?>, MemoryModuleState> builder = ImmutableMap.builder();
        builder.put(firstModule, MemoryModuleState.VALUE_ABSENT);
        return builder.build();
    }

    @Override
    protected boolean shouldRun(ServerWorld serverWorld, VillagerEntityMCA pathAwareEntity) {
        if (this.onlyRunIfAdult && pathAwareEntity.isBaby()) {
            return false;
        }
        if (this.positionExpireTimeLimit == 0L) {
            this.positionExpireTimeLimit = pathAwareEntity.getWorld().getTime() + (long)serverWorld.random.nextInt(POSITION_EXPIRE_INTERVAL);
            return false;
        }
        return serverWorld.getTime() >= this.positionExpireTimeLimit;
    }


    @Override
    protected void run(ServerWorld serverWorld, VillagerEntityMCA villager, long l) {
        this.positionExpireTimeLimit = l + POSITION_EXPIRE_INTERVAL + (long)serverWorld.getRandom().nextInt(POSITION_EXPIRE_INTERVAL);
        PointOfInterestStorage pointOfInterestStorage = serverWorld.getPointOfInterestStorage();
        this.foundPositionsToExpiry.long2ObjectEntrySet().removeIf(entry -> !entry.getValue().isAttempting(l));
        Predicate<BlockPos> predicate = blockPos -> {
            RetryMarker retryMarker = this.foundPositionsToExpiry.get(blockPos.asLong());
            if (retryMarker != null) {
                if (!retryMarker.shouldRetry(l)) {
                    return false;
                }
                retryMarker.setAttemptTime(l);
            }
            if (isBedOccupiedByOthers(serverWorld, blockPos, villager)) {
                return false;
            }
            return this.predicate.test(villager, blockPos);
        };
        Set<Pair<RegistryEntry<PointOfInterestType>, BlockPos>> set = pointOfInterestStorage.getSortedTypesAndPositions(this.poiType, predicate, villager.getBlockPos(), POI_SORTING_RADIUS, PointOfInterestStorage.OccupationStatus.HAS_SPACE).limit(MAX_POSITIONS_PER_RUN).collect(Collectors.toSet());
        Path path = findPathToPoi(villager, set);
        if (path != null && path.reachesTarget()) {
            BlockPos blockPos2 = path.getTarget();
            pointOfInterestStorage.getType(blockPos2).ifPresent(pointOfInterestType -> {
                pointOfInterestStorage.getPosition(this.poiType, (typeRegistryEntry, otherPos) -> {
                    return otherPos.equals(blockPos2);
                }, blockPos2, 1);

                villager.getBrain().remember(this.targetMemoryModuleType, GlobalPos.create(serverWorld.getRegistryKey(), blockPos2));
                this.entityStatus.ifPresent(statusByte -> serverWorld.sendEntityStatus(villager, statusByte));
                this.foundPositionsToExpiry.clear();
                DebugInfoSender.sendPointOfInterest(serverWorld, blockPos2);

                // on finish callback
                onFinish.accept(villager);
            });
        } else {
            for (Pair<RegistryEntry<PointOfInterestType>, BlockPos> blockPos2 : set) {
                this.foundPositionsToExpiry.computeIfAbsent(blockPos2.getSecond().asLong(), m -> new RetryMarker(villager.getWorld().random, l));
            }
        }
    }

    //todo this check is not necessary in vanilla, but since the 1.19.2 port of 7.4.0 it is requires as occupied beds are used
    private boolean isBedOccupiedByOthers(ServerWorld world, BlockPos pos, LivingEntity entity) {
        BlockState blockState = world.getBlockState(pos);
        return blockState.isIn(BlockTags.BEDS) && blockState.get(BedBlock.OCCUPIED) && !entity.isSleeping();
    }

    static class RetryMarker {
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
            this.nextScheduledAttemptAt = time + (long)this.currentDelay;
        }

        public boolean isAttempting(long time) {
            return time - this.previousAttemptAt < ATTEMPT_DURATION;
        }

        public boolean shouldRetry(long time) {
            return time >= this.nextScheduledAttemptAt;
        }
    }
}
