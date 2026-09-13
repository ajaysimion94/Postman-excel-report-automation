package com.automation.web;

import com.automation.postman.PostmanCollection;
import com.automation.postman.PostmanCollectionParser;
import com.automation.postman.RequestSpec;
import com.automation.web.documents.CatalogField;
import com.automation.web.documents.CatalogMethod;
import com.automation.web.documents.CatalogRecord;
import com.automation.web.documents.CatalogType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File-backed OPD notes and typed catalog data for the Documents workspace. */
public final class DocumentsService {
    private static final String OPDS = "documents/opds/";
    private static final String TYPES = "documents/catalogs/types/";
    private static final String RECORDS = "documents/catalogs/records/";
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^]#|]+)(?:#[^]|]+)?(?:\\|([^]]+))?]]");
    private static final Pattern HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Set<String> FIELD_TYPES = Set.of("string", "number", "boolean", "date", "object", "array");

    private final WorkspaceFiles files;
    private final ObjectMapper mapper;

    public DocumentsService(WorkspaceFiles files, ObjectMapper mapper) throws IOException {
        this.files = files;
        this.mapper = mapper;
        migrateLegacyCatalogs();
    }

    public Map<String, Object> summary() throws IOException {
        return Map.of("notes", listNotes(), "types", listTypes(), "records", listRecords());
    }

    public List<Map<String, Object>> listNotes() throws IOException {
        List<Map<String, Object>> notes = new ArrayList<>();
        for (WorkspaceFiles.Entry entry : files.list()) {
            if (!entry.directory() && entry.path().startsWith(OPDS) && entry.path().endsWith(".md")) {
                WorkspaceFiles.Document document = files.read(entry.path());
                Map<String, Object> note = noteMetadata(document);
                note.put("modified", entry.modified());
                notes.add(note);
            }
        }
        notes.sort((a, b) -> Objects.toString(a.get("title")).compareToIgnoreCase(Objects.toString(b.get("title"))));
        return notes;
    }

    public Map<String, Object> getNote(String path) throws IOException {
        requireNotePath(path);
        WorkspaceFiles.Document document = files.read(path);
        Map<String, Object> result = noteMetadata(document);
        result.put("content", document.content());
        result.put("links", resolvedLinks(document.content()));
        result.put("backlinks", backlinks(path));
        return result;
    }

    public Map<String, Object> createNote(JsonNode body) throws IOException {
        String title = body.path("title").asText("Untitled OPD").trim();
        if (title.isEmpty()) title = "Untitled OPD";
        String requested = body.path("path").asText("").trim();
        String path = requested.isEmpty() ? uniqueNotePath(title) : requested;
        requireNotePath(path);
        String id = UUID.randomUUID().toString();
        String content = "---\nid: " + id + "\ntitle: " + yamlText(title) + "\ncategory: Procedure\ntags: []\n---\n\n# " + title + "\n\nDescribe the purpose, responsibilities, and procedure.\n";
        WorkspaceFiles.Document saved = files.save(path, content, null);
        return getNote(saved.path());
    }

    public Map<String, Object> saveNote(JsonNode body) throws IOException {
        String path = required(body, "path");
        requireNotePath(path);
        String content = required(body, "content");
        String revision = body.path("revision").isTextual() ? body.get("revision").asText() : null;
        files.save(path, content, revision);
        return getNote(path);
    }

    public void deleteNote(String path) throws IOException {
        requireNotePath(path);
        files.trash(path);
    }

    public List<Map<String, Object>> listTypes() throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entity<CatalogType> entity : typeEntities()) result.add(withMeta(entity.value(), entity.document()));
        result.sort((a, b) -> Objects.toString(a.get("name")).compareToIgnoreCase(Objects.toString(b.get("name"))));
        return result;
    }

    public Map<String, Object> getType(String id) throws IOException {
        Entity<CatalogType> entity = findType(id);
        Map<String, Object> result = withMeta(entity.value(), entity.document());
        result.put("resolvedFields", resolveFields(entity.value(), typesById(), new LinkedHashSet<>()));
        return result;
    }

    public Map<String, Object> createType(JsonNode body) throws IOException {
        CatalogType value = parseType(body, UUID.randomUUID().toString());
        validateType(value, null);
        String path = TYPES + value.id() + ".json";
        WorkspaceFiles.Document saved = files.save(path, pretty(value), null);
        return withMeta(value, saved);
    }

    public Map<String, Object> updateType(JsonNode body) throws IOException {
        String id = required(body, "id");
        Entity<CatalogType> existing = findType(id);
        CatalogType value = parseType(body, id);
        validateType(value, id);
        WorkspaceFiles.Document saved = files.save(existing.document().path(), pretty(value), required(body, "revision"));
        Map<String, Object> result = withMeta(value, saved);
        List<Map<String, String>> affected = new ArrayList<>();
        for (Entity<CatalogRecord> entity : recordEntities()) try { validateRecord(entity.value()); }
        catch (WebException error) { affected.add(Map.of("id", entity.value().id(), "name", entity.value().name(), "error", error.getMessage())); }
        result.put("affectedRecords", affected);
        return result;
    }

    public void deleteType(String id) throws IOException {
        Entity<CatalogType> target = findType(id);
        for (Entity<CatalogType> entity : typeEntities()) {
            CatalogType type = entity.value();
            if (id.equals(type.extendsId()) || safe(type.fields()).stream().anyMatch(field -> id.equals(field.referenceTypeId()) || id.equals(field.arrayItemType()))) {
                throw new WebException(409, "This type is referenced by " + type.name() + ". Remove that relationship first.");
            }
        }
        for (Entity<CatalogRecord> entity : recordEntities()) {
            if (id.equals(entity.value().typeId())) throw new WebException(409, "This type is used by record " + entity.value().name() + ". Delete or reassign that record first.");
        }
        files.trash(target.document().path());
    }

    public List<Map<String, Object>> listRecords() throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entity<CatalogRecord> entity : recordEntities()) result.add(withMeta(entity.value(), entity.document()));
        result.sort((a, b) -> Objects.toString(a.get("name")).compareToIgnoreCase(Objects.toString(b.get("name"))));
        return result;
    }

    public Map<String, Object> createRecord(JsonNode body) throws IOException {
        CatalogRecord value = parseRecord(body, UUID.randomUUID().toString());
        validateRecord(value);
        String path = RECORDS + value.id() + ".json";
        WorkspaceFiles.Document saved = files.save(path, pretty(value), null);
        return withMeta(value, saved);
    }

    public Map<String, Object> updateRecord(JsonNode body) throws IOException {
        String id = required(body, "id");
        Entity<CatalogRecord> existing = findRecord(id);
        CatalogRecord value = parseRecord(body, id);
        validateRecord(value);
        WorkspaceFiles.Document saved = files.save(existing.document().path(), pretty(value), required(body, "revision"));
        return withMeta(value, saved);
    }

    public void deleteRecord(String id) throws IOException {
        files.trash(findRecord(id).document().path());
    }

    public Map<String, Object> catalogSearch(String query) throws IOException {
        String needle = Objects.toString(query, "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> types = listTypes().stream().filter(item -> contains(item, needle)).toList();
        List<Map<String, Object>> records = listRecords().stream().filter(item -> contains(item, needle)).toList();
        return Map.of("types", types, "records", records, "count", types.size() + records.size());
    }

    public Map<String, Object> noteSearch(String query) throws IOException {
        String needle = Objects.toString(query, "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : listNotes()) {
            WorkspaceFiles.Document document = files.read(Objects.toString(note.get("path")));
            if (needle.isBlank() || contains(note, needle) || document.content().toLowerCase(Locale.ROOT).contains(needle)) {
                Map<String, Object> match = new LinkedHashMap<>(note);
                String plain = document.content().replaceAll("(?s)^---.*?---\\s*", "").replaceAll("[#*_`>]", "").trim();
                match.put("snippet", plain.length() > 180 ? plain.substring(0, 180) + "…" : plain);
                result.add(match);
            }
        }
        return Map.of("documents", result, "count", result.size());
    }

    public List<Map<String, Object>> discover(String collectionPath) throws IOException {
        if (collectionPath == null || !collectionPath.startsWith("collections/") || !collectionPath.endsWith(".json")) {
            throw new WebException(400, "Choose a Postman collection from the collections folder.");
        }
        Path path = files.resolve(collectionPath);
        PostmanCollection collection = new PostmanCollectionParser().parse(path);
        Map<String, List<RequestSpec>> folders = new LinkedHashMap<>();
        for (RequestSpec request : collection.requests()) {
            String folder = request.folderPath() == null || request.folderPath().isBlank() ? "Root" : request.folderPath();
            folders.computeIfAbsent(folder, ignored -> new ArrayList<>()).add(request);
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map.Entry<String, List<RequestSpec>> folder : folders.entrySet()) {
            List<CatalogMethod> methods = folder.getValue().stream().map(request -> new CatalogMethod(
                    request.name(), Objects.toString(request.description(), ""), request.name(), "", "", "object")).toList();
            CatalogType type = new CatalogType(UUID.randomUUID().toString(), folder.getKey(), "class", null,
                    "Discovered from " + collection.name(), "Discovered", List.of(), List.of(), methods,
                    List.of(collectionPath), folder.getValue().stream().map(RequestSpec::name).toList());
            WorkspaceFiles.Document saved = files.save(TYPES + type.id() + ".json", pretty(type), null);
            created.add(withMeta(type, saved));
        }
        return created;
    }

    public Path exportExcel() throws IOException {
        Path output = files.resolve("reports/documents-export-" + Instant.now().toEpochMilli() + ".xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet notes = workbook.createSheet("OPD Index");
            writeHeader(notes, "Title", "Category", "Path", "Tags");
            int rowIndex = 1;
            for (Map<String, Object> note : listNotes()) {
                Row row = notes.createRow(rowIndex++);
                row.createCell(0).setCellValue(Objects.toString(note.get("title"), ""));
                row.createCell(1).setCellValue(Objects.toString(note.get("category"), ""));
                row.createCell(2).setCellValue(Objects.toString(note.get("path"), ""));
                row.createCell(3).setCellValue(Objects.toString(note.get("tags"), ""));
            }
            Sheet types = workbook.createSheet("Catalog Types");
            writeHeader(types, "Name", "Kind", "Category", "Extends", "Fields", "Methods");
            rowIndex = 1;
            for (Entity<CatalogType> entity : typeEntities()) {
                CatalogType type = entity.value();
                Row row = types.createRow(rowIndex++);
                row.createCell(0).setCellValue(type.name());
                row.createCell(1).setCellValue(type.kind());
                row.createCell(2).setCellValue(type.category());
                row.createCell(3).setCellValue(Objects.toString(type.extendsId(), ""));
                row.createCell(4).setCellValue(safe(type.fields()).size());
                row.createCell(5).setCellValue(safe(type.methods()).size());
            }
            Sheet records = workbook.createSheet("Catalog Records");
            writeHeader(records, "Name", "Type", "Values (JSON)");
            rowIndex = 1;
            for (Entity<CatalogRecord> entity : recordEntities()) {
                CatalogRecord record = entity.value();
                Row row = records.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.name());
                row.createCell(1).setCellValue(record.typeId());
                row.createCell(2).setCellValue(record.values().toString());
            }
            for (Sheet sheet : List.of(notes, types, records)) {
                for (int column = 0; column < sheet.getRow(0).getLastCellNum(); column++) sheet.autoSizeColumn(column);
            }
            try (var stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
        return output;
    }

    private void validateType(CatalogType candidate, String replacingId) throws IOException {
        if (candidate.name().isBlank()) throw new WebException(400, "Enter a type name.");
        if (!Set.of("class", "interface").contains(candidate.kind())) throw new WebException(400, "Type kind must be class or interface.");
        Map<String, CatalogType> types = typesById();
        if (replacingId != null) types.remove(replacingId);
        if (types.values().stream().anyMatch(type -> type.name().equalsIgnoreCase(candidate.name()))) {
            throw new WebException(409, "A catalog type already uses this name.");
        }
        types.put(candidate.id(), candidate);
        Set<String> fieldNames = new HashSet<>();
        for (CatalogField field : safe(candidate.fields())) {
            if (field.name() == null || field.name().isBlank()) throw new WebException(400, "Every field needs a name.");
            if (!fieldNames.add(field.name().toLowerCase(Locale.ROOT))) throw new WebException(400, "Field names must be unique within a type.");
            if (!FIELD_TYPES.contains(field.type())) throw new WebException(400, "Unsupported field type: " + field.type() + ".");
            if (field.referenceTypeId() != null && !field.referenceTypeId().isBlank() && !types.containsKey(field.referenceTypeId())) {
                throw new WebException(400, "Field " + field.name() + " references a type that does not exist.");
            }
            if (field.arrayItemType() != null && !field.arrayItemType().isBlank() && !FIELD_TYPES.contains(field.arrayItemType()) && !types.containsKey(field.arrayItemType())) {
                throw new WebException(400, "Field " + field.name() + " has an array item type that does not exist.");
            }
        }
        validateInheritance(candidate.id(), types, new HashSet<>(), new HashSet<>());
    }

    private void validateInheritance(String id, Map<String, CatalogType> types, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new WebException(400, "Type inheritance cannot contain a cycle.");
        CatalogType type = types.get(id);
        if (type != null && type.extendsId() != null && !type.extendsId().isBlank()) {
            if (!types.containsKey(type.extendsId())) throw new WebException(400, "The selected parent type does not exist.");
            validateInheritance(type.extendsId(), types, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private void validateRecord(CatalogRecord record) throws IOException {
        if (record.name().isBlank()) throw new WebException(400, "Enter a record name.");
        CatalogType type = findType(record.typeId()).value();
        if (record.values() == null || !record.values().isObject()) throw new WebException(400, "Record values must be a JSON object.");
        validateValues(record.values(), type, "Record", typesById(), 0);
    }

    private void validateValues(JsonNode values, CatalogType type, String prefix, Map<String, CatalogType> types, int depth) {
        if (depth > 20) throw new WebException(400, prefix + " is nested too deeply.");
        for (Map<String, Object> resolved : resolveFields(type, types, new LinkedHashSet<>())) {
            CatalogField field = (CatalogField) resolved.get("field");
            JsonNode value = values.get(field.name());
            String label = prefix + " field " + field.name();
            if (field.required() && (value == null || value.isNull())) throw new WebException(400, label + " is required.");
            if (value != null && !value.isNull() && !matchesType(value, field.type())) {
                throw new WebException(400, label + " must be " + field.type() + ".");
            }
            if (value != null && value.isObject() && field.referenceTypeId() != null && types.containsKey(field.referenceTypeId())) {
                validateValues(value, types.get(field.referenceTypeId()), label, types, depth + 1);
            }
            if (value != null && value.isArray() && field.arrayItemType() != null && !field.arrayItemType().isBlank()) {
                for (int index = 0; index < value.size(); index++) {
                    JsonNode item = value.get(index);
                    CatalogType itemType = types.get(field.arrayItemType());
                    if (itemType != null) {
                        if (!item.isObject()) throw new WebException(400, label + " item " + (index + 1) + " must be an object.");
                        validateValues(item, itemType, label + " item " + (index + 1), types, depth + 1);
                    } else if (FIELD_TYPES.contains(field.arrayItemType()) && !matchesType(item, field.arrayItemType())) {
                        throw new WebException(400, label + " item " + (index + 1) + " must be " + field.arrayItemType() + ".");
                    }
                }
            }
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "string", "date" -> value.isTextual();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    private List<Map<String, Object>> resolveFields(CatalogType type, Map<String, CatalogType> types, Set<String> seen) {
        if (!seen.add(type.id())) return List.of();
        LinkedHashMap<String, Map<String, Object>> fields = new LinkedHashMap<>();
        if (type.extendsId() != null && types.containsKey(type.extendsId())) {
            for (Map<String, Object> inherited : resolveFields(types.get(type.extendsId()), types, seen)) {
                Map<String, Object> copy = new LinkedHashMap<>(inherited);
                copy.put("inherited", true);
                fields.put(((CatalogField) copy.get("field")).name(), copy);
            }
        }
        for (CatalogField field : safe(type.fields())) {
            fields.put(field.name(), new LinkedHashMap<>(Map.of("field", field, "originTypeId", type.id(), "inherited", false)));
        }
        return new ArrayList<>(fields.values());
    }

    private CatalogType parseType(JsonNode body, String id) {
        List<CatalogField> fields = new ArrayList<>();
        for (JsonNode field : iterable(body.path("fields"))) fields.add(new CatalogField(
                field.path("name").asText(""), field.path("type").asText("string"), field.path("required").asBoolean(false),
                field.path("defaultValue").asText(""), field.path("visibility").asText("public"), field.path("validationRules").asText(""),
                textOrNull(field.path("referenceTypeId")), textOrNull(field.path("arrayItemType"))));
        List<CatalogMethod> methods = new ArrayList<>();
        for (JsonNode method : iterable(body.path("methods"))) methods.add(new CatalogMethod(
                method.path("name").asText(""), method.path("description").asText(""), method.path("linkedRequest").asText(""),
                method.path("inputMapping").asText(""), method.path("outputMapping").asText(""), method.path("returnType").asText("")));
        return new CatalogType(id, body.path("name").asText("New Type").trim(), body.path("kind").asText("class"),
                textOrNull(body.path("extendsId")), body.path("description").asText(""), body.path("category").asText("General"),
                textList(body.path("implementedBy")), fields, methods, textList(body.path("linkedCollections")), textList(body.path("linkedRequests")));
    }

    private CatalogRecord parseRecord(JsonNode body, String id) {
        JsonNode values = body.path("values");
        if (values.isMissingNode()) values = mapper.createObjectNode();
        return new CatalogRecord(id, body.path("name").asText("New Record").trim(), body.path("typeId").asText(""), values.deepCopy());
    }

    private List<Entity<CatalogType>> typeEntities() throws IOException { return entities(TYPES, CatalogType.class); }
    private List<Entity<CatalogRecord>> recordEntities() throws IOException { return entities(RECORDS, CatalogRecord.class); }

    private <T> List<Entity<T>> entities(String prefix, Class<T> type) throws IOException {
        List<Entity<T>> result = new ArrayList<>();
        for (WorkspaceFiles.Entry entry : files.list()) {
            if (!entry.directory() && entry.path().startsWith(prefix) && entry.path().endsWith(".json")) {
                WorkspaceFiles.Document document = files.read(entry.path());
                try { result.add(new Entity<>(mapper.readValue(document.content(), type), document)); }
                catch (Exception error) { throw new WebException(400, "Cannot read " + entry.path() + ": " + error.getMessage()); }
            }
        }
        return result;
    }

    private Entity<CatalogType> findType(String id) throws IOException {
        return typeEntities().stream().filter(entity -> entity.value().id().equals(id)).findFirst()
                .orElseThrow(() -> new WebException(404, "Catalog type not found."));
    }

    private Entity<CatalogRecord> findRecord(String id) throws IOException {
        return recordEntities().stream().filter(entity -> entity.value().id().equals(id)).findFirst()
                .orElseThrow(() -> new WebException(404, "Catalog record not found."));
    }

    private Map<String, CatalogType> typesById() throws IOException {
        Map<String, CatalogType> result = new LinkedHashMap<>();
        for (Entity<CatalogType> entity : typeEntities()) result.put(entity.value().id(), entity.value());
        return result;
    }

    private <T> Map<String, Object> withMeta(T value, WorkspaceFiles.Document document) {
        Map<String, Object> result = mapper.convertValue(value, LinkedHashMap.class);
        result.put("path", document.path());
        result.put("revision", document.revision());
        return result;
    }

    private Map<String, Object> noteMetadata(WorkspaceFiles.Document document) {
        Map<String, String> frontmatter = frontmatter(document.content());
        Matcher heading = HEADING.matcher(document.content());
        String filename = Path.of(document.path()).getFileName().toString().replaceFirst("\\.md$", "");
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", frontmatter.getOrDefault("id", document.path()));
        note.put("path", document.path());
        note.put("title", frontmatter.getOrDefault("title", heading.find() ? heading.group(1).trim() : filename));
        note.put("category", frontmatter.getOrDefault("category", "Procedure"));
        note.put("tags", parseYamlList(frontmatter.get("tags")));
        note.put("revision", document.revision());
        return note;
    }

    private List<Map<String, Object>> resolvedLinks(String content) throws IOException {
        List<Map<String, Object>> notes = listNotes();
        List<Map<String, Object>> links = new ArrayList<>();
        for (String target : wikiTargets(content)) {
            List<Map<String, Object>> matches = notes.stream().filter(note -> noteMatches(note, target)).toList();
            links.add(Map.of("target", target, "matches", matches.stream().map(note -> note.get("path")).toList(), "exists", !matches.isEmpty()));
        }
        return links;
    }

    private List<Map<String, Object>> backlinks(String path) throws IOException {
        Map<String, Object> target = noteMetadata(files.read(path));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> note : listNotes()) {
            if (path.equals(note.get("path"))) continue;
            String content = files.read(Objects.toString(note.get("path"))).content();
            if (wikiTargets(content).stream().anyMatch(link -> noteMatches(target, link))) result.add(note);
        }
        return result;
    }

    private List<String> wikiTargets(String content) {
        String withoutCode = content.replaceAll("(?s)```.*?```", "");
        List<String> result = new ArrayList<>();
        Matcher matcher = WIKI_LINK.matcher(withoutCode);
        while (matcher.find()) result.add(matcher.group(1).trim());
        return result;
    }

    private boolean noteMatches(Map<String, Object> note, String target) {
        String normalized = target.toLowerCase(Locale.ROOT).replace('\\', '/').replaceFirst("\\.md$", "");
        String path = Objects.toString(note.get("path"), "").toLowerCase(Locale.ROOT).replaceFirst("\\.md$", "");
        String filename = Path.of(path).getFileName().toString();
        return Objects.toString(note.get("title"), "").equalsIgnoreCase(target) || path.equals(normalized) || filename.equals(normalized);
    }

    private Map<String, String> frontmatter(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!content.startsWith("---\n")) return values;
        int end = content.indexOf("\n---", 4);
        if (end < 0) return values;
        for (String line : content.substring(4, end).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim().replaceAll("^['\"]|['\"]$", ""));
        }
        return values;
    }

    private List<String> parseYamlList(String value) {
        if (value == null || value.isBlank() || value.equals("[]")) return List.of();
        String clean = value.replaceAll("^\\[|]$", "");
        List<String> values = new ArrayList<>();
        for (String part : clean.split(",")) if (!part.trim().isEmpty()) values.add(part.trim().replaceAll("^['\"]|['\"]$", ""));
        return values;
    }

    private String uniqueNotePath(String title) throws IOException {
        String slug = title.replaceAll("[^\\p{L}\\p{N}._ ()-]", "").trim();
        if (slug.isEmpty()) slug = "Untitled OPD";
        String path = OPDS + slug + ".md";
        int suffix = 2;
        while (Files.exists(files.resolve(path))) path = OPDS + slug + " " + suffix++ + ".md";
        return path;
    }

    private void requireNotePath(String path) {
        if (path == null || !path.startsWith(OPDS) || !path.endsWith(".md")) throw new WebException(400, "OPDs must be Markdown files inside documents/opds.");
        files.resolve(path);
    }

    private void migrateLegacyCatalogs() throws IOException {
        Path legacy = files.root().resolve(".web-state/catalogs");
        if (!Files.isDirectory(legacy)) return;
        Path legacyDocuments = legacy.resolve("documents");
        Set<String> migratedNoteIds = new HashSet<>();
        for (Map<String, Object> note : listNotes()) migratedNoteIds.add(Objects.toString(note.get("id")));
        if (Files.isDirectory(legacyDocuments)) try (var paths = Files.list(legacyDocuments)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                JsonNode old = mapper.readTree(path.toFile());
                String id = old.path("id").asText(UUID.randomUUID().toString());
                if (migratedNoteIds.contains(id)) continue;
                String title = old.path("title").asText("Imported OPD");
                String destination = uniqueNotePath(title);
                String content = "---\nid: " + id + "\ntitle: " + yamlText(title)
                        + "\ncategory: " + yamlText(old.path("category").asText("Procedure")) + "\ntags: " + old.path("tags") + "\n---\n\n" + old.path("content").asText("");
                files.save(destination, content, null);
                migratedNoteIds.add(id);
            }
        }
        Path legacyClasses = legacy.resolve("classes");
        if (Files.isDirectory(legacyClasses)) try (var paths = Files.list(legacyClasses)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                ObjectNode old = (ObjectNode) mapper.readTree(path.toFile());
                String id = old.path("id").asText(UUID.randomUUID().toString());
                String destination = TYPES + id + ".json";
                if (Files.exists(files.resolve(destination))) continue;
                old.put("kind", old.path("isInterface").asBoolean(false) ? "interface" : "class");
                JsonNode properties = old.path("properties");
                if (properties.isArray()) for (JsonNode property : properties) if (property instanceof ObjectNode field) {
                    String composedOf = field.path("composedOf").asText("");
                    if (!composedOf.isBlank()) field.put("referenceTypeId", composedOf);
                    field.putNull("arrayItemType");
                    field.remove("composedOf");
                }
                old.set("fields", properties);
                old.remove(List.of("isInterface", "properties"));
                files.save(destination, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(old), null);
            }
        }
    }

    private String pretty(Object value) throws IOException { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"; }
    private static String yamlText(String value) { return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; }
    private static String required(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw new WebException(400, "Missing text field: " + key);
        return node.get(key).asText();
    }
    private static String textOrNull(JsonNode node) { return node.isTextual() && !node.asText().isBlank() ? node.asText() : null; }
    private static List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode item : node) if (item.isTextual() && !item.asText().isBlank()) result.add(item.asText());
        return result;
    }
    private static Iterable<JsonNode> iterable(JsonNode node) { return node != null && node.isArray() ? node : List.of(); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static boolean contains(Object value, String needle) { return needle.isBlank() || Objects.toString(value, "").toLowerCase(Locale.ROOT).contains(needle); }
    private static void writeHeader(Sheet sheet, String... values) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
    }
    private record Entity<T>(T value, WorkspaceFiles.Document document) {}
}
