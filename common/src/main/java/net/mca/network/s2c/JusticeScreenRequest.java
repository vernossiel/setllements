package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/** A guard confronts the player: what he says, and the answers to pick from, shown in a window of their own. */
public class JusticeScreenRequest implements Message {
    @Serial
    private static final long serialVersionUID = 4471923817716255301L;

    private final String speaker;
    private final String[] lines;
    private final String[] options;
    private final int seconds;

    public JusticeScreenRequest(Text speaker, List<Text> lines, String[] options, int seconds) {
        this.speaker = Text.Serializer.toJson(speaker);
        this.lines = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) this.lines[i] = Text.Serializer.toJson(lines.get(i));
        this.options = options;
        this.seconds = seconds;
    }

    private static MutableText load(String json) {
        MutableText t = Text.Serializer.fromJson(json);
        return t == null ? Text.literal("") : t;
    }

    public Text getSpeaker() {
        return load(speaker);
    }

    public List<Text> getLines() {
        List<Text> out = new ArrayList<>();
        for (String l : lines) out.add(load(l));
        return out;
    }

    public String[] getOptions() {
        return options;
    }

    public int getSeconds() {
        return seconds;
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleJusticeScreen(this);
    }
}
