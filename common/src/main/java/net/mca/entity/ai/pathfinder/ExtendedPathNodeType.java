package net.mca.entity.ai.pathfinder;

import net.minecraft.entity.ai.pathing.PathNodeType;

public enum ExtendedPathNodeType {
    // Vanilla types
    BLOCKED(-1.0F),
    OPEN(0.0F),
    WALKABLE(0.0F),
    WALKABLE_DOOR(0.0F),
    TRAPDOOR(0.0F),
    POWDER_SNOW(-1.0F),
    DANGER_POWDER_SNOW(0.0F),
    FENCE(-1.0F),
    LAVA(-1.0F),
    WATER(8.0F),
    WATER_BORDER(8.0F),
    RAIL(0.0F),
    UNPASSABLE_RAIL(-1.0F),
    DANGER_FIRE(8.0F),
    DAMAGE_FIRE(16.0F),
    DANGER_OTHER(8.0F),
    DAMAGE_OTHER(-1.0F),
    DOOR_OPEN(0.0F),
    DOOR_WOOD_CLOSED(-1.0F),
    DOOR_IRON_CLOSED(-1.0F),
    BREACH(4.0F),
    LEAVES(-1.0F),
    STICKY_HONEY(8.0F),
    COCOA(0.0F),

    // MCA custom types
    GRASS(-1.0f, PathNodeType.BLOCKED),
    PATH(-1.0f, PathNodeType.BLOCKED),
    WALKABLE_GRASS(0.0f, PathNodeType.WALKABLE),
    WALKABLE_PATH(0.0f, PathNodeType.WALKABLE);

    private final float defaultPenalty;
    private PathNodeType vanilla;

    ExtendedPathNodeType(float defaultPenalty) {
        this(defaultPenalty, null);
        if (vanilla == null) {
            vanilla = PathNodeType.valueOf(name());
        }
    }

    ExtendedPathNodeType(float defaultPenalty, PathNodeType vanilla) {
        this.defaultPenalty = defaultPenalty;
        this.vanilla = vanilla;
    }

    public float getDefaultPenalty() {
        return this.defaultPenalty;
    }

    public PathNodeType toVanilla() {
        return vanilla;
    }

    public boolean isWalkable() {
        return this == ExtendedPathNodeType.WALKABLE || this == ExtendedPathNodeType.WALKABLE_GRASS || this == ExtendedPathNodeType.WALKABLE_PATH;
    }

    public float getBonusPenalty() {
        return defaultPenalty >= 0.0f ? (
                this == ExtendedPathNodeType.WALKABLE_GRASS ? 2.0f :
                        this == ExtendedPathNodeType.WALKABLE_PATH ? 0.001f :
                                this == ExtendedPathNodeType.OPEN ? 0.0f : 1.0f
        ) : 0.0f;
    }
}
