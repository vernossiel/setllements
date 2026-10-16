package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;

/**
 * A villager in real need (starving, or a builder stuck without materials) can be helped by handing them the item
 * directly. The item goes to that villager, never into a shared pool, and it earns reputation once per need.
 */
public final class Needs {
    public enum Need {
        NONE, FOOD, PLANKS, WOOL, IRON, COBBLE, MEAT;

        public boolean matches(ItemStack s) {
            return switch (this) {
                case FOOD -> s.isFood() && Workforce.eatable(s);
                case PLANKS -> s.isIn(ItemTags.PLANKS) || s.isIn(ItemTags.LOGS);
                case WOOL -> s.isIn(ItemTags.WOOL);
                case IRON -> s.isOf(Items.IRON_INGOT) || s.isOf(Items.RAW_IRON);
                case COBBLE -> s.isOf(Items.COBBLESTONE);
                case MEAT -> Cooking.isRaw(s);
                default -> false;
            };
        }

        public int wanted() {
            return switch (this) {
                case FOOD -> 3;
                case MEAT -> 8;
                case WOOL -> 3;
                default -> 16;
            };
        }
    }

    private Needs() {
    }

    public static Need get(VillagerEntityMCA v) {
        try {
            return Need.valueOf(v.settlementsNeed);
        } catch (IllegalArgumentException e) {
            return Need.NONE;
        }
    }

    public static void set(VillagerEntityMCA v, Need need) {
        v.settlementsNeed = need.name();
    }

    /** Once per second: starving villagers announce their need to a nearby player. */
    public static void update(VillagerEntityMCA v, Village village) {
        Need current = get(v);
        if (v.settlementsHunger >= 600 && !Workforce.hasMeal(v, village)) {
            set(v, Need.FOOD);
        } else if (current == Need.FOOD) {
            set(v, Need.NONE);
        }
        current = get(v);
        // hunger shows: grumbling first, then real distress
        if (v.age % 1200 == 600) {
            if (v.settlementsHunger >= 900) Speech.sayAny(v, "settlements.say.starving", 5);
            else if (v.settlementsHunger >= 600) Speech.sayAny(v, "settlements.say.hungry", 6);
            else if (v.settlementsEnergy < 250) Speech.sayAny(v, "settlements.say.weary", 4);
        }
        if (current == Need.NONE || v.age % 400 != 0) return;
        PlayerEntity p = v.getWorld().getClosestPlayer(v, 10);
        if (p != null) {
            String key = "settlements.need." + current.name().toLowerCase();
            if (current == Need.FOOD && v.getRandom().nextInt(3) > 0) key = key + "." + (1 + v.getRandom().nextInt(3));
            v.sendChatMessage(Text.translatable(key), p);
        }
    }

    /** Called when a player gives the villager the item in their hand. Returns true if it was a donation. */
    public static boolean tryDonate(VillagerEntityMCA v, PlayerEntity player) {
        Need need = get(v);
        if (need == Need.NONE) return false;
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || !need.matches(held)) return false;

        int amount = Math.min(held.getCount(), need.wanted());
        ItemStack given = held.split(amount);
        double value = Reputation.value(given);
        if (need == Need.FOOD) {
            v.getInventory().addStack(given);
        } else {
            Village village = Village.findNearest(v).orElse(null);
            if (village != null) Workforce.addToTreasury(village, given);
            else v.getInventory().addStack(given);
        }
        set(v, Need.NONE);
        int hearts = (int) Math.max(2, Math.min(15, value / 2));
        Village.findNearest(v).ifPresent(vil -> {
            vil.pushHearts(player, hearts);
            if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld gw) Fame.deed(gw, vil, player, Fame.Deed.GIFT, v, Math.max(0.5, value / 6));
        });
        v.sendChatMessage(player, "settlements.need.thanks");
        return true;
    }
}
