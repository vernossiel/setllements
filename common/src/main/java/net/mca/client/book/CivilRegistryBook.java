package net.mca.client.book;

import net.mca.client.book.pages.Page;
import net.mca.client.book.pages.SimpleListPage;
import net.mca.client.book.pages.TitlePage;
import net.mca.cobalt.network.NetworkHandler;
import net.mca.network.c2s.CivilRegistryPageRequest;
import net.mca.util.localization.FlowingText;
import net.minecraft.text.Text;

import java.util.*;

public class CivilRegistryBook extends Book {
    Set<Integer> requestedPages = new HashSet<>();
    HashMap<Integer, Page> loadedPages = new HashMap<>();
    int lastIndex = 0;

    Page EMPTY = new TitlePage("...", "");

    public CivilRegistryBook(String bookName, Text bookAuthor) {
        super(bookName, bookAuthor);
    }

    @Override
    public void open() {
        super.open();

        requestedPages.clear();
        loadedPages.clear();
        lastIndex = 0;
    }

    @Override
    public int getPageCount() {
        return 9999;
    }

    @Override
    public boolean showPageCount() {
        return false;
    }

    @Override
    public Page getPage(int index) {
        if (!requestedPages.contains(index) && (requestedPages.contains(index - 1) || index == 0)) {
            requestedPages.add(index);
            NetworkHandler.sendToServer(new CivilRegistryPageRequest(index, lastIndex, lastIndex + 14));
        }

        if (loadedPages.containsKey(index)) {
            return loadedPages.get(index);
        } else {
            return EMPTY;
        }
    }

    public void receive(int index, List<Text> lines) {
        List<Text> text = new LinkedList<>();
        for (Text line : lines) {
            List<Text> wrap = FlowingText.wrap(line, 110);
            if (text.size() + wrap.size() > 14) {
                break;
            }
            int i = 0;
            for (Text l : wrap) {
                if (i == 0) {
                    text.add(l);
                } else {
                    text.add(Text.literal(" ").append(l));
                }
                i++;
            }
            lastIndex++;
        }

        loadedPages.put(index, new SimpleListPage(text));
    }
}
