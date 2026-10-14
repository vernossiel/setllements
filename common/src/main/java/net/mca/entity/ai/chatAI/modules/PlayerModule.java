package net.mca.entity.ai.chatAI.modules;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.advancement.Advancement;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PlayerModule {
    private final static Map<Identifier, String> advancements = Map.of(
            new Identifier("story/mine_diamond"), "$player found diamonds.",
            new Identifier("story/enter_the_nether"), "$player explored the nether.",
            new Identifier("nether/find_fortress"), "$player found a nether fortress.",
            new Identifier("story/enchant_item"), "$player enchanted items.",
            new Identifier("story/cure_zombie_villager"), "$player cured a zombie villager.",
            new Identifier("end/kill_dragon"), "$player killed the ender dragon.",
            new Identifier("nether/summon_wither"), "$player summoned the wither.",
            new Identifier("adventure/hero_of_the_village"), "$player is the hero of the village."
    );

    public static void apply(List<String> input, VillagerEntityMCA villager, ServerPlayerEntity player) {
        List<String> list = advancements.entrySet().stream()
                .filter(entry -> {
                    Advancement advancement = Objects.requireNonNull(player.getServer()).getAdvancementLoader().get(entry.getKey());
                    if (advancement == null) {
                        MCA.LOGGER.warn("Advancement {} not found.", entry.getKey());
                        return false;
                    }
                    return player.getAdvancementTracker().getProgress(advancement).isDone();
                })
                .map(Map.Entry::getValue)
                .toList();

        if (!list.isEmpty()) {
            input.add("Player has completed the following advancements: ");
            for (String advancement : list) {
                input.add(advancement + " ");
            }
        }
    }
}
