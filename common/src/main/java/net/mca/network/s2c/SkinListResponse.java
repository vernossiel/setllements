package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;
import net.mca.resources.data.skin.Clothing;
import net.mca.resources.data.skin.Hair;

import java.io.Serial;
import java.util.HashMap;

public class SkinListResponse implements Message {
    @Serial
    private static final long serialVersionUID = 3523559818338225910L;

    private final HashMap<String, Clothing> clothing;
    private final HashMap<String, Hair> hair;

    public SkinListResponse(HashMap<String, Clothing> clothing, HashMap<String, Hair> hair) {
        this.clothing = clothing;
        this.hair = hair;
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleSkinListResponse(this);
    }

    public HashMap<String, Clothing> getClothing() {
        return clothing;
    }

    public HashMap<String, Hair> getHair() {
        return hair;
    }
}
