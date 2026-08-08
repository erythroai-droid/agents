package ai.erythro;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.Russian;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class AuditCollector {

    private static final String TARGET_URL = "https://erythro.ai/";
    private static final List<String> LOCALES = List.of("en", "ru", "he");
    private static final List<String> IGNORED_WORDS = List.of(
            "Erythro", "AI", "Erythro-auditor", "i18next", "Playwright", "LanguageTool"
    );
    private static final String EXCEPTIONS_FILE = "config/audit_exceptions.json";
    private static final String AXE_CORE_URL = "https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.2/axe.min.js";
    private static final String OUTPUT_FILE = "reports/audit_data.json";

    private static String getGeminiApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        key = System.getProperty("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }
        File envFile = new File(".env");
        if (envFile.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(envFile.toPath());
                for (String line : lines) {
                    if (line.startsWith("GEMINI_API_KEY=")) {
                        return line.substring("GEMINI_API_KEY=".length()).trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
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
        System.out.println("[+] Запуск глубокого аудита сайта на Java...");
        
        String apiKey = getGeminiApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            System.out.println("[+] GEMINI_API_KEY успешно загружен в агента.");
        } else {
            System.out.println("[!] GEMINI_API_KEY не обнаружен. Запуск в автобазовом режиме.");
        }
        
        Map<String, Object> finalReport = new HashMap<>();
        List<Map<String, Object>> failedRequests = new CopyOnWriteArrayList<>();
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
                    reqError.put("locale", "unknown"); // Будет дополнено при обработке
                    failedRequests.add(reqError);
                }
            });

            for (String loc : LOCALES) {
                System.out.println("\n[+] Запуск глубокого аудита локали: " + loc);
                Map<String, Object> localeResult = new LinkedHashMap<>();

                // Переход и настройка локали через localStorage и Cookie (для Next.js SSR метатегов)
                context.addCookies(List.of(
                        new com.microsoft.playwright.options.Cookie("NEXT_LOCALE", loc).setUrl(TARGET_URL),
                        new com.microsoft.playwright.options.Cookie("i18nextLng", loc).setUrl(TARGET_URL),
                        new com.microsoft.playwright.options.Cookie("locale", loc).setUrl(TARGET_URL)
                ));
                page.navigate(TARGET_URL);
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

                // Обновляем локаль в сетевых ошибках, возникших на текущем шаге
                for (Map<String, Object> reqError : failedRequests) {
                    if ("unknown".equals(reqError.get("locale"))) {
                        reqError.put("locale", loc);
                    }
                }

                // А. Сбор SEO, структурированных текстов и базового A11y из DOM
                @SuppressWarnings("unchecked")
                Map<String, Object> domData = (Map<String, Object>) page.evaluate("""
                    () => {
                        // 1. Метатеги и SEO
                        const title = document.title || '';
                        const description = document.querySelector('meta[name="description"]')?.getAttribute('content') || null;
                        const ogTitle = document.querySelector('meta[property="og:title"]')?.getAttribute('content') || null;
                        const ogDescription = document.querySelector('meta[property="og:description"]')?.getAttribute('content') || null;
                        const canonical = document.querySelector('link[rel="canonical"]')?.getAttribute('href') || null;
                        const htmlLang = document.documentElement.getAttribute('lang') || null;

                        // 2. Иерархия заголовков H1-H6
                        const headings = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6'));
                        const headingIssues = [];
                        let lastLevel = 0;
                        headings.forEach(h => {
                            const level = parseInt(h.tagName.substring(1));
                            if (lastLevel > 0 && level - lastLevel > 1) {
                                headingIssues.push(`Нарушена иерархия: h${lastLevel} -> h${level} (${h.innerText.trim().substring(0, 30)})`);
                            }
                            lastLevel = level;
                        });

                        // 3. Текст для орфографии
                        const textElements = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, p, span, a, button, li'));
                        const rawTexts = textElements
                            .map(el => {
                                if (el.getAttribute('aria-label')) {
                                    return el.getAttribute('aria-label').trim();
                                }
                                const firstChildLabel = el.querySelector('[aria-label]');
                                if (firstChildLabel) {
                                    return firstChildLabel.getAttribute('aria-label').trim();
                                }
                                return el.innerText ? el.innerText.trim() : '';
                            })
                            .filter(text => text.length > 2);

                        // 4. Картинки без alt
                        const imagesMissingAlt = Array.from(document.querySelectorAll('img'))
                            .filter(img => !img.hasAttribute('alt') || img.getAttribute('alt').trim() === '')
                            .map(img => img.src || img.outerHTML.substring(0, 100));

                        // 5. Интерактивные элементы без доступных имен
                        const interactiveMissingLabels = Array.from(document.querySelectorAll('button, a[href], input, select, textarea'))
                            .filter(el => {
                                const text = el.innerText?.trim();
                                const ariaLabel = el.getAttribute('aria-label')?.trim();
                                const ariaLabelledBy = el.getAttribute('aria-labelledby')?.trim();
                                const alt = el.getAttribute('alt')?.trim();
                                const titleAttr = el.getAttribute('title')?.trim();
                                return !text && !ariaLabel && !ariaLabelledBy && !alt && !titleAttr;
                            })
                            .map(el => el.outerHTML.substring(0, 150));

                        return {
                            seo: { title, description, ogTitle, ogDescription, canonical, htmlLang },
                            heading_hierarchy_issues: headingIssues,
                            extracted_texts: Array.from(new Set(rawTexts)),
                            dom_a11y_issues: {
                                images_missing_alt_count: imagesMissingAlt.length,
                                images_missing_alt_samples: imagesMissingAlt.slice(0, 5),
                                interactive_missing_labels_count: interactiveMissingLabels.length,
                                interactive_missing_labels_samples: interactiveMissingLabels.slice(0, 5)
                            }
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

                localeAudits.put(loc, localeResult);
            }

            browser.close();
        }

        // 3. Формирование и сохранение итогового JSON
        finalReport.put("timestamp", System.currentTimeMillis());
        finalReport.put("failed_network_requests", new ArrayList<>(failedRequests));
        finalReport.put("audits_by_locale", localeAudits);
        finalReport.put("allowed_exceptions", exceptionsConfig.allowedTextExceptions);

        File reportsDir = new File("reports");
        if (!reportsDir.exists()) {
            reportsDir.mkdirs();
        }

        objectMapper.writeValue(Paths.get(OUTPUT_FILE).toFile(), finalReport);
        System.out.println("\n[✓] Глубокий аудит завершен. Данные сохранены в `" + OUTPUT_FILE + "`.");
    }
}
