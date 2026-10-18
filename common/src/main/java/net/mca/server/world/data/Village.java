package net.mca.server.world.data;

import net.mca.Config;
import net.mca.entity.VillagerEntityMCA;
import net.mca.resources.API;
import net.mca.resources.BuildingTypes;
import net.mca.server.world.data.villageComponents.*;
import net.mca.util.BlockBoxExtended;
import net.mca.util.NbtHelper;
import net.mca.util.WorldUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Village implements Iterable<Building> {
    private static final int MOVE_IN_COOLDOWN = 1200;
    public static final int PLAYER_BORDER_MARGIN = 32;
    public static final int BORDER_MARGIN = 48;
    public static final int MERGE_MARGIN = 64;
    private static final long BED_SYNC_TIME = 200;

    private final ServerWorld world;

    private String name = API.getVillagePool().pickVillageName("village");

    public BlockPos buildOrigin;
    public int buildStep;
    public String buildType = "";
    public int buildW, buildD, buildF, buildRot;
    public boolean wellBuilt, wallBuilt;
    public int palisadeR;
    public net.mca.settlements.Tavern tavern;
    public BlockPos mineOrigin, smithyFurnace, smithyAnvil, hallPos, penCenter, stocksPos;
    /** What the village remembers (Settlements): the lumberjacks' grove, ore the miners have seen, how far its streets reach. */
    public final Set<BlockPos> grove = new HashSet<>(), knownOres = new HashSet<>();
    public int streets;
    public NbtCompound memories = new NbtCompound();
    public NbtCompound orderBook = new NbtCompound();
    public net.mca.settlements.House hall;
    public boolean seeded;
    /** When the village was last lived in (a player near enough for it to run); the days between are caught up (see Ledger). */
    public long lastLived;
    /** The village's books (see settlements.Ledger): the last day reckoned; the workers as last seen; the work and
     * the comings and goings owed to the world, to be shown when a player is near. */
    public long ledgerDay = -1;
    public int[] ledgerRoles = new int[0];
    public int owedSteps, owedNewcomers, owedLeavers;
    public transient int owedTries; // (not saved: times the owed work could not be laid yet)
    /** The village's coffers, in coins: wages are paid out of it, and what people pay for their bread goes back in. */
    public int coffers = 60;
    public int groveSaplings;
    public final List<BlockPos> hallChests = new LinkedList<>();
    public final List<BlockPos> boardSigns = new LinkedList<>();
    public int mineLevel = 1, mineStep;
    public final List<BlockPos> watchPoints = new LinkedList<>();
    /** Bottom cell of each tower's ladder, same order as {@link #watchPoints}. */
    public final List<BlockPos> towerLadders = new LinkedList<>();
    public int farms, towers;
    /** Reserved plots as {x, z, width, depth}, so the layout never overlaps its own buildings. */
    public final List<int[]> plots = new LinkedList<>();
    public boolean storageBuilt, mineBuilt, smithyBuilt, armoryBuilt;
    public final List<BlockPos> campfires = new LinkedList<>();
    public final Map<Integer, Integer> relations = new HashMap<>();
    /** What this village owes its neighbours for goods bought on credit: creditor id -> {nuggets, day the debt was made}. */
    public final Map<Integer, long[]> debts = new HashMap<>();
    public final java.util.Set<BlockPos> lootChests = new java.util.HashSet<>();
    /** Houses a household has paid to have rebuilt better (origin of the old house -> the one who paid), see settlements.Home. */
    public final java.util.Map<Long, java.util.UUID> commissions = new java.util.LinkedHashMap<>();
    /** New houses promised to whoever paid for them (a spot inside the new house -> that villager). */
    public final java.util.Map<Long, java.util.UUID> reserved = new java.util.HashMap<>();
    /** A paid-for house that would not fit where the old one stands: to be built on a new site (the one who paid). */
    public java.util.UUID commissionElsewhere = null;
    /** How the village knows each player: honest, peaceable, open-handed (see settlements.Fame). */
    public final java.util.Map<java.util.UUID, int[]> fame = new java.util.HashMap<>();
    /** Chests a player put down himself inside the village: his, not the villagers'. */
    public final java.util.Set<BlockPos> playerChests = new java.util.HashSet<>();
    public final List<net.mca.settlements.House> houses = new LinkedList<>();
    public final List<net.mca.settlements.Built> built = new java.util.ArrayList<>();
    public boolean buildRepair;
    public final List<ItemStack> storageBuffer = new LinkedList<>();

    private final Map<Integer, Building> buildings = new HashMap<>();
    private Map<UUID, Integer> unspentHearts = new HashMap<>();
    private Map<UUID, Map<UUID, Integer>> reputation = new HashMap<>();
    private int unspentMood = 0;

    private int beds;
    private long lastBedSync;

    private Map<UUID, String> residentNames = new HashMap<>();
    private Map<UUID, Long> residentHomes = new HashMap<>();

    public long lastMoveIn;
    private final int id;

    private float taxes = 0;
    private float populationThreshold = 0.75f;
    private float marriageThreshold = 0.5f;

    private boolean autoScan = Config.getInstance().enableAutoScanByDefault;

    private BlockBoxExtended box = new BlockBoxExtended(0, 0, 0, 0, 0, 0);

    private final VillageGuardsManager villageGuardsManager = new VillageGuardsManager(this);
    private final VillageInnManager villageInnManager = new VillageInnManager(this);
    private final VillageMarriageManager villageMarriageManager = new VillageMarriageManager(this);
    private final VillageProcreationManager villageProcreationManager = new VillageProcreationManager(this);
    private final VillageTaxesManager villageTaxesManager = new VillageTaxesManager(this);

    public ServerWorld getWorld() {
        return world;
    }

    public Village(int id, ServerWorld world) {
        this.id = id;

        this.world = world;
    }

    public Village(NbtCompound v, ServerWorld world) {
        id = v.getInt("id");
        name = v.getString("name");
        taxes = v.getFloat("taxesFloat");
        beds = v.getInt("beds");
        unspentHearts = NbtHelper.toMap(v.getCompound("unspentHearts"), UUID::fromString, i -> ((NbtInt) i).intValue());
        reputation = NbtHelper.toMap(v.getCompound("reputation"), UUID::fromString, i ->
                NbtHelper.toMap((NbtCompound) i, UUID::fromString, i2 -> ((NbtInt) i2).intValue())
        );
        residentNames = NbtHelper.toMap(v.getCompound("residentNames"), UUID::fromString, NbtElement::asString);
        residentHomes = NbtHelper.toMap(v.getCompound("residentHomes"), UUID::fromString, i -> ((NbtLong) i).longValue());
        unspentMood = v.getInt("unspentMood");
        if (v.contains("buildOrigin")) {
            buildOrigin = BlockPos.fromLong(v.getLong("buildOrigin"));
            buildStep = v.getInt("buildStep");
            buildType = v.getString("buildType");
            buildW = v.getInt("buildW");
            buildD = v.getInt("buildD");
            buildF = v.getInt("buildF");
            buildRot = v.getInt("buildRot");
        }
        storageBuilt = v.getBoolean("storageBuilt");
        mineBuilt = v.getBoolean("mineBuilt");
        if (v.contains("mineOrigin")) mineOrigin = BlockPos.fromLong(v.getLong("mineOrigin"));
        if (v.contains("hallPos")) hallPos = BlockPos.fromLong(v.getLong("hallPos"));
        if (v.contains("stocksPos")) stocksPos = BlockPos.fromLong(v.getLong("stocksPos"));
        if (v.contains("hall")) hall = net.mca.settlements.House.load(v.getCompound("hall"));
        if (v.contains("penCenter")) penCenter = BlockPos.fromLong(v.getLong("penCenter"));
        seeded = v.getBoolean("seeded");
        lastLived = v.getLong("lastLived");
        ledgerDay = v.contains("ledgerDay") ? v.getLong("ledgerDay") : -1;
        ledgerRoles = v.getIntArray("ledgerRoles");
        owedSteps = v.getInt("owedSteps");
        owedNewcomers = v.getInt("owedNewcomers");
        owedLeavers = v.getInt("owedLeavers");
        if (v.contains("coffers")) coffers = v.getInt("coffers");
        groveSaplings = v.getInt("groveSaplings");
        for (long l : v.getLongArray("hallChests")) hallChests.add(BlockPos.fromLong(l));
        for (long l : v.getLongArray("boardSigns")) boardSigns.add(BlockPos.fromLong(l));
        if (v.contains("smithyFurnace")) smithyFurnace = BlockPos.fromLong(v.getLong("smithyFurnace"));
        if (v.contains("smithyAnvil")) smithyAnvil = BlockPos.fromLong(v.getLong("smithyAnvil"));
        mineLevel = v.contains("mineLevel") ? Math.max(1, v.getInt("mineLevel")) : 1;
        mineStep = v.getInt("mineStep");
        for (long l : v.getLongArray("watchPoints")) watchPoints.add(BlockPos.fromLong(l));
        for (long l : v.getLongArray("towerLadders")) towerLadders.add(BlockPos.fromLong(l));
        if (v.contains("tavern")) tavern = net.mca.settlements.Tavern.load(v.getCompound("tavern"));
        wellBuilt = v.getBoolean("wellBuilt");
        wallBuilt = v.getBoolean("wallBuilt");
        palisadeR = v.getInt("palisadeR");
        farms = v.getInt("farms");
        for (long l : v.getLongArray("grove")) grove.add(BlockPos.fromLong(l));
        for (long l : v.getLongArray("knownOres")) knownOres.add(BlockPos.fromLong(l));
        streets = v.getInt("streets");
        memories = v.getCompound("memories");
        orderBook = v.getCompound("orderBook");
        towers = v.getInt("towers");
        int[] flatPlots = v.getIntArray("plots");
        for (int i = 0; i + 3 < flatPlots.length; i += 4) plots.add(new int[]{flatPlots[i], flatPlots[i + 1], flatPlots[i + 2], flatPlots[i + 3]});
        smithyBuilt = v.getBoolean("smithyBuilt");
        armoryBuilt = v.getBoolean("armoryBuilt");
        for (long l : v.getLongArray("campfires")) campfires.add(BlockPos.fromLong(l));
        NbtCompound relNbt = v.getCompound("relations");
        for (String k : relNbt.getKeys()) relations.put(Integer.parseInt(k), relNbt.getInt(k));
        NbtCompound debtNbt = v.getCompound("debts");
        for (String k : debtNbt.getKeys()) debts.put(Integer.parseInt(k), debtNbt.getLongArray(k));
        for (long l : v.getLongArray("lootChests")) lootChests.add(BlockPos.fromLong(l));
        NbtCompound comNbt = v.getCompound("commissions");
        for (String k : comNbt.getKeys()) commissions.put(Long.parseLong(k), comNbt.getUuid(k));
        NbtCompound fameNbt = v.getCompound("fame");
        for (String k : fameNbt.getKeys()) fame.put(java.util.UUID.fromString(k), fameNbt.getIntArray(k));
        commissionElsewhere = v.containsUuid("commissionElsewhere") ? v.getUuid("commissionElsewhere") : null;
        NbtCompound resNbt = v.getCompound("reserved");
        for (String k : resNbt.getKeys()) reserved.put(Long.parseLong(k), resNbt.getUuid(k));
        for (long l : v.getLongArray("playerChests")) playerChests.add(BlockPos.fromLong(l));
        NbtList builtList = v.getList("built", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < builtList.size(); i++) built.add(net.mca.settlements.Built.load(builtList.getCompound(i)));
        buildRepair = v.getBoolean("buildRepair");
        NbtList houseList = v.getList("houses", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < houseList.size(); i++) houses.add(net.mca.settlements.House.load(houseList.getCompound(i)));
        NbtList treasury = v.getList("treasury", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < treasury.size(); i++) {
            net.minecraft.item.ItemStack st = net.minecraft.item.ItemStack.fromNbt(treasury.getCompound(i));
            if (!st.isEmpty()) storageBuffer.add(st);
        }

        if (v.contains("populationThresholdFloat")) {
            populationThreshold = v.getFloat("populationThresholdFloat");
        }
        if (v.contains("marriageThresholdFloat")) {
            marriageThreshold = v.getFloat("marriageThresholdFloat");
        }
        this.world = world;

        if (v.contains("autoScan")) {
            autoScan = v.getBoolean("autoScan");
        } else {
            autoScan = true;
        }

        NbtList b = v.getList("buildings", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < b.size(); i++) {
            Building building = new Building(b.getCompound(i));

            if (world == null || BuildingTypes.getInstance().getBuildingTypes().containsKey(building.getType())) {
                buildings.put(building.getId(), building);
            }
        }

        if (!buildings.isEmpty()) {
            calculateDimensions();
        }
    }

    public static Optional<Village> findNearest(Entity entity) {
        return VillageManager.get((ServerWorld) entity.getWorld()).findNearestVillage(entity);
    }

    public boolean isWithinBorder(Entity entity) {
        return isWithinBorder(entity.getBlockPos(), entity instanceof PlayerEntity ? PLAYER_BORDER_MARGIN : BORDER_MARGIN);
    }

    public boolean isWithinBorder(BlockPos pos, int margin) {
        return box.expand(margin).contains(pos);
    }

    @Override
    public Iterator<Building> iterator() {
        return buildings.values().iterator();
    }

    public void removeBuilding(int id) {
        buildings.remove(id);
        if (!buildings.isEmpty()) {
            calculateDimensions();
        }
        markDirty();
    }

    public Stream<Building> getBuildingsOfType(String type) {
        return getBuildings().values().stream().filter(b -> b.getType().equals(type));
    }

    public Optional<Building> getBuildingAt(Vec3i pos) {
        return getBuildings().values().stream().filter(b -> b.containsPos(pos)).findAny();
    }

    public void calculateDimensions() {
        int sx = Integer.MAX_VALUE;
        int sy = Integer.MAX_VALUE;
        int sz = Integer.MAX_VALUE;
        int ex = Integer.MIN_VALUE;
        int ey = Integer.MIN_VALUE;
        int ez = Integer.MIN_VALUE;

        for (Building building : buildings.values()) {
            ex = Math.max(building.getPos1().getX(), ex);
            sx = Math.min(building.getPos0().getX(), sx);

            ey = Math.max(building.getPos1().getY(), ey);
            sy = Math.min(building.getPos0().getY(), sy);

            ez = Math.max(building.getPos1().getZ(), ez);
            sz = Math.min(building.getPos0().getZ(), sz);
        }

        box = new BlockBoxExtended(sx, sy, sz, ex, ey, ez);
    }

    public Vec3i getCenter() {
        return box.getCenter();
    }

    public BlockBoxExtended getBox() {
        return box;
    }

    public List<String> getResidents(int building) {
        return getBuilding(building).map(value -> residentHomes.entrySet().stream().filter(e -> {
            return value.containsPos(BlockPos.fromLong(e.getValue()));
        }).map(k -> residentNames.getOrDefault(k.getKey(), "Unknown")).collect(Collectors.toList())).orElseGet(List::of);
    }

    public float getTaxes() {
        return taxes;
    }

    public void setTaxes(float taxes) {
        this.taxes = taxes;
    }

    public float getPopulationThreshold() {
        return populationThreshold;
    }

    public void setPopulationThreshold(float populationThreshold) {
        this.populationThreshold = populationThreshold;
    }

    public float getMarriageThreshold() {
        return marriageThreshold;
    }

    public void setMarriageThreshold(float marriageThreshold) {
        this.marriageThreshold = marriageThreshold;
    }

    public boolean isAutoScan() {
        return autoScan;
    }

    public void setAutoScan(boolean autoScan) {
        this.autoScan = autoScan;
    }

    public void toggleAutoScan() {
        setAutoScan(!isAutoScan());
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<Integer, Building> getBuildings() {
        return buildings;
    }

    public Optional<Building> getBuilding(int id) {
        return Optional.ofNullable(buildings.get(id));
    }

    public int getId() {
        return id;
    }

    public boolean hasSpace() {
        return getPopulation() < getMaxPopulation();
    }

    public int getPopulation() {
        return residentNames.size();
    }

    public Stream<UUID> getResidentsUUIDs() {
        return residentNames.keySet().stream();
    }

    // verify that this bed is not blocked
    public boolean isPositionValidBed(BlockPos pos) {
        return getBuildingAt(pos).filter(b -> b.getBuildingType().noBeds()).isEmpty();
    }

    public List<VillagerEntityMCA> getResidents(ServerWorld world) {
        return getResidentsUUIDs()
                .map(world::getEntity)
                .filter(VillagerEntityMCA.class::isInstance)
                .map(VillagerEntityMCA.class::cast)
                .collect(Collectors.toList());
    }

    public void updateMaxPopulation() {
        if (world != null) {
            Vec3i dimensions = box.getDimensions();
            int radius = (int) Math.sqrt(dimensions.getX() * dimensions.getX() + dimensions.getY() * dimensions.getY() + dimensions.getZ() * dimensions.getZ());
            beds = (int) world.getPointOfInterestStorage().getPositions(
                    registryEntry -> registryEntry.matchesKey(PointOfInterestTypes.HOME),
                    this::isPositionValidBed,
                    new BlockPos(getCenter()),
                    radius + BORDER_MARGIN,
                    PointOfInterestStorage.OccupationStatus.ANY).count();
        }
    }

    public int getMaxPopulation() {
        if (world != null && world.getTime() - lastBedSync > BED_SYNC_TIME) {
            lastBedSync = world.getTime();
            updateMaxPopulation();
        }
        return beds;
    }

    public boolean hasStoredResource() {
        return !storageBuffer.isEmpty();
    }

    public boolean hasBuilding(String building) {
        return buildings.values().stream().anyMatch(b -> b.getType().equals(building) && b.isComplete());
    }

    public void tick(ServerWorld world, long time) {
        // spread performance to avoid lag spikes
        time += getId();

        boolean isTaxSeason = time % Config.getInstance().taxSeason == 0;
        boolean isVillageUpdateTime = time % MOVE_IN_COOLDOWN == 0;

        if (isTaxSeason && hasBuilding("storage")) {
            villageTaxesManager.taxes(world);
        }

        if (time % 24000 == 0) {
            cleanReputation();
        }

        if (isVillageUpdateTime && lastMoveIn + MOVE_IN_COOLDOWN < time && WorldUtils.isChunkLoaded(world, getCenter())) {
            villageGuardsManager.spawnGuards(world);
            villageInnManager.updateInn(world);
            villageMarriageManager.marry(world);
            villageProcreationManager.procreate(world);
        }
    }

    public void onEnter(ServerWorld world) {
        villageTaxesManager.deliverTaxes(world);
    }

    public void broadCastMessage(ServerWorld world, String event, VillagerEntityMCA suitor, VillagerEntityMCA mate) {
        world.getPlayers().stream().filter(p -> PlayerSaveData.get(p).getLastSeenVillageId().orElse(-2) == getId()
                                                || suitor.getVillagerBrain().getMemoriesForPlayer(p).getHearts() > Config.getInstance().heartsToBeConsideredAsFriend
                                                || mate.getVillagerBrain().getMemoriesForPlayer(p).getHearts() > Config.getInstance().heartsToBeConsideredAsFriend)
                .forEach(player -> player.sendMessage(Text.translatable(event, suitor.getName(), mate.getName()), !Config.getInstance().showNotificationsAsChat));
    }

    public void broadCastMessage(ServerWorld world, String event, String targetName) {
        world.getPlayers().stream().filter(p -> PlayerSaveData.get(p).getLastSeenVillageId().orElse(-2) == getId())
                .forEach(player -> player.sendMessage(Text.translatable(event, targetName), !Config.getInstance().showNotificationsAsChat));
    }

    public void markDirty() {
        VillageManager.get(world).markDirty();
    }

    // removes all villagers no longer living here
    public void cleanReputation() {
        Set<UUID> residents = getResidentsUUIDs().collect(Collectors.toSet());
        for (Map<UUID, Integer> map : reputation.values()) {
            Set<UUID> toRemove = map.keySet().stream().filter(v -> !residents.contains(v)).collect(Collectors.toSet());
            for (UUID uuid : toRemove) {
                map.remove(uuid);
            }
        }
    }

    public void setReputation(PlayerEntity player, VillagerEntityMCA villager, int rep) {
        reputation.computeIfAbsent(player.getUuid(), i -> new HashMap<>()).put(villager.getUuid(), rep);
        markDirty();
    }

    public int getReputation(PlayerEntity player) {
        return reputation.getOrDefault(player.getUuid(), Collections.emptyMap()).values().stream().mapToInt(i -> i).sum()
               + unspentHearts.getOrDefault(player.getUuid(), 0);
    }

    public void resetHearts(PlayerEntity player) {
        unspentHearts.remove(player.getUuid());
        markDirty();
    }

    public void pushHearts(PlayerEntity player, int rep) {
        pushHearts(player.getUuid(), rep);
        markDirty();
    }

    public void pushHearts(UUID player, int rep) {
        rep = Math.max(rep, -30); // Settlements: one wrong, however big, is one wrong - not a lifelong feud (time mends it, see mend)
        unspentHearts.put(player, unspentHearts.getOrDefault(player, 0) + rep);
        markDirty();
    }

    /** Settlements, once a village day: a grudge against a player wears off a little (a twelfth, at least two). */
    public void mend() {
        java.util.Set<UUID> players = new java.util.HashSet<>(unspentHearts.keySet());
        players.addAll(reputation.keySet());
        for (UUID p : players) {
            int total = reputation.getOrDefault(p, Collections.emptyMap()).values().stream().mapToInt(i -> i).sum() + unspentHearts.getOrDefault(p, 0);
            if (total < 0) unspentHearts.put(p, unspentHearts.getOrDefault(p, 0) + Math.min(-total, Math.max(2, -total / 12)));
        }
        markDirty();
    }

    public int popHearts(PlayerEntity player) {
        int v = unspentHearts.getOrDefault(player.getUuid(), 0);
        int step = (int) Math.ceil(Math.abs(((double) v) / getPopulation()));
        if (v > 0) {
            v -= step;
            if (v == 0) {
                unspentHearts.remove(player.getUuid());
            } else {
                unspentHearts.put(player.getUuid(), v);
            }
            markDirty();
            return step;
        } else if (v < 0) {
            v += step;
            if (v == 0) {
                unspentHearts.remove(player.getUuid());
            } else {
                unspentHearts.put(player.getUuid(), v);
            }
            markDirty();
            return -step;
        } else {
            return 0;
        }
    }

    public void pushMood(int m) {
        unspentMood += m;
        markDirty();
    }

    public int popMood() {
        int step = (int) Math.ceil(Math.abs(((double) unspentMood) / getPopulation()));
        if (unspentMood > 0) {
            unspentMood -= step;
            markDirty();
            return step;
        } else if (unspentMood < 0) {
            unspentMood += step;
            markDirty();
            return -step;
        } else {
            return 0;
        }
    }

    public NbtCompound save() {
        NbtCompound v = new NbtCompound();
        v.putInt("id", id);
        v.putString("name", name);
        v.putFloat("taxesFloat", taxes);
        v.putInt("beds", beds);
        NbtList treasury = new NbtList();
        for (net.minecraft.item.ItemStack st : storageBuffer) {
            if (!st.isEmpty()) treasury.add(st.writeNbt(new NbtCompound()));
        }
        v.put("treasury", treasury);
        if (buildOrigin != null) {
            v.putLong("buildOrigin", buildOrigin.asLong());
            v.putInt("buildStep", buildStep);
            v.putString("buildType", buildType);
            v.putInt("buildW", buildW);
            v.putInt("buildD", buildD);
            v.putInt("buildF", buildF);
            v.putInt("buildRot", buildRot);
        }
        v.putBoolean("storageBuilt", storageBuilt);
        v.putBoolean("mineBuilt", mineBuilt);
        if (tavern != null) v.put("tavern", tavern.save());
        if (mineOrigin != null) v.putLong("mineOrigin", mineOrigin.asLong());
        if (hallPos != null) v.putLong("hallPos", hallPos.asLong());
        if (stocksPos != null) v.putLong("stocksPos", stocksPos.asLong());
        if (hall != null) v.put("hall", hall.save());
        if (penCenter != null) v.putLong("penCenter", penCenter.asLong());
        v.putBoolean("seeded", seeded);
        v.putLong("lastLived", lastLived);
        v.putLong("ledgerDay", ledgerDay);
        v.putIntArray("ledgerRoles", ledgerRoles);
        v.putInt("owedSteps", owedSteps);
        v.putInt("owedNewcomers", owedNewcomers);
        v.putInt("owedLeavers", owedLeavers);
        v.putInt("coffers", coffers);
        v.putInt("groveSaplings", groveSaplings);
        v.putLongArray("hallChests", hallChests.stream().mapToLong(BlockPos::asLong).toArray());
        v.putLongArray("boardSigns", boardSigns.stream().mapToLong(BlockPos::asLong).toArray());
        if (smithyFurnace != null) v.putLong("smithyFurnace", smithyFurnace.asLong());
        if (smithyAnvil != null) v.putLong("smithyAnvil", smithyAnvil.asLong());
        v.putInt("mineLevel", mineLevel);
        v.putInt("mineStep", mineStep);
        v.putLongArray("watchPoints", watchPoints.stream().mapToLong(BlockPos::asLong).toArray());
        v.putLongArray("towerLadders", towerLadders.stream().mapToLong(BlockPos::asLong).toArray());
        v.putBoolean("wellBuilt", wellBuilt);
        v.putBoolean("wallBuilt", wallBuilt);
        v.putInt("palisadeR", palisadeR);
        v.putInt("farms", farms);
        v.putLongArray("grove", grove.stream().mapToLong(BlockPos::asLong).toArray());
        v.putLongArray("knownOres", knownOres.stream().mapToLong(BlockPos::asLong).toArray());
        v.putInt("streets", streets);
        v.put("memories", memories);
        v.put("orderBook", orderBook);
        v.putInt("towers", towers);
        int[] flat = new int[plots.size() * 4];
        for (int i = 0; i < plots.size(); i++) System.arraycopy(plots.get(i), 0, flat, i * 4, 4);
        v.putIntArray("plots", flat);
        v.putBoolean("smithyBuilt", smithyBuilt);
        v.putBoolean("armoryBuilt", armoryBuilt);
        NbtCompound rel = new NbtCompound();
        relations.forEach((k, val) -> rel.putInt(String.valueOf(k), val));
        NbtCompound debtOut = new NbtCompound();
        debts.forEach((k, val) -> debtOut.putLongArray(String.valueOf(k), val));
        v.put("debts", debtOut);
        v.put("relations", rel);
        v.putLongArray("lootChests", lootChests.stream().mapToLong(BlockPos::asLong).toArray());
        NbtCompound com = new NbtCompound();
        commissions.forEach((k, u) -> com.putUuid(String.valueOf(k), u));
        v.put("commissions", com);
        NbtCompound fm = new NbtCompound();
        fame.forEach((k, a) -> fm.putIntArray(k.toString(), a));
        v.put("fame", fm);
        if (commissionElsewhere != null) v.putUuid("commissionElsewhere", commissionElsewhere);
        NbtCompound res = new NbtCompound();
        reserved.forEach((k, u) -> res.putUuid(String.valueOf(k), u));
        v.put("reserved", res);
        v.putLongArray("playerChests", playerChests.stream().mapToLong(BlockPos::asLong).toArray());
        v.putLongArray("campfires", campfires.stream().mapToLong(BlockPos::asLong).toArray());
        NbtList houseList = new NbtList();
        for (net.mca.settlements.House h : houses) houseList.add(h.save());
        v.put("houses", houseList);
        NbtList builtOut = new NbtList();
        for (net.mca.settlements.Built b : built) builtOut.add(b.save());
        v.put("built", builtOut);
        v.putBoolean("buildRepair", buildRepair);
        v.put("unspentHearts", NbtHelper.fromMap(new NbtCompound(), unspentHearts, UUID::toString, NbtInt::of));
        v.put("reputation", NbtHelper.fromMap(new NbtCompound(), reputation, UUID::toString, i ->
                NbtHelper.fromMap(new NbtCompound(), i, UUID::toString, NbtInt::of)
        ));
        v.put("residentNames", NbtHelper.fromMap(new NbtCompound(), residentNames, Object::toString, NbtString::of));
        v.put("residentHomes", NbtHelper.fromMap(new NbtCompound(), residentHomes, Object::toString, NbtLong::of));
        v.putInt("unspentMood", unspentMood);
        v.putFloat("populationThresholdFloat", populationThreshold);
        v.putFloat("marriageThresholdFloat", marriageThreshold);
        v.put("buildings", NbtHelper.fromList(buildings.values(), Building::save));
        v.putBoolean("autoScan", autoScan);
        return v;
    }

    public void merge(Village village) {
        buildings.putAll(village.buildings);
        unspentMood += village.unspentMood;
        calculateDimensions();
    }

    public boolean isVillage() {
        return getBuildings().size() >= Config.getInstance().minimumBuildingsToBeConsideredAVillage;
    }

    public void updateResident(VillagerEntityMCA e) {
        residentNames.put(e.getUuid(), e.getName().getString());

        Optional<GlobalPos> home = e.getResidency().getHome();
        if (home.isPresent()) {
            residentHomes.put(e.getUuid(), home.get().getPos().asLong());
        } else {
            residentHomes.remove(e.getUuid());
        }
    }

    public Map<UUID, String> getResidentNames() {
        return residentNames;
    }

    public boolean hasResident(UUID id) {
        return residentNames.containsKey(id);
    }

    public void removeResident(VillagerEntityMCA villager) {
        removeResident(villager.getUuid());
    }

    public void removeResident(UUID uuid) {
        residentNames.remove(uuid);
        residentHomes.remove(uuid);
        cleanReputation();
        markDirty();
    }

    public VillageGuardsManager getVillageGuardsManager() {
        return villageGuardsManager;
    }

    public Optional<CivilRegistryManager> getCivilRegistry() {
        return world != null ? Optional.of(CivilRegistryManager.get(world, this)) : Optional.empty();
    }
}
