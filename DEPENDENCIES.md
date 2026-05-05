# Dependencies

This project targets Java 17 and uses Maven.

## Runtime Dependencies

| Dependency | Version | Scope | Purpose |
| --- | --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-databind` | `2.17.1` | `compile` | Parses the Postman collection JSON and reads nested request/auth structures. |
| `io.github.cdimascio:java-dotenv` | `5.2.2` | `compile` | Loads username, password, token, and other runtime variables from `.env`. |
| `org.apache.poi:poi-ooxml` | `5.2.5` | `compile` | Creates the `.xlsx` workbook and applies per-sheet styling. |
| `org.slf4j:slf4j-simple` | `2.0.13` | `compile` | Provides the SLF4J logging backend required by Apache POI. |
| `org.apache.logging.log4j:log4j-to-slf4j` | `2.23.1` | `compile` | Bridges Log4j2 API calls (used internally by POI) through SLF4J to silence the missing-implementation warning. |

## Test Dependencies

| Dependency | Version | Scope | Purpose |
| --- | --- | --- | --- |
| `org.junit.jupiter:junit-jupiter` | `5.10.2` | `test` | Unit tests for parsing, variable resolution, and Excel generation. |

## Build Plugins

| Plugin | Version | Purpose |
| --- | --- | --- |
| `org.apache.maven.plugins:maven-compiler-plugin` | `3.13.0` | Compiles the application with Java 17. |
| `org.apache.maven.plugins:maven-surefire-plugin` | `3.2.5` | Runs JUnit 5 tests. |
| `org.apache.maven.plugins:maven-shade-plugin` | `3.5.3` | Packages an executable fat JAR with `com.automation.Main` as the entry point. |

## Standard Library Features Used

These do not appear in `pom.xml` because they come with Java 17:

- `java.net.http.HttpClient` for API execution
- `java.util.Base64` for Basic auth header generation
- `java.nio.file` for file and path handling
