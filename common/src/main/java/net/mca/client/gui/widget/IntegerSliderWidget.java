package net.mca.client.gui.widget;

import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class IntegerSliderWidget extends ExtendedSliderWidget<Integer> {
    private final int min;
    private final int max;
    private final Function<Integer, Text> textFunction;

    public IntegerSliderWidget(int x, int y, int width, int height, double value, int min, int max, Consumer<Integer> onApplyValue, Function<Integer, Text> function, Supplier<Text> tooltipSupplier) {
        super(x, y, width, height, Text.literal(""), (value - min) / (max - min), onApplyValue, tooltipSupplier);
        this.min = min;
        this.max = max;
        textFunction = function;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(textFunction.apply(getValue()));
    }

    @Override
    Integer getValue() {
        return (int)(value * (max - min + 1.0) + min - 0.5);
    }

    @Override
    protected double getOpticalValue() {
        return ((double)getValue() - min) / (max - min);
    }
}
