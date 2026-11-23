package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.storage.AdvancedQuery;
import io.github.betapetey.eventtrail.storage.AdvancedQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of grouped lookup lines, and the one method that reads it.
 *
 * <p>{@link #read} makes the scan (page 1 of the frozen query at page size
 * {@code scanRows}), groups it with {@link LookupGrouper}, and slices the
 * requested line page. Rows older than the scan show one line per row, so the
 * line total is {@code scanLines + (total - scannedRows)}. A line page that
 * reaches past the scan's lines takes its remaining rows from at most two
 * ordinary page reads of the frozen query at its own per-page: the pages that
 * hold SQL offsets {@code firstOffset} and {@code endOffset - 1}. A read that
 * comes back with another page number than asked for (storage moved a page
 * past the end back to the last page) contributes no rows. Retention deletes
 * remove the oldest rows first, so a row whose offset is not below the
 * smallest total among the reads no longer exists and is not shown; the row
 * and line totals use that smallest total.</p>
 *
 * <p>If deletes between the scan and a later read remove the whole requested
 * page, the page would lie past the shrunk result's last page. The whole read
 * (scan, then the reads past it) then runs once more; its scan sees the shrunk
 * total and moves the page back into range as storage does for rows. In that
 * rare race one page takes up to six storage reads instead of three. If the
 * second attempt is still inconsistent, {@link #read} throws an
 * {@link IllegalStateException} and returns no page.</p>
 *
 * <p>Each read is its own storage read with its own queue-wait limit, so a
 * page past the scan can still fail on its second or third read after the scan
 * has run; any read that throws fails the whole page (the exception
 * propagates, the caller shows today's error or busy text, and no partial page
 * is returned).</p>
 *
 * @param lines the requested page's lines, in order
 * @param rowTotal stored rows in the result (the smallest total among the reads)
 * @param lineTotal lines in the result
 * @param page the line page shown (the requested page moved into range, as storage does for rows)
 * @param totalPages line pages at {@code perPage}
 * @param perPage lines per page
 * @param scannedRows rows the scan read
 * @param scanLines lines the scanned rows form
 */
public record GroupedLookupPage(
        List<LookupGrouper.Line> lines,
        long rowTotal,
        long lineTotal,
        int page,
        int totalPages,
        int perPage,
        int scannedRows,
        int scanLines) {

    /** R_MAX: rows in one grouping scan (PROVISIONAL). */
    public static final int SCAN_ROWS = 1_000;

    public GroupedLookupPage {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (rowTotal < 0L || lineTotal < 0L) throw new IllegalArgumentException("Totals cannot be negative");
        if (page < 1 || totalPages < 1 || perPage < 1) {
            throw new IllegalArgumentException("Page values must be positive");
        }
        if (page > totalPages) throw new IllegalArgumentException("The page cannot be past the last page");
        if (lines.size() > perPage) throw new IllegalArgumentException("A page cannot hold more than perPage lines");
        if (scannedRows < 0 || scanLines < 0 || scanLines > scannedRows) {
            throw new IllegalArgumentException("Scan counts are inconsistent");
        }
    }

    /** The {@code [#n]} number of the line at {@code index} on this page. */
    public long ordinal(int index) {
        if (index < 0 || index >= lines.size()) throw new IndexOutOfBoundsException(index);
        return (long) (page - 1) * perPage + index + 1L;
    }

    /** Reads with the design values W, S and R_MAX. */
    public static GroupedLookupPage read(
            Function<AdvancedQuery, AdvancedQueryResult> reader,
            AdvancedQuery frozenQuery) {
        return read(reader, frozenQuery,
                LookupGrouper.GAP_LIMIT_MS, LookupGrouper.SPAN_LIMIT_MS, SCAN_ROWS);
    }

    /**
     * Reads the line page {@code frozenQuery.page()} at {@code frozenQuery.perPage()} lines per page.
     *
     * @param reader storage's {@code queryAdvanced}
     * @param frozenQuery the session's query (time window and high-water id fixed)
     * @param gapLimitMs W
     * @param spanLimitMs S
     * @param scanRows R_MAX
     * @throws IllegalStateException when deletes still push the page past the last page on the second attempt
     */
    public static GroupedLookupPage read(
            Function<AdvancedQuery, AdvancedQueryResult> reader,
            AdvancedQuery frozenQuery,
            long gapLimitMs,
            long spanLimitMs,
            int scanRows) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(frozenQuery, "frozenQuery");
        if (scanRows < 1) throw new IllegalArgumentException("scanRows must be positive");
        GroupedLookupPage first = readOnce(reader, frozenQuery, gapLimitMs, spanLimitMs, scanRows);
        if (first != null) return first;
        // Deletes removed the whole requested page after the scan: read everything once more.
        GroupedLookupPage second = readOnce(reader, frozenQuery, gapLimitMs, spanLimitMs, scanRows);
        if (second != null) return second;
        throw new IllegalStateException("The lookup result changed while its page was being read");
    }

    /** One attempt; null when the requested page ended up past the last page. */
    private static GroupedLookupPage readOnce(
            Function<AdvancedQuery, AdvancedQueryResult> reader,
            AdvancedQuery frozenQuery,
            long gapLimitMs,
            long spanLimitMs,
            int scanRows) {
        int perPage = Math.max(1, frozenQuery.perPage());

        // Only the page's scan lines survive this call; the scan rows are released
        // before any read past the scan.
        ScanSlice slice = scanAndSlice(reader, frozenQuery, gapLimitMs, spanLimitMs, scanRows, perPage);

        long smallestTotal = slice.total();
        List<LookupGrouper.Line> pageLines = new ArrayList<>(slice.scanPart());
        if (slice.lineEnd() > slice.scanLines()) {
            long firstOffset = slice.scannedRows() + Math.max(0L, slice.lineStart() - slice.scanLines());
            long endOffset = slice.scannedRows() + (slice.lineEnd() - slice.scanLines());
            int firstPage = pageHolding(firstOffset, perPage);
            int lastPage = pageHolding(endOffset - 1L, perPage);
            List<OffsetRow> tail = new ArrayList<>();
            for (int readPage = firstPage; readPage <= lastPage; readPage++) {
                AdvancedQueryResult result = Objects.requireNonNull(
                        reader.apply(frozenQuery.withPage(readPage)), "read result");
                smallestTotal = Math.min(smallestTotal, result.total());
                if (result.page() != readPage) continue;
                long base = (long) (readPage - 1) * perPage;
                List<AuditRecord> entries = result.entries();
                for (int index = 0; index < entries.size(); index++) {
                    long offset = base + index;
                    if (offset >= firstOffset && offset < endOffset) {
                        tail.add(new OffsetRow(offset, entries.get(index)));
                    }
                }
            }
            for (OffsetRow row : tail) {
                if (row.offset() < smallestTotal) {
                    pageLines.add(LookupGrouper.Line.single(row.offset(), row.record()));
                }
            }
        }

        long lineTotal = slice.scanLines() + Math.max(0L, smallestTotal - slice.scannedRows());
        int totalPages = pageCount(lineTotal, perPage);
        if (slice.page() > totalPages) return null;
        return new GroupedLookupPage(
                pageLines,
                Math.max(0L, smallestTotal),
                lineTotal,
                slice.page(),
                totalPages,
                perPage,
                slice.scannedRows(),
                slice.scanLines());
    }

    private static ScanSlice scanAndSlice(
            Function<AdvancedQuery, AdvancedQueryResult> reader,
            AdvancedQuery frozenQuery,
            long gapLimitMs,
            long spanLimitMs,
            int scanRows,
            int perPage) {
        AdvancedQueryResult scan = Objects.requireNonNull(
                reader.apply(pageQuery(frozenQuery, scanRows, 1)), "scan result");
        List<AuditRecord> rows = scan.entries();
        long total = scan.total();
        List<LookupGrouper.Line> lines = LookupGrouper.group(
                rows, gapLimitMs, spanLimitMs, frozenQuery.fromTime(), total > rows.size());

        long lineTotal = lines.size() + Math.max(0L, total - rows.size());
        int page = Math.max(1, Math.min(frozenQuery.page(), pageCount(lineTotal, perPage)));
        long lineStart = (long) (page - 1) * perPage;
        long lineEnd = Math.min(lineStart + perPage, lineTotal);
        List<LookupGrouper.Line> scanPart = lineStart < lines.size()
                ? List.copyOf(lines.subList((int) lineStart, (int) Math.min(lineEnd, lines.size())))
                : List.of();
        return new ScanSlice(scanPart, total, rows.size(), lines.size(), page, lineStart, lineEnd);
    }

    /** The frozen query with another page size and page; every other field unchanged. */
    private static AdvancedQuery pageQuery(AdvancedQuery query, int perPage, int page) {
        return query.withPaging(perPage, page);
    }

    private static int pageHolding(long offset, int perPage) {
        return Math.toIntExact(offset / perPage + 1L);
    }

    /** As storage counts pages: one page for an empty result. */
    private static int pageCount(long items, int perPage) {
        if (items <= 0L) return 1;
        return (int) Math.min(Integer.MAX_VALUE, (items - 1L) / perPage + 1L);
    }

    private record ScanSlice(
            List<LookupGrouper.Line> scanPart,
            long total,
            int scannedRows,
            int scanLines,
            int page,
            long lineStart,
            long lineEnd) {
    }

    private record OffsetRow(long offset, AuditRecord record) {
    }
}
