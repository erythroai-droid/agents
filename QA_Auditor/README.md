# 🤖 Erythro.ai Commercial Website QA & Lead-Closing Auditor

Автоматизированный B2B-аудитор веб-сайтов с генерацией коммерческого **Executive Scorecard**, расчетом потерь бизнеса, анализом мобильного UX/RTL, тестированием лид-форм, SEO, безопасности и продающим отчетом с Call-to-Action.

---

## ⚡ Ключевые возможности

1. **Executive Scorecard & Светофор конверсии**:
   - Оценка сайта от 0 до 100 и грейд (A+, A, B, C, D, F).
   - 5 шкал здоровья: Скорость & Мобильный UX, Лидогенерация и Формы, SEO & Маркетинг, Безопасность & Стабильность, **AI Visibility & Brand Discovery**.
   - Автоматический расчет **Топ-3 уязвимостей конверсии** на языке денег и потерь рекламного бюджета.

2. **Мобильная верстка и кросс-культурный аудит (RTL)**:
   - Автоматическая проверка паразитного бокового скролла (`overflow-x`) на экранах смартфонов (iPhone SE / Android).
   - Проверка RTL-зеркалирования (`he` / иврит), выравнивания заголовков и направления элементов.
   - Соответствие закону о доступности сайтов (IS 5568 / WCAG 2.1 AA).

3. **Тест форм захвата и сценариев лидогенерации (UX & LeadGen)**:
   - Обнаружение всех форм захвата, полей Email/Phone и кнопок отправки.
   - Проверка защиты от спама (Cloudflare Turnstile / reCAPTCHA / Honeypot).
   - Маркер AI-автоматизации и мгновенного автоответа (мостик к продаже n8n / AI-агентов).

4. **SEO & Маркетинговые превью**:
   - Проверка OpenGraph (`og:image`, `og:title`) для WhatsApp, Telegram, LinkedIn.
   - Проверка наличия `sitemap.xml`, `robots.txt`, `canonical`, фавиконок.

5. **AI Visibility & Brand Discovery**:
   - `llms.txt`, MCP manifest (`/.well-known/mcp`), JSON-LD Organization/FAQ, `/about`.
   - Правила robots.txt для AI-ботов (GPTBot, ClaudeBot и др.), GA4 `dataLayer` + consent stub.

6. **Безопасность и Инфраструктура**:
   - HTTPS шифрование, HTTP Security Headers (HSTS, CSP, X-Frame-Options), замер TTFB.
   - Перехват рантайм-сбоев JavaScript (`PageError`) и ошибок Dev Console.

7. **Агентный просмотр ключевых страниц**:
   - Сбор кандидатов из `sitemap.xml` и навигации, обход до 5 коммерчески важных URL.
   - Проверка статуса, форм, CTA и soft-404; опциональный Gemini-вердикт по воронке (`GEMINI_API_KEY`).
   - Лимит страниц: `AGENT_BROWSE_MAX_PAGES` (по умолчанию 5).

8. **Умный орфографический фильтр**:
   - Интеграция LanguageTool с расширенным словарем `config/audit_exceptions.json` без ложных срабатываний на IT/B2B термины (`n8n`, `AI-агенты`, `SLA`, `CRM` и др.).

---

## 🚀 Быстрый запуск

### 1. Установка браузеров Playwright (при первом запуске)
```bash
mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"
```

### 2. Запуск аудитора
```bash
mvn compile exec:java
```

---

## 📊 Результаты проверки

После завершения работы скрипта формируются файлы в папке `reports/`:
- **`reports/audit-report.pdf`** — презентабельный B2B PDF-отчет для клиентов и ЛПР с Executive Scorecard и блоком записи на консультацию.
- **`reports/audit-report.md`** — детальный структурированный Markdown-отчет.
- **`reports/audit_data.json`** — полный машиночитаемый JSON со всеми метриками.
