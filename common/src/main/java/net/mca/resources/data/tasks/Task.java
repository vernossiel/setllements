package net.mca.resources.data.tasks;

import com.google.gson.JsonObject;
import net.mca.server.world.data.Village;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.JsonHelper;

import java.io.Serial;
import java.io.Serializable;

public abstract class Task implements Serializable {
    @Serial
    private static final long serialVersionUID = 6029812512760976500L;

    private final String id;

    public Task(JsonObject json) {
        this(JsonHelper.getString(json, "id"));
    }

    public Task(String id) {
        this.id = id;
    }

    abstract public boolean isCompleted(Village village, ServerPlayerEntity player);

    public boolean isRequired() {
        return false;
    }

    public MutableText getTranslatable() {
        return Text.translatable("task." + id);
    }

    public String getId() {
        return id;
    }
}
