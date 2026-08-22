package ai.erythro;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Генератор PDF-отчета в макете Figma (A4: чёрный сайдбар, красные шапка/подвал, белая колонка).
 * Используется и как шаблон (main), и аудитором {@link AuditCollector}.
 */
public class ReportTemplateGenerator {

    private static final String TEMPLATE_HTML_FILE = "reports/audit_template_white.html";
    private static final String TEMPLATE_PDF_FILE = "reports/audit_template_white.pdf";
    /** Used when TEMPLATE_PDF_FILE is locked by a viewer; fixed name so it never accumulates. */
    private static final String TEMPLATE_PDF_FALLBACK = "reports/audit_template_white.locked.pdf";

    public static void main(String[] args) throws IOException {
        System.out.println("=================================================================");
        System.out.println("📄 Erythro.ai Commercial Audit — Динамическая генерация PDF");
        System.out.println("=================================================================");

        String targetUrl = "https://www.example.com";
        List<String> locales = List.of("en", "ru", "he");
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");
        String dateStr = sdf.format(new Date());

        List<Map<String, String>> findings = List.of(
                Map.of(
                        "title", "Потеря 25–40% мобильного рекламного трафика",
                        "issue", "Высокое время загрузки Largest Contentful Paint (LCP > 4.0s) на мобильных сетях 4G.",
                        "impact", "Пользователи закрывают сайт до первого экрана, сжигая бюджет Google / Meta Ads.",
                        "solution", "Мобильная оптимизация Core Web Vitals, сжатие Next-Gen WebP/AVIF и кэширование CDN до LCP < 1.5s."
                ),
                Map.of(
                        "title", "Риск срыва лидогенерации и зависания входящих заявок",
                        "issue", "Лид-формы не оснащены невидимой защитой Turnstile и мгновенным AI-автоответчиком.",
                        "impact", "Риск утери «горячих» заявок из-за спам-ботов или долгой ручной обработки свыше 15 минут.",
                        "solution", "Подключение умных AI-агентов (n8n/CRM) для мгновенной квалификации и ответа в Telegram 24/7."
                ),
                Map.of(
                        "title", "Несоответствие закону о доступности (IS 5568 / WCAG 2.1 AA)",
                        "issue", "Отсутствие aria-лейблов на кнопках мобильного меню и некорректное RTL-выравнивание заголовков на иврите.",
                        "impact", "Риск юридических претензий и снижение конверсии региональной аудитории.",
                        "solution", "Комплексный аудит доступности, исправление RTL-зеркалирования и сертификация по стандарту IS 5568."
                )
        );

        List<Map<String, String>> lighthouse = List.of(
                lighthouseRow("MOBILE (iPhone SE)", "72", "54", "100", "100", "2.1 s", "3.4 s", "0.02"),
                lighthouseRow("DESKTOP", "96", "98", "100", "100", "0.7 s", "1.1 s", "0.00")
        );

        List<Map<String, String>> checks = List.of(
                checkRow("Паразитный боковой скролл (Overflow-X на 375px)", "Идеально подогнано под экран смартфона (0px перелива)", "good", "Отлично"),
                checkRow("RTL-зеркалирование и верстка для Израиля (Иврит)", "Тег dir=\"rtl\" активен, выравнивание справа настроено", "good", "Норма"),
                checkRow("SSL / HTTPS шифрование & Security Headers", "HTTPS включен, HSTS и CSP активны", "good", "Защищено"),
                checkRow("Служебные файлы индексации (robots.txt / sitemap.xml)", "robots.txt: 200 OK | sitemap.xml: 200 OK", "good", "В норме")
        );

        String htmlContent = buildAuditHtml(
                targetUrl, dateStr, String.join(", ", locales).toUpperCase(),
                85, "A", 80, 94, 70, 40, 88,
                findings, lighthouse, checks
        );

        File reportsDir = new File("reports");
        if (!reportsDir.exists()) reportsDir.mkdirs();

        Files.writeString(Paths.get(TEMPLATE_HTML_FILE), htmlContent);
        System.out.println("[✓] HTML-отчет сохранен: `" + TEMPLATE_HTML_FILE + "`");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setContent(htmlContent);
            page.emulateMedia(new Page.EmulateMediaOptions().setMedia(com.microsoft.playwright.options.Media.PRINT));
            page.evaluate("document.fonts.ready");

            Path pdfPath = Paths.get(TEMPLATE_PDF_FILE).toAbsolutePath();
            try {
                page.pdf(new Page.PdfOptions()
                        .setPath(pdfPath)
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setPreferCSSPageSize(true)
                        .setMargin(new Margin().setTop("0mm").setBottom("0mm").setLeft("0mm").setRight("0mm"))
                );
                System.out.println("[✓] Превью шаблона сгенерировано: `" + pdfPath + "`");
            } catch (Exception e) {
                Path fallback = Paths.get(TEMPLATE_PDF_FALLBACK).toAbsolutePath();
                page.pdf(new Page.PdfOptions()
                        .setPath(fallback)
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setPreferCSSPageSize(true)
                        .setMargin(new Margin().setTop("0mm").setBottom("0mm").setLeft("0mm").setRight("0mm"))
                );
                System.out.println("[!] Файл занят, превью сохранено как: `" + fallback + "`");
            }
            browser.close();
        } catch (Exception e) {
            System.err.println("[-] Ошибка генерации PDF: " + e.getMessage());
        }

        System.out.println("=================================================================");
        System.out.println("✅ Готово! Превью шаблона обновлено (демо-данные, боевой отчет не затронут).");
        System.out.println("=================================================================");
    }

    public static Map<String, String> lighthouseRow(String device, String perf, String a11y, String bp, String seo,
                                                    String fcp, String lcp, String cls) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("device", device);
        row.put("performance", perf);
        row.put("accessibility", a11y);
        row.put("best_practices", bp);
        row.put("seo", seo);
        row.put("fcp", fcp);
        row.put("lcp", lcp);
        row.put("cls", cls);
        return row;
    }

    public static Map<String, String> checkRow(String param, String result, String status, String label) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("param", param);
        row.put("result", result);
        row.put("status", status);
        row.put("label", label);
        return row;
    }

    public static String buildAuditHtml(
            String targetUrl,
            String dateStr,
            String localesStr,
            int overallScore,
            String grade,
            int speedScore,
            int seoScore,
            int leadScore,
            int secScore,
            int aiVisScore,
            List<Map<String, String>> topVulnerabilities,
            List<Map<String, String>> lighthouseRows,
            List<Map<String, String>> checkRows
    ) {
        String displayUrl = displayHost(targetUrl);

        String[] idxColors = {"#6b6254", "#8c806d", "#b5a58d"};
        StringBuilder findingsHtml = new StringBuilder();
        int findingCount = Math.min(3, topVulnerabilities == null ? 0 : topVulnerabilities.size());
        for (int i = 0; i < findingCount; i++) {
            Map<String, String> v = topVulnerabilities.get(i);
            findingsHtml.append("<article class=\"finding\">")
                    .append("<div class=\"finding-idx\" style=\"background:").append(idxColors[i]).append(";\">")
                    .append(String.format("%02d", i + 1)).append("</div>")
                    .append("<div class=\"finding-body\">")
                    .append("<div class=\"finding-row\">")
                    .append("<span class=\"ico ico-alert\">").append(iconWarn()).append("</span>")
                    .append("<div><span class=\"finding-k finding-k-red\">Проблема:</span> ")
                    .append("<span class=\"finding-title\">").append(esc(v.get("title"))).append("</span>")
                    .append("<div class=\"finding-p\">").append(esc(v.get("issue"))).append("</div></div></div>")
                    .append("<div class=\"finding-row\">")
                    .append("<span class=\"ico ico-diagram\">").append(iconImpact()).append("</span>")
                    .append("<div><span class=\"finding-k finding-k-orange\">Потери бизнеса:</span> ")
                    .append("<span class=\"finding-p finding-p-inline\">").append(esc(v.get("impact"))).append("</span></div></div>")
                    .append("<div class=\"finding-row\">")
                    .append("<span class=\"ico ico-idea\">").append(iconIdea()).append("</span>")
                    .append("<div><span class=\"finding-k finding-k-green\">Решение от Erythro.ai:</span> ")
                    .append("<span class=\"finding-p finding-p-inline\">").append(esc(v.get("solution"))).append("</span></div></div>")
                    .append("</div></article>");
        }

        StringBuilder lighthouseHtml = new StringBuilder();
        if (lighthouseRows != null && !lighthouseRows.isEmpty()) {
            lighthouseHtml.append("<table class=\"metrics\"><thead><tr>")
                    .append("<th>УСТРОЙСТВО</th><th>PERFOMANCE</th><th>ACCESSEBILITY</th>")
                    .append("<th>BEST PRACTICES</th><th>SEO</th><th>FCP</th><th>LCP</th><th>CLS</th>")
                    .append("</tr></thead><tbody>");
            for (Map<String, String> row : lighthouseRows) {
                lighthouseHtml.append("<tr>")
                        .append("<td class=\"device\">").append(esc(row.get("device"))).append("</td>")
                        .append("<td>").append(scoreBadge(row.get("performance"))).append("</td>")
                        .append("<td>").append(scoreBadge(row.get("accessibility"))).append("</td>")
                        .append("<td>").append(scoreBadge(row.get("best_practices"))).append("</td>")
                        .append("<td>").append(scoreBadge(row.get("seo"))).append("</td>")
                        .append("<td>").append(esc(row.get("fcp"))).append("</td>")
                        .append("<td>").append(esc(row.get("lcp"))).append("</td>")
                        .append("<td>").append(esc(row.get("cls"))).append("</td></tr>");
            }
            lighthouseHtml.append("</tbody></table>");
        }

        StringBuilder checksHtml = new StringBuilder();
        if (checkRows != null && !checkRows.isEmpty()) {
            checksHtml.append("<table class=\"checks-table\"><thead><tr>")
                    .append("<th>ПАРАМЕТР АУДИТА</th><th>РЕЗУЛЬТАТ ПРОВЕРКИ</th><th>СТАТУС</th>")
                    .append("</tr></thead><tbody>");
            for (Map<String, String> r : checkRows) {
                String stClass = switch (String.valueOf(r.get("status")).toLowerCase()) {
                    case "good" -> "status-good";
                    case "warn" -> "status-warn";
                    default -> "status-bad";
                };
                checksHtml.append("<tr>")
                        .append("<td class=\"check-param\">").append(esc(r.get("param"))).append("</td>")
                        .append("<td class=\"check-result\">").append(esc(r.get("result"))).append("</td>")
                        .append("<td class=\"check-status\"><span class=\"status-badge ").append(stClass).append("\">")
                        .append(esc(r.get("label"))).append("</span></td></tr>");
            }
            checksHtml.append("</tbody></table>");
        }

        String template = null;
        for (String path : List.of("templates/audit_template_white.html", "QA_Auditor/templates/audit_template_white.html", "../templates/audit_template_white.html")) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    template = Files.readString(f.toPath());
                    break;
                } catch (Exception ignored) {}
            }
        }

        if (template == null) {
            throw new RuntimeException("templates/audit_template_white.html not found on disk!");
        }

        String res = template;

        String logoSvg = "";
        for (String path : List.of("templates/Logo_digital 1.svg", "QA_Auditor/templates/Logo_digital 1.svg", "../templates/Logo_digital 1.svg")) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    logoSvg = Files.readString(f.toPath());
                    break;
                } catch (Exception ignored) {}
            }
        }

        if (!logoSvg.isEmpty()) {
            int brandStart = res.indexOf("<div class=\"brand\">");
            int brandEnd = res.indexOf("</div>", brandStart);
            if (brandStart != -1 && brandEnd != -1) {
                res = res.substring(0, brandStart) + "<div class=\"brand\">" + logoSvg + res.substring(brandEnd);
            }
        }

        // Full-circle path + pathLength/dasharray — reliable white progress in Chromium PDF
        int scoreClamped = Math.max(0, Math.min(100, overallScore));
        String dashArray = scoreClamped + " " + (100 - scoreClamped);
        String gaugePathTag = "<path class=\"gauge-progress\" d=\"M 70 12 A 58 58 0 1 1 70 128 A 58 58 0 1 1 70 12\" pathLength=\"100\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"5\" stroke-linecap=\"butt\" stroke-dasharray=\"" + dashArray + "\"/>";

        res = res.replace("www.example.com", displayUrl)
                 .replace("{{DISPLAY_URL}}", displayUrl)
                 .replace("20.08.2026", dateStr)
                 .replace("{{DATE_STR}}", dateStr)
                 .replace("EN, RU, HE", localesStr)
                 .replace("{{LOCALES_STR}}", localesStr)
                 .replace("<div class=\"gauge-score\">85</div>", "<div class=\"gauge-score\">" + overallScore + "</div>")
                 .replace("<div class=\"gauge-score\">{{OVERALL}}</div>", "<div class=\"gauge-score\">" + overallScore + "</div>")
                 .replace("<div class=\"gauge-grade\">GRADE:A</div>", "<div class=\"gauge-grade\">GRADE:" + grade + "</div>")
                 .replace("<div class=\"gauge-grade\">GRADE:{{GRADE}}</div>", "<div class=\"gauge-grade\">GRADE:" + grade + "</div>")
                 .replace("style=\"--p: 85;\"", "style=\"--p: " + overallScore + ";\"")
                 .replace("style=\"--p: {{OVERALL}};\"", "style=\"--p: " + overallScore + ";\"");

        int pathStart = res.indexOf("<path class=\"gauge-progress\"");
        if (pathStart != -1) {
            int pathEnd = res.indexOf("/>", pathStart);
            if (pathEnd != -1) {
                res = res.substring(0, pathStart) + gaugePathTag + res.substring(pathEnd + 2);
            }
        }

        StringBuilder chartRowsHtml = new StringBuilder();
        chartRowsHtml.append("<div class=\"chart-rows\">")
                .append(chartRow("скорость и<br>мобильный UX", speedScore))
                .append(chartRow("Seo и видимость", seoScore))
                .append(chartRow("лидогенерация и формы", leadScore))
                .append(chartRow("безопасность и стабильность", secScore))
                .append(chartRow("AI visibility &<br>brand discovery", aiVisScore))
                .append("</div>");

        int rowsStart = res.indexOf("<div class=\"chart-rows\">");
        int ticksBottom = res.indexOf("<div class=\"ticks ticks-bottom\">");
        if (rowsStart != -1 && ticksBottom != -1 && rowsStart < ticksBottom) {
            res = res.substring(0, rowsStart) + chartRowsHtml + "\n                " + res.substring(ticksBottom);
        }

        int findStart = res.indexOf("<div class=\"findings\">");
        if (findStart != -1) {
            String afterFindings = res.substring(findStart);
            int depth = 0;
            int i = 0;
            int endIdx = -1;
            while (i < afterFindings.length()) {
                int open = afterFindings.indexOf("<div", i);
                int close = afterFindings.indexOf("</div>", i);
                if (close == -1) break;
                if (open != -1 && open < close) {
                    depth++;
                    i = open + 4;
                } else {
                    depth--;
                    i = close + 6;
                    if (depth == 0) {
                        endIdx = findStart + i;
                        break;
                    }
                }
            }
            if (endIdx != -1) {
                res = res.substring(0, findStart)
                        + "<div class=\"findings\">\n"
                        + findingsHtml
                        + "\n                </div>"
                        + res.substring(endIdx);
            }
        }

        int tableStart = res.indexOf("<table class=\"metrics\">");
        int tableEnd = tableStart == -1 ? -1 : res.indexOf("</table>", tableStart);
        if (tableStart != -1 && tableEnd != -1) {
            res = res.substring(0, tableStart) + lighthouseHtml.toString() + res.substring(tableEnd + "</table>".length());
        }

        if (checksHtml.length() > 0) {
            int checksStart = res.indexOf("<table class=\"checks-table\">");
            int secondTableEnd = checksStart == -1 ? -1 : res.indexOf("</table>", checksStart);
            if (checksStart != -1 && secondTableEnd != -1) {
                res = res.substring(0, checksStart) + checksHtml.toString() + res.substring(secondTableEnd + "</table>".length());
            }
        }

        return res;
    }

    private static String getBarClass(int score) {
        if (score >= 75) return "bar-green";
        if (score >= 50) return "bar-warn";
        return "bar-bad";
    }

    private static String chartLabel(String label) {
        return "<div class=\"chart-label\">" + label + "</div>";
    }

    private static String chartRow(String label, int score) {
        return "<div class=\"chart-row\">" + chartLabel(label) + chartBar(score) + "</div>";
    }

    private static String chartBar(int score) {
        String fill = getBarClass(score);
        return "<div class=\"bar-track\"><div class=\"bar-fill " + fill + "\" style=\"width:" + score + "%;\"></div></div>";
    }

    private static String scoreBadge(String raw) {
        int score;
        try {
            score = Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return esc(raw);
        }
        String cls = score >= 80 ? "badge-good" : (score >= 60 ? "badge-warn" : "badge-bad");
        return "<span class=\"badge " + cls + "\">" + score + "</span>";
    }

    private static String statusBadge(String status, String label) {
        String cls = "badge-good";
        if ("warn".equals(status)) cls = "badge-warn";
        if ("bad".equals(status)) cls = "badge-bad";
        return "<span class=\"badge " + cls + "\">" + esc(label) + "</span>";
    }

    private static String displayHost(String url) {
        if (url == null) return "";
        return url.replaceFirst("^https?://", "").replaceFirst("/$", "");
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String loadSvgAsset(String name) {
        Path[] candidates = {
                Paths.get("templates/figma-assets/" + name),
                Paths.get("QA_Auditor/templates/figma-assets/" + name),
                Paths.get("../templates/figma-assets/" + name)
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p)
                            .replace("preserveAspectRatio=\"none\"", "")
                            .replace("overflow=\"visible\"", "")
                            .replace("style=\"display: block;\"", "")
                            .replace("clip0_0_48", "clip-idea");
                } catch (IOException ignored) {
                    // fall through to next candidate
                }
            }
        }
        return "";
    }

    private static String iconWarn() {
        return loadSvgAsset("icon-alert.svg");
    }

    private static String iconImpact() {
        return loadSvgAsset("icon-diagram.svg");
    }

    private static String iconIdea() {
        return loadSvgAsset("icon-idea.svg");
    }

}
