package net.mca.entity.ai;

import net.mca.Config;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Activity;
import net.minecraft.entity.ai.brain.Schedule;
import net.minecraft.entity.ai.brain.ScheduleBuilder;

public interface SchedulesMCA {
    //DEFAULT (500 ticks longer awaken)
    Schedule DEFAULT = new ScheduleBuilder(new Schedule())
            .withActivity(10, Activity.IDLE)
            .withActivity(2000, Activity.WORK)
            .withActivity(9000, Activity.MEET)
            .withActivity(11000, Activity.IDLE)
            .withActivity(12500, Activity.REST)
            .build();

    // NIGHT DEFAULT (500 ticks longer awaken)
    Schedule NIGHT_OWL_DEFAULT = new ScheduleBuilder(new Schedule())
            .withActivity(10, Activity.REST)
            .withActivity(12500, Activity.IDLE)
            .withActivity(15000, Activity.WORK)
            .withActivity(18000, Activity.MEET)
            .withActivity(19500, Activity.IDLE)
            .build();

    //DAY GUARD
    Schedule GUARD = new ScheduleBuilder(new Schedule())
            .withActivity(10, Activity.WORK)
            .withActivity(9000, Activity.MEET)
            .withActivity(11000, Activity.WORK)
            .withActivity(14000, Activity.IDLE)
            .withActivity(15000, Activity.REST)
            .build();

    //NIGHT GUARD
    Schedule GUARD_NIGHT = new ScheduleBuilder(new Schedule())
            .withActivity(10, Activity.REST)
            .withActivity(8000, Activity.IDLE)
            .withActivity(9000, Activity.MEET)
            .withActivity(14000, Activity.WORK)
            .build();

    //Schedule for inn guests
    Schedule GUESTS = new ScheduleBuilder(new Schedule())
            .withActivity(10, Activity.IDLE)
            .withActivity(12500, Activity.REST)
            .build();

    static void bootstrap() {
    }

    static Schedule getTypeSchedule(LivingEntity entity, boolean allowNightOwl, Schedule normalSchedule, Schedule nightSchedule) {
        return (allowNightOwl && entity.getRandom().nextFloat() < Config.getInstance().nightOwlChance) ? nightSchedule : normalSchedule;
    }

    static Schedule getTypeSchedule(LivingEntity entity, Schedule normalSchedule, Schedule nightSchedule) {
        return getTypeSchedule(entity, Config.getInstance().allowAnyNightOwl, normalSchedule, nightSchedule);
    }

    static Schedule getTypeSchedule(LivingEntity entity, boolean allowNightOwl) {
        return getTypeSchedule(entity, allowNightOwl, SchedulesMCA.DEFAULT, SchedulesMCA.NIGHT_OWL_DEFAULT);
    }

    static Schedule getTypeSchedule(LivingEntity entity) {
        return getTypeSchedule(entity, Config.getInstance().allowAnyNightOwl);
    }
}
