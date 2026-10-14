package net.mca.settlements;

import net.mca.ProfessionsMCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.resources.Names;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.Attachment;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Settlements that come with the world, in place of vanilla villages (whose generation the mod's data pack switches
 * off). The world is cut into regions; most regions hold one site, placed from the world seed. When a player comes
 * near a site, the settlement is founded there: its trade follows the land (lumberjacks in the woods, miners in the
 * hills, farmers on the plains, now and then an outpost or a trading village) and its stage is chosen at random,
 * richer the further from spawn: a poor new hamlet, a village, a well-off village, or a small town.
 */
public final class Sites extends PersistentState {
    private static final int REGION = 576;
    private static final int TRIGGER = 144;
    private final Set<Long> done = new HashSet<>();

    public enum Trade { LUMBER, MINING, FARMING, OUTPOST, TRADING }

    private Sites() {
    }

    private static Sites get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(Sites::load, Sites::new, "settlements_sites");
    }

    private static Sites load(NbtCompound nbt) {
        Sites s = new Sites();
        for (long l : nbt.getLongArray("done")) s.done.add(l);
        return s;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.put("done", new NbtLongArray(done.stream().mapToLong(Long::longValue).toArray()));
        return nbt;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) ^ (rz & 0xffffffffL);
    }

    /** The site of a region, or null if the region has none (a quarter of them). */
    private static BlockPos site(ServerWorld world, int rx, int rz) {
        Random r = new Random(world.getSeed() ^ (rx * 341873128712L + rz * 132897987541L) ^ 0x5E771E5L);
        if (r.nextFloat() < 0.25f) return null;
        int x = rx * REGION + 96 + r.nextInt(REGION - 192), z = rz * REGION + 96 + r.nextInt(REGION - 192);
        return new BlockPos(x, 0, z);
    }

    /** Off for now: vanilla villages generate as usual while villager behaviour is being worked out. */
    public static final boolean ENABLED = false;

    /** Every five seconds: found the sites players are coming close to. */
    public static void tick(ServerWorld world) {
        if (!ENABLED || !world.getRegistryKey().equals(World.OVERWORLD)) return;
        Sites sites = get(world);
        for (ServerPlayerEntity p : world.getPlayers()) {
            int prx = Math.floorDiv(p.getBlockX(), REGION), prz = Math.floorDiv(p.getBlockZ(), REGION);
            for (int rx = prx - 1; rx <= prx + 1; rx++) {
                for (int rz = prz - 1; rz <= prz + 1; rz++) {
                    long k = key(rx, rz);
                    if (sites.done.contains(k)) continue;
                    BlockPos s = site(world, rx, rz);
                    if (s == null) {
                        sites.done.add(k);
                        sites.markDirty();
                        continue;
                    }
                    double dx = s.getX() - p.getX(), dz = s.getZ() - p.getZ();
                    if (dx * dx + dz * dz > TRIGGER * TRIGGER) continue;
                    if (!loaded(world, s, 40)) continue;
                    sites.done.add(k);
                    sites.markDirty();
                    // a village already there (an older world, or one the villagers founded themselves)
                    if (VillageManager.get(world).findNearestVillage(s, 160).isPresent()) continue;
                    Random r = new Random(world.getSeed() ^ k ^ 0x7A11E5L);
                    int stage = stage(world, s, r);
                    settle(world, s.getX(), s.getZ(), stage, null, r);
                }
            }
        }
    }

    private static boolean loaded(ServerWorld world, BlockPos c, int radius) {
        for (int x = c.getX() - radius; x <= c.getX() + radius; x += 16)
            for (int z = c.getZ() - radius; z <= c.getZ() + radius; z += 16)
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        return true;
    }

    /** 0 poor hamlet, 1 village, 2 well-off village, 3 small town; far from spawn the odds go up. */
    private static int stage(ServerWorld world, BlockPos s, Random r) {
        BlockPos spawn = world.getSpawnPos();
        double far = Math.min(1.0, Math.sqrt(s.getSquaredDistance(spawn.getX(), s.getY(), spawn.getZ())) / 4000.0);
        double roll = r.nextDouble() - far * 0.25;
        if (roll < 0.35) return 0;
        if (roll < 0.70) return 1;
        if (roll < 0.93) return 2;
        return 3;
    }

    private static String biome(ServerWorld world, BlockPos p) {
        return world.getBiome(p).getKey().map(k -> k.getValue().getPath()).orElse("plains");
    }

    private static boolean habitable(String b) {
        return !(b.contains("ocean") || b.contains("river") || b.contains("beach") || b.contains("peaks") || b.contains("swamp")
                || b.contains("mushroom") || b.equals("jungle") || b.contains("bamboo") || b.contains("deep_dark") || b.contains("frozen"));
    }

    private static Trade trade(String b, int y, Random r) {
        double x = r.nextDouble();
        if (b.contains("windswept") || b.contains("stony") || b.contains("slopes") || b.contains("badlands") || y > 105)
            return x < 0.7 ? Trade.MINING : Trade.OUTPOST;
        if (b.contains("forest") || b.contains("taiga") || b.contains("birch") || b.contains("grove") || b.contains("cherry"))
            return x < 0.6 ? Trade.LUMBER : x < 0.85 ? Trade.FARMING : Trade.OUTPOST;
        if (b.contains("desert")) return x < 0.5 ? Trade.TRADING : Trade.MINING;
        if (b.contains("snowy")) return x < 0.6 ? Trade.LUMBER : Trade.MINING;
        return x < 0.55 ? Trade.FARMING : x < 0.8 ? Trade.TRADING : Trade.OUTPOST;
    }

    /**
     * Founds a settlement around (x, z): the village bell on a stone post at the center, the town hall and hearth, the
     * buildings of its trade and stage, the settlers. {@code trade} null: chosen by the land.
     */
    public static Village settle(ServerWorld world, int x, int z, int stage, Trade trade, Random r) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        if (!world.getFluidState(ground).isEmpty() || !world.getFluidState(ground.up()).isEmpty()) return null;
        String b = biome(world, ground);
        if (!habitable(b)) return null;
        if (trade == null) trade = trade(b, y, r);

        // the bell is the village's center: MCA knows a village from the moment it rings
        BlockPos post = ground.up();
        world.setBlockState(post, Blocks.STONE_BRICKS.getDefaultState());
        BlockPos bell = post.up();
        world.setBlockState(bell, Blocks.BELL.getDefaultState().with(BellBlock.ATTACHMENT, Attachment.FLOOR).with(BellBlock.FACING, Direction.SOUTH));
        VillageManager vm = VillageManager.get(world);
        vm.processBuilding(bell, true, false);
        Village village = vm.findNearestVillage(bell, 8).orElse(null);
        if (village == null) return null;
        village.seeded = true;

        Builder.foundHall(world, village);
        Camp.ensureHearth(village, world);
        Roads.plaza(world, village);

        List<String[]> plan = new ArrayList<>();
        // homes
        int homes = new int[]{2, 3, 5, 7}[stage];
        for (int i = 0; i < homes; i++) {
            String home = stage == 0 ? "hut" : stage == 1 ? (i % 2 == 0 ? "hut" : "house") : stage == 2 ? (i % 2 == 0 ? "annex" : "house") : (i < 2 ? "manor" : i % 2 == 0 ? "annex" : "house");
            plan.add(new String[]{home});
        }
        if (stage >= 1) {
            plan.add(new String[]{"well"});
            plan.add(new String[]{"board"});
        }
        switch (trade) {
            case LUMBER -> {
                if (stage >= 1) plan.add(new String[]{"sawmill"});
                if (stage >= 1) plan.add(new String[]{"charcoal"});
            }
            case MINING -> {
                if (stage >= 1) plan.add(new String[]{"charcoal"});
                if (stage >= 2) plan.add(new String[]{"mason"});
            }
            case FARMING -> {
                if (stage >= 1) plan.add(new String[]{"farm"});
                if (stage >= 1) plan.add(new String[]{"pen"});
                if (stage >= 2) plan.add(new String[]{"granary"});
                if (stage >= 2) plan.add(new String[]{"mill"});
            }
            case OUTPOST -> {
                if (stage >= 1) plan.add(new String[]{"tower"});
                if (stage >= 2) plan.add(new String[]{"barracks"});
            }
            case TRADING -> {
                if (stage >= 1) plan.add(new String[]{"market"});
                if (stage >= 2) plan.add(new String[]{"granary"});
            }
        }
        if (stage >= 2) {
            plan.add(new String[]{"storage"});
            plan.add(new String[]{"farm"});
            plan.add(new String[]{"tower"});
            if (trade != Trade.LUMBER) plan.add(new String[]{"sawmill"});
        }
        if (stage >= 3) {
            plan.add(new String[]{"chapel"});
            plan.add(new String[]{"barracks"});
            plan.add(new String[]{"market"});
            plan.add(new String[]{"mason"});
            plan.add(new String[]{"tower"});
        }
        for (String[] p : plan) raise(world, village, p[0]);
        village.wellBuilt = true; // raised above, or the hamlet draws its water from the stream
        if (stage >= 2) Builder.raiseNow(world, village, "hallup", 0, 0, 0, 0, 0);
        if (stage >= 3) Builder.raiseNow(world, village, "hallup", 0, 0, 0, 0, 0);

        // stores to match the stage
        int s1 = stage + 1;
        Workforce.addToTreasury(village, new ItemStack(Items.OAK_PLANKS, 48 * s1));
        Workforce.addToTreasury(village, new ItemStack(Items.OAK_LOG, 12 * s1));
        Workforce.addToTreasury(village, new ItemStack(Items.COBBLESTONE, 24 * s1));
        Workforce.addToTreasury(village, new ItemStack(Items.BREAD, 8 + 10 * stage));
        Workforce.addToTreasury(village, new ItemStack(Items.WHEAT_SEEDS, 8));
        Workforce.addToTreasury(village, new ItemStack(Items.WHITE_WOOL, 3 * s1));
        Workforce.addToTreasury(village, new ItemStack(Items.TORCH, 8 * s1));
        if (stage >= 2) Workforce.addToTreasury(village, new ItemStack(Items.IRON_INGOT, 6 * stage));

        settlers(world, village, trade, stage, r);
        Life.news(village, "founded." + trade.name().toLowerCase());
        village.markDirty();
        return village;
    }

    private static void raise(ServerWorld world, Village village, String type) {
        switch (type) {
            case "hut" -> Builder.raiseNow(world, village, "hut", 5, 5, 1, 0, 3);
            case "house" -> Builder.raiseNow(world, village, "house", 6 + world.random.nextInt(3), 7, 1, 0, 3);
            case "annex" -> Builder.raiseNow(world, village, "annex", 7, 7, 1, 1, 4);
            case "manor" -> Builder.raiseNow(world, village, "manor", 9, 8, 2, 0, 3);
            case "well" -> Builder.raiseNow(world, village, "well", 0, 0, 0, 0, 0);
            case "board" -> Builder.raiseNow(world, village, "board", 0, 0, 0, 0, 1);
            case "farm", "tower", "charcoal", "mill" -> Builder.raiseNow(world, village, type, 0, 0, 0, 2, 5);
            default -> Builder.raiseNow(world, village, type, 0, 0, 0, 0, 4);
        }
    }

    private static void settlers(ServerWorld world, Village village, Trade trade, int stage, Random r) {
        List<VillagerProfession> jobs = new ArrayList<>();
        jobs.add(VillagerProfession.CARTOGRAPHER);
        VillagerProfession main = switch (trade) {
            case LUMBER -> VillagerProfession.FLETCHER;
            case MINING -> VillagerProfession.MASON;
            case FARMING -> VillagerProfession.FARMER;
            case OUTPOST -> ProfessionsMCA.GUARD.get();
            case TRADING -> VillagerProfession.FLETCHER;
        };
        jobs.add(main);
        if (stage >= 1) jobs.add(main);
        if (trade != Trade.LUMBER) jobs.add(VillagerProfession.FLETCHER);
        if (stage >= 1 && trade != Trade.FARMING) jobs.add(VillagerProfession.FARMER);
        if (stage >= 1 && trade != Trade.MINING) jobs.add(VillagerProfession.MASON);
        for (int i = 0; i < stage; i++) jobs.add(ProfessionsMCA.GUARD.get());
        if (stage >= 2) jobs.add(VillagerProfession.CARTOGRAPHER);
        int total = new int[]{3, 5, 8, 12}[stage];
        while (jobs.size() < total) jobs.add(VillagerProfession.NONE);
        while (jobs.size() > total) jobs.remove(jobs.size() - 1);

        BlockPos fire = Camp.hearth(village, world);
        BlockPos around = fire != null ? fire : village.hallPos != null ? village.hallPos : new BlockPos(village.getCenter());
        for (VillagerProfession job : jobs) {
            Gender g = r.nextBoolean() ? Gender.MALE : Gender.FEMALE;
            BlockPos spot = spot(world, around, r);
            VillagerEntityMCA v = g.getVillagerType().spawn(world, spot, SpawnReason.EVENT);
            if (v == null) continue;
            v.setCustomName(Text.literal(Names.pickCitizenName(g)));
            if (job != VillagerProfession.NONE) Staffing.assign(world, v, job);
            v.getInventory().addStack(new ItemStack(Items.BREAD, 3));
        }
    }

    private static BlockPos spot(ServerWorld world, BlockPos around, Random r) {
        for (int i = 0; i < 20; i++) {
            int x = around.getX() + r.nextInt(9) - 4, z = around.getZ() + r.nextInt(9) - 4;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (world.getBlockState(p).isAir() && world.getBlockState(p.up()).isAir() && world.getFluidState(p.down()).isEmpty()) return p;
        }
        return around.up();
    }
}
