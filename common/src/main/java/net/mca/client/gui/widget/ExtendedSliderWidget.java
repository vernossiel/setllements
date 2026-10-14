package net.mca.client.gui.widget;

import net.mca.util.localization.FlowingText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class ExtendedSliderWidget<T> extends SliderWidget {
    private T oldValue;
    final Consumer<T> onApplyValue;
    protected final Supplier<Text> tooltipSupplier;

    public ExtendedSliderWidget(int x, int y, int width, int height, Text text, double value, Consumer<T> onApplyValue, Supplier<Text> tooltipSupplier) {
        super(x, y, width, height, text, value);
        this.onApplyValue = onApplyValue;
        this.tooltipSupplier = tooltipSupplier;
    }

    protected double getOpticalValue() {
        return value;
    }

    abstract T getValue();

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        int i = (this.isHovered() ? 2 : 1) * 20;
        context.drawTexture(WIDGETS_TEXTURE, this.getX() + (int) (getOpticalValue() * (double) (this.width - 8)), this.getY(), 0, 46 + i, 4, 20);
        context.drawTexture(WIDGETS_TEXTURE, this.getX() + (int) (getOpticalValue() * (double) (this.width - 8)) + 4, this.getY(), 196, 46 + i, 4, 20);

        super.renderButton(context, mouseX, mouseY, delta);

        if (this.isHovered()) {
            this.renderTooltip(context, mouseX, mouseY);
        }
    }

    @Override
    protected void applyValue() {
        T v = getValue();
        if (v != oldValue) {
            oldValue = v;
            onApplyValue.accept(v);
        }
    }

    public void renderTooltip(DrawContext context, int mouseX, int mouseY) {
        assert MinecraftClient.getInstance() != null;
        context.drawTooltip(MinecraftClient.getInstance().textRenderer, FlowingText.wrap(tooltipSupplier.get(), 160), mouseX, mouseY);
    }
}
