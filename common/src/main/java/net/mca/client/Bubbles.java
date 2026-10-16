package net.mca.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What villagers say, over their heads instead of in the chat: a line shows a few seconds (longer for a longer one)
 * and fades; a new one replaces it. Only what is said close by is seen, as it would be heard.
 */
public final class Bubbles {
    private record Line(Text text, long start, long until) {
    }

    private static final Map<UUID, Line> LINES = new HashMap<>();

    private Bubbles() {
    }

    /** Over the speaker's head, if he is near enough to be seen saying it; false when he is not (it goes to the chat). */
    public static boolean say(UUID speaker, Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return false;
        Entity e = null;
        for (Entity x : mc.world.getEntities()) {
            if (x.getUuid().equals(speaker)) {
                e = x;
                break;
            }
        }
        if (e == null || e.squaredDistanceTo(mc.player) > 32 * 32) return false;
        long now = System.currentTimeMillis();
        LINES.put(speaker, new Line(text, now, now + 3000 + text.getString().length() * 60L));
        return true;
    }

    /** Drawn by the villager renderer, above the name tag. */
    public static void render(Entity e, MatrixStack matrices, VertexConsumerProvider vertices, int light, float above) {
        Line l = LINES.get(e.getUuid());
        if (l == null) return;
        long now = System.currentTimeMillis();
        if (now > l.until()) {
            LINES.remove(e.getUuid());
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        List<OrderedText> rows = tr.wrapLines(l.text(), 140);
        float fade = Math.min(1f, Math.min((now - l.start()) / 150f, (l.until() - now) / 400f));
        int alpha = Math.max(8, (int) (255 * fade));
        int bg = (int) (mc.options.getTextBackgroundOpacity(0.35f) * 255 * fade) << 24;
        matrices.push();
        matrices.translate(0, above + rows.size() * 0.25f, 0);
        matrices.multiply(mc.getEntityRenderDispatcher().getRotation());
        matrices.scale(-0.026f, -0.026f, 0.026f); // (a little bigger than a name tag, to be read from a few steps off)
        Matrix4f m = matrices.peek().getPositionMatrix();
        int y = 0;
        for (OrderedText row : rows) {
            float x = -tr.getWidth(row) / 2f;
            tr.draw(row, x, y, (alpha << 24) | 0xFFFFFF, false, m, vertices, TextRenderer.TextLayerType.NORMAL, bg, 0xF000F0); // lit full: readable at night and in the shade
            y += 10;
        }
        matrices.pop();
    }
}
