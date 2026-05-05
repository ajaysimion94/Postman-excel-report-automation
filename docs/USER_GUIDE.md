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

```json
{
  "collection": "jsonplaceholder",
  "requests": ["List all posts", "Get post by ID"],
  "responseColumns": {
    "*": ["id", "title", "userId"],
    "Get post by ID": ["id", "title", "body", "userId"]
  },
  "outputPrefix": "posts-focus",
  "auth": {
    "API_USERNAME": "override-user",
    "API_PASSWORD": "override-pass",
    "BEARER_TOKEN": "override-token",
    "API_KEY": "override-key",
    "APIKEY_HEADER": "X-API-Key"
  },
  "vars": {
    "TEAM": "smoke",
    "ENV_NAME": "staging"
  }
}
```

### Rules

- `collection`:
  - optional
  - can be collection file name/stem or display name
- `requests`:
  - optional
  - exact request names only
- `responseColumns`:
  - optional
  - keys must be `*` or valid request names
- `outputPrefix`:
  - optional
  - must be non-blank if provided
- `auth`:
  - optional
  - overrides `.env` auth values
- `vars`:
  - optional
  - overrides any `.env` variable keys

### Validation policy (strict)

Filter validation runs before HTTP execution. The run fails fast for:

- unknown request names
- invalid `responseColumns` keys
- collection mismatch
- blank `outputPrefix`
- invalid API key auth block (must include both `API_KEY` and `APIKEY_HEADER`)

---

## Included Sample Filters

- `filters/posts-only.json`
  - focuses on two post APIs with curated columns
- `filters/minimal-daily.json`
  - single-request quick daily run
- `filters/auth-override.json`
  - demonstrates filter-level auth and vars overrides

---

## Credential Precedence

Runtime variable precedence is:

1. filter `auth` and filter `vars`
2. `.env`
3. system environment
4. collection variables (used as base values before runtime overrides)

---

## Troubleshooting

- `Multiple filter files found...`
  - pass `--filter <name>` or remove extra filters from `FILTERS_DIR`
- `No collection was provided...`
  - provide `--collection-name`, `--collection`, or set `collection` in filter
- `Filter has unknown request names...`
  - use exact request names from the collection
- `Filter responseColumns contains unknown request keys...`
  - use `*` or exact request names only

---

For internals, architecture, and test strategy, see [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md).
