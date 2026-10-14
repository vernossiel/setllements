package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.network.NbtDataMessage;
import net.minecraft.nbt.NbtCompound;

import java.io.Serial;

public class GetVillagerResponse extends NbtDataMessage {
    @Serial
    private static final long serialVersionUID = 4997443623143425383L;

    public GetVillagerResponse(NbtCompound data) {
        super(data);
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleVillagerDataResponse(this);
    }
}
