package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.village.VillagerProfession;

import java.util.List;

/**
 * A village lives on when nobody is about. Minecraft runs nothing in the parts of the world no player is near; so the
 * village keeps its books instead, and when a player comes back the days he was away are caught up at once from them:
 * what the fields, the herd, the woods and the quarry brought in, what the people ate, what the builders put up in the
 * time (stone on stone, paid from the stores as they would have paid), who came to live there. Like S.T.A.L.K.E.R.'s
 * A-Life: the village moves on in figures while unseen, and in the world when seen.
 */
public final class Ledger {
    private static final long DAY = 24000L;
    private static final int MAX_DAYS = 60;

    private Ledger() {
    }

    /** Every half minute per world. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        for (Village v : VillageManager.get(world)) {
            if (v.hall == null) continue;
            BlockPos c = new BlockPos(v.getCenter());
            // lived in: the middle of it running (a player near enough for crops to grow) and a player close by
            boolean lived = world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4) && world.shouldTick(new ChunkPos(c))
                    && world.getClosestPlayer(c.getX(), c.getY(), c.getZ(), 80, false) != null;
            if (!lived) continue;
            if (v.lastLived > 0 && now - v.lastLived > DAY) {
                int days = (int) Math.min(MAX_DAYS, (now - v.lastLived) / DAY);
                catchUp(world, v, days);
            }
            v.lastLived = now;
        }
    }

    /** The days unseen, one after another: the work done, the bread eaten, the houses raised, the newcomers. */
    public static void catchUp(ServerWorld world, Village v, int days) {
        List<VillagerEntityMCA> folk = v.getResidents(world);
        int pop = Math.max(1, folk.size());
        int farmers = count(folk, VillagerProfession.FARMER), jacks = count(folk, VillagerProfession.FLETCHER) + count(folk, VillagerProfession.TOOLSMITH);
        int masons = count(folk, VillagerProfession.MASON), builders = Math.max(count(folk, VillagerProfession.CARTOGRAPHER), 0);
        int herd = count(folk, VillagerProfession.SHEPHERD) + count(folk, VillagerProfession.BUTCHER);
        int hunters = count(folk, VillagerProfession.LEATHERWORKER), fishers = count(folk, VillagerProfession.FISHERMAN);
        boolean miller = count(folk, net.mca.ProfessionsMCA.MILLER.get()) > 0;
        int laid = 0, came = 0, hungryDays = 0;
        for (int d = 0; d < days; d++) {
            // the day's work: each field worked yields its harvest (bread, more of it with a miller), the herd its
            // meat, the woods and the quarry what one man brings in a day
            int fields = Math.min(v.farms, farmers * 2);
            int bread = fields * (miller ? 14 : 8) + herd * 4 + hunters * 4 + fishers * 6;
            if (bread > 0) Workforce.addToTreasury(v, new ItemStack(Items.BREAD, bread));
            if (jacks > 0 && Crafts.count(v, s -> s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) < 256) Workforce.addToTreasury(v, new ItemStack(Items.OAK_LOG, jacks * 24));
            if (masons > 0 && Crafts.count(v, s -> s.isOf(Items.COBBLESTONE)) < 1200) Workforce.addToTreasury(v, new ItemStack(Items.COBBLESTONE, masons * 48));
            // the day's meals: three a head
            int food = Forage.food(v), meals = pop * 3;
            if (food >= meals) eat(v, meals);
            else {
                eat(v, food);
                hungryDays++;
            }
            // the builders' day
            if (builders > 0) laid += Builder.catchUp(world, v, builders * 90);
            // newcomers, to a village with room and bread to spare
            if (Forage.food(v) >= pop * 2 && world.random.nextFloat() < 0.3f) came += Arrivals.newcomers(world, v);
        }
        Life.news(v, "away", days, laid, came);
        if (hungryDays >= 3) Life.news(v, "away.hungry", hungryDays);
        v.markDirty();
    }

    private static int count(List<VillagerEntityMCA> folk, VillagerProfession p) {
        return (int) folk.stream().filter(r -> r.isAlive() && !r.isBaby() && r.getProfession() == p).count();
    }

    /** Takes {@code n} meals out of the stores (any food, a piece a meal). */
    private static void eat(Village v, int n) {
        Crafts.take(v, ItemStack::isFood, n);
    }
}
