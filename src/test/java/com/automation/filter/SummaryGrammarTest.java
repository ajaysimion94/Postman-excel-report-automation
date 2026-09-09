package com.automation.filter;

import com.automation.postman.PostmanCollectionParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SummaryGrammarTest {
    private FilterSpec parse(String source) {
        return FilterQueryParser.parseSource(source, Path.of("editor.filter"), null);
    }

    @Test void explicitSummaryProducesExistingMetricsAndNewParagraphs() {
        FilterSpec spec = parse("""
                $ITEMS = FILTER "List items" WHERE id > 0;
                SUMMARY {
                  TITLE "Items";
                  METRIC "Count" = $ITEMS;
                  FIELD "Environment" = "Local";
                  PARAGRAPH $ITEMS + " items are available.";
                };
                """);
        assertEquals(4, spec.summary().items().size());
        assertInstanceOf(SummaryItem.KeyValue.class, spec.summary().items().get(1));
        assertInstanceOf(SummaryItem.LabelValue.class, spec.summary().items().get(2));
        assertInstanceOf(SummaryItem.Paragraph.class, spec.summary().items().get(3));
    }

    @Test void acceptsLegacyStatementsAndSummaryBlockTogether() {
        FilterSpec spec = parse("TITLE \"Old heading\"; KV \"Old value\" \"Yes\"; SUMMARY { PARAGRAPH \"New text\"; } METRICS;");
        assertEquals(4, spec.summary().items().size());
    }

    @Test void rejectsUnclosedBlocksMissingEqualsAndNonSummaryStatements() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("SUMMARY { TITLE \"X\";")).getMessage().contains("close the SUMMARY"));
        assertThrows(IllegalArgumentException.class, () -> parse("SUMMARY { METRIC \"X\" \"Y\"; }"));
        assertThrows(IllegalArgumentException.class, () -> parse("SUMMARY { COLLECTION other; }"));
        assertThrows(IllegalArgumentException.class, () -> parse("SUMMARY { SUMMARY { METRICS; } }"));
    }

    @Test void validatesVariablesInsideParagraphConditions() throws Exception {
        FilterSpec spec = parse("SUMMARY { PARAGRAPH IF $MISSING = 1 THEN \"One\" ELSE \"Many\"; }");
        Path collection = Path.of("collections/reqres.json");
        assertThrows(IllegalArgumentException.class, () -> FilterValidator.validate(spec,
                new PostmanCollectionParser().parse(collection), collection));
    }

    @Test void bundledReqresReportValidatesAgainstItsActualCollection() throws Exception {
        Path collection = Path.of("collections/reqres.json");
        FilterSpec spec = FilterQueryParser.parse(Path.of("filters/reqres.filter"));
        assertDoesNotThrow(() -> FilterValidator.validate(spec, new PostmanCollectionParser().parse(collection), collection));
        assertEquals(2, spec.requests().size());
        assertEquals(1, spec.summary().items().stream().filter(SummaryItem.Paragraph.class::isInstance).count());
    }

    @Test void everyBundledReportFilterValidatesAgainstItsCollection() throws Exception {
        assertBundledFilterValid("filters/pokeapi-open.filter", "collections/pokeapi-open.json");
        assertBundledFilterValid("filters/reqres.filter", "collections/reqres.json");
    }

    private void assertBundledFilterValid(String filterPath, String collectionPath) throws Exception {
        Path collection = Path.of(collectionPath);
        FilterSpec spec = FilterQueryParser.parse(Path.of(filterPath));
        assertDoesNotThrow(() -> FilterValidator.validate(
                spec, new PostmanCollectionParser().parse(collection), collection));
    }

    @Test void bracesInStringsAndCommentsAreNotTreatedAsBlocks() {
        FilterSpec spec = parse("SUMMARY {\n # closing } in a comment\n PARAGRAPH \"Use {value} in your text.\"; }");
        SummaryItem.Paragraph paragraph = (SummaryItem.Paragraph) spec.summary().items().get(0);
        assertEquals("Use {value} in your text.", ((SummaryTextPart.Literal) paragraph.parts().get(0)).value());
    }
}
