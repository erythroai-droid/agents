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
    private static final String HTML_REPORT_FILE = "reports/audit-report.html";
    private static final String PDF_REPORT_FILE = "reports/audit-report.pdf";
    /** Used when PDF_REPORT_FILE is locked by a viewer; fixed name so it never accumulates. */
    private static final String PDF_REPORT_FALLBACK = "reports/audit-report.locked.pdf";
    private static final int MOBILE_VIEWPORT_WIDTH = 375;
    private static final int MOBILE_VIEWPORT_HEIGHT = 667;
    /** Wix, Tilda and similar builders serve a separate mobile layout by user agent, so a resized
     *  desktop browser would report a phantom horizontal scroll. Emulate a real iPhone instead. */
    private static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    /** How many pages the agent browses beyond the homepage (homepage always included). */
    private static final int DEFAULT_AGENT_BROWSE_MAX_PAGES = 5;
    private static final String GEMINI_MODEL = "gemini-3.6-flash";

    /** HTTP header -> human readable name, in the order shown in the report. */
    private static final Map<String, String> SECURITY_HEADERS;

    /** Path / label hints that mark commercially important pages for the agent crawl. */
    private static final List<String> AGENT_PAGE_PRIORITY_HINTS = List.of(
            "contact", "contacts", "צור-קשר", "צור_קשר", "контакт", "связ",
            "about", "אודות", "о-нас", "о_нас",
            "service", "services", "שירות", "услуг",
            "price", "pricing", "тариф", "מחיר", "תעריף",
            "menu", "תפריט", "меню",
            "portfolio", "project", "work", "case", "תיק", "портфолио",
            "book", "booking", "order", "заказ", "הזמנ",
            "shop", "store", "product", "חנות"
    );

    static {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("strict-transport-security", "HSTS");
        headers.put("content-security-policy", "CSP");
        headers.put("x-frame-options", "X-Frame-Options");
        headers.put("x-content-type-options", "X-Content-Type-Options");
        headers.put("referrer-policy", "Referrer-Policy");
        headers.put("permissions-policy", "Permissions-Policy");
        SECURITY_HEADERS = Collections.unmodifiableMap(headers);
    }

    private static final String LEAD_AND_RTL_SCRIPT = """
        () => {
            const markup = (document.documentElement.outerHTML || '').toLowerCase();
            const attrBag = (el) => [
                el.getAttribute('name'), el.getAttribute('id'), el.getAttribute('type'),
                el.getAttribute('placeholder'), el.getAttribute('aria-label'), el.getAttribute('autocomplete')
            ].filter(Boolean).join(' ').toLowerCase();

            const isHoneypotField = (f) => {
                const style = getComputedStyle(f);
                const rect = f.getBoundingClientRect();
                const offScreen = rect.width === 0 || rect.height === 0
                    || rect.left < -500 || rect.top < -500;
                const hidden = style.display === 'none' || style.visibility === 'hidden'
                    || parseFloat(style.opacity || '1') === 0;
                const ariaHidden = f.closest('[aria-hidden="true"]') !== null;
                const name = (f.getAttribute('name') || '').toLowerCase();
                const trapName = /company_website|website|url|honeypot|hp_/;
                return hidden || offScreen || ariaHidden || trapName.test(name);
            };

            const forms = Array.from(document.querySelectorAll('form'));
            let hasHiddenTrap = false;
            const formDetails = forms.map(form => {
                const fields = Array.from(form.querySelectorAll('input, textarea, select'));
                const bag = fields.map(attrBag).join(' ');
                const types = fields.map(f => (f.getAttribute('type') || '').toLowerCase());
                const trapped = fields.filter(isHoneypotField);
                if (trapped.length > 0) hasHiddenTrap = true;
                return {
                    action: form.getAttribute('action') || null,
                    method: (form.getAttribute('method') || 'get').toLowerCase(),
                    fieldsCount: fields.length,
                    hasEmailField: types.includes('email') || bag.includes('email') || bag.includes('mail') || bag.includes('почт'),
                    hasPhoneField: types.includes('tel') || bag.includes('phone') || bag.includes('телефон') || bag.includes('טלפון'),
                    hasNameField: bag.includes('name') || bag.includes('имя') || bag.includes('שם'),
                    hasSubmitButton: !!form.querySelector('button[type="submit"], input[type="submit"], button:not([type])'),
                    hasHiddenTrap: trapped.length > 0
                };
            });

            const providers = [];
            if (markup.includes('recaptcha') || typeof window.grecaptcha !== 'undefined') providers.push('Google reCAPTCHA');
            if (markup.includes('turnstile') || typeof window.turnstile !== 'undefined') providers.push('Cloudflare Turnstile');
            if (markup.includes('hcaptcha') || typeof window.hcaptcha !== 'undefined') providers.push('hCaptcha');
            if (hasHiddenTrap) providers.push('Honeypot');

            const chatSelectors = [
                'iframe[src*="tawk"]', 'iframe[src*="crisp"]', 'iframe[src*="intercom"]',
                'iframe[src*="zendesk"]', 'iframe[src*="jivosite"]', 'iframe[src*="livechat"]',
                '[class*="chat-widget"]', '[id*="chat-widget"]', '[class*="chatbot"]', '[id*="chatbot"]'
            ];
            const chatGlobals = ['Tawk_API', '$crisp', 'Intercom', 'zE', 'jivo_api', 'LiveChatWidget', 'Chatra'];
            const chatSelectorHit = chatSelectors.find(sel => document.querySelector(sel)) || null;
            const chatGlobalHit = chatGlobals.find(name => typeof window[name] !== 'undefined') || null;
            const hasChatWidget = !!(chatSelectorHit || chatGlobalHit);

            const messengerMarkers = ['wa.me', 'api.whatsapp.com', 't.me', 'tg://', 'viber:', 'm.me'];
            const messengerLinks = Array.from(document.querySelectorAll('a[href]'))
                .map(a => a.getAttribute('href'))
                .filter(href => href && messengerMarkers.some(marker => href.includes(marker)))
                .slice(0, 5);

            const htmlDir = document.documentElement.getAttribute('dir');
            const bodyDir = document.body ? document.body.getAttribute('dir') : null;
            const computedDirection = getComputedStyle(document.documentElement).direction;
            const h1 = document.querySelector('h1');

            const linkHref = (selector) => {
                const el = document.querySelector(selector);
                return el ? el.getAttribute('href') : null;
            };
            const viewportMeta = document.querySelector('meta[name="viewport"]');

            return {
                lead_capture: {
                    formsCount: forms.length,
                    forms: formDetails,
                    antiSpam: { isProtected: providers.length > 0, providers: providers },
                    hasChatWidget: hasChatWidget,
                    chatWidgetSignature: chatGlobalHit || chatSelectorHit,
                    messengerLinks: messengerLinks,
                    hasInstantContactChannel: hasChatWidget || messengerLinks.length > 0
                },
                rtl_audit: {
                    htmlDir: htmlDir,
                    bodyDir: bodyDir,
                    computedDirection: computedDirection,
                    isRtlDeclared: htmlDir === 'rtl' || bodyDir === 'rtl' || computedDirection === 'rtl',
                    h1TextAlign: h1 ? getComputedStyle(h1).textAlign : null
                },
                seo_assets: {
                    favicon: linkHref('link[rel="icon"], link[rel="shortcut icon"]'),
                    appleTouchIcon: linkHref('link[rel="apple-touch-icon"]'),
                    hreflangs: Array.from(document.querySelectorAll('link[rel="alternate"][hreflang]')).map(l => l.getAttribute('hreflang')),
                    viewportMeta: viewportMeta ? viewportMeta.getAttribute('content') : null
                }
            };
        }
        """;

    private static final List<String> AI_CRAWLER_BOTS = List.of(
            "GPTBot", "ClaudeBot", "CCBot", "Google-Extended"
    );

    /** JSON-LD, llms link, GA4 consent stub — homepage AI visibility signals. */
    private static final String AI_VISIBILITY_SCRIPT = """
        () => {
            const scripts = Array.from(document.querySelectorAll('script[type="application/ld+json"]'));
            let organizationSchema = false;
            let faqSchema = false;
            const orgDetails = { hasName: false, hasUrl: false, hasSameAs: false, hasContactPoint: false };

            const inspectOrgNode = (node) => {
                if (!node || typeof node !== 'object') return;
                const type = node['@type'];
                const types = Array.isArray(type) ? type : (type ? [type] : []);
                if (types.some(t => t === 'Organization' || t === 'LocalBusiness')) {
                    organizationSchema = true;
                    orgDetails.hasName = orgDetails.hasName || !!node.name;
                    orgDetails.hasUrl = orgDetails.hasUrl || !!node.url;
                    orgDetails.hasSameAs = orgDetails.hasSameAs || !!(node.sameAs && (Array.isArray(node.sameAs) ? node.sameAs.length : true));
                    orgDetails.hasContactPoint = orgDetails.hasContactPoint || !!node.contactPoint;
                }
                if (Array.isArray(node['@graph'])) node['@graph'].forEach(inspectOrgNode);
            };

            const inspectFaqNode = (node) => {
                if (!node || typeof node !== 'object') return;
                const type = node['@type'];
                const types = Array.isArray(type) ? type : (type ? [type] : []);
                if (types.includes('FAQPage') && Array.isArray(node.mainEntity) && node.mainEntity.length > 0) {
                    faqSchema = true;
                }
                if (Array.isArray(node['@graph'])) node['@graph'].forEach(inspectFaqNode);
            };

            for (const s of scripts) {
                try {
                    const json = JSON.parse(s.textContent || '{}');
                    inspectOrgNode(json);
                    inspectFaqNode(json);
                } catch (e) { /* skip malformed JSON-LD */ }
            }

            const llmsLink = document.querySelector('link[rel="describedby"]');
            const llmsHref = llmsLink ? (llmsLink.getAttribute('href') || '') : '';
            const llmsDescribedby = llmsHref.endsWith('/llms.txt') || llmsHref.endsWith('llms.txt');

            const inlineScripts = Array.from(document.querySelectorAll('script:not([src])'))
                .map(s => s.textContent || '').join('\\n');
            const externalScripts = Array.from(document.querySelectorAll('script[src]'))
                .map(s => s.getAttribute('src') || '').join(' ');
            const allScriptText = inlineScripts + ' ' + externalScripts;

            const dataLayerPresent = typeof window.dataLayer !== 'undefined' || inlineScripts.includes('dataLayer');
            const gtagConsentStub = (inlineScripts.includes('consent') || inlineScripts.includes('Consent'))
                && (inlineScripts.includes('gtag') || inlineScripts.includes('dataLayer'));
            const ga4IdPresent = /G-[A-Z0-9]+/.test(allScriptText)
                || allScriptText.includes('googletagmanager.com/gtag/js');

            return {
                organization_schema: organizationSchema && orgDetails.hasName && orgDetails.hasUrl,
                organization_details: orgDetails,
                faq_schema: faqSchema,
                llms_describedby: llmsDescribedby,
                data_layer_present: dataLayerPresent,
                gtag_consent_stub: gtagConsentStub,
                ga4_id_present: ga4IdPresent
            };
        }
        """;

    /** Marks a button that most likely opens a modal contact form and returns its label. */
    private static final String CONTACT_TRIGGER_SCRIPT = """
        () => {
            const keywords = [
                'contact', 'get in touch', 'let us talk', 'lets talk', 'request a quote', 'send request',
                'связаться', 'свяжитесь', 'контакт', 'заявк', 'обсудить', 'написать',
                'צור קשר', 'צרו קשר', 'יצירת קשר', 'טופס', 'נדבר'
            ];
            // Buttons only: following a link would navigate away from the audited page
            for (const el of document.querySelectorAll('button, [role="button"]')) {
                const label = [el.innerText, el.getAttribute('aria-label'), el.getAttribute('title')]
                    .filter(Boolean).join(' ').toLowerCase();
                if (!label.trim()) continue;
                if (keywords.some(keyword => label.includes(keyword))) {
                    el.setAttribute('data-erythro-audit-trigger', '1');
                    return label.trim().slice(0, 60);
                }
            }
            return null;
        }
        """;

    private static final String MOBILE_OVERFLOW_SCRIPT = """
        () => {
            const root = document.scrollingElement || document.documentElement;
            const viewportWidth = root.clientWidth || window.innerWidth;
            const isRtl = getComputedStyle(document.documentElement).direction === 'rtl';

            // scrollWidth alone lies: off-canvas panels inflate it even when clipped by overflow-x hidden,
            // and in RTL the overflow grows to the left. Probing the real scroll range is direction-safe.
            const initialScroll = root.scrollLeft;
            root.scrollLeft = isRtl ? -100000 : 100000;
            const reachablePx = Math.round(Math.abs(root.scrollLeft - initialScroll));
            root.scrollLeft = initialScroll;
            const hasHorizontalOverflow = reachablePx > 1;

            const offenders = [];
            let clippedOffscreen = 0;
            const seen = new Set();
            for (const el of document.querySelectorAll('body *')) {
                const box = el.getBoundingClientRect();
                if (box.width === 0 || box.height === 0) continue;
                const over = Math.round(isRtl ? -box.left : box.right - viewportWidth);
                if (over <= 2) continue;
                if (!hasHorizontalOverflow) {
                    clippedOffscreen++;
                    continue;
                }
                const classes = typeof el.className === 'string' ? el.className.trim() : '';
                const shortClass = classes ? '.' + classes.split(' ').filter(Boolean).slice(0, 2).join('.') : '';
                const selector = el.tagName.toLowerCase() + shortClass;
                if (seen.has(selector)) continue;
                seen.add(selector);
                offenders.push({ selector: selector, overflowPx: over, widthPx: Math.round(box.width) });
                if (offenders.length >= 8) break;
            }

            return {
                viewport: viewportWidth + 'x' + window.innerHeight,
                direction: isRtl ? 'rtl' : 'ltr',
                documentScrollWidth: Math.round(Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0)),
                overflowPx: reachablePx,
                hasHorizontalOverflow: hasHorizontalOverflow,
                clippedOffscreenElements: clippedOffscreen,
                offenders: offenders
            };
        }
        """;

    /** Lightweight commercial signals collected on every page the agent opens. */
    private static final String AGENT_PAGE_SNAPSHOT_SCRIPT = """
        () => {
            const title = (document.title || '').trim();
            const h1 = Array.from(document.querySelectorAll('h1'))
                .map(h => h.innerText.trim()).filter(Boolean);
            const forms = document.querySelectorAll('form').length;
            const text = (document.body ? document.body.innerText : '') || '';
            const words = text.split(/\\s+/).filter(Boolean).length;
            const ctaRe = /(contact|связ|צור|קשר|whatsapp|wa\\.me|telegram|заявк|order|book|הזמנ|оставьте|оставьте заявку)/i;
            const hasCta = Array.from(document.querySelectorAll('a, button')).some(el => ctaRe.test(
                [el.innerText, el.getAttribute('aria-label'), el.getAttribute('href')].filter(Boolean).join(' ')
            ));
            const soft404 = /404|not found|page not found|הדף לא נמצא|страница не найдена/i.test(title + ' ' + text.slice(0, 800));
            return {
                title: title.slice(0, 160),
                h1: h1.slice(0, 3),
                formsCount: forms,
                wordCount: words,
                hasCta: hasCta,
                soft404: soft404,
                htmlLang: document.documentElement.getAttribute('lang'),
                dir: document.documentElement.getAttribute('dir')
                    || getComputedStyle(document.documentElement).direction
            };
        }
        """;

    private static final String HOMEPAGE_LINK_DISCOVERY_SCRIPT = """
        () => {
            const origin = location.origin;
            const out = [];
            const seen = new Set();
            for (const a of document.querySelectorAll('a[href]')) {
                try {
                    const url = new URL(a.href, location.href);
                    if (url.origin !== origin) continue;
                    if (url.protocol !== 'http:' && url.protocol !== 'https:') continue;
                    url.hash = '';
                    // Drop asset / admin noise early
                    if (/\\.(pdf|jpg|jpeg|png|gif|svg|webp|zip|mp4|css|js|xml)$/i.test(url.pathname)) continue;
                    if (/sitemap/i.test(url.pathname)) continue;
                    if (/\\/(wp-admin|cdn-cgi|cart|checkout)\\/?/i.test(url.pathname)) continue;
                    const key = url.origin + url.pathname.replace(/\\/$/, '') + (url.search || '');
                    if (seen.has(key)) continue;
                    seen.add(key);
                    out.push({
                        url: url.toString(),
                        path: url.pathname,
                        text: (a.innerText || a.getAttribute('aria-label') || '').trim().slice(0, 80)
                    });
                    if (out.length >= 80) break;
                } catch (e) {}
            }
            return out;
        }
        """;

    static Page.PdfOptions pdfOptions(Path target) {
        return new Page.PdfOptions()
                .setPath(target)
                .setFormat("A4")
                .setPrintBackground(true)
                .setPreferCSSPageSize(true)
                .setMargin(new Margin().setTop("0mm").setBottom("0mm").setLeft("0mm").setRight("0mm"));
    }

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

            // Separate phone context: builders like Wix pick the mobile layout by user agent
            BrowserContext mobileContext = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(MOBILE_VIEWPORT_WIDTH, MOBILE_VIEWPORT_HEIGHT)
                    .setDeviceScaleFactor(2)
                    .setIsMobile(true)
                    .setHasTouch(true)
                    .setUserAgent(MOBILE_USER_AGENT));
            Page mobilePage = mobileContext.newPage();

            Map<String, Object> aiVisibilityDom = null;

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
                try {
                    page.navigate(targetUrl, new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
                } catch (Exception e) {
                    System.out.println("  [!] Предупреждение при переходе на " + targetUrl + ": " + e.getMessage());
                }
                try {
                    page.evaluate(String.format("""
                        () => {
                            localStorage.setItem('i18nextLng', '%s');
                            localStorage.setItem('locale', '%s');
                            localStorage.setItem('lang', '%s');
                            document.cookie = 'NEXT_LOCALE=%s; path=/; max-age=31536000';
                            document.cookie = 'i18nextLng=%s; path=/; max-age=31536000';
                        }
                    """, loc, loc, loc, loc, loc));
                } catch (Exception ignored) {}
                try {
                    page.reload(new Page.ReloadOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
                } catch (Exception e) {
                    System.out.println("  [!] Предупреждение при перезагрузке: " + e.getMessage());
                }
                try {
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                } catch (Exception ignored) {}

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

                // Д. Лид-формы, антиспам, каналы мгновенного ответа, RTL и служебные иконки
                @SuppressWarnings("unchecked")
                Map<String, Object> pageSignals = (Map<String, Object>) page.evaluate(LEAD_AND_RTL_SCRIPT);
                localeResult.put("rtl_audit", pageSignals.get("rtl_audit"));
                localeResult.put("seo_assets", pageSignals.get("seo_assets"));

                Map<String, Object> leadCapture = new LinkedHashMap<>((Map<String, Object>) pageSignals.get("lead_capture"));
                if (asLong(leadCapture.get("formsCount"), 0) == 0) {
                    leadCapture = probeModalLeadForm(page, leadCapture);
                }
                localeResult.put("lead_capture", leadCapture);
                System.out.printf("  Лид-захват: форм %s, антиспам %s, мгновенный канал %s%n",
                        leadCapture.get("formsCount"),
                        ((Map<String, Object>) leadCapture.get("antiSpam")).get("isProtected"),
                        leadCapture.get("hasInstantContactChannel"));

                if (aiVisibilityDom == null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> domAi = (Map<String, Object>) page.evaluate(AI_VISIBILITY_SCRIPT);
                        aiVisibilityDom = domAi;
                        System.out.printf("  AI Visibility (DOM): Organization schema %s, llms link %s, dataLayer %s%n",
                                domAi.get("organization_schema"),
                                domAi.get("llms_describedby"),
                                domAi.get("data_layer_present"));
                    } catch (Exception e) {
                        System.err.println("  [!] AI Visibility DOM-проверки: " + e.getMessage());
                    }
                }

                // Е. Мобильная верстка: паразитный горизонтальный скролл на эмулированном iPhone SE
                Map<String, Object> localeMobileLayout = auditMobileLayout(mobilePage, targetUrl, loc);
                localeResult.put("mobile_layout_audit", localeMobileLayout);
                System.out.printf("  Мобильный экран 375px: перелив %s px%n", localeMobileLayout.getOrDefault("overflowPx", "n/a"));

                localeAudits.put(loc, localeResult);
            }

            // 3. Инфраструктура: TTFB, security headers, robots.txt / sitemap.xml
            Map<String, Object> infrastructure = auditInfrastructure(targetUrl);

            // 3b. AI Visibility: llms.txt, MCP, Organization schema, robots AI rules
            String origin = String.valueOf(infrastructure.getOrDefault("origin", originOf(targetUrl)));
            Map<String, Object> aiVisibility = collectAiVisibility(origin, aiVisibilityDom);
            finalReport.put("ai_visibility", aiVisibility);

            // 4. Агентный просмотр: обход ключевых внутренних страниц (не только главная)
            Map<String, Object> agentBrowse = runAgentBrowse(page, targetUrl, infrastructure, apiKey);

            // 5. Проверка производительности через PageSpeed Insights API
            Map<String, Object> pageSpeedData = fetchPageSpeedInsights(targetUrl, getPageSpeedApiKey());

            // 6. Формирование и сохранение итогового JSON
            finalReport.put("target_url", targetUrl);
            finalReport.put("locales", locales);
            finalReport.put("timestamp", System.currentTimeMillis());
            finalReport.put("failed_network_requests", new ArrayList<>(failedRequests));
            finalReport.put("console_logs", new ArrayList<>(consoleLogs));
            finalReport.put("uncaught_page_errors", new ArrayList<>(uncaughtPageErrors));
            finalReport.put("audits_by_locale", localeAudits);
            finalReport.put("infrastructure_and_security", infrastructure);
            finalReport.put("agent_browse", agentBrowse);
            finalReport.put("mobile_layout_audit", worstMobileLayout(localeAudits));
            finalReport.put("pagespeed_insights", pageSpeedData);
            // Scorecard is computed before the JSON dump so audit_data.json carries the same verdict as the PDF
            finalReport.put("executive_scorecard", calculateExecutiveScorecard(finalReport, targetUrl, locales));
            finalReport.put("allowed_exceptions", exceptionsConfig.allowedTextExceptions);

            File reportsDir = new File("reports");
            if (!reportsDir.exists()) {
                reportsDir.mkdirs();
            }

            objectMapper.writeValue(Paths.get(OUTPUT_FILE).toFile(), finalReport);
            System.out.println("\n[✓] Глубокий аудит завершен. Данные сохранены в `" + OUTPUT_FILE + "`.");

            // 7. Генерация Markdown-отчета
            String markdownReport = generateMarkdownReport(finalReport, targetUrl, locales);
            Files.writeString(Paths.get(MD_REPORT_FILE), markdownReport);
            System.out.println("[✓] Markdown-отчет сгенерирован в `" + MD_REPORT_FILE + "`.");

            // 8. Генерация PDF-отчета через Playwright
            try {
                String htmlReport = generateHtmlReport(finalReport, targetUrl, locales);
                Files.writeString(Paths.get(HTML_REPORT_FILE), htmlReport);
                System.out.println("[✓] HTML-отчет сохранен в `" + HTML_REPORT_FILE + "`.");
                Page pdfPage = context.newPage();
                pdfPage.setContent(htmlReport);
                pdfPage.emulateMedia(new Page.EmulateMediaOptions().setMedia(com.microsoft.playwright.options.Media.PRINT));
                pdfPage.evaluate("document.fonts.ready");

                Path pdfPath = Paths.get(PDF_REPORT_FILE).toAbsolutePath();
                try {
                    pdfPage.pdf(pdfOptions(pdfPath));
                    System.out.println("[✓] PDF-отчет сгенерирован в `" + pdfPath + "`.");
                } catch (Exception fileLockedExc) {
                    Path fallbackPath = Paths.get(PDF_REPORT_FALLBACK).toAbsolutePath();
                    pdfPage.pdf(pdfOptions(fallbackPath));
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

        Map<String, Object> localeAudits = (Map<String, Object>) finalReport.getOrDefault("audits_by_locale", Map.of());

        sb.append("## 4. 📱 MOBILE UX & RTL (375x667)\n");
        sb.append("| Locale | Overflow-X | Document Width | RTL Declared | Offenders |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");
        for (String loc : locales) {
            if (!(localeAudits.get(loc) instanceof Map)) continue;
            Map<String, Object> localeAudit = (Map<String, Object>) localeAudits.get(loc);
            Map<String, Object> layout = (Map<String, Object>) localeAudit.get("mobile_layout_audit");
            Map<String, Object> rtl = (Map<String, Object>) localeAudit.get("rtl_audit");
            List<Map<String, Object>> offenders = layout == null
                    ? List.of()
                    : (List<Map<String, Object>>) layout.getOrDefault("offenders", List.of());
            String offenderNames = offenders.stream()
                    .map(o -> String.valueOf(o.get("selector")))
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining(", "));
            sb.append("| `").append(loc).append("` | ")
                    .append(layout == null ? "-" : layout.getOrDefault("overflowPx", "-") + " px").append(" | ")
                    .append(layout == null ? "-" : String.valueOf(layout.getOrDefault("documentScrollWidth", "-"))).append(" | ")
                    .append(rtl == null ? "-" : String.valueOf(rtl.get("isRtlDeclared"))).append(" | ")
                    .append(offenderNames.isEmpty() ? "-" : cleanMd(offenderNames)).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 5. 🎯 LEAD CAPTURE & ANTI-SPAM\n");
        Map<String, Object> lead = aggregateLeadCapture(localeAudits);
        if (lead == null) {
            sb.append("Данные о лид-формах не собраны.\n\n");
        } else {
            sb.append("- **Форм на странице:** ").append(lead.get("formsCount")).append("\n");
            sb.append("- **Поля контакта:** email — ").append(yesNo(Boolean.TRUE.equals(lead.get("hasEmailField"))))
                    .append(", телефон — ").append(yesNo(Boolean.TRUE.equals(lead.get("hasPhoneField"))))
                    .append(", имя — ").append(yesNo(Boolean.TRUE.equals(lead.get("hasNameField")))).append("\n");
            sb.append("- **Кнопка отправки:** ").append(yesNo(Boolean.TRUE.equals(lead.get("hasSubmitButton")))).append("\n");
            List<String> providers = (List<String>) lead.getOrDefault("providers", List.of());
            sb.append("- **Защита от спама:** ").append(providers.isEmpty() ? "не обнаружена" : String.join(", ", providers)).append("\n");
            sb.append("- **Чат-виджет:** ").append(yesNo(Boolean.TRUE.equals(lead.get("hasChatWidget"))))
                    .append(notBlank(lead.get("chatWidgetSignature")) ? " (" + lead.get("chatWidgetSignature") + ")" : "").append("\n");
            List<String> messengers = (List<String>) lead.getOrDefault("messengerLinks", List.of());
            sb.append("- **Ссылки в мессенджеры:** ").append(messengers.isEmpty() ? "нет" : cleanMd(String.join(", ", messengers))).append("\n\n");
        }

        sb.append("## 6. 🔐 INFRASTRUCTURE & SECURITY HEADERS\n");
        Map<String, Object> infra = (Map<String, Object>) finalReport.get("infrastructure_and_security");
        if (infra == null) {
            sb.append("Данные инфраструктуры не собраны.\n\n");
        } else {
            sb.append("- **HTTPS:** ").append(infra.get("is_https"))
                    .append(" | **HTTP-статус:** ").append(infra.getOrDefault("http_status", "-"))
                    .append(" | **TTFB:** ").append(infra.getOrDefault("ttfb_ms", "-")).append(" ms\n");
            sb.append("- **robots.txt:** ").append(infra.getOrDefault("robots_txt_status", "-"))
                    .append(" | **sitemap.xml:** ").append(infra.getOrDefault("sitemap_xml_status", "-"))
                    .append(" (URL в карте: ").append(infra.getOrDefault("sitemap_urls_count", 0)).append(")\n\n");
            sb.append("| Security Header | Value |\n");
            sb.append("| :--- | :--- |\n");
            Map<String, Object> headers = (Map<String, Object>) infra.getOrDefault("security_headers", Map.of());
            for (Map.Entry<String, String> header : SECURITY_HEADERS.entrySet()) {
                Object value = headers.get(header.getKey());
                sb.append("| `").append(header.getValue()).append("` | ")
                        .append(notBlank(value) ? cleanMd(String.valueOf(value)) : "❌ отсутствует").append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 7. 🤖 AI VISIBILITY & BRAND DISCOVERY\n");
        Map<String, Object> aiVisibility = (Map<String, Object>) finalReport.get("ai_visibility");
        if (aiVisibility == null) {
            sb.append("Данные AI Visibility не собраны.\n\n");
        } else {
            sb.append("- **AI Visibility score:** ").append(aiVisibility.getOrDefault("score", "-"))
                    .append("/100 (").append(aiVisibility.getOrDefault("checks_passed", 0))
                    .append("/").append(aiVisibility.getOrDefault("checks_total", 0)).append(" checks)\n\n");
            sb.append("| Check | Status | Details | Fix hint |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");

            Map<String, Object> llms = aiVisibility.get("llms_txt") instanceof Map
                    ? (Map<String, Object>) aiVisibility.get("llms_txt") : Map.of();
            boolean llmsOk = Boolean.TRUE.equals(llms.get("ok"));
            sb.append("| llms.txt | ").append(llmsOk ? "✅" : "⚠️").append(" | ")
                    .append(llmsOk ? "HTTP 200, markdown OK" : "Код " + llms.getOrDefault("status", "err"))
                    .append(" | ").append(llmsOk ? "—" : "Создайте /llms.txt по спецификации llmstxt.org").append(" |\n");

            Map<String, Object> mcp = aiVisibility.get("mcp_manifest") instanceof Map
                    ? (Map<String, Object>) aiVisibility.get("mcp_manifest") : Map.of();
            boolean mcpOk = Boolean.TRUE.equals(mcp.get("ok"));
            sb.append("| MCP manifest | ").append(mcpOk ? "✅" : "⚠️").append(" | ")
                    .append(mcpOk ? "JSON mcp_version + endpoints" : "Код " + mcp.getOrDefault("status", "404"))
                    .append(" | ").append(mcpOk ? "—" : "Добавьте GET /.well-known/mcp (JSON manifest)").append(" |\n");

            Map<String, Object> dom = aiVisibility.get("dom") instanceof Map
                    ? (Map<String, Object>) aiVisibility.get("dom") : Map.of();
            boolean orgOk = Boolean.TRUE.equals(dom.get("organization_schema"));
            sb.append("| Organization schema | ").append(orgOk ? "✅" : "❌").append(" | ")
                    .append(orgOk ? "JSON-LD Organization на главной" : "Schema не найден")
                    .append(" | ").append(orgOk ? "—" : "JSON-LD Organization в layout с name, url, logo, sameAs, contactPoint").append(" |\n");

            Map<String, Object> about = aiVisibility.get("about_page") instanceof Map
                    ? (Map<String, Object>) aiVisibility.get("about_page") : Map.of();
            boolean aboutOk = Boolean.TRUE.equals(about.get("ok"));
            sb.append("| Brand Facts /about | ").append(aboutOk ? "✅" : "⚠️").append(" | ")
                    .append(aboutOk ? "HTTP 200" : "Страница недоступна")
                    .append(" | ").append(aboutOk ? "—" : "Опубликуйте /about с фактами бренда и контактами").append(" |\n");

            Map<String, Object> robotsAi = aiVisibility.get("robots_ai") instanceof Map
                    ? (Map<String, Object>) aiVisibility.get("robots_ai") : Map.of();
            boolean severe = Boolean.TRUE.equals(robotsAi.get("severe_block"));
            sb.append("| Robots AI bots | ").append(severe ? "❌" : "✅").append(" | ")
                    .append(severe ? "Disallow / для " + robotsAi.getOrDefault("blocked_bots", List.of()) : "Allow для AI-ботов")
                    .append(" | ").append(severe ? "Отключите Cloudflare Managed robots.txt или Allow AI crawlers" : "—").append(" |\n");

            boolean gaOk = Boolean.TRUE.equals(dom.get("data_layer_present")) && Boolean.TRUE.equals(dom.get("gtag_consent_stub"));
            sb.append("| GA4 / dataLayer | ").append(gaOk ? "✅" : "⚠️").append(" | ")
                    .append(gaOk ? "dataLayer + consent stub" : "Неполный bootstrap")
                    .append(" | ").append(gaOk ? "—" : "Добавьте GA4 Consent Mode stub в head до cookie banner").append(" |\n");

            if (Boolean.TRUE.equals(dom.get("faq_schema"))) {
                sb.append("| FAQ schema | ✅ | FAQPage mainEntity найден | — |\n");
            } else {
                sb.append("| FAQ schema | ⚠️ | FAQPage не найден | Добавьте FAQPage JSON-LD для AI-сниппетов |\n");
            }
            sb.append("\n");
        }

        sb.append("## 8. 🧭 AGENT BROWSE (КЛЮЧЕВЫЕ СТРАНИЦЫ)\n");
        Map<String, Object> agentBrowse = (Map<String, Object>) finalReport.get("agent_browse");
        if (agentBrowse == null) {
            sb.append("Агентный просмотр не выполнялся.\n\n");
        } else {
            sb.append("- **Просмотрено страниц:** ").append(agentBrowse.getOrDefault("pages_visited", 0))
                    .append(" / лимит ").append(agentBrowse.getOrDefault("max_pages", "-"))
                    .append(" (кандидатов: ").append(agentBrowse.getOrDefault("candidates_total", 0)).append(")\n");
            sb.append("- **Успешно открыто:** ").append(agentBrowse.getOrDefault("pages_ok", 0))
                    .append(" | битых/soft-404: ").append(agentBrowse.getOrDefault("pages_broken", 0)).append("\n");
            sb.append("- **CTA на внутренних:** ").append(agentBrowse.getOrDefault("inner_pages_with_cta", 0))
                    .append(" | форм на внутренних: ").append(agentBrowse.getOrDefault("inner_pages_forms_total", 0)).append("\n\n");
            sb.append("| URL | HTTP | Title | Forms | CTA | OK |\n");
            sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");
            for (Object pageObj : (List<Object>) agentBrowse.getOrDefault("pages", List.of())) {
                if (!(pageObj instanceof Map)) continue;
                Map<String, Object> p = (Map<String, Object>) pageObj;
                sb.append("| ").append(cleanMd(String.valueOf(p.get("url"))))
                        .append(" | ").append(p.getOrDefault("http_status", "-"))
                        .append(" | ").append(cleanMd(String.valueOf(p.getOrDefault("title", ""))))
                        .append(" | ").append(p.getOrDefault("formsCount", 0))
                        .append(" | ").append(p.getOrDefault("hasCta", false))
                        .append(" | ").append(p.getOrDefault("ok", false))
                        .append(" |\n");
            }
            sb.append("\n");
            Map<String, Object> gemini = (Map<String, Object>) agentBrowse.get("gemini_review");
            if (gemini != null && "SUCCESS".equals(gemini.get("status"))) {
                sb.append("**Gemini-вердикт:** ").append(cleanMd(String.valueOf(gemini.getOrDefault("verdict", "")))).append("\n");
                List<String> gaps = (List<String>) gemini.getOrDefault("gaps", List.of());
                if (!gaps.isEmpty()) {
                    sb.append("- Пробелы воронки: ").append(cleanMd(String.join("; ", gaps))).append("\n");
                }
                if (notBlank(gemini.get("priority_fix"))) {
                    sb.append("- Приоритет: ").append(cleanMd(String.valueOf(gemini.get("priority_fix")))).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /** Rebuild HTML from a saved audit_data.json payload (no live crawl). */
    public static String rebuildHtmlFromReport(Map<String, Object> finalReport, String targetUrl, List<String> locales) {
        return generateHtmlReport(finalReport, targetUrl, locales);
    }

    @SuppressWarnings("unchecked")
    private static String generateHtmlReport(Map<String, Object> finalReport, String targetUrl, List<String> locales) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        String dateStr = sdf.format(new Date());

        // Always recomputed from raw data so regenerating the PDF from audit_data.json cannot show a stale verdict
        Map<String, Object> scorecard = calculateExecutiveScorecard(finalReport, targetUrl, locales);
        finalReport.put("executive_scorecard", scorecard);

        int overallScore = (int) scorecard.get("overall_score");
        String grade = String.valueOf(scorecard.get("grade"));
        int speedScore = (int) scorecard.get("speed_mobile_ux");
        int leadScore = (int) scorecard.get("lead_gen_forms");
        int seoScore = (int) scorecard.get("seo_visibility");
        int secScore = (int) scorecard.get("security_stability");
        int aiVisScore = (int) scorecard.getOrDefault("ai_visibility", 50);
        List<Map<String, String>> topV = (List<Map<String, String>>) scorecard.get("top_vulnerabilities");

        Map<String, Object> psData = (Map<String, Object>) finalReport.get("pagespeed_insights");
        Map<String, Object> infra = (Map<String, Object>) finalReport.get("infrastructure_and_security");
        Map<String, Object> aiVisibility = (Map<String, Object>) finalReport.get("ai_visibility");
        Map<String, Object> mobileLayout = (Map<String, Object>) finalReport.get("mobile_layout_audit");
        Map<String, Object> localeAudits = (Map<String, Object>) finalReport.getOrDefault("audits_by_locale", Map.of());
        // Fall back to per-locale payloads when top-level aggregates are absent
        if (mobileLayout == null || infra == null) {
            for (Object locObj : localeAudits.values()) {
                if (!(locObj instanceof Map)) continue;
                Map<String, Object> lMap = (Map<String, Object>) locObj;
                if (mobileLayout == null && lMap.get("mobile_layout_audit") instanceof Map) {
                    mobileLayout = (Map<String, Object>) lMap.get("mobile_layout_audit");
                }
                if (infra == null && lMap.get("infrastructure_and_security") instanceof Map) {
                    infra = (Map<String, Object>) lMap.get("infrastructure_and_security");
                }
            }
        }

        List<Map<String, String>> lighthouseRows = new ArrayList<>();
        if (psData != null && !psData.isEmpty()) {
            for (String strat : List.of("mobile", "desktop")) {
                Map<String, Object> st = (Map<String, Object>) psData.get(strat);
                if (st != null && "SUCCESS".equals(st.get("status"))) {
                    Map<String, Object> sc = (Map<String, Object>) st.get("scores");
                    Map<String, Object> m = (Map<String, Object>) st.get("metrics");
                    String device = "mobile".equals(strat) ? "MOBILE (iPhone SE)" : "DESKTOP";
                    lighthouseRows.add(ReportTemplateGenerator.lighthouseRow(
                            device,
                            String.valueOf(((Number) sc.getOrDefault("performance", 0)).intValue()),
                            String.valueOf(((Number) sc.getOrDefault("accessibility", 0)).intValue()),
                            String.valueOf(((Number) sc.getOrDefault("best_practices", 0)).intValue()),
                            String.valueOf(((Number) sc.getOrDefault("seo", 0)).intValue()),
                            getMetricDisplay(m, "FCP"),
                            getMetricDisplay(m, "LCP"),
                            getMetricDisplay(m, "CLS")
                    ));
                }
            }
        }

        Map<String, Object> lead = aggregateLeadCapture(localeAudits);
        List<Map<String, Object>> uncaughtErrors = (List<Map<String, Object>>) finalReport.getOrDefault("uncaught_page_errors", List.of());
        List<Map<String, Object>> failedNetwork = (List<Map<String, Object>>) finalReport.getOrDefault("failed_network_requests", List.of());

        List<Map<String, String>> checkRows = new ArrayList<>();
        boolean hasOverflow = mobileLayout != null && Boolean.TRUE.equals(mobileLayout.get("hasHorizontalOverflow"));
        if (mobileLayout != null) {
            long overflowPx = asLong(mobileLayout.get("overflowPx"), 0);
            List<Map<String, Object>> offenders = (List<Map<String, Object>>) mobileLayout.getOrDefault("offenders", List.of());
            String offenderHint = offenders.isEmpty() ? "" : " Первый источник: " + offenders.get(0).get("selector") + ".";
            checkRows.add(ReportTemplateGenerator.checkRow(
                    "Паразитный боковой скролл (Overflow-X на смартфонах 375px)",
                    hasOverflow
                            ? "Контент выходит за границы экрана на " + overflowPx + "px." + offenderHint
                            : "Идеально подогнано под экран смартфона (0px перелива)",
                    hasOverflow ? "bad" : "good",
                    hasOverflow ? "Критично" : "Отлично"
            ));
        }
        if (localeAudits.get("he") instanceof Map) {
            Map<String, Object> heAudit = (Map<String, Object>) localeAudits.get("he");
            Map<String, Object> rtl = (Map<String, Object>) heAudit.get("rtl_audit");
            if (rtl != null) {
                boolean isRtl = Boolean.TRUE.equals(rtl.get("isRtlDeclared"));
                checkRows.add(ReportTemplateGenerator.checkRow(
                        "RTL-зеркалирование и верстка для Израиля (Иврит)",
                        isRtl
                                ? "Направление rtl активно, заголовки выровнены справа (text-align: " + rtl.getOrDefault("h1TextAlign", "n/a") + ")"
                                : "Отсутствует dir=\"rtl\": иврит выводится в левостороннем макете",
                        isRtl ? "good" : "bad",
                        isRtl ? "Норма" : "Критично"
                ));
            }
        }
        if (lead != null) {
            int formsCount = (int) asLong(lead.get("formsCount"), 0);
            boolean hasEmail = Boolean.TRUE.equals(lead.get("hasEmailField"));
            boolean hasPhone = Boolean.TRUE.equals(lead.get("hasPhoneField"));
            boolean hasSubmit = Boolean.TRUE.equals(lead.get("hasSubmitButton"));
            boolean formsOk = formsCount > 0 && hasSubmit && (hasEmail || hasPhone);
            boolean isProtected = Boolean.TRUE.equals(lead.get("isProtected"));
            List<String> providers = (List<String>) lead.getOrDefault("providers", List.of());
            boolean instantChannel = Boolean.TRUE.equals(lead.get("hasInstantContactChannel"));
            boolean hasChat = Boolean.TRUE.equals(lead.get("hasChatWidget"));
            List<String> messengers = (List<String>) lead.getOrDefault("messengerLinks", List.of());

            StringBuilder leadDetail = new StringBuilder();
            if (formsCount == 0) {
                leadDetail.append("Формы не найдены");
            } else {
                leadDetail.append("Форм: ").append(formsCount);
                if (Boolean.TRUE.equals(lead.get("openedFromTrigger"))) {
                    leadDetail.append(" (по кнопке)");
                }
                leadDetail.append(". Email: ").append(yesNo(hasEmail))
                        .append(", телефон: ").append(yesNo(hasPhone))
                        .append(", submit: ").append(yesNo(hasSubmit));
            }
            leadDetail.append(". Антиспам: ")
                    .append(isProtected ? String.join(", ", providers) : "не обнаружен");
            if (instantChannel) {
                leadDetail.append(hasChat
                        ? ". Мгновенный канал: чат " + lead.getOrDefault("chatWidgetSignature", "widget")
                        : ". Мгновенный канал: WhatsApp/Telegram (" + messengers.size() + ")");
            } else {
                leadDetail.append(". Мгновенный канал: нет");
            }

            String leadStatus = formsOk && isProtected ? "good"
                    : (formsCount == 0 || !isProtected ? "bad" : "warn");
            String leadLabel = formsOk && isProtected ? "Работает"
                    : (formsCount == 0 ? "Критично" : (isProtected ? "Требует внимания" : "Критично"));
            checkRows.add(ReportTemplateGenerator.checkRow(
                    "Лид-формы, антиспам и мгновенный ответ",
                    leadDetail.toString(),
                    leadStatus,
                    leadLabel
            ));
        }
        appendAiVisibilityCheckRows(checkRows, aiVisibility, infra);
        if (infra != null) {
            boolean hasHttps = Boolean.TRUE.equals(infra.get("is_https"));
            int headersPresent = (int) asLong(infra.get("security_headers_present"), 0);
            List<String> missingHeaders = (List<String>) infra.getOrDefault("security_headers_missing", List.of());
            String headerStatus = hasHttps && headersPresent >= 5 ? "good"
                    : (hasHttps && headersPresent >= 3 ? "warn" : "bad");
            String secDetail = hasHttps ? "HTTPS активен" : "Незащищённое HTTP";
            secDetail += "; заголовки " + headersPresent + "/" + SECURITY_HEADERS.size();
            if (!missingHeaders.isEmpty()) {
                secDetail += " (нет: " + String.join(", ", missingHeaders) + ")";
            }
            checkRows.add(ReportTemplateGenerator.checkRow(
                    "HTTPS и HTTP-заголовки безопасности",
                    secDetail,
                    headerStatus,
                    headersPresent >= 5 && hasHttps ? "Защищено"
                            : (hasHttps ? "Требует внимания" : "Опасно")
            ));
        } else if (targetUrl != null && targetUrl.startsWith("https://")) {
            checkRows.add(ReportTemplateGenerator.checkRow(
                    "HTTPS и HTTP-заголовки безопасности",
                    "Целевой URL использует HTTPS",
                    "good",
                    "Защищено"
            ));
        }
        boolean runtimeClean = uncaughtErrors.isEmpty() && failedNetwork.isEmpty();
        checkRows.add(ReportTemplateGenerator.checkRow(
                "Стабильность рантайма (JS-ошибки и битые запросы)",
                runtimeClean
                        ? "Падений JavaScript и ответов 4xx/5xx не зафиксировано"
                        : "JS-исключений: " + uncaughtErrors.size() + ", ответов 4xx/5xx: " + failedNetwork.size(),
                runtimeClean ? "good" : (uncaughtErrors.isEmpty() ? "warn" : "bad"),
                runtimeClean ? "Стабильно" : (uncaughtErrors.isEmpty() ? "Требует внимания" : "Критично")
        ));

        return ReportTemplateGenerator.buildAuditHtml(
                targetUrl,
                dateStr,
                String.join(", ", locales).toUpperCase(),
                overallScore,
                grade,
                speedScore,
                seoScore,
                leadScore,
                secScore,
                aiVisScore,
                topV,
                lighthouseRows,
                checkRows
        );
    }

    /** AI Visibility rows — condensed; indexing folded into robots line when infra present. */
    @SuppressWarnings("unchecked")
    private static void appendAiVisibilityCheckRows(
            List<Map<String, String>> checkRows,
            Map<String, Object> aiVisibility,
            Map<String, Object> infra) {
        if (aiVisibility == null) return;

        Map<String, Object> llms = aiVisibility.get("llms_txt") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("llms_txt") : Map.of();
        Map<String, Object> mcp = aiVisibility.get("mcp_manifest") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("mcp_manifest") : Map.of();
        Map<String, Object> about = aiVisibility.get("about_page") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("about_page") : Map.of();
        Map<String, Object> robotsAi = aiVisibility.get("robots_ai") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("robots_ai") : Map.of();
        Map<String, Object> dom = aiVisibility.get("dom") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("dom") : Map.of();

        boolean llmsOk = Boolean.TRUE.equals(llms.get("ok"));
        boolean mcpOk = Boolean.TRUE.equals(mcp.get("ok"));
        boolean discoveryOk = llmsOk && mcpOk;
        checkRows.add(ReportTemplateGenerator.checkRow(
                "llms.txt и MCP discovery",
                discoveryOk
                        ? "/llms.txt HTTP " + llms.getOrDefault("status", 200)
                          + "; /.well-known/mcp JSON OK"
                        : "llms: " + (llmsOk ? "OK" : llms.getOrDefault("status", "fail"))
                          + "; MCP: " + (mcpOk ? "OK" : mcp.getOrDefault("status", "fail")),
                discoveryOk ? "good" : "warn",
                discoveryOk ? "В норме" : "Требует внимания"
        ));

        boolean orgOk = Boolean.TRUE.equals(dom.get("organization_schema"));
        boolean aboutOk = Boolean.TRUE.equals(about.get("ok"));
        boolean brandOk = orgOk && aboutOk;
        checkRows.add(ReportTemplateGenerator.checkRow(
                "Brand facts: JSON-LD и /about",
                brandOk
                        ? "Organization schema на главной; /about HTTP " + about.getOrDefault("status", 200)
                        : "Отсутствует: " + missingList(
                                orgOk ? null : "Organization schema",
                                aboutOk ? null : "/about"),
                brandOk ? "good" : (orgOk || aboutOk ? "warn" : "bad"),
                brandOk ? "В норме" : (orgOk || aboutOk ? "Частично" : "Критично")
        ));

        boolean severeBlock = Boolean.TRUE.equals(robotsAi.get("severe_block"));
        List<String> allowed = robotsAi.get("allowed_bots") instanceof List
                ? (List<String>) robotsAi.get("allowed_bots") : List.of();
        boolean hasRobots = infra != null && Boolean.TRUE.equals(infra.get("has_robots_txt"));
        boolean hasSitemap = infra != null && Boolean.TRUE.equals(infra.get("has_sitemap_xml"));
        String robotsDetail = severeBlock
                ? "Disallow: / для ботов: " + robotsAi.getOrDefault("blocked_bots", List.of())
                : "Allow для AI-ботов" + (allowed.isEmpty() ? "" : ": " + String.join(", ", allowed));
        if (infra != null) {
            robotsDetail += "; robots.txt: " + (hasRobots ? "200 OK" : infra.getOrDefault("robots_txt_status", "err"));
            robotsDetail += "; sitemap: " + (hasSitemap
                    ? "200 OK, " + infra.getOrDefault("sitemap_urls_count", 0) + " URL"
                    : infra.getOrDefault("sitemap_xml_status", "err"));
        }
        boolean robotsOk = !severeBlock && (infra == null || (hasRobots && hasSitemap));
        checkRows.add(ReportTemplateGenerator.checkRow(
                "Robots.txt: AI-боты и индексация",
                robotsDetail,
                severeBlock ? "bad" : (robotsOk ? "good" : "warn"),
                severeBlock ? "Критично" : (robotsOk ? "В норме" : "Требует внимания")
        ));

        boolean dataLayer = Boolean.TRUE.equals(dom.get("data_layer_present"));
        boolean consentStub = Boolean.TRUE.equals(dom.get("gtag_consent_stub"));
        boolean gaOk = dataLayer && consentStub;
        checkRows.add(ReportTemplateGenerator.checkRow(
                "GA4 / dataLayer readiness",
                gaOk
                        ? "dataLayer + consent stub в head (GA4 "
                          + (Boolean.TRUE.equals(dom.get("ga4_id_present")) ? "G- ID найден" : "stub only") + ")"
                        : dataLayer
                                ? "dataLayer есть, consent stub неполный"
                                : "Нет сигналов dataLayer / gtag consent",
                gaOk ? "good" : (dataLayer ? "warn" : "bad"),
                gaOk ? "В норме" : (dataLayer ? "Частично" : "Критично")
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> calculateExecutiveScorecard(Map<String, Object> report, String targetUrl, List<String> locales) {
        Map<String, Object> scorecard = new LinkedHashMap<>();

        Map<String, Object> psData = (Map<String, Object>) report.get("pagespeed_insights");
        Map<String, Object> mobilePs = psData != null ? (Map<String, Object>) psData.get("mobile") : null;
        Map<String, Object> desktopPs = psData != null ? (Map<String, Object>) psData.get("desktop") : null;
        Map<String, Object> mobileScores = mobilePs != null ? (Map<String, Object>) mobilePs.get("scores") : null;
        if (mobileScores == null && desktopPs != null) {
            mobileScores = (Map<String, Object>) desktopPs.get("scores");
        }
        Map<String, Object> mobileLayout = (Map<String, Object>) report.get("mobile_layout_audit");
        Map<String, Object> infra = (Map<String, Object>) report.get("infrastructure_and_security");
        List<Map<String, Object>> failedNetwork = (List<Map<String, Object>>) report.getOrDefault("failed_network_requests", List.of());
        List<Map<String, Object>> uncaughtErrors = (List<Map<String, Object>>) report.getOrDefault("uncaught_page_errors", List.of());
        Map<String, Object> localeAudits = (Map<String, Object>) report.getOrDefault("audits_by_locale", Map.of());
        if (mobileLayout == null || infra == null) {
            for (Object locObj : localeAudits.values()) {
                if (!(locObj instanceof Map)) continue;
                Map<String, Object> lMap = (Map<String, Object>) locObj;
                if (mobileLayout == null && lMap.get("mobile_layout_audit") instanceof Map) {
                    mobileLayout = (Map<String, Object>) lMap.get("mobile_layout_audit");
                }
                if (infra == null && lMap.get("infrastructure_and_security") instanceof Map) {
                    infra = (Map<String, Object>) lMap.get("infrastructure_and_security");
                }
            }
        }

        Map<String, Object> mobileMetrics = mobilePs != null ? (Map<String, Object>) mobilePs.get("metrics") : null;
        Map<String, Object> lead = aggregateLeadCapture(localeAudits);
        Map<String, Object> primaryLocale = primaryLocaleAudit(localeAudits, locales);
        Map<String, Object> primarySeo = primaryLocale == null ? null : (Map<String, Object>) primaryLocale.get("seo");
        Map<String, Object> primaryAssets = primaryLocale == null ? null : (Map<String, Object>) primaryLocale.get("seo_assets");

        boolean hasOverflow = mobileLayout != null && Boolean.TRUE.equals(mobileLayout.get("hasHorizontalOverflow"));
        long overflowPx = mobileLayout == null ? 0 : asLong(mobileLayout.get("overflowPx"), 0);
        String overflowSelector = null;
        if (mobileLayout != null) {
            List<Map<String, Object>> offenders = (List<Map<String, Object>>) mobileLayout.getOrDefault("offenders", List.of());
            if (!offenders.isEmpty()) {
                overflowSelector = String.valueOf(offenders.get(0).get("selector"));
            }
        }
        long ttfbMs = infra == null ? -1 : asLong(infra.get("ttfb_ms"), -1);
        int headersPresent = infra == null ? 0 : (int) asLong(infra.get("security_headers_present"), 0);
        List<String> missingHeaders = infra == null
                ? List.of()
                : (List<String>) infra.getOrDefault("security_headers_missing", List.of());
        boolean hasRobots = infra != null && Boolean.TRUE.equals(infra.get("has_robots_txt"));
        boolean hasSitemap = infra != null && Boolean.TRUE.equals(infra.get("has_sitemap_xml"));
        boolean isHttps = infra != null
                ? Boolean.TRUE.equals(infra.get("is_https"))
                : targetUrl != null && targetUrl.startsWith("https://");

        boolean hasForms = lead != null && asLong(lead.get("formsCount"), 0) > 0;
        boolean hasContactField = lead != null
                && (Boolean.TRUE.equals(lead.get("hasEmailField")) || Boolean.TRUE.equals(lead.get("hasPhoneField")));
        boolean hasSubmit = lead != null && Boolean.TRUE.equals(lead.get("hasSubmitButton"));
        boolean isProtected = lead != null && Boolean.TRUE.equals(lead.get("isProtected"));
        boolean hasInstantChannel = lead != null && Boolean.TRUE.equals(lead.get("hasInstantContactChannel"));

        int mobilePerformance = mobileScores != null && mobileScores.containsKey("performance")
                ? ((Number) mobileScores.get("performance")).intValue()
                : 70;
        int speedScore = mobilePerformance;
        if (hasOverflow) speedScore -= 25;
        if (ttfbMs > 1500) speedScore -= 15;
        else if (ttfbMs > 600) speedScore -= 5;
        speedScore = clamp(speedScore, 10, 100);

        int leadScore = 35;
        if (hasForms) leadScore += 25;
        if (hasContactField) leadScore += 10;
        if (hasSubmit) leadScore += 5;
        if (isProtected) leadScore += 15;
        if (hasInstantChannel) leadScore += 10;
        // Broken JS blocks submit handlers, so runtime failures hit lead capture directly
        if (!uncaughtErrors.isEmpty()) leadScore -= 10;
        leadScore = clamp(leadScore, 10, 100);

        int seoScore = 65;
        if (mobileScores != null && mobileScores.containsKey("seo")) {
            seoScore = ((Number) mobileScores.get("seo")).intValue();
        }
        boolean hasOgImage = primarySeo != null && notBlank(primarySeo.get("ogImage"));
        boolean hasCanonical = primarySeo != null && notBlank(primarySeo.get("canonical"));
        List<Object> hreflangs = primaryAssets == null
                ? List.of()
                : (List<Object>) primaryAssets.getOrDefault("hreflangs", List.of());
        boolean hasFavicon = primaryAssets != null && notBlank(primaryAssets.get("favicon"));
        if (infra != null) {
            if (!hasRobots) seoScore -= 8;
            if (!hasSitemap) seoScore -= 10;
        }
        if (primarySeo != null && !hasOgImage) seoScore -= 8;
        if (primarySeo != null && !hasCanonical) seoScore -= 5;
        if (locales.size() > 1 && hreflangs.isEmpty()) seoScore -= 6;
        if (primaryAssets != null && !hasFavicon) seoScore -= 3;
        seoScore = clamp(seoScore, 10, 100);

        int secScore = isHttps ? 58 : 15;
        secScore += headersPresent * 7;
        if (!uncaughtErrors.isEmpty()) secScore -= 20;
        if (!failedNetwork.isEmpty()) secScore -= 10;
        if (ttfbMs > 1500) secScore -= 5;

        Map<String, Object> aiVisibility = report.get("ai_visibility") instanceof Map
                ? (Map<String, Object>) report.get("ai_visibility") : null;
        int aiVisScore = aiVisibility != null ? (int) asLong(aiVisibility.get("score"), 50) : 50;
        Map<String, Object> aiDom = aiVisibility != null && aiVisibility.get("dom") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("dom") : null;
        Map<String, Object> aiLlms = aiVisibility != null && aiVisibility.get("llms_txt") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("llms_txt") : null;
        Map<String, Object> aiMcp = aiVisibility != null && aiVisibility.get("mcp_manifest") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("mcp_manifest") : null;
        Map<String, Object> aiRobots = aiVisibility != null && aiVisibility.get("robots_ai") instanceof Map
                ? (Map<String, Object>) aiVisibility.get("robots_ai") : null;
        aiVisScore = clamp(aiVisScore, 10, 100);

        Map<String, Object> agentBrowse = report.get("agent_browse") instanceof Map
                ? (Map<String, Object>) report.get("agent_browse") : null;
        int agentBroken = agentBrowse == null ? 0 : (int) asLong(agentBrowse.get("pages_broken"), 0);
        int agentVisited = agentBrowse == null ? 0 : (int) asLong(agentBrowse.get("pages_visited"), 0);
        int agentCtaInner = agentBrowse == null ? 0 : (int) asLong(agentBrowse.get("inner_pages_with_cta"), 0);
        int agentCandidates = agentBrowse == null ? 0 : (int) asLong(agentBrowse.get("candidates_total"), 0);
        if (agentBroken > 0) {
            seoScore -= Math.min(15, agentBroken * 8);
            leadScore -= 5;
        }
        if (agentVisited > 1 && agentCtaInner == 0 && !hasInstantChannel) {
            leadScore -= 8;
        }
        if (agentBrowse != null && Boolean.TRUE.equals(agentBrowse.get("shallow_site"))) {
            seoScore -= 4;
        }

        secScore = clamp(secScore, 10, 100);
        leadScore = clamp(leadScore, 10, 100);
        seoScore = clamp(seoScore, 10, 100);

        int overallScore = (int) Math.round(
                speedScore * 0.27 + leadScore * 0.22 + seoScore * 0.22 + secScore * 0.18 + aiVisScore * 0.11);
        String grade = switch (overallScore / 10) {
            case 10, 9 -> "A+";
            case 8 -> "A";
            case 7 -> "B";
            case 6 -> "C";
            case 5 -> "D";
            default -> "F";
        };

        scorecard.put("overall_score", overallScore);
        scorecard.put("grade", grade);
        scorecard.put("speed_mobile_ux", speedScore);
        scorecard.put("lead_gen_forms", leadScore);
        scorecard.put("seo_visibility", seoScore);
        scorecard.put("security_stability", secScore);
        scorecard.put("ai_visibility", aiVisScore);

        List<Finding> findings = new ArrayList<>();

        if (hasForms && !isProtected) {
            findings.add(new Finding(96,
                    "Лид-формы открыты для ботов и спам-заявок",
                    "Найдено форм: " + asLong(lead.get("formsCount"), 0)
                            + " — ни одна не защищена Cloudflare Turnstile, reCAPTCHA или honeypot-ловушкой.",
                    "Менеджеры разбирают спам вместо звонков реальным клиентам, а email-рассылки уходят в папку «Спам».",
                    "Подключение Cloudflare Turnstile и AI-фильтрации заявок Erythro.ai перед попаданием лида в CRM."));
        }
        if (!hasForms) {
            findings.add(new Finding(99,
                    "На сайте нет формы захвата заявок",
                    "Аудит не нашел ни одной формы с полем контакта и кнопкой отправки на главной странице.",
                    "Весь трафик уходит без следа: посетитель не может оставить заявку, даже если готов купить.",
                    "Внедрение конверсионной лид-формы с мгновенной доставкой заявки в WhatsApp и CRM силами Erythro.ai."));
        }
        if (!uncaughtErrors.isEmpty()) {
            findings.add(new Finding(92,
                    "JavaScript падает в браузере и блокирует кнопки",
                    "Зафиксировано необработанных JS-исключений: " + uncaughtErrors.size() + " (PageError в рантайме).",
                    "Кнопки и отправка форм могут не срабатывать — заявка не доходит до отдела продаж, а клиент уходит к конкуренту.",
                    "Аудит и исправление фронтенд-ошибок, покрытие критичных сценариев автотестами Playwright от Erythro.ai."));
        }
        if (hasOverflow) {
            findings.add(new Finding(88,
                    "Паразитный боковой скролл ломает мобильную верстку",
                    "На экране 375px контент выходит за границы на " + overflowPx + "px"
                            + (overflowSelector == null ? "" : " (источник: " + overflowSelector + ")") + ".",
                    "Мобильные посетители видят «сломанный» сайт и уходят с первого экрана, сжигая бюджет Google Ads и Meta Ads.",
                    "Адаптивная переверстка под 375–430px и регрессионные проверки мобильной сетки от Erythro.ai."));
        }
        if (locales.contains("he")) {
            Map<String, Object> heAudit = localeAudits.get("he") instanceof Map
                    ? (Map<String, Object>) localeAudits.get("he") : null;
            Map<String, Object> rtl = heAudit == null ? null : (Map<String, Object>) heAudit.get("rtl_audit");
            if (rtl != null && !Boolean.TRUE.equals(rtl.get("isRtlDeclared"))) {
                findings.add(new Finding(86,
                        "Иврит выводится в левостороннем макете (нет RTL)",
                        "В версии he отсутствует dir=\"rtl\": направление документа определено как "
                                + rtl.getOrDefault("computedDirection", "ltr") + ".",
                        "Израильская аудитория воспринимает сайт как непрофессиональный перевод и не оставляет заявки.",
                        "Полноценная RTL-адаптация верстки, меню и слайдеров под израильский рынок от Erythro.ai."));
            }
        }
        int seriousA11y = countSeriousA11yViolations(localeAudits);
        if (seriousA11y > 0) {
            findings.add(new Finding(78,
                    "Нарушения доступности (WCAG 2.1 AA / IS 5568)",
                    "axe-core обнаружил критичных и серьезных нарушений: " + seriousA11y + ".",
                    "В Израиле доступность сайта регулируется стандартом IS 5568 — нарушения несут юридический риск и штрафы.",
                    "Приведение контрастности, alt-текстов и семантики к WCAG 2.1 AA с протоколом соответствия от Erythro.ai."));
        }
        if (ttfbMs > 1500) {
            findings.add(new Finding(76,
                    "Сервер отвечает медленно (TTFB " + ttfbMs + " ms)",
                    "Время до первого байта " + ttfbMs + " ms при целевом значении до 600 ms.",
                    "Каждая секунда ожидания снижает конверсию рекламного трафика и повышает стоимость лида.",
                    "Перенос на быстрый хостинг, CDN-кэширование и серверная оптимизация от Erythro.ai."));
        }
        if (speedScore < 60) {
            findings.add(new Finding(74,
                    "Медленная загрузка на смартфонах сжигает рекламный бюджет",
                    "Мобильный балл Lighthouse: " + mobilePerformance + "/100"
                            + (mobileMetrics == null ? "" : ", LCP " + getMetricDisplay(mobileMetrics, "LCP")) + ".",
                    "25–40% посетителей закрывают страницу до первого экрана, оплаченный клик не превращается в заявку.",
                    "Оптимизация Core Web Vitals: Next-Gen изображения, критический CSS и предзагрузка LCP-элемента."));
        }
        if (!hasInstantChannel) {
            findings.add(new Finding(64,
                    "Нет канала мгновенного ответа на заявку",
                    "На странице не найдено ни чат-виджета, ни прямых ссылок в WhatsApp или Telegram.",
                    "Горячий лид остывает: без ответа в первые 15 минут вероятность сделки падает в разы, особенно вне рабочих часов.",
                    "Подключение AI-агентов n8n/CRM Erythro.ai с мгновенным ответом в WhatsApp и Telegram 24/7."));
        }
        if (infra != null && (!hasRobots || !hasSitemap)) {
            findings.add(new Finding(58,
                    "Отсутствуют служебные файлы индексации",
                    missingList(hasRobots ? null : "robots.txt", hasSitemap ? null : "sitemap.xml") + " недоступны для поисковых роботов.",
                    "Google медленнее находит и переобходит страницы — сайт теряет позиции и бесплатный поисковый трафик.",
                    "Настройка карты сайта, директив индексации и мониторинга Search Console от Erythro.ai."));
        }
        if (infra != null && missingHeaders.size() >= 3) {
            findings.add(new Finding(52,
                    "Не настроены HTTP-заголовки безопасности",
                    "Настроено " + headersPresent + " из " + SECURITY_HEADERS.size()
                            + ", отсутствуют: " + String.join(", ", missingHeaders) + ".",
                    "Сайт уязвим к встраиванию в чужие фреймы и подмене контента, а браузеры считают его менее надежным.",
                    "Внедрение HSTS, CSP и X-Frame-Options с проверкой на регресс силами Erythro.ai."));
        }
        if (primarySeo != null && !hasOgImage) {
            findings.add(new Finding(46,
                    "Ссылка на сайт выглядит непрезентабельно в мессенджерах",
                    "Не задан og:image — WhatsApp, Telegram и LinkedIn показывают ссылку без превью.",
                    "Пересылаемые ссылки получают меньше переходов, что снижает эффективность сарафанного и партнерского трафика.",
                    "Настройка OpenGraph-разметки и брендированных превью для всех локалей от Erythro.ai."));
        }
        if (aiLlms != null && !Boolean.TRUE.equals(aiLlms.get("ok"))) {
            findings.add(new Finding(68,
                    "Отсутствует llms.txt для AI-ассистентов",
                    "Файл /llms.txt недоступен или не соответствует спецификации llmstxt.org.",
                    "ChatGPT, Claude и Perplexity хуже понимают бренд и услуги — AI-трафик и рекомендации уходят конкурентам.",
                    "Создайте /llms.txt по спецификации llmstxt.org с описанием компании, услуг и контактов."));
        }
        if (aiMcp != null && !Boolean.TRUE.equals(aiMcp.get("ok"))) {
            findings.add(new Finding(66,
                    "Нет MCP discovery manifest",
                    "GET /.well-known/mcp не возвращает JSON с mcp_version и endpoints.",
                    "AI-агенты и IDE-плагины не могут автоматически обнаружить API бренда и ключевые страницы.",
                    "Добавьте GET /.well-known/mcp (JSON manifest) с версией и списком endpoints."));
        }
        if (aiDom != null && !Boolean.TRUE.equals(aiDom.get("organization_schema"))) {
            findings.add(new Finding(62,
                    "Нет Organization schema (JSON-LD)",
                    "На главной не найден JSON-LD Organization/LocalBusiness с name и url.",
                    "AI-поисковики и Google Knowledge Panel хуже связывают бренд с сайтом и контактами.",
                    "JSON-LD Organization в layout с name, url, logo, sameAs, contactPoint."));
        }
        if (aiRobots != null && Boolean.TRUE.equals(aiRobots.get("severe_block"))) {
            findings.add(new Finding(80,
                    "Robots.txt блокирует AI-краулеров на корне",
                    "Disallow: / для ботов: " + aiRobots.getOrDefault("blocked_bots", List.of()) + ".",
                    "GPTBot, ClaudeBot и Google-Extended не индексируют сайт — бренд исчезает из AI-ответов.",
                    "Отключите Cloudflare Managed robots.txt или добавьте Allow: / для AI-ботов."));
        }
        if (aiDom != null && !Boolean.TRUE.equals(aiDom.get("data_layer_present"))) {
            findings.add(new Finding(42,
                    "Нет GA4 / dataLayer bootstrap",
                    "window.dataLayer или consent stub не найден в head до cookie banner.",
                    "Без Consent Mode stub теряются сигналы конверсии и модели атрибуции рекламных кампаний.",
                    "Добавьте GA4 Consent Mode stub в head до cookie banner."));
        }
        if (!failedNetwork.isEmpty()) {
            findings.add(new Finding(44,
                    "Битые сетевые запросы на странице",
                    "Ответов со статусом 4xx/5xx: " + failedNetwork.size() + ".",
                    "Часть ресурсов не загружается: страница отображается неполно, а аналитика получает искаженные данные.",
                    "Ревизия сетевых запросов и удаление битых интеграций и ссылок от Erythro.ai."));
        }
        if (agentBroken > 0) {
            findings.add(new Finding(82,
                    "Агентный просмотр нашел битые или пустые страницы",
                    "Из " + agentVisited + " открытых страниц недоступны или soft-404: " + agentBroken + ".",
                    "Посетитель уходит с тупика в меню — рекламный и органический трафик не доходит до заявки.",
                    "Ремонт битых маршрутов, редиректы и регрессионный агентный обход ключевых страниц от Erythro.ai."));
        }
        if (agentVisited > 1 && agentCtaInner == 0 && !hasForms) {
            findings.add(new Finding(70,
                    "На внутренних страницах нет пути к заявке",
                    "Агент обошел " + agentVisited + " страниц: на внутренних нет CTA и форм захвата.",
                    "Пользователь читает услуги или меню, но не находит, куда оставить контакт — лид теряется в середине воронки.",
                    "Сквозные CTA и форма/WhatsApp на ключевых посадочных страницах с агентной проверкой пути Erythro.ai."));
        }
        if (agentCandidates <= 1 && agentVisited <= 1) {
            findings.add(new Finding(48,
                    "Сайт почти одностраничный — узкая воронка",
                    "Агент нашел всего " + agentCandidates + " внутренних URL для обхода.",
                    "Нет посадочных под услуги/контакты: сложнее ранжироваться и сложнее закрывать разные сегменты трафика.",
                    "Структура из ключевых посадочных страниц и карта сайта под коммерческие сценарии от Erythro.ai."));
        }

        findings.sort(Comparator.comparingInt(Finding::severity).reversed());
        List<Map<String, String>> topVulnerabilities = new ArrayList<>();
        for (Finding finding : findings) {
            if (topVulnerabilities.size() == 3) break;
            topVulnerabilities.add(Map.of(
                    "title", finding.title(),
                    "issue", finding.issue(),
                    "impact", finding.impact(),
                    "solution", finding.solution()
            ));
        }
        // Commercial report always shows three plates: pad with growth opportunities when the site is clean
        Finding[] growthPads = {
                new Finding(0,
                        "Ускорение Core Web Vitals и мобильного LCP",
                        "Даже при хороших средних баллах мобильные LCP и INP можно довести до целевых 1.5 s и 200 ms.",
                        "Каждые +100 ms задержки снижают конверсию рекламного трафика и увеличивают стоимость лида.",
                        "Сжатие Next-Gen изображений, критический CSS и CDN-кэширование от Erythro.ai."),
                new Finding(0,
                        "AI-квалификация входящих заявок",
                        "Заявки обрабатываются вручную, без автоматической квалификации и распределения по менеджерам.",
                        "Время первого ответа растет, а часть заявок теряется между почтой, мессенджерами и CRM.",
                        "AI-агенты n8n/CRM Erythro.ai: квалификация лида, авторассылка и постановка задач 24/7."),
                new Finding(0,
                        "Расширение мультиязычной воронки",
                        "Локали работают, но нет отдельных сценариев прогрева и посадочных страниц под каждый рынок.",
                        "Единый оффер для разных рынков конвертирует хуже локализованных сценариев.",
                        "Кросс-культурная адаптация посадочных страниц и офферов под RU / EN / HE от Erythro.ai.")
        };
        int padIdx = 0;
        while (topVulnerabilities.size() < 3 && padIdx < growthPads.length) {
            Finding pad = growthPads[padIdx++];
            topVulnerabilities.add(Map.of(
                    "title", pad.title(),
                    "issue", pad.issue(),
                    "impact", pad.impact(),
                    "solution", pad.solution()
            ));
        }
        scorecard.put("top_vulnerabilities", topVulnerabilities);
        scorecard.put("findings_total", findings.size());
        return scorecard;
    }

    /** One prioritized business finding; severity drives the Top-3 order. */
    private record Finding(int severity, String title, String issue, String impact, String solution) {}

    @SuppressWarnings("unchecked")
    private static int countSeriousA11yViolations(Map<String, Object> localeAudits) {
        int total = 0;
        for (Object localeObj : localeAudits.values()) {
            if (!(localeObj instanceof Map)) continue;
            Object violationsObj = ((Map<String, Object>) localeObj).get("axe_wcag_violations");
            if (!(violationsObj instanceof List)) continue;
            for (Object violationObj : (List<Object>) violationsObj) {
                if (!(violationObj instanceof Map)) continue;
                String impact = String.valueOf(((Map<String, Object>) violationObj).get("impact"));
                if ("critical".equalsIgnoreCase(impact) || "serious".equalsIgnoreCase(impact)) {
                    total++;
                }
            }
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Modern sites mount the contact form only after a click, so a static DOM scan reports zero forms.
     * Clicks the most likely trigger and rescans, then closes the modal to keep the page state clean.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> probeModalLeadForm(Page page, Map<String, Object> staticScan) {
        Object triggerLabel;
        try {
            triggerLabel = page.evaluate(CONTACT_TRIGGER_SCRIPT);
        } catch (Exception e) {
            return staticScan;
        }
        if (triggerLabel == null) {
            return staticScan;
        }
        try {
            // Native DOM click: cookie banners and splash overlays intercept real pointer events
            page.evaluate("() => document.querySelector('[data-erythro-audit-trigger=\"1\"]').click()");
            page.waitForTimeout(1800);
            Map<String, Object> rescan = (Map<String, Object>) page.evaluate(LEAD_AND_RTL_SCRIPT);
            Map<String, Object> modalLead = new LinkedHashMap<>((Map<String, Object>) rescan.get("lead_capture"));
            if (asLong(modalLead.get("formsCount"), 0) > 0) {
                modalLead.put("openedFromTrigger", true);
                modalLead.put("triggerLabel", String.valueOf(triggerLabel));
                System.out.println("  Форма найдена после клика по «" + triggerLabel + "»");
                return modalLead;
            }
        } catch (Exception e) {
            System.out.println("  [!] Не удалось раскрыть форму по кнопке «" + triggerLabel + "»: " + e.getMessage());
        } finally {
            try {
                page.keyboard().press("Escape");
                page.waitForTimeout(400);
            } catch (Exception ignored) {}
        }
        staticScan.put("openedFromTrigger", false);
        staticScan.put("triggerLabel", String.valueOf(triggerLabel));
        return staticScan;
    }

    /**
     * Agent browse: discover commercially important same-origin pages (sitemap + nav links),
     * open up to N of them, collect conversion signals, optionally ask Gemini for a short verdict.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> runAgentBrowse(Page page, String targetUrl,
                                                      Map<String, Object> infrastructure, String geminiApiKey) {
        System.out.println("\n[+] Агентный просмотр: поиск и обход ключевых страниц сайта...");
        Map<String, Object> result = new LinkedHashMap<>();
        int maxPages = getAgentBrowseMaxPages();
        result.put("max_pages", maxPages);

        URI base;
        try {
            base = URI.create(targetUrl);
        } catch (Exception e) {
            result.put("error", "Некорректный URL: " + targetUrl);
            return result;
        }
        String origin = base.getScheme() + "://" + base.getAuthority();
        String homeKey = normalizeUrlKey(targetUrl);

        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();

        // Homepage is always visited first
        Map<String, Object> home = candidate(targetUrl, "/", "homepage", "homepage", 1000);
        byKey.put(homeKey, home);

        List<String> sitemapLocs = (List<String>) infrastructure.getOrDefault("sitemap_locs", List.of());
        for (String loc : sitemapLocs) {
            if (!loc.startsWith(origin)) continue;
            if (loc.toLowerCase().contains("sitemap") || loc.toLowerCase().endsWith(".xml")) continue;
            String key = normalizeUrlKey(loc);
            if (byKey.containsKey(key)) continue;
            byKey.put(key, candidate(loc, pathOf(loc), "", "sitemap", scoreAgentPage(pathOf(loc), "")));
        }

        try {
            page.navigate(targetUrl, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));
            page.waitForTimeout(800);
            List<Map<String, Object>> links = (List<Map<String, Object>>) page.evaluate(HOMEPAGE_LINK_DISCOVERY_SCRIPT);
            for (Map<String, Object> link : links) {
                String url = String.valueOf(link.get("url"));
                String key = normalizeUrlKey(url);
                String path = String.valueOf(link.getOrDefault("path", ""));
                String text = String.valueOf(link.getOrDefault("text", ""));
                int score = scoreAgentPage(path, text);
                Map<String, Object> existing = byKey.get(key);
                if (existing == null) {
                    byKey.put(key, candidate(url, path, text, "nav", score));
                } else {
                    existing.put("score", Math.max(asLong(existing.get("score"), 0), score));
                    if (!notBlank(existing.get("text")) && notBlank(text)) existing.put("text", text);
                    if ("sitemap".equals(existing.get("source"))) existing.put("source", "sitemap+nav");
                }
            }
            result.put("nav_links_discovered", links.size());
        } catch (Exception e) {
            result.put("nav_discovery_error", e.getMessage());
            System.err.println("  [!] Не удалось собрать ссылки с главной: " + e.getMessage());
        }

        candidates.addAll(byKey.values());
        candidates.sort((a, b) -> Long.compare(asLong(b.get("score"), 0), asLong(a.get("score"), 0)));
        result.put("candidates_total", candidates.size());

        List<Map<String, Object>> selected = new ArrayList<>();
        selected.add(home);
        for (Map<String, Object> c : candidates) {
            if (selected.size() >= maxPages) break;
            if (homeKey.equals(normalizeUrlKey(String.valueOf(c.get("url"))))) continue;
            // Prefer commercially scored pages; still fill with remaining sitemap/nav if scores are flat
            selected.add(c);
        }
        result.put("selected", selected.stream()
                .map(c -> Map.of(
                        "url", String.valueOf(c.get("url")),
                        "source", String.valueOf(c.get("source")),
                        "score", c.get("score"),
                        "text", String.valueOf(c.getOrDefault("text", ""))
                )).toList());

        List<Map<String, Object>> visited = new ArrayList<>();
        int okCount = 0;
        int soft404Count = 0;
        int formsOnInner = 0;
        int ctaOnInner = 0;
        List<String> broken = new ArrayList<>();

        for (Map<String, Object> pick : selected) {
            String url = String.valueOf(pick.get("url"));
            Map<String, Object> visit = new LinkedHashMap<>();
            visit.put("url", url);
            visit.put("source", pick.get("source"));
            visit.put("score", pick.get("score"));
            try {
                Response response = page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(45000));
                page.waitForTimeout(700);
                int status = response == null ? 0 : response.status();
                visit.put("http_status", status);
                Map<String, Object> snap = (Map<String, Object>) page.evaluate(AGENT_PAGE_SNAPSHOT_SCRIPT);
                visit.putAll(snap);
                boolean soft404 = Boolean.TRUE.equals(snap.get("soft404")) || status >= 400;
                visit.put("ok", status > 0 && status < 400 && !Boolean.TRUE.equals(snap.get("soft404")));
                if (Boolean.TRUE.equals(visit.get("ok"))) okCount++;
                if (soft404) {
                    soft404Count++;
                    broken.add(url);
                }
                boolean isHome = homeKey.equals(normalizeUrlKey(url));
                if (!isHome) {
                    formsOnInner += (int) asLong(snap.get("formsCount"), 0);
                    if (Boolean.TRUE.equals(snap.get("hasCta"))) ctaOnInner++;
                }
                System.out.printf("  → %s [%s] forms=%s cta=%s%n",
                        url, status,
                        snap.get("formsCount"),
                        snap.get("hasCta"));
            } catch (Exception e) {
                visit.put("ok", false);
                visit.put("error", e.getMessage());
                soft404Count++;
                broken.add(url);
                System.err.println("  [!] Агент не открыл " + url + ": " + e.getMessage());
            }
            visited.add(visit);
        }

        result.put("pages_visited", visited.size());
        result.put("pages_ok", okCount);
        result.put("pages_broken", soft404Count);
        result.put("broken_urls", broken);
        result.put("inner_pages_with_cta", ctaOnInner);
        result.put("inner_pages_forms_total", formsOnInner);
        result.put("pages", visited);

        boolean shallowSite = candidates.size() <= 1;
        boolean missingContactPath = selected.stream().noneMatch(c -> {
            String bag = (c.get("path") + " " + c.get("text")).toLowerCase();
            return bag.contains("contact") || bag.contains("контакт") || bag.contains("קשר") || bag.contains("связ");
        });
        result.put("shallow_site", shallowSite);
        result.put("missing_contact_page_in_sample", missingContactPath && !shallowSite);

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            Map<String, Object> gemini = askGeminiAgentBrowse(geminiApiKey, targetUrl, visited);
            result.put("gemini_review", gemini);
        } else {
            result.put("gemini_review", Map.of("status", "SKIPPED", "reason", "GEMINI_API_KEY не задан"));
        }

        System.out.printf("  Агентный просмотр: %d/%d страниц OK, кандидатов %d%n",
                okCount, visited.size(), candidates.size());
        return result;
    }

    private static Map<String, Object> candidate(String url, String path, String text, String source, long score) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("url", url);
        c.put("path", path == null ? "" : path);
        c.put("text", text == null ? "" : text);
        c.put("source", source);
        c.put("score", score);
        return c;
    }

    private static int scoreAgentPage(String path, String text) {
        String bag = ((path == null ? "" : path) + " " + (text == null ? "" : text)).toLowerCase();
        int score = 1;
        for (String hint : AGENT_PAGE_PRIORITY_HINTS) {
            if (bag.contains(hint.toLowerCase())) score += 40;
        }
        // Prefer shallow paths (/, /about) over deep blog posts
        long depth = path == null ? 0 : Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).count();
        if (depth <= 1) score += 10;
        if (depth >= 4) score -= 15;
        return score;
    }

    private static String normalizeUrlKey(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getPath() == null || u.getPath().isBlank() ? "/" : u.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = u.getQuery() == null ? "" : "?" + u.getQuery();
            return (u.getScheme() + "://" + u.getAuthority() + path + query).toLowerCase();
        } catch (Exception e) {
            return url == null ? "" : url.toLowerCase();
        }
    }

    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (Exception e) {
            return "/";
        }
    }

    private static int getAgentBrowseMaxPages() {
        String env = System.getenv("AGENT_BROWSE_MAX_PAGES");
        if (env == null || env.isBlank()) env = System.getProperty("AGENT_BROWSE_MAX_PAGES");
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(1, Math.min(15, Integer.parseInt(env.trim())));
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_AGENT_BROWSE_MAX_PAGES;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> askGeminiAgentBrowse(String apiKey, String targetUrl,
                                                            List<Map<String, Object>> visited) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder pagesBlock = new StringBuilder();
            for (Map<String, Object> page : visited) {
                pagesBlock.append("- URL: ").append(page.get("url"))
                        .append(" | status: ").append(page.getOrDefault("http_status", "?"))
                        .append(" | title: ").append(page.getOrDefault("title", ""))
                        .append(" | forms: ").append(page.getOrDefault("formsCount", 0))
                        .append(" | CTA: ").append(page.getOrDefault("hasCta", false))
                        .append(" | soft404: ").append(page.getOrDefault("soft404", false))
                        .append("\n");
            }
            String prompt = """
                Ты — коммерческий QA-агент Erythro.ai. По результатам обхода страниц сайта %s\
                 оцени пользовательский путь к заявке. Ответь строго JSON без markdown:
                {"verdict":"короткий вердикт на русском до 180 символов",\
                "gaps":["до 3 пробелов воронки на русском"],\
                "priority_fix":"одна приоритетная доработка на русском"}
                Страницы:
                %s
                """.formatted(targetUrl, pagesBlock);

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", Map.of(
                            "temperature", 0.2,
                            "responseMimeType", "application/json"
                    )
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                            + GEMINI_MODEL + ":generateContent?key=" + apiKey))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            out.put("http_status", response.statusCode());
            if (response.statusCode() != 200) {
                out.put("status", "ERROR");
                out.put("error", response.body() != null && response.body().length() > 300
                        ? response.body().substring(0, 300) : response.body());
                return out;
            }
            JsonNode root = mapper.readTree(response.body());
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            out.put("raw_text", text);
            try {
                JsonNode parsed = mapper.readTree(text);
                out.put("status", "SUCCESS");
                out.put("verdict", parsed.path("verdict").asText(""));
                List<String> gaps = new ArrayList<>();
                if (parsed.path("gaps").isArray()) {
                    parsed.path("gaps").forEach(n -> gaps.add(n.asText()));
                }
                out.put("gaps", gaps);
                out.put("priority_fix", parsed.path("priority_fix").asText(""));
            } catch (Exception parseExc) {
                out.put("status", "SUCCESS");
                out.put("verdict", text.length() > 220 ? text.substring(0, 220) : text);
            }
            System.out.println("  Gemini-разбор агентного просмотра: " + out.getOrDefault("verdict", ""));
        } catch (Exception e) {
            out.put("status", "FAILED");
            out.put("error", e.getMessage());
            System.err.println("  [!] Gemini agent browse: " + e.getMessage());
        }
        return out;
    }

    /** Loads the page in an emulated iPhone SE to catch parasitic horizontal scroll. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> auditMobileLayout(Page mobilePage, String targetUrl, String locale) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", "iPhone SE (" + MOBILE_VIEWPORT_WIDTH + "x" + MOBILE_VIEWPORT_HEIGHT + ", mobile user agent)");
        try {
            mobilePage.context().addCookies(List.of(
                    new com.microsoft.playwright.options.Cookie("NEXT_LOCALE", locale).setUrl(targetUrl),
                    new com.microsoft.playwright.options.Cookie("i18nextLng", locale).setUrl(targetUrl),
                    new com.microsoft.playwright.options.Cookie("locale", locale).setUrl(targetUrl)
            ));
            mobilePage.navigate(targetUrl, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60000));
            mobilePage.waitForTimeout(1800);
            result.putAll((Map<String, Object>) mobilePage.evaluate(MOBILE_OVERFLOW_SCRIPT));
        } catch (Exception e) {
            System.err.println("  [!] Не удалось проверить мобильную верстку: " + e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** HTTP layer: TLS, TTFB, security headers, robots.txt and sitemap.xml. */
    private static Map<String, Object> auditInfrastructure(String targetUrl) {
        System.out.println("\n[+] Проверка инфраструктуры: TTFB, security headers, robots.txt / sitemap.xml...");
        Map<String, Object> infra = new LinkedHashMap<>();

        URI base;
        try {
            base = URI.create(targetUrl);
        } catch (Exception e) {
            infra.put("error", "Некорректный целевой URL: " + targetUrl);
            return infra;
        }
        String origin = base.getScheme() + "://" + base.getAuthority();
        infra.put("origin", origin);
        infra.put("is_https", "https".equalsIgnoreCase(base.getScheme()));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try {
            // Warm-up request so TTFB is not distorted by TLS handshake and cold cache
            client.send(documentRequest(base), HttpResponse.BodyHandlers.discarding());

            long startNs = System.nanoTime();
            HttpResponse<java.io.InputStream> response = client.send(documentRequest(base), HttpResponse.BodyHandlers.ofInputStream());
            long ttfbMs = (System.nanoTime() - startNs) / 1_000_000L;
            try (java.io.InputStream body = response.body()) {
                body.readNBytes(1);
            }

            infra.put("http_status", response.statusCode());
            infra.put("ttfb_ms", ttfbMs);
            infra.put("server", response.headers().firstValue("server").orElse(null));

            Map<String, Object> securityHeaders = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String> header : SECURITY_HEADERS.entrySet()) {
                String value = response.headers().firstValue(header.getKey()).orElse(null);
                securityHeaders.put(header.getKey(), value);
                if (value == null || value.isBlank()) {
                    missing.add(header.getValue());
                }
            }
            infra.put("security_headers", securityHeaders);
            infra.put("security_headers_missing", missing);
            infra.put("security_headers_present", SECURITY_HEADERS.size() - missing.size());
            System.out.println("  TTFB: " + ttfbMs + " ms | Security headers: "
                    + (SECURITY_HEADERS.size() - missing.size()) + " из " + SECURITY_HEADERS.size());
        } catch (Exception e) {
            System.err.println("  [!] Не удалось измерить TTFB и заголовки безопасности: " + e.getMessage());
            infra.put("document_error", e.getMessage());
        }

        List<String> sitemapCandidates = new ArrayList<>();
        try {
            HttpResponse<String> robots = client.send(documentRequest(URI.create(origin + "/robots.txt")),
                    HttpResponse.BodyHandlers.ofString());
            String body = robots.body() == null ? "" : robots.body();
            // SPA hosts often answer 200 with index.html instead of a real robots.txt
            boolean isRealRobots = robots.statusCode() == 200 && !body.stripLeading().startsWith("<");
            infra.put("robots_txt_status", robots.statusCode());
            infra.put("has_robots_txt", isRealRobots);
            if (isRealRobots) {
                for (String line : body.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.toLowerCase().startsWith("sitemap:")) {
                        String url = trimmed.substring("sitemap:".length()).trim();
                        if (!url.isEmpty()) sitemapCandidates.add(url);
                    }
                }
            }
            infra.put("robots_sitemap_directives", new ArrayList<>(sitemapCandidates));
        } catch (Exception e) {
            infra.put("has_robots_txt", false);
            infra.put("robots_txt_status", "ERROR");
            infra.put("robots_txt_error", e.getMessage());
        }

        String defaultSitemap = origin + "/sitemap.xml";
        if (!sitemapCandidates.contains(defaultSitemap)) {
            sitemapCandidates.add(defaultSitemap);
        }
        boolean sitemapFound = false;
        Object lastStatus = "ERROR";
        String sitemapUrl = null;
        String sitemapBody = "";
        int sitemapUrlsCount = 0;
        for (String candidate : sitemapCandidates) {
            try {
                HttpResponse<String> sitemap = client.send(documentRequest(URI.create(candidate)),
                        HttpResponse.BodyHandlers.ofString());
                lastStatus = sitemap.statusCode();
                String body = sitemap.body() == null ? "" : sitemap.body();
                if (sitemap.statusCode() == 200 && (body.contains("<urlset") || body.contains("<sitemapindex"))) {
                    sitemapFound = true;
                    sitemapUrl = candidate;
                    sitemapBody = body;
                    sitemapUrlsCount = countOccurrences(body, "<loc>");
                    break;
                }
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        infra.put("has_sitemap_xml", sitemapFound);
        infra.put("sitemap_xml_status", lastStatus);
        infra.put("sitemap_url", sitemapUrl);
        infra.put("sitemap_urls_count", sitemapUrlsCount);
        infra.put("sitemap_locs", extractSitemapLocs(sitemapBody, 40));
        System.out.println("  robots.txt: " + infra.get("robots_txt_status")
                + " | sitemap.xml: " + (sitemapFound ? "200 OK (" + sitemapUrlsCount + " URL)" : lastStatus));

        return infra;
    }

    private static String originOf(String targetUrl) {
        try {
            URI base = URI.create(targetUrl);
            return base.getScheme() + "://" + base.getAuthority();
        } catch (Exception e) {
            return targetUrl;
        }
    }

    /** HTTP + DOM checks for AI assistants, MCP discovery, brand facts and GA4 consent readiness. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> collectAiVisibility(String origin, Map<String, Object> domSignals) {
        System.out.println("\n[+] AI Visibility: llms.txt, MCP manifest, /about, robots AI rules, JSON-LD...");
        Map<String, Object> aiVis = new LinkedHashMap<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String host = "";
        try {
            host = URI.create(origin).getHost();
        } catch (Exception ignored) {}

        Map<String, Object> llms = fetchLlmsTxt(client, origin, host);
        aiVis.put("llms_txt", llms);

        Map<String, Object> mcpManifest = fetchMcpManifest(client, origin);
        aiVis.put("mcp_manifest", mcpManifest);

        Map<String, Object> mcpBrand = fetchMcpBrandApi(client, origin);
        aiVis.put("mcp_brand_api", mcpBrand);

        Map<String, Object> aboutPage = fetchAboutPage(client, origin);
        aiVis.put("about_page", aboutPage);

        Map<String, Object> robotsAi = fetchRobotsAiRules(client, origin);
        aiVis.put("robots_ai", robotsAi);

        Map<String, Object> dom = domSignals != null ? new LinkedHashMap<>(domSignals) : new LinkedHashMap<>();
        aiVis.put("dom", dom);

        int checksPassed = 0;
        int checksTotal = 7;
        if (Boolean.TRUE.equals(llms.get("ok"))) checksPassed++;
        if (Boolean.TRUE.equals(mcpManifest.get("ok"))) checksPassed++;
        if (Boolean.TRUE.equals(aboutPage.get("ok"))) checksPassed++;
        if (!Boolean.TRUE.equals(robotsAi.get("severe_block"))) checksPassed++;
        if (Boolean.TRUE.equals(dom.get("organization_schema"))) checksPassed++;
        if (Boolean.TRUE.equals(dom.get("llms_describedby"))) checksPassed++;
        if (Boolean.TRUE.equals(dom.get("data_layer_present")) && Boolean.TRUE.equals(dom.get("gtag_consent_stub"))) {
            checksPassed++;
        } else if (Boolean.TRUE.equals(dom.get("data_layer_present"))) {
            // partial credit for dataLayer without full consent stub
            checksPassed++;
        }

        int score = checksTotal == 0 ? 0 : (int) Math.round(checksPassed * 100.0 / checksTotal);
        aiVis.put("checks_passed", checksPassed);
        aiVis.put("checks_total", checksTotal);
        aiVis.put("score", score);

        System.out.printf("  AI Visibility score: %d/%d (%d%%) | llms.txt %s | MCP %s | robots severe_block %s%n",
                checksPassed, checksTotal, score,
                Boolean.TRUE.equals(llms.get("ok")) ? "OK" : "FAIL",
                Boolean.TRUE.equals(mcpManifest.get("ok")) ? "OK" : "FAIL",
                robotsAi.get("severe_block"));
        return aiVis;
    }

    private static Map<String, Object> fetchLlmsTxt(HttpClient client, String origin, String host) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = client.send(
                    documentRequest(URI.create(origin + "/llms.txt")),
                    HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            result.put("status", response.statusCode());
            boolean startsMarkdown = body.stripLeading().startsWith("#");
            boolean hasBrand = body.contains("Erythro") || (!host.isEmpty() && body.toLowerCase().contains(host.toLowerCase()));
            boolean hasStructure = body.contains("##") || body.contains(">");
            boolean ok = response.statusCode() == 200 && startsMarkdown && hasBrand && hasStructure;
            result.put("ok", ok);
            result.put("has_h1", startsMarkdown);
            result.put("has_brand", hasBrand);
            result.put("has_structure", hasStructure);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> fetchMcpManifest(HttpClient client, String origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = client.send(
                    documentRequest(URI.create(origin + "/.well-known/mcp")),
                    HttpResponse.BodyHandlers.ofString());
            result.put("status", response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            result.put("content_type", contentType);
            boolean jsonType = contentType.toLowerCase().contains("json");
            boolean hasVersion = false;
            boolean hasEndpoints = false;
            if (response.statusCode() == 200 && jsonType) {
                JsonNode root = new ObjectMapper().readTree(response.body());
                hasVersion = root.has("mcp_version");
                hasEndpoints = root.has("endpoints");
            }
            result.put("has_mcp_version", hasVersion);
            result.put("has_endpoints", hasEndpoints);
            result.put("ok", response.statusCode() == 200 && jsonType && hasVersion && hasEndpoints);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> fetchMcpBrandApi(HttpClient client, String origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = client.send(
                    documentRequest(URI.create(origin + "/api/mcp")),
                    HttpResponse.BodyHandlers.ofString());
            result.put("status", response.statusCode());
            boolean hasName = false;
            boolean hasUrl = false;
            boolean hasCanonical = false;
            if (response.statusCode() == 200) {
                JsonNode root = new ObjectMapper().readTree(response.body());
                hasName = root.has("name");
                hasUrl = root.has("url");
                hasCanonical = root.has("canonicalPages");
            }
            result.put("has_name", hasName);
            result.put("has_url", hasUrl);
            result.put("has_canonical_pages", hasCanonical);
            result.put("ok", response.statusCode() == 200 && hasName && hasUrl);
            result.put("warn_missing_canonical", response.statusCode() == 200 && hasName && hasUrl && !hasCanonical);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> fetchAboutPage(HttpClient client, String origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = client.send(
                    documentRequest(URI.create(origin + "/about")),
                    HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            String lower = body.toLowerCase();
            result.put("status", response.statusCode());
            boolean hasBrandFacts = lower.contains("brand facts") || lower.contains("organization")
                    || body.contains("<dl") || lower.contains("tel:") || lower.contains("mailto:");
            result.put("has_brand_markers", hasBrandFacts);
            result.put("ok", response.statusCode() == 200 && hasBrandFacts);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> fetchRobotsAiRules(HttpClient client, String origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpResponse<String> response = client.send(
                    documentRequest(URI.create(origin + "/robots.txt")),
                    HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            result.put("status", response.statusCode());
            if (response.statusCode() != 200 || body.stripLeading().startsWith("<")) {
                result.put("severe_block", false);
                result.put("blocked_bots", List.of());
                result.put("allowed_bots", List.of());
                result.put("cloudflare_managed", false);
                result.put("ok", false);
                return result;
            }
            Map<String, Object> parsed = parseRobotsAiAccess(body);
            result.putAll(parsed);
            result.put("ok", !Boolean.TRUE.equals(parsed.get("severe_block")));
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("severe_block", false);
            result.put("blocked_bots", List.of());
            result.put("allowed_bots", List.of());
            result.put("cloudflare_managed", false);
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** Detects Cloudflare Managed robots.txt and AI crawler allow/disallow on root. */
    private static Map<String, Object> parseRobotsAiAccess(String robotsBody) {
        List<String> blockedBots = new ArrayList<>();
        List<String> allowedBots = new ArrayList<>();
        boolean severeBlock = false;
        boolean cloudflareManaged = robotsBody.contains("BEGIN Cloudflare Managed");

        Map<String, List<String>> rulesByAgent = new LinkedHashMap<>();
        String currentAgent = "*";

        for (String line : robotsBody.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).trim();
            if ("user-agent".equals(key)) {
                currentAgent = value.toLowerCase(Locale.ROOT);
                rulesByAgent.computeIfAbsent(currentAgent, k -> new ArrayList<>());
            } else if ("disallow".equals(key) || "allow".equals(key)) {
                rulesByAgent.computeIfAbsent(currentAgent, k -> new ArrayList<>()).add(key + ":" + value);
            }
        }

        for (String bot : AI_CRAWLER_BOTS) {
            String botLower = bot.toLowerCase(Locale.ROOT);
            List<String> rules = rulesByAgent.get(botLower);
            if (rules == null) {
                rules = rulesByAgent.get("*");
            }
            if (rules == null) {
                allowedBots.add(bot);
                continue;
            }
            boolean hasAllowRoot = rules.stream().anyMatch(r -> {
                String v = r.substring(r.indexOf(':') + 1).trim();
                return r.startsWith("allow:") && ("/".equals(v) || v.isEmpty());
            });
            boolean hasDisallowRoot = rules.stream().anyMatch(r -> {
                String v = r.substring(r.indexOf(':') + 1).trim();
                return r.startsWith("disallow:") && ("/".equals(v) || v.isEmpty());
            });
            if (hasDisallowRoot && !hasAllowRoot) {
                severeBlock = true;
                blockedBots.add(bot);
            } else {
                allowedBots.add(bot);
            }
        }

        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("severe_block", severeBlock);
        parsed.put("blocked_bots", blockedBots);
        parsed.put("allowed_bots", allowedBots);
        parsed.put("cloudflare_managed", cloudflareManaged);
        return parsed;
    }

    private static List<String> extractSitemapLocs(String body, int limit) {
        List<String> locs = new ArrayList<>();
        if (body == null || body.isBlank()) return locs;
        int idx = 0;
        while (locs.size() < limit) {
            int start = body.indexOf("<loc>", idx);
            if (start < 0) break;
            int end = body.indexOf("</loc>", start);
            if (end < 0) break;
            String loc = body.substring(start + 5, end).trim();
            if (!loc.isEmpty() && !locs.contains(loc)) locs.add(loc);
            idx = end + 6;
        }
        return locs;
    }

    private static HttpRequest documentRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (compatible; Erythro-auditor/1.0; +https://erythro.ai/)")
                .header("Accept", "*/*")
                .GET()
                .build();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    /** Picks the locale with the widest horizontal overflow so the report shows the worst case. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> worstMobileLayout(Map<String, Object> localeAudits) {
        Map<String, Object> worst = null;
        long worstOverflow = -1;
        for (Object localeObj : localeAudits.values()) {
            if (!(localeObj instanceof Map)) continue;
            Object layoutObj = ((Map<String, Object>) localeObj).get("mobile_layout_audit");
            if (!(layoutObj instanceof Map)) continue;
            Map<String, Object> layout = (Map<String, Object>) layoutObj;
            long overflow = asLong(layout.get("overflowPx"), 0);
            if (overflow > worstOverflow) {
                worstOverflow = overflow;
                worst = layout;
            }
        }
        return worst;
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    /** Merges per-locale lead capture signals: the site is credited with the best result of any locale. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> aggregateLeadCapture(Map<String, Object> localeAudits) {
        Map<String, Object> aggregated = new LinkedHashMap<>();
        long maxForms = 0;
        boolean hasEmail = false, hasPhone = false, hasName = false, hasSubmit = false;
        boolean isProtected = false, hasChat = false, hasInstant = false, openedFromTrigger = false;
        Set<String> providers = new LinkedHashSet<>();
        Set<String> messengers = new LinkedHashSet<>();
        String chatSignature = null;
        boolean found = false;

        for (Object localeObj : localeAudits.values()) {
            if (!(localeObj instanceof Map)) continue;
            Object leadObj = ((Map<String, Object>) localeObj).get("lead_capture");
            if (!(leadObj instanceof Map)) continue;
            found = true;
            Map<String, Object> lead = (Map<String, Object>) leadObj;
            maxForms = Math.max(maxForms, asLong(lead.get("formsCount"), 0));

            for (Object formObj : (List<Object>) lead.getOrDefault("forms", List.of())) {
                if (!(formObj instanceof Map)) continue;
                Map<String, Object> form = (Map<String, Object>) formObj;
                hasEmail |= Boolean.TRUE.equals(form.get("hasEmailField"));
                hasPhone |= Boolean.TRUE.equals(form.get("hasPhoneField"));
                hasName |= Boolean.TRUE.equals(form.get("hasNameField"));
                hasSubmit |= Boolean.TRUE.equals(form.get("hasSubmitButton"));
            }

            Object antiSpamObj = lead.get("antiSpam");
            if (antiSpamObj instanceof Map) {
                Map<String, Object> antiSpam = (Map<String, Object>) antiSpamObj;
                isProtected |= Boolean.TRUE.equals(antiSpam.get("isProtected"));
                for (Object provider : (List<Object>) antiSpam.getOrDefault("providers", List.of())) {
                    providers.add(String.valueOf(provider));
                }
            }
            if (Boolean.TRUE.equals(lead.get("hasChatWidget"))) {
                hasChat = true;
                if (chatSignature == null && notBlank(lead.get("chatWidgetSignature"))) {
                    chatSignature = String.valueOf(lead.get("chatWidgetSignature"));
                }
            }
            hasInstant |= Boolean.TRUE.equals(lead.get("hasInstantContactChannel"));
            openedFromTrigger |= Boolean.TRUE.equals(lead.get("openedFromTrigger"));
            for (Object link : (List<Object>) lead.getOrDefault("messengerLinks", List.of())) {
                messengers.add(String.valueOf(link));
            }
        }

        if (!found) return null;

        aggregated.put("formsCount", maxForms);
        aggregated.put("hasEmailField", hasEmail);
        aggregated.put("hasPhoneField", hasPhone);
        aggregated.put("hasNameField", hasName);
        aggregated.put("hasSubmitButton", hasSubmit);
        aggregated.put("openedFromTrigger", openedFromTrigger);
        aggregated.put("isProtected", isProtected);
        aggregated.put("providers", new ArrayList<>(providers));
        aggregated.put("hasChatWidget", hasChat);
        aggregated.put("chatWidgetSignature", chatSignature);
        aggregated.put("hasInstantContactChannel", hasInstant);
        aggregated.put("messengerLinks", new ArrayList<>(messengers));
        return aggregated;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> primaryLocaleAudit(Map<String, Object> localeAudits, List<String> locales) {
        for (String loc : locales) {
            if (localeAudits.get(loc) instanceof Map) {
                return (Map<String, Object>) localeAudits.get(loc);
            }
        }
        for (Object localeObj : localeAudits.values()) {
            if (localeObj instanceof Map) {
                return (Map<String, Object>) localeObj;
            }
        }
        return null;
    }

    private static boolean notBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank() && !"null".equals(String.valueOf(value));
    }

    private static String yesNo(boolean value) {
        return value ? "да" : "нет";
    }

    /** Joins only the non-null names, used for "missing X, Y" phrasing. */
    private static String missingList(String... names) {
        List<String> present = new ArrayList<>();
        for (String name : names) {
            if (name != null) present.add(name);
        }
        return present.isEmpty() ? "нет" : String.join(", ", present);
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
