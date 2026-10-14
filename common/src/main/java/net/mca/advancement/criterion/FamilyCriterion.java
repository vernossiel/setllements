package net.mca.advancement.criterion;

import com.google.gson.JsonObject;
import net.mca.MCA;
import net.mca.server.world.data.FamilyTree;
import net.mca.server.world.data.FamilyTreeNode;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.advancement.criterion.AbstractCriterionConditions;
import net.minecraft.predicate.NumberRange;
import net.minecraft.predicate.entity.AdvancementEntityPredicateDeserializer;
import net.minecraft.predicate.entity.AdvancementEntityPredicateSerializer;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class FamilyCriterion extends AbstractCriterion<FamilyCriterion.Conditions> {
    private static final Identifier ID = MCA.locate("family");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Conditions conditionsFromJson(JsonObject json, LootContextPredicate player, AdvancementEntityPredicateDeserializer deserializer) {
        // quite limited, but I do not assume any more use cases
        NumberRange.IntRange c = NumberRange.IntRange.fromJson(json.get("children"));
        NumberRange.IntRange gc = NumberRange.IntRange.fromJson(json.get("grandchildren"));
        return new Conditions(player, c, gc);
    }

    public void trigger(ServerPlayerEntity player) {
        FamilyTreeNode familyTree = FamilyTree.get(player.getServerWorld()).getOrCreate(player);
        long c = familyTree.getRelatives(0, 1).count();
        long gc = familyTree.getRelatives(0, 2).count() - c;

        trigger(player, condition -> condition.test((int)c, (int)gc));
    }

    public static class Conditions extends AbstractCriterionConditions {
        private final NumberRange.IntRange children;
        private final NumberRange.IntRange grandchildren;

        public Conditions(LootContextPredicate player, NumberRange.IntRange children, NumberRange.IntRange grandchildren) {
            super(FamilyCriterion.ID, player);
            this.children = children;
            this.grandchildren = grandchildren;
        }

        public boolean test(int c, int gc) {
            return children.test(c) && grandchildren.test(gc);
        }

        @Override
        public JsonObject toJson(AdvancementEntityPredicateSerializer serializer) {
            JsonObject json = super.toJson(serializer);
            json.add("children", children.toJson());
            json.add("grandchildren", grandchildren.toJson());
            return json;
        }
    }
}
