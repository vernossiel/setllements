package net.mca.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.mca.MCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.resources.data.skin.Hair;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.profiler.Profiler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HairList extends JsonDataLoader {
    protected static final Identifier ID = MCA.locate("skins/hair");

    public final HashMap<String, Hair> hair = new HashMap<>();

    private static HairList INSTANCE;

    public static HairList getInstance() {
        return INSTANCE;
    }

    public HairList() {
        super(Resources.GSON, "skins/hair");
        INSTANCE = this;
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> data, ResourceManager manager, Profiler profiler) {
        hair.clear();

        data.forEach((id, file) -> {
            Gender gender = Gender.byName(id.getPath().split("\\.")[0]);

            if (gender == Gender.UNASSIGNED) {
                MCA.LOGGER.warn("Invalid gender for clothing pool: {}", id);
                return;
            }

            for (String key : file.getAsJsonObject().keySet()) {
                JsonObject object = file.getAsJsonObject().get(key).getAsJsonObject();

                for (int i = 0; i < JsonHelper.getInt(object, "count", 1); i++) {
                    String identifier = String.format(Locale.ROOT, key, i);

                    Hair c = new Hair(identifier, gender, JsonHelper.getFloat(object, "chance", 1.0f));

                    if (!hair.containsKey(identifier) || !object.has("count")) {
                        hair.put(identifier, c);
                    }
                }
            }
        });
    }

    public WeightedPool<String> getPool(Gender gender) {
        return hair.values().stream()
                .filter(c -> c.getGender() == Gender.NEUTRAL || gender == Gender.NEUTRAL || c.getGender() == gender)
                .collect(() -> new WeightedPool.Mutable<>("mca:missing"),
                        (list, entry) -> list.add(entry.getIdentifier(), entry.getChance()),
                        (a, b) -> {
                            a.entries.addAll(b.entries);
                        });
    }
}
