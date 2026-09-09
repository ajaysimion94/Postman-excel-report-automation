# Developer Guide

This guide is for implementation details, architecture, and testing strategy.

## Web application

`Main --web` starts `com.automation.web.WebServer`, a loopback-only JDK HTTP server.
The static client lives in `src/main/resources/web` and is packaged into the existing
executable JAR. No additional production dependencies or frontend build are required.

- `WorkspaceFiles` limits file operations to collections, filters, and reports,
  rejects symbolic links and traversal, uses content revisions for writes, and
  moves removed files to recoverable trash.
- `ReportService` parses editor snapshots, reuses the existing validators,
  `CredentialLoader`, `RequestExecutor`, and `ExcelReportGenerator`, and runs work on
  one background worker with a bounded queue. Immutable progress snapshots and run
  metadata support polling and persistent history.
- `WorkbookPreview` reads generated `.xlsx` files with POI and returns paginated
  cell values, styles, widths, heights, and merge ranges. The client escapes cell text
  and only accepts fixed CSS properties and hex colors when rendering the workbook.
- The parser accepts source strings without temporary filter files. `SUMMARY` groups
  presentation statements; `METRIC` and `FIELD` map to existing item types, while
  `Paragraph` preserves sentence layout instead of applying `TEXT`'s label inference.

Read operations include `/api/session`, `/api/files`, `/api/file`, `/api/collection`,
`/api/runs`, `/api/run`, `/api/workbook`, and `/api/download`. Mutations use
`PUT /api/file` or POST to `/api/folder`, `/api/move`, `/api/trash`, `/api/validate`,
and `/api/runs`. Validate is non-mutating despite using POST for editor contents.
Every request checks local Host/Origin headers; non-GET requests also require the
`X-Workspace-Token` from `/api/session`. There is no CORS allowance or remote bind option.

Run the Java suite with `./mvnw test`. Web integration tests use ephemeral workspaces
and loopback mock HTTP servers. Client rendering and editor behavior tests run with
`node --test src/test/web/app.test.cjs`; Node is optional for end users.

## Pipeline Overview

Execution flow:

1. Parse CLI args in `CliCommand`
2. Resolve filter in `FilterLoader`
  - explicit `--filter`
  - or auto-select when exactly one filter exists in `FILTERS_DIR`
  - for `.filter` files with multiple `COLLECTION` blocks, select block using CLI collection selector
3. Load runtime variables in `CredentialLoader`
  - merge precedence: filter auth/vars > .env > system env
4. Resolve collection path
  - CLI collection args first
  - fallback to filter `collection` selector
5. Parse Postman collection
6. Validate filter strictly before HTTP calls
7. Execute requests
8. Generate Excel report

## Filter Schema

`FilterSpec` fields:

| Field | Type | Notes |
| ----- | ---- | ----- |
| `collection` | String | |
| `requests` | List\<String\> | |
| `responseColumns` | Map\<String, List\<String\>\> | |
| `outputPrefix` | String | |
| `auth` | FilterAuthSpec | |
| `vars` | Map\<String, String\> | |
| `rowFilters` | Map\<String, RowFilterGroup\> | **new** — per-request or `"*"` row conditions |
| `dateConfig` | Map\<String, Map\<String, DateFieldConfig\>\> | **new** — per-request/field date parse hints |
| `customTables` | List\<CustomTableSpec\> | **new** — custom joined/filtered table sheets |
| `dataShapes` | Map\<String, DataShapeSpec\> | output shaping by key: DISTINCT, ORDER BY, LIMIT/OFFSET, GROUP BY, AGG, HAVING |
| `unions` | List\<UnionSpec\> | UNION / UNION ALL sheet definitions |

`RowFilterGroup`: `logic` + `rules` + optional expression tree (`RowFilterExpression`) for nested AND/OR/NOT

`RowFilterRule`: `field`, `op`, `value`, `from`, `to`

`DateFieldConfig`: `format` (DateTimeFormatter pattern), `timezone` (ZoneId name)

`CustomTableSpec`: `name`, `sourceRequest` or `sources`+`joinOn`, optional `joinType` (INNER/LEFT/RIGHT/FULL), `columns`, `where`

`DataShapeSpec`: `distinct`, `orderBy`, `limit`, `offset`, `groupBy`, `aggregates`, `having`

`UnionSpec`: `name`, `sources`, `all`

`FilterAuthSpec` maps aliases from JSON keys like `API_USERNAME` and camelCase keys like `username`.

## Row Filter Engine

Two new classes in `com.automation.filter`:

- **`DateWindowResolver`** — resolves date preset strings (e.g., `YESTERDAY`) to `[from, to]`
  `Instant` windows relative to an execution-time reference, respecting the configured `ZoneId`.
  Week boundaries use Monday as the first day.

- **`RowConditionEvaluator`** — stateless evaluator. `evaluate(row, group, dateConfig, now)`:
  - Dispatches on `rule.op()` (case-insensitive).
  - Numeric operators try `Double.parseDouble` first, fall back to lexicographic.
  - Date operators call `RowConditionEvaluator.parseDate()` with config-then-ISO-fallback strategy.
  - Missing fields: warn and treat rule as non-matching.
  - Unknown operators: warn and skip rule (row not excluded).
  - Supports expression-tree evaluation for nested boolean logic and NOT.

`.filter` parser (`FilterQueryParser`) supports:

- statement parsing for REQUEST/FILTER/COLUMNS/DATE_CONFIG/SHAPE/UNION
- SQL-like predicates (`LIKE`, `ILIKE`, `NOT LIKE`, `IN`, `BETWEEN`, `DATE_PRESET`)
- collection-scoped blocks (`COLLECTION ...`) with global defaults and selected-block merge

## Strict Filter Validation Rules

`FilterValidator` fails fast for:

- collection mismatch
- unknown request names in `requests`, `responseColumns`, `rowFilters`, `dateConfig`
- invalid `responseColumns` keys (must be `*` or known request names)
- unknown operator name in `rowFilters`
- unknown date preset name in `DATE_PRESET` rules
- `DATE_RANGE` rule with neither `from` nor `to`
- invalid `DateTimeFormatter` pattern in `dateConfig`
- unknown `ZoneId` timezone in `dateConfig`
- custom table missing both `sourceRequest` and `sources`
- custom table specifying both (mutually exclusive)
- multi-source custom table missing `joinOn`
- custom table `sourceRequest` referencing unknown request
- duplicate custom table names
- unsupported join type in custom table
- invalid multi-source join condition count
- blank `outputPrefix`
- invalid API-key auth block
- invalid `dataShapes` keys/values (negative limits, unsupported aggregate function, blank sort/group fields)
- invalid union definitions (duplicate names, <2 sources, unknown source requests)

Warnings (non-fatal):

- Row filter rule referencing a missing field at runtime
- Date parse failure (warns + skips row)
- Custom table producing 0 rows (INFO log, sheet skipped)

## Excel Sheet Generation (Updated)

`ExcelReportGenerator.generate()` now calls five sheet builders in order:

1. `createSummarySheet` / `createCustomSummarySheet` — default metrics or custom summary from `.filter` spec
2. `createResultsSheet` — unchanged
3. `createFolderSheets` — unchanged
4. `createResponseDataSheets` — applies `rowFilters` before writing rows
5. `createCustomTableSheets` — generates custom table sheets
6. `createUnionSheets` — generates UNION/UNION ALL sheets

### Custom Summary Sheet Rendering

`createCustomSummarySheet` iterates `SummaryItem` variants and renders each:

| Item | Rendering |
| ---- | --------- |
| `Title` | Merged banner across cols A–B, colored background |
| `Description` | Subtitle banner |
| `KeyValue` (KV) | Bold grey label in col A, value in col B; boolean conditional formatting |
| `LabelValue` (LV) | Plain grey label in col A, value in col B |
| `Text` (with `$var`) | Auto-detected as label+value (Title Case humanized variable name as label) |
| `Text` (no `$var`) | Merged text across cols A–B |
| `Metrics` | Execution metrics as label/value rows |
| `Table` | Section title + headers + data rows (multi-column tables auto-size beyond cols A–B) |
| `QuickTable` (QT) | Label-value table with default "Label"/"Value" header row |
| `QuickTable` (LABEL_TABLE) | Label-value table **without** header row by default; add `HEADERS` to include one |

**Boolean conditional formatting:**

- `true` / `yes` → bright green fill + bold white text (centered)
- `false` / `no` → red fill + bold white text (centered)
- Other values → plain text style

**Variable humanization:** `$POSTS` → "Posts", `$USER_ID` → "User Id" (Title Case with spaces replacing underscores).

`createResponseDataSheets` calls `applyRowFilter()` which resolves the matching
`RowFilterGroup` (request-specific then wildcard) and evaluates each row via
`RowConditionEvaluator`.

`createCustomTableSheets` builds a `Map<requestName, List<ObjectNode>>` from all
results, then for each `CustomTableSpec`:

- **Single source**: looks up rows, applies `where` clause, selects columns.
- **Join source**: calls `buildJoinedRows()` with support for INNER/LEFT/RIGHT/FULL join types and multi-source chaining.

`applyDataShape()` is shared by response-data, custom-table, and union outputs and supports:

- DISTINCT
- ORDER BY
- LIMIT/OFFSET
- GROUP BY + aggregates (COUNT/SUM/AVG/MIN/MAX)
- HAVING

`createUnionSheets()` merges rows from listed source requests and supports UNION distinct and UNION ALL.

A shared `usedNames` `Set<String>` ensures no sheet name collisions between standard
response data sheets and custom table sheets.

## CLI Notes

- `--list`: list collection JSON files from `COLLECTIONS_DIR`
- `--list-filters`: list `.filter` files from `FILTERS_DIR`
- no `--filter`:
  - if exactly one filter exists, it is auto-selected
  - if multiple filters exist, run fails and asks for explicit `--filter`
- non-interactive daily mode:
  - keep defaults in `.env`
  - keep one filter in `FILTERS_DIR`
  - run with one command: `java -jar target/postman-excel-runner-1.0.0.jar --env .env`

When a `.filter` file contains multiple `COLLECTION` blocks, pass `--collection-name` (or `--collection`) so parser block selection is unambiguous.

## Build

Preferred (Maven Wrapper):

```bash
./mvnw clean package
./mvnw clean package -DskipTests
```

If Maven is installed globally:

```bash
mvn clean package
mvn clean package -DskipTests
```

Build output:

```text
target/postman-excel-runner-1.0.0.jar
```

## Testing

Run all tests:

```bash
./mvnw -q test
```

Focused tests:

```bash
./mvnw -q test -Dtest=FilterLoaderTest
./mvnw -q test -Dtest=FilterValidatorTest
./mvnw -q test -Dtest=DateWindowResolverTest
./mvnw -q test -Dtest=RowConditionEvaluatorTest
./mvnw -q test -Dtest=CredentialLoaderFilterOverrideTest
```

Test classes:

| Class | Coverage |
| ----- | -------- |
| `FilterLoaderTest` | `.filter` file discovery/loading and collection block selection |
| `FilterValidatorTest` | All strict validation rules including rowFilters, dateConfig, customTables |
| `DateWindowResolverTest` | All 10 date presets, week boundaries, case-insensitivity, invalid preset rejection |
| `RowConditionEvaluatorTest` | All 18 operators, AND/OR logic, missing fields, DATE_PRESET, DATE_RANGE, custom format |
| `ExcelReportGeneratorTest` | Sheet structure, row filtering applied, custom table sheet generation |
| `CredentialLoaderFilterOverrideTest` | filter auth/vars precedence over `.env` |
| `PostmanCollectionParserTest` | Postman collection JSON parsing |
| `VariableResolverTest` | Variable placeholder substitution |
