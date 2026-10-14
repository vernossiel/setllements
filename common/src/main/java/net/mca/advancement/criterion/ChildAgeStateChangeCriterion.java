package net.mca.advancement.criterion;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.mca.MCA;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.advancement.criterion.AbstractCriterionConditions;
import net.minecraft.predicate.entity.AdvancementEntityPredicateDeserializer;
import net.minecraft.predicate.entity.AdvancementEntityPredicateSerializer;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ChildAgeStateChangeCriterion extends AbstractCriterion<ChildAgeStateChangeCriterion.Conditions> {
    private static final Identifier ID = MCA.locate("child_age_state_change");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Conditions conditionsFromJson(JsonObject json, LootContextPredicate player, AdvancementEntityPredicateDeserializer deserializer) {
        String event = json.has("state") ? json.get("state").getAsString() : "";
        return new Conditions(player, event);
    }

    public void trigger(ServerPlayerEntity player, String event) {
        trigger(player, (conditions) -> conditions.test(event));
    }

    public static class Conditions extends AbstractCriterionConditions {
        private final String event;

        public Conditions(LootContextPredicate player, String event) {
            super(ChildAgeStateChangeCriterion.ID, player);
            this.event = event;
        }

        public boolean test(String event) {
            return this.event.equals(event);
        }

        @Override
        public JsonObject toJson(AdvancementEntityPredicateSerializer serializer) {
            JsonObject json = super.toJson(serializer);
            json.add("state", new JsonPrimitive(event));
            return json;
        }
    }
}
