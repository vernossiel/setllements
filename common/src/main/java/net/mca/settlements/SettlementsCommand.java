package net.mca.settlements;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Optional;

/** Debug and test commands, operators only: /settlements status | rich | give | fast | staff | daily | cancel | relation. */
public final class SettlementsCommand {
    /** Testing: the server ticks as fast as it can (see MixinMinecraftServerWarp). */
    public static volatile boolean WARP = Boolean.getBoolean("settlements.warp");

    private SettlementsCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        // player-facing answers to a guard's demand, opened by the clickable buttons in the chat
        d.register(CommandManager.literal("settle")
                .then(CommandManager.literal("return").executes(c -> Reputation.returnGoods(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("pay").executes(c -> Justice.pay(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("deny").executes(c -> Justice.deny(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("leave").executes(c -> Justice.leave(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("surrender").executes(c -> Justice.surrender(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("resist").executes(c -> Justice.resist(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("plead").executes(c -> Justice.plead(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("rogue")
                        .then(CommandManager.literal("accept").executes(c -> Rogue.answer(c.getSource().getPlayerOrThrow(), true)))
                        .then(CommandManager.literal("refuse").executes(c -> Rogue.answer(c.getSource().getPlayerOrThrow(), false))))
                .then(CommandManager.literal("bribe").executes(c -> Justice.bribe(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("rep").executes(c -> Reputation.showStanding(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("why").executes(c -> Doing.toggle(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("elder").then(CommandManager.argument("topic", StringArgumentType.word())
                        .executes(c -> Elder.ask(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "topic")))))
                .then(CommandManager.literal("hall").executes(c -> {
                    var player = c.getSource().getPlayerOrThrow();
                    var vil = net.mca.server.world.data.Village.findNearest(player).orElse(null);
                    if (vil == null || vil.hallPos == null) {
                        c.getSource().sendError(net.minecraft.text.Text.translatable("settlements.hall.none"));
                        return 0;
                    }
                    var h = vil.hallPos;
                    double dx = h.getX() + 0.5 - player.getX(), dz = h.getZ() + 0.5 - player.getZ();
                    String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
                    c.getSource().sendFeedback(() -> net.minecraft.text.Text.translatable("settlements.hall.where", vil.getName(), h.getX(), h.getY(), h.getZ(),
                            (int) Math.sqrt(dx * dx + dz * dz), net.minecraft.text.Text.translatable("settlements.dir." + dir)), false);
                    return 1;
                }))
                .then(CommandManager.literal("plans").executes(c -> {
                    var player = c.getSource().getPlayerOrThrow();
                    var near = player.getWorld().getEntitiesByClass(net.mca.entity.VillagerEntityMCA.class, player.getBoundingBox().expand(64), x -> x.isAlive() && !x.isBaby());
                    if (near.isEmpty()) {
                        c.getSource().sendError(net.minecraft.text.Text.translatable("settlements.plan.unregistered"));
                        return 0;
                    }
                    near.sort(java.util.Comparator.comparingDouble(x -> x.squaredDistanceTo(player)));
                    var vil = net.mca.server.world.data.Village.findNearest(near.get(0)).orElse(null);
                    var elder = vil == null ? null : Plan.elder((net.minecraft.server.world.ServerWorld) player.getWorld(), vil);
                    Elder.open(elder != null ? elder : near.get(0), player);
                    return 1;
                }))
                .then(CommandManager.literal("refuse").executes(c -> Reputation.refuse(c.getSource().getPlayerOrThrow()))));
        d.register(CommandManager.literal("settlements").requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.literal("status").executes(SettlementsCommand::status))
                .then(CommandManager.literal("dump").executes(SettlementsCommand::dump))
                .then(CommandManager.literal("pen").executes(c -> { // testing: a pen here, the village's own
                    Optional<Village> opt = village(c);
                    if (opt.isEmpty()) return 0;
                    var at = net.minecraft.util.math.BlockPos.ofFloored(c.getSource().getPosition());
                    var o = at.add(-Blueprints.PEN / 2, 0, -Blueprints.PEN / 2);
                    Builder.placeForLook(c.getSource().getWorld(), "pen", o, 0);
                    opt.get().penCenter = o.add(Blueprints.PEN / 2, 0, Blueprints.PEN / 2);
                    opt.get().markDirty();
                    return say(c, "Pen at " + opt.get().penCenter.toShortString());
                }))
                .then(CommandManager.literal("fake")
                        .then(CommandManager.literal("spawn").executes(SettlementsCommand::fakeSpawn))
                        .then(CommandManager.literal("hit").then(CommandManager.argument("name", StringArgumentType.word()).executes(SettlementsCommand::fakeHit)))
                        .then(CommandManager.literal("remove").executes(SettlementsCommand::fakeRemove)))
                .then(CommandManager.literal("rich").executes(SettlementsCommand::rich))
                .then(CommandManager.literal("warp").requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("on").executes(c -> { WARP = true; c.getSource().sendFeedback(() -> Text.literal("warp on"), false); return 1; }))
                        .then(CommandManager.literal("off").executes(c -> { WARP = false; c.getSource().sendFeedback(() -> Text.literal("warp off"), false); return 1; })))
                .then(CommandManager.literal("give")
                        .then(CommandManager.argument("item", StringArgumentType.string())
                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 4096)).executes(SettlementsCommand::give))))
                .then(CommandManager.literal("place").then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(Builder.SHOWCASE.keySet(), b))
                        .executes(c -> {
                            var player = c.getSource().getPlayerOrThrow();
                            String type = StringArgumentType.getString(c, "type");
                            if (!Builder.SHOWCASE.containsKey(type)) return say(c, "Unknown building. Try: " + String.join(", ", Builder.SHOWCASE.keySet()));
                            // in front of the player, door towards him
                            var facing = player.getHorizontalFacing();
                            int rot = switch (facing) {
                                case NORTH -> 0;
                                case SOUTH -> 2;
                                case EAST -> 1;
                                default -> 3;
                            };
                            int[] f = Builder.footprintOf(type, rot);
                            int px = player.getBlockX(), pz = player.getBlockZ(), y = player.getBlockY();
                            int x0, z0;
                            switch (facing) {
                                case NORTH -> { x0 = px - f[0] / 2; z0 = pz - 4 - f[1]; }
                                case SOUTH -> { x0 = px - f[0] / 2; z0 = pz + 5; }
                                case EAST -> { x0 = px + 5; z0 = pz - f[1] / 2; }
                                default -> { x0 = px - 4 - f[0]; z0 = pz - f[1] / 2; }
                            }
                            Builder.placeForLook((ServerWorld) player.getWorld(), type, new net.minecraft.util.math.BlockPos(x0, y, z0), rot);
                            return say(c, "Placed " + type + ".");
                        })))
                .then(CommandManager.literal("showcase")
                        .then(CommandManager.literal("tier").then(CommandManager.argument("tier", StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(java.util.List.of(Works.TIER_NAMES), b))
                                .executes(c -> {
                                    String t = StringArgumentType.getString(c, "tier");
                                    int tier = java.util.List.of(Works.TIER_NAMES).indexOf(t);
                                    if (tier < 0) return say(c, "Tiers: poor, ordinary, wealthy, city");
                                    java.util.List<String> list = new java.util.ArrayList<>();
                                    for (String kind : Tiers.kinds()) for (String design : Tiers.row(kind, tier)) if (!list.contains(design)) list.add(design);
                                    return placeRow(c, list, "Every building of a " + t + " village");
                                })))
                        .then(CommandManager.literal("kind").then(CommandManager.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(Tiers.kinds(), b))
                                .executes(c -> {
                                    String kind = StringArgumentType.getString(c, "kind");
                                    if (!Tiers.kinds().contains(kind)) return say(c, "Kinds: " + String.join(", ", Tiers.kinds()));
                                    java.util.List<String> list = new java.util.ArrayList<>();
                                    for (int tier = 0; tier <= Tiers.CITY; tier++) for (String design : Tiers.row(kind, tier)) if (!list.contains(design)) list.add(design);
                                    return placeRow(c, list, kind + " from the poorest to the city's");
                                })))
                        .executes(c -> {
                    var player = c.getSource().getPlayerOrThrow();
                    int x = player.getBlockX() + 4, y = player.getBlockY(), z = player.getBlockZ() + 6;
                    for (String type : Builder.SHOWCASE.keySet()) {
                        int[] f = Builder.footprintOf(type, 0);
                        Builder.placeForLook((ServerWorld) player.getWorld(), type, new net.minecraft.util.math.BlockPos(x, y, z), 0);
                        x += f[0] + 6;
                    }
                    return say(c, "All buildings placed in a row going east, a few blocks south of you; their doors face south.");
                }))
                .then(CommandManager.literal("found").then(CommandManager.argument("stage", IntegerArgumentType.integer(0, 3)).executes(c -> {
                    var player = c.getSource().getPlayerOrThrow();
                    int stage = IntegerArgumentType.getInteger(c, "stage");
                    var village = Sites.settle((net.minecraft.server.world.ServerWorld) player.getWorld(), player.getBlockX() + 24, player.getBlockZ(), stage, null, new java.util.Random());
                    return say(c, village == null ? "No settlement here (water, unsuitable land or MCA refused the bell)." : "Founded " + village.getName() + ", stage " + stage + ".");
                })))
                .then(CommandManager.literal("fast").then(CommandManager.argument("ops", IntegerArgumentType.integer(1, 200)).executes(c -> {
                    Builder.opsPerSecond = IntegerArgumentType.getInteger(c, "ops");
                    return say(c, "Builder and miner speed: " + Builder.opsPerSecond + " blocks per second (default 2)");
                })))
                .then(CommandManager.literal("scan").executes(c -> {
                    int n = Registration.reportAround(c.getSource().getWorld(), c.getSource().getPlayerOrThrow().getBlockPos(), 64);
                    return say(c, "Handed " + n + " beds to the village scanner. Wait a few seconds, then try /settlements status.");
                }))
                .then(CommandManager.literal("staff").executes(c -> {
                    Staffing.tick(c.getSource().getWorld());
                    return say(c, "Jobs handed out.");
                }))
                .then(CommandManager.literal("daily").executes(c -> {
                    ServerWorld w = c.getSource().getWorld();
                    Diplomacy.daily(w);
                    Expansion.daily(w);
                    return say(c, "Daily diplomacy and expansion step done.");
                }))
                .then(CommandManager.literal("cancel").executes(c -> village(c).map(v -> {
                    v.buildOrigin = null;
                    v.buildStep = 0;
                    v.markDirty();
                    return say(c, "Current project cancelled.");
                }).orElse(0)))
                .then(CommandManager.literal("relation")
                        .then(CommandManager.argument("idA", IntegerArgumentType.integer())
                                .then(CommandManager.argument("idB", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("value", IntegerArgumentType.integer(-100, 100)).executes(c -> {
                                            VillageManager vm = VillageManager.get(c.getSource().getWorld());
                                            var a = vm.getOrEmpty(IntegerArgumentType.getInteger(c, "idA"));
                                            var b = vm.getOrEmpty(IntegerArgumentType.getInteger(c, "idB"));
                                            if (a.isEmpty() || b.isEmpty()) return say(c, "No such village.");
                                            int val = IntegerArgumentType.getInteger(c, "value");
                                            a.get().relations.put(b.get().getId(), val);
                                            b.get().relations.put(a.get().getId(), val);
                                            return say(c, "Relation set to " + val + ". Run /settlements daily to see what happens.");
                                        })))))
        );
    }

    private static Optional<Village> village(CommandContext<ServerCommandSource> c) {
        // from a player, the village he stands in; from the console (execute positioned ... run), the one at that spot
        Optional<Village> v = c.getSource().getEntity() != null ? VillageManager.get(c.getSource().getWorld()).findNearestVillage(c.getSource().getEntity())
                : VillageManager.get(c.getSource().getWorld()).findNearestVillage(net.minecraft.util.math.BlockPos.ofFloored(c.getSource().getPosition()), 64);
        if (v.isEmpty()) say(c, "No village nearby. Stand inside a village.");
        return v;
    }

    private static int say(CommandContext<ServerCommandSource> c, String s) {
        c.getSource().sendFeedback(() -> Text.literal("[Settlements] " + s), false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> c) {
        Optional<Village> opt = village(c);
        if (opt.isEmpty()) return 0;
        Village v = opt.get();
        say(c, v.getName() + " (id " + v.getId() + "): " + Plan.level(v) + ", " + v.getPopulation() + "/" + v.getMaxPopulation() + " residents");
        say(c, "Built: well=" + v.wellBuilt + " storage=" + v.storageBuilt + " houses=" + v.houses.size() + " farms=" + v.farms
                + " mine=" + v.mineBuilt + "(level " + v.mineLevel + ") smithy=" + v.smithyBuilt + " armory=" + v.armoryBuilt
                + " tavern=" + (v.tavern != null) + " towers=" + v.towers + " wall=" + v.wallBuilt);
        say(c, "Project: " + (v.buildOrigin == null ? "none" : v.buildType + " at " + v.buildOrigin.toShortString() + ", step " + v.buildStep));
        java.util.Map<String, Integer> stock = new java.util.TreeMap<>();
        for (ItemStack st : v.storageBuffer) stock.merge(Registries.ITEM.getId(st.getItem()).getPath(), st.getCount(), Integer::sum);
        say(c, "Stores: " + stock);
        say(c, "Mine: " + (v.mineOrigin == null ? "none" : v.mineOrigin.toShortString() + " level " + v.mineLevel + " step " + v.mineStep));
        say(c, "Coffers: " + v.coffers + " coins. Treasury: planks+logs*4=" + Builder.stockPlanks(v) + " cobble=" + Builder.stockCobble(v) + " iron=" + Builder.stockIron(v)
                + " wool=" + Builder.stockWool(v) + " stacks=" + v.storageBuffer.size());
        for (Plan.Status s : Plan.pending(v)) {
            say(c, " goal " + s.goal().id() + (s.ready() ? ": ready" : ": missing planks " + s.missPlanks() + ", cobble " + s.missCobble()
                    + ", iron " + s.missIron() + ", wool " + s.missWool()));
        }
        for (var e : v.relations.entrySet()) say(c, " relation with village " + e.getKey() + ": " + e.getValue());
        return 1;
    }

    /** Every grown villager about: who, where, what he is doing and why, his strength and hunger (for testing). */
    private static int dump(CommandContext<ServerCommandSource> c) {
        ServerWorld w = c.getSource().getWorld();
        var at = c.getSource().getPosition();
        var box = new net.minecraft.util.math.Box(at, at).expand(128, 64, 128);
        long tod = w.getTimeOfDay();
        if (fake != null) say(c, "Tester @" + fake.getBlockPos().toShortString() + " health " + fake.getHealth());
        say(c, "time " + tod % 24000L + " day " + tod / 24000L + (Workforce.isRestDay(tod) ? " (rest day)" : ""));
        for (var v : w.getEntitiesByClass(net.mca.entity.VillagerEntityMCA.class, box, x -> x.isAlive())) {
            String prof = v.isBaby() ? "child" : v.isGuard() ? "guard" : v.isElder() ? "elder" : Registries.VILLAGER_PROFESSION.getId(v.getProfession()).getPath();
            Village vil = Village.findNearest(v).or(() -> v.getResidency().getHomeVillage()).orElse(null);
            say(c, v.getName().getString() + " [" + prof + "] @" + v.getBlockPos().toShortString() + " e=" + v.settlementsEnergy + " h=" + v.settlementsHunger + " $=" + v.settlementsPurse + " home=" + v.getResidency().getHome().map(h -> h.getPos().toShortString()).orElse("-")
                    + " idle=" + v.settlementsIdleWork + " job=" + v.getVillagerBrain().getCurrentJob() + " vil=" + (vil == null ? "-" : vil.getId())
                    + " load=" + Haul.load(v) + (v.settlementsHauling ? "(hauling)" : "") + " store=" + (vil == null || Haul.store(v, vil) == null ? "-" : Haul.store(v, vil).toShortString())
                    + " hand=" + Registries.ITEM.getId(v.getMainHandStack().getItem()).getPath() + " goal=" + (Move.goal(v) == null ? "-" : Move.goal(v).toShortString())
                    + v.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET).map(t -> " TARGET=" + t.getName().getString() + " d=" + (int) t.distanceTo(v)).orElse("")
                    + (v.settlementsDigPos != null ? " dig=" + v.settlementsDigPos.toShortString() : "") + (v.getNavigation().isFollowingPath() ? " nav" : "")
                    + " stage=" + Workforce.STAGE.getOrDefault(v.getUuid(), "-")
                    + " :: " + Doing.describe(v).getString());
        }
        return 1;
    }

    // ---------- a stand-in player for testing on a server nobody plays on ----------

    private static ServerPlayerEntity fake;

    /** A player standing where the command is run (from the console: execute positioned ... run), seen by villagers and guards. */
    private static int fakeSpawn(CommandContext<ServerCommandSource> c) {
        ServerWorld w = c.getSource().getWorld();
        if (fake != null) w.removePlayer(fake, net.minecraft.entity.Entity.RemovalReason.DISCARDED);
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("settlements-tester".getBytes()), "Tester");
        fake = new ServerPlayerEntity(w.getServer(), w, profile) {
            @Override
            public void sendMessage(Text message, boolean overlay) {
                net.mca.MCA.LOGGER.info("[to Tester] " + message.getString()); // what a player would read, in the server log
            }
        };
        var conn = new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new net.minecraft.server.network.ServerPlayNetworkHandler(w.getServer(), conn, fake);
        var at = c.getSource().getPosition();
        fake.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        fake.getAbilities().invulnerable = true; // a test player: the night's zombies must not take him off
        fake.setInvulnerable(true);
        w.onPlayerConnected(fake);
        return say(c, "Tester stands at " + net.minecraft.util.math.BlockPos.ofFloored(at).toShortString());
    }

    /** The stand-in hits the named villager, as a player would. */
    private static int fakeHit(CommandContext<ServerCommandSource> c) {
        if (fake == null) return say(c, "No tester. /settlements fake spawn first.");
        String name = StringArgumentType.getString(c, "name");
        ServerWorld w = c.getSource().getWorld();
        for (var v : w.getEntitiesByClass(net.mca.entity.VillagerEntityMCA.class, fake.getBoundingBox().expand(128), x -> x.isAlive())) {
            if (!v.getName().getString().equalsIgnoreCase(name)) continue;
            v.damage(w.getDamageSources().playerAttack(fake), 3f);
            return say(c, "Tester hit " + name + " (health " + v.getHealth() + ")");
        }
        return say(c, "No villager called " + name + " near the tester.");
    }

    private static int fakeRemove(CommandContext<ServerCommandSource> c) {
        if (fake != null) c.getSource().getWorld().removePlayer(fake, net.minecraft.entity.Entity.RemovalReason.DISCARDED);
        fake = null;
        return say(c, "Tester gone.");
    }

    private static void add(Village v, Item item, int count) {
        while (count > 0) {
            int n = Math.min(count, item.getMaxCount());
            Workforce.addToTreasury(v, new ItemStack(item, n));
            count -= n;
        }
    }

    private static int rich(CommandContext<ServerCommandSource> c) {
        Optional<Village> opt = village(c);
        if (opt.isEmpty()) return 0;
        Village v = opt.get();
        add(v, Items.OAK_LOG, 256);
        add(v, Items.OAK_PLANKS, 512);
        add(v, Items.COBBLESTONE, 800);
        add(v, Items.WHITE_WOOL, 24);
        add(v, Items.IRON_INGOT, 120);
        add(v, Items.RAW_IRON, 40);
        add(v, Items.COAL, 64);
        add(v, Items.TORCH, 48);
        add(v, Items.GLASS_PANE, 32);
        add(v, Items.WHEAT, 64);
        add(v, Items.WHEAT_SEEDS, 64);
        add(v, Items.BREAD, 64);
        add(v, Items.BEEF, 32);
        add(v, Items.POTATO, 32);
        add(v, Items.LEATHER, 48);
        add(v, Items.FEATHER, 16);
        add(v, Items.FLINT, 16);
        v.markDirty();
        return say(c, "The treasury is stocked for testing.");
    }

    private static int give(CommandContext<ServerCommandSource> c) {
        Optional<Village> opt = village(c);
        if (opt.isEmpty()) return 0;
        String id = StringArgumentType.getString(c, "item");
        Identifier ident = id.contains(":") ? new Identifier(id) : new Identifier("minecraft", id);
        if (!Registries.ITEM.containsId(ident)) return say(c, "Unknown item: " + id);
        int count = IntegerArgumentType.getInteger(c, "count");
        add(opt.get(), Registries.ITEM.get(ident), count);
        opt.get().markDirty();
        return say(c, "Added " + count + " x " + ident + " to the treasury.");
    }

    /** Places these designs in a row going east, a few blocks south of the player, and lists them in order. */
    private static int placeRow(com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> c, java.util.List<String> types, String title)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = c.getSource().getPlayerOrThrow();
        int x = player.getBlockX() + 4, y = player.getBlockY(), z = player.getBlockZ() + 6;
        for (String type : types) {
            int[] f = Builder.footprintOf(type, 0);
            Builder.placeForLook((ServerWorld) player.getWorld(), type, new net.minecraft.util.math.BlockPos(x, y, z), 0);
            x += f[0] + 5;
        }
        return say(c, title + ", west to east: " + String.join(", ", types) + ". Doors face south.");
    }
}
