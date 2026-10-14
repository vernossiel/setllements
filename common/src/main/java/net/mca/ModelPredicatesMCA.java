package net.mca;

import net.mca.item.BabyItem;
import net.mca.item.ItemsMCA;
import net.mca.item.SirbenBabyItem;
import net.mca.item.VillagerTrackerItem;
import net.mca.util.network.datasync.CDataParameter;
import net.minecraft.client.item.ClampedModelPredicateProvider;
import net.minecraft.client.item.CompassAnglePredicateProvider;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

public interface ModelPredicatesMCA {
    static void setup(CDataParameter.TriConsumer<Item, Identifier, ClampedModelPredicateProvider> register) {
        register.accept(ItemsMCA.BABY_BOY.get(), new Identifier("invalidated"), (stack, world, entity, i) ->
                BabyItem.hasBeenInvalidated(stack) ? 1 : 0
        );
        register.accept(ItemsMCA.BABY_GIRL.get(), new Identifier("invalidated"), (stack, world, entity, i) ->
                BabyItem.hasBeenInvalidated(stack) ? 1 : 0
        );
        register.accept(ItemsMCA.SIRBEN_BABY_BOY.get(), new Identifier("invalidated"), (stack, world, entity, i) ->
                SirbenBabyItem.hasBeenInvalidated(stack) ? 1 : 0
        );
        register.accept(ItemsMCA.SIRBEN_BABY_GIRL.get(), new Identifier("invalidated"), (stack, world, entity, i) ->
                SirbenBabyItem.hasBeenInvalidated(stack) ? 1 : 0
        );

        register.accept(ItemsMCA.VILLAGER_TRACKER.get(), new Identifier("angle"), new CompassAnglePredicateProvider((world, stack, entity) -> {
            return VillagerTrackerItem.getTargetPos(stack);
        }));
    }
}
