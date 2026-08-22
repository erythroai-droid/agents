package ai.erythro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Rebuilds PDF/HTML from existing reports/audit_data.json (no live crawl).
 */
public class RegeneratePdfFromData {

    private static final String HTML_FILE = "reports/audit-report.html";
    private static final String PDF_FILE = "reports/audit-report.pdf";
    private static final String PDF_FALLBACK = "reports/audit-report.locked.pdf";

    public static void main(String[] args) throws Exception {
        Path dataPath = Paths.get("reports/audit_data.json");
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = mapper.readValue(dataPath.toFile(), Map.class);

        String targetUrl = String.valueOf(report.getOrDefault("target_url", "https://erythro.ai/"));
        @SuppressWarnings("unchecked")
        List<String> locales = (List<String>) report.getOrDefault("locales_audited", List.of("en", "ru", "he"));

        // Reuse AuditCollector private logic via reflection-free duplicate call path:
        // generateHtmlReport is private — invoke through package-visible rebuild helper.
        String html = AuditCollector.rebuildHtmlFromReport(report, targetUrl, locales);
        Files.writeString(Paths.get(HTML_FILE), html);
        System.out.println("[✓] HTML: " + HTML_FILE);

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setContent(html);
            page.emulateMedia(new Page.EmulateMediaOptions().setMedia(com.microsoft.playwright.options.Media.PRINT));
            page.evaluate("document.fonts.ready");

            Path out = Paths.get(PDF_FILE).toAbsolutePath();
            try {
                page.pdf(AuditCollector.pdfOptions(out));
                System.out.println("[✓] PDF: " + out);
            } catch (Exception e) {
                Path fallback = Paths.get(PDF_FALLBACK).toAbsolutePath();
                page.pdf(AuditCollector.pdfOptions(fallback));
                System.out.println("[!] Файл занят, PDF сохранен как: " + fallback);
            }
            browser.close();
        }
    }
}
