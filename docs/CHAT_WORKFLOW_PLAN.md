# Guided chat workspace — proposed design brief

Status: implemented as the Guided workspace in Report Studio. The later enhancements
listed in section 10 remain optional future work.

## 1. Purpose and design direction

Add a guided chat page to Report Studio where API and report developers can search
reports, run saved filters, create filters, add collections, and test requests. The
primary outcome is a reusable `.filter` definition and a generated Excel report,
created through selections and previews.

Use the established [design context](../.impeccable.md): calm, precise, utilitarian;
muted green working surfaces, dark application chrome, and dense readable tables.
Chat explains each step; embedded controls collect structured input. Keep the
existing editor accessible as an advanced workspace.

The initial version uses a deterministic guided workflow. Free text can search the
current choices and invoke named actions. Broader natural-language interpretation
can be added later, translating into the same validated workflow actions.

## 2. Page structure

```text
+------------------+--------------------------------+-------------------------+
| Workspace        | Conversation                   | Preview                 |
|                  |                                |                         |
| New task         | Create filter                  | Data / Definition /     |
| Drafts           | Collection > Request > Columns | Summary                 |
| Collections      |                                |                         |
| Filters          | Select output columns          | Live draft preview      |
| Reports          | [Search fields]                | Source and sample time  |
| Recent runs      | [ ] Field / Type / Sample      |                         |
|                  | [Continue]                     |                         |
|                  |                                |                         |
|                  | [Type an action or search...]  |                         |
+------------------+--------------------------------+-------------------------+
```

The active step occupies the center. Completed steps collapse into short editable
entries, such as “Collection: School · Change.” The preview updates after meaningful
edits without creating a new chat message for every checkbox change.

On smaller screens, use one working column with Conversation and Preview tabs.
Open workspace navigation in a drawer. Preserve selection, focus, and scroll when
switching views. Use labeled controls, keyboard-operable tables, visible focus, and
accessible progress announcements.

## 3. Entry actions

| Action | Guided flow | Result |
| --- | --- | --- |
| Search reports | Search name → optional collection/date/status filters → select result | Summary, workbook preview, download; rerun when the source definition is available |
| Run filter / Generate report | Select saved filter → resolve collection and variables → review requests → run | Execution progress, results, workbook download, saved run history |
| Create filter | Select collection → request → test and inspect data → select columns → customize → save | Reusable `.filter` file; optional immediate report generation |
| Add collection | Import Postman JSON or start an empty collection → validate → name and save | Collection available in the request picker; optionally test its first request |
| Test APIs | Select collection/request → review parameters/auth/body → send → inspect | HTTP outcome, response time, body, datasets, and “Use in a new filter” |

Search reports across both report files and run metadata. A workbook without a
matching run remains downloadable; do not invent its execution status or collection.
If required variables are missing, show their names and fields for entering values.

## 4. Create-filter journey

1. **Choose collection.** Searchable list showing collection name and request count.
   Empty state: “No collections yet. Add a collection to begin.” Import can complete
   inline and return to this step.
2. **Choose request.** Show folder, name, HTTP method, and URL. Use a stable reference
   tied to the collection revision; a display name alone is insufficient if names
   repeat. Until executable identity is unambiguous, explain duplicate-name conflicts.
3. **Test and inspect.** A GET request choice can be labeled “Select and test” and
   start inspection immediately. For methods that may change server data, show the
   concrete request and a “Run request and inspect” action. Show actual progress,
   HTTP status, duration, and error details. An HTTP success is not a test-assertion
   result and does not guarantee a usable JSON dataset.
4. **Choose dataset when needed.** Identify possible row sources and show a small
   sample of each. Automatically select an unambiguous dataset; keep a Change action.
   Mark array boundaries and the row scope for conditions.
5. **Choose columns.** Searchable checkbox table with field path, inferred type,
   sample value, and optional output label. Provide selected count, select visible,
   clear, and reorder controls. Optional or mixed-type fields remain visible.
6. **Create the draft.** Require at least one output column. Generate the definition
   and display “Filter draft ready.” Offer Create summary, Add request, Add conditions,
   Save filter, and Generate report. “Filter file saved” appears only after saving.
7. **Save.** Collect a filename, validate the generated definition, then use existing
   revision-aware file saving. Show the saved path. Preserve a draft if saving fails.
8. **Generate.** Run an immutable definition snapshot with the selected request
   settings, then show summary and workbook results with a download action.

“Add request” repeats steps 2–5 and adds to the same draft. Each request keeps its
own dataset, columns, and conditions. Start with one collection per draft, matching
the engine's execution scope; cross-collection orchestration is a later feature.

Editing a collection, request, or variable invalidates affected response samples and
downstream previews. Preserve still-valid selections and identify fields requiring
attention. Never resend an API request merely because an old chat entry is opened.

## 5. Conditions and summary builder

### Response conditions

Use rows of `Field | Operator | Value`, with Add condition and AND/OR groups.
Offer operators appropriate to the discovered type, while validating against the
actual engine semantics. Keep condition fields available even if they are not
selected for display. Filtering happens before output column projection.

Start with WHERE; reveal sorting, limits, distinct rows, grouping, aggregates, and
HAVING as advanced options. WHERE filters source rows. HAVING filters aggregate
results, and becomes available after grouping/aggregation is configured.

### Create summary

Collect a title and description, followed by an ordered list of summary elements:

- **Add query:** choose a dataset, optionally filter it, and choose its output.
- **Add metric:** choose a dataset and an explicit calculation, such as row count,
  sum, average, minimum, or maximum, then enter a display label.
- **Add status:** initially insert the existing per-request HTTP status table.

Each element supports preview, edit, remove, and move up/down. A query may have its
own title. Summary-only conditions affect that query; applying a condition to the
request's response sheet is a separate, visible scope choice.

| Query choice | Inputs | Output |
| --- | --- | --- |
| Select table | Source dataset; columns | Table of rows |
| Select table with condition | Source dataset; predicate; columns | Matching rows as a table |
| Select values from table | Source dataset; field; optional predicate | One-column list of matching values |

Use the plural “values” for the last choice because the result may contain many
names. Preserve duplicates by default; offer Distinct values explicitly. A future
single-value mode must define how to handle zero or multiple matches.

Metric calculations must be explicit in the internal model. The existing summary
language can display a single cell for a one-row/one-column variable, so reusing a
projected dataset variable is not a reliable general-purpose row-count operation.
Verify or extend compilation for explicit aggregates. Custom business pass/fail
rules are distinct from the current HTTP STATUS table and require separate support.

## 6. The School example

Interpret the user's expression as a proposed guided expression:

```text
School.class.students.student.name where age < 13;
```

The picker must resolve this to the selected collection, request, JSON dataset, and
field; those are separate identities. For this example, assume `School` is a JSON
root object and `class.students` is an array of rows containing a `student` object:

```json
{
  "School": {
    "class": {
      "students": [
        {"student": {"name": "Asha", "age": 12}},
        {"student": {"name": "Ben", "age": 14}},
        {"student": {"name": "Mina", "age": 11}}
      ]
    }
  }
}
```

The structured builder resolves:

| Setting | Value |
| --- | --- |
| Dataset path | `School.class.students[]` |
| Value field within each row | `student.name` |
| Condition within the same row | `student.age < 13` |
| Display | List of values, header “Student name” |

Preview: **Asha**, **Mina**. Explain: “List student names where the student's age is
less than 13.” If the actual response shape differs, derive paths from that response
and show array traversal explicitly. Resolve abbreviated `age` only when unambiguous.

This full path expression is **proposed UI syntax**, not an existing `.filter`
statement. Store path segments and array traversal in a typed query model; avoid
interpreting arbitrary pasted code. Keys containing dots need explicit path segments.

The current language supports FILTER, COLUMNS, EXPAND, and SUMMARY. From inspection,
the example's single array boundary can map to `EXPAND "Get school" ON
School.class.students;`, then a summary FILTER using the full
`School.class.students.student.age` path and a TABLE selecting the corresponding
name field. Verify that mapping with the fixture during implementation.

The engine permits one EXPAND definition per request; the browser's nested-table
renderer is a separate implementation. Add a shared, explicit dataset-selection
contract and compile to existing syntax wherever it expresses the selected dataset.
Extend the parser/runtime only for unsupported cases such as traversal across
multiple array boundaries or explicit choice among otherwise ambiguous row sources.
The saved definition must retain those choices for both CLI and web execution.
Do not silently fall back to another array.

Preview/export parity for the School acceptance case is required, not an optional
follow-up. Test the actual fixture and supported multi-level arrays against both.

Preview shows a labeled sample and its timestamp. “All matching names” means all
matches in the fetched data, with pagination in the UI. Fetching every page from a
remote API requires explicit API pagination support; a single response is not
evidence that the whole remote dataset was retrieved.

## 7. Workflow state and execution

```text
Choose action → Choose collection → Choose request → Inspect response
                                                     ↓
                                           Choose dataset → Choose columns
                                                                 ↓
                                                            Draft ready
                                                  ↙              ↓            ↘
                                           Add request     Build summary   Add conditions
                                                  ↘              ↓            ↙
                                                        Validate → Save → Run
                                                                           ↓
                                                                   Results / Download
```

Keep explicit workflow state containing an ID, revision, active step, collection
reference, request references, response sample references, dataset paths, column
selections, conditions, summary elements, filename, and run IDs. Chat messages are
a readable history of transitions; they are not the definition's source of truth.

Use typed events such as SelectCollection, SelectRequest, InspectResponse,
SelectColumns, AddSummaryQuery, SaveFilter, and RunReport. Validate transitions on
the server where they cause execution or persistence. Give each asynchronous action
an ID and associate its result with a draft revision so late responses cannot alter
a newer selection. Prevent duplicate dispatch from repeated clicks.

Preview evaluates an existing sample and causes no additional network request.
“Refresh data” and “Generate report” are explicit operations. Record the source and
settings used so changes between sampled data and a fresh run are understandable.
Temporary API-client overrides must either be captured in a supported executable
definition or saved to the collection before claiming the report will reproduce them.

Persist resumable drafts and compact conversation events under workspace state.
Keep credentials and raw response bodies out of chat history and durable browser
storage. Reuse credential references; persist response samples only if a retention
feature is deliberately added. After restart, drafts may need fresh samples.

Generated definition is inspectable throughout. An “Open in editor” handoff may
detach the draft from the builder. Only return arbitrary source edits to guided mode
after supported parsing succeeds; never silently drop unsupported statements.

## 8. Existing foundations and required additions

| Capability | Existing foundation | Planned addition |
| --- | --- | --- |
| Collections and files | `GET /api/files`, `GET /api/file`, `GET /api/collection`, `PUT /api/file` | Chat pickers, import flow, draft references |
| Request execution | `POST /api/request`, shared Java request executor | Inspection orchestration and sample identity |
| Response viewing | Browser dataset detection and nested response tables | Shared dataset/schema semantics with the report engine |
| Filter generation | Parser, validator, existing filter language | Typed builder model, compiler, explicit nested dataset support |
| Validation | `POST /api/validate` | Map validation errors to the relevant builder control |
| Report execution | `POST /api/runs`, `POST /api/runs/saved-filter` | Guided launch, progress entries, duplicate-action prevention |
| Results and search | `GET /api/runs`, `GET /api/run`, file listing | Combined report search with provenance-aware result fields |
| Preview/download | `GET /api/workbook`, `GET /api/download` | Preview panel and chat result actions |
| Draft recovery | Existing workspace state storage conventions | Versioned draft and workflow-event persistence |

Suggested new service boundaries are WorkflowService, DatasetService, and
FilterDraftCompiler. These are proposed modules, not existing APIs. Add routes only
where the current routes cannot carry the required workflow data. Keep Java 17 and
the bundled JavaScript frontend for the first delivery; no framework migration is
needed for this feature.

## 9. Key states and feedback

| State | What the user sees and can do |
| --- | --- |
| New task | Five clear entry actions and recent drafts |
| No collections or requests | Inline add/import action or choose another collection |
| Inspecting API | Request identity and real progress; repeated dispatch disabled |
| Missing auth/variables | Specific missing inputs and retry after correction |
| API failure/timeout | HTTP or transport error, inspect details, retry explicitly |
| Non-JSON response | Raw response and explanation that columns cannot be inferred |
| Empty dataset | “No rows returned”; retry, choose another dataset, or enter a schema |
| Partial schema/sample | Sample bounds, optional fields, and a way to add known fields |
| Zero selected columns | Selected count and disabled Continue with a clear reason |
| Zero query matches | Empty result with active condition and Edit condition |
| Invalid query | Field-level error and generated-source location when available |
| Stale schema/settings | Identify affected selections and offer refresh/review |
| Save conflict | Preserve draft and offer reload comparison or Save as |
| Report running | Actual backend run state and the immutable input snapshot |
| Report partially fails | Which requests failed, available outputs, explicit rerun |
| Report complete | Summary, workbook preview, generated filename, download |
| Reload/restart | Resume draft or inspect saved run; refresh samples when necessary |

Do not show invented percentages. Cancellation must describe whether the client
stopped waiting or server execution actually stopped; use cancellation controls only
when the corresponding backend behavior is implemented.

## 10. Implementation sequence and acceptance

1. **Shared data contract and compiler.** Define dataset paths and row scope; build
   the draft model and compilation to valid filter source. Map the School case to
   existing EXPAND behavior and verify it. Extend the engine only for identified
   gaps, aligning sample preview with export semantics.
2. **Complete create-filter path.** Add chat shell, collection/request pickers,
   inspection, column table, conditions, save, and report generation. Deliver one
   end-to-end workflow before broadening the action menu.
3. **Summary builder.** Add title, description, all three query modes, explicit
   metrics, HTTP status table, ordering, and summary preview. Complete the user's
   School example through a saved filter and generated workbook.
4. **Remaining actions and recovery.** Add report search, saved-filter launch,
   collection import/create, standalone API testing, draft resume, and keyboard and
   narrow-screen verification. All five entry actions are part of the first full release.
5. **Optional later enhancements.** Natural-language commands, custom assertions,
   cross-collection orchestration, and configurable remote pagination.

Acceptance checks should exercise outcomes:

- Create a valid filter through the guided flow without typing filter source.
- The School fixture returns exactly Asha and Mina in both preview and export.
- A non-displayed age field remains usable for filtering names.
- Multiple requests retain independent fields/conditions when editing earlier steps.
- Metrics return correct counts for zero, one, and multiple rows.
- Sample preview does not call the API again; fresh report generation does.
- Old or repeated actions cannot cause duplicate runs or overwrite newer selections.
- Empty, mixed-type, sparse, deeply nested, and non-JSON responses remain recoverable.
- Saved generated filters work through the CLI and the existing editor.
- Existing filters continue producing equivalent results after extraction changes.
- Search, import, request testing, restart recovery, and download complete successfully.

Use local mock APIs and response fixtures for execution tests. Add focused Java
tests for extraction, compilation, and report parity, and browser tests for workflow
transitions and recovery. Reuse the project's existing Maven and Node test suites.

## 11. Assumptions and review points

- This is an additional guided mode in the existing local Report Studio application.
- The initial audience and visual tone follow `.impeccable.md`.
- “Automated chat” initially means button-driven workflow automation, with optional
  text shortcuts; an external AI service is not required.
- “Test APIs” initially means send and inspect using current HTTP outcome rules;
  Postman scripts and a custom assertion runner are separate features.
- The School payload above is illustrative; actual dataset paths come from responses.
- One collection per draft is the initial execution boundary.

Review these assumptions and the primary flow before implementation. During UI
implementation, consult impeccable's interaction-design, spatial-design,
responsive-design, and ux-writing references for the forms, table layout, recovery
behavior, and action labels.

## Source references

- [Design context](../.impeccable.md)
- [Web workflow and behavior](WEB_GUIDE.md)
- [Filter and summary language](FILTER_GUIDE.md)
- [HTTP routes](../src/main/java/com/automation/web/WebServer.java)
- [Report and request services](../src/main/java/com/automation/web/ReportService.java)
- [Current browser response rendering](../src/main/resources/web/app.js)
- [Current report data extraction](../src/main/java/com/automation/excel/ExcelReportGenerator.java)
