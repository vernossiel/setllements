package net.mca.resources;

import com.google.gson.JsonElement;
import net.mca.MCA;
import net.mca.resources.data.BuildingType;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class BuildingTypes extends JsonDataLoader implements Iterable<BuildingType> {
    protected static final Identifier ID = MCA.locate("building_types");

    private final Map<String, BuildingType> buildingTypes = new HashMap<>();
    private final Map<String, BuildingType> buildingTypesClient = new HashMap<>();

    private static BuildingTypes INSTANCE = new BuildingTypes();

    public BuildingTypes() {
        super(Resources.GSON, ID.getPath());
        INSTANCE = this;
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, Profiler profiler) {
        for (Map.Entry<Identifier, JsonElement> pair : prepared.entrySet()) {
            String name = pair.getKey().getPath();
            buildingTypes.put(name, new BuildingType(name, pair.getValue().getAsJsonObject()));
        }
        setBuildingTypes(buildingTypes);
    }

    // Provide the client with building types
    public void setBuildingTypes(Map<String, BuildingType> buildingTypes) {
        buildingTypesClient.clear();
        buildingTypesClient.putAll(buildingTypes);
    }

    public Map<String, BuildingType> getServerBuildingTypes() {
        return buildingTypes;
    }

    public Map<String, BuildingType> getBuildingTypes() {
        return buildingTypesClient;
    }

    public BuildingType getBuildingType(String type) {
        return buildingTypesClient.containsKey(type) ? buildingTypesClient.get(type) : new BuildingType();
    }

    public static BuildingTypes getInstance() {
        return INSTANCE;
    }

    @Override
    public Iterator<BuildingType> iterator() {
        return buildingTypesClient.values().iterator();
    }
}
