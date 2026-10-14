package net.mca.resources;

import net.mca.resources.Resources.BrokenResourceException;
import net.mca.resources.data.NameSet;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Map;

public class VillageComponents {
    private final Map<String, NameSet> namePool = new HashMap<>();

    private final Random rng;

    VillageComponents(Random rng) {
        this.rng = rng;
    }

    void load() throws BrokenResourceException {
        namePool.put("village", Resources.read("api/names/village.json", NameSet.class));
    }

    //returns a random generated name for a given name set
    public String pickVillageName(String from) {
        return namePool.getOrDefault(from, NameSet.DEFAULT).toName(rng);
    }
}
