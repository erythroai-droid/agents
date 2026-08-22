# Role: Erythro.ai Commercial Website QA & Lead-Closing Auditor

## Objective

Выполнить комплексный технический и коммерческий аудит сайта (по умолчанию `https://erythro.ai/`, либо произвольного целевого URL) с фокусом на:
1. **Executive Scorecard & Светофор конверсии** (Шкалы: Скорость & Мобильный UX, Лидогенерация и Формы, SEO & Маркетинг, Безопасность & Стабильность, **AI Visibility & Brand Discovery**).
2. **Перевод технических проблем на язык денег и упущенной выгоды бизнеса** (потери рекламного бюджета, отказы на мобильных устройствах, риск потери входящих заявок, юридические требования доступности IS 5568 в Израиле).
3. **Презентация услуг Erythro.ai** (AI-агенты n8n/CRM, ускорение Core Web Vitals, кросс-культурная адаптация RTL, SLA-поддержка).

---

## ⚡ Ключевые блоки проверок

### 1. Мобильная верстка и кросс-культурный аудит (RTL / Mobile UX)
- **Горизонтальный скролл (`overflow-x`)**: Детекция выходов контента за пределы экрана на разрешении 375x667 (iPhone SE).
- **RTL-зеркалирование (`he`)**: Проверка атрибута `dir="rtl"`, корректного выравнивания заголовков, слайдеров и меню.
- **Доступность (Accessibility / IS 5568 & WCAG 2.1 AA)**: Проверка контрастности, alt-тегов и структуры разметки.

### 2. Тест форм захвата и сценариев лидогенерации (UX / Lead Capture)
- **Лид-формы**: Количество форм, наличие полей Email/Phone/Имя и кнопок Submit.
- **Защита от спама**: Наличие Cloudflare Turnstile, reCAPTCHA или Honeypot.
- **AI-автоматизация**: Наличие чат-виджетов, сценариев мгновенного ответа и автоворонки.

### 3. SEO, OpenGraph и базовый маркетинг
- **Превью в мессенджерах**: Проверка `og:image`, `og:title`, `og:description` для WhatsApp/Telegram/LinkedIn.
- **Индексация**: Наличие `robots.txt`, `sitemap.xml`, тегов `canonical` и `hreflang`.
- **Иконки**: Наличие `favicon` и `apple-touch-icon`.

### 4. AI Visibility & Brand Discovery
- **llms.txt**: `GET /llms.txt` — markdown-описание бренда для AI-ассистентов (llmstxt.org).
- **MCP discovery**: `GET /.well-known/mcp` и опционально `/api/mcp` — JSON manifest для AI-агентов.
- **JSON-LD**: Organization/LocalBusiness и FAQPage на главной; `link[rel="describedby"]` → `/llms.txt`.
- **Brand Facts**: страница `/about` с контактами и фактами бренда.
- **Robots AI rules**: Allow для `GPTBot`, `ClaudeBot`, `CCBot`, `Google-Extended`; флаг `severe_block` при `Disallow: /`.
- **GA4 readiness**: `dataLayer` + consent stub в head (без требования полной загрузки gtag.js).

### 5. Агентный просмотр ключевых страниц
- **Discovery**: сбор кандидатов из `sitemap.xml` и внутренних ссылок навигации с главной.
- **Обход**: до 5 коммерчески важных URL (контакты, услуги, меню, портфолио, цены) — статус, title, формы, CTA, soft-404.
- **Gemini-разбор** (при `GEMINI_API_KEY`): вердикт по воронке и приоритетная доработка.

### 6. Безопасность и Инфраструктура
- **SSL / HTTPS**: Проверка работы защищенного соединения.
- **Время отклика (TTFB)**: Время первого байта сервера.
- **Security Headers**: HSTS, CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy.
- **Рантайм JS**: Перехват `PageError` и ошибок `console.error`.

---

## 📋 Полный чеклист параметров тестирования

Официальный перечень сигналов, которые собирает `AuditCollector.java`.  
**~55–60 уникальных типов проверок**; часть выполняется **на каждой локали**, часть — **один раз на сайт**.

| Область | Уникальных параметров | Кратность прогона |
| :--- | :---: | :--- |
| Сеть, сервер, безопасность | 13 | 1× на сайт |
| Индексация и robots | 6 | 1× |
| AI Visibility (HTTP) | 4 | 1× |
| AI Visibility (DOM, главная) | 6 | 1× |
| PageSpeed Insights | 10 | 2× (mobile + desktop) |
| На каждой локали | ~28 | ×N локалей (`en`, `ru`, `he` по умолчанию) |
| Агентный обход | 9 на страницу | до 5 URL |

**PDF-таблица «МОБИЛЬНЫЙ UX, RTL, ФОРМЫ & БЕЗОПАСНОСТЬ»** — **9 агрегированных строк** (свод, не полный список).  
**AI Visibility score в JSON** — **7 критериев** для расчёта `ai_visibility.score`.

### 1. Сеть, сервер и безопасность (1 раз)

| ID | Параметр | Метод |
| :--- | :--- | :--- |
| `net_https` | HTTPS / TLS | HTTP HEAD/GET origin |
| `net_http_status` | HTTP-статус главной | HTTP |
| `net_ttfb` | TTFB (мс до первого байта) | HTTP timing |
| `net_server` | Заголовок `Server` | HTTP headers |
| `sec_hsts` | Strict-Transport-Security | HTTP headers |
| `sec_csp` | Content-Security-Policy | HTTP headers |
| `sec_xfo` | X-Frame-Options | HTTP headers |
| `sec_xcto` | X-Content-Type-Options | HTTP headers |
| `sec_referrer` | Referrer-Policy | HTTP headers |
| `sec_permissions` | Permissions-Policy | HTTP headers |
| `net_failed` | Ответы 4xx/5xx | Playwright `onResponse` |
| `runtime_console` | `console.error` / `console.warn` | Playwright `onConsoleMessage` |
| `runtime_pageerror` | Необработанные PageError (JS) | Playwright `onPageError` |

### 2. Индексация и robots.txt (1 раз)

| ID | Параметр | Метод |
| :--- | :--- | :--- |
| `idx_robots` | `robots.txt` — наличие, HTTP-статус | HTTP GET |
| `idx_sitemap` | `sitemap.xml` — наличие, статус, число URL | HTTP GET |
| `idx_sitemap_directives` | Директивы `Sitemap:` в robots | Parse robots.txt |
| `robots_ai_gptbot` | GPTBot — Allow / Disallow: / | Parse robots.txt |
| `robots_ai_claudebot` | ClaudeBot — Allow / Disallow: / | Parse robots.txt |
| `robots_ai_ccbot` | CCBot — Allow / Disallow: / | Parse robots.txt |
| `robots_ai_google_extended` | Google-Extended — Allow / Disallow: / | Parse robots.txt |
| `robots_severe_block` | `severe_block` (root disallow для AI-ботов) | Parse robots.txt |
| `robots_cloudflare_managed` | Маркер Cloudflare Managed Content | Parse robots.txt |

### 3. AI Visibility — HTTP (1 раз)

| ID | Параметр | Pass criteria |
| :--- | :--- | :--- |
| `ai_llms_txt` | `GET /llms.txt` | 200, markdown `#`, бренд, структура `##` / `>` |
| `ai_mcp_manifest` | `GET /.well-known/mcp` | 200, JSON, `mcp_version` + `endpoints` |
| `ai_mcp_brand_api` | `GET /api/mcp` | 200, JSON `name`, `url`, `canonicalPages` (warn если нет) |
| `ai_about_page` | `GET /about` | 200, маркеры Brand Facts / Organization / контакты |

### 4. AI Visibility — DOM (главная, 1 раз)

| ID | Параметр | Метод |
| :--- | :--- | :--- |
| `dom_org_schema` | JSON-LD Organization / LocalBusiness | `script[type=application/ld+json]` |
| `dom_org_name_url` | Organization: `name`, `url` | JSON-LD parse |
| `dom_org_sameas_contact` | Organization: `sameAs`, `contactPoint` (prefer) | JSON-LD parse |
| `dom_faq_schema` | JSON-LD FAQPage + `mainEntity` | JSON-LD parse |
| `dom_llms_link` | `link[rel=describedby]` → `/llms.txt` | DOM |
| `dom_data_layer` | `window.dataLayer` или inline stub | DOM / scripts |
| `dom_gtag_consent` | Consent Mode stub (`gtag` + consent) | Inline scripts |
| `dom_ga4_id` | GA4 ID `G-…` / `googletagmanager.com/gtag/js` | Scripts (info) |

**7 критериев score:** llms.txt, MCP manifest, /about, robots AI (no severe_block), Organization schema, llms link, dataLayer (+ consent stub).

### 5. PageSpeed Insights (1 раз × mobile + desktop)

| ID | Параметр |
| :--- | :--- |
| `psi_performance` | Lighthouse Performance score |
| `psi_accessibility` | Lighthouse Accessibility score |
| `psi_best_practices` | Lighthouse Best Practices score |
| `psi_seo` | Lighthouse SEO score |
| `psi_fcp` | First Contentful Paint |
| `psi_lcp` | Largest Contentful Paint |
| `psi_cls` | Cumulative Layout Shift |
| `psi_tbt` | Total Blocking Time |
| `psi_speed_index` | Speed Index |
| `psi_tti` | Time to Interactive |

### 6. На каждой локали (×N: по умолчанию `en`, `ru`, `he`)

**SEO и мета (DOM)**

| ID | Параметр |
| :--- | :--- |
| `loc_title` | `document.title` |
| `loc_meta_description` | `meta description` |
| `loc_og_title` | `og:title` |
| `loc_og_description` | `og:description` |
| `loc_og_image` | `og:image` |
| `loc_canonical` | `link[rel=canonical]` |
| `loc_html_lang` | `html lang` |
| `loc_html_dir` | `html dir` |
| `loc_h1_count` | Количество `h1` |
| `loc_h2_count` | Количество `h2` |
| `loc_h3_count` | Количество `h3` |
| `loc_heading_no_h1` | Отсутствует `h1` |
| `loc_heading_multi_h1` | Несколько `h1` |
| `loc_heading_h3_without_h2` | `h3` без `h2` |

**Доступность (DOM + axe-core)**

| ID | Параметр |
| :--- | :--- |
| `loc_img_no_alt` | `img` без `alt` |
| `loc_button_no_label` | `button` без текста и `aria-label` |
| `loc_axe_violations` | axe-core: WCAG 2.1 AA (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`) |

**Орфография** — только локали `en` и `ru` (LanguageTool + `config/audit_exceptions.json`).

**Лид-захват (DOM + modal probe)**

| ID | Параметр |
| :--- | :--- |
| `loc_forms_count` | Число `<form>` |
| `loc_form_email` | Поле email |
| `loc_form_phone` | Поле телефона |
| `loc_form_name` | Поле имени |
| `loc_form_submit` | Кнопка submit |
| `loc_form_honeypot` | Honeypot (hidden / off-screen / `aria-hidden` / trap-name) |
| `loc_antispam_recaptcha` | Google reCAPTCHA |
| `loc_antispam_turnstile` | Cloudflare Turnstile |
| `loc_antispam_hcaptcha` | hCaptcha |
| `loc_chat_widget` | Чат-виджет (Tawk, Crisp, Intercom, Zendesk, Jivo, LiveChat, Chatra) |
| `loc_messenger_links` | Ссылки wa.me / t.me / Viber / m.me |
| `loc_modal_form_probe` | Форма после клика по кнопке контакта |

**RTL**

| ID | Параметр |
| :--- | :--- |
| `loc_rtl_html_dir` | `dir` на `<html>` |
| `loc_rtl_body_dir` | `dir` на `<body>` |
| `loc_rtl_computed` | `computed direction` |
| `loc_rtl_declared` | RTL заявлен (`isRtlDeclared`) |
| `loc_rtl_h1_align` | `text-align` у `h1` |

**SEO-ассеты**

| ID | Параметр |
| :--- | :--- |
| `loc_favicon` | `link[rel=icon]` |
| `loc_apple_touch` | `link[rel=apple-touch-icon]` |
| `loc_hreflang` | `link[rel=alternate][hreflang]` |
| `loc_viewport` | `meta viewport` |

**Мобильная вёрстка (iPhone SE 375×667, mobile UA)**

| ID | Параметр |
| :--- | :--- |
| `loc_overflow_x` | Горизонтальный overflow (px) |
| `loc_overflow_offenders` | Селекторы источников перелива |
| `loc_scroll_width` | `documentScrollWidth` |
| `loc_mobile_direction` | LTR / RTL при overflow-тесте |

### 7. Агентный обход (до 5 страниц, 1 раз)

Discovery: `sitemap.xml` + внутренние ссылки с главной (приоритет: contact, services, portfolio, pricing, menu…).

На каждой открытой URL:

| ID | Параметр |
| :--- | :--- |
| `agent_http_status` | HTTP-статус |
| `agent_title` | `document.title` |
| `agent_h1` | Заголовки `h1` |
| `agent_forms` | Число форм |
| `agent_word_count` | Объём текста (слова) |
| `agent_cta` | CTA (contact / заявка / WhatsApp / Telegram…) |
| `agent_soft404` | Soft-404 по title + текст |
| `agent_lang` | `html lang` |
| `agent_dir` | `dir` / direction |

Опционально при `GEMINI_API_KEY`: вердикт по воронке, пробелы, `priority_fix`.

### 8. Executive Scorecard (агрегация, не отдельные HTTP-тесты)

| Шкала | Источники |
| :--- | :--- |
| `speed_mobile_ux` | PageSpeed mobile, overflow-x, TTFB |
| `lead_gen_forms` | Формы, антиспам, мгновенный канал, JS-ошибки |
| `seo_visibility` | Lighthouse SEO, robots/sitemap, OG, canonical, hreflang, favicon, agent broken pages |
| `security_stability` | HTTPS, security headers, JS-ошибки, failed network, TTFB |
| `ai_visibility` | 7 критериев блока AI Visibility |

### 9. Свод в PDF-таблице (9 строк при `en` + `ru` + `he`)

1. Overflow-X 375px  
2. RTL (иврит)  
3. Лид-формы + антиспам + мгновенный ответ (агрегат)  
4. llms.txt + MCP discovery (агрегат)  
5. Brand facts: JSON-LD + /about (агрегат)  
6. Robots: AI-боты + индексация (агрегат)  
7. GA4 / dataLayer  
8. HTTPS + HTTP-заголовки (агрегат)  
9. Стабильность рантайма (JS + 4xx/5xx)

Без локали `he` — **8 строк** (без RTL).

---

## 📊 Генерируемые артефакты

1. **`reports/audit-report.pdf`** — презентабельный A4 PDF-отчет для собственников бизнеса и ЛПР с Executive Scorecard, Топ-3 уязвимостями конверсии и блоком CTA для записи на консультацию.
2. **`reports/audit-report.md`** — структурированный Markdown-отчет с подробными таблицами и бизнес-рекомендациями.
3. **`reports/audit_data.json`** — полный массив спарсенных данных, агентного просмотра и результатов PageSpeed Insights.
