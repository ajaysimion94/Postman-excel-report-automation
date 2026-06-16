# Filter Guide

This guide documents the current `.filter` language implemented by the parser, validator, and Excel generator in this project. Use it as the reference for supported keywords, valid combinations, scope rules, and practical examples.

## Quick Start

```sql
COLLECTION jsonplaceholder;
REQUESTS "List posts", "List users";

FILTER "List posts" WHERE userId = 1;
COLUMNS "List posts": id, userId, title;
SHAPE "List posts" ORDER BY id DESC LIMIT 50;
```

This will:

1. Select the `jsonplaceholder` collection.
2. Run only the two named requests.
3. Keep only rows from `List posts` where `userId = 1`.
4. Show only `id`, `userId`, and `title` in the `List posts` response-data sheet.
5. Sort the remaining rows by `id` descending and keep the first 50.

## 1. Core Syntax Rules

| Rule | Details |
| ---- | ------- |
| Statement terminator | Every statement must end with `;` |
| Comments | Use `# comment` or `-- comment` |
| Keyword case | Keywords are case-insensitive |
| Names and values | Use single or double quotes when a value contains spaces |
| Wildcard | `*` means "all matching request outputs" for supported statements |
| Boolean grouping | Parentheses are supported inside `WHERE` and `HAVING` |
| Request matching | Request names must match the Postman collection request names exactly during validation |

Examples:

```sql
# valid comments
-- valid comments

REQUESTS "List posts", "List users";
FILTER * WHERE status = active;
FILTER "List posts" WHERE (status = active OR priority = high) AND NOT archived IS TRUE;
```

## 2. Keyword Categories

| Category | Keywords |
| -------- | -------- |
| Collection and execution selection | `COLLECTION`, `REQUESTS`, `REQUEST`, `OUTPUT_PREFIX`, `SET` |
| Response-data filtering | `FILTER`, `WHERE`, `AND`, `OR`, `NOT` |
| Value and text operators | `IS`, `NULL`, `TRUE`, `FALSE`, `IN`, `LIKE`, `ILIKE`, `CONTAINS`, `NOT_CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `REGEX` |
| Date filtering | `DATE_CONFIG`, `FORMAT`, `TIMEZONE`, `BETWEEN`, `DATE_PRESET`, `TODAY`, `YESTERDAY`, `THIS_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `LAST_MONTH`, `THIS_QUARTER`, `LAST_QUARTER`, `THIS_YEAR`, `LAST_YEAR` |
| Output shaping | `SHAPE`, `DISTINCT`, `ORDER BY`, `ASC`, `DESC`, `LIMIT`, `OFFSET`, `GROUP BY`, `AGG`, `AS`, `HAVING` |
| Cross-request outputs | `LOOKUP_TABLE`, `FROM`, `LOOKUP`, `BY`, `AS`, `UNION`, `ALL`, `INTERSECT`, `EXCEPT`, `DIFF`, `COMPARE` |
| Array expansion | `EXPAND`, `ON`, `AS` |
| Summary sheet layout | `TITLE`, `DESCRIPTION`, `TEXT`, `KV`, `LV`, `TABLE`, `QT`/`QUICK_TABLE`, `LABEL_TABLE`, `METRICS`, `STATUS`, `$var = FILTER\|TABLE\|UNION\|INTERSECT\|EXCEPT\|DIFF\|COMPARE ...`, `$var = FILTER $other ...`, `$var` |

## 3. Statement Reference

### `COLLECTION`

Syntax:

```sql
COLLECTION <collection-name>;
```

Purpose:

- Declares which collection block the file is targeting.
- When a file contains multiple `COLLECTION` blocks, you must run with `--collection-name` or `--collection` so the correct block is selected.

Example:

```sql
COLLECTION jsonplaceholder;
```

### `OUTPUT_PREFIX`

Syntax:

```sql
OUTPUT_PREFIX <prefix>;
SET OUTPUT_PREFIX <prefix>;
```

Purpose:

- Sets the output filename prefix.
- `SET OUTPUT_PREFIX` and `OUTPUT_PREFIX` are equivalent. `SET` is only used with `OUTPUT_PREFIX`.

Examples:

```sql
OUTPUT_PREFIX daily-run;
SET OUTPUT_PREFIX daily-run;
```

### `REQUESTS`

Syntax:

```sql
REQUESTS <request1>, <request2>, ...;
```

Purpose:

- Selects multiple requests to execute.
- Use quoted names when the request name contains spaces.

Example:

```sql
REQUESTS "List posts", "List users", "Get post by ID";
```

### `REQUEST`

Syntax:

```sql
REQUEST <request>;
REQUEST <request> WHERE <predicate>;
```

Purpose:

- Adds a single request to the execution list.
- Optional inline `WHERE` acts as a shorthand for a request-specific row filter.

Examples:

```sql
REQUEST "List posts";
REQUEST "List posts" WHERE userId = 1;
```

Notes:

- `REQUEST "List posts" WHERE userId = 1;` is equivalent to selecting the request and adding a filter for that request.
- If you later define another `FILTER` for the same request, the later filter replaces the earlier one for that request key.

### `COLUMNS`

Syntax:

```sql
COLUMNS <request-or-*>: <column1>, <column2>, ...;
COLUMNS <request-or-*>: <field> [AS <header>], ...;
```

Purpose:

- Controls which columns appear in a response-data sheet and their Excel header labels.
- Applies to the matching **response-data sheet** and to **Summary** tables that use the same request (via `$var = FILTER ...`).
- Use `AS` to rename headers without changing the JSON field used for cell values.

Examples:

```sql
COLUMNS "List posts": id, userId, title;
COLUMNS "List posts": id AS "Post ID", userId AS "User";
COLUMNS *: id, createdAt;
```

Header labels:

- `id AS "Post ID"` — reads `id` from the response, shows **Post ID** in Excel.
- `userId AS User` — quoted or unquoted labels are allowed.
- Without `AS`, the header matches the field path (e.g. `detail.price`).

Notes:

- The exact request key wins over `*`; they do not merge.
- Request-level `COLUMNS` does not affect `LOOKUP_TABLE` or `UNION` source rows; use the `COLUMNS` clause inside `LOOKUP_TABLE` for lookup sheets (rename supported there too).

### `FILTER`

Syntax:

```sql
FILTER <request-or-*> WHERE <predicate>;
```

Purpose:

- Filters rows on response-data sheets.
- Supports nested boolean logic and date-aware predicates.

Examples:

```sql
FILTER "List posts" WHERE userId = 1;
FILTER * WHERE enabled IS TRUE;
FILTER "List posts" WHERE (status = active OR priority = high) AND NOT archived IS TRUE;
```

Notes:

- The exact request key wins over `*`; they do not merge.
- Combine conditions inside one `WHERE` instead of writing multiple `FILTER` statements for the same key.
- `FILTER` applies to response-data sheets only. It does not change the source rows used by `LOOKUP_TABLE` or `UNION`.

### `DATE_CONFIG`

Syntax:

```sql
DATE_CONFIG <request-or-*>.<field> FORMAT <pattern> [TIMEZONE <zone>];
```

Purpose:

- Tells the date filter engine how to parse a field before evaluating `DATE_PRESET` or `BETWEEN`.

Examples:

```sql
DATE_CONFIG *.createdAt FORMAT "yyyy-MM-dd'T'HH:mm:ss'Z'" TIMEZONE UTC;
DATE_CONFIG Orders.createdAt FORMAT yyyy-MM-dd TIMEZONE Asia/Kolkata;
```

Notes:

- Wildcard and request-specific date configs merge per field, with the request-specific field winning.
- Current parser limitation: request-specific `DATE_CONFIG` keys must be written as one token. If your request name contains spaces, use `*.<field>` instead of a request-specific key.

### `LOOKUP_TABLE`

Syntax:

```sql
LOOKUP_TABLE <table-name>
  FROM <source-request>
  LOOKUP <detail-request>
  BY <field> [AS <variable>]
  [WHERE <predicate>]
  [COLUMNS <column1>, <column2>, ...];
```

Purpose:

- Creates a new sheet by taking each row from one source request, calling a detail request for that row, and merging the results.

Example:

```sql
LOOKUP_TABLE "Items With Details"
  FROM "List Items"
  LOOKUP "Get Item Details"
  BY id
  WHERE detail.price >= 1000 AND detail.availability = in_stock
  COLUMNS id, itemName, detail.price, detail.availability;
```

Notes:

- `WHERE` and `COLUMNS` are both optional and can appear in either order before the semicolon.
- `BY id` means the lookup request must be able to use `{{id}}` when it runs.
- `BY id AS itemid` extracts the `id` field from the source row but injects it as `{{itemid}}` into the detail request URL. Use this when the source field name differs from the URL placeholder name.
- `BY` also supports dot-separated paths for nested fields: `BY data.id AS itemid`.
- Merged detail fields are always available as `detail.<field>`.
- If a detail field does not clash with a source field, it is also available without the `detail.` prefix.
- `LOOKUP_TABLE` uses raw request response rows as its source. Request-level `FILTER` and `COLUMNS` do not change its input rows.
- After the table is built, you can target it with `SHAPE "<table-name>" ...`.

### `SHAPE`

Syntax:

```sql
SHAPE <key> [DISTINCT] [ORDER BY ...] [LIMIT ...] [OFFSET ...] [GROUP BY ...] [AGG ...] [HAVING ...];
```

Purpose:

- Shapes an output after rows are collected.
- Can target a request response-data sheet, a `LOOKUP_TABLE` output, a `UNION` output, or `*` as a wildcard.

Examples:

```sql
SHAPE "List posts" DISTINCT ORDER BY id DESC LIMIT 50 OFFSET 0;
SHAPE "Items With Details" ORDER BY detail.price DESC LIMIT 100;
SHAPE "MergedPosts" GROUP BY userId AGG COUNT(*) AS cnt HAVING cnt > 1;
SHAPE * LIMIT 200;
```

Notes:

- The exact key wins over `*`; they do not merge at runtime.
- Repeating `SHAPE` for the same key is allowed. Clauses from later statements overwrite the same clause type and keep the others.

Example of split `SHAPE` statements:

```sql
SHAPE "List posts" LIMIT 100;
SHAPE "List posts" ORDER BY id DESC;
```

This results in both `LIMIT 100` and `ORDER BY id DESC` for `List posts`.

### `UNION`

Syntax:

```sql
UNION <union-name> FROM <request1>, <request2>, ...;
UNION <union-name> FROM <request1>, <request2>, ... ALL;
```

Purpose:

- Creates a new sheet by merging rows from two or more request outputs.
- Without `ALL`, duplicate rows are removed.
- With `ALL`, duplicates are preserved.

Examples:

```sql
UNION "MergedPostsUsers" FROM "List posts", "List users";
UNION "MergedPostsUsersAll" FROM "List posts", "List users" ALL;
```

Notes:

- Sources must be request names, not lookup table names or union names.
- `UNION` uses raw request response rows. Request-level `FILTER` and `COLUMNS` do not change union input rows.
- After the union is built, you can target it with `SHAPE "<union-name>" ...`.

### `INTERSECT`

Syntax:

```sql
INTERSECT <sheet-name> FROM <request1>, <request2> [, <request3>, ...];
```

Purpose:

- Creates a new sheet containing only rows that exist in **every** specified source request.
- A row is considered identical across sources when its full JSON serialisation matches (same dedup mechanism as `UNION`).
- Adds provenance columns to the output so you can see which sources contain each row.

Provenance columns:

| Column | Content |
| ------ | ------- |
| `_source` | Always `"ALL"` — all rows passed every source |
| `_in_<request>` | `true` for every source (since only common rows survive) |

Example:

```sql
INTERSECT CommonPosts FROM "List posts", "List users";
```

If `List posts` returns `[{id:1},{id:2},{id:3}]` and `List users` returns `[{id:2},{id:3},{id:4}]`, the `CommonPosts` sheet contains:

| id | _source | _in_List posts | _in_List users |
| -- | ------- | -------------- | -------------- |
| 2  | ALL     | true           | true           |
| 3  | ALL     | true           | true           |

Notes:

- Requires at least 2 sources.
- You can target the output with `SHAPE "<sheet-name>" ...`.

### `EXCEPT`

Syntax:

```sql
EXCEPT <sheet-name> FROM <request1> [, <request2>, <request3>, ...];
```

Purpose:

- Creates a new sheet containing rows from the **first** source that are **not** present in any subsequent source.
- A row is considered identical across sources when its full JSON serialisation matches.
- Adds provenance columns to the output.

Provenance columns:

| Column | Content |
| ------ | ------- |
| `_source` | Name of the first source (e.g., `List posts`) |
| `_in_<source1>` | `true` |
| `_in_<sourceN>` | `false` for every other source |

Example:

```sql
EXCEPT PostsOnly FROM "List posts", "List users";
```

If `List posts` returns `[{id:1},{id:2},{id:3}]` and `List users` returns `[{id:2},{id:3},{id:4}]`, the `PostsOnly` sheet contains:

| id | _source | _in_List posts | _in_List users |
| -- | ------- | -------------- | -------------- |
| 1  | List posts | true         | false          |

Notes:

- Requires at least 2 sources.
- Multiple secondary sources are combined: a row is excluded if it exists in *any* subsequent source.
- You can target the output with `SHAPE "<sheet-name>" ...`.

### `COMPARE`

Syntax:

```sql
COMPARE <sheet-name> ON <field> FROM <request1>, <request2> [, <request3>, ...]
  [WHERE <predicate>] [HAVING <predicate>];
```

`WHERE` filters the source rows before the value matrix is built; `HAVING` filters the produced
matrix rows (e.g. `HAVING _count > 1` to keep only values present in more than one source).

Purpose:

- Creates a value matrix comparing a **specific field** across multiple request outputs.
- Each unique value of `<field>` gets one row with boolean columns per source and a count of how many sources contain it.
- Unlike `INTERSECT` / `EXCEPT`, this compares a single column rather than entire rows.

Output columns:

| Column | Content |
| ------ | ------- |
| `<field>` | The unique value |
| `_in_<request>` | `true` or `false` per source |
| `_count` | Number of sources containing this value |

Example:

```sql
COMPARE IdCompare ON id FROM "List posts", "List users";
```

If `List posts` returns `[{id:1},{id:2},{id:3}]` and `List users` returns `[{id:2},{id:3},{id:4}]`, the `IdCompare` sheet contains:

| id | _in_List posts | _in_List users | _count |
| -- | -------------- | -------------- | ------ |
| 1  | true           | false          | 1      |
| 2  | true           | true           | 2      |
| 3  | true           | true           | 2      |
| 4  | false          | true           | 1      |

Notes:

- Requires at least 2 sources.
- Values are sorted alphabetically for deterministic output.
- You can target the output with `SHAPE "<sheet-name>" ...`.

### `DIFF`

Syntax:

```sql
DIFF <sheet-name> FROM <request1> [, <request2>, <request3>, ...];
```

Purpose:

- Creates a sheet listing rows that are **unique to each source** — the symmetric difference.
- For each source in order, a section label row (`--- <source> unique ---`) is written, followed by the rows that exist only in that source and no other.
- Within each section, each row has `_source` set to its source name and `_in_<request>` boolean columns showing presence across all sources.

Complete example:

```sql
# ── Select collection and requests ──
COLLECTION jsonplaceholder;
REQUESTS "List posts", "List users";

# ── Column projection for response-data sheets ──
COLUMNS "List posts": id, userId, title;
COLUMNS "List users": id, name, email;

# ── DIFF: rows unique to each source ──
# Creates "PostUserDiff" sheet with section labels:
#   --- List posts unique ---
#   ...rows in List posts but NOT in List users...
#   --- List users unique ---
#   ...rows in List users but NOT in List posts...
DIFF PostUserDiff FROM "List posts", "List users";

# ── Apply shape (sort, limit) on the DIFF output ──
SHAPE "PostUserDiff" ORDER BY id ASC;
```

Given these responses:

`List posts`:
```json
[{"id":1,"userId":1,"title":"post A"},{"id":2,"userId":1,"title":"post B"}]
```

`List users`:
```json
[{"id":1,"name":"Alice"},{"id":3,"name":"Bob"}]
```

The `PostUserDiff` sheet looks like:

```
            PostUserDiff — DIFF
_source     | id | userId | title | name | _in_List posts | _in_List users
-----------------------------------------------------------------------------
--- List posts unique ---
List posts  | 2  | 1      | post B|      | true           | false
--- List users unique ---
List users  | 3  |        |       | Bob  | false          | true
```

Row `id=1` is common to both — excluded from DIFF. Row `id=2` only in posts, row `id=3` only in users — each appears under its source's section label.

Notes:

- Requires at least 2 sources.
- A row is considered unique to a source when its full JSON serialisation does not match any row in any other source.
- You can target the output with `SHAPE "<sheet-name>" ...`. When `SHAPE` reorders or limits rows, section labels adjust to only show groups that still have rows.

### `EXPAND`

Syntax:

```sql
EXPAND <request> ON <arrayField>;
EXPAND <request> ON <arrayField> AS <exceptionLabel>;
```

Purpose:

- Unnests a named array field within each response row into individual rows — one row per array element.
- Parent fields are repeated on every produced row.
- Child fields are prefixed with the array field name (e.g., `items.itemid`, `items.name`).
- Child fields that appear in only some array elements (sparse fields) are placed in the last columns, prefixed with the exception label (default: `exceptions`).

Examples:

```sql
EXPAND "My Request" ON items;
EXPAND "My Request" ON items AS extras;
```

Given a response like:

```json
[{"category":"fruits","items":[{"itemid":1,"name":"apple"},{"itemid":2,"name":"orange"}]}]
```

`EXPAND "My Request" ON items;` produces:

| category | items.itemid | items.name |
| -------- | ------------ | ---------- |
| fruits   | 1            | apple      |
| fruits   | 2            | orange     |

Notes:

- `EXPAND` targets a single request by name; the `*` wildcard is not supported.
- `EXPAND` is applied before `FILTER` and `SHAPE`, so those statements operate on the already-expanded rows.
- Each request can have at most one `EXPAND` definition; a later statement for the same request replaces the earlier one.

## 4. `WHERE` and `HAVING` Keyword Reference

`WHERE` is used in `FILTER`, inline `REQUEST ... WHERE`, and `LOOKUP_TABLE ... WHERE`.

`HAVING` is used inside `SHAPE` after grouping and aggregation.

### Boolean keywords

| Keyword | Meaning | Example |
| ------- | ------- | ------- |
| `WHERE` | Starts a predicate expression | `FILTER "List posts" WHERE userId = 1;` |
| `AND` | Both conditions must match | `status = active AND priority = high` |
| `OR` | Either condition may match | `status = active OR priority = high` |
| `NOT` | Negates the next predicate or grouped expression | `NOT archived IS TRUE` |
| `(` `)` | Controls grouping and precedence | `(a = 1 OR b = 2) AND c = 3` |
| `IF` … `THEN` … `ELSE` | Conditional branching | `IF priority = high THEN (severity > 7) ELSE (severity > 3)` |

Evaluation order:

1. Parentheses
2. `NOT`
3. `AND`
4. `OR`

### Comparison operators

| Operator | Meaning | Example |
| -------- | ------- | ------- |
| `=` | Equal | `userId = 1` |
| `!=` | Not equal | `status != archived` |
| `>` | Greater than | `amount > 1000` |
| `>=` | Greater than or equal | `score >= 90` |
| `<` | Less than | `amount < 1000` |
| `<=` | Less than or equal | `score <= 90` |

Runtime behavior:

- Numeric operators try numeric comparison first.
- If both sides are not numeric, comparison falls back to case-insensitive string comparison.

### Variable values in predicates

A comparison value can be a `$name` reference instead of a literal. At evaluation time it is
resolved against the **runtime variable map** (values from your `.env` file, the credential
store, system environment, and any filter-level `auth`/`vars`). This lets one `.filter` file
run against different environments without edits.

```sql
FILTER "List posts" WHERE userId = $targetUser;
FILTER "Orders"     WHERE region IN ('US','CA') AND tier = $minTier;
```

Notes:

- The form is `$identifier` (letters, digits, underscore). A quoted literal that merely starts
  with `$` (e.g. `"$4.99"`) is left as-is.
- If the variable is not defined, a warning is printed and the literal text is kept (the rule
  will generally not match).
- Variable resolution works in `FILTER`, inline `REQUEST ... WHERE`, `LOOKUP_TABLE ... WHERE`,
  `SHAPE ... HAVING`, and `COMPARE ... WHERE/HAVING`.

### Null and boolean keywords

| Keyword | Meaning | Example |
| ------- | ------- | ------- |
| `IS NULL` | Field is missing or null | `deletedAt IS NULL` |
| `IS NOT NULL` | Field exists and is not null | `deletedAt IS NOT NULL` |
| `IS TRUE` | Field is boolean true or text `true` | `active IS TRUE` |
| `IS FALSE` | Field is boolean false or text `false` | `active IS FALSE` |

### Membership keywords

| Keyword | Meaning | Example |
| ------- | ------- | ------- |
| `IN (...)` | Field matches any listed value | `status IN ('active','pending')` |
| `NOT IN (...)` | Field matches none of the listed values | `country NOT IN ('US','CA')` |

### String and pattern keywords

| Keyword | Meaning | Example | Notes |
| ------- | ------- | ------- | ----- |
| `CONTAINS` | Case-insensitive substring match | `title CONTAINS guide` | |
| `NOT_CONTAINS` | Inverse substring match | `title NOT_CONTAINS draft` | |
| `STARTS_WITH` | Case-insensitive prefix match | `email STARTS_WITH admin` | |
| `ENDS_WITH` | Case-insensitive suffix match | `email ENDS_WITH .com` | |
| `REGEX` | Java regex match | `code REGEX '^ERR-[0-9]+$'` | Uses regex `find()` behavior |
| `LIKE` | SQL-like wildcard pattern | `title LIKE '%guide%'` | `%` = any length, `_` = single char |
| `ILIKE` | SQL-like wildcard pattern | `title ILIKE '%guide%'` | Currently behaves the same as `LIKE` |
| `NOT LIKE` | Negated SQL-like match | `city NOT LIKE 'New%'` | |
| `NOT ILIKE` | Negated SQL-like match | `city NOT ILIKE 'New%'` | Currently behaves the same as `NOT LIKE` |

### Date keywords inside predicates

| Keyword | Meaning | Example | Notes |
| ------- | ------- | ------- | ----- |
| `BETWEEN <from> AND <to>` | Date range match | `createdAt BETWEEN 2026-01-01 AND 2026-01-31` | Intended for date fields |
| `DATE_PRESET <preset>` | Relative date window | `createdAt DATE_PRESET THIS_MONTH` | Uses `DATE_CONFIG` when present |

### IF/ELSE conditional expressions in WHERE

You can use `IF`/`ELSE` inside `WHERE` clauses to create conditional branching logic.

Syntax:

```sql
IF <condition> THEN (<expr>) [ELSE (<expr>)]
```

The condition is a standard predicate (field op value). `THEN` and `ELSE` branches are full sub-expressions (can contain `AND`, `OR`, `NOT`, or nested `IF`). Parentheses around branches are optional but recommended for readability.

| Keyword | Meaning | Example |
| ------- | ------- | ------- |
| `IF` | Starts a conditional | `IF priority = high THEN (severity > 7) ELSE (severity > 3)` |
| `THEN` | Branch when condition is true | `IF status = active THEN (score > 50)` |
| `ELSE` | Branch when condition is false (optional) | `IF type = A THEN (val > 10) ELSE (val > 5)` |

Rules:

- If `ELSE` is omitted and the condition is false, the row passes (no exclusion).
- The condition is a single predicate (field + operator + value).
- `IF`/`ELSE` can be nested inside `AND`/`OR` expressions.

Examples:

```sql
-- Apply different thresholds depending on priority level
FILTER * WHERE IF priority = high THEN (severity > 7) ELSE (severity > 3);

-- IF without ELSE: only exclude rows when condition is true AND then-branch fails
FILTER * WHERE IF status = active THEN (score > 50);

-- Nested in AND: both the IF result AND category must match
FILTER * WHERE IF type = A THEN (val > 10) ELSE (val > 5) AND category = premium;
```

## 5. Date Presets and Date Parsing

### Supported `DATE_PRESET` values

| Preset | Meaning |
| ------ | ------- |
| `TODAY` | Current day |
| `YESTERDAY` | Previous day |
| `THIS_WEEK` | Current week, Monday to Sunday |
| `LAST_WEEK` | Previous week, Monday to Sunday |
| `THIS_MONTH` | Current month |
| `LAST_MONTH` | Previous month |
| `THIS_QUARTER` | Current quarter |
| `LAST_QUARTER` | Previous quarter |
| `THIS_YEAR` | Current year |
| `LAST_YEAR` | Previous year |

Examples:

```sql
FILTER "Events" WHERE createdAt DATE_PRESET TODAY;
FILTER "Events" WHERE createdAt DATE_PRESET THIS_MONTH;
FILTER "Events" WHERE createdAt BETWEEN 2026-01-01 AND 2026-01-31;
```

Recommended date setup:

```sql
DATE_CONFIG *.createdAt FORMAT "yyyy-MM-dd'T'HH:mm:ss'Z'" TIMEZONE UTC;
FILTER "Events" WHERE createdAt DATE_PRESET THIS_MONTH;
```

Date notes:

- If `TIMEZONE` is not supplied, the runtime falls back to the system default timezone.
- If `FORMAT` is not supplied, the runtime tries common ISO-8601 formats automatically.
- When date parsing fails, the row does not match the date rule.

## 6. `SHAPE` Keyword Reference

| Keyword | Meaning | Example |
| ------- | ------- | ------- |
| `DISTINCT` | Remove duplicate rows | `SHAPE "List posts" DISTINCT;` |
| `ORDER BY` | Sort rows | `SHAPE "List posts" ORDER BY id DESC, name ASC;` |
| `ASC` | Ascending sort | `ORDER BY id ASC` |
| `DESC` | Descending sort | `ORDER BY id DESC` |
| `LIMIT` | Keep first `n` rows | `SHAPE "List posts" LIMIT 100;` |
| `OFFSET` | Skip first `n` rows | `SHAPE "List posts" OFFSET 50;` |
| `GROUP BY` | Group rows by fields | `SHAPE "List posts" GROUP BY userId;` |
| `AGG` | Define aggregate output fields | `AGG COUNT(*) AS cnt, SUM(amount) AS total` |
| `AS` | Set aggregate alias | `COUNT(*) AS cnt` |
| `HAVING` | Filter grouped rows | `HAVING cnt > 1` |

Supported aggregate functions:

- `COUNT`
- `SUM`
- `AVG`
- `MIN`
- `MAX`

Aggregate rules:

- `COUNT(*)` is allowed.
- `SUM(*)`, `AVG(*)`, `MIN(*)`, and `MAX(*)` are not allowed.
- If `AS` is omitted, an automatic alias is generated.

Example:

```sql
SHAPE "List posts"
  GROUP BY userId
  AGG COUNT(*) AS post_count, MAX(id) AS latest_post_id
  HAVING post_count > 1
  ORDER BY post_count DESC;
```

## 7. Target Matrix and Valid Combinations

### Which statements can target which outputs?

| Statement | Request name | `*` wildcard | Lookup table name | Union name | SetOp/Compare name |
| --------- | ------------ | ------------ | ----------------- | ---------- | ------------------ |
| `REQUESTS` / `REQUEST` | Yes | No | No | No | No |
| `COLUMNS` | Yes | Yes | No | No | No |
| `FILTER` | Yes | Yes | No | No | No |
| `DATE_CONFIG` | Yes | Yes | No | No | No |
| `EXPAND` | Yes | No | No | No | No |
| `LOOKUP_TABLE` | Source request only | No | Creates a table | No | No |
| `SHAPE` | Yes | Yes | Yes | Yes | Yes |
| `UNION` | Source requests only | No | No | Creates a union | No |
| `INTERSECT` | Source requests only | No | No | No | Creates a set op sheet |
| `EXCEPT` | Source requests only | No | No | No | Creates a set op sheet |
| `DIFF` | Source requests only | No | No | No | Creates a set op sheet |
| `COMPARE` | Source requests only | No | No | No | Creates a compare sheet |

### Which statements combine in practice?

| Output type | Supported combination |
| ----------- | --------------------- |
| Response-data sheet | `REQUESTS` or `REQUEST` + `FILTER` or inline `REQUEST ... WHERE` + `DATE_CONFIG` + `SHAPE` + `COLUMNS` |
| Lookup table sheet | `REQUESTS` + `LOOKUP_TABLE ... WHERE ... COLUMNS ...` + optional `DATE_CONFIG` + optional `SHAPE "<table-name>"` |
| Union sheet | `REQUESTS` + `UNION ...` + optional `SHAPE "<union-name>"` |
| Set operation sheet | `REQUESTS` + `INTERSECT` / `EXCEPT` / `DIFF` + optional `SHAPE "<setop-name>"` |
| Compare sheet | `REQUESTS` + `COMPARE` + optional `SHAPE "<compare-name>"` |
| Multi-collection file | Global statements + one selected `COLLECTION` block |

### Execution order by output type

| Output type | Effective order |
| ----------- | --------------- |
| Response-data sheet | Raw request rows -> `EXPAND` -> `FILTER` -> `SHAPE` -> `COLUMNS` |
| Lookup table sheet | Raw source request rows -> detail lookup merge -> table `WHERE` -> `SHAPE` -> table `COLUMNS` |
| Union sheet | Raw request rows -> `UNION` or `UNION ... ALL` -> `SHAPE` |
| Set operation sheet | Raw request rows -> `INTERSECT` / `EXCEPT` / `DIFF` (set logic) -> `SHAPE` |
| Compare sheet | Raw request rows -> `COMPARE` (value matrix) -> `SHAPE` |

Important:

- Request-level `FILTER` does not feed `LOOKUP_TABLE` source rows.
- Request-level `COLUMNS` does not affect `LOOKUP_TABLE`, `UNION`, `INTERSECT`, `EXCEPT`, or `COMPARE` sheets.
- `SHAPE *` can act as a global default, but an exact key-specific shape replaces it for that output.

## 8. Multi-Collection Files and Merge Rules

You can keep several collection blocks in one `.filter` file.

Example:

```sql
SHAPE * LIMIT 200;

COLLECTION posts;
REQUESTS "List posts";
FILTER "List posts" WHERE id > 10;

COLLECTION users;
REQUESTS "List users";
FILTER "List users" WHERE id > 100;
```

How selection works:

- If the file has one `COLLECTION` block, it is used automatically.
- If the file has more than one `COLLECTION` block, you must pass `--collection-name` or `--collection`.
- Statements before the first `COLLECTION` block are treated as global defaults and merged into the selected block.

Merge behavior when both global and selected block define values:

| Item | Behavior |
| ---- | -------- |
| `REQUESTS` / `REQUEST` | Combined |
| `OUTPUT_PREFIX` | Selected block wins |
| `COLUMNS` | Selected block entry for the same key wins |
| `FILTER` | Selected block entry for the same key wins |
| `DATE_CONFIG` | Field maps merge, selected block field wins |
| `LOOKUP_TABLE` | Global and selected entries are both kept |
| `SHAPE` | Selected block entry for the same key wins |
| `UNION` | Global and selected entries are both kept |
| `INTERSECT` / `EXCEPT` / `DIFF` | Global and selected entries are both kept |
| `COMPARE` | Global and selected entries are both kept |
| `EXPAND` | Selected block entry for the same key wins |

## 9. Complete Example

```sql
COLLECTION my-collection;
REQUESTS "List Items", "Get Item Details", "List Archived Items";
OUTPUT_PREFIX daily-report;

FILTER "List Items"
  WHERE (status IN ('active','pending') OR title LIKE '%guide%')
    AND NOT archived IS TRUE;

COLUMNS "List Items": id, title, status, createdAt;

DATE_CONFIG *.createdAt FORMAT "yyyy-MM-dd'T'HH:mm:ss'Z'" TIMEZONE UTC;
SHAPE "List Items" ORDER BY createdAt DESC LIMIT 100;

LOOKUP_TABLE "Items With Details"
  FROM "List Items"
  LOOKUP "Get Item Details"
  BY id
  WHERE detail.price >= 1000 AND detail.availability = in_stock
  COLUMNS id, title, status, detail.price, detail.availability;

SHAPE "Items With Details" ORDER BY detail.price DESC LIMIT 50;

UNION "All Items" FROM "List Items", "List Archived Items" ALL;
SHAPE "All Items" ORDER BY id ASC LIMIT 200;

# INTERSECT: rows common to both List Items and Archived Items
INTERSECT ActiveAndArchived FROM "List Items", "List Archived Items";

# EXCEPT: rows in List Items that are NOT in Archived Items
EXCEPT ActiveOnly FROM "List Items", "List Archived Items";

# DIFF: rows unique to each source (section labels group them)
DIFF Mismatches FROM "List Items", "List Archived Items";

# COMPARE: value matrix comparing the "status" field across List Items and Archived Items
COMPARE StatusCompare ON status FROM "List Items", "List Archived Items";
```

## 10. Current Parser Scope

The current `.filter` parser supports:

- request selection
- row filtering
- date parsing configuration
- response-data column selection
- array field expansion (`EXPAND`)
- lookup tables
- shaping
- unions
- set operations (`INTERSECT`, `EXCEPT`, `DIFF`)
- column-wise comparison (`COMPARE`)
- multi-collection blocks
- customizable Summary sheet and Index navigation sheet

The current `.filter` syntax does not expose a statement for multi-source join tables. The runtime model supports them internally, but they are not available as `.filter` keywords yet.

New in this release:

### Recently added keywords

- **Variables across queries** — capture any output into a summary variable
  (`$x = UNION/INTERSECT/EXCEPT/DIFF/COMPARE ...`), derive one variable from another
  (`$b = FILTER $a WHERE ...`), and reference a runtime `$variable` inside a `WHERE` value
  (`WHERE userId = $targetUser`).
- **Compound summary `IF` conditions** — `IF $a > 0 AND $b > 0 THEN ... ELSE ...` with `AND`/`OR`/parentheses.
- **Redesigned Summary visuals** — automatic spacing, borderless rows, right-aligned numbers,
  zebra-striped tables, and auto-sized columns (no new syntax).

- **`LV`** — like `KV` but uses a plain (non-bold) label style.
- **`QT`** / **`QUICK_TABLE`** — inline table with default header row ("Label", "Value"). Override headers with `HEADERS` or `COLUMNS`. 3+ headers enable multi-column mode with comma-separated ROW values.
- **`LABEL_TABLE`** — inline label-value table **without** a header row by default (clean label/value layout). Add `HEADERS` or `COLUMNS` to include one.
- **`TEXT` with `$var`** — auto-detected as label+value pair with plain label style. Variable names are humanized to Title Case (e.g., `$POSTS` → "Posts").
- **`TEXT` without `$var`** — merged across columns A–B instead of confined to column A only.
- **`STATUS`** — per-request status table (like `METRICS` but per-request). Shows request name, method, status code, success flag, and duration. Accepts optional `COLOR` clause.
- **`IF/ELSE` in WHERE** — conditional branching inside filter predicates: `IF field = value THEN (expr) ELSE (expr)`.
- **`IF/ELSE` in Summary text** — conditional rendering inside `TEXT`, `KV`, `LV`, and `QT` column values: `IF $var > 0 THEN "found" ELSE "none"`.
- **Hex colors** — `COLOR` clauses accept hex RGB strings (e.g., `COLOR "#FF5500"` or `COLOR "336699"`) in addition to named `IndexedColors` values.
- **`INTERSECT`** — set intersection on entire rows across multiple request outputs. Creates a new sheet with only rows present in all sources. Adds `_source` and `_in_<request>` provenance columns.
- **`EXCEPT`** — set difference on entire rows. Creates a new sheet with rows from the first source that are not in any subsequent source. Adds `_source` and `_in_<request>` provenance columns.
- **`DIFF`** — symmetric difference (mutual exclusion). Creates a new sheet with section labels for each source followed by rows unique to that source. Adds `_source` and `_in_<request>` provenance columns.
- **`COMPARE`** — column-wise value matrix across multiple request outputs. Creates a new sheet showing each unique value of a field with boolean presence columns per source and a `_count` column.

## 11. Common Mistakes

| Problem | Cause | Fix |
| ------- | ----- | --- |
| `request not found` validation error | Request name does not match the collection exactly | Use the exact Postman request name |
| Date filter returns no rows | Missing or wrong `DATE_CONFIG` | Add correct `FORMAT` and `TIMEZONE` |
| Wildcard filter seems ignored | A request-specific filter exists | Exact request key overrides `*` |
| `COLUMNS` does not change a lookup table | `COLUMNS` only affects response-data sheets | Use the `COLUMNS` clause inside `LOOKUP_TABLE` |
| `FILTER` does not change a union | `UNION` reads raw request rows | Filter the request output separately only for its own response sheet, or add a union `SHAPE` if shaping is enough |
| `EXPAND` wildcard not working | `EXPAND` does not support `*` | Use the exact request name |
| Request-specific `DATE_CONFIG` fails with spaces in request name | Parser limitation on `<request>.<field>` tokenization | Use `DATE_CONFIG *.<field>` |
| `INTERSECT` / `EXCEPT` / `DIFF` produces 0 rows | Row signatures must match exactly (full JSON serialisation) | Ensure the responses share identical field structures |
| `COMPARE` field not found in output | The field name must exist in at least one response row | Check the JSON field paths in your request responses |
| `FILTER` does not change `INTERSECT` / `EXCEPT` input rows | Set operations read raw request rows | Apply `SHAPE` after the set operation instead |

## 12. Custom Summary Sheet and Index

Place summary statements at the end of your `.filter` file (or in a global block before `COLLECTION`).

### Workbook layout

| Position | Sheet | Purpose |
| -------- | ----- | ------- |
| 1st | `Summary` | Custom dashboard (or default execution metrics when no summary block is defined) |
| 2nd | `Index` | Hyperlinks to every other sheet |
| 3rd+ | Data sheets | `Results`, folder sheets, response data, lookup tables, unions |

### Workbook sheet inventory

For a run with many requests, the workbook contains the following tabs, in this order. Each
appears only when its condition is met:

| Order | Sheet | When it appears |
| ----- | ----- | --------------- |
| 1 | **Summary** | Always (custom dashboard if a summary block exists, else default execution metrics) |
| 2 | **Index** | Always — hyperlinks to every other sheet |
| 3 | **Results** | Always — one row per request (HTTP status, duration, errors, assertions) |
| 4 | **Folder sheets** | One per Postman folder that contains requests (HTTP execution summary per folder) |
| 5 | **Response-data sheets** | One per request, **skipped if the request yields no rows** after `EXPAND`/`FILTER`/`SHAPE`/column selection |
| 6 | **Lookup / custom-table sheets** | One per `LOOKUP_TABLE` that produces rows |
| 7 | **Union sheets** | One per `UNION` that produces rows |
| 8 | **Set-op sheets** | One per `INTERSECT` / `EXCEPT` / `DIFF` that produces rows |
| 9 | **Compare sheets** | One per `COMPARE` that produces values |

Notes:

- A `UNION`/`INTERSECT`/`EXCEPT`/`DIFF`/`COMPARE` **captured into a summary `$variable`** (e.g.
  `$x = COMPARE ON id FROM ...`) renders only inside the Summary — it does **not** create its own sheet.
- If the data exceeds Excel's per-sheet row limit, the report is split across multiple files; only
  the first file carries Summary/Index/Results/derived sheets, and later files carry the overflow
  response-data sheets.
- Response-data, lookup, union, set-op, and compare sheet names are de-duplicated (and truncated to
  Excel's 31-character limit); folder sheets are de-duplicated against the reserved names too.

### Summary layout

- **Column A** = labels (grey background, bold for `KV`; plain grey for `LV` and auto-detected `TEXT`).
- **Column B** = values (plain text; booleans auto-colored green/red with bold white text for high visibility).
- **Tables** span both columns (and beyond for multi-column tables) with a compact header row (not the large workbook title style). Columns beyond A and B are auto-sized.
- **`TEXT` without `$var`** merges across columns A–B (no label/value split).
- **`TEXT` with `$var`** auto-detects as a label+value pair. Variable names are humanized with Title Case (e.g., `$POSTS` → label "Posts").
- No **Metric / Value** header row on `METRICS`, `KV`, or `LV` blocks.

The Summary is styled for readability out of the box: blank-row spacing separates section blocks,
label/value rows are borderless, numeric values are right-aligned, data tables use alternating
("zebra") row shading, banner rows are slightly taller, and columns are auto-sized within a
readable range. No extra syntax is required.

### Summary statements

| Statement | Example | Purpose |
| --------- | ------- | ------- |
| `TITLE` | `TITLE "Daily Report" COLOR DARK_BLUE;` | Banner across columns A–B |
| `DESCRIPTION` | `DESCRIPTION "Notes for QA";` | Subtitle banner |
| `KV` | `KV "Active" $FLAG;` | Label/value row, **bold** grey label style |
| `LV` | `LV "Active" $FLAG;` | Label/value row, **plain** grey label style |
| `TEXT` | `TEXT "Welcome";` | Merged text spanning columns A–B |
| `TEXT` + `$var` | `TEXT "Row count" + $POSTS;` | Auto-detected as KV (label + value) with plain label style |
| `TEXT` `$POSTS` | `TEXT $POSTS;` | KV with Title Case humanized variable name as label (e.g., "Posts") |
| `QT` / `QUICK_TABLE` | `QT "Status" HEADERS "M","V" ROW "Total" $TOTAL ROW "Pass" $PASS;` | Inline label-value table with header row (default headers: "Label", "Value") |
| `LABEL_TABLE` | `LABEL_TABLE "Status" ROW "Total" $TOTAL ROW "Pass" $PASS;` | Inline label-value table **without** header row by default; add `HEADERS` to include one |
| `$var = FILTER ...` | `$POSTS = FILTER "List posts" WHERE id > 10;` | Dataset from a request (summary-only) |
| `$var = TABLE "..."` | `$DETAILS = TABLE "Items With Details";` | Dataset from a `LOOKUP_TABLE` / custom table |
| `$var = FILTER $other ...` | `$TOP = FILTER $POSTS WHERE id > 50;` | Dataset **derived** from another `$variable` |
| `$var = UNION ...` | `$ALL = UNION FROM "List posts", "List users" ALL;` | Captures a UNION result (no separate sheet) |
| `$var = INTERSECT/EXCEPT/DIFF ...` | `$COMMON = INTERSECT FROM "List posts", "List users";` | Captures a set-operation result |
| `$var = COMPARE ...` | `$M = COMPARE ON id FROM "List posts", "List users";` | Captures a COMPARE value matrix |
| `TABLE $var` | `TABLE $POSTS TITLE "Posts" COLUMNS id AS "ID";` | Table with optional title and column rename |
| `$var;` | `$POSTS;` | Shorthand for `TABLE $var;` |
| `METRICS` | `METRICS;` | Execution stats as label/value rows |
| `STATUS` | `STATUS;` or `STATUS COLOR "#228B22";` | Per-request status table (name, method, status code, success, duration) |

### Supported `COLOR` values

`TITLE`, `DESCRIPTION`, and `STATUS` accept an optional trailing `COLOR <value>`. Values can be:

1. **Named colors** — case-insensitive, spaces/hyphens allowed (e.g., `DARK_BLUE`, `dark blue`, `dark-blue`)
2. **Hex RGB colors** — with or without `#` prefix (e.g., `"#FF5500"`, `"FF5500"`, `"#228B22"`)

If the name is missing or invalid, defaults are **`DARK_BLUE`** for `TITLE`, **`GREY_50_PERCENT`** for `DESCRIPTION`, and **`GREY_40_PERCENT`** for `STATUS`.

Colors map to Apache POI `IndexedColors` (palette index colors used for title/description fills):

| | | | |
| --- | --- | --- | --- |
| `BLACK` | `BLACK1` | `WHITE` | `WHITE1` |
| `RED` | `RED1` | `DARK_RED` | `ROSE` |
| `GREEN` | `BRIGHT_GREEN` | `BRIGHT_GREEN1` | `DARK_GREEN` |
| `SEA_GREEN` | `LIGHT_GREEN` | `OLIVE_GREEN` | `LIME` |
| `BLUE` | `BLUE1` | `DARK_BLUE` | `ROYAL_BLUE` |
| `CORNFLOWER_BLUE` | `LIGHT_CORNFLOWER_BLUE` | `SKY_BLUE` | `PALE_BLUE` |
| `LIGHT_BLUE` | `INDIGO` | | |
| `YELLOW` | `YELLOW1` | `DARK_YELLOW` | `LIGHT_YELLOW` |
| `GOLD` | `LEMON_CHIFFON` | `LIGHT_ORANGE` | `ORANGE` |
| `TEAL` | `TURQUOISE` | `TURQUOISE1` | `DARK_TEAL` |
| `LIGHT_TURQUOISE` | `LIGHT_TURQUOISE1` | `AQUA` | |
| `VIOLET` | `PLUM` | `ORCHID` | `LAVENDER` |
| `PINK` | `PINK1` | `CORAL` | `MAROON` |
| `BROWN` | `TAN` | | |
| `GREY_25_PERCENT` | `GREY_40_PERCENT` | `GREY_50_PERCENT` | `GREY_80_PERCENT` |
| `BLUE_GREY` | `AUTOMATIC` | | |

Built-in sheet styles elsewhere in the workbook also use: `TEAL`, `DARK_TEAL`, `DARK_GREEN`, `SEA_GREEN`, `BROWN`, `GOLD`, `VIOLET`, `PLUM`, `DARK_RED`, `RED`, `INDIGO`, and `ROSE` — any name in the table above is valid in summary `COLOR` clauses.

### Variable values in `KV` / `LV` / `TEXT` / `QT`

| Situation | Value shown in column B |
| --------- | ------------------------ |
| `$var` has **one row, one column** | That cell value |
| `$var` has multiple rows | Row count |
| Filter `vars` map | Configured string |

When `$var` is used alone (e.g., `TEXT $POSTS;`), the label is auto-generated from the variable name in Title Case (e.g., `$POSTS` → "Posts", `$USER_ID` → "User Id").

**Boolean coloring:** values `true` / `false` (and `yes` / `no`) use bold white text on bright green fill (true) or red fill (false) for high visibility.

### `QT` / `QUICK_TABLE` / `LABEL_TABLE` — inline tables

Creates a compact table directly in the Summary sheet without requiring a separate `$var` query definition.

**Multi-column mode** (3+ columns):

```sql
QT "Scoreboard" HEADERS Name, Score, Grade
  ROW "Alice", $COUNT + "pts", "A"
  ROW "Bob",   $COUNT + "pts", "B";

QT "Results" COLUMNS Metric, Value, Status
  ROW "Throughput", $TOTAL, IF $TOTAL > 100 THEN "High" ELSE "Low";
```

**Classic 2-column mode** (backward compatible):

```sql
QT "Status Overview" HEADERS "Metric", "Count"
  ROW "Total requests" $TOTAL
  ROW "Passed" $PASSED;
```

Full syntax:

```sql
QT ["title"] [HEADERS|COLUMNS <h1>, <h2>, ...] ROW <col1>, <col2>, ... [ROW ...];
QUICK_TABLE ["title"] [HEADERS|COLUMNS <h1>, <h2>, ...] ROW <col1>, <col2>, ... [ROW ...];
LABEL_TABLE ["title"] [HEADERS|COLUMNS <h1>, <h2>, ...] ROW <label> <valueExpr> [ROW ...];
```

- **title** — optional section banner above the table
- **HEADERS** or **COLUMNS** — optional custom column headers (synonyms)
  - `QT` / `QUICK_TABLE`: default headers are `"Label"`, `"Value"` (always shows header row)
  - `LABEL_TABLE`: **no header row by default** — use `HEADERS` or `COLUMNS` to add one
  - 3+ headers enable **multi-column mode** where each ROW provides comma-separated values per column
- **ROW** — one row of data
  - **2-column mode**: `ROW <label> <valueExpr>` (label + value expression)
  - **3+ column mode**: `ROW <col1>, <col2>, <col3>, ...` (comma-separated expressions, one per header)
- Each column value is a text expression (literals and `$variable` parts joined with `+`)
- Column values also support `IF/ELSE` conditionals (see below)

**`QT` / `QUICK_TABLE` example** (includes header row):

```sql
QT "Status Overview" HEADERS "Metric", "Count"
  ROW "Total requests" $TOTAL
  ROW "Passed" $PASSED
  ROW "Failed" $FAILED;
```

Renders as:

| **Status Overview** (section banner) |  |
| Metric | Count |
| Total requests | 150 |
| Passed | 142 |
| Failed | 8 |

**`LABEL_TABLE` example** (no header row by default):

```sql
LABEL_TABLE "Status Overview"
  ROW "Total requests" $TOTAL
  ROW "Passed" $PASSED;
```

Renders as:

| **Status Overview** (section banner) |  |
| Total requests | 150 |
| Passed | 142 |

**`LABEL_TABLE` with explicit headers**:

```sql
LABEL_TABLE "Status Overview" HEADERS "Metric", "Count"
  ROW "Total requests" $TOTAL;
```

Boolean values in the value column are automatically color-coded (bold white on bright green for `true`, bold white on red for `false`).

### `STATUS` — per-request status table

Like `METRICS` but shows individual request status instead of aggregate stats.

Syntax:

```sql
STATUS;
STATUS COLOR <color>;
```

Produces a table with these columns:

| Request | Method | Status Code | Success | Duration (ms) |
| ------- | ------ | ----------- | ------- | ------------- |
| List posts | GET | 200 | true | 50 |
| Create user | POST | 500 | false | 120 |

- The section title is always **"Request Status"**.
- `Success` and `Status Code` columns are color-coded (green for pass, red for fail).
- `COLOR` accepts both named colors and hex RGB (e.g., `COLOR "#228B22"` or `COLOR DARK_GREEN`).
- Without `COLOR`, defaults to `GREY_40_PERCENT`.

Examples:

```sql
STATUS;
STATUS COLOR "#228B22";
STATUS COLOR DARK_GREEN;
```

### IF/ELSE conditional text on the Summary page

Summary text expressions (`TEXT`, `KV`, `LV`, and `QT` column values) support conditional rendering with `IF/ELSE`.

Syntax:

```sql
IF <condition> THEN <textExpr> [ELSE <textExpr>]
```

The **condition** is one or more `$variable <op> <value>` terms combined with `AND`/`OR`
and optional parentheses (the same shape as a `WHERE` predicate, scoped to summary variables):

```sql
KV "Coverage" IF $POSTS > 0 AND $TODOS > 0 THEN "both present" ELSE "incomplete";
KV "Tier"     IF ($A > 0 OR $B > 0) AND $MODE = active THEN "live" ELSE "idle";
```

- **`$variable`** — a summary query variable (resolves to row count for multi-row results, or scalar for 1-row-1-column results)
- **`op`** — comparison operator: `=`, `==`, `!=`, `<>`, `>`, `>=`, `<`, `<=`
- **`value`** — comparison target (numeric or string)
- **`textExpr`** — a text expression (literals, `$vars`, `+` concatenation, or nested `IF`)
- If `ELSE` is omitted and the condition is false, the result is an empty string

Comparison behavior:

- Numeric comparison is tried first (e.g., `$count > 0`)
- Falls back to case-insensitive string comparison (e.g., `$mode = active`)
- Empty or missing variables default to `"0"` for numeric comparisons

Examples:

```sql
# Conditional text
TEXT IF $POSTS > 0 THEN $POSTS + " posts found" ELSE "No posts found";

# Conditional KV
KV "Status" IF $POSTS > 0 THEN "Has posts" ELSE "Empty";

# Conditional inside a QuickTable column
QT "Summary" HEADERS Name, Value, Level
  ROW "Count", $POSTS, IF $POSTS > 100 THEN "High" ELSE "Low";

# Nested IF/ELSE
TEXT IF $POSTS > 100 THEN
       IF $POSTS > 500 THEN "Very High" ELSE "High"
     ELSE "Low";

# String comparison
KV "Mode" IF $MODE = active THEN "Running" ELSE "Stopped";
```

### Complete summary example

```sql
COLLECTION jsonplaceholder;
REQUESTS "List posts", "Get post";

LOOKUP_TABLE "Items With Details"
  FROM "List posts"
  LOOKUP "Get post"
  BY id
  COLUMNS id AS "Post ID", title AS "Title";

$POSTS = FILTER "List posts" WHERE id > 10;
$DETAILS = TABLE "Items With Details";

# ── Banner with hex color ──────────────────────────────────────────────────
TITLE "Post Report" COLOR "#1A5276";
DESCRIPTION "Automated daily report" COLOR "#2E86C1";

# ── Conditional text using IF/ELSE ────────────────────────────────────────
KV "Result" IF $POSTS > 0 THEN $POSTS + " posts found" ELSE "No posts found";
LV "Health" IF $POSTS >= 50 THEN "Healthy" ELSE IF $POSTS > 0 THEN "Low volume" ELSE "No data";

# ── Multi-column QuickTable ───────────────────────────────────────────────
QT "Scorecard" HEADERS Metric, Value, Level
  ROW "Post count", $POSTS, IF $POSTS > 100 THEN "High" ELSE "Low"
  ROW "Details",   $DETAILS, IF $DETAILS > 0 THEN "Available" ELSE "None";

# ── Classic 2-column QuickTable ──────────────────────────────────────────
QT "Status Overview" HEADERS "Key", "Value"
  ROW "Matching posts" $POSTS
  ROW "User filter" "1";

# ── Label table (no header row) ──────────────────────────────────────────
LABEL_TABLE "Counts"
  ROW "Matching posts" $POSTS
  ROW "User filter" "1";

# ── Data tables ──────────────────────────────────────────────────────────
TABLE $POSTS TITLE "Filtered posts" COLUMNS id AS "Post ID", title AS "Title";
TABLE $DETAILS TITLE "Lookup details";

# ── Per-request status block ──────────────────────────────────────────────
STATUS COLOR "#228B22";

# ── Aggregate execution metrics ──────────────────────────────────────────
METRICS;
```

See `filters/summary-example.filter` in this repo.

## 13. Example Files in This Repo

- `filters/showcase.filter` — variables across queries (`$x = UNION/COMPARE`, `$b = FILTER $a`, `$var` in `WHERE`), compound summary `IF`, and the redesigned Summary
- `filters/tutorial.filter` — full walkthrough of all core features
- `filters/frequent-use.filter` — commonly used patterns
- `filters/multi-collection.filter` — multiple `COLLECTION` blocks
- `filters/posts-with-details.filter` — `LOOKUP_TABLE` demo
- `filters/summary-example.filter` — customizable Summary sheet with all item types
- `filters/summary-features-demo.filter` — comprehensive demo of every Summary feature
- `filters/ifelse-conditional.filter` — IF/ELSE in WHERE filters and Summary text
- `filters/status-and-multicol.filter` — STATUS keyword, multi-column QT, hex colors
- `filters/set-operations.filter` — combined INTERSECT, EXCEPT, DIFF, COMPARE demo
- `filters/intersect-example.filter` — INTERSECT standalone example
- `filters/except-example.filter` — EXCEPT standalone example
- `filters/diff-example.filter` — DIFF standalone example (section labels, symmetric difference)
- `filters/compare-example.filter` — COMPARE standalone example (value matrix across sources)
