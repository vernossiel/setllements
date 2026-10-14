package net.mca.advancement.criterion;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.mca.MCA;
import net.mca.resources.Rank;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.advancement.criterion.AbstractCriterionConditions;
import net.minecraft.predicate.entity.AdvancementEntityPredicateDeserializer;
import net.minecraft.predicate.entity.AdvancementEntityPredicateSerializer;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class RankCriterion extends AbstractCriterion<RankCriterion.Conditions> {
    private static final Identifier ID = MCA.locate("rank");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Conditions conditionsFromJson(JsonObject json, LootContextPredicate player, AdvancementEntityPredicateDeserializer deserializer) {
        Rank rank = Rank.fromName(json.get("rank").getAsString());
        return new Conditions(player, rank);
    }

    public void trigger(ServerPlayerEntity player, Rank rank) {
        trigger(player, (conditions) -> conditions.test(rank));
    }

    public static class Conditions extends AbstractCriterionConditions {
        private final Rank rank;

        public Conditions(LootContextPredicate player, Rank rank) {
            super(RankCriterion.ID, player);
            this.rank = rank;
        }

        public boolean test(Rank rank) {
            return this.rank == rank;
        }

        @Override
        public JsonObject toJson(AdvancementEntityPredicateSerializer serializer) {
            JsonObject json = super.toJson(serializer);
            json.add("rank", new JsonPrimitive(rank.name()));
            return json;
        }
    }
}
