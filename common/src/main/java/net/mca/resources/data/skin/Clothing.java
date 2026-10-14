package net.mca.resources.data.skin;

import com.google.gson.JsonObject;
import net.mca.entity.ai.relationship.Gender;
import net.minecraft.util.JsonHelper;
import org.jetbrains.annotations.Nullable;

public class Clothing extends SkinListEntry {
    @Nullable
    public final String profession;
    public final int temperature;
    public final boolean exclude;

    public Clothing(String identifier, @Nullable String profession, int temperature, boolean exclude, Gender gender) {
        super(identifier, gender, 1.0f);
        this.profession = profession;
        this.temperature = temperature;
        this.exclude = exclude;
    }

    public Clothing(String identifier, JsonObject object) {
        super(identifier, object);

        this.profession = object.get("profession").isJsonNull() ? null : JsonHelper.getString(object, "profession", null);
        this.exclude = JsonHelper.getBoolean(object, "exclude", false);
        this.temperature = JsonHelper.getInt(object, "temperature", 0);
    }

    @Override
    public JsonObject toJson() {
        JsonObject j = super.toJson();
        j.addProperty("profession", profession);
        j.addProperty("exclude", exclude);
        j.addProperty("temperature", temperature);
        return j;
    }
}
