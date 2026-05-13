# Developer Guide

This guide is for implementation details, architecture, and testing strategy.

## Pipeline Overview

Execution flow:

1. Parse CLI args in `CliCommand`
2. Resolve filter in `FilterLoader`
   - explicit `--filter`
   - or auto-select when exactly one filter exists in `FILTERS_DIR`
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
|-------|------|-------|
| `collection` | String | |
| `requests` | List\<String\> | |
| `responseColumns` | Map\<String, List\<String\>\> | |
| `outputPrefix` | String | |
| `auth` | FilterAuthSpec | |
| `vars` | Map\<String, String\> | |
| `rowFilters` | Map\<String, RowFilterGroup\> | **new** — per-request or `"*"` row conditions |
| `dateConfig` | Map\<String, Map\<String, DateFieldConfig\>\> | **new** — per-request/field date parse hints |
| `customTables` | List\<CustomTableSpec\> | **new** — custom joined/filtered table sheets |

`RowFilterGroup`: `logic` (AND/OR) + `rules` (List\<RowFilterRule\>)

`RowFilterRule`: `field`, `op`, `value`, `from`, `to`

`DateFieldConfig`: `format` (DateTimeFormatter pattern), `timezone` (ZoneId name)

`CustomTableSpec`: `name`, `sourceRequest` or `sources`+`joinOn`, `columns`, `where`

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
- blank `outputPrefix`
- invalid API-key auth block

Warnings (non-fatal):

- Row filter rule referencing a missing field at runtime
- Date parse failure (warns + skips row)
- Custom table producing 0 rows (INFO log, sheet skipped)

## Excel Sheet Generation (Updated)

`ExcelReportGenerator.generate()` now calls five sheet builders in order:

1. `createSummarySheet` — unchanged
2. `createResultsSheet` — unchanged
3. `createFolderSheets` — unchanged
4. `createResponseDataSheets` — applies `rowFilters` before writing rows
5. `createCustomTableSheets` — **new** — generates custom table sheets

`createResponseDataSheets` calls `applyRowFilter()` which resolves the matching
`RowFilterGroup` (request-specific then wildcard) and evaluates each row via
`RowConditionEvaluator`.

`createCustomTableSheets` builds a `Map<requestName, List<ObjectNode>>` from all
results, then for each `CustomTableSpec`:

- **Single source**: looks up rows, applies `where` clause, selects columns.
- **Join source**: calls `buildJoinedRows()` which builds a right-side index map,
  performs an inner join, stores merged fields as `"alias.field"` keys (plus
  un-prefixed copies for non-conflicting fields), then applies `where` and column selection.

A shared `usedNames` `Set<String>` ensures no sheet name collisions between standard
response data sheets and custom table sheets.

## CLI Notes

- `--list`: list collection JSON files from `COLLECTIONS_DIR`
- `--list-filters`: list filter JSON files from `FILTERS_DIR`
- no `--filter`:
  - if exactly one filter exists, it is auto-selected
  - if multiple filters exist, run fails and asks for explicit `--filter`
- non-interactive daily mode:
   - keep defaults in `.env`
   - keep one filter in `FILTERS_DIR`
   - run with one command: `java -jar target/postman-excel-runner-1.0.0.jar --env .env`

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
|-------|----------|
| `FilterLoaderTest` | JSON deserialization of filter files |
| `FilterValidatorTest` | All strict validation rules including rowFilters, dateConfig, customTables |
| `DateWindowResolverTest` | All 10 date presets, week boundaries, case-insensitivity, invalid preset rejection |
| `RowConditionEvaluatorTest` | All 18 operators, AND/OR logic, missing fields, DATE_PRESET, DATE_RANGE, custom format |
| `ExcelReportGeneratorTest` | Sheet structure, row filtering applied, custom table sheet generation |
| `CredentialLoaderFilterOverrideTest` | filter auth/vars precedence over `.env` |
| `PostmanCollectionParserTest` | Postman collection JSON parsing |
| `VariableResolverTest` | Variable placeholder substitution |
