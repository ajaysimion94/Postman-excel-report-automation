package com.automation.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QuickQueryTest {
    private FilterSpec parse(String source) {
        return FilterQueryParser.parseSource(source, Path.of("quick.filter"), null);
    }

    @Test void acceptsCommentsQuotedNamesAndIndependentAssignments() throws Exception {
        FilterSpec spec = parse("""
                # existing comment
                @"My collection" #"List items" > id AS "ID", "column n" where "column n" > 7; # trailing comment
                $young = @"My collection" #"List items" > name where age < 13;
                $older = @"My collection" #"List items" > age where age >= 13;
                SUMMARY { TABLE $young; METRIC "Older" = $older; }
                """);
        assertEquals("My collection", spec.collection());
        assertEquals(List.of("List items"), spec.requests());
        assertEquals(3, spec.summary().queries().size());
        var young = (SummaryQuerySource.QuickRows) spec.summary().queries().get("young").source();
        var older = (SummaryQuerySource.QuickRows) spec.summary().queries().get("older").source();
        var root = new ObjectMapper().readTree("[{\"name\":\"Asha\",\"age\":12},{\"name\":\"Ben\",\"age\":14}]");
        assertEquals("Asha", QuickQueryEvaluator.evaluate(root, young, Map.of()).get(0).get("name").asText());
        assertFalse(QuickQueryEvaluator.evaluate(root, young, Map.of()).get(0).has("age"));
        assertEquals(14, QuickQueryEvaluator.evaluate(root, older, Map.of()).get(0).get("age").asInt());
    }

    @Test void traversesMultipleArraysAndKeepsFieldsOnTheSameStudent() throws Exception {
        var spec = parse("$students = @school #studentsinfo > name, age where School.class.students.student.age < 13 AND age > 10;");
        var query = (SummaryQuerySource.QuickRows) spec.summary().queries().get("students").source();
        var root = new ObjectMapper().readTree("""
                {"School":{"class":[
                  {"students":[{"student":{"name":"Asha","age":12}},{"student":{"name":"Ben","age":14}}]},
                  {"students":[{"student":{"name":"Mina","age":11}},{"student":{"name":"Lee","age":9}}]}
                ]}}
                """);
        var rows = QuickQueryEvaluator.evaluate(root, query, Map.of());
        assertEquals(List.of("Asha", "Mina"), rows.stream().map(row -> row.get("name").asText()).toList());
        assertEquals(List.of(12, 11), rows.stream().map(row -> row.get("age").asInt()).toList());
        assertEquals(2, rows.get(0).size());
        assertEquals(1, spec.summary().items().size());
    }

    @Test void handlesQualifiedProjectionWrappersOptionalWhereAndEmptyResults() throws Exception {
        var spec = parse("$rows = @demo #items > data.name;");
        var query = (SummaryQuerySource.QuickRows) spec.summary().queries().get("rows").source();
        assertEquals("Asha", QuickQueryEvaluator.evaluate(new ObjectMapper().readTree("{\"data\":[{\"name\":\"Asha\"}]}"), query, Map.of()).get(0).get("data.name").asText());
        assertTrue(QuickQueryEvaluator.evaluate(new ObjectMapper().readTree("{\"data\":[]}"), query, Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> parse("@demo #items > ;"));
        assertThrows(IllegalArgumentException.class, () -> parse("@demo #items > name"));
        assertThrows(IllegalArgumentException.class, () -> parse("$a = @demo #items > name; $a = @demo #items > age;"));
    }
}
