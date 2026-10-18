package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.Map;

/**
 * The farmer keeps his own fields, not just reaps them: trampled earth is hoed back into tilth, bare beds are sown,
 * and the growing crop is fed - bone meal from the stores, or from the compost heap he fills with the surplus seed
 * and chaff. One thing at a time, within arm's reach of where he is working.
 */
public final class Farming {
    private static final Map<Item, Block> SOW = Map.of(Items.WHEAT_SEEDS, Blocks.WHEAT, Items.CARROT, Blocks.CARROTS,
            Items.POTATO, Blocks.POTATOES, Items.BEETROOT_SEEDS, Blocks.BEETROOTS);

    private Farming() {
    }

    /** Once a second while the farmer is at his work. */
    public static void tend(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || Dig.busy(v)) return;
        Built farm = null;
        for (Built b : village.built) {
            if (!b.type().equals("farm")) continue;
            if (b.contains(v.getBlockPos()) || b.origin().isWithinDistance(v.getBlockPos(), 10)) {
                farm = b;
                break;
            }
        }
        if (farm == null) return;
        int y = farm.origin().getY() - 1; // the tilled ground of the field
        BlockPos feet = v.getBlockPos();
        BlockPos till = null, sow = null, feed = null;
        for (BlockPos p : BlockPos.iterate(feet.getX() - 4, y, feet.getZ() - 4, feet.getX() + 4, y, feet.getZ() + 4)) {
            if (!inside(farm, p) || !world.getBlockState(p.up()).isAir() && !(world.getBlockState(p.up()).getBlock() instanceof CropBlock) && !weed(world.getBlockState(p.up()))) continue;
            BlockState s = world.getBlockState(p), above = world.getBlockState(p.up());
            if ((s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.COARSE_DIRT)) && (above.isAir() || weed(above)) && till == null) till = p.toImmutable(); // (water near or not: a bed far from it still bears, only slower - the corner beds of a field lay undug)
            else if (s.isOf(Blocks.FARMLAND) && above.isAir() && sow == null) sow = p.up().toImmutable();
            else if (above.getBlock() instanceof CropBlock crop && !crop.isMature(above) && feed == null) feed = p.up().toImmutable();
        }
        if (till != null && Dig.sees(v, till)) {
            if (!Gear.has(v, HoeItem.class)) Gear.equipTool(v, village, Chore.HARVEST);
            if (!Gear.wear(v, HoeItem.class)) return;
            Gear.face(v, till);
            if (weed(world.getBlockState(till.up()))) world.breakBlock(till.up(), false, v);
            world.setBlockState(till, Blocks.FARMLAND.getDefaultState());
            world.playSound(null, till, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1f, 1f);
            v.swingHand(v.getDominantHand());
            Doing.set(v, "settlements.doing.farm.till");
            return;
        }
        if (sow != null && Dig.sees(v, sow)) {
            Block crop = takeSeed(v, village);
            if (crop != null) {
                Gear.face(v, sow);
                world.setBlockState(sow, crop.getDefaultState());
                world.playSound(null, sow, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1f, 1f);
                v.swingHand(v.getDominantHand());
                Doing.set(v, "settlements.doing.farm.sow");
                return;
            }
        }
        // the crop fed now and then: the stores' bone meal, or the compost made from the surplus
        if (feed != null && world.random.nextInt(6) == 0 && Dig.sees(v, feed) && (take(village, Items.BONE_MEAL, 1) || compost(village))) {
            BlockState s = world.getBlockState(feed);
            if (s.getBlock() instanceof CropBlock crop) {
                Gear.face(v, feed);
                crop.applyGrowth(world, feed, s);
                world.syncWorldEvent(1505, feed, 15); // the green sparkle of bone meal
                v.swingHand(v.getDominantHand());
                Doing.set(v, "settlements.doing.farm.feed");
                if (world.random.nextInt(8) == 0) Speech.say(v, "settlements.say.farm.feed");
            }
        }
    }

    private static final Map<java.util.UUID, BlockPos> TARGET = new java.util.HashMap<>();
    private static final Map<java.util.UUID, Long> LOOKED = new java.util.HashMap<>();
    private static final int FIELD_RADIUS = 64; // (at 48 a field out on the edge of a spread village stood ripe and never reaped)

    /**
     * A farmer's day on the village's fields, whoever laid them out (the old fields of the village too, not only the
     * farms our builders make): the ripe crop is reaped and the bed sown again at once, a bare bed sown, the growing
     * crop fed now and then. He goes to the nearest bed that wants something done; true while there is any.
     */
    public static boolean fields(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || Dig.busy(v)) return false;
        java.util.UUID id = v.getUuid();
        BlockPos t = TARGET.get(id);
        if (t != null && want(world, t) == 0) t = null;
        if (t == null) {
            if (world.getTime() < LOOKED.getOrDefault(id, 0L)) return false; // looked a moment ago: nothing to do
            t = look(v, village, world);
            LOOKED.put(id, world.getTime() + (t == null ? 400 : 0));
            if (t == null) {
                TARGET.remove(id);
                return false;
            }
            TARGET.put(id, t);
        }
        int what = want(world, t);
        if (v.squaredDistanceTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) > 6.25) {
            Move.to(v, t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 0.55);
            Doing.set(v, what == 1 ? "settlements.doing.farm.reap" : what == 2 ? "settlements.doing.farm.sow" : what == 4 ? "settlements.doing.farm.till" : "settlements.doing.farm.feed");
            return true;
        }
        v.getNavigation().stop();
        Gear.face(v, t);
        BlockState s = world.getBlockState(t);
        if (what == 1 && s.getBlock() instanceof CropBlock crop) {
            // reaped into his bag (it goes to the stores with the rest), the bed sown again with the seed of it
            double yield = Harvest.factor(world, village); // the land and the year
            for (ItemStack drop : Block.getDroppedStacks(s, world, t, null, v, v.getMainHandStack())) {
                Harvest.scale(drop, yield, world.random);
                if (drop.isEmpty()) continue;
                ItemStack left = v.getInventory().addStack(drop);
                if (!left.isEmpty()) Block.dropStack(world, t, left);
            }
            world.setBlockState(t, crop.withAge(0));
            Pose.crouch(v, 24); // down to the bed
            Anim.set(v, Anim.Act.REAP, 30);
            world.playSound(null, t, SoundEvents.BLOCK_CROP_BREAK, SoundCategory.BLOCKS, 1f, 1f);
            Doing.set(v, "settlements.doing.farm.reap");
        } else if (what == 2) {
            Block crop = takeSeed(v, village);
            if (crop == null) {
                TARGET.remove(id);
                LOOKED.put(id, world.getTime() + 600); // nothing to sow with
                return false;
            }
            world.setBlockState(t, crop.getDefaultState());
            world.playSound(null, t, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1f, 1f);
            Pose.crouch(v, 24);
            Anim.set(v, Anim.Act.SOW, 30);
            Doing.set(v, "settlements.doing.farm.sow");
        } else if (what == 4) {
            // a new bed hoed beside the old ones (by hand if there is no hoe), to be sown next
            if (!Gear.has(v, HoeItem.class)) Gear.equipTool(v, village, Chore.HARVEST);
            if (Gear.has(v, HoeItem.class)) Gear.wear(v, HoeItem.class);
            if (weed(world.getBlockState(t))) world.breakBlock(t, false, v); // the grass on it pulled up
            world.setBlockState(t.down(), Blocks.FARMLAND.getDefaultState());
            world.playSound(null, t, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1f, 1f);
            Anim.set(v, Anim.Act.DIG, 30);
            Doing.set(v, "settlements.doing.farm.till");
        } else if (what == 3 && s.getBlock() instanceof CropBlock crop && (take(village, Items.BONE_MEAL, 1) || compost(village))) {
            crop.applyGrowth(world, t, s);
            world.syncWorldEvent(1505, t, 15);
            Anim.set(v, Anim.Act.SOW, 30);
            Doing.set(v, "settlements.doing.farm.feed");
        } else {
            TARGET.remove(id);
            return true;
        }
        v.swingHand(v.getDominantHand());
        TARGET.remove(id); // the next bed
        return true;
    }

    /** 1 a ripe crop, 2 a bare bed, 3 a growing crop (fed only when there is bone meal to spare), 0 nothing. */
    private static int want(ServerWorld world, BlockPos crop) {
        BlockState s = world.getBlockState(crop), under = world.getBlockState(crop.down());
        // (grass or a flower standing on the spot is pulled up first: the bare patch in a field, grown over, was never dug)
        if (!under.isOf(Blocks.FARMLAND)) return (newBed(world, crop.down()) || GARDEN.contains(crop.down().asLong())) && (s.isAir() || weed(s)) ? 4 : 0;
        if (s.getBlock() instanceof CropBlock c) return c.isMature(s) ? 1 : 3;
        return s.isAir() ? 2 : 0;
    }

    private static final Map<java.util.UUID, BlockPos> DRY = new java.util.HashMap<>();
    private static final Map<java.util.UUID, Long> DRY_LOOKED = new java.util.HashMap<>();

    /**
     * Nothing to reap or sow: the dry beds watered (a bed far from water dries out and its crop comes on three times
     * slower - the village went hungry with the farmer 'waiting for the crop' all day over dry earth). True while at it.
     */
    public static boolean water(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || Dig.busy(v)) return false;
        BlockPos t = DRY.get(v.getUuid());
        if (t != null && !(world.getBlockState(t).isOf(Blocks.FARMLAND) && world.getBlockState(t).get(net.minecraft.block.FarmlandBlock.MOISTURE) < 3)) t = null;
        if (t == null) {
            if (world.getTime() < DRY_LOOKED.getOrDefault(v.getUuid(), 0L)) return false;
            DRY_LOOKED.put(v.getUuid(), world.getTime() + 200); // the beds looked over: again in a while
            BlockPos feet = v.getBlockPos();
            double bd = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.iterate(feet.add(-24, -4, -24), feet.add(24, 3, 24))) {
                BlockState s = world.getBlockState(p);
                if (!s.isOf(Blocks.FARMLAND) || s.get(net.minecraft.block.FarmlandBlock.MOISTURE) >= 3) continue;
                if (!(world.getBlockState(p.up()).getBlock() instanceof CropBlock)) continue; // a bare bed waits for its seed
                double d = p.getSquaredDistance(feet);
                if (d < bd) {
                    bd = d;
                    t = p.toImmutable();
                }
            }
            if (t == null) {
                DRY.remove(v.getUuid());
                return false;
            }
            DRY.put(v.getUuid(), t);
        }
        Doing.set(v, "settlements.doing.farm.water");
        if (v.squaredDistanceTo(t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5) > 6.25) {
            Move.to(v, t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5, 0.55);
            return true;
        }
        v.getNavigation().stop();
        Gear.face(v, t);
        v.swingHand(v.getDominantHand());
        for (BlockPos q : BlockPos.iterate(t.add(-2, -1, -2), t.add(2, 1, 2))) {
            BlockState s = world.getBlockState(q);
            if (s.isOf(Blocks.FARMLAND)) world.setBlockState(q, s.with(net.minecraft.block.FarmlandBlock.MOISTURE, 7), Block.NOTIFY_LISTENERS);
        }
        world.spawnParticles(net.minecraft.particle.ParticleTypes.SPLASH, t.getX() + 0.5, t.getY() + 1.1, t.getZ() + 0.5, 20, 1.2, 0.1, 1.2, 0.1);
        world.playSound(null, t, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 0.6f, 1.2f);
        DRY.remove(v.getUuid());
        return true;
    }

    /** Earth beside a bed of the field, near water: where the field can grow by a bed. */
    /** Grass, a fern or a flower: pulled up before the ground under it is dug. */
    private static boolean weed(BlockState s) {
        return s.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE_BY_TREES) && s.getFluidState().isEmpty() && !(s.getBlock() instanceof CropBlock)
                || s.isIn(net.minecraft.registry.tag.BlockTags.SMALL_FLOWERS);
    }

    private static boolean newBed(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (!(s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK))) return false;
        // the fence is the edge of the field: no beds dug outside it, along it (a row of them ran round the outside)
        for (BlockPos q : BlockPos.iterate(p.add(-1, 0, -1), p.add(1, 2, 1))) {
            var b = world.getBlockState(q).getBlock();
            if (b instanceof net.minecraft.block.FenceBlock || b instanceof net.minecraft.block.FenceGateBlock || b instanceof net.minecraft.block.WallBlock) return false;
        }
        boolean beside = false;
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) if (world.getBlockState(p.offset(d)).isOf(Blocks.FARMLAND)) beside = true;
        return beside; // sown at once, a bed keeps without water too (the crop only comes on slower)
    }

    /** The nearest bed round the village that wants something done: ripe first, then bare; growing ones only with bone meal in the stores. */
    private static BlockPos look(VillagerEntityMCA v, Village village, ServerWorld world) {
        BlockPos c = new BlockPos(village.getCenter());
        boolean meal = count(village, Items.BONE_MEAL) > 0 || count(village, Items.WHEAT_SEEDS) >= 24;
        boolean seed = false;
        for (Item i : SOW.keySet()) if (count(village, i) > 0 || v.getInventory().count(i) > 0) seed = true;
        BlockPos best = null, bed = null;
        double bd = Double.MAX_VALUE, bedD = Double.MAX_VALUE;
        int beds = 0, most = 72 + 32 * Prosperity.tier(village);
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                // the crop stands on the farmland: the top is the farmland itself (a bare bed or a young crop) or one above
                for (int yy = y; yy >= y - 1; yy--) {
                    BlockPos p = new BlockPos(x, yy, z);
                    if (world.getBlockState(p.down()).isOf(Blocks.FARMLAND)) beds++;
                    int w = want(world, p);
                    if (w != 0 && Move.unreachable(world, p)) break; // no way up to it: the other beds first
                    if (w == 4) {
                        boolean plot = village.getBuildingAt(p).isPresent(); // a new bed: not into a plot or a building
                        for (int[] pl : village.plots) if (x >= pl[0] - 1 && x <= pl[0] + pl[2] && z >= pl[1] - 1 && z <= pl[1] + pl[3]) plot = true;
                        // ...but a field's own ground is for beds: a bare patch inside the fence was never dug, the
                        // field being "a plot" like any other
                        for (Built b : village.built) if (Buildings.base(b.type()).equals("farm") && (b.contains(p) || b.contains(p.down()))) plot = false;
                        if (plot) break;
                        double d = v.squaredDistanceTo(x + 0.5, yy, z + 0.5);
                        if (seed && d < bedD) {
                            bedD = d;
                            bed = p;
                        }
                        break;
                    }
                    if (w == 2 && !seed) BARE.put(village.getId(), world.getTime()); // a bed waiting for seed there is none of
                    if (w == 0 || w == 2 && !seed || w == 3 && !meal) continue;
                    double d = v.squaredDistanceTo(x + 0.5, yy, z + 0.5) + (w == 1 ? 0 : w == 2 ? 200 : 900);
                    if (d < bd) {
                        bd = d;
                        best = p;
                    }
                    break;
                }
            }
        }
        // nothing to do in the fields: they are made bigger, a bed at a time, while there is seed and use for more
        if (best == null && bed == null && seed && beds < most && Forage.hungry(village)) {
            // the fields fenced in and nowhere to dig another bed, and the village going hungry: a new garden by the water,
            // its first bed now, the rest beside it after (the farmer stood waiting on his crop while they starved)
            bed = gardenSpot(world, village, c);
            if (bed != null) GARDEN.add(bed.down().asLong());
        }
        return best != null ? best : beds < most ? bed : null;
    }

    /** Village -> when a bare bed was last seen with no seed to sow it. */
    private static final Map<Integer, Long> BARE = new java.util.HashMap<>();
    private static final Map<java.util.UUID, BlockPos> GRASS = new java.util.HashMap<>();

    /**
     * No seed left and beds lying bare: he goes over the grass round the fields pulling it up for its seed, as a player
     * does (the beds of a new field lay tilled and empty for days while the village went hungry). True while at it.
     */
    public static boolean seeds(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || world.getTime() - BARE.getOrDefault(village.getId(), -10000L) > 20 * 120) return false;
        for (Item i : SOW.keySet()) if (count(village, i) > 0 || v.getInventory().count(i) > 0) {
            BARE.remove(village.getId());
            LOOKED.remove(v.getUuid()); // seed in hand: to the beds at once
            return false;
        }
        BlockPos g = GRASS.get(v.getUuid());
        if (g != null && !isGrass(world.getBlockState(g))) g = null;
        if (g == null) {
            for (BlockPos q : BlockPos.iterateOutwards(v.getBlockPos(), 20, 4, 20)) {
                if (isGrass(world.getBlockState(q)) && !Move.unreachable(world, q)) {
                    g = q.toImmutable();
                    break;
                }
            }
            if (g == null) return false;
            GRASS.put(v.getUuid(), g);
        }
        Doing.set(v, "settlements.doing.farm.seeds");
        if (v.squaredDistanceTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5) > 4) {
            Move.to(v, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.55);
            return true;
        }
        v.getNavigation().stop();
        Gear.face(v, g);
        Pose.crouch(v, 16);
        v.swingHand(v.getDominantHand());
        world.breakBlock(g, false, v);
        // a handful of grass pulled over gives a seed more often than a player's single stroke (one in eight)
        if (world.random.nextInt(3) == 0) v.getInventory().addStack(new ItemStack(Items.WHEAT_SEEDS, 1 + world.random.nextInt(2)));
        GRASS.remove(v.getUuid());
        return true;
    }

    private static boolean isGrass(BlockState s) {
        return s.isOf(Blocks.GRASS) || s.isOf(Blocks.TALL_GRASS) || s.isOf(Blocks.FERN) || s.isOf(Blocks.LARGE_FERN);
    }

    /** Where a new garden was begun: its first bed may be dug though nothing is tilled beside it yet. */
    private static final java.util.Set<Long> GARDEN = new java.util.HashSet<>();

    /** Grass by the water near the village, clear of buildings, plots, roads and fences: the place for a new garden. */
    private static BlockPos gardenSpot(ServerWorld world, Village village, BlockPos c) {
        for (int r = 6; r <= 32; r += 2) {
            for (int a = 0; a < r * 4; a++) {
                double ang = a * Math.PI * 2 / (r * 4);
                int x = c.getX() + (int) Math.round(Math.cos(ang) * r), z = c.getZ() + (int) Math.round(Math.sin(ang) * r);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                BlockState g = world.getBlockState(p.down());
                if (!(g.isOf(Blocks.GRASS_BLOCK) || g.isOf(Blocks.DIRT)) || !world.getBlockState(p).isReplaceable() || !nearWater(world, p.down())) continue;
                if (!world.isSkyVisible(p) || village.getBuildingAt(p).isPresent()) continue;
                boolean clear = true;
                for (int[] pl : village.plots) if (x >= pl[0] - 2 && x <= pl[0] + pl[2] + 1 && z >= pl[1] - 2 && z <= pl[1] + pl[3] + 1) clear = false;
                for (Built b : village.built) if (b.contains(p)) clear = false;
                for (BlockPos q : BlockPos.iterate(p.add(-2, -1, -2), p.add(2, 1, 2))) {
                    var qb = world.getBlockState(q);
                    if (qb.isOf(Blocks.DIRT_PATH) || qb.getBlock() instanceof net.minecraft.block.FenceBlock || qb.getBlock() instanceof net.minecraft.block.DoorBlock
                            || qb.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) clear = false;
                }
                if (clear) return p.toImmutable();
            }
        }
        return null;
    }

    /** Within the fence, not on it or outside it. */
    private static boolean inside(Built farm, BlockPos p) {
        BlockPos o = farm.origin();
        return p.getX() > o.getX() && p.getX() < o.getX() + farm.fw() - 1 && p.getZ() > o.getZ() && p.getZ() < o.getZ() + farm.fd() - 1;
    }

    private static boolean nearWater(ServerWorld world, BlockPos p) {
        for (BlockPos q : BlockPos.iterate(p.add(-4, 0, -4), p.add(4, 1, 4))) if (world.getFluidState(q).isIn(net.minecraft.registry.tag.FluidTags.WATER)) return true;
        return false;
    }

    private static Block takeSeed(VillagerEntityMCA v, Village village) {
        var inv = v.getInventory();
        boolean hungry = Forage.hungry(village);
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            Block b = SOW.get(s.getItem());
            if (b != null && hungry && s.isOf(Items.WHEAT_SEEDS) && (count(village, Items.CARROT) > 0 || count(village, Items.POTATO) > 0)) continue; // the store's carrots first
            if (b != null) {
                s.decrement(1);
                return b;
            }
        }
        // a hungry village sows what feeds it soonest: a carrot comes up as two to five that are eaten as they are,
        // where wheat is a quarter of a loaf a stalk (the savanna, on poor land, starved on wheat with carrots in store)
        Item[] order = Forage.hungry(village) ? new Item[]{Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS, Items.WHEAT_SEEDS}
                : new Item[]{Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};
        for (Item i : order) if (take(village, i, 1)) return SOW.get(i);
        return null;
    }

    /** The compost heap: seven of the surplus seed (keeping a good stock back for sowing) make a bone meal. */
    private static boolean compost(Village village) {
        if (count(village, Items.WHEAT_SEEDS) < 24) return false;
        take(village, Items.WHEAT_SEEDS, 7);
        return true;
    }

    private static int count(Village village, Item item) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isOf(item)) n += s.getCount();
        return n;
    }

    private static boolean take(Village village, Item item, int n) {
        if (count(village, item) < n) return false;
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && n > 0; ) {
            ItemStack s = it.next();
            if (!s.isOf(item)) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
            if (s.isEmpty()) it.remove();
        }
        village.markDirty();
        return true;
    }
}
