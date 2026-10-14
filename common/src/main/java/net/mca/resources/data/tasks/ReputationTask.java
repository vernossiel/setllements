package net.mca.resources.data.tasks;

import com.google.gson.JsonObject;
import net.mca.server.world.data.Village;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.JsonHelper;

import java.io.Serial;

public class ReputationTask extends Task {
    @Serial
    private static final long serialVersionUID = -7232675787774372089L;

    private final int reputation;

    public ReputationTask(int reputation) {
        super("reputation_" + reputation);
        this.reputation = reputation;
    }

    public ReputationTask(JsonObject json) {
        this(JsonHelper.getInt(json, "reputation"));
    }

    @Override
    public boolean isCompleted(Village village, ServerPlayerEntity player) {
        return village.getReputation(player) >= reputation;
    }

    @Override
    public boolean isRequired() {
        return true;
    }

    @Override
    public MutableText getTranslatable() {
        return Text.translatable("task.reputation", reputation);
    }
}
