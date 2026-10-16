package net.mca.client.gui;

import net.mca.network.s2c.JusticeScreenRequest;
import net.mca.util.compat.ButtonWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The village's justice face to face: the guard's words, the fine, how long the player has to answer, and the
 * answers as buttons. Closed with Escape it can be answered later in the chat (/settle pay and the like).
 */
public class JusticeScreen extends Screen {
    private final JusticeScreenRequest request;
    private final long opened = System.currentTimeMillis();
    private List<OrderedText> wrapped = new ArrayList<>();
    private static final int W = 280;

    public JusticeScreen(JusticeScreenRequest request) {
        super(Text.translatable("settlements.justice.title"));
        this.request = request;
    }

    @Override
    protected void init() {
        wrapped = new ArrayList<>();
        for (Text l : request.getLines()) wrapped.addAll(textRenderer.wrapLines(l, W - 20));
        String[] opts = request.getOptions();
        int bw = Math.min(130, (W - 10) / Math.max(1, Math.min(opts.length, 2)) - 6);
        int top = panelTop() + 36 + wrapped.size() * 10 + 10;
        for (int i = 0; i < opts.length; i++) {
            String o = opts[i];
            int col = i % 2, row = i / 2;
            int x = width / 2 - (bw + 6) + col * (bw + 6) + (opts.length == 1 ? (bw + 6) / 2 : 0);
            addDrawableChild(new ButtonWidget(x, top + row * 24, bw, 20, Text.translatable("settlements.justice.button." + o), b -> answer(o)));
        }
    }

    private int panelTop() {
        int rows = (request.getOptions().length + 1) / 2;
        int h = 36 + wrapped.size() * 10 + 10 + rows * 24 + 10;
        return Math.max(10, height / 2 - h / 2 - 20);
    }

    private void answer(String option) {
        if (client != null && client.player != null) client.player.networkHandler.sendChatCommand("settle " + option);
        close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        int top = panelTop(), left = width / 2 - W / 2;
        int rows = (request.getOptions().length + 1) / 2;
        int h = 36 + wrapped.size() * 10 + 10 + rows * 24 + 10;
        ctx.fill(left, top, left + W, top + h, 0xE0201812);
        ctx.fill(left, top, left + W, top + 1, 0xFFB08A4A);
        ctx.fill(left, top + h - 1, left + W, top + h, 0xFFB08A4A);
        ctx.drawCenteredTextWithShadow(textRenderer, request.getSpeaker().copy().formatted(Formatting.GOLD, Formatting.BOLD), width / 2, top + 8, 0xFFFFFF);
        int left_s = request.getSeconds() - (int) ((System.currentTimeMillis() - opened) / 1000);
        if (request.getSeconds() > 0) {
            Text timer = Text.translatable("settlements.justice.timer", Math.max(0, left_s)).formatted(left_s <= 10 ? Formatting.RED : Formatting.GRAY);
            ctx.drawCenteredTextWithShadow(textRenderer, timer, width / 2, top + 20, 0xFFFFFF);
        }
        int y = top + 36;
        for (OrderedText line : wrapped) {
            ctx.drawTextWithShadow(textRenderer, line, left + 10, y, 0xE8E0D0);
            y += 10;
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
