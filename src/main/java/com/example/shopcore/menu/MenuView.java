package com.example.shopcore.menu;

/** 当前 GUI 的视图状态。翻页/返回只改这里，不重新创建整个菜单对象。 */
public final class MenuView {

    private final MenuType type;
    private final SelectionMode selectionMode;
    private final String contextId;
    private int page;

    public MenuView(MenuType type) {
        this(type, 0, null, null);
    }

    public MenuView(MenuType type, int page, String contextId, SelectionMode selectionMode) {
        this.type = type;
        this.page = Math.max(0, page);
        this.contextId = contextId;
        this.selectionMode = selectionMode;
    }

    public MenuType type() {
        return type;
    }

    public int page() {
        return page;
    }

    public void page(int page) {
        this.page = Math.max(0, page);
    }

    public String contextId() {
        return contextId;
    }

    public SelectionMode selectionMode() {
        return selectionMode;
    }
}
