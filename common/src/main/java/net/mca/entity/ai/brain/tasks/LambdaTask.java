package net.mca.entity.ai.brain.tasks;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.function.Consumer;

public class LambdaTask<E extends VillagerEntityMCA> extends MultiTickTask<E> {
    private final Consumer<E> lambda;

    public LambdaTask(Consumer<E> lambda) {
        super(Map.of());
        this.lambda = lambda;
    }

    @Override
    protected boolean shouldRun(ServerWorld world, E entity) {
        return true;
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, E entity, long time) {
        return false;
    }

    @Override
    protected boolean isTimeLimitExceeded(long time) {
        return false;
    }

    @Override
    protected void run(ServerWorld world, E entity, long time) {
        lambda.accept(entity);
    }
}

