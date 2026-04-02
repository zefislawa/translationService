# Running Baseline vs Improved Comparison Modes

This guide documents how to run the translation pipeline in two modes and compare outputs on:

1. a **risky input subset** (short UI strings, placeholders, ambiguous verbs, Basket/Shopping Cart terms), and
2. the **full file**.

## 1) Mode configuration matrix

Use `local.properties` to switch modes.

| Setting | Mode 1: baseline | Mode 2: improved |
|---|---:|---:|
| New layer behavior | Off (simulate old flow) | On |
| `myapp.local.placeholderProtectionEnabled` | `false` | `true` |
| Translation model (`myapp.local.googleModel`) | non-LLM model (example: `general/nmt`) | `general/translation-llm` |
| `myapp.local.googleGlossaryEnabled` | `false` | `true` |
| `myapp.local.validationEnabled` | `false` | `true` |
| `myapp.local.googleGlossaryId` | blank | set to your glossary |

> Keep the same source file and target language in both runs so results are directly comparable.

---

## 2) Prepare `local.properties`

From repo root:

```bash
cp local.properties.example local.properties
```

Fill in required Google settings (`googleApiKey`, `googleProjectId`, etc.) and `myapp.local.dataDir`.

---

## 3) Start the app

From `my-spring-war/`:

```bash
mvn -Pdev spring-boot:run
```

---

## 4) Build risky subset and full-file input sets

### Full file
Use all rows from `/api/translations/load`.

### Risky subset
Build a subset that includes at least:
- placeholders (`{{name}}`, `{id}`, `%s`, HTML-like tags),
- Basket / Shopping Cart terms,
- ambiguous verbs (`Close`, `Apply`, `Run`, `Clear`),
- short UI strings (1-3 words).

Example pull + filter (adjust language file names as needed):

```bash
curl -s http://localhost:8080/api/translations/load \
  -H 'Content-Type: application/json' \
  -d '{"path":"","fileName":"en.json"}' > /tmp/en-rows.json

jq '[.[]
  | select(
      (.text | test("\\{\\{|\\{|%[0-9]*\\$?[sdfoxegc]|<[^>]+>"))
      or (.text | test("(?i)\\bbasket\\b|shopping cart|\\bclose\\b|\\bapply\\b|\\brun\\b|\\bclear\\b"))
      or ((.text | split(" ") | length) <= 3)
    )
]' /tmp/en-rows.json > /tmp/en-risky-rows.json
```

---

## 5) Run both modes on risky subset and full file

For each mode:
1. update `local.properties` to the mode values,
2. restart the app,
3. run translation for risky subset,
4. run translation for full file.

Example translation request payload pattern:

```json
{
  "path": "",
  "fileName": "en.json",
  "targetLanguage": "fr",
  "rows": [ ... ]
}
```

Endpoint:

```bash
curl -s http://localhost:8080/api/translations/translate \
  -H 'Content-Type: application/json' \
  -d @/tmp/translate-request.json
```

Save outputs under separate filenames/folders after each run (for example `fr.baseline.json`, `fr.improved.json`) so later compare steps are clean.

---

## 6) Verification checklist

Use this checklist for both risky subset output and full-file output.

### A) Placeholder preservation
- Confirm placeholders are unchanged in final translation (`{{name}}`, `{id}`, `%s`, tags).
- In improved mode, review `<target>.validation-report.json`:
  - `missing-placeholder-token`
  - `missing-restored-placeholder`
  - `leftover-protected-token`

### B) Term consistency
- Review duplicate source strings translating to different outputs.
- In improved mode, check warning type `duplicate-inconsistency`.

### C) Short UI wording quality
- Scan short labels/buttons for over-long phrasing.
- In improved mode, check `short-ui-expansion` warnings.

### D) Basket vs Shopping Cart consistency
- Search translated output for both concepts and confirm intended one is used consistently by product terminology.
- If glossary is configured in improved mode, verify output follows glossary preference.

### E) Ambiguous UI verbs (Close, Apply, Run, Clear)
- Manually review these verbs in UI contexts (dialog close, filter apply, job run, field clear).
- Confirm translation is context-appropriate and consistent across keys.

---

## 7) Suggested side-by-side compare workflow

1. Compare risky subset outputs first (fast signal).
2. Compare full-file outputs second (coverage).
3. For JSON-level diff:

```bash
diff -u fr.baseline.json fr.improved.json | less
```

4. Keep improved-mode validation reports as objective QA evidence attached to the run.
