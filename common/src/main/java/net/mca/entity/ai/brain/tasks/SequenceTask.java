package net.mca.entity.ai.brain.tasks;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.server.world.ServerWorld;

import java.util.List;
import java.util.Map;

public class SequenceTask<E extends LivingEntity> extends MultiTickTask<E> {
    private final List<Task<? super E>> tasks;
    int progress = 0;

    public SequenceTask(Map<MemoryModuleType<?>, MemoryModuleState> requiredMemoryState, List<Task<? super E>> tasks) {
        super(requiredMemoryState);

        this.tasks = tasks;
    }

    private Task<? super E> getRunningTask() {
        return tasks.get(progress);
    }

    @Override
    protected void run(ServerWorld world, E entity, long time) {
        getRunningTask().tryStarting(world, entity, time);
    }

    @Override
    protected void keepRunning(ServerWorld world, E entity, long time) {
        this.tasks.stream().filter(task -> task.getStatus() == MultiTickTask.Status.RUNNING).forEach(task -> task.tick(world, entity, time));
    }

    @Override
    protected void finishRunning(ServerWorld world, E entity, long time) {
        progress = 0;
        this.tasks.stream().filter(task -> task.getStatus() == MultiTickTask.Status.RUNNING).forEach(task -> task.stop(world, entity, time));
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, E entity, long time) {
        if (this.tasks.stream().anyMatch(task -> task.getStatus() == MultiTickTask.Status.RUNNING)) {
            return true;
        } else if (progress < tasks.size() - 1) {
            progress++;
            getRunningTask().tryStarting(world, entity, time);
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected boolean isTimeLimitExceeded(long time) {
        return false;
    }
}
