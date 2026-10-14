package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;

import java.io.Serial;

public class GetVillageFailedResponse implements Message {
    @Serial
    private static final long serialVersionUID = 4021214184633955444L;

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleVillageDataFailedResponse(this);
    }
}
