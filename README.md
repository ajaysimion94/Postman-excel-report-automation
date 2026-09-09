# Postman Excel Report Automation

Execute Postman collections, shape response data with SQL-like filters, and generate
styled Excel reports from the command line or the local Report Studio web IDE.

## Open Report Studio

Requires Java 17 or newer.

```bash
./mvnw package
java -jar target/postman-excel-runner-1.0.0.jar --web
```

Open `http://127.0.0.1:8080`. Add `--port 8087` if port 8080 is already in use.

The web workspace includes a file explorer for collections and filters, a tabbed
editor, validation, background report generation, enhanced summaries, worksheet
previews, run history, and Excel downloads. Start with `filters/reqres.filter`.

Ready-to-run public samples are included:

- `collections/pokeapi-open.json` + `filters/pokeapi-open.filter`
- `collections/reqres.json` + `filters/reqres.filter`

All report samples select only read-only GET requests.

- [Web workspace guide](docs/WEB_GUIDE.md)
- [Full guide and quick reference](docs/FULL_GUIDE.md)
- [CLI user guide](docs/USER_GUIDE.md)
- [Filter language and summary syntax](docs/FILTER_GUIDE.md)
- [Developer guide](docs/DEVELOPER_GUIDE.md)
- [Working open API samples](docs/OPEN_API_SAMPLES.md)

Existing CLI commands and legacy `.filter` syntax remain supported.
