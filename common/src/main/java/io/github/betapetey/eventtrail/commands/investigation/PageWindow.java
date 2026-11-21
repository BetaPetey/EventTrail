package io.github.betapetey.eventtrail.commands.investigation;

import java.util.List;

/** An immutable, validated view of exactly one page. */
public record PageWindow<T>(
        int page,
        int totalPages,
        int perPage,
        long totalItems,
        List<T> items) {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 50;

    public PageWindow {
        validatePageSize(perPage);
        if (totalItems < 0L) throw new IllegalArgumentException("Total items cannot be negative");
        int expectedPages = pageCount(totalItems, perPage);
        if (totalPages != expectedPages) {
            throw new IllegalArgumentException("Total page count does not match total items");
        }
        if (page < 1 || page > totalPages) {
            throw new IllegalArgumentException("Requested page is outside the result window");
        }
        items = List.copyOf(items);
        long expectedItems = expectedItemCount(totalItems, page, perPage);
        if (items.size() != expectedItems) {
            throw new IllegalArgumentException("Current-page item count does not match its boundaries");
        }
    }

    /** Slices an in-memory result snapshot without exposing its mutable list. */
    public static <T> PageWindow<T> slice(List<T> allItems, int page, int perPage) {
        List<T> snapshot = List.copyOf(allItems);
        validatePageSize(perPage);
        int pages = pageCount(snapshot.size(), perPage);
        if (page < 1 || page > pages) {
            throw new IllegalArgumentException("Requested page is outside the result window");
        }
        int start = Math.toIntExact(Math.min(snapshot.size(), (long) (page - 1) * perPage));
        int end = Math.min(snapshot.size(), start + perPage);
        return new PageWindow<>(page, pages, perPage, snapshot.size(), snapshot.subList(start, end));
    }

    /** Validates a page already sliced by persistent storage. */
    public static <T> PageWindow<T> fromPage(
            List<T> pageItems, long totalItems, int page, int perPage) {
        return new PageWindow<>(page, pageCount(totalItems, perPage), perPage, totalItems, pageItems);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }

    public long firstOrdinal() {
        return items.isEmpty() ? 0L : (long) (page - 1) * perPage + 1L;
    }

    public long lastOrdinal() {
        return items.isEmpty() ? 0L : firstOrdinal() + items.size() - 1L;
    }

    public long ordinal(int itemIndex) {
        if (itemIndex < 0 || itemIndex >= items.size()) {
            throw new IndexOutOfBoundsException(itemIndex);
        }
        return firstOrdinal() + itemIndex;
    }

    private static int pageCount(long totalItems, int perPage) {
        validatePageSize(perPage);
        if (totalItems < 0L) throw new IllegalArgumentException("Total items cannot be negative");
        if (totalItems == 0L) return 1;
        long pages = ((totalItems - 1L) / perPage) + 1L;
        if (pages > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Result has more pages than EventTrail can address");
        }
        return (int) pages;
    }

    private static long expectedItemCount(long totalItems, int page, int perPage) {
        if (totalItems == 0L) return 0L;
        long firstOffset = (long) (page - 1) * perPage;
        return Math.min(perPage, totalItems - firstOffset);
    }

    private static void validatePageSize(int perPage) {
        if (perPage < MIN_PAGE_SIZE || perPage > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE);
        }
    }
}
