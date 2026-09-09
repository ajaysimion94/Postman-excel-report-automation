# Working Open API Samples

These collection/filter pairs are ready to use in Report Studio. They use public,
no-key endpoints and select only read-only GET requests when generating reports.

## Quick start

1. Start Report Studio and expand **Collections**.
2. Select `pokeapi-open.json` or `reqres.json`.
3. Choose a request and select **Send** to inspect its live response.
4. Expand **Filters** and select the matching filter's **▶** button.
5. Review the summary and workbook on the right.

## PokéAPI sample

- Collection: `collections/pokeapi-open.json`
- Filter: `filters/pokeapi-open.filter`
- Requests: Pokémon list, type list, and Pikachu details
- Demonstrates: pagination, `results` datasets, nested response exploration, and
  multi-sheet reports

Public documentation: <https://pokeapi.co/docs/v2>

## Reqres sample

- Collection: `collections/reqres.json`
- Filter: `filters/reqres.filter`
- Report requests: user list and one user profile
- Demonstrates: wrapped arrays, nested response objects, response projection, and
  testing GET, POST, PUT, and DELETE requests in the API client

Public documentation: <https://reqres.in/>

## Notes

- Public services can enforce availability or rate limits outside this project.
- The verified sample requests keep result sets small and do not require credentials.
- API-client edits are temporary. Use **View collection JSON** to save permanent changes.
- The matching filter name lets Quick Run resolve the collection automatically.
