# Plan: automatic entry categorization (backend)

Companion plan in the app repo: `BudgetHunter/docs/plans/auto-categorization.md`.
Work through the parts in order; each part is one small PR that leaves `./gradlew check` green.

_Last revised against `main` at `a1f7322` (includes per-account preferences, Spring Boot 3.5.16,
Flyway migrations up to V3)._

## Goal

The backend reads an entry's `description` and assigns it one of the app's categories, then
stores it and notifies clients over SSE. Provider: **Gemini Flash-Lite** (free tier) behind an
interface, so it can be swapped (Jev, Ollama, ...) without touching the rest.

## What already exists (and changes this plan)

- **Per-account preferences are already built** (`UserPreferencesController`,
  `UserPreferencesService`, `V3__add_user_preferences.sql`): `GET/PUT /api/users/me/preferences`,
  with `users.ai_processing_enabled BOOLEAN` **nullable** (`User.aiProcessingEnabled: Boolean?`).
  The app's "AI processing" toggle already syncs through it. So this plan **no longer adds a
  settings column or endpoint**; it only *reads* that flag. (The previous revision's Part 1b is gone.)
- `null` means "this account never saved a value". For server-side AI we treat **`null` as off**:
  no description leaves the server until the app has explicitly saved `true`. The app uploads its
  device value the first time it syncs on an account that has none (`SyncUserPreferencesUseCase.pull`),
  and the app's default is on, so this only delays categorization until that first sync.
- Next Flyway migration is **V4** (prod baseline is 2, V3 already applied). Mirror every schema
  change in `database/schema.sql` too (V3 did).
- `category` is still a required, non-blank `String` in `CreateBudgetEntryRequest`,
  `UpdateBudgetEntryRequest` and `PutEntryRequest`, and `BudgetEntry` has no source marker.

## Decisions

- **Provider:** Gemini Flash-Lite via the REST API. Confirm the current model id and free-tier
  limits in Google's docs before Part 3. Model id and key are config, not constants.
- **Categories (closed list, shared with the app):** `FOOD, GROCERIES, SELF_CARE,
  TRANSPORTATION, HOUSEHOLD_ITEMS, SERVICES, EDUCATION, HEALTH, LEISURE, TAXES, OTHER`.
  `category` stays a `String` column; the backend validates against the list in code, so old rows
  and old clients keep working.
- **Reuse the app's category definitions.** The app's receipt prompt now carries a one-line meaning
  per category (e.g. `GROCERIES`: supermarket and food/drink shopping to cook at home;
  `SERVICES`: utilities, internet, phone, subscriptions, rent...). Use the same wording in the
  server prompt so a receipt and an SMS for the same purchase land in the same category. Keep one
  copy per repo and note the sync requirement in both.
- **Same Gemini approach as the app:** structured output (`responseSchema` with an **enum** of the
  categories), bounded retries and a deadline. The app moved to a 30s deadline with limited retries
  after real-world timeouts; for a background job use a shorter one (~10s) with at most one retry,
  since nobody waits on it.
- **Never override the user.** Add `category_source` (`USER` | `AUTO`) to `budget_entries`.
- **Never block or fail a write because of the AI.** Classification runs after the transaction
  commits, asynchronously. On any failure the entry keeps its category (`OTHER` if none).
- **The app's AI toggle controls this** (see above). Whose setting applies in a shared budget: the
  user who created or last edited the entry, never other collaborators. `createdBy == null` ->
  never auto-categorized.
- **The server never sees the receipt file** (the app keeps `invoice` local), so server-side
  processing is categorization from the description only.
- **Privacy:** only the description text is sent to Gemini, never amounts, emails or budget names.
  The free tier may use submitted data for model improvement; say so in the docs.

## How the pieces fit

```
POST/PUT entry ──► BudgetService saves entry
                     category present                          -> source = USER (no AI)
                     category absent + creator's flag == true  -> OTHER, source = AUTO, publish event
                     category absent + flag false/null         -> OTHER, source = USER, nothing else
                                             │ after commit
                                             ▼
                          EntryCategorizationService (@Async)
                            0. re-read creator's ai_processing_enabled (skip unless true)
                            1. normalize description
                            2. cache / rules lookup  ── hit ──┐
                            3. CategoryClassifier (Gemini) ───┤
                            4. update entry if source still AUTO and description unchanged
                            5. broadcast UPDATED over SSE ◄───┘
```

Client contract: `category` becomes **optional** in create/update/put requests. Present = the
user's (or the receipt's) choice. Absent/blank = "please categorize". Old app builds always send a
category, so they are unaffected.

## Parts

### Part 1 - Schema and API contract
- Flyway `V4__add_category_source.sql`: `category_source VARCHAR(10) NOT NULL DEFAULT 'USER'`
  (existing rows are user-chosen). Mirror in `database/schema.sql`; extend `MigrationFilesTest`
  (check how it handles V3 first).
- `CategorySource` enum + field on `BudgetEntry` and `BudgetEntryResponse` (additive JSON).
- Make `category` optional in the three request DTOs (drop `@NotBlank`).
- `BudgetService`: apply the table above. Reading the flag: `userRepository` already loads the
  acting user in `createEntry`/`updateEntry`/`putEntry`.
- Update path: a request with a category -> `USER`. Omitted on an `AUTO` entry -> keep the current
  category and re-classify only if the description changed. Omitted on a `USER` entry -> treat as
  "re-categorize" only if the flag is on (this is the app's future explicit re-categorize action).
- Unknown category strings: accept (old rows may hold anything).
- Tests: service + controller per branch (flag true/false/null); sort by `category` still works.

### Part 2 - Classifier abstraction and rules/cache layer
- `CategoryClassifier { fun classify(descriptions: List<String>): List<Category?> }`.
- `RuleBasedClassifier` (keyword map, ES/EN - the app is now English by default with Spanish
  strings, users' descriptions are in both - accent/case-insensitive) as the first layer.
- `CategoryCache`: normalized description -> category (Caffeine, bounded). Normalization: lowercase,
  strip accents, drop digits/reference codes (`"RAPPI 8841"` and `"rappi 9921"` share a key).
- `FakeCategoryClassifier` for tests. Unit tests for normalization and rules.

### Part 3 - Gemini classifier
- `GeminiCategoryClassifier` using Spring `RestClient`: `generateContent` with the `x-goog-api-key`
  header (never in the URL, so it cannot reach logs), `responseMimeType: application/json` and a
  `responseSchema` whose `category` is an enum of the allowed values.
- Prompt: short instruction + the category guide shared with the app, "if unsure answer OTHER".
  Batch several descriptions per request.
- Config in `application-production.properties` **and** mirrored in the test/debug files (the test
  file shadows main; see `CLAUDE.md`): `categorization.enabled`, `categorization.gemini.api-key=
  ${GEMINI_API_KEY:}`, `...model`, `...timeout`, `...max-requests-per-minute`.
- Disabled automatically when the key is blank, so dev, tests and CI never call Google.
- Resilience: ~10s timeout, 1 retry on 5xx/timeouts, 429 means back off and leave the entries for
  the backfill, Bucket4j limiter below the free-tier quota. Never log descriptions at INFO.
- Tests with `MockRestServiceServer`: happy path, malformed JSON, out-of-list value, timeout, 429,
  blank key.

### Part 4 - Async categorization flow
- `@EnableAsync` with a small bounded executor (full queue -> leave for the backfill).
- Publish an event on save when `source == AUTO`; handle with
  `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`.
- `EntryCategorizationService`: check flag -> rules -> cache -> Gemini; re-load the entry and update
  **only if** `category_source == AUTO` and the description is unchanged (guards races with a user
  edit); bump `modificationDate`, leave `updatedBy` null, broadcast `UPDATED` via the existing SSE
  path. Empty description -> stay `OTHER`, no call.
- Preserve `spring.jpa.open-in-view=false`: no lazy loading outside the transaction.
- Tests: user edit during classification wins; AI failure leaves the entry intact; flag turned off
  while pending -> skipped; collaborator's flag does not trigger another user's entry; SSE receives
  the update; concurrency case next to `ConcurrentBudgetEntryTest`.

### Part 5 - Backfill and operations
- Scheduled job (`@Scheduled`, off by default) classifying `AUTO` entries still at `OTHER`, in
  batches, only for creators whose flag is `true`, stopping on 429.
- Micrometer counters (classified, rule/cache hit, API error, skipped) via Actuator.
- Docs: `GEMINI_API_KEY` / `CATEGORIZATION_*` in `.env.example`, `docker-compose.yml`, the
  `deploy.yml` secrets list, `DEPLOYMENT.md`; update `CLAUDE.md`, `PROGRESS.md`,
  `postman_requests.md` and the OpenAPI annotations (`category` optional, new response field).

## Acceptance criteria
- With the AI toggle off (or never saved), no entry of that user is categorized and none of their
  descriptions reaches Gemini; turning it on resumes it for new entries.
- Creating an entry without `category` returns immediately with `OTHER`/`AUTO`; within seconds an
  SSE `UPDATED` event carries the AI category.
- Entries with a user-chosen category are never modified by the AI.
- With no API key, or with Gemini down, every endpoint behaves exactly as today.
- `./gradlew check` (ktlint, detekt, tests, Kover >= 80%) passes with no baseline changes.

## Open questions
- Should turning the toggle on offer to categorize existing `OTHER` entries (on-demand backfill)?
- Persistent cache table vs. in-memory only (in-memory is fine to start).
