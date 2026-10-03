# Plan: automatic entry categorization (backend)

Companion plan in the app repo: `BudgetHunter/docs/plans/auto-categorization.md`.
Work through the parts in order; each part is one small PR that leaves `./gradlew check` green.

## Goal

The backend reads an entry's `description` and assigns it one of the app's categories, then
stores it and notifies clients over SSE. Provider: **Gemini Flash-Lite** (free tier) behind an
interface, so it can be swapped (Jev, Ollama, ...) without touching the rest.

## Decisions already made

- **Provider:** Gemini Flash-Lite via the REST API. Confirm the current model id and free-tier
  limits in Google's docs before Part 3 (the app uses `gemini-2.5-flash` on `v1`; Flash-Lite ids
  and quotas change). Model id and key are config, not constants.
- **Categories (closed list, shared with the app):** `FOOD, GROCERIES, SELF_CARE,
  TRANSPORTATION, HOUSEHOLD_ITEMS, SERVICES, EDUCATION, HEALTH, LEISURE, TAXES, OTHER`
  (`BudgetEntry.Category` in the app). `category` stays a `String` column; the backend
  validates against this list in code, so old rows and old clients keep working.
- **Never override the user.** Add `category_source` (`USER` | `AUTO`) to `budget_entries`.
- **Never block or fail a write because of the AI.** Classification runs after the transaction
  commits, asynchronously. On any failure the entry keeps its category (`OTHER` if none).
- **Privacy:** only the description text is sent to Gemini, never amounts, emails or budget names.
  Note that the free tier may use submitted data for model improvement; mention it in docs.

## How the pieces fit

```
POST/PUT entry ──► BudgetService saves entry
                     category present  -> category_source = USER (no AI)
                     category absent   -> category = OTHER, source = AUTO, publish event
                                             │ after commit
                                             ▼
                          EntryCategorizationService (@Async)
                            1. normalize description
                            2. cache / rules lookup  ── hit ──┐
                            3. CategoryClassifier (Gemini) ───┤
                            4. update entry if source still AUTO and description unchanged
                            5. broadcast UPDATED over SSE ◄───┘
```

Client contract: `category` becomes **optional** in create/update/put requests. Present =
user's choice. Absent/blank = "please categorize". Old app builds always send a category, so they
are unaffected (they just never get auto-categorization).

## Parts

### Part 1 - Schema and API contract
- Flyway `V3__add_category_source.sql`: `category_source VARCHAR(10) NOT NULL DEFAULT 'USER'`
  (existing rows are treated as user-chosen). Mirror in `database/schema.sql` and
  `database/migrations/`; extend `MigrationFilesTest`.
- `CategorySource` enum + field on `BudgetEntry` and `BudgetEntryResponse` (additive JSON field).
- Make `category` optional in `CreateBudgetEntryRequest`, `UpdateBudgetEntryRequest`,
  `PutEntryRequest` (drop `@NotBlank`).
- `BudgetService`: absent category -> `OTHER` + `AUTO`; present -> must be in the allowed list
  (400 otherwise? decide: reject unknown values vs. accept free text for backward compat -
  recommend accept, since old rows may hold anything) + `USER`.
- Update-path rule: if the request carries a category -> `USER`. If it omits one and the entry is
  `AUTO` -> keep the current category and mark for re-classification only when the description
  changed.
- Tests: service + controller for each branch; sort by `category` still works.

### Part 2 - Classifier abstraction and rules/cache layer
- `CategoryClassifier { suspend/fun classify(descriptions: List<String>): List<Category?> }`.
- `RuleBasedClassifier` (keyword map, ES/EN, accent/case-insensitive) as the first layer.
- `CategoryCache`: normalized description -> category (Caffeine in-memory, bounded; optional
  table later). Normalization: lowercase, strip accents, drop digits/reference codes
  (`"RAPPI 8841"` and `"rappi 9921"` hit the same key).
- `FakeCategoryClassifier` for tests. Unit tests for normalization and rules.

### Part 3 - Gemini classifier
- `GeminiCategoryClassifier` using Spring `RestClient` (no new heavy dependency):
  `generateContent` with `x-goog-api-key` header (not in the URL, so it never reaches logs),
  `responseMimeType: application/json` and a `responseSchema` whose `category` is an **enum of the
  allowed values**, so the model cannot invent a category.
- Prompt: short system instruction in Spanish/English, categories with one-line meanings,
  "if unsure answer OTHER". Support a batch of descriptions per request.
- Config (`application-production.properties` **and** mirrored in test/debug as the CLAUDE.md
  warns): `categorization.enabled`, `categorization.gemini.api-key=${GEMINI_API_KEY:}`,
  `...model`, `...timeout`, `...max-requests-per-minute`.
- Disabled automatically when the key is blank (dev, tests, CI never call Google).
- Resilience: connect/read timeout (~5s), 1 retry on 5xx/timeouts, treat 429 as "back off" and
  leave entries for the backfill job, token-bucket limiter (Bucket4j is already a dependency) sized
  below the free-tier quota. Never log descriptions at INFO.
- Tests with `MockRestServiceServer`/MockWebServer: happy path, malformed JSON, out-of-list value,
  timeout, 429, blank key.

### Part 4 - Async categorization flow
- `@EnableAsync` with a small bounded executor (queue limit; drop-and-leave-for-backfill when full).
- Publish a domain event on save when `source == AUTO`; handle with
  `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`.
- `EntryCategorizationService`: rules -> cache -> Gemini; then re-load the entry and update **only
  if** `category_source == AUTO` and the description is unchanged (guards against races with a user
  edit); bump `modificationDate`, leave `updatedBy` null, broadcast `UPDATED` via the existing SSE
  path. Empty description -> stay `OTHER`, no call.
- Keep `spring.jpa.open-in-view=false` assumptions: no lazy loading outside the transaction.
- Tests: user edit during classification wins; AI failure leaves entry intact; SSE receives the
  update; concurrency test alongside `ConcurrentBudgetEntryTest`.

### Part 5 - Backfill and operations
- Scheduled job (`@Scheduled`, off by default) that classifies existing `AUTO` entries still at
  `OTHER`, in batches, respecting the rate limiter and stopping on 429.
- Micrometer counters (classified, cache/rule hit, API error, skipped) exposed through Actuator.
- Docs: add `GEMINI_API_KEY` and `CATEGORIZATION_*` to `.env.example`, `docker-compose.yml`,
  `deploy.yml` secrets list, `DEPLOYMENT.md`; update `CLAUDE.md`, `PROGRESS.md`,
  `postman_requests.md`, and the OpenAPI annotations (`category` optional, new response field).

## Acceptance criteria
- Creating an entry without `category` returns immediately with `OTHER`/`AUTO`; within seconds an
  SSE `UPDATED` event carries the AI category.
- Entries with a user-chosen category are never modified by the AI.
- With no API key, or with Gemini down, every endpoint behaves exactly as today.
- `./gradlew check` (ktlint, detekt, tests, Kover >= 80%) passes with no baseline changes.

## Open questions
- Reject unknown `category` strings from clients, or accept free text? (recommended: accept)
- Should the user be able to ask "re-categorize this entry" explicitly? (small follow-up endpoint
  or just send `category` omitted on update)
- Persistent cache table vs. in-memory only (in-memory is fine to start).
