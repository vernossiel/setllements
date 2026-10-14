package net.mca.client.resources;

import net.mca.MCA;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.SinglePreparationResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ColorPaletteLoader extends SinglePreparationResourceReloader<Map<Identifier, ColorPalette.Data>> {
    protected static final Identifier ID = new Identifier(MCA.MOD_ID, "color_palettes");

    @Override
    protected Map<Identifier, ColorPalette.Data> prepare(ResourceManager manager, Profiler profiler) {
        return ColorPalette.REGISTRY.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            return loadPalette(entry.getKey(), manager);
        }));
    }

    @SuppressWarnings("deprecation")
    private ColorPalette.Data loadPalette(Identifier id, ResourceManager manager) {
        try (NativeImage img = NativeImage.read(manager.getResource(id).get().getInputStream())) {
            return new ColorPalette.Data(
                    img.getWidth(),
                    img.getHeight(),
                    img.makePixelArray()
            );
        } catch (Exception e) {
            MCA.LOGGER.error("Failed to load color palette from `{}`", id, e);
        }
        return ColorPalette.EMPTY;
    }

    @Override
    protected void apply(Map<Identifier, ColorPalette.Data> palettes, ResourceManager manager, Profiler profiler) {
        palettes.forEach((id, data) -> {
            if (ColorPalette.REGISTRY.containsKey(id)) {
                ColorPalette.REGISTRY.get(id).data = Objects.requireNonNull(data);
            }
        });
    }
}
