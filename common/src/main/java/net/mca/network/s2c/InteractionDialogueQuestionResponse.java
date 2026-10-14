package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.cobalt.network.Message;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.Serial;

public class InteractionDialogueQuestionResponse implements Message {
    @Serial
    private static final long serialVersionUID = 1371939319244994642L;

    public final String questionText;
    public final boolean silent;

    public InteractionDialogueQuestionResponse(boolean silent, Text questionText) {
        this.questionText = Text.Serializer.toJson(questionText);
        this.silent = silent;
    }

    public MutableText getQuestionText() {
        return Text.Serializer.fromJson(questionText);
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleDialogueQuestionResponse(this);
    }
}
