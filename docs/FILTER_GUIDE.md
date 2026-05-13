# Filter File Guide

This guide teaches how to write filter files for the Postman Excel Report Automation tool.

A filter file is a JSON configuration that controls:
- Which requests to execute
- Which response columns to display
- Which rows to include in sheets (row filtering)
- How to parse date fields
- How to generate custom joined/projected tables

---

## Quick Start

Minimal filter file (one request, no filtering):

```json
{
  "collection": "jsonplaceholder",
  "requests": ["List posts"]
}
```

Filter with row conditions:

```json
{
  "collection": "jsonplaceholder",
  "requests": ["List posts"],
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "userId", "op": "EQ", "value": "1" }
      ]
    }
  }
}
```

---

## File Structure

Top-level fields in a filter file:

```json
{
  "collection":      "string (optional)",
  "requests":        ["array of request names (optional)"],
  "responseColumns": { "map of request names to column lists (optional)" },
  "outputPrefix":    "string (optional)",
  "auth":            { "authentication overrides (optional)" },
  "vars":            { "variable overrides (optional)" },
  "rowFilters":      { "row-level conditions (optional)" },
  "dateConfig":      { "date parsing hints (optional)" },
  "customTables":    [ "custom table definitions (optional)" ]
}
```

All top-level fields are optional and backward-compatible. If you omit a field, defaults apply.

---

## Collection Selection

Specify which Postman collection to use:

```json
{
  "collection": "jsonplaceholder"
}
```

Matching rules:
- Match by file stem (e.g., `jsonplaceholder.json` → `"collection": "jsonplaceholder"`)
- Match by collection display name (e.g., `"collection": "My API"`)
- If omitted, must be provided via `--collection-name` CLI flag

---

## Request Selection

Whitelist specific requests to execute:

```json
{
  "requests": [
    "List posts",
    "Get post by ID",
    "List comments"
  ]
}
```

Rules:
- Must match **exact** request names from the collection
- If omitted, **all** requests run
- If any name doesn't exist in the collection, validation fails

---

## Response Column Selection

Control which columns appear in the Response Data sheet:

```json
{
  "responseColumns": {
    "*": ["id", "title", "userId"],
    "Get post by ID": ["id", "title", "body", "userId"],
    "List comments": ["id", "email"]
  }
}
```

Rules:
- Key `"*"` = default for any request not listed by name
- Request-specific lists override the wildcard
- Columns are whitelisted; non-listed columns are hidden
- If a column doesn't exist in the response, it's silently skipped
- Omit this field to show all columns

---

## Row Filtering

Include or exclude rows based on field values.

### Structure

```json
{
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [ /* rule objects */ ]
    },
    "List posts": {
      "logic": "OR",
      "rules": [ /* rule objects */ ]
    }
  }
}
```

- Key `"*"` = default for any request not listed by name
- Request-specific rules override the wildcard
- `logic` is `"AND"` (default, all rules must match) or `"OR"` (any rule matches)

### Operators

All operators are case-insensitive. Each rule has:
- `field`: the response JSON field name
- `op`: the operator (see table below)
- `value`, `from`, `to`: operator-specific parameters

#### Comparison Operators

| Operator | Description | Requires `value` |
|----------|-------------|:----------------:|
| `EQ` | Equals (numeric or string, case-insensitive) | ✓ |
| `NEQ` | Not equals | ✓ |
| `GT` | Greater than (numeric) | ✓ |
| `GTE` | Greater than or equal (numeric) | ✓ |
| `LT` | Less than (numeric) | ✓ |
| `LTE` | Less than or equal (numeric) | ✓ |

Example:

```json
{ "field": "age", "op": "GT", "value": "18" }
{ "field": "status", "op": "EQ", "value": "active" }
```

#### String Operators

| Operator | Description | Requires `value` |
|----------|-------------|:----------------:|
| `CONTAINS` | String contains (case-insensitive) | ✓ |
| `NOT_CONTAINS` | String does not contain | ✓ |
| `STARTS_WITH` | String starts with (case-insensitive) | ✓ |
| `ENDS_WITH` | String ends with (case-insensitive) | ✓ |

Example:

```json
{ "field": "email", "op": "CONTAINS", "value": "@example.com" }
{ "field": "name", "op": "STARTS_WITH", "value": "john" }
```

#### List Operators

| Operator | Description | `value` format |
|----------|-------------|:----------------:|
| `IN` | Value in comma-separated list | ✓ comma-list |
| `NOT_IN` | Value not in list | ✓ comma-list |

Example:

```json
{ "field": "status", "op": "IN", "value": "active,pending,review" }
{ "field": "priority", "op": "NOT_IN", "value": "low,medium" }
```

#### Null/Boolean Operators

| Operator | Description | Requires `value` |
|----------|-------------|:----------------:|
| `IS_NULL` | Field is null or missing | — |
| `IS_NOT_NULL` | Field is present and non-null | — |
| `IS_TRUE` | Field is boolean true or string `"true"` | — |
| `IS_FALSE` | Field is boolean false or string `"false"` | — |

Example:

```json
{ "field": "archived", "op": "IS_FALSE" }
{ "field": "metadata", "op": "IS_NOT_NULL" }
```

#### Pattern Matching

| Operator | Description | Requires `value` |
|----------|-------------|:----------------:|
| `REGEX` | Field matches Java regex pattern | ✓ |

Example:

```json
{ "field": "zipCode", "op": "REGEX", "value": "^[0-9]{5}(-[0-9]{4})?$" }
```

#### Date Operators

| Operator | Description | `value` required |
|----------|-------------|:----------------:|
| `DATE_PRESET` | Field date falls within a preset window | ✓ preset name |
| `DATE_RANGE` | Field date is between `from` and `to` | `from` and/or `to` |

Explained in detail in the [Date Filtering](#date-filtering) section.

### Examples

Single rule (must enable = true):

```json
{
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "enabled", "op": "IS_TRUE" }
      ]
    }
  }
}
```

Multiple rules with AND (all must match):

```json
{
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "status", "op": "EQ", "value": "active" },
        { "field": "approved", "op": "IS_TRUE" },
        { "field": "score", "op": "GT", "value": "50" }
      ]
    }
  }
}
```

Multiple rules with OR (any can match):

```json
{
  "rowFilters": {
    "*": {
      "logic": "OR",
      "rules": [
        { "field": "priority", "op": "EQ", "value": "high" },
        { "field": "urgent", "op": "IS_TRUE" },
        { "field": "daysOverdue", "op": "GT", "value": "7" }
      ]
    }
  }
}
```

Request-specific filters override wildcard:

```json
{
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [{ "field": "enabled", "op": "IS_TRUE" }]
    },
    "List posts": {
      "logic": "AND",
      "rules": [
        { "field": "userId", "op": "EQ", "value": "1" },
        { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
      ]
    }
  }
}
```

---

## Date Filtering

Filter rows based on date field values. Two approaches: presets and custom ranges.

### Date Presets

Filter using named time windows relative to the execution time:

```json
{
  "field": "modifiedDate",
  "op": "DATE_PRESET",
  "value": "YESTERDAY"
}
```

#### Available Presets

| Preset | Window |
|--------|--------|
| `TODAY` | Current day, 00:00 – 23:59:59 |
| `YESTERDAY` | Previous day, 00:00 – 23:59:59 |
| `THIS_WEEK` | Monday – Sunday of current week |
| `LAST_WEEK` | Monday – Sunday of previous week |
| `THIS_MONTH` | 1st – last day of current month |
| `LAST_MONTH` | 1st – last day of previous month |
| `THIS_QUARTER` | Current calendar quarter (Jan/Apr/Jul/Oct – Mar/Jun/Sep/Dec) |
| `LAST_QUARTER` | Previous calendar quarter |
| `THIS_YEAR` | Jan 1 – Dec 31 of current year |
| `LAST_YEAR` | Jan 1 – Dec 31 of previous year |

Example:

```json
{
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "createdDate", "op": "DATE_PRESET", "value": "THIS_MONTH" },
        { "field": "status", "op": "EQ", "value": "completed" }
      ]
    }
  }
}
```

### Custom Date Range

Filter using explicit from/to boundaries:

```json
{
  "field": "modifiedDate",
  "op": "DATE_RANGE",
  "from": "2026-03-01",
  "to": "2026-03-31T23:59:59"
}
```

Rules:
- Both `from` and `to` are optional (open-ended ranges allowed)
- At least one must be provided
- Dates use the format configured in `dateConfig`, or ISO-8601 by default
- Comparison is **inclusive** on both boundaries

Examples:

```json
// Entire Q1 2026
{ "field": "ts", "op": "DATE_RANGE", "from": "2026-01-01", "to": "2026-03-31" }

// Before a specific date
{ "field": "expiry", "op": "DATE_RANGE", "to": "2026-12-31" }

// After a specific date
{ "field": "published", "op": "DATE_RANGE", "from": "2026-01-01" }
```

### Date Parsing Configuration

Dates in your JSON responses may use different formats. Tell the evaluator how to parse them using `dateConfig`:

```json
{
  "dateConfig": {
    "*": {
      "modifiedDate": {
        "format": "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "timezone": "UTC"
      },
      "createdAt": {
        "format": "dd/MM/yyyy",
        "timezone": "Asia/Kolkata"
      }
    },
    "Specific Request": {
      "timestamp": {
        "format": "MM/dd/yyyy HH:mm",
        "timezone": "America/New_York"
      }
    }
  }
}
```

- Outer key: request name or `"*"` (wildcard, applied to all requests)
- Request-specific entries override wildcard for the same field
- `format`: a `java.time.format.DateTimeFormatter` pattern (omit for ISO-8601 auto-detection)
- `timezone`: a `java.time.ZoneId` name (omit for JVM default, usually UTC or system timezone)

#### Common Format Patterns

| Format | Example | Pattern |
|--------|---------|---------|
| ISO 8601 (auto) | `2026-03-15T10:30:00Z` | — omit `format` |
| Date only | `2026-03-15` | `yyyy-MM-dd` |
| US style | `03/15/2026` | `MM/dd/yyyy` |
| EU style | `15/03/2026` | `dd/MM/yyyy` |
| Full timestamp | `2026-03-15 10:30:45` | `yyyy-MM-dd HH:mm:ss` |
| 12-hour time | `03/15/2026 10:30 AM` | `MM/dd/yyyy hh:mm a` |

#### Common Timezone Names

- `UTC` — Coordinated Universal Time
- `America/New_York` — Eastern Time
- `America/Los_Angeles` — Pacific Time
- `Europe/London` — UK
- `Europe/Paris` — Central European Time
- `Asia/Tokyo` — Japan Standard Time
- `Asia/Kolkata` — Indian Standard Time
- `Australia/Sydney` — Australian Eastern Time

See [IANA Time Zone Database](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones) for complete list.

#### Example: Parsing Non-ISO Dates

If your API returns `createdAt` as `"15/03/2026"` (DD/MM/YYYY):

```json
{
  "dateConfig": {
    "*": {
      "createdAt": {
        "format": "dd/MM/yyyy",
        "timezone": "UTC"
      }
    }
  },
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "createdAt", "op": "DATE_PRESET", "value": "THIS_MONTH" }
      ]
    }
  }
}
```

---

## Custom Tables

Generate additional sheets by filtering, selecting columns from, and joining request responses.

### Single-Source Custom Table

Create a filtered and projected view of a single request's data:

```json
{
  "customTables": [
    {
      "name": "Active Users",
      "sourceRequest": "List users",
      "columns": ["id", "name", "email"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "active", "op": "IS_TRUE" }
        ]
      }
    }
  ]
}
```

- `name`: sheet name (must be unique across all custom tables)
- `sourceRequest`: exact name of a request in the collection
- `columns`: whitelist of response fields to include (optional; omit to include all)
- `where`: row filter conditions (uses same syntax as `rowFilters`, optional)

### Join-Based Custom Table

Create a new sheet by joining two request responses on a key:

```json
{
  "customTables": [
    {
      "name": "Posts with Author Names",
      "sources": [
        { "request": "List posts", "as": "p" },
        { "request": "List users", "as": "u" }
      ],
      "joinOn": [
        { "leftField": "userId", "rightField": "id" }
      ],
      "columns": ["p.id", "p.title", "u.name", "u.email"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "u.active", "op": "IS_TRUE" }
        ]
      }
    }
  ]
}
```

- `sources`: array of 2 sources, each with:
  - `request`: exact request name
  - `as`: short alias (used in column references and filter rules)
- `joinOn`: array of join conditions, each with:
  - `leftField`: field name from first source
  - `rightField`: field name from second source
- `columns`: column references with `"alias.field"` notation (e.g., `"p.id"`, `"u.name"`)
  - Non-ambiguous fields can omit the prefix (e.g., `"id"` if only one source has it)
- `where`: row filter conditions using `"alias.field"` notation for ambiguous fields

Rules:
- Only inner join is supported (rows without a match in both sources are excluded)
- If multiple join conditions exist, all must match (AND logic)
- Join always happens on the first two sources (at most 2 sources per table)

### Full Example: Products with Category and Inventory

```json
{
  "customTables": [
    {
      "name": "In-Stock Products",
      "sources": [
        { "request": "List products", "as": "prod" },
        { "request": "List inventory", "as": "inv" }
      ],
      "joinOn": [
        { "leftField": "id", "rightField": "productId" }
      ],
      "columns": ["prod.id", "prod.name", "prod.price", "inv.quantity", "inv.warehouse"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "inv.quantity", "op": "GT", "value": "0" },
          { "field": "prod.status", "op": "EQ", "value": "active" }
        ]
      }
    }
  ]
}
```

---

## Authentication Overrides

Override credentials from `.env` for a specific run:

```json
{
  "auth": {
    "API_USERNAME": "qa-user",
    "API_PASSWORD": "qa-pass",
    "BEARER_TOKEN": "qa-token",
    "API_KEY": "qa-api-key",
    "APIKEY_HEADER": "X-API-Key"
  }
}
```

Rules:
- Any key can be omitted; only provided values are overridden
- See `.env` template for all available auth keys
- If both `API_KEY` and `APIKEY_HEADER` are provided, both are used
- If only one is provided, validation fails

---

## Variable Overrides

Override runtime variables from `.env` for a specific run:

```json
{
  "vars": {
    "TEAM": "qa",
    "ENV_NAME": "staging",
    "BASE_URL": "https://staging.example.com"
  }
}
```

- Any `.env` variable key can be overridden
- Precedence: filter `vars` > `.env` > system environment

---

## Output Customization

Control the output filename:

```json
{
  "outputPrefix": "daily-qa-run"
}
```

The final filename will be: `daily-qa-run_{collection}_{timestamp}.xlsx`

Rules:
- Must be non-blank if provided
- No path separators (must be a simple name)
- Timestamp is added automatically

---

## Complete Example

A comprehensive filter file demonstrating all features:

```json
{
  "collection": "jsonplaceholder",
  "requests": ["List posts", "List users", "List comments"],
  "responseColumns": {
    "*": ["id", "title", "body", "userId", "name", "email"],
    "List posts": ["id", "title", "userId"],
    "List users": ["id", "name", "email", "phone"]
  },
  "outputPrefix": "daily-report",

  "auth": {
    "BEARER_TOKEN": "prod-token"
  },
  "vars": {
    "TEAM": "analytics",
    "ENV_NAME": "production"
  },

  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "enabled", "op": "IS_TRUE" }
      ]
    },
    "List posts": {
      "logic": "AND",
      "rules": [
        { "field": "userId", "op": "EQ", "value": "1" },
        { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
      ]
    }
  },

  "dateConfig": {
    "*": {
      "modifiedDate": { "format": "yyyy-MM-dd'T'HH:mm:ss'Z'", "timezone": "UTC" },
      "createdDate": { "format": "dd/MM/yyyy", "timezone": "Asia/Kolkata" }
    }
  },

  "customTables": [
    {
      "name": "Yesterday's Posts",
      "sourceRequest": "List posts",
      "columns": ["id", "title", "userId"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "modifiedDate", "op": "DATE_PRESET", "value": "YESTERDAY" }
        ]
      }
    },
    {
      "name": "Posts by Active Authors",
      "sources": [
        { "request": "List posts", "as": "p" },
        { "request": "List users", "as": "u" }
      ],
      "joinOn": [
        { "leftField": "userId", "rightField": "id" }
      ],
      "columns": ["p.id", "p.title", "u.name", "u.email"],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "u.enabled", "op": "IS_TRUE" }
        ]
      }
    }
  ]
}
```

---

## Validation and Error Handling

### Validation Errors (Fail Fast)

The tool stops immediately if:
- Unknown request names in `requests`, `responseColumns`, `rowFilters`, `dateConfig`, or `customTables`
- Invalid operator name in a rule
- Unknown date preset name
- `DATE_RANGE` rule with no `from` and no `to`
- Invalid `DateTimeFormatter` pattern in `dateConfig`
- Unknown timezone in `dateConfig`
- Custom table missing both `sourceRequest` and `sources`
- Custom table with both `sourceRequest` and `sources` (mutually exclusive)
- Multi-source custom table missing `joinOn`
- Duplicate custom table names
- Blank `outputPrefix`
- Collection mismatch

### Warnings (Non-Fatal)

The tool logs a warning and continues:
- Row filter references a field missing from a particular response (that row is excluded)
- Date format parsing fails (that row is excluded from that rule)
- Custom table produces 0 rows (sheet is skipped, logged as INFO)

---

## Tips and Best Practices

1. **Start simple**: Begin with just `collection` and `requests`, add filters incrementally.

2. **Test incrementally**: Run with a single request first, verify output, then add more.

3. **Use wildcards for common rules**: Apply row filters to `"*"` if they apply to most requests, then override specific ones.

4. **Keep date formats consistent**: If your API uses the same date format across fields, define it once in `"*"` dateConfig.

5. **Name custom tables descriptively**: Use names like `"Active Users"` or `"Orders This Month"` so the Excel sheets are self-explanatory.

6. **Verify field names**: JSON field names are case-sensitive. Use the browser's "Copy JSON path" or `jq` to verify exact field names.

7. **Test joins carefully**: Verify that the join condition field names exist in both requests before saving.

8. **Use AND logic for strict filtering**: Use `"AND"` when multiple conditions must all be true (safer, more predictable).

9. **Use OR logic for inclusive filtering**: Use `"OR"` when any of several conditions can be true (e.g., "priority is HIGH OR days overdue > 7").

10. **Document custom tables**: Add comments (via `.md` files or a readme) explaining what each custom table is for if you have many.

---

## Common Patterns

### Filter for today's data with a custom date format

```json
{
  "dateConfig": {
    "*": {
      "createdAt": { "format": "MM-dd-yyyy HH:mm", "timezone": "America/New_York" }
    }
  },
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "createdAt", "op": "DATE_PRESET", "value": "TODAY" }
      ]
    }
  }
}
```

### Show only recent, active records

```json
{
  "rowFilters": {
    "*": {
      "logic": "AND",
      "rules": [
        { "field": "status", "op": "EQ", "value": "active" },
        { "field": "archived", "op": "IS_FALSE" },
        { "field": "lastModified", "op": "DATE_PRESET", "value": "THIS_MONTH" }
      ]
    }
  }
}
```

### Include rows matching any of several statuses

```json
{
  "rowFilters": {
    "*": {
      "logic": "OR",
      "rules": [
        { "field": "status", "op": "EQ", "value": "critical" },
        { "field": "status", "op": "EQ", "value": "high" },
        { "field": "overdue", "op": "IS_TRUE" }
      ]
    }
  }
}
```

### Join orders with customer information

```json
{
  "customTables": [
    {
      "name": "Orders with Customer Details",
      "sources": [
        { "request": "List orders", "as": "o" },
        { "request": "List customers", "as": "c" }
      ],
      "joinOn": [
        { "leftField": "customerId", "rightField": "id" }
      ],
      "columns": ["o.id", "o.orderDate", "o.total", "c.name", "c.email", "c.phone"]
    }
  ]
}
```

### Filter a joined table by both sources

```json
{
  "customTables": [
    {
      "name": "Recent Orders from VIP Customers",
      "sources": [
        { "request": "List orders", "as": "o" },
        { "request": "List customers", "as": "c" }
      ],
      "joinOn": [
        { "leftField": "customerId", "rightField": "id" }
      ],
      "where": {
        "logic": "AND",
        "rules": [
          { "field": "o.orderDate", "op": "DATE_PRESET", "value": "THIS_MONTH" },
          { "field": "c.tier", "op": "EQ", "value": "VIP" }
        ]
      }
    }
  ]
}
```

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `Filter has unknown request names...` | Typo in `requests`, `responseColumns`, `rowFilters`, or `dateConfig` key | Use exact request name from collection |
| `Unknown operator "XYZ"` | Misspelled operator name | Check operator table, use correct capitalization (e.g., `EQ`, not `eq`) |
| `Unknown date preset "XYZ"` | Typo in preset name or omitted in `"*"` dateConfig | Use preset name from table above; check capitalization |
| `DATE_RANGE rule has neither from nor to` | Both `from` and `to` are missing or null | Provide at least one boundary |
| `Invalid format pattern...` | Bad `DateTimeFormatter` pattern | Check Java DateTimeFormatter syntax or use an example pattern from the table |
| `Unknown timezone "XYZ"` | Typo in timezone name or doesn't exist | Use valid timezone from IANA database (e.g., `UTC`, `America/New_York`) |
| `[WARN] Could not parse date value...` | Date in response doesn't match configured format | Verify actual date format in API response; update `dateConfig` pattern |
| `Custom table "XYZ" produced 0 rows` | `where` clause filtered all rows, or join condition has no matches | Verify row filter logic or join field names |
| `Custom table missing both sourceRequest and sources` | Table definition incomplete | Add either `sourceRequest` (single-source) or `sources` + `joinOn` (join-based) |
| `Collection mismatch` | Filter file `collection` doesn't match provided/inferred collection | Use correct collection name or omit `collection` to auto-detect |

---

## File Locations

Filter files are stored in the `FILTERS_DIR` directory (default: `./filters/`).

Naming convention: Use descriptive names that reflect the filter's purpose:
- `daily.json` — daily run configuration
- `qa-smoke-tests.json` — QA smoke test filter
- `posts-only.json` — focuses on post-related requests
- `date-range-2026-q1.json` — Q1 2026 date range

Filters are referenced by file name (stem):
```bash
java -jar target/postman-excel-runner-1.0.0.jar --filter daily
```

---

## See Also

- [USER_GUIDE.md](USER_GUIDE.md) — full system overview
- [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) — architecture and testing
