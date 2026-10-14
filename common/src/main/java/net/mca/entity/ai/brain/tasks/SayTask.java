package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.server.world.ServerWorld;

import java.util.function.Predicate;

public class SayTask extends MultiTickTask<VillagerEntityMCA> {
    private final String phrase;
    private final int interval;
    private final Predicate<VillagerEntityMCA> condition;

    private long lastShout;

    public SayTask(String phrase) {
        this(phrase, 0, (v) -> true);
    }

    public SayTask(String phrase, int interval, Predicate<VillagerEntityMCA> condition) {
        super(ImmutableMap.of());
        this.phrase = phrase;
        this.interval = interval;
        this.condition = condition;
    }

    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA entity) {
        return entity.getWorld().getTime() - lastShout > interval && condition.test(entity);
    }

    protected void run(ServerWorld world, VillagerEntityMCA entity, long time) {
        entity.sendChatToAllAround(phrase);
        lastShout = entity.getWorld().getTime();
    }
}
