# Prompt: добавить блок «AI Visibility» в QA_Auditor (Erythro.ai)

Скопируй этот промпт в Cursor / агента при работе в репозитории  
`C:\agents\website-auditor\erythro-ai`.

---

## Контекст

На продакшене `https://erythro.ai` внедрён пакет **AI visibility** (август 2026). Текущий
`AuditCollector.java` проверяет security headers, robots.txt, формы и SEO, но **не проверяет**
отдельно:

- `llms.txt`
- `/.well-known/mcp`
- Organization / FAQ JSON-LD
- Brand Facts page (`/about`)
- AI bot rules в `robots.txt` (severe block)
- GA4 / `dataLayer` bootstrap (consent stub)
- AI referral readiness (опционально)

Нужно добавить новый раздел аудита и строки в Executive Scorecard / PDF / Markdown отчёт.

Референс реализации на сайте: репозиторий `erythro-ai`, документ `docs/AI_VISIBILITY.md`.

---

## Задача

Расширь `QA_Auditor/src/main/java/ai/erythro/AuditCollector.java` (и при необходимости
`ReportTemplateGenerator.java`, `AGENTS.md`, `README.md`) новым блоком **`ai_visibility`**.

### 1. HTTP-проверки (без браузера)

Добавь метод `collectAiVisibility(String origin)` и вызывай его из основного pipeline
рядом с `collectInfrastructureSecurity`.

| ID | Проверка | Pass criteria |
|---|---|---|
| `llms_txt` | `GET {origin}/llms.txt` | Status 200; body starts with `#` (markdown); contains `Erythro` or site host; has section `##` or blockquote `>` |
| `mcp_manifest` | `GET {origin}/.well-known/mcp` | Status 200; `Content-Type` contains `json`; JSON has `mcp_version` and `endpoints` |
| `mcp_brand_api` | `GET {origin}/api/mcp` | Status 200; JSON has `name`, `url`, `canonicalPages` (optional warn if missing) |
| `about_page` | `GET {origin}/about` | Status 200; HTML contains brand facts markers (`Brand facts` / `dl` / `Organization` / phone or email) |
| `robots_ai_allow` | Parse `{origin}/robots.txt` | For each of `GPTBot`, `ClaudeBot`, `CCBot`, `Google-Extended`: **no** `Disallow: /` on root; prefer `Allow: /`. Flag `severe_block=true` if any listed bot has root disallow |
| `robots_cloudflare_managed` | Same file | Warn if body contains `BEGIN Cloudflare Managed` |

Сохрани в `finalReport` → `ai_visibility`:

```json
{
  "llms_txt": { "status": 200, "ok": true, "has_h1": true },
  "mcp_manifest": { "status": 200, "ok": true, "has_mcp_version": true },
  "about_page": { "status": 200, "ok": true },
  "robots_ai": { "severe_block": false, "blocked_bots": [], "allowed_bots": ["GPTBot", ...] },
  "score": 85,
  "checks_passed": 5,
  "checks_total": 7
}
```

### 2. Playwright / DOM-проверки (на главной)

Расширь существующий page script (или добавь `AI_VISIBILITY_SCRIPT`) и выполни на homepage:

| ID | Проверка | Pass criteria |
|---|---|---|
| `organization_schema` | `script[type="application/ld+json"]` | Parsed JSON contains `@type` Organization or LocalBusiness **or** `@graph` entry with Organization; has `name`, `url`; prefer `sameAs`, `contactPoint` |
| `faq_schema` | Same scripts | FAQPage with `mainEntity` array (warn if missing) |
| `llms_link` | `link[rel="describedby"]` | href ends with `/llms.txt` |
| `ga_bootstrap` | inline scripts + globals | `window.dataLayer` defined OR script contains `dataLayer`; `gtag` function or consent default; **do not** require full analytics load (cookie consent may block gtag.js) |
| `ga4_id` | scripts | Contains `G-` measurement id OR `googletagmanager.com/gtag/js` (info only) |

Добавь в `ai_visibility.dom`:

```json
{
  "organization_schema": true,
  "faq_schema": true,
  "llms_describedby": true,
  "data_layer_present": true,
  "gtag_consent_stub": true
}
```

### 3. Формы — улучшить honeypot detection

Сейчас `LEAD_AND_RTL_SCRIPT` помечает honeypot только если поле `display:none` / `visibility:hidden` / `opacity:0`.

На `erythro.ai` honeypot использует **off-screen trap** (`position:absolute; left:-9999px`) + `aria-hidden="true"`.

Обнови детекцию:

```javascript
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
```

Также: если модальная форма открывается по кнопке (уже есть `CONTACT_TRIGGER_SCRIPT`), после клика **перезапусти** lead_capture scan для honeypot.

### 4. Executive Scorecard — новые строки checkRow

В `buildCheckRows` (или аналог) добавь секцию **«AI Visibility & Brand Discovery»**:

| Название (RU) | good | warn | bad |
|---|---|---|---|
| llms.txt для AI-ассистентов | 200 + markdown | 404 / пустой | — |
| MCP discovery (/.well-known/mcp) | JSON manifest OK | 404 | — |
| Organization schema (JSON-LD) | Organization + contact | нет schema | — |
| Brand Facts / About page | /about 200 | нет страницы | — |
| Robots.txt: доступ AI-ботам | Allow, no severe_block | частичный block | Disallow / для GPTBot |
| GA4 / dataLayer readiness | dataLayer + consent stub | только partial | нет сигналов |

Не дублируй security headers — они уже в блоке «Безопасность»; но можно cross-link.

### 5. Scorecard bucket

Добавь пятую шкалу **или** включи в `seo_marketing`:

- Вариант A: `scorecard.ai_visibility` (0–100), вес в общем грейде 10–15%
- Вариант B: под-блок внутри `seo_marketing` с отдельной подписью в PDF

Рекомендация: **отдельная шкала `ai_visibility`** в JSON и одна строка в Executive Summary.

### 6. Markdown / PDF отчёт

В `generateMarkdownReport` добавь секцию:

```markdown
## 7. 🤖 AI VISIBILITY & BRAND DISCOVERY
```

Таблица: Check | Status | Details | Fix hint (на русском, коммерческий тон).

Примеры fix hints:

- llms.txt missing → «Создайте /llms.txt по спецификации llmstxt.org»
- MCP missing → «Добавьте GET /.well-known/mcp (JSON manifest)»
- Organization schema → «JSON-LD Organization в layout с name, url, logo, sameAs, contactPoint»
- robots severe_block → «Отключите Cloudflare Managed robots.txt или Allow AI crawlers»
- dataLayer → «Добавьте GA4 Consent Mode stub в head до cookie banner»

### 7. Тесты и документация

- Обнови `AGENTS.md` — новый § «AI Visibility»
- Обнови `README.md` — bullet в возможностях
- Прогон: `mvn compile exec:java` против `https://erythro.ai` — все новые checks **good** кроме опциональных (GTM, honeypot если ещё не на проде)

---

## Acceptance criteria

1. `reports/audit_data.json` содержит ключ `ai_visibility` с HTTP + DOM полями.
2. PDF и MD показывают секцию AI Visibility с ≥6 check rows.
3. Прогон на `https://erythro.ai` (после деплоя 2026-08-22):
   - llms.txt ✅
   - MCP ✅
   - Organization schema ✅
   - /about ✅
   - robots AI allow ✅
   - security headers ✅ (уже было)
   - dataLayer ✅
4. Не ломать существующие тесты RTL, lead capture, PageSpeed.

---

## Файлы для правки

```
QA_Auditor/src/main/java/ai/erythro/AuditCollector.java   # основная логика
QA_Auditor/src/main/java/ai/erythro/ReportTemplateGenerator.java  # если нужны новые шаблонные строки
QA_Auditor/AGENTS.md
QA_Auditor/README.md
```

---

## Референс: curl smoke test (erythro.ai prod)

```bash
curl -sI https://erythro.ai
curl -s https://erythro.ai/llms.txt | head -5
curl -s https://erythro.ai/.well-known/mcp
curl -s https://erythro.ai/robots.txt | findstr GPTBot
```

Ожидаемый robots snippet:

```
User-Agent: GPTBot
Allow: /
Disallow: /admin
Disallow: /api/
```
