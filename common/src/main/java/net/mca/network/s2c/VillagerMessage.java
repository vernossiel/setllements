package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.Serial;
import java.util.UUID;

public class VillagerMessage implements Message {
    @Serial
    private static final long serialVersionUID = -4135222437610000843L;

    private final String prefix;
    private final String message;
    private final UUID uuid;

    public VillagerMessage(MutableText prefix, MutableText message, UUID uuid) {
        this.prefix = Text.Serializer.toJson(prefix);
        this.message = Text.Serializer.toJson(message);
        this.uuid = uuid;
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleVillagerMessage(this);
    }

    public MutableText safeLoadFromJson(String json) {
        MutableText mutableText = Text.Serializer.fromJson(json);
        if (mutableText == null) return Text.literal("");
        return mutableText;
    }

    public MutableText getMessage() {
        return safeLoadFromJson(prefix).append(safeLoadFromJson(message));
    }

    public MutableText getContent() {
        return safeLoadFromJson(message);
    }

    public UUID getUuid() {
        return uuid;
    }
}
