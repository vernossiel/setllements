package net.mca.entity.ai.brain.tasks;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.task.SingleTickTask;
import net.minecraft.server.world.ServerWorld;

import java.util.function.Predicate;

public class ConditionalSingleTickTask<E extends LivingEntity> extends SingleTickTask<E> {
    private final SingleTickTask<? super E> task;
    private final Predicate<E> predicate;

    public ConditionalSingleTickTask(SingleTickTask<? super E> task, Predicate<E> predicate) {
        super();

        this.task = task;
        this.predicate = predicate;
    }

    @Override
    public boolean trigger(ServerWorld world, E entity, long time) {
        return predicate.test(entity) && task.trigger(world, entity, time);
    }
}