package net.mca.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.mca.MCA;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public class ColorPickerWidget extends ClickableWidget {
    @FunctionalInterface
    public interface DualConsumer<A, B> {
        void apply(A a, B b);
    }

    public static final Identifier MCA_GUI_ICONS_TEXTURE = MCA.locate("textures/gui.png");

    private final DualConsumer<Double, Double> consumer;
    private final Identifier texture;
    double valueX;
    double valueY;

    public ColorPickerWidget(int x, int y, int width, int height, double valueX, double valueY, Identifier texture, DualConsumer<Double, Double> consumer) {
        super(x, y, width, height, Text.literal(""));
        this.consumer = consumer;
        this.texture = texture;
        this.valueX = valueX;
        this.valueY = valueY;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        context.drawTexture(texture, getX(), getY(), 0, 0, width, height, width, height);

        WidgetUtils.drawRectangle(context, getX(), getY(), getX() + width, getY() + height, 0xaaffffff);

        context.drawTexture(MCA_GUI_ICONS_TEXTURE, (int)(getX() + valueX * width) - 8, (int)(getY() + valueY * height) - 8, 240, 0, 16, 16, 256, 256);

        WidgetUtils.drawRectangle(context, getX(), getY(), getX() + width, getY() + height, 0xaaffffff);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
        update(mouseX, mouseY);
        super.onDrag(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInArea(mouseX, mouseY)) {
            update(mouseX, mouseY);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isInArea(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX <= getX() + width && mouseY >= getY() && mouseY <= getY() + height;
    }

    void update(double mouseX, double mouseY) {
        valueX = MathHelper.clamp((mouseX - getX()) / width, 0.0, 1.0);
        valueY = MathHelper.clamp((mouseY - getY()) / height, 0.0, 1.0);
        consumer.apply(valueX, valueY);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    public double getValueX() {
        return valueX;
    }

    public void setValueX(double valueX) {
        this.valueX = valueX;
    }

    public double getValueY() {
        return valueY;
    }

    public void setValueY(double valueY) {
        this.valueY = valueY;
    }
}
