# Report Studio

Report Studio brings this project's existing Postman execution and Excel reporting
engine into a local browser workspace. It runs from the same Java application as the
CLI and needs no Node.js installation, database, CDN, or frontend build step.

## Start the application

From the project directory:

```bash
./mvnw package
java -jar target/postman-excel-runner-1.0.0.jar --web
```

Open `http://127.0.0.1:8080`. Stop the server with Ctrl+C.

For a different port, workspace, or environment file:

```bash
java -jar target/postman-excel-runner-1.0.0.jar --web \
  --port 8087 \
  --workspace /absolute/path/to/workspace \
  --env /absolute/path/to/.env
```

The workspace directory must already exist. The application creates its
`collections`, `filters`, and `reports` subdirectories when needed. The default
environment file is `.env` inside the selected workspace. Environment variables,
credential profiles, filter overrides, HTTP settings, and Postman compatibility
rules use the existing Java engine.

Web mode manages these workspace subdirectories directly. It does not redirect the
explorer to `COLLECTIONS_DIR` or `FILTERS_DIR` from `.env`. Import files or select the
workspace that contains your collections and filters.

## Create a report

1. Open a `.filter` file from the explorer. `reqres.filter` is a working
   example that selects two GET requests from the bundled Reqres collection.
2. Choose its collection in the editor toolbar. Opening a filter with a matching
   `COLLECTION` filename selects it automatically.
3. Edit the definition. The editor provides syntax highlighting, line numbers,
   indentation, tabs, and a request outline. Click a request name in the outline to
   insert its quoted name into the editor.
4. Select **Validate** to parse the current editor contents and check request names,
   filters, and summary variables. Validation sends no API requests and saves no files.
   Syntax errors include their source line and column.
5. Select **Run report** to execute the selected requests and generate the workbook.
   The run uses a snapshot of the current filter and any open collection buffer,
   including unsaved changes. You can continue editing while the report runs.
6. Review **Summary**, **Requests**, and **Workbook**, then select **Export Excel**.

Run report executes API requests, including POST, PUT, PATCH, or DELETE requests if
your definition selects them. Without `REQUESTS`, the engine executes the collection.
The collection outline displays request methods before execution.

Click a collection JSON file to open the API client on the right. Select any request,
adjust its method, URL, headers, or body, and select **Send** to inspect the response
status, duration, and body. Collection variables, `.env` values, authentication, SSL,
timeout, and response-size settings use the same Java execution engine as report runs.
Select **View collection JSON** when you need to edit the underlying source. An unsaved
collection buffer selected in the report toolbar is used when running from a filter tab.

The editor uses this project's SQL-like `.filter` language. It is not a general SQL
database client. The built-in language guide and [FILTER_GUIDE.md](FILTER_GUIDE.md)
describe the supported statements.

## Working public API samples

The workspace includes verified, no-key collection and filter pairs:

| Collection | Matching filter | Best for |
| --- | --- | --- |
| `pokeapi-open.json` | `pokeapi-open.filter` | Pagination, resource lists, and rich nested JSON |
| `reqres.json` | `reqres.filter` | User lists, nested response objects, and request testing |

Select a collection to test each endpoint in the API client. Hover over its matching
filter and select **▶** to generate a report without opening the script. These samples
run read-only GET requests and require internet access, but no API key.

## Manage files

- **New file** creates a `.filter` definition or a Postman `.json` collection.
- **New folder** organizes files into subfolders.
- **Import files** accepts `.json` collections and `.filter` definitions. Each file
  can be placed in a folder of your choice. Importing never overwrites an existing file.
- **Save** writes the active editor buffer. If another application changed that
  file, saving stops with a conflict message instead of overwriting its changes.
- Select a file and open **•••** to rename or move it, reload it from disk, save the
  open editor contents under a new name, or move the selection to trash.
- **Move to trash** moves the selection to `.web-trash/<unique-id>/`. The confirmation
  names the selection, and the result gives its recovery path. Restore it with your
  operating system's file manager. Top-level workspace folders cannot be removed.
- **Refresh** discovers changes made outside Report Studio. Open editor buffers are
  preserved; use **Reload from disk** to replace one explicitly.

## Test an API from a collection

1. Expand **Collections** in the explorer and select a `.json` collection.
2. Choose a request from the request list in the right-side API client.
3. Review or edit the HTTP method and URL, then use the request tabs:
   - **Params** adds, removes, or temporarily disables query parameters and keeps the
     URL synchronized.
   - **Authorization** supports No Auth, Basic Auth, Bearer Token, and API Key in a
     header or query parameter.
   - **Headers** manages enabled request headers.
   - **Body** supports raw, `x-www-form-urlencoded`, and text `form-data` bodies.
   - **Variables** supplies per-send values for placeholders used anywhere in the
     request. For example, add `ID = 42` and use `{{ID}}` in a URL such as
     `{{baseUrl}}/users/{{ID}}` or in a parameter value.
4. Select **Send**. Collection and `.env` variables are loaded automatically; values
   in the Variables tab override them for this API-client send only.
5. Review the response status, elapsed time, error, or response body. Use **Pretty**
   for formatted JSON, **Table** for report-like rows and columns, or **Raw** for the
   original response text. Nested arrays such as `data.items` are detected as separate
   datasets. Objects and arrays contained in a dataset row appear as compact tables
   inside their parent cell, including values nested several levels deep.

Edits made in the API client are temporary request overrides. They let you test safely
without rewriting the collection file. To make a change permanent, select
**View collection JSON**, edit the JSON source, and save it.

The local API client does not expose unsupported Postman runtime features such as
OAuth 2 flows, pre-request/test scripts, GraphQL mode, or local file uploads. Text
multipart form fields are supported; imported file fields produce a clear error rather
than reading an arbitrary path from disk.

Files must remain within their collection, filter, or report area when moved.
Renaming or moving reports updates saved run references. Text files are limited to
5 MB. Hidden files, symbolic links, and paths outside the workspace are excluded.
The explorer lists up to 5,000 entries and 12 folder levels.

## Review and share results

**Summary** shows request totals, successes, failures, average response time, and a
plain-language outcome. It includes the custom Summary worksheet from your actual
Excel report. Singular and plural wording changes with the result count.

**Workbook** displays worksheet tabs, merged cells, row heights, column widths,
colors, bold and italic text, alignment, and wrapped cell values from the generated
file. It pages through 200 rows at a time. For reports split across multiple files,
use the workbook selector to view and export each part.

The web preview is a cell-based rendering, not a full Excel runtime: it does not
render charts, embedded images, interactive Excel features, or every border and
conditional-formatting rule. The downloaded workbook retains the original Excel
file. The web file-size limit is 100 MB; larger workbooks can be opened directly
from the reports folder.

**Run history** keeps the most recent 100 runs available in the interface. Metadata
is stored under `.web-state`; workbooks are stored under `reports`. History survives
application restarts. A run interrupted by a restart is marked interrupted and can
be started again. **Output** contains validation and run messages for the current page.

## Keyboard shortcuts

| Action | macOS | Windows / Linux |
| --- | --- | --- |
| Save | Command+S | Ctrl+S |
| Save as | Command+Shift+S | Ctrl+Shift+S |
| Run report | Command+Enter | Ctrl+Enter |
| Validate | Command+Shift+Enter | Ctrl+Shift+Enter |
| Find files | Command+K | Ctrl+K |
| Indent | Tab | Tab |
| Leave the editor using the keyboard | Escape, then Tab | Escape, then Tab |
| Open language guide, outside an input | ? | ? |

Use the divider below the editor to change its height. On narrow screens, the menu
button opens the file explorer. Use **Hide results** to give the editor the full
workspace height, and **Show results** to restore the report tabs. The preference is
remembered in this browser. The request outline can be toggled from the toolbar.

To run a report without opening its script, hover over a saved filter in the explorer
and select its play button. Report Studio reads the saved filter, resolves its
`COLLECTION` declaration, and switches to a results-only view. Select **Show editor**
from the results toolbar to return to the script.

## Local access

The server binds only to `127.0.0.1`, checks local Host and Origin headers, and
requires a session token for writes and runs. It is intended for one trusted local
user, not shared or public deployment. The browser UI is bundled and works without
internet access; executing remote API collections still requires network access.

If the browser blocks a local address, open the printed URL in a browser that permits
local application access. This does not affect the CLI or report generation.

## Verification

```bash
./mvnw test
node --test src/test/web/app.test.cjs
```

The HTTP integration tests execute a temporary local mock API, then validate report
content, styled previews, downloads, persistence, file operations, and origin/path
boundaries. They do not call the bundled public APIs.
