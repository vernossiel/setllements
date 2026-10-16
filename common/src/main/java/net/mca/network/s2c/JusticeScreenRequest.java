package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * Face to face with a villager (a guard's demand, an arrest, a word with the elder): what he says, and the answers to
 * pick from. Shown at the bottom of the screen over the game; the answers come up while the player looks at him.
 */
public class JusticeScreenRequest implements Message {
    @Serial
    private static final long serialVersionUID = 4471923817716255301L;

    private final String speaker;
    private final String[] lines;
    private final String[] options;
    private final int seconds;
    private final int entity;
    private final String[] labels;

    /** {@code options}: the commands (after "/settle ") the answers run; {@code labels}: what the answers say. */
    public JusticeScreenRequest(int entity, Text speaker, List<Text> lines, String[] options, List<Text> labels, int seconds) {
        this.speaker = Text.Serializer.toJson(speaker);
        this.lines = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) this.lines[i] = Text.Serializer.toJson(lines.get(i));
        this.options = options;
        this.labels = new String[labels.size()];
        for (int i = 0; i < labels.size(); i++) this.labels[i] = Text.Serializer.toJson(labels.get(i));
        this.seconds = seconds;
        this.entity = entity;
        net.mca.settlements.Dialogue.hold(entity, seconds > 0 ? seconds : options.length > 0 ? 40 : 12);
    }

    /** The one who speaks (an entity id), or -1. */
    public int getEntity() {
        return entity;
    }

    public List<Text> getLabels() {
        List<Text> out = new ArrayList<>();
        for (String l : labels) out.add(load(l));
        return out;
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
