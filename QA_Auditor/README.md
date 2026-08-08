# 🤖 Erythro.ai Website QA Auditor Agent

Автоматизированный агент полного QA-аудита сайта [https://erythro.ai/](https://erythro.ai/) по 3 локалям (`en`, `ru`, `he` с поддержкой RTL-верстки).

---

## ⚡ Ключевые возможности

1. **Мультилокальная проверка**: Аудит `en`, `ru`, `he` с автоматическим переключением локалей.
2. **Перехват Dev Console**: Автоматический отлов `console.error`, `console.warn` и неперехваченных падений JavaScript (`PageError / Uncaught Exceptions`).
3. **Проверка скорости и метрик (Speed Test)**: Интеграция с Google PageSpeed Insights API v5 (оценки Performance, Accessibility, Best Practices, SEO и метрики Core Web Vitals FCP, LCP, CLS, TBT, Speed Index).
4. **Проверка орфографии и грамматики**: Интеграция с LanguageTool (English, Русский) с поддержкой исключений из `config/audit_exceptions.json`.
5. **Аудит доступности**: Встроенная проверка WCAG 2.1 AA через Axe-Core.

---

## 🛠 Предварительные требования

- **Java JDK 17+**
- **Apache Maven 3.8+**
- **Git**

---

## 🚀 Быстрый запуск из репозитория

### 1. Клонирование репозитория
```bash
git clone https://github.com/erythroai-droid/agents.git
cd agents/QA_Auditor
```

### 2. Установка браузерных зависимостей Playwright (при первом запуске)
```bash
mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"
```

### 3. Запуск аудитора
```bash
mvn compile exec:java
```

---

## 📊 Результаты проверки

После завершения работы скрипта формируются два файла:

1. **`reports/audit-report.pdf`** — визуально оформленный PDF-отчет для руководства и QA-команды.
2. **`reports/audit-report.md`** — детальный человекочитаемый Markdown-отчет о статусе проверок.
3. **`reports/audit_data.json`** — полный машиночитаемый JSON со всеми спарсенными SEO-метатегами, текстами, ошибками Dev Console, неперехваченными исключениями JS, данными PageSpeed Insights, результатами сканирования орфографии (LanguageTool) и проверками доступности WCAG (Axe-Core).

---

## ⚙️ Структура проекта

- `AGENTS.md` — Инструкция и спецификация QA-агента.
- `src/main/java/ai/erythro/AuditCollector.java` — Главный класс логики сбора и анализа данных (Playwright, LanguageTool, Axe-Core, PageSpeed Insights API).
- `config/audit_exceptions.json` — Реестр исключений и брендовых терминов (брендбук, техническая терминология).
- `reports/` — Директория с генерируемыми отчетами.
- `.github/workflows/audit.yml` — GitHub Actions Workflow для автоматического запуска аудита.

---

## ⚡ Запуск через GitHub Actions

В проект добавлен готовый workflow [`.github/workflows/audit.yml`](file:///.github/workflows/audit.yml).

### Настройка секретов (API Key)
1. Перейдите в ваш репозиторий GitHub: **Settings -> Secrets and variables -> Actions**.
2. Нажмите **New repository secret**.
3. Имя: `GEMINI_API_KEY` (или `PAGESPEED_API_KEY`), Значение: ваш ключ.

### Запуск вручную из интерфейса GitHub:
1. Откройте вкладку **Actions** в репозитории на GitHub.
2. Выберите **Website QA Auditor** в левом меню.
3. Нажмите **Run workflow** -> **Run workflow**.

После завершения воркфлоу отчеты `audit_data.json` и `audit-report.md` будут доступны для скачивания в артефактах запуска (**Artifacts**).
