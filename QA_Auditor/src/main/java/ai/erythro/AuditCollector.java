package ai.erythro;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Margin;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.Russian;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class AuditCollector {

    private static final String DEFAULT_TARGET_URL = "https://erythro.ai/";
    private static final List<String> DEFAULT_LOCALES = List.of("en", "ru", "he");
    private static final List<String> IGNORED_WORDS = List.of(
            "Erythro", "AI", "Erythro-auditor", "i18next", "Playwright", "LanguageTool"
    );
    private static final String EXCEPTIONS_FILE = "config/audit_exceptions.json";
    private static final String AXE_CORE_URL = "https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.2/axe.min.js";
    private static final String OUTPUT_FILE = "reports/audit_data.json";
    private static final String MD_REPORT_FILE = "reports/audit-report.md";
    private static final String PDF_REPORT_FILE = "reports/audit-report.pdf";

    private static String getTargetUrl() {
        String url = System.getenv("TARGET_URL");
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        url = System.getProperty("TARGET_URL");
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        for (File envFile : List.of(new File(".env"), new File("../.env"))) {
            if (envFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(envFile.toPath());
                    for (String line : lines) {
                        if (line.startsWith("TARGET_URL=")) {
                            String val = line.substring("TARGET_URL=".length()).trim();
                            if (!val.isBlank()) return val;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return DEFAULT_TARGET_URL;
    }

    private static List<String> getLocales() {
        String locs = System.getenv("LOCALES");
        if (locs == null || locs.isBlank()) {
            locs = System.getProperty("LOCALES");
        }
        if (locs == null || locs.isBlank()) {
            for (File envFile : List.of(new File(".env"), new File("../.env"))) {
                if (envFile.exists()) {
                    try {
                        List<String> lines = Files.readAllLines(envFile.toPath());
                        for (String line : lines) {
                            if (line.startsWith("LOCALES=")) {
                                locs = line.substring("LOCALES=".length()).trim();
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        if (locs != null && !locs.isBlank()) {
            String[] parts = locs.split(",");
            List<String> result = new ArrayList<>();
            for (String p : parts) {
                if (!p.trim().isEmpty()) {
                    result.add(p.trim().toLowerCase());
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return DEFAULT_LOCALES;
    }

    private static String getGeminiApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        key = System.getProperty("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        for (File envFile : List.of(new File(".env"), new File("../.env"))) {
            if (envFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(envFile.toPath());
                    for (String line : lines) {
                        if (line.startsWith("GEMINI_API_KEY=")) {
                            return line.substring("GEMINI_API_KEY=".length()).trim();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String getPageSpeedApiKey() {
        String key = System.getenv("PAGESPEED_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        key = System.getProperty("PAGESPEED_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        for (File envFile : List.of(new File(".env"), new File("../.env"))) {
            if (envFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(envFile.toPath());
                    for (String line : lines) {
                        if (line.startsWith("PAGESPEED_API_KEY=")) {
                            return line.substring("PAGESPEED_API_KEY=".length()).trim();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        String geminiKey = getGeminiApiKey();
        if (geminiKey != null && !geminiKey.isBlank()) {
            return geminiKey;
        }
        return "AIzaSyAri-3Ij68TkdnBWaWKvjek9voZuQNPl1A";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuditExceptions {
        public String description;
        @JsonProperty("ignored_spelling_terms")
        public List<String> ignoredSpellingTerms = new ArrayList<>();
        @JsonProperty("allowed_text_exceptions")
        public List<AllowedTextException> allowedTextExceptions = new ArrayList<>();

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AllowedTextException {
            public String id;
            public String locale;
            public String context;
            public String snippet;
            public String reason;
        }
    }

    public static void main(String[] args) throws IOException {
        String targetUrl = getTargetUrl();
        List<String> locales = getLocales();

        System.out.println("[+] Запуск глубокого аудита сайта на Java...");
        System.out.println("[+] Целевой URL: " + targetUrl);
        System.out.println("[+] Список локалей: " + String.join(", ", locales));
        
        String apiKey = getGeminiApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            System.out.println("[+] GEMINI_API_KEY успешно загружен в агента.");
        } else {
            System.out.println("[!] GEMINI_API_KEY не обнаружен. Запуск в автобазовом режиме.");
        }
        
        String psApiKey = getPageSpeedApiKey();
        if (psApiKey != null && !psApiKey.isBlank()) {
            System.out.println("[+] PAGESPEED_API_KEY успешно загружен в агента.");
        } else {
            System.out.println("[!] PAGESPEED_API_KEY не обнаружен. Используется анонимный режим.");
        }
        
        Map<String, Object> finalReport = new HashMap<>();
        List<Map<String, Object>> failedRequests = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> consoleLogs = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> uncaughtPageErrors = new CopyOnWriteArrayList<>();
        Map<String, Object> localeAudits = new LinkedHashMap<>();

        // 1. Инициализация LanguageTool + Загрузка исключений
        JLanguageTool toolEn = new JLanguageTool(new AmericanEnglish());
        JLanguageTool toolRu = new JLanguageTool(new Russian());

        // Считываем внешние исключения из json
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        AuditExceptions exceptionsConfig = null;
        File exceptionsFile = new File(EXCEPTIONS_FILE);
        if (exceptionsFile.exists()) {
            try {
                exceptionsConfig = objectMapper.readValue(exceptionsFile, AuditExceptions.class);
                System.out.println("[+] Конфигурация исключений загружена успешно.");
            } catch (IOException e) {
                System.err.println("[-] Ошибка при чтении файла исключений: " + e.getMessage());
            }
        }

        if (exceptionsConfig == null) {
            exceptionsConfig = new AuditExceptions();
        }

        // Объединяем встроенные игнорируемые слова и внешние
        Set<String> allIgnoredWords = new HashSet<>(IGNORED_WORDS);
        if (exceptionsConfig.ignoredSpellingTerms != null) {
            allIgnoredWords.addAll(exceptionsConfig.ignoredSpellingTerms);
        }

        List<String> ignoredList = new ArrayList<>(allIgnoredWords);
        for (JLanguageTool tool : List.of(toolEn, toolRu)) {
            for (Rule rule : tool.getAllActiveRules()) {
                if (rule instanceof SpellingCheckRule) {
                    ((SpellingCheckRule) rule).addIgnoreTokens(ignoredList);
                }
            }
        }

        // 2. Старт Playwright
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // Перехват сетевых ошибок (4xx/5xx)
            page.onResponse(response -> {
                if (response.status() >= 400) {
                    Map<String, Object> reqError = new HashMap<>();
                    reqError.put("url", response.url());
                    reqError.put("status", response.status());
                    reqError.put("statusText", response.statusText());
                    reqError.put("locale", "unknown");
                    failedRequests.add(reqError);
                }
            });

            final String[] currentLocaleRef = new String[]{"unknown"};

            // 1. Перехват Dev Console (console.error и console.warn)
            page.onConsoleMessage(msg -> {
                String type = msg.type();
                if ("error".equalsIgnoreCase(type) || "warning".equalsIgnoreCase(type) || "warn".equalsIgnoreCase(type)) {
                    Map<String, Object> log = new LinkedHashMap<>();
                    log.put("type", type.toLowerCase());
                    log.put("text", msg.text());
                    log.put("location", msg.location());
                    log.put("locale", currentLocaleRef[0]);
                    consoleLogs.add(log);
                }
            });

            // 2. Перехват PageError (Uncaught Exceptions)
            page.onPageError(error -> {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", error);
                err.put("locale", currentLocaleRef[0]);
                uncaughtPageErrors.add(err);
            });

            for (String loc : locales) {
                currentLocaleRef[0] = loc;
                System.out.println("\n[+] Запуск глубокого аудита локали: " + loc);
                Map<String, Object> localeResult = new LinkedHashMap<>();

                // Переход и настройка локали через localStorage и Cookie (для Next.js SSR метатегов)
                context.addCookies(List.of(
                        new com.microsoft.playwright.options.Cookie("NEXT_LOCALE", loc).setUrl(targetUrl),
                        new com.microsoft.playwright.options.Cookie("i18nextLng", loc).setUrl(targetUrl),
                        new com.microsoft.playwright.options.Cookie("locale", loc).setUrl(targetUrl)
                ));
                page.navigate(targetUrl);
                page.evaluate(String.format("""
                    () => {
                        localStorage.setItem('i18nextLng', '%s');
                        localStorage.setItem('locale', '%s');
                        localStorage.setItem('lang', '%s');
                        document.cookie = 'NEXT_LOCALE=%s; path=/; max-age=31536000';
                        document.cookie = 'i18nextLng=%s; path=/; max-age=31536000';
                    }
                """, loc, loc, loc, loc, loc));
                page.reload();
                page.waitForLoadState(LoadState.NETWORKIDLE);

                // Резервный UI-переключатель, если localStorage не обновил локаль (например, при первом запуске)
                String htmlLang = (String) page.evaluate("document.documentElement.getAttribute('lang')");
                if (htmlLang == null || (!htmlLang.startsWith(loc) && !htmlLang.equals(loc))) {
                    System.out.println("  [!] Переключение через LocalStorage не обновило html lang (" + htmlLang + "). Запуск резервного кликера...");
                    Locator menuBtn = page.locator("button:has-text('MENU')").first();
                    if (!menuBtn.isVisible()) {
                        menuBtn = page.locator("button:has-text('МЕНЮ'), button:has-text('ЗАКРЫТЬ'), button:has-text('תפריט'), button:has-text('סגירה')").first();
                    }
                    if (menuBtn.isVisible()) {
                        menuBtn.click();
                        page.waitForTimeout(500);
                    }
                    Locator btn = page.locator(String.format("button:has-text('%s')", loc.toUpperCase())).first();
                    if (btn.isVisible()) {
                        btn.click();
                        page.waitForTimeout(2000);
                    }
                }

                // Обновляем локаль в перехваченных событиях, возникших на текущем шаге
                for (Map<String, Object> reqError : failedRequests) {
                    if ("unknown".equals(reqError.get("locale"))) {
                        reqError.put("locale", loc);
                    }
                }
                for (Map<String, Object> logItem : consoleLogs) {
                    if ("unknown".equals(logItem.get("locale"))) {
                        logItem.put("locale", loc);
                    }
                }
                for (Map<String, Object> errItem : uncaughtPageErrors) {
                    if ("unknown".equals(errItem.get("locale"))) {
                        errItem.put("locale", loc);
                    }
                }

                // А. SEO и мета-анализ страницы
                Map<String, Object> domData = (Map<String, Object>) page.evaluate("""
                    () => {
                        const getMeta = (name) => {
                            const el = document.querySelector(`meta[name="${name}"], meta[property="${name}"]`);
                            return el ? el.getAttribute('content') : null;
                        };

                        const h1s = Array.from(document.querySelectorAll('h1')).map(h => h.innerText.trim()).filter(t => t.length > 0);
                        const h2s = Array.from(document.querySelectorAll('h2')).map(h => h.innerText.trim()).filter(t => t.length > 0);
                        const h3s = Array.from(document.querySelectorAll('h3')).map(h => h.innerText.trim()).filter(t => t.length > 0);

                        // Проверка иерархии заголовков
                        const headingIssues = [];
                        if (h1s.length === 0) {
                            headingIssues.push("Отсутствует тег <h1> на странице");
                        } else if (h1s.length > 1) {
                            headingIssues.push(`Обнаружено несколько тегов <h1> (${h1s.length} шт.)`);
                        }
                        if (h2s.length === 0 && h3s.length > 0) {
                            headingIssues.push("Нарушена иерархия: присутствуют <h3>, но отсутствуют <h2>");
                        }

                        // Проверка доступности элементов (A11y DOM checks)
                        const a11yIssues = [];
                        const imgsWithoutAlt = Array.from(document.querySelectorAll('img')).filter(img => !img.hasAttribute('alt') || img.getAttribute('alt').trim() === '');
                        if (imgsWithoutAlt.length > 0) {
                            a11yIssues.push(`Обнаружено ${imgsWithoutAlt.length} изображений без атрибута alt`);
                        }

                        const buttonsWithoutAriaOrText = Array.from(document.querySelectorAll('button')).filter(btn => {
                            const text = btn.innerText.trim();
                            const aria = btn.getAttribute('aria-label') || btn.getAttribute('aria-labelledby');
                            return !text && !aria;
                        });
                        if (buttonsWithoutAriaOrText.length > 0) {
                            a11yIssues.push(`Обнаружено ${buttonsWithoutAriaOrText.length} кнопок без текста и без aria-label`);
                        }

                        // Сбор всех текстовых блоков для проверки орфографии
                        const walker = document.createTreeWalker(
                            document.body,
                            NodeFilter.SHOW_TEXT,
                            {
                                acceptNode: function(node) {
                                    const parent = node.parentElement;
                                    if (!parent) return NodeFilter.FILTER_REJECT;
                                    const tag = parent.tagName.toLowerCase();
                                    if (['script', 'style', 'noscript', 'svg', 'code'].includes(tag)) {
                                        return NodeFilter.FILTER_REJECT;
                                    }
                                    const text = node.nodeValue.trim();
                                    if (text.length < 3 || /^[^a-zA-Z\u0400-\u04FF\u0590-\u05FF]+$/.test(text)) {
                                        return NodeFilter.FILTER_REJECT;
                                    }
                                    return NodeFilter.FILTER_ACCEPT;
                                }
                            }
                        );

                        const extractedTexts = [];
                        while (walker.nextNode()) {
                            extractedTexts.push(walker.currentNode.nodeValue.trim());
                        }

                        return {
                            seo: {
                                title: document.title,
                                description: getMeta('description'),
                                ogTitle: getMeta('og:title'),
                                ogDescription: getMeta('og:description'),
                                ogImage: getMeta('og:image'),
                                canonical: document.querySelector('link[rel="canonical"]')?.getAttribute('href') || null,
                                htmlLang: document.documentElement.getAttribute('lang'),
                                dir: document.documentElement.getAttribute('dir'),
                                h1_count: h1s.length,
                                h1_list: h1s,
                                h2_count: h2s.length,
                                h3_count: h3s.length
                            },
                            heading_hierarchy_issues: headingIssues,
                            dom_a11y_issues: a11yIssues,
                            extracted_texts: Array.from(new Set(extractedTexts))
                        };
                    }
                """);

                localeResult.put("seo", domData.get("seo"));
                localeResult.put("heading_hierarchy_issues", domData.get("heading_hierarchy_issues"));
                localeResult.put("dom_a11y_issues", domData.get("dom_a11y_issues"));

                // Б. Глубокий аудит доступности через axe-core (WCAG 2.1 AA)
                try {
                    page.addScriptTag(new Page.AddScriptTagOptions().setUrl(AXE_CORE_URL));
                    Object axeViolations = page.evaluate("""
                        async () => {
                            if (typeof axe === 'undefined') return [];
                            const results = await axe.run(document, {
                                runOnly: {
                                    type: 'tag',
                                    values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']
                                }
                            });
                            return results.violations.map(v => ({
                                id: v.id,
                                impact: v.impact,
                                description: v.description,
                                helpUrl: v.helpUrl,
                                nodesCount: v.nodes.length,
                                elements: v.nodes.map(n => n.html).slice(0, 3)
                            }));
                        }
                    """);
                    localeResult.put("axe_wcag_violations", axeViolations);
                } catch (Exception e) {
                    System.err.println("  [!] Не удалось запустить axe-core для локали " + loc + ": " + e.getMessage());
                    localeResult.put("axe_wcag_violations", List.of());
                }

                // В. Проверка орфографии LanguageTool
                @SuppressWarnings("unchecked")
                List<String> uniqueTexts = (List<String>) domData.get("extracted_texts");
                localeResult.put("extracted_texts", uniqueTexts);

                JLanguageTool currentTool = switch (loc) {
                    case "en" -> toolEn;
                    case "ru" -> toolRu;
                    default -> null;
                };

                if (currentTool != null) {
                    System.out.printf("  Проверка орфографии (%d строк)...%n", uniqueTexts.size());
                    List<Map<String, Object>> spellingIssues = new ArrayList<>();

                    for (String text : uniqueTexts) {
                        try {
                            List<RuleMatch> matches = currentTool.check(text);
                            for (RuleMatch match : matches) {
                                int start = Math.max(0, match.getFromPos());
                                int end = Math.min(text.length(), match.getToPos());
                                String matchedStr = text.substring(start, end);

                                // Резервная фильтрация игнорируемых терминов
                                if (!matchedStr.isEmpty() && allIgnoredWords.contains(matchedStr.trim())) {
                                    continue;
                                }

                                Map<String, Object> issue = new LinkedHashMap<>();
                                issue.put("text_snippet", text);
                                issue.put("message", match.getMessage());
                                issue.put("matched_string", matchedStr);
                                issue.put("replacements", match.getSuggestedReplacements().stream().limit(3).toList());
                                spellingIssues.add(issue);
                            }
                        } catch (Exception e) {
                            System.err.println("  [!] Ошибка проверки орфографии в строке: " + text + ": " + e.getMessage());
                        }
                    }
                    localeResult.put("spelling_issues", spellingIssues);
                } else {
                    localeResult.put("spelling_issues", List.of());
                }

                // Г. Логи Dev Console и PageErrors для данной локали
                final String currentLoc = loc;
                List<Map<String, Object>> currentLocaleConsole = consoleLogs.stream()
                        .filter(l -> currentLoc.equals(l.get("locale")))
                        .toList();
                List<Map<String, Object>> currentLocalePageErrors = uncaughtPageErrors.stream()
                        .filter(e -> currentLoc.equals(e.get("locale")))
                        .toList();
                localeResult.put("console_logs", currentLocaleConsole);
                localeResult.put("uncaught_page_errors", currentLocalePageErrors);

                localeAudits.put(loc, localeResult);
            }

            // 3. Проверка производительности через PageSpeed Insights API
            Map<String, Object> pageSpeedData = fetchPageSpeedInsights(targetUrl, getPageSpeedApiKey());

            // 4. Формирование и сохранение итогового JSON
            finalReport.put("target_url", targetUrl);
            finalReport.put("locales", locales);
            finalReport.put("timestamp", System.currentTimeMillis());
            finalReport.put("failed_network_requests", new ArrayList<>(failedRequests));
            finalReport.put("console_logs", new ArrayList<>(consoleLogs));
            finalReport.put("uncaught_page_errors", new ArrayList<>(uncaughtPageErrors));
            finalReport.put("audits_by_locale", localeAudits);
            finalReport.put("pagespeed_insights", pageSpeedData);
            finalReport.put("allowed_exceptions", exceptionsConfig.allowedTextExceptions);

            File reportsDir = new File("reports");
            if (!reportsDir.exists()) {
                reportsDir.mkdirs();
            }

            objectMapper.writeValue(Paths.get(OUTPUT_FILE).toFile(), finalReport);
            System.out.println("\n[✓] Глубокий аудит завершен. Данные сохранены в `" + OUTPUT_FILE + "`.");

            // 5. Генерация Markdown-отчета
            String markdownReport = generateMarkdownReport(finalReport, targetUrl, locales);
            Files.writeString(Paths.get(MD_REPORT_FILE), markdownReport);
            System.out.println("[✓] Markdown-отчет сгенерирован в `" + MD_REPORT_FILE + "`.");

            // 6. Генерация PDF-отчета через Playwright
            try {
                String htmlReport = generateHtmlReport(finalReport, targetUrl, locales);
                Page pdfPage = context.newPage();
                pdfPage.setContent(htmlReport);
                
                Path pdfPath = Paths.get(PDF_REPORT_FILE).toAbsolutePath();
                try {
                    pdfPage.pdf(new Page.PdfOptions()
                            .setPath(pdfPath)
                            .setFormat("A4")
                            .setPrintBackground(true)
                            .setMargin(new Margin()
                                    .setTop("15mm")
                                    .setBottom("15mm")
                                    .setLeft("15mm")
                                    .setRight("15mm"))
                    );
                    System.out.println("[✓] PDF-отчет сгенерирован в `" + pdfPath + "`.");
                } catch (Exception fileLockedExc) {
                    Path fallbackPath = Paths.get("reports/audit-report-" + System.currentTimeMillis() + ".pdf").toAbsolutePath();
                    pdfPage.pdf(new Page.PdfOptions()
                            .setPath(fallbackPath)
                            .setFormat("A4")
                            .setPrintBackground(true)
                            .setMargin(new Margin()
                                    .setTop("15mm")
                                    .setBottom("15mm")
                                    .setLeft("15mm")
                                    .setRight("15mm"))
                    );
                    System.out.println("[!] Основной PDF-файл заблокирован открытым просмотрщиком. Отчет сохранен в `" + fallbackPath.getFileName() + "`.");
                }
                pdfPage.close();
            } catch (Exception e) {
                System.err.println("[-] Ошибка при генерации PDF-отчета: " + e.getMessage());
            }

            browser.close();
        }
    }

    private static Map<String, Object> fetchPageSpeedInsights(String targetUrl, String apiKey) {
        System.out.println("\n[+] Запуск проверки скорости и метрик производительности (PageSpeed Insights API)...");
        Map<String, Object> pageSpeedData = new LinkedHashMap<>();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        for (String strategy : List.of("mobile", "desktop")) {
            Map<String, Object> strategyResult = new LinkedHashMap<>();
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                System.out.println("  Запрос PageSpeed Insights (" + strategy + ")" + (attempt > 1 ? " [повтор " + attempt + "]" : "") + "...");
                try {
                    StringBuilder urlBuilder = new StringBuilder("https://www.googleapis.com/pagespeedonline/v5/runPagespeed?url=");
                    urlBuilder.append(URLEncoder.encode(targetUrl, StandardCharsets.UTF_8));
                    urlBuilder.append("&strategy=").append(strategy);
                    urlBuilder.append("&category=performance&category=accessibility&category=best-practices&category=seo");
                    if (apiKey != null && !apiKey.isBlank()) {
                        urlBuilder.append("&key=").append(apiKey);
                    }

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(urlBuilder.toString()))
                            .timeout(Duration.ofSeconds(60))
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode root = mapper.readTree(response.body());
                        JsonNode lighthouse = root.path("lighthouseResult");
                        JsonNode categories = lighthouse.path("categories");

                        Map<String, Object> scores = new LinkedHashMap<>();
                        if (categories.has("performance") && categories.path("performance").has("score")) {
                            scores.put("performance", Math.round(categories.path("performance").path("score").asDouble() * 100));
                        }
                        if (categories.has("accessibility") && categories.path("accessibility").has("score")) {
                            scores.put("accessibility", Math.round(categories.path("accessibility").path("score").asDouble() * 100));
                        }
                        if (categories.has("best-practices") && categories.path("best-practices").has("score")) {
                            scores.put("best_practices", Math.round(categories.path("best-practices").path("score").asDouble() * 100));
                        }
                        if (categories.has("seo") && categories.path("seo").has("score")) {
                            scores.put("seo", Math.round(categories.path("seo").path("score").asDouble() * 100));
                        }
                        strategyResult.put("scores", scores);

                        JsonNode audits = lighthouse.path("audits");
                        Map<String, Object> metrics = new LinkedHashMap<>();
                        extractAuditMetric(audits, "first-contentful-paint", "FCP", metrics);
                        extractAuditMetric(audits, "largest-contentful-paint", "LCP", metrics);
                        extractAuditMetric(audits, "cumulative-layout-shift", "CLS", metrics);
                        extractAuditMetric(audits, "total-blocking-time", "TBT", metrics);
                        extractAuditMetric(audits, "speed-index", "Speed Index", metrics);
                        extractAuditMetric(audits, "interactive", "TTI", metrics);
                        strategyResult.put("metrics", metrics);
                        strategyResult.put("status", "SUCCESS");
                        break; // Успешно получено
                    } else {
                        System.err.println("  [!] PageSpeed Insights API (" + strategy + ") вернул статус " + response.statusCode());
                        strategyResult.put("status", "ERROR");
                        strategyResult.put("statusCode", response.statusCode());
                        strategyResult.put("errorResponse", response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
                    }
                } catch (Exception e) {
                    System.err.println("  [!] Ошибка при запросе PageSpeed Insights (" + strategy + "): " + e.getMessage());
                    strategyResult.put("status", "FAILED");
                    strategyResult.put("errorMessage", e.getMessage());
                }

                if (attempt < maxAttempts) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
            pageSpeedData.put(strategy, strategyResult);
            if ("mobile".equals(strategy)) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
            }
        }
        return pageSpeedData;
    }

    private static void extractAuditMetric(JsonNode audits, String auditKey, String metricName, Map<String, Object> metrics) {
        if (audits.has(auditKey)) {
            JsonNode audit = audits.path(auditKey);
            Map<String, Object> metricInfo = new LinkedHashMap<>();
            if (audit.has("displayValue")) {
                metricInfo.put("displayValue", audit.path("displayValue").asText());
            }
            if (audit.has("numericValue")) {
                metricInfo.put("numericValue", audit.path("numericValue").asDouble());
            }
            if (audit.has("score")) {
                metricInfo.put("score", audit.path("score").asDouble());
            }
            metrics.put(metricName, metricInfo);
        }
    }

    @SuppressWarnings("unchecked")
    private static String generateMarkdownReport(Map<String, Object> finalReport, String targetUrl, List<String> locales) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss (z)");
        String dateStr = sdf.format(new Date());

        sb.append("# 📊 QA Audit Report: ").append(targetUrl).append("\n");
        sb.append("**Дата проверки:** ").append(dateStr).append("  \n");
        sb.append("**Проверяемые локали:** ").append(String.join(", ", locales)).append("\n\n");

        List<Map<String, Object>> failedNetwork = (List<Map<String, Object>>) finalReport.getOrDefault("failed_network_requests", List.of());
        List<Map<String, Object>> consoleLogs = (List<Map<String, Object>>) finalReport.getOrDefault("console_logs", List.of());
        List<Map<String, Object>> uncaughtErrors = (List<Map<String, Object>>) finalReport.getOrDefault("uncaught_page_errors", List.of());

        sb.append("## EXECUTIVE SUMMARY\n");
        int totalDevErrors = consoleLogs.size() + uncaughtErrors.size();
        String status = (failedNetwork.isEmpty() && uncaughtErrors.isEmpty()) ? "PASS" : "CONDITIONAL PASS";
        sb.append("- **Общий статус:** `").append(status).append("`\n");
        sb.append("- **Ошибок сетевых запросов (HTTP 4xx/5xx):** ").append(failedNetwork.size()).append("\n");
        sb.append("- **Сообщений Dev Console / JS Ошибок:** ").append(totalDevErrors).append("\n\n");

        sb.append("## 1. 🖥️ DEV CONSOLE & UNCAUGHT JS ERRORS\n");
        if (consoleLogs.isEmpty() && uncaughtErrors.isEmpty()) {
            sb.append("Ошибок JS и предупреждений в консоли браузера не обнаружено.\n\n");
        } else {
            sb.append("| Error Type | Message / Exception | Source Location | Active Locale |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            for (Map<String, Object> err : uncaughtErrors) {
                sb.append("| `PageError (Uncaught)` | ").append(cleanMd(String.valueOf(err.get("error"))))
                        .append(" | N/A | ").append(err.get("locale")).append(" |\n");
            }
            for (Map<String, Object> log : consoleLogs) {
                sb.append("| `").append(log.get("type")).append("` | ")
                        .append(cleanMd(String.valueOf(log.get("text"))))
                        .append(" | `").append(cleanMd(String.valueOf(log.get("location")))).append("` | ")
                        .append(log.get("locale")).append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 2. ⚡ SPEED TEST & PERFORMANCE METRICS (PAGESPEED INSIGHTS)\n");
        Map<String, Object> psData = (Map<String, Object>) finalReport.get("pagespeed_insights");
        if (psData != null && !psData.isEmpty()) {
            sb.append("| Strategy | Status | Performance | Accessibility | Best Practices | SEO | FCP | LCP | CLS |\n");
            sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
            for (String strat : List.of("mobile", "desktop")) {
                Map<String, Object> st = (Map<String, Object>) psData.get(strat);
                if (st != null) {
                    String stStatus = String.valueOf(st.get("status"));
                    Map<String, Object> scores = (Map<String, Object>) st.get("scores");
                    Map<String, Object> metrics = (Map<String, Object>) st.get("metrics");
                    if ("SUCCESS".equals(stStatus) && scores != null) {
                        sb.append("| `").append(strat).append("` | `SUCCESS` | ")
                                .append(scores.getOrDefault("performance", "-")).append(" | ")
                                .append(scores.getOrDefault("accessibility", "-")).append(" | ")
                                .append(scores.getOrDefault("best_practices", "-")).append(" | ")
                                .append(scores.getOrDefault("seo", "-")).append(" | ")
                                .append(getMetricDisplay(metrics, "FCP")).append(" | ")
                                .append(getMetricDisplay(metrics, "LCP")).append(" | ")
                                .append(getMetricDisplay(metrics, "CLS")).append(" |\n");
                    } else {
                        sb.append("| `").append(strat).append("` | `").append(stStatus).append("` | - | - | - | - | - | - | - |\n");
                    }
                }
            }
            sb.append("\n");
        } else {
            sb.append("Данные PageSpeed Insights отсутствуют.\n\n");
        }

        sb.append("## 3. 🌐 NETWORK & RESOURCE ERRORS\n");
        if (failedNetwork.isEmpty()) {
            sb.append("Сетевых ошибок HTTP 4xx/5xx не обнаружено.\n\n");
        } else {
            sb.append("| Target URL | Status Code | Error Description | Active Locale |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            for (Map<String, Object> req : failedNetwork) {
                sb.append("| ").append(req.get("url")).append(" | ").append(req.get("status"))
                        .append(" | ").append(req.get("statusText")).append(" | ")
                        .append(req.get("locale")).append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String generateHtmlReport(Map<String, Object> finalReport, String targetUrl, List<String> locales) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss (z)");
        String dateStr = sdf.format(new Date());

        List<Map<String, Object>> failedNetwork = (List<Map<String, Object>>) finalReport.getOrDefault("failed_network_requests", List.of());
        List<Map<String, Object>> consoleLogs = (List<Map<String, Object>>) finalReport.getOrDefault("console_logs", List.of());
        List<Map<String, Object>> uncaughtErrors = (List<Map<String, Object>>) finalReport.getOrDefault("uncaught_page_errors", List.of());
        Map<String, Object> localeAudits = (Map<String, Object>) finalReport.getOrDefault("audits_by_locale", Map.of());
        Map<String, Object> psData = (Map<String, Object>) finalReport.get("pagespeed_insights");

        int totalDevErrors = consoleLogs.size() + uncaughtErrors.size();
        String overallStatus = (failedNetwork.isEmpty() && uncaughtErrors.isEmpty()) ? "PASS" : "CONDITIONAL PASS";
        String statusBadgeClass = "PASS".equals(overallStatus) ? "badge-pass" : "badge-warn";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"UTF-8\">\n")
            .append("<title>QA Audit Report: ").append(escapeHtml(targetUrl)).append("</title>\n")
            .append("""
            <style>
                body { font-family: 'Inter', system-ui, -apple-system, sans-serif; margin: 0; padding: 25px; color: #1e293b; background: #ffffff; line-height: 1.5; font-size: 13px; }
                .header { border-bottom: 2px solid #3b82f6; padding-bottom: 12px; margin-bottom: 20px; }
                h1 { color: #0f172a; font-size: 24px; margin: 0 0 6px 0; font-weight: 700; }
                .meta { color: #64748b; font-size: 12px; margin: 0; }
                h2 { color: #0f172a; border-bottom: 1px solid #cbd5e1; padding-bottom: 6px; font-size: 16px; margin-top: 25px; margin-bottom: 12px; font-weight: 600; }
                .summary-cards { display: flex; gap: 12px; margin-bottom: 20px; }
                .card { flex: 1; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; text-align: center; }
                .card-title { font-size: 11px; text-transform: uppercase; color: #64748b; font-weight: 600; letter-spacing: 0.5px; }
                .card-value { font-size: 20px; font-weight: 700; color: #0f172a; margin-top: 4px; }
                .badge-pass { background: #d1fae5; color: #065f46; padding: 3px 8px; border-radius: 4px; font-weight: 600; font-size: 12px; display: inline-block; }
                .badge-warn { background: #fef3c7; color: #92400e; padding: 3px 8px; border-radius: 4px; font-weight: 600; font-size: 12px; display: inline-block; }
                .badge-fail { background: #fee2e2; color: #991b1b; padding: 3px 8px; border-radius: 4px; font-weight: 600; font-size: 12px; display: inline-block; }
                table { width: 100%; border-collapse: collapse; margin-top: 8px; margin-bottom: 16px; font-size: 12px; page-break-inside: avoid; }
                th { background-color: #f1f5f9; color: #334155; text-align: left; padding: 8px 10px; font-weight: 600; border-bottom: 2px solid #cbd5e1; }
                td { padding: 8px 10px; border-bottom: 1px solid #e2e8f0; vertical-align: top; word-break: break-word; }
                tr:nth-child(even) { background-color: #f8fafc; }
                .code { font-family: monospace; background: #f1f5f9; padding: 2px 4px; border-radius: 3px; font-size: 11px; color: #0f172a; }
                .no-issues { color: #10b981; font-weight: 500; padding: 6px 0; }
                @media print {
                    body { padding: 0; }
                }
            </style>
            </head>
            <body>
            <div class="header">
            """)
            .append("<h1>📊 QA Audit Report: ").append(escapeHtml(targetUrl)).append("</h1>\n");

        html.append("<p class=\"meta\"><strong>Дата проверки:</strong> ")
            .append(escapeHtml(dateStr))
            .append(" &nbsp;|&nbsp; <strong>Локали:</strong> ")
            .append(escapeHtml(String.join(", ", locales)))
            .append("</p></div>");

        html.append("<div class=\"summary-cards\">")
            .append("<div class=\"card\"><div class=\"card-title\">Общий статус</div><div class=\"card-value\"><span class=\"")
            .append(statusBadgeClass).append("\">").append(overallStatus).append("</span></div></div>")
            .append("<div class=\"card\"><div class=\"card-title\">HTTP Ошибки (4xx/5xx)</div><div class=\"card-value\">")
            .append(failedNetwork.size()).append("</div></div>")
            .append("<div class=\"card\"><div class=\"card-title\">Console / JS Ошибки</div><div class=\"card-value\">")
            .append(totalDevErrors).append("</div></div></div>");

        // 1. Dev Console
        html.append("<h2>1. 🖥️ DEV CONSOLE & UNCAUGHT JS ERRORS</h2>");
        if (consoleLogs.isEmpty() && uncaughtErrors.isEmpty()) {
            html.append("<p class='no-issues'>✓ Ошибок JS и предупреждений в консоли браузера не обнаружено.</p>");
        } else {
            html.append("""
                <table>
                <thead><tr><th>Тип</th><th>Сообщение / Исключение</th><th>Файл / Строка</th><th>Локаль</th></tr></thead>
                <tbody>
            """);
            for (Map<String, Object> err : uncaughtErrors) {
                html.append("<tr><td><span class='badge-fail'>PageError</span></td><td>")
                        .append(escapeHtml(String.valueOf(err.get("error"))))
                        .append("</td><td>N/A</td><td>")
                        .append(escapeHtml(String.valueOf(err.get("locale"))))
                        .append("</td></tr>");
            }
            for (Map<String, Object> log : consoleLogs) {
                String type = String.valueOf(log.get("type"));
                String badgeClass = "error".equalsIgnoreCase(type) ? "badge-fail" : "badge-warn";
                html.append("<tr><td><span class='").append(badgeClass).append("'>")
                        .append(escapeHtml(type)).append("</span></td><td>")
                        .append(escapeHtml(String.valueOf(log.get("text")))).append("</td><td><span class='code'>")
                        .append(escapeHtml(String.valueOf(log.get("location")))).append("</span></td><td>")
                        .append(escapeHtml(String.valueOf(log.get("locale")))).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        // 2. PageSpeed Insights
        html.append("<h2>2. ⚡ SPEED TEST & PERFORMANCE METRICS (PAGESPEED INSIGHTS)</h2>");
        if (psData != null && !psData.isEmpty()) {
            html.append("""
                <table>
                <thead><tr><th>Платформа</th><th>Статус</th><th>Performance</th><th>Accessibility</th><th>Best Practices</th><th>SEO</th><th>FCP</th><th>LCP</th><th>CLS</th></tr></thead>
                <tbody>
            """);
            for (String strat : List.of("mobile", "desktop")) {
                Map<String, Object> st = (Map<String, Object>) psData.get(strat);
                if (st != null) {
                    String stStatus = String.valueOf(st.get("status"));
                    Map<String, Object> scores = (Map<String, Object>) st.get("scores");
                    Map<String, Object> metrics = (Map<String, Object>) st.get("metrics");
                    if ("SUCCESS".equals(stStatus) && scores != null) {
                        html.append("<tr><td><strong>").append(strat).append("</strong></td><td><span class='badge-pass'>SUCCESS</span></td><td>")
                                .append(scores.getOrDefault("performance", "-")).append("</td><td>")
                                .append(scores.getOrDefault("accessibility", "-")).append("</td><td>")
                                .append(scores.getOrDefault("best_practices", "-")).append("</td><td>")
                                .append(scores.getOrDefault("seo", "-")).append("</td><td>")
                                .append(escapeHtml(getMetricDisplay(metrics, "FCP"))).append("</td><td>")
                                .append(escapeHtml(getMetricDisplay(metrics, "LCP"))).append("</td><td>")
                                .append(escapeHtml(getMetricDisplay(metrics, "CLS"))).append("</td></tr>");
                    } else {
                        html.append("<tr><td><strong>").append(strat).append("</strong></td><td><span class='badge-warn'>")
                                .append(escapeHtml(stStatus)).append("</span></td><td colspan='7'>-</td></tr>");
                    }
                }
            }
            html.append("</tbody></table>");
        } else {
            html.append("<p>Данные PageSpeed Insights отсутствуют.</p>");
        }

        // 3. Network Errors
        html.append("<h2>3. 🌐 NETWORK & RESOURCE ERRORS</h2>");
        if (failedNetwork.isEmpty()) {
            html.append("<p class='no-issues'>✓ Сетевых ошибок HTTP 4xx/5xx не обнаружено.</p>");
        } else {
            html.append("""
                <table>
                <thead><tr><th>Target URL</th><th>Status Code</th><th>Описание</th><th>Локаль</th></tr></thead>
                <tbody>
            """);
            for (Map<String, Object> req : failedNetwork) {
                html.append("<tr><td><span class='code'>").append(escapeHtml(String.valueOf(req.get("url"))))
                        .append("</span></td><td><span class='badge-fail'>").append(escapeHtml(String.valueOf(req.get("status"))))
                        .append("</span></td><td>").append(escapeHtml(String.valueOf(req.get("statusText"))))
                        .append("</td><td>").append(escapeHtml(String.valueOf(req.get("locale"))))
                        .append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        // 4. Spelling Issues
        html.append("<h2>4. ✍️ SPELLING & GRAMMAR ISSUES</h2>");
        boolean hasSpellingIssues = false;
        for (String loc : locales) {
            Map<String, Object> locData = (Map<String, Object>) localeAudits.get(loc);
            if (locData != null) {
                List<Map<String, Object>> spelling = (List<Map<String, Object>>) locData.get("spelling_issues");
                if (spelling != null && !spelling.isEmpty()) {
                    if (!hasSpellingIssues) {
                        html.append("""
                            <table>
                            <thead><tr><th>Локаль</th><th>Контекст / Строка</th><th>Сообщение</th><th>Найдено</th><th>Варианты</th></tr></thead>
                            <tbody>
                        """);
                        hasSpellingIssues = true;
                    }
                    for (Map<String, Object> issue : spelling) {
                        html.append("<tr><td><strong>").append(loc).append("</strong></td><td>")
                                .append(escapeHtml(String.valueOf(issue.get("text_snippet")))).append("</td><td>")
                                .append(escapeHtml(String.valueOf(issue.get("message")))).append("</td><td><span class='badge-warn'>")
                                .append(escapeHtml(String.valueOf(issue.get("matched_string")))).append("</span></td><td>")
                                .append(escapeHtml(String.valueOf(issue.get("replacements")))).append("</td></tr>");
                    }
                }
            }
        }
        if (hasSpellingIssues) {
            html.append("</tbody></table>");
        } else {
            html.append("<p class='no-issues'>✓ Орфографических ошибок не обнаружено.</p>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String getMetricDisplay(Map<String, Object> metrics, String metricKey) {
        if (metrics == null || !metrics.containsKey(metricKey)) return "-";
        @SuppressWarnings("unchecked")
        Map<String, Object> mInfo = (Map<String, Object>) metrics.get(metricKey);
        if (mInfo != null && mInfo.containsKey("displayValue")) {
            return String.valueOf(mInfo.get("displayValue"));
        }
        return "-";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String cleanMd(String text) {
        if (text == null) return "";
        return text.replace("\n", " ").replace("|", "\\|");
    }
}
