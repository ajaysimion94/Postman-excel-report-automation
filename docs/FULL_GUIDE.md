# Postman Excel Report Automation — Full Guide

This is the practical reference for the project. It explains the two ways to use
the application, the workspace layout, the report language, the summary page, and
the instructions used most often.

## Quick reference

| Task | Command or action |
| --- | --- |
| Build and test | `./mvnw clean verify` |
| Build a runnable JAR | `./mvnw package` |
| Start the web workspace | `java -jar target/postman-excel-runner-1.0.0.jar --web` |
| Start on another port | Add `--port 8087` |
| List collections | `java -jar target/postman-excel-runner-1.0.0.jar --env .env --list` |
| List filters | `java -jar target/postman-excel-runner-1.0.0.jar --env .env --list-filters` |
| Run one filter | `java -jar target/postman-excel-runner-1.0.0.jar --env .env --filter tutorial` |
| Run a specific collection | Add `--collection collections/my-api.json` |
| Include response previews | Add `--include-body` |
| Choose an output file | Add `--output reports/daily.xlsx` |
| Validate in the web UI | Open a `.filter`, select a collection, click **Validate** |
| Run in the web UI | Click **Run report**, then review **Summary** and **Workbook** |
| Save an editor buffer | `Command/Ctrl + S` |
| Run from the editor | `Command/Ctrl + Enter` |
| Validate from the editor | `Command/Ctrl + Shift + Enter` |
| Find files | `Command/Ctrl + K` |

The web workspace is local-only. Start it from the project directory, then open the
URL printed by the application:

```bash
./mvnw package
java -jar target/postman-excel-runner-1.0.0.jar --web --port 8087
```

Open `http://127.0.0.1:8087` in a browser. Use Ctrl+C to stop the server.

## What the application does

The application reads a Postman collection, executes selected requests, parses JSON
responses, applies row filters and shaping rules, and writes a styled `.xlsx` file.
The workbook can contain:

- A Summary sheet with metrics, request outcomes, custom text, and data tables.
- A Results sheet with one row per executed request.
- Folder and response-data sheets.
- Optional lookup, union, set-operation, compare, and expanded-array sheets.

The web UI adds a file explorer, SQL Developer-style report editor, request outline,
validation, background execution, run history, workbook preview, and export.

## First-time setup

### Requirements

- Java 17 or newer.
- A Postman Collection v2 or v2.1 JSON file.
- Network access to the APIs called by the collection when a report runs.

### Workspace layout

For web mode, keep files in this structure:

```text
my-workspace/
├── collections/       # Postman .json files
├── filters/           # Report definitions
├── reports/           # Generated .xlsx files
├── .env               # Optional runtime settings and credentials
├── .web-state/        # Run history (created automatically)
└── .web-trash/        # Recoverable deleted files (created automatically)
```

The web explorer creates `collections`, `filters`, and `reports` if they do not
exist. It does not move files from `COLLECTIONS_DIR` or `FILTERS_DIR` automatically;
use `--workspace` to point at the workspace you want to manage or import files into it.

### Environment file

Create `.env` in the workspace or pass an explicit path with `--env`:

```env
COLLECTIONS_DIR=/absolute/path/to/collections
FILTERS_DIR=/absolute/path/to/filters
OUTPUT_FILE=reports/{collection}_{timestamp}.xlsx

REQUEST_TIMEOUT_SECONDS=30
MAX_RESPONSE_MB=10
```

`COLLECTIONS_DIR`, `FILTERS_DIR`, and `OUTPUT_FILE` are used primarily by CLI mode.
Web mode uses the selected workspace's folders for file management and reports.

## Web workflow

1. Start Report Studio.
2. Open a `.filter` file from **Filters**. Use `filters/reqres.filter` as a
   known-good example.
3. Select a collection in the toolbar. Opening a filter with `COLLECTION
   reqres;` automatically selects a matching collection filename.
4. Edit the report definition. The outline on the right inserts exact request names.
5. Click **Validate**. This parses the current editor buffer and performs checks
   without sending API requests or saving the file.
6. Click **Run report**. The run uses the current filter and an open, unsaved
   collection buffer if one is selected.
7. Review **Summary** for the plain-language outcome and **Requests** for details.
8. Open **Workbook** to inspect worksheet tabs and formatting, then click
   **Export Excel**.

Use **Hide results** in the editor toolbar when you want the report definition to
fill the workspace. The control changes to **Show results** and restores the
Summary, Workbook, Requests, Run history, and Output tabs. The preference is
remembered in this browser.

### Quick Run from the explorer

Hover over a saved filter in **Filters** and select its play button. Quick Run reads
the saved `.filter` directly, so it does not open an editor tab or use unsaved editor
changes. It resolves the filter's `COLLECTION` declaration automatically, then opens
the results-only view on the right. Select **Show editor** from the result toolbar
when you want to return to the report definition.

If a filter does not include `COLLECTION`, Quick Run uses the selected collection when
available. If there is exactly one collection in the workspace, it uses that file.
Otherwise, choose a collection in the editor or add `COLLECTION collection-name;`.

### File management

Use the explorer buttons to create folders, create files, import `.json` and
`.filter` files, refresh the workspace, or manage the selected item.

- Save detects an on-disk revision change and refuses to overwrite newer edits.
- Save As creates a new copy of the current editor buffer.
- Rename or move keeps files inside their top-level workspace area.
- Move to trash is recoverable under `.web-trash/<id>/`.
- Hidden files, symbolic links, path traversal, and top-level folder deletion are blocked.

Press Escape and then Tab to leave the editor with the keyboard after Tab has been
used for indentation.

## CLI workflow

Build once:

```bash
./mvnw package
```

List available files:

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env --list
java -jar target/postman-excel-runner-1.0.0.jar --env .env --list-filters
```

Run a named filter:

```bash
java -jar target/postman-excel-runner-1.0.0.jar \
  --env .env \
  --filter filters/reqres.filter
```

Run an explicit collection and filter:

```bash
java -jar target/postman-excel-runner-1.0.0.jar \
  --collection collections/reqres.json \
  --filter filters/reqres.filter \
  --output reports/summary.xlsx \
  --include-body
```

When no `--filter` is provided, the CLI auto-selects the only `.filter` in
`FILTERS_DIR`. If multiple filters exist, specify one explicitly. A filter's
`COLLECTION` declaration can supply the collection when `--collection` is omitted.

## Filter language: the common path

Every statement ends with `;`. Keywords are case-insensitive. Quote values that
contain spaces. Comments begin with `#` or `--`.

```sql
COLLECTION reqres;
REQUESTS "List users page 1", "Get single user";

FILTER "List users page 1" WHERE id > 0;
COLUMNS "List users page 1": id AS "User ID", first_name AS "First name", email AS "Email";
SHAPE "List users page 1" ORDER BY id ASC LIMIT 20;
```

### Frequently used filter instructions

Select one request:

```sql
REQUEST "List users page 1";
```

Select several requests:

```sql
REQUESTS "List users page 1", "Get single user";
```

Filter by a value:

```sql
FILTER "List users page 1" WHERE id > 0;
FILTER "Get single user" WHERE data.id = 2;
```

Combine conditions:

```sql
FILTER "List users page 1"
  WHERE (id = 1 OR id = 2) AND email IS NOT NULL;
```

Use text operators:

```sql
FILTER "List users page 1" WHERE first_name ILIKE "%e%";
FILTER "List users page 1" WHERE email ENDS_WITH ".in";
FILTER "List users page 1" WHERE id IN (1, 2, 3);
```

Select and rename output columns:

```sql
COLUMNS "List users page 1":
  id AS "User ID", first_name AS "First name", last_name AS "Last name", email AS "Email";
```

Sort and limit results:

```sql
SHAPE "List users page 1" ORDER BY id ASC LIMIT 5 OFFSET 0;
```

Remove duplicates:

```sql
SHAPE "List users page 1" DISTINCT;
```

Use date windows:

```sql
DATE_CONFIG "List events".createdAt FORMAT "yyyy-MM-dd'T'HH:mm:ssX" TIMEZONE "UTC";
FILTER "List events" WHERE createdAt DATE_PRESET THIS_MONTH;
```

See [FILTER_GUIDE.md](FILTER_GUIDE.md) for the complete operator and data-shaping
reference, including joins, unions, set operations, compare tables, and array expansion.

## Summary page instructions

The recommended syntax keeps presentation inside one `SUMMARY` block:

```sql
$USERS = FILTER "List users page 1" WHERE id > 0;

SUMMARY {
  TITLE "User report" COLOR "#245C50";
  DESCRIPTION "Users returned by the public sample API.";
  METRIC "Users" = $USERS;
  FIELD "Filter" = "id > 0";
  PARAGRAPH IF $USERS = 1 THEN "One user was returned."
    ELSE $USERS + " users were returned.";
  TABLE $USERS TITLE "Users" COLUMNS id, first_name, last_name, email;
  STATUS;
  METRICS;
}
```

The statements are intentionally small:

| Statement | Use it for |
| --- | --- |
| `TITLE` | The main report heading |
| `DESCRIPTION` | Context below the heading |
| `METRIC` | A prominent label/value pair |
| `FIELD` | A secondary detail or configuration value |
| `PARAGRAPH` | A complete sentence with variables and conditions |
| `TABLE` | Rows from a `$variable` |
| `STATUS` | One outcome row per executed request |
| `METRICS` | Collection, counts, pass/fail, duration, and timestamp |

Use `IF / THEN / ELSE` for clear singular/plural wording. `$POSTS` resolves to the
row count in summary expressions. Existing `TEXT`, `KV`, `LV`, `QT`, and
`LABEL_TABLE` syntax remains supported for older filters.

## Credentials and runtime settings

Use the environment file for non-secret settings and the credential profile command
for reusable secrets:

```bash
java -jar target/postman-excel-runner-1.0.0.jar --config
java -jar target/postman-excel-runner-1.0.0.jar --config --show
java -jar target/postman-excel-runner-1.0.0.jar --config --switch profile-name
```

Supported runtime settings include:

| Setting | Meaning |
| --- | --- |
| `REQUEST_TIMEOUT_SECONDS` | HTTP connection and request timeout |
| `MAX_RESPONSE_MB` | Maximum response body retained |
| `DISABLE_SSL_VERIFY` | Disable certificate checks; use only for controlled testing |
| `SSL_TRUST_STORE` | Custom JKS or PKCS12 trust store |
| `SSL_TRUST_STORE_PASSWORD` | Trust-store password |
| `OUTPUT_FILE` | Output filename template |

Filter-level `AUTH` and `VARS` values override environment values for that run.

## Troubleshooting

**“No collection was provided.”** Set `COLLECTION` in the filter, pass
`--collection`, or pass `--collection-name` with a configured `COLLECTIONS_DIR`.

**“Multiple filters found.”** Add `--filter filters/name.filter` or leave one filter
in `FILTERS_DIR` for auto-selection.

**“Unknown request name.”** Copy the exact request name from the web outline or run
`--list` and inspect the collection. Names are case-sensitive during validation.

**“Summary variable is undefined.”** Define `$NAME = FILTER ...;` before using
`$NAME` in `METRIC`, `PARAGRAPH`, `TABLE`, or another summary expression.

**The web page is empty or cannot connect.** Confirm the Java process is running,
open the exact loopback URL it printed, and check whether the port is already used.

**A save reports a conflict.** Refresh or use **Reload from disk** to inspect the
newer version. Use **Save as** if both versions should be retained.

**A run is interrupted.** Restarting the application marks unfinished runs as
interrupted. Run the report again; completed workbooks remain in `reports`.

**The workbook preview differs from Excel.** The web preview renders cells, merges,
widths, heights, colors, alignment, and wrapping. Open the downloaded `.xlsx` for
charts, images, formulas, borders, conditional formatting, and other Excel features.

**An API request fails.** Open **Requests** and **Output**. Check the URL, method,
credentials, timeout, TLS settings, and response status. A failed HTTP request still
produces a workbook so the failure can be reviewed.

## Verification and development

Run the complete checks:

```bash
./mvnw clean verify
node --test src/test/web/app.test.cjs
```

The Java tests cover parsing, validation, request execution, Excel generation, web
file boundaries, run persistence, workbook previews, and the complete mock-API flow.
The Node tests cover static client bindings, syntax highlighting, escaping, editor
state, keyboard behavior, and workbook rendering helpers.

For implementation details, read [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md). For the
web-specific API and architecture, read [WEB_GUIDE.md](WEB_GUIDE.md).
