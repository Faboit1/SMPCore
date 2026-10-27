package net.siftvanilla.siftcore.ui.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A six-row list GUI with the standard layout used everywhere:
 * <pre>
 *  rows 1-5 : entries, left to right, top to bottom (45 per page)
 *  slot 45  : previous page        slot 53 : next page
 *  slot 46  : back (when there is a parent)
 *  slot 47  : sort                 slot 48 : filter
 *  slot 49  : search (left click to search, right click to clear)
 *  slots 50-52 : extra buttons of the specific menu
 * </pre>
 * Paging, sorting, filtering and searching behave identically in every menu.
 */
public abstract class PagedMenu<T> extends Menu {

    public static final int PAGE_SIZE = 45;
    public static final int SLOT_PREVIOUS = 45;
    public static final int SLOT_BACK = 46;
    public static final int SLOT_SORT = 47;
    public static final int SLOT_FILTER = 48;
    public static final int SLOT_SEARCH = 49;
    public static final int SLOT_EXTRA_1 = 50;
    public static final int SLOT_EXTRA_2 = 51;
    public static final int SLOT_EXTRA_3 = 52;
    public static final int SLOT_NEXT = 53;

    private final Cycle<Comparator<T>> sort;
    private final Cycle<Predicate<T>> filter;
    private final Runnable back;
    private String query = "";
    private int page;
    private List<T> shown = List.of();

    /**
     * @param sort   sort options, or null for none
     * @param filter filter options, or null for none
     * @param back   what the back button does, or null for no back button
     */
    protected PagedMenu(MenuContext ctx, Player viewer, Component title, Cycle<Comparator<T>> sort,
                        Cycle<Predicate<T>> filter, Runnable back) {
        super(ctx, viewer, title, 6);
        this.sort = sort;
        this.filter = filter;
        this.back = back;
    }

    /** The full, unfiltered list. Called on every redraw; keep it cheap (a snapshot of in-memory state). */
    protected abstract List<T> entries();

    /** The icon of an entry. */
    protected abstract ItemStack icon(T entry);

    /** Handles a click on an entry. */
    protected abstract void clicked(T entry, ClickContext click);

    /** Lowercase text an entry is searched by; return null to make the menu unsearchable. */
    protected String searchText(T entry) {
        return null;
    }

    /** Draws menu-specific buttons into slots 50-52. */
    protected void drawExtras() {
    }

    public String query() {
        return this.query;
    }

    public void query(String query) {
        this.query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        this.page = 0;
    }

    public int page() {
        return this.page;
    }

    private boolean searchable(List<T> all) {
        return all.isEmpty() || searchText(all.getFirst()) != null;
    }

    @Override
    protected final void draw() {
        List<T> all = entries();
        List<T> visible = new ArrayList<>(all.size());
        Predicate<T> predicate = this.filter == null ? null : this.filter.value();
        boolean searchable = searchable(all);
        for (T entry : all) {
            if (predicate != null && !predicate.test(entry)) {
                continue;
            }
            if (!this.query.isEmpty() && searchable) {
                String text = searchText(entry);
                if (text == null || !text.contains(this.query)) {
                    continue;
                }
            }
            visible.add(entry);
        }
        if (this.sort != null) {
            visible.sort(this.sort.value());
        }
        this.shown = visible;
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Math.clamp(this.page, 0, pages - 1);
        int from = this.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = from + i;
            if (index < visible.size()) {
                T entry = visible.get(index);
                set(i, icon(entry), click -> clicked(entry, click));
            } else {
                clear(i);
            }
        }
        if (visible.isEmpty()) {
            set(22, Items.icon(Material.PAPER, this.ctx.lang().get(CoreMessages.UI_EMPTY),
                this.ctx.lang().lines(CoreMessages.UI_EMPTY_LORE)), null);
        }
        drawNavigation(pages, searchable);
        drawExtras();
    }

    private void drawNavigation(int pages, boolean searchable) {
        if (this.page > 0) {
            set(SLOT_PREVIOUS, Items.icon(Material.ARROW, this.ctx.lang().get(CoreMessages.UI_PREVIOUS_PAGE),
                this.ctx.lang().lines(CoreMessages.UI_PREVIOUS_PAGE_LORE, Arg.number("page", this.page), Arg.number("pages", pages))),
                click -> {
                    this.page--;
                    click(Feedback.CLICK);
                    redraw();
                });
        } else {
            clear(SLOT_PREVIOUS);
        }
        if (this.page < pages - 1) {
            set(SLOT_NEXT, Items.icon(Material.ARROW, this.ctx.lang().get(CoreMessages.UI_NEXT_PAGE),
                this.ctx.lang().lines(CoreMessages.UI_NEXT_PAGE_LORE, Arg.number("page", this.page + 2), Arg.number("pages", pages))),
                click -> {
                    this.page++;
                    click(Feedback.CLICK);
                    redraw();
                });
        } else {
            clear(SLOT_NEXT);
        }
        if (this.back != null) {
            set(SLOT_BACK, Items.icon(Material.OAK_DOOR, this.ctx.lang().get(CoreMessages.UI_BACK), List.of()), click -> {
                click(Feedback.CLICK);
                this.back.run();
            });
        } else {
            clear(SLOT_BACK);
        }
        if (this.sort != null) {
            set(SLOT_SORT, cycleIcon(Material.HOPPER, this.ctx.lang().get(CoreMessages.UI_SORT), this.sort), click -> {
                this.sort.step(click);
                this.page = 0;
                click(Feedback.CLICK);
                redraw();
            });
        } else {
            clear(SLOT_SORT);
        }
        if (this.filter != null) {
            set(SLOT_FILTER, cycleIcon(Material.COMPARATOR, this.ctx.lang().get(CoreMessages.UI_FILTER), this.filter), click -> {
                this.filter.step(click);
                this.page = 0;
                click(Feedback.CLICK);
                redraw();
            });
        } else {
            clear(SLOT_FILTER);
        }
        if (searchable) {
            List<Component> lore = this.query.isEmpty()
                ? this.ctx.lang().lines(CoreMessages.UI_SEARCH_LORE)
                : this.ctx.lang().lines(CoreMessages.UI_SEARCH_ACTIVE_LORE, Arg.text("query", this.query));
            set(SLOT_SEARCH, Items.icon(Material.OAK_SIGN, this.ctx.lang().get(CoreMessages.UI_SEARCH), lore), click -> {
                click(Feedback.CLICK);
                if (click.right() && !this.query.isEmpty()) {
                    query("");
                    redraw();
                    return;
                }
                openSearch();
            });
        } else {
            clear(SLOT_SEARCH);
        }
    }

    /** Builds a cycle button: white name, every option gray with a bullet, the selected one white. */
    protected final ItemStack cycleIcon(Material material, Component name, Cycle<?> cycle) {
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < cycle.options().size(); i++) {
            Component label = cycle.options().get(i).label();
            lore.add(this.ctx.lang().get(i == cycle.index() ? CoreMessages.UI_OPTION_SELECTED : CoreMessages.UI_OPTION,
                Arg.component("option", label)));
        }
        lore.add(Component.empty());
        lore.addAll(this.ctx.lang().lines(CoreMessages.UI_CYCLE_HINT));
        return Items.icon(material, name, lore);
    }

    private void openSearch() {
        Player player = this.viewer;
        this.ctx.dialogs().show(player, this.ctx.templates().form(
            this.ctx.lang().get(CoreMessages.UI_SEARCH_TITLE),
            List.of(),
            List.of(Templates.text("query", this.ctx.lang().get(CoreMessages.UI_SEARCH_INPUT), this.query, 48)),
            submission -> {
                query(submission.values().text("query"));
                submission.close();
                open();
            },
            submission -> {
                submission.close();
                open();
            }).waiting());
    }

    /** The entries currently visible after filter, search and sort. */
    protected final List<T> shown() {
        return this.shown;
    }
}
