package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Trade caravans really travel. Out of sight they are just a position on the line between two villages; when a player
 * is near, a merchant villager appears and walks the road with the goods. Kill him and the cargo drops (the sending
 * village remembers who did it), walk with him and both villages remember that too.
 * Caravans are kept in memory only: a server restart cancels the ones on the road and the goods are lost.
 */
public final class Caravans {
    private static final double SPEED_BLOCKS_PER_SECOND = 3.0;
    private static final double SEE_RANGE = 96;
    private static final double ESCORT_RANGE = 16;

    private static final class Caravan {
        int fromId, toId;
        String fromName, toName;
        List<ItemStack> cargo = new ArrayList<>();
        double sx, sz, ex, ez;
        long start, eta;
        UUID entity;
        double lastX, lastY, lastZ;
        final java.util.Set<UUID> escorts = new java.util.HashSet<>();
        final java.util.Map<UUID, Integer> escortSeconds = new java.util.HashMap<>();
    }

    private static final List<Caravan> ACTIVE = new ArrayList<>();

    private Caravans() {
    }

    public static void launch(ServerWorld world, Village from, Village to, List<ItemStack> cargo) {
        Caravan c = new Caravan();
        c.fromId = from.getId();
        c.toId = to.getId();
        c.fromName = from.getName();
        c.toName = to.getName();
        c.cargo = cargo;
        c.sx = from.getCenter().getX();
        c.sz = from.getCenter().getZ();
        c.ex = to.getCenter().getX();
        c.ez = to.getCenter().getZ();
        double dist = Math.hypot(c.ex - c.sx, c.ez - c.sz);
        c.start = world.getTime();
        c.eta = c.start + Math.max(600, (long) (dist / SPEED_BLOCKS_PER_SECOND * 20));
        ACTIVE.add(c);
    }

    /** Once per second per world. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        for (Iterator<Caravan> it = ACTIVE.iterator(); it.hasNext(); ) {
            Caravan c = it.next();
            double t = Math.min(1.0, (double) (now - c.start) / (c.eta - c.start));
            double x = c.sx + (c.ex - c.sx) * t, z = c.sz + (c.ez - c.sz) * t;

            VillagerEntityMCA merchant = c.entity == null ? null : world.getEntity(c.entity) instanceof VillagerEntityMCA m ? m : null;
            PlayerEntity near = world.getClosestPlayer(x, 64, z, SEE_RANGE, false);

            if (merchant != null && !merchant.isAlive()) {
                robbed(world, c, merchant);
                it.remove();
                continue;
            }
            if (t >= 1.0) {
                deliver(world, c);
                if (merchant != null) merchant.discard();
                it.remove();
                continue;
            }
            if (near != null) {
                if (merchant == null) {
                    BlockPos p = surface(world, x, z);
                    merchant = Gender.MALE.getVillagerType().spawn(world, p, SpawnReason.EVENT);
                    if (merchant == null) continue;
                    merchant.setCustomName(Text.translatable("settlements.caravan.merchant", c.fromName));
                    merchant.setCustomNameVisible(true);
                    merchant.settlementsCaravan = true;
                    c.entity = merchant.getUuid();
                }
                double ahead = Math.min(1.0, t + 20.0 / Math.max(1, Math.hypot(c.ex - c.sx, c.ez - c.sz)));
                BlockPos goal = surface(world, c.sx + (c.ex - c.sx) * ahead, c.sz + (c.ez - c.sz) * ahead);
                merchant.getNavigation().startMovingTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 0.6);
                c.lastX = merchant.getX();
                c.lastY = merchant.getY();
                c.lastZ = merchant.getZ();
                // the road follows the merchant, not the clock, while he is on screen
                double dist = Math.hypot(c.ex - c.sx, c.ez - c.sz);
                double walked = Math.hypot(merchant.getX() - c.sx, merchant.getZ() - c.sz);
                if (dist > 0 && walked / dist > t) c.start = now - (long) ((c.eta - c.start) * (walked / dist));
                for (PlayerEntity p : world.getPlayers()) {
                    if (p.squaredDistanceTo(merchant) < ESCORT_RANGE * ESCORT_RANGE) c.escortSeconds.merge(p.getUuid(), 1, Integer::sum);
                }
            } else if (merchant != null) {
                merchant.discard();
                c.entity = null;
            }
        }
    }

    private static BlockPos surface(ServerWorld world, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return new BlockPos(bx, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz);
    }

    private static void deliver(ServerWorld world, Caravan c) {
        VillageManager vm = VillageManager.get(world);
        Village to = vm.getOrEmpty(c.toId).orElse(null);
        Village from = vm.getOrEmpty(c.fromId).orElse(null);
        if (to == null) return;
        for (ItemStack s : c.cargo) Workforce.addToTreasury(to, s);
        if (from != null) Diplomacy.shiftRelation(from, to, 3);
        Diplomacy.announceNear(world, to, Text.translatable("settlements.caravan.arrived", c.fromName, c.toName));
        for (var e : c.escortSeconds.entrySet()) {
            if (e.getValue() < 30) continue; // walked along for at least half a minute
            PlayerEntity p = world.getPlayerByUuid(e.getKey());
            if (p == null) continue;
            to.pushHearts(p, 10);
            if (from != null) {
                from.pushHearts(p, 10);
                Diplomacy.shiftRelation(from, to, 2);
            }
            p.sendMessage(Text.translatable("settlements.caravan.escorted"), false);
        }
    }

    private static void robbed(ServerWorld world, Caravan c, VillagerEntityMCA merchant) {
        for (ItemStack s : c.cargo) {
            world.spawnEntity(new ItemEntity(world, c.lastX, c.lastY + 0.5, c.lastZ, s));
        }
        Village from = VillageManager.get(world).getOrEmpty(c.fromId).orElse(null);
        PlayerEntity killer = merchant.getAttacker() instanceof PlayerEntity p ? p : null;
        if (killer != null && from != null) {
            from.pushHearts(killer, -40);
            killer.sendMessage(Text.translatable("settlements.caravan.robbed.player", c.fromName), false);
        }
        VillageManager.get(world).getOrEmpty(c.toId).ifPresent(to -> {
            if (from != null) Diplomacy.shiftRelation(from, to, -5);
        });
    }
}
