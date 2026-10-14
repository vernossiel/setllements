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
    private SettlementsCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        // player-facing answers to a guard's demand, opened by the clickable buttons in the chat
        d.register(CommandManager.literal("settle")
                .then(CommandManager.literal("return").executes(c -> Reputation.returnGoods(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("rep").executes(c -> Reputation.showStanding(c.getSource().getPlayerOrThrow())))
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
                    Plan.talk(elder != null ? elder : near.get(0), player);
                    return 1;
                }))
                .then(CommandManager.literal("refuse").executes(c -> Reputation.refuse(c.getSource().getPlayerOrThrow()))));
        d.register(CommandManager.literal("settlements").requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.literal("status").executes(SettlementsCommand::status))
                .then(CommandManager.literal("rich").executes(SettlementsCommand::rich))
                .then(CommandManager.literal("give")
                        .then(CommandManager.argument("item", StringArgumentType.string())
                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 4096)).executes(SettlementsCommand::give))))
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
        try {
            ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
            Optional<Village> v = VillageManager.get(c.getSource().getWorld()).findNearestVillage(p);
            if (v.isEmpty()) say(c, "No village nearby. Stand inside a village.");
            return v;
        } catch (Exception e) {
            say(c, "Run this as a player.");
            return Optional.empty();
        }
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
        say(c, "Treasury: planks+logs*4=" + Builder.stockPlanks(v) + " cobble=" + Builder.stockCobble(v) + " iron=" + Builder.stockIron(v)
                + " wool=" + Builder.stockWool(v) + " stacks=" + v.storageBuffer.size());
        for (Plan.Status s : Plan.pending(v)) {
            say(c, " goal " + s.goal().id() + (s.ready() ? ": ready" : ": missing planks " + s.missPlanks() + ", cobble " + s.missCobble()
                    + ", iron " + s.missIron() + ", wool " + s.missWool()));
        }
        for (var e : v.relations.entrySet()) say(c, " relation with village " + e.getKey() + ": " + e.getValue());
        return 1;
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
}
