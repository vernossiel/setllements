package net.mca.resources;

import net.mca.MCA;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;

public class ApiReloadListener implements SynchronousResourceReloader {
    public static final Identifier ID = MCA.locate("api");

    @Override
    public void reload(ResourceManager manager) {
        API.instance = new API.Data();
        API.instance.init(manager);
    }
}
