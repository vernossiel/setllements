package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;
import net.minecraft.text.Text;

import java.util.List;
import java.util.stream.Collectors;

public class CivilRegistryResponse implements Message {
    private final int index;
    private final List<String> lines;

    public CivilRegistryResponse(int index, List<Text> lines) {
        this.index = index;
        this.lines = lines.stream().map(Text.Serializer::toJson).collect(Collectors.toList());
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleCivilRegistryResponse(this);
    }

    public int getIndex() {
        return index;
    }

    public List<Text> getLines() {
        return lines.stream().map(Text.Serializer::fromJson).collect(Collectors.toList());
    }
}
