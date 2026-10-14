package net.mca.client.book.pages;

import net.minecraft.text.Text;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

public class DynamicListPage extends CenteredListPage {
    private final Function<Page, List<Text>> generator;

    public DynamicListPage(String title, Function<Page, List<Text>> generator) {
        super(title, new LinkedList<>());

        this.generator = generator;
    }

    @Override
    public void open(boolean back) {
        text.clear();
        text.addAll(generator.apply(this));

        super.open(back);
    }
}
