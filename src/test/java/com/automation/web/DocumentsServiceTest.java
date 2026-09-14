package com.automation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentsServiceTest {
    @TempDir Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void storesMarkdownNotesAndBuildsBacklinksWithoutIndexingCodeBlocks() throws Exception {
        DocumentsService service = service();
        Map<String, Object> procedure = service.createNote(mapper.valueToTree(Map.of("title", "Release procedure")));
        Map<String, Object> checklist = service.createNote(mapper.valueToTree(Map.of("title", "Release checklist")));

        String content = "# Release checklist\n\nFollow [[Release procedure|the procedure]].\n\n```md\n[[Ignored code link]]\n```\n";
        service.saveNote(mapper.valueToTree(Map.of("path", checklist.get("path"), "content", content, "revision", checklist.get("revision"))));

        Map<String, Object> reopened = service.getNote(procedure.get("path").toString());
        List<?> backlinks = (List<?>) reopened.get("backlinks");
        assertEquals(1, backlinks.size());
        assertEquals("Release checklist", ((Map<?, ?>) backlinks.get(0)).get("title"));
        assertTrue(Files.readString(workspace.resolve(checklist.get("path").toString())).contains("[[Release procedure|the procedure]]"));
        assertEquals(2, service.listNotes().size());
    }

    @Test void rejectsAnAutosaveWhenTheMarkdownFileChangedOutsideTheApp() throws Exception {
        DocumentsService service = service();
        Map<String, Object> note = service.createNote(mapper.valueToTree(Map.of("title", "Recovery")));
        Files.writeString(workspace.resolve(note.get("path").toString()), "# Changed outside\n");

        WebException conflict = assertThrows(WebException.class, () -> service.saveNote(mapper.valueToTree(Map.of(
                "path", note.get("path"), "content", "# Browser draft\n", "revision", note.get("revision")))));
        assertEquals(409, conflict.status);
        assertEquals("# Changed outside\n", Files.readString(workspace.resolve(note.get("path").toString())));
    }

    @Test void validatesInheritanceAndTypedNestedRecords() throws Exception {
        DocumentsService service = service();
        Map<String, Object> base = service.createType(mapper.valueToTree(Map.of(
                "name", "Named entity", "kind", "interface", "fields", List.of(Map.of(
                        "name", "name", "type", "string", "required", true)), "methods", List.of())));
        Map<String, Object> address = service.createType(mapper.valueToTree(Map.of(
                "name", "Address", "kind", "class", "fields", List.of(Map.of(
                        "name", "city", "type", "string", "required", true)), "methods", List.of())));
        Map<String, Object> person = service.createType(mapper.valueToTree(Map.of(
                "name", "Person", "kind", "class", "extendsId", base.get("id"), "fields", List.of(Map.of(
                        "name", "address", "type", "object", "required", true, "referenceTypeId", address.get("id"))), "methods", List.of())));

        Map<String, Object> view = service.getType(person.get("id").toString());
        assertEquals(2, ((List<?>) view.get("resolvedFields")).size());
        Map<String, Object> record = service.createRecord(mapper.valueToTree(Map.of(
                "name", "Ada", "typeId", person.get("id"), "values", Map.of("name", "Ada", "address", Map.of("city", "London")))));
        assertEquals("Ada", record.get("name"));

        WebException invalid = assertThrows(WebException.class, () -> service.createRecord(mapper.valueToTree(Map.of(
                "name", "Incomplete", "typeId", person.get("id"), "values", Map.of("name", "Incomplete")))));
        assertTrue(invalid.getMessage().contains("address"));
        WebException inUse = assertThrows(WebException.class, () -> service.deleteType(person.get("id").toString()));
        assertEquals(409, inUse.status);
        assertEquals(1, service.catalogSearch("Ada").get("count"));
    }

    @Test void rejectsInheritanceCyclesAndUnknownRelationships() throws Exception {
        DocumentsService service = service();
        Map<String, Object> first = service.createType(mapper.valueToTree(Map.of("name", "First", "kind", "class", "fields", List.of(), "methods", List.of())));
        Map<String, Object> second = service.createType(mapper.valueToTree(Map.of("name", "Second", "kind", "class", "extendsId", first.get("id"), "fields", List.of(), "methods", List.of())));
        Map<String, Object> update = new java.util.LinkedHashMap<>(first);
        update.put("extendsId", second.get("id"));
        WebException cycle = assertThrows(WebException.class, () -> service.updateType(mapper.valueToTree(update)));
        assertTrue(cycle.getMessage().contains("cycle"));
    }

    @Test void migratesEachLegacyOpdOnlyOnceAcrossRestarts() throws Exception {
        Path legacy = workspace.resolve(".web-state/catalogs/documents");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("release.json"), """
                {"id":"legacy-release","title":"Release OPD","category":"Operations","tags":["release"],"content":"# Steps"}
                """);

        assertEquals(1, service().listNotes().size());
        assertEquals(1, service().listNotes().size());
        try (var notes = Files.list(workspace.resolve("documents/opds"))) {
            assertEquals(1, notes.count());
        }
    }

    @Test void storesImportsAndExportsBrowserBookmarks() throws Exception {
        DocumentsService service = service();
        Map<String, Object> first = service.createBookmark(mapper.valueToTree(Map.of(
                "title", "Reqres", "url", "https://reqres.in/", "folder", "API references")));
        assertEquals(1, service.listBookmarks().size());
        assertTrue(service.exportBookmarksHtml().contains("https://reqres.in/"));

        Map<String, Object> updated = new java.util.LinkedHashMap<>(first);
        updated.put("title", "Reqres API");
        assertEquals("Reqres API", service.updateBookmark(mapper.valueToTree(updated)).get("title"));
        assertEquals(1, service.importBookmarks(mapper.valueToTree(Map.of("bookmarks", List.of(
                Map.of("title", "PokeAPI", "url", "https://pokeapi.co/", "folder", "API references"),
                Map.of("title", "Duplicate", "url", "https://reqres.in/", "folder", "API references"))))));
        assertEquals(2, service.listBookmarks().size());
        service.deleteBookmark(first.get("id").toString());
        assertEquals(1, service.listBookmarks().size());
    }

    private DocumentsService service() throws Exception {
        return new DocumentsService(new WorkspaceFiles(workspace), mapper);
    }
}
