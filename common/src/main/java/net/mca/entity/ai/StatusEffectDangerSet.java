package net.mca.entity.ai;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;

import java.util.HashSet;
import java.util.Set;

public class StatusEffectDangerSet {
    public static final Set<StatusEffect> isDanger = new HashSet<>();
    static {
        isDanger.add(StatusEffects.SLOWNESS);
        isDanger.add(StatusEffects.MINING_FATIGUE);
        isDanger.add(StatusEffects.INSTANT_DAMAGE);
        isDanger.add(StatusEffects.NAUSEA);
        isDanger.add(StatusEffects.BLINDNESS);
        isDanger.add(StatusEffects.HUNGER);
        isDanger.add(StatusEffects.WEAKNESS);
        isDanger.add(StatusEffects.POISON);
        isDanger.add(StatusEffects.WITHER);
        isDanger.add(StatusEffects.LEVITATION);
        isDanger.add(StatusEffects.UNLUCK);
        isDanger.add(StatusEffects.SPEED);
    }
}
