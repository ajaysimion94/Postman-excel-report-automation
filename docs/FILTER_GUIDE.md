# Filter File Guide

This is the simple version. Start with the 2-minute template, then copy only the query patterns you need.

## 2-Minute Start

```sql
COLLECTION jsonplaceholder;
REQUESTS "List posts", "List users";
FILTER "List posts" WHERE userId = 1;
COLUMNS "List posts": id, title, userId;
```

That is enough for most daily runs.

## Most Used Patterns

| Need | Use |
| ---- | --- |
| Basic filter | `FILTER "List posts" WHERE userId = 1;` |
| Nested filter | `FILTER "List posts" WHERE (status = active OR priority = high) AND NOT archived IS TRUE;` |
| Today | `FILTER "List posts" WHERE createdAt DATE_PRESET TODAY;` |
| Yesterday | `FILTER "List posts" WHERE createdAt DATE_PRESET YESTERDAY;` |
| Date range | `FILTER "List posts" WHERE createdAt BETWEEN 2026-01-01 AND 2026-01-31;` |
| Sort + limit | `SHAPE "List posts" ORDER BY createdAt DESC LIMIT 100;` |
| Distinct | `SHAPE "List posts" DISTINCT;` |
| Union | `UNION "Merged" FROM "List posts", "List archived posts";` |
| List -> detail lookup | `LOOKUP_TABLE "Items With Details" FROM "List Items" LOOKUP "Get Item Details" BY id WHERE detail.price >= 1000 COLUMNS id, detail.price;` |

## Nested Filters (Simple Rules)

Use parentheses when mixing `AND` and `OR`.

```sql
FILTER "Orders" WHERE (region = APAC OR region = EMEA) AND amount > 1000;
FILTER "Users" WHERE NOT (deleted IS TRUE OR status = inactive);
```

Order of evaluation:

1. Parentheses
2. `NOT`
3. `AND`
4. `OR`

## List API Then Detail API (Per-Item Lookup)

Use this when detail API returns only one item (for example `/items/{{id}}`) and you need a full detailed list.

```sql
COLLECTION my-collection;
REQUESTS "List Items", "Get Item Details";

FILTER "List Items" WHERE status = active;

LOOKUP_TABLE "Items With Details"
  FROM "List Items"
  LOOKUP "Get Item Details"
  BY id
  WHERE detail.availability = in_stock AND detail.price >= 1000
  COLUMNS id, itemName, detail.price, detail.availability;
```

How it works:

- Runs `List Items` first.
- Reads `id` from each filtered list row.
- Calls `Get Item Details` once per `id`.
- Merges source + detail fields into one table (`detail.*` prefix is used for conflicting field names).
- Applies `WHERE` to merged rows before writing the table sheet.

## Timestamp Keywords

Use `DATE_PRESET` for relative dates.

| Keyword | Meaning |
| ------- | ------- |
| `TODAY` | Current day |
| `YESTERDAY` | Previous day |
| `THIS_WEEK` | Current week |
| `LAST_WEEK` | Previous week |
| `THIS_MONTH` | Current month |
| `LAST_MONTH` | Previous month |
| `THIS_QUARTER` | Current quarter |
| `LAST_QUARTER` | Previous quarter |
| `THIS_YEAR` | Current year |
| `LAST_YEAR` | Previous year |

Example:

```sql
FILTER "Events" WHERE createdAt DATE_PRESET THIS_MONTH;
```

## If Date Filter Returns No Rows

Use explicit date parsing config:

```sql
DATE_CONFIG "Events".createdAt FORMAT yyyy-MM-dd'T'HH:mm:ss'Z' TIMEZONE UTC;
FILTER "Events" WHERE createdAt DATE_PRESET TODAY;
```

Quick checks:

- Confirm field name is exact (`createdAt` vs `created_at`).
- Confirm timezone (use explicit `TIMEZONE`, do not rely on machine default).
- Confirm date format matches actual API value.

## When You Need More

- Group/Aggregate/Having:
  `SHAPE "List posts" GROUP BY userId AGG COUNT(*) AS cnt HAVING cnt > 1;`
- Multi-collection file: use multiple `COLLECTION` blocks and run with `--collection-name`.
- More examples: `filters/tutorial.filter`, `filters/frequent-use.filter`, `filters/multi-collection.filter`.
