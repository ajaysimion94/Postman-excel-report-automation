# User Guide

This is the primary day-to-day guide (setup, run commands, filters, and troubleshooting).

## Quick Daily Commands

Default daily mode is non-interactive: configure once in `.env` and run a single command.

### 1) List collections

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env --list
```

### 2) List filters

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env --list-filters
```

### 3) Run with a selected filter (most common)

```bash
java -jar target/postman-excel-runner-1.0.0.jar \
  --env .env \
  --filter posts-only
```

### 4) Run with one command (recommended daily flow)

If `FILTERS_DIR` has exactly one valid filter, the app auto-selects it.

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env
```

### 5) Include response body preview cells

```bash
java -jar target/postman-excel-runner-1.0.0.jar \
  --env .env \
  --filter posts-only \
  --include-body
```

### When it asks for explicit input (only when necessary)

- Multiple filters in `FILTERS_DIR`: pass `--filter <name>`
- No collection resolvable from CLI/filter: pass `--collection-name <name>` or `--collection <path>`
- Missing env values (timeouts/auth/paths): set them in `.env`

---

## Build with Maven

Use Maven Wrapper (recommended, no global Maven required):

```bash
./mvnw clean package
```

Fast local build without running tests:

```bash
./mvnw clean package -DskipTests
```

If Maven is installed globally, equivalent commands are:

```bash
mvn clean package
mvn clean package -DskipTests
```

The runnable shaded JAR is produced at:

```text
target/postman-excel-runner-1.0.0.jar
```

---

## Step-by-Step Setup Workflow

### 1. Prerequisites

- Java 17+
- macOS/Linux/Windows terminal

### 2. Project layout

```text
Automation/
├── collections/
├── filters/
├── reports/
├── .env
└── target/postman-excel-runner-1.0.0.jar
```

### 3. Configure `.env`

Use this as your baseline (same keys as `.env.example`):

```env
COLLECTIONS_DIR=/absolute/path/to/your/collections
FILTERS_DIR=/absolute/path/to/your/filters

REQUEST_TIMEOUT_SECONDS=30
MAX_RESPONSE_MB=10

API_USERNAME=my-user
API_PASSWORD=my-password
BEARER_TOKEN=my-token
API_KEY=my-api-key
APIKEY_HEADER=X-API-Key

OUTPUT_FILE=/absolute/path/to/reports/{collection}_{timestamp}.xlsx
```

### 4. Add collections

Export Postman collections as v2.1 JSON and place them in `collections/`.

### 5. Add filters

Place your filter JSON files in `filters/`.

### 6. Build once

```bash
./mvnw clean package -DskipTests
```

### 7. Run daily

Use either:

- `--filter <name>` for explicit control
- no `--filter` when there is exactly one valid filter in `FILTERS_DIR`

---

## Filter File Format

A filter file is a JSON file that controls which requests run, which response columns appear,
which rows are included, and whether custom tables are generated.  
All top-level fields are optional and backward-compatible.

```json
{
  "collection":      "jsonplaceholder",
  "requests":        ["List all posts", "Get post by ID"],
  "responseColumns": {
    "*":              ["id", "title", "userId"],
    "Get post by ID": ["id", "title", "body", "userId"]
  },
  "outputPrefix": "posts-focus",
  "auth": {
    "BEARER_TOKEN": "override-token",
    "API_KEY":      "override-key",
    "APIKEY_HEADER": "X-API-Key"
  },
  "vars": {
    "TEAM":     "smoke",
    "ENV_NAME": "staging"
  },

  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "enabled", "op": "EQ", "value": "true" }
      ]
    },
    "List all posts": {
      "logic": "AND",
      "rules": [
        { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
      ]
    }
  },

  "dateConfig": {
    "*": {
      "modifiedDate": { "format": "yyyy-MM-dd'T'HH:mm:ss'Z'", "timezone": "UTC" }
    },
    "List all posts": {
      "createdAt": { "format": "dd/MM/yyyy", "timezone": "Asia/Kolkata" }
    }
  },

  "customTables": [
    {
      "name":          "Yesterday Active Posts",
      "sourceRequest": "List all posts",
      "columns":       ["id", "title", "modifiedDate"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "enabled",      "op": "EQ",         "value": "true"      },
          { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
        ]
      }
    },
    {
      "name": "Posts with Author Names",
      "sources": [
        { "request": "List all posts", "as": "p" },
        { "request": "List users",     "as": "u" }
      ],
      "joinOn":  [{ "leftField": "userId", "rightField": "id" }],
      "columns": ["p.id", "p.title", "u.name", "u.email"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "u.name", "op": "NOT_CONTAINS", "value": "Deleted" }
        ]
      }
    }
  ]
}
```

---

### Field reference

| Field | Type | Description |
|-------|------|-------------|
| `collection` | string | Optional. Must match the collection name or file stem. |
| `requests` | string[] | Optional. Whitelist of exact request names to execute. |
| `responseColumns` | map | Optional. Per-request or `"*"` column whitelists for Response Data sheets. |
| `outputPrefix` | string | Optional. Prepended to the output filename. |
| `auth` | object | Optional. Overrides `.env` auth values for this run. |
| `vars` | map | Optional. Overrides any `.env` variable keys. |
| `rowFilters` | map | Optional. Per-request or `"*"` row conditions for Response Data sheets. |
| `dateConfig` | map | Optional. Date parsing hints per request and per field (required for DATE_PRESET / DATE_RANGE rules when dates are not ISO-8601). |
| `customTables` | array | Optional. Defines extra sheets with filtered, projected, or joined rows. |

---

### Row filters (`rowFilters`)

Row filters are applied to each Response Data sheet to include only matching rows.

```json
"rowFilters": {
  "*": {
    "logic": "AND",
    "rules": [ { "field": "status", "op": "EQ", "value": "active" } ]
  },
  "My Request": {
    "logic": "OR",
    "rules": [
      { "field": "priority", "op": "EQ",  "value": "high" },
      { "field": "urgent",   "op": "IS_TRUE" }
    ]
  }
}
```

- Key `"*"` = default for any request not listed by name.
- Request-specific rules override the wildcard.
- `logic` is `"AND"` (default) or `"OR"`.

#### Supported operators

| Operator | Description | `value` required? |
|----------|-------------|:-----------------:|
| `EQ` | Equals (numeric or string, case-insensitive) | ✓ |
| `NEQ` | Not equals | ✓ |
| `GT` / `GTE` | Greater than / greater than or equal | ✓ |
| `LT` / `LTE` | Less than / less than or equal | ✓ |
| `CONTAINS` | String contains (case-insensitive) | ✓ |
| `NOT_CONTAINS` | String does not contain | ✓ |
| `STARTS_WITH` | String starts with (case-insensitive) | ✓ |
| `ENDS_WITH` | String ends with (case-insensitive) | ✓ |
| `IN` | Value in comma-separated list | ✓ comma-list |
| `NOT_IN` | Value not in list | ✓ comma-list |
| `IS_NULL` | Field is null or missing | — |
| `IS_NOT_NULL` | Field is present and non-null | — |
| `IS_TRUE` | Field is boolean true or string `"true"` | — |
| `IS_FALSE` | Field is boolean false or string `"false"` | — |
| `REGEX` | Field matches Java regex | ✓ |
| `DATE_PRESET` | Field date falls within a preset window | ✓ preset name |
| `DATE_RANGE` | Field date is between `from` and `to` | `from` and/or `to` |

#### Date presets (`DATE_PRESET` values)

| Preset | Window |
|--------|--------|
| `TODAY` | Current day, 00:00 – 23:59:59 |
| `YESTERDAY` | Previous day |
| `THIS_WEEK` | Monday – Sunday of current week |
| `LAST_WEEK` | Monday – Sunday of previous week |
| `THIS_MONTH` | 1st – last day of current month |
| `LAST_MONTH` | 1st – last day of previous month |
| `THIS_QUARTER` | Current calendar quarter |
| `LAST_QUARTER` | Previous calendar quarter |
| `THIS_YEAR` | Jan 1 – Dec 31 of current year |
| `LAST_YEAR` | Jan 1 – Dec 31 of previous year |

#### Custom date range (`DATE_RANGE`)

```json
{
  "field": "createdAt",
  "op":    "DATE_RANGE",
  "from":  "2026-01-01",
  "to":    "2026-03-31T23:59:59"
}
```

`from` and `to` use the format configured in `dateConfig` (or ISO-8601 by default).
Either `from` or `to` may be omitted for open-ended ranges.

---

### Date config (`dateConfig`)

Tell the evaluator how to parse date strings in specific fields.
Required when dates are not ISO-8601 (e.g., `dd/MM/yyyy` or `MM-dd-yyyy HH:mm`).
If omitted, the evaluator tries common ISO-8601 patterns automatically and warns if it cannot parse.

```json
"dateConfig": {
  "*": {
    "modifiedDate": { "format": "yyyy-MM-dd'T'HH:mm:ss'Z'", "timezone": "UTC"          },
    "createdAt":    { "format": "dd/MM/yyyy",                "timezone": "Asia/Kolkata" }
  },
  "My Specific Request": {
    "timestamp": { "format": "MM/dd/yyyy HH:mm", "timezone": "America/New_York" }
  }
}
```

- Outer key: request name or `"*"` (wildcard, applied to all requests).
- Request-specific entries override wildcard for the same field.
- `format`: a `java.time.format.DateTimeFormatter` pattern. Omit for ISO-8601 auto-detection.
- `timezone`: a `java.time.ZoneId` name (e.g., `UTC`, `Asia/Kolkata`). Omit to use JVM default.

---

### Custom tables (`customTables`)

Custom tables produce extra sheets alongside the standard response data sheets.
Each table can be built from a single request or by joining two requests.

#### Single-source table

```json
{
  "name":          "Active Users",
  "sourceRequest": "List users",
  "columns":       ["id", "name", "email"],
  "where": {
    "logic": "AND",
    "rules": [ { "field": "active", "op": "IS_TRUE" } ]
  }
}
```

#### Join table (inner join, two requests)

```json
{
  "name": "Orders with Customer Names",
  "sources": [
    { "request": "List orders",    "as": "o" },
    { "request": "List customers", "as": "c" }
  ],
  "joinOn":  [{ "leftField": "customerId", "rightField": "id" }],
  "columns": ["o.id", "o.amount", "c.name", "c.email"],
  "where": {
    "logic": "AND",
    "rules": [ { "field": "o.amount", "op": "GT", "value": "100" } ]
  }
}
```

- `joinOn` maps one or more field pairs between the first and second source.
- Column references use `"alias.field"` notation when sources share field names.
- The `where` clause uses the same rule syntax as `rowFilters`.
- Non-ambiguous fields (present in only one source) can be referenced without a prefix.
- Join type: inner join only (rows without a match in either source are excluded).

---

### Validation policy (strict)

Filter validation runs before HTTP execution. The run fails fast for:

- Unknown request names in `requests`, `responseColumns`, `rowFilters`, `dateConfig`, or `customTables`.
- Invalid operator names in row filter rules.
- Unknown date preset names.
- Invalid `DateTimeFormatter` pattern string or unknown `ZoneId` timezone in `dateConfig`.
- `DATE_RANGE` rule with no `from` and no `to`.
- Custom table missing both `sourceRequest` and `sources`.
- Custom table with both `sourceRequest` and `sources` (mutually exclusive).
- Multi-source custom table with missing `joinOn`.
- Duplicate custom table names.
- Collection mismatch, blank `outputPrefix`, invalid API key auth block.

**Warning only (run continues):**

- A row filter rule references a field not present in a particular response row (row is excluded from that sheet).
- `dateConfig` is absent for a date rule — the evaluator tries ISO-8601 auto-detection and warns if it cannot parse a value.

---

## Included Sample Filters

| File | Purpose |
|------|---------|
| `posts-only.json` | Column selection + row filter + single-source custom table |
| `minimal-daily.json` | Single-request quick daily run with row condition |
| `auth-override.json` | Filter-level auth and vars overrides |
| `posts-with-authors.json` | Join-style custom table combining two requests |
| `date-filter-example.json` | DATE_PRESET and DATE_RANGE usage with dateConfig |

---

## Credential Precedence

Runtime variable precedence is:

1. filter `auth` and filter `vars`
2. `.env`
3. system environment
4. collection variables (used as base values before runtime overrides)

---

## Troubleshooting

| Message | Fix |
|---------|-----|
| `Multiple filter files found...` | Pass `--filter <name>` or remove extra filters from `FILTERS_DIR`. |
| `No collection was provided...` | Provide `--collection-name`, `--collection`, or set `collection` in the filter. |
| `Filter has unknown request names...` | Use exact request names from the collection. |
| `Filter responseColumns contains unknown request keys...` | Use `*` or exact request names only. |
| `Filter rowFilters contains unknown request keys...` | Use `*` or exact request names only. |
| `Row filter group ... has invalid logic...` | Use `AND` or `OR`. |
| `Unknown operator...` | See operator table above. |
| `Unknown date preset...` | See preset table above. |
| `Invalid format pattern...` | Verify the `java.time.format.DateTimeFormatter` pattern. |
| `Unknown timezone...` | Use a valid `java.time.ZoneId` name (e.g., `UTC`, `Europe/London`). |
| `[WARN] Could not parse date value...` | Add a `dateConfig` entry with the correct `format` and `timezone`. |
| `[INFO] Custom table "..." produced 0 rows...` | The where-clause filtered all rows — check your rules. |

---

For internals, architecture, and test strategy, see [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md).
