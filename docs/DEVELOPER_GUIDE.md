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

## Strict Filter Validation Rules

`FilterValidator` fails fast for:

- collection mismatch
- unknown request names in `requests`
- invalid `responseColumns` keys (must be `*` or known request names)
- blank `outputPrefix`
- invalid API-key auth block (`apiKey` and `apiKeyHeader` must both exist)

## Filter Schema

`FilterSpec` fields:

- `collection`
- `requests`
- `responseColumns`
- `outputPrefix`
- `auth` (`FilterAuthSpec`)
- `vars`

`FilterAuthSpec` maps aliases from JSON keys like `API_USERNAME` and camelCase keys like `username`.

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
./mvnw -q test -Dtest=CredentialLoaderFilterOverrideTest
```
