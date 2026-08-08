# AI Agents Repository

A monorepo for autonomous AI agents and automation tools.

## Structure

```text
.
├── .github/workflows/          # CI/CD Workflows
└── All_agents/                 # Agents directory
    └── QA_Auditor/             # Website QA & Localization Audit Agent
```

## Agents

- **[QA_Auditor](./All_agents/QA_Auditor)**: Autonomous multi-locale QA Auditor for web applications (`https://erythro.ai`). Checks broken links, i18n completeness (EN, RU, HE), grammar/spelling, and RTL layouts.
