# 📊 QA Audit Report: https://erythro.ai/
**Дата проверки:** 2026-08-22 13:15:24 (IDT)  
**Проверяемые локали:** en, ru, he

## EXECUTIVE SUMMARY
- **Общий статус:** `PASS`
- **Ошибок сетевых запросов (HTTP 4xx/5xx):** 0
- **Сообщений Dev Console / JS Ошибок:** 21

## 1. 🖥️ DEV CONSOLE & UNCAUGHT JS ERRORS
| Error Type | Message / Exception | Source Location | Active Locale |
| :--- | :--- | :--- | :--- |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | en |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | en |
| `error` | Refused to load the script 'https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.2/axe.min.js' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `:8:0` | en |
| `warning` | The resource https://erythro.ai/_next/static/media/e4af272ccee01ff0-s.p.woff2?dpl=dpl_EKhyMXdmhYSka4htKiV98BJXTaaa was preloaded using link preload but not used within a few seconds from the window's load event. Please make sure it has an appropriate `as` value and it is preloaded intentionally. | `https://erythro.ai/:0:0` | en |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | ru |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | ru |
| `error` | Refused to load the script 'https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.2/axe.min.js' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `:8:0` | ru |
| `warning` | The resource https://erythro.ai/_next/static/media/e4af272ccee01ff0-s.p.woff2?dpl=dpl_EKhyMXdmhYSka4htKiV98BJXTaaa was preloaded using link preload but not used within a few seconds from the window's load event. Please make sure it has an appropriate `as` value and it is preloaded intentionally. | `https://erythro.ai/:0:0` | ru |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | he |
| `error` | Refused to load the script 'https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.2/axe.min.js' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `:8:0` | he |
| `warning` | The resource https://erythro.ai/_next/static/media/e4af272ccee01ff0-s.p.woff2?dpl=dpl_EKhyMXdmhYSka4htKiV98BJXTaaa was preloaded using link preload but not used within a few seconds from the window's load event. Please make sure it has an appropriate `as` value and it is preloaded intentionally. | `https://erythro.ai/:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/:0:0` | he |
| `warning` | The resource https://erythro.ai/_next/static/media/e4af272ccee01ff0-s.p.woff2?dpl=dpl_EKhyMXdmhYSka4htKiV98BJXTaaa was preloaded using link preload but not used within a few seconds from the window's load event. Please make sure it has an appropriate `as` value and it is preloaded intentionally. | `https://erythro.ai/:0:0` | he |
| `warning` | The resource https://erythro.ai/_next/image?url=https%3A%2F%2Fwgw9moyqjdjcaq9l.public.blob.vercel-storage.com%2FHero_Mobile.webp&w=640&q=70 was preloaded using link preload but not used within a few seconds from the window's load event. Please make sure it has an appropriate `as` value and it is preloaded intentionally. | `https://erythro.ai/:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/portfolio:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/contacts:0:0` | he |
| `warning` | The resource https://erythro.ai/_next/static/media/e4af272ccee01ff0-s.p.woff2?dpl=dpl_EKhyMXdmhYSka4htKiV98BJXTaaa was preloaded using link preload but not used within a few seconds from the window's load event. Please make sure it has an appropriate `as` value and it is preloaded intentionally. | `https://erythro.ai/contacts:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/services/enterprise-engineering:0:0` | he |
| `error` | Refused to load the script 'https://static.cloudflareinsights.com/beacon.min.js/v4513226cdae34746b4dedf0b4dfa099e1781791509496' because it violates the following Content Security Policy directive: "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com". Note that 'script-src-elem' was not explicitly set, so 'script-src' is used as a fallback.  | `https://erythro.ai/portfolio/next-project:0:0` | he |

## 2. ⚡ SPEED TEST & PERFORMANCE METRICS (PAGESPEED INSIGHTS)
| Strategy | Status | Performance | Accessibility | Best Practices | SEO | FCP | LCP | CLS |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `mobile` | `SUCCESS` | 90 | 100 | 92 | 100 | 2.4 s | 2.7 s | 0 |
| `desktop` | `SUCCESS` | 99 | 100 | 92 | 100 | 0.6 s | 1.0 s | 0 |

## 3. 🌐 NETWORK & RESOURCE ERRORS
Сетевых ошибок HTTP 4xx/5xx не обнаружено.

## 4. 📱 MOBILE UX & RTL (375x667)
| Locale | Overflow-X | Document Width | RTL Declared | Offenders |
| :--- | :--- | :--- | :--- | :--- |
| `en` | 0 px | 375 | false | - |
| `ru` | 0 px | 375 | false | - |
| `he` | 98874 px | 100374 | true | header.fixed.top-0, div.relative.z-[70], button.group.relative |

## 5. 🎯 LEAD CAPTURE & ANTI-SPAM
- **Форм на странице:** 1
- **Поля контакта:** email — да, телефон — да, имя — да
- **Кнопка отправки:** да
- **Защита от спама:** не обнаружена
- **Чат-виджет:** нет
- **Ссылки в мессенджеры:** https://wa.me/972505308305

## 6. 🔐 INFRASTRUCTURE & SECURITY HEADERS
- **HTTPS:** true | **HTTP-статус:** 200 | **TTFB:** 477 ms
- **robots.txt:** 200 | **sitemap.xml:** 200 (URL в карте: 15)

| Security Header | Value |
| :--- | :--- |
| `HSTS` | max-age=63072000 |
| `CSP` | default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.googletagmanager.com https://www.google-analytics.com https://va.vercel-scripts.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' https://www.google-analytics.com https://region1.google-analytics.com https://vitals.vercel-insights.com https://*.public.blob.vercel-storage.com; media-src 'self' blob: https:; frame-ancestors 'self'; base-uri 'self'; form-action 'self' |
| `X-Frame-Options` | SAMEORIGIN |
| `X-Content-Type-Options` | nosniff |
| `Referrer-Policy` | strict-origin-when-cross-origin |
| `Permissions-Policy` | camera=(), microphone=(), geolocation=(), interest-cohort=() |

## 7. 🤖 AI VISIBILITY & BRAND DISCOVERY
- **AI Visibility score:** 100/100 (7/7 checks)

| Check | Status | Details | Fix hint |
| :--- | :--- | :--- | :--- |
| llms.txt | ✅ | HTTP 200, markdown OK | — |
| MCP manifest | ✅ | JSON mcp_version + endpoints | — |
| Organization schema | ✅ | JSON-LD Organization на главной | — |
| Brand Facts /about | ✅ | HTTP 200 | — |
| Robots AI bots | ✅ | Allow для AI-ботов | — |
| GA4 / dataLayer | ✅ | dataLayer + consent stub | — |
| FAQ schema | ✅ | FAQPage mainEntity найден | — |

## 8. 🧭 AGENT BROWSE (КЛЮЧЕВЫЕ СТРАНИЦЫ)
- **Просмотрено страниц:** 5 / лимит 5 (кандидатов: 18)
- **Успешно открыто:** 5 | битых/soft-404: 0
- **CTA на внутренних:** 4 | форм на внутренних: 1

| URL | HTTP | Title | Forms | CTA | OK |
| :--- | :--- | :--- | :--- | :--- | :--- |
| https://erythro.ai/ | 200 | Erythro.ai - סוכנות דיגיטל | 0 | true | true |
| https://erythro.ai/portfolio | 200 | Portfolio \| Erythro.ai | 0 | true | true |
| https://erythro.ai/contacts | 200 | יצירת קשר \| Erythro.ai | 1 | true | true |
| https://erythro.ai/services/enterprise-engineering | 200 | פיתוח אתרים ארגוניים ושירותי Backend בעומס גבוה \| Erythro.ai | 0 | true | true |
| https://erythro.ai/portfolio/next-project | 200 | הפרויקט הבא \| Portfolio \| Erythro.ai | 0 | true | true |

**Gemini-вердикт:** Путь к заявке усложнен: лид-форма есть только на странице контактов. На ключевых страницах услуг и в портфолио отсутствуют встроенные формы, что снижает конверсию.
- Пробелы воронки: Отсутствие лид-форм на главной странице и страницах услуг; Принудительный переход на страницу контактов для отправки заявки; Отсутствие быстрых модальных форм по клику на CTA-кнопки
- Приоритет: Добавить встроенные формы заявки и модальные окна по клику на CTA на ключевые страницы (главная, услуги, портфолио).

