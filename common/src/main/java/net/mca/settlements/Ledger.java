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
 * A village lives on when nobody is about. Minecraft runs nothing where no player is near, so each village also keeps
 * books, and every day they are reckoned whether anybody is there or not: what the fields, the herd, the hunter and
 * the fisherman brought in, the logs and the stone, three meals a head eaten, hunger, newcomers to a village with room
 * and bread, people gone from a hungry one. The stores change then and there (and with them what the village can
 * trade or covet); only what has to be put into the world - the builders' work, the newcomers, the leavers - waits
 * until a player comes near, and then appears. Like S.T.A.L.K.E.R.'s A-Life: the village moves on in figures while
 * unseen, and in the world when seen.
 */
public final class Ledger {
    private static final long DAY = 24000L;
    // the workers, as last seen: indices into Village.ledgerRoles
    private static final int POP = 0, FARM = 1, WOOD = 2, STONE = 3, BUILD = 4, HERD = 5, HUNT = 6, FISH = 7, MILL = 8, MEALS = 9, N = 10;

    private Ledger() {
    }

    /** Every half minute per world. */
    public static void tick(ServerWorld world) {
        long today = world.getTimeOfDay() / DAY;
        for (Village v : VillageManager.get(world)) {
            if (v.hall == null) continue;
            if (v.ledgerDay >= 0 && v.ledgerDay < today) Fame.day(world, v); // a new day: old deeds fade, news travels
            if (lived(world, v)) {
                snapshot(world, v);
                settle(world, v);
                v.ledgerDay = today;
                v.lastLived = world.getTime();
                continue;
            }
            if (v.ledgerDay < 0) v.ledgerDay = today;
            for (int guard = 0; v.ledgerDay < today && guard < 60; guard++) {
                day(v);
                v.ledgerDay++;
            }
        }
    }

    /** Running in the world now: the middle of it ticking (a player near enough for crops to grow), a player close by. */
    private static boolean lived(ServerWorld world, Village v) {
        BlockPos c = new BlockPos(v.getCenter());
        return world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4) && world.shouldTick(new ChunkPos(c))
                && world.getClosestPlayer(c.getX(), c.getY(), c.getZ(), 80, false) != null;
    }

    /** Who works at what, written into the books while the village can be seen. */
    private static void snapshot(ServerWorld world, Village v) {
        List<VillagerEntityMCA> folk = v.getResidents(world);
        int[] r = new int[N];
        r[POP] = folk.size();
        float meals = 0;
        for (VillagerEntityMCA f : folk) {
            if (f.isAlive()) meals += 3 * Workforce.appetite(f); // three a head, more for the ones at heavy work, less for the children
            if (!f.isAlive() || f.isBaby()) continue;
            VillagerProfession p = f.getProfession();
            if (p == VillagerProfession.FARMER) r[FARM]++;
            else if (p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH) r[WOOD]++;
            else if (p == VillagerProfession.MASON) r[STONE]++;
            else if (p == VillagerProfession.CARTOGRAPHER) r[BUILD]++;
            else if (p == VillagerProfession.SHEPHERD || p == VillagerProfession.BUTCHER) r[HERD]++;
            else if (p == VillagerProfession.LEATHERWORKER) r[HUNT]++;
            else if (p == VillagerProfession.FISHERMAN) r[FISH]++;
            else if (p == net.mca.ProfessionsMCA.MILLER.get()) r[MILL]++;
        }
        r[MEALS] = Math.round(meals);
        v.ledgerRoles = r;
    }

    /** One day of the village unseen, in figures. */
    private static void day(Village v) {
        int[] r = v.ledgerRoles.length == N ? v.ledgerRoles : new int[N];
        int pop = Math.max(1, Math.max(r[POP], v.getPopulation()) - v.owedLeavers + v.owedNewcomers);
        int fields = Math.min(v.farms, r[FARM] * 2);
        int bread = fields * (r[MILL] > 0 ? 14 : 8) + r[HERD] * 4 + r[HUNT] * 4 + r[FISH] * 6;
        if (bread > 0) Workforce.addToTreasury(v, new ItemStack(Items.BREAD, bread));
        if (r[WOOD] > 0 && Crafts.count(v, s -> s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) < 256) Workforce.addToTreasury(v, new ItemStack(Items.OAK_LOG, r[WOOD] * 24));
        if (r[STONE] > 0 && Crafts.count(v, s -> s.isOf(Items.COBBLESTONE)) < 1200) Workforce.addToTreasury(v, new ItemStack(Items.COBBLESTONE, r[STONE] * 48));
        int food = Forage.food(v), meals = r[MEALS] > 0 ? r[MEALS] + (pop - r[POP]) * 3 : pop * 3;
        Crafts.take(v, ItemStack::isFood, Math.min(food, meals));
        if (food < meals / 2 && pop > 4 && Math.random() < 0.3) v.owedLeavers++; // hunger drives somebody away
        if (r[BUILD] > 0) v.owedSteps = Math.min(v.owedSteps + r[BUILD] * 90, 3000);
        int room = v.getMaxPopulation() - pop;
        if (room > 0 && Forage.food(v) >= pop * 2 && Math.random() < 0.3) v.owedNewcomers++;
        v.markDirty();
    }

    /** A player has come near: what the books owe the world is put into it. */
    private static void settle(ServerWorld world, Village v) {
        if (v.owedSteps <= 0 && v.owedNewcomers <= 0 && v.owedLeavers <= 0) return;
        int laid = v.owedSteps > 0 ? Builder.catchUp(world, v, v.owedSteps) : 0;
        v.owedSteps = 0;
        int came = 0;
        for (int i = 0; i < Math.min(4, v.owedNewcomers); i++) came += Arrivals.newcomers(world, v);
        v.owedNewcomers = 0;
        int left = 0;
        for (int i = 0; i < Math.min(3, v.owedLeavers); i++) if (Arrivals.goneWhileAway(world, v)) left++;
        v.owedLeavers = 0;
        if (laid > 0 || came > 0 || left > 0) Life.news(v, "away", laid, came, left);
        v.markDirty();
    }
}
