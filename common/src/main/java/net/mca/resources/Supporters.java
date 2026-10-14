package net.mca.resources;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.mca.MCA;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.profiler.Profiler;

import java.util.*;

public class Supporters extends JsonDataLoader {
    protected static final Identifier ID = new Identifier(MCA.MOD_ID, "api/supporters");

    private static Supporters INSTANCE;

    static final Random rng = Random.create();

    private final List<String> supporters = new ArrayList<>();
    private final Map<String, List<String>> supporterGroups = new HashMap<>();

    public Supporters() {
        super(Resources.GSON, ID.getPath());
        INSTANCE = this;
    }

    public Supporters(Gson gson, String dataType) {
        super(gson, dataType);
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, Profiler profiler) {
        for (Map.Entry<Identifier, JsonElement> pair : prepared.entrySet()) {
            List<String> strings = supporterGroups.computeIfAbsent(pair.getKey().toString(), x -> new LinkedList<>());
            for (JsonElement e : pair.getValue().getAsJsonArray()) {
                supporters.add(e.getAsString());
                strings.add(e.getAsString());
            }
        }
    }

    public String pickSupporter() {
        return PoolUtil.pickOne(supporters, "nobody", rng);
    }

    public static String getRandomSupporter() {
        return INSTANCE.pickSupporter();
    }

    public static List<String> getSupporterGroup(String group) {
        return INSTANCE.supporterGroups.getOrDefault(group, new LinkedList<>());
    }
}
