# User Guide

This guide covers setup, daily commands, and common troubleshooting for `.filter`-based runs.

For the complete workflow, quick reference, frequent instructions, and troubleshooting,
start with [FULL_GUIDE.md](FULL_GUIDE.md).

## Report Studio web interface

Build the application and start the local workspace from the project directory:

```bash
./mvnw package
java -jar target/postman-excel-runner-1.0.0.jar --web
```

Open `http://127.0.0.1:8080`. If that port is occupied, add `--port 8087`.

Report Studio provides a file explorer, a Postman-style API client for testing each
collection request, a tabbed report editor, validation, background execution, run
history, formatted worksheet previews, and Excel downloads. Select a collection to
open its API client, or start with `filters/reqres.filter` for a report example.

See [WEB_GUIDE.md](WEB_GUIDE.md) for file management, keyboard shortcuts, and web-specific behavior.

## Recommended Filter Format

Use `.filter` files only.

See `docs/FILTER_GUIDE.md` for the full tutorial and query reference.

## Quick Daily Commands

### 1) List collections

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env --list
```

### 2) List filters

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env --list-filters
```

### 3) Run with a selected filter

```bash
java -jar target/postman-excel-runner-1.0.0.jar \
  --env .env \
  --filter reqres
```

### 4) Run with one command

If `FILTERS_DIR` has exactly one `.filter` file, the app auto-selects it.

```bash
java -jar target/postman-excel-runner-1.0.0.jar --env .env
```

### 5) Include response body preview

```bash
java -jar target/postman-excel-runner-1.0.0.jar \
  --env .env \
  --filter reqres \
  --include-body
```

## Build

```bash
./mvnw clean package
```

Fast build without tests:

```bash
./mvnw clean package -DskipTests
```

Runnable jar:

```text
target/postman-excel-runner-1.0.0.jar
```

## Setup Checklist

1. Install Java 17+.
2. Put Postman collections in `COLLECTIONS_DIR`.
3. Put `.filter` files in `FILTERS_DIR`.
4. Set required values in `.env`.
5. Build and run.

## Minimal `.env` Example

```env
COLLECTIONS_DIR=/absolute/path/to/collections
FILTERS_DIR=/absolute/path/to/filters

REQUEST_TIMEOUT_SECONDS=30
MAX_RESPONSE_MB=10
```

## Common Issues

- Error: multiple filters found
  - Pass `--filter <name>`.
- Error: collection block is ambiguous
  - Pass `--collection-name <name>` when using a multi-collection `.filter` file.
- Error: request not found
  - Ensure request names in `REQUESTS`/`FILTER` exactly match collection request names.
