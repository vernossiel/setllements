package net.mca.entity;

import net.mca.util.LimitedLinkedHashMap;
import net.minecraft.text.TextContent;

public class CommonSpeechManager {
    public static final CommonSpeechManager INSTANCE = new CommonSpeechManager();

    public String lastResolvedKey;
    public final LimitedLinkedHashMap<TextContent, String> translations = new LimitedLinkedHashMap<>(100);
}
