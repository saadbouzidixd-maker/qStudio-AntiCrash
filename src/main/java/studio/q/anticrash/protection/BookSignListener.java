package studio.q.anticrash.protection;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.meta.BookMeta;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;

import java.util.List;
import java.util.Locale;

/**
 * Book and sign protection. Intercepts the player-authored text at the Bukkit
 * event layer before the server persists it into the item or the tile entity:
 * - PlayerEditBookEvent: writable/signed book edits.
 * - SignChangeEvent: sign text lines.
 *
 * Both events are cancellable (verified), so oversized/absurd data is
 * rejected before any storage or broadcast happens.
 */
public final class BookSignListener implements Listener {
    private final ProtectionEngine engine;
    private final ModuleToggles toggles;
    private final Limits limits;

    public BookSignListener(ProtectionEngine engine, ModuleToggles toggles, Limits limits) {
        this.engine = engine;
        this.toggles = toggles;
        this.limits = limits;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!toggles.books) {
            return;
        }
        BookMeta meta = event.getNewBookMeta();
        if (meta == null) {
            return;
        }
        List<String> pages = meta.getPages();
        int pageCount = pages == null ? 0 : pages.size();
        int totalPages = pageCount;
        int maxPages = limits.maxBookPages();
        long totalChars = 0;
        int longestPage = 0;
        if (pages != null) {
            for (String p : pages) {
                int len = p == null ? 0 : p.length();
                totalChars += len;
                if (len > longestPage) {
                    longestPage = len;
                }
            }
        }
        String title = meta.hasTitle() ? meta.getTitle() : "";

        if (pageCount > maxPages) {
            event.setCancelled(true);
            detect(event, "book-pages", "Book page count exceeds limit",
                    pageCount + " pages", "max-pages=" + maxPages, Severity.HIGH);
            return;
        }
        long maxTotal = limits.maxBookTotalLength();
        if (totalChars > maxTotal) {
            event.setCancelled(true);
            detect(event, "book-size", "Book total text size exceeds limit",
                    totalChars + " chars", "max-total-length=" + maxTotal, Severity.HIGH);
            return;
        }
        int maxPage = limits.maxBookPageLength();
        if (longestPage > maxPage) {
            event.setCancelled(true);
            detect(event, "book-page-size", "Single book page exceeds limit",
                    longestPage + " chars", "max-page-length=" + maxPage, Severity.MEDIUM);
            return;
        }
        int maxTitle = limits.maxBookTitleLength();
        if (title != null && title.length() > maxTitle) {
            event.setCancelled(true);
            detect(event, "book-title-size", "Book title exceeds limit",
                    title.length() + " chars", "max-title-length=" + maxTitle, Severity.MEDIUM);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!toggles.signs) {
            return;
        }
        String[] lines = event.getLines();
        int maxLine = limits.maxSignLineLength();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int len = line == null ? 0 : line.length();
            if (len > maxLine) {
                event.setCancelled(true);
                Detection d = Detection.builder(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                                "SignGuard", "sign-oversize")
                        .detail("Sign line exceeds limit")
                        .actual("line " + (i + 1) + " length=" + len)
                        .limit("max-sign-line=" + maxLine)
                        .severity(Severity.MEDIUM)
                        .world(event.getBlock().getWorld().getName())
                        .build();
                engine.handle(d);
                return;
            }
        }
    }

    private void detect(PlayerEditBookEvent event, String rule, String detail,
                        String actual, String limit, Severity severity) {
        Detection d = Detection.builder(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                        "BookGuard", rule)
                .detail(detail)
                .actual(actual)
                .limit(limit)
                .severity(severity)
                .build();
        engine.handle(d);
    }

    /** Configured limits bridge (implemented by the plugin). */
    public interface Limits {
        int maxBookPages();

        int maxBookPageLength();

        long maxBookTotalLength();

        int maxBookTitleLength();

        int maxSignLineLength();
    }
}
