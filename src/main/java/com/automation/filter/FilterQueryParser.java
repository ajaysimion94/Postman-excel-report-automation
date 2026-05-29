package com.automation.filter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses lightweight SQL-like filter scripts (.filter) into {@link FilterSpec}.
 *
 * <p>Supported statements (semicolon-terminated):
 * <pre>
 *   COLLECTION <name>;
 *   OUTPUT_PREFIX <prefix>;
 *   SET OUTPUT_PREFIX <prefix>;
 *   REQUESTS <req1>, <req2>, ...;
 *   REQUEST <req>;                          // adds one request
 *   COLUMNS <request-or-*>: <col1>, <col2>;
 *   FILTER <request-or-*> WHERE <predicate>;
 *   DATE_CONFIG <request-or-*>.<field> FORMAT <pattern> [TIMEZONE <zone>];
 *   LOOKUP_TABLE <name> FROM <sourceRequest> LOOKUP <detailRequest> BY <sourceField>
 *     [WHERE <predicate>] [COLUMNS <col1>, <col2>, ...];
 * </pre>
 */
public final class FilterQueryParser {
    private FilterQueryParser() {
    }

    public static FilterSpec parse(Path filterPath) throws IOException {
        return parse(filterPath, null);
    }

    public static FilterSpec parse(Path filterPath, String preferredCollectionSelector) throws IOException {
        String raw = Files.readString(filterPath);
        TokenStream ts = new TokenStream(raw, filterPath);
        ParseState state = new ParseState(filterPath);

        while (!ts.peekType(TokenType.EOF)) {
            parseStatement(ts, state);
            ts.expectSymbol(";");
        }

        return state.resolve(preferredCollectionSelector);
    }

    private static void parseStatement(TokenStream ts, ParseState state) {
        Builder b = state.current;

        if (ts.matchKeyword("SET")) {
            ts.expectKeyword("OUTPUT_PREFIX");
            b.outputPrefix = ts.readValue();
            return;
        }

        if (ts.matchKeyword("COLLECTION")) {
            state.switchCollection(ts.readValue());
            return;
        }

        if (ts.matchKeyword("OUTPUT_PREFIX")) {
            b.outputPrefix = ts.readValue();
            return;
        }

        if (ts.matchKeyword("REQUESTS")) {
            b.requests.addAll(ts.readCommaSeparatedValues());
            return;
        }

        if (ts.matchKeyword("REQUEST")) {
            String requestName = ts.readValue();
            b.requests.add(requestName);
            if (ts.matchKeyword("WHERE")) {
                Expr expr = parseExpr(ts);
                b.rowFilters.put(requestName, compileWhere(expr, ts));
            }
            return;
        }

        if (ts.matchKeyword("COLUMNS")) {
            String requestKey = ts.readValue();
            ts.expectSymbol(":");
            b.responseColumns.put(requestKey, parseColumnList(ts));
            return;
        }

        if (ts.matchKeyword("FILTER")) {
            String requestKey = ts.readValue();
            ts.expectKeyword("WHERE");
            Expr expr = parseExpr(ts);
            b.rowFilters.put(requestKey, compileWhere(expr, ts));
            return;
        }

        if (ts.matchKeyword("DATE_CONFIG")) {
            String qualifiedField = ts.readIdentifierLike();
            int dot = qualifiedField.indexOf('.');
            if (dot <= 0 || dot == qualifiedField.length() - 1) {
                throw ts.error("DATE_CONFIG expects <request>.<field>");
            }
            String request = qualifiedField.substring(0, dot);
            String field = qualifiedField.substring(dot + 1);

            ts.expectKeyword("FORMAT");
            String format = ts.readValue();
            String timezone = null;
            if (ts.matchKeyword("TIMEZONE")) {
                timezone = ts.readValue();
            }

            b.dateConfig.computeIfAbsent(request, ignored -> new LinkedHashMap<>())
                    .put(field, new DateFieldConfig(format, timezone));
            return;
        }

        if (ts.matchKeyword("LOOKUP_TABLE")) {
            String tableName = ts.readValue();
            ts.expectKeyword("FROM");
            String sourceRequest = ts.readValue();
            ts.expectKeyword("LOOKUP");
            String lookupRequest = ts.readValue();
            ts.expectKeyword("BY");
            String lookupParam = ts.readIdentifierLike();
            String lookupVar = null;
            if (ts.matchKeyword("AS")) {
                lookupVar = ts.readIdentifierLike();
            }

            RowFilterGroup where = null;
            List<ColumnSpec> columns = null;
            while (!ts.peekSymbol(";")) {
                if (ts.matchKeyword("WHERE")) {
                    Expr expr = parseExpr(ts);
                    where = compileWhere(expr, ts);
                    continue;
                }
                if (ts.matchKeyword("COLUMNS")) {
                    columns = parseColumnList(ts);
                    continue;
                }
                throw ts.error("Unexpected token in LOOKUP_TABLE clause: '" + ts.peekText() + "'");
            }

            b.customTables.add(new CustomTableSpec(
                    tableName,
                    sourceRequest,
                    null,
                    null,
                    null,
                    lookupRequest,
                    lookupParam,
                    lookupVar,
                    columns,
                    where
            ));
            return;
        }

        if (ts.matchKeyword("SHAPE")) {
            String key = ts.readValue();
            DataShapeSpec existing = b.dataShapes.getOrDefault(key, new DataShapeSpec(false, List.of(), null, null, null, null, null));
            boolean distinct = existing.distinct();
            List<SortSpec> orderBy = new ArrayList<>(existing.orderBy() == null ? List.of() : existing.orderBy());
            Integer limit = existing.limit();
            Integer offset = existing.offset();
            List<String> groupBy = new ArrayList<>(existing.groupBy() == null ? List.of() : existing.groupBy());
            List<AggregateSpec> aggregates = new ArrayList<>(existing.aggregates() == null ? List.of() : existing.aggregates());
            RowFilterGroup having = existing.having();

            while (!ts.peekSymbol(";")) {
                if (ts.matchKeyword("DISTINCT")) {
                    distinct = true;
                    continue;
                }
                if (ts.matchKeyword("ORDER")) {
                    ts.expectKeyword("BY");
                    orderBy = parseOrderBy(ts);
                    continue;
                }
                if (ts.matchKeyword("LIMIT")) {
                    limit = ts.readInt();
                    continue;
                }
                if (ts.matchKeyword("OFFSET")) {
                    offset = ts.readInt();
                    continue;
                }
                if (ts.matchKeyword("GROUP")) {
                    ts.expectKeyword("BY");
                    groupBy = ts.readCommaSeparatedIdentifiers();
                    continue;
                }
                if (ts.matchKeyword("AGG")) {
                    aggregates = parseAggregates(ts);
                    continue;
                }
                if (ts.matchKeyword("HAVING")) {
                    Expr expr = parseExpr(ts);
                    having = compileWhere(expr, ts);
                    continue;
                }
                throw ts.error("Unexpected token in SHAPE clause: '" + ts.peekText() + "'");
            }

            b.dataShapes.put(key, new DataShapeSpec(
                    distinct,
                    List.copyOf(orderBy),
                    limit,
                    offset,
                    groupBy.isEmpty() ? null : List.copyOf(groupBy),
                    aggregates.isEmpty() ? null : List.copyOf(aggregates),
                    having
            ));
            return;
        }

        if (ts.matchKeyword("UNION")) {
            String name = ts.readValue();
            ts.expectKeyword("FROM");
            List<String> sources = ts.readCommaSeparatedValues();
            boolean all = ts.matchKeyword("ALL");
            b.unions.add(new UnionSpec(name, List.copyOf(sources), all));
            return;
        }

        if (ts.matchKeyword("EXPAND")) {
            String requestKey = ts.readValue();
            ts.expectKeyword("ON");
            String expandField = ts.readValue();
            String exceptionPrefix = "exceptions";
            if (ts.matchKeyword("AS")) {
                exceptionPrefix = ts.readIdentifierLike();
            }
            b.expands.put(requestKey, new ExpandSpec(expandField, exceptionPrefix));
            return;
        }

        if (ts.matchSymbol("$")) {
            parseSummaryDollarStatement(ts, b);
            return;
        }

        if (ts.matchKeyword("TEXT")) {
            b.summaryItems.add(new SummaryItem.Text(parseSummaryTextExpr(ts)));
            return;
        }

        if (ts.matchKeyword("TITLE")) {
            String text = ts.readValue();
            String color = readOptionalColor(ts);
            b.summaryItems.add(new SummaryItem.Title(text, color));
            return;
        }

        if (ts.matchKeyword("DESCRIPTION")) {
            String text = ts.readValue();
            String color = readOptionalColor(ts);
            b.summaryItems.add(new SummaryItem.Description(text, color));
            return;
        }

        if (ts.matchKeyword("TABLE")) {
            parseSummaryTableItem(ts, b, readSummaryVariableName(ts));
            return;
        }

        if (ts.matchKeyword("KV")) {
            String label = ts.readValue();
            b.summaryItems.add(new SummaryItem.KeyValue(label, parseSummaryTextExpr(ts)));
            return;
        }

        if (ts.matchKeyword("LV")) {
            String label = ts.readValue();
            b.summaryItems.add(new SummaryItem.LabelValue(label, parseSummaryTextExpr(ts)));
            return;
        }

        if (ts.matchKeyword("LABEL_TABLE")) {
            String title = null;
            if (ts.peekType(TokenType.STRING) || (ts.peekType(TokenType.IDENT) && !ts.peekText().equalsIgnoreCase("HEADERS") && !ts.peekText().equalsIgnoreCase("ROW"))) {
                title = ts.readValue();
            }
            List<String> headers = null;
            boolean explicitHeaders = ts.matchKeyword("HEADERS");
            if (explicitHeaders) {
                headers = ts.readCommaSeparatedValues();
            }
            List<SummaryItem.InlineTableRow> rows = new ArrayList<>();
            while (ts.matchKeyword("ROW")) {
                String rowLabel = ts.readValue();
                rows.add(new SummaryItem.InlineTableRow(rowLabel, parseSummaryTextExpr(ts)));
            }
            b.summaryItems.add(new SummaryItem.QuickTable(title, headers, rows));
            return;
        }

        if (ts.matchKeyword("QT") || ts.matchKeyword("QUICK_TABLE")) {
            String title = null;
            if (ts.peekType(TokenType.STRING) || (ts.peekType(TokenType.IDENT) && !ts.peekText().equalsIgnoreCase("HEADERS") && !ts.peekText().equalsIgnoreCase("ROW") && !ts.peekText().equalsIgnoreCase("COLUMNS"))) {
                title = ts.readValue();
            }
            List<String> headers = null;
            if (ts.matchKeyword("HEADERS") || ts.matchKeyword("COLUMNS")) {
                headers = ts.readCommaSeparatedValues();
            }
            List<SummaryItem.InlineTableRow> rows = new ArrayList<>();
            while (ts.matchKeyword("ROW")) {
                List<List<SummaryTextPart>> columnParts = parseMultiColumnRow(ts, headers);
                if (columnParts != null) {
                    // Multi-column mode: each column has its own text parts
                    String rowLabel = columnParts.isEmpty() ? "" : renderFirstColumnLabel(columnParts.get(0));
                    List<SummaryTextPart> valueParts = columnParts.size() > 1 ? columnParts.get(1) : List.of();
                    rows.add(new SummaryItem.InlineTableRow(rowLabel, valueParts, columnParts));
                } else {
                    // Classic 2-column mode: label + value expression
                    String rowLabel = ts.readValue();
                    rows.add(new SummaryItem.InlineTableRow(rowLabel, parseSummaryTextExpr(ts)));
                }
            }
            if (headers == null) {
                b.summaryItems.add(new SummaryItem.QuickTable(title, rows));
            } else {
                b.summaryItems.add(new SummaryItem.QuickTable(title, headers, rows));
            }
            return;
        }

        if (ts.matchKeyword("METRICS")) {
            b.summaryItems.add(new SummaryItem.Metrics());
            return;
        }

        if (ts.matchKeyword("STATUS")) {
            String color = readOptionalColor(ts);
            b.summaryItems.add(new SummaryItem.Status(color));
            return;
        }

        throw ts.error("Unknown statement. Supported: COLLECTION, OUTPUT_PREFIX, REQUESTS, REQUEST, COLUMNS, FILTER, DATE_CONFIG, LOOKUP_TABLE, SHAPE, UNION, EXPAND, TITLE, DESCRIPTION, TEXT, KV, LV, TABLE, LABEL_TABLE, QT, QUICK_TABLE, METRICS, STATUS, $var = FILTER ..., $var;");
    }

    private static void parseSummaryDollarStatement(TokenStream ts, Builder b) {
        String varName = ts.readIdentifierLike();
        if (ts.matchSymbol("=")) {
            if (b.summaryQueries.containsKey(varName)) {
                throw ts.error("Duplicate summary variable: $" + varName);
            }
            if (ts.matchKeyword("FILTER")) {
                String requestKey = ts.readValue();
                ts.expectKeyword("WHERE");
                Expr expr = parseExpr(ts);
                RowFilterGroup filter = compileWhere(expr, ts);
                b.summaryQueries.put(varName, new SummaryQuerySpec(varName,
                        new SummaryQuerySource.FilterRows(requestKey, filter)));
                return;
            }
            if (ts.matchKeyword("LOOKUP_TABLE") || ts.matchKeyword("TABLE")) {
                String tableName = ts.readValue();
                b.summaryQueries.put(varName, new SummaryQuerySpec(varName,
                        new SummaryQuerySource.NamedTable(tableName)));
                return;
            }
            throw ts.error("Expected FILTER or TABLE after $name =");
        }
        parseSummaryTableItem(ts, b, varName);
    }

    private static void parseSummaryTableItem(TokenStream ts, Builder b, String varName) {
        String title = null;
        List<ColumnSpec> columns = null;
        while (!ts.peekSymbol(";")) {
            if (ts.matchKeyword("TITLE")) {
                title = ts.readValue();
                continue;
            }
            if (ts.matchKeyword("COLUMNS")) {
                columns = parseColumnList(ts);
                continue;
            }
            throw ts.error("Unexpected token in TABLE clause: '" + ts.peekText() + "'");
        }
        b.summaryItems.add(new SummaryItem.Table(varName, title, columns));
    }

    private static String readSummaryVariableName(TokenStream ts) {
        if (ts.matchSymbol("$")) {
            return ts.readIdentifierLike();
        }
        throw ts.error("Expected summary variable name like $posts");
    }

    /**
     * Parses a multi-column ROW when the QuickTable has explicit HEADERS.
     * Each column value is a text expression (literals + variables joined by +).
     * Column values are separated by commas.
     * Returns null when the next token is a plain string (classic 2-column mode).
     */
    private static List<List<SummaryTextPart>> parseMultiColumnRow(TokenStream ts, List<String> headers) {
        if (headers == null || headers.size() <= 2) {
            // For 0, 1, or 2 headers, use classic label+value syntax unless
            // the row starts with an expression (no plain label string).
            // Peek: if next is a string value followed by + or $, it might be multi-col.
            return null;
        }
        // N-column mode: each column is a text expression, separated by commas.
        List<List<SummaryTextPart>> columns = new ArrayList<>();
        columns.add(parseSummaryTextExpr(ts));
        while (ts.matchSymbol(",")) {
            columns.add(parseSummaryTextExpr(ts));
        }
        return columns;
    }

    /** Extracts a plain-text label from the first column's text parts if it's a simple literal. */
    private static String renderFirstColumnLabel(List<SummaryTextPart> parts) {
        if (parts != null && parts.size() == 1 && parts.get(0) instanceof SummaryTextPart.Literal lit) {
            return lit.value();
        }
        return "";
    }

    private static String readOptionalColor(TokenStream ts) {
        if (ts.matchKeyword("COLOR")) {
            // Accept both quoted strings (hex colors like "#FF5500") and identifiers (named colors)
            if (ts.peekType(TokenType.STRING)) {
                return ts.readValue();
            }
            return ts.readIdentifierLike();
        }
        return null;
    }

    private static List<ColumnSpec> parseColumnList(TokenStream ts) {
        List<ColumnSpec> out = new ArrayList<>();
        out.add(readColumnSpec(ts));
        while (ts.matchSymbol(",")) {
            out.add(readColumnSpec(ts));
        }
        return List.copyOf(out);
    }

    private static ColumnSpec readColumnSpec(TokenStream ts) {
        String field = ts.readIdentifierLike();
        String label = null;
        if (ts.matchKeyword("AS")) {
            label = ts.readValue();
        }
        return new ColumnSpec(field, label);
    }

    private static List<SummaryTextPart> parseSummaryTextExpr(TokenStream ts) {
        List<SummaryTextPart> parts = new ArrayList<>();
        parts.add(readSummaryTextPart(ts));
        while (ts.matchSymbol("+")) {
            parts.add(readSummaryTextPart(ts));
        }
        return List.copyOf(parts);
    }

    private static SummaryTextPart readSummaryTextPart(TokenStream ts) {
        // IF/ELSE conditional inside a text expression
        if (ts.matchKeyword("IF")) {
            return parseSummaryIfElse(ts);
        }
        if (ts.matchSymbol("$")) {
            return new SummaryTextPart.Variable(ts.readIdentifierLike());
        }
        return new SummaryTextPart.Literal(ts.readValue());
    }

    /**
     * Parses a summary IF/ELSE conditional inside a text expression.
     * Syntax: {@code IF $variable op value THEN textExpr [ELSE textExpr]}
     * Where {@code op} is =, !=, &gt;, &gt;=, &lt;, &lt;=.
     * The THEN/ELSE branches are full text expressions (can use +, $vars, nested IF).
     *
     * <p>Examples:
     * <pre>
     *   IF $count > 0 THEN $count + " found" ELSE "none found"
     *   IF $success = true THEN "All passed" ELSE "Some failed"
     * </pre>
     */
    private static SummaryTextPart.IfElse parseSummaryIfElse(TokenStream ts) {
        // Condition: $variable op value
        if (!ts.matchSymbol("$")) {
            throw ts.error("Expected $variable after IF in summary expression");
        }
        String variableName = ts.readIdentifierLike();
        String op = ts.readSymbolOp();
        String value = ts.readValue();

        ts.expectKeyword("THEN");
        List<SummaryTextPart> thenParts = parseSummaryTextExpr(ts);

        List<SummaryTextPart> elseParts = List.of();
        if (ts.matchKeyword("ELSE")) {
            elseParts = parseSummaryTextExpr(ts);
        }

        return new SummaryTextPart.IfElse(variableName, op, value, thenParts, elseParts);
    }

    private static List<SortSpec> parseOrderBy(TokenStream ts) {
        List<SortSpec> terms = new ArrayList<>();
        while (true) {
            String field = ts.readIdentifierLike();
            boolean desc = false;
            if (ts.matchKeyword("DESC")) {
                desc = true;
            } else {
                ts.matchKeyword("ASC");
            }
            terms.add(new SortSpec(field, desc));
            if (!ts.matchSymbol(",")) {
                break;
            }
        }
        return terms;
    }

    private static List<AggregateSpec> parseAggregates(TokenStream ts) {
        List<AggregateSpec> terms = new ArrayList<>();
        while (true) {
            String function = ts.readIdentifierLike();
            ts.expectSymbol("(");
            String field = ts.readIdentifierLike();
            ts.expectSymbol(")");

            String alias;
            if (ts.matchKeyword("AS")) {
                alias = ts.readIdentifierLike();
            } else {
                alias = function.toLowerCase() + "_" + field.replace('*', 'a');
            }

            terms.add(new AggregateSpec(function.toUpperCase(), field, alias));
            if (!ts.matchSymbol(",")) {
                break;
            }
        }
        return terms;
    }

    private static RowFilterGroup compileWhere(Expr expr, TokenStream ts) {
        List<RowFilterRule> rules = new ArrayList<>();
        collectRules(expr, rules, ts);
        return new RowFilterGroup(null, rules, toExpression(expr, ts));
    }

    private static void collectRules(Expr expr, List<RowFilterRule> out, TokenStream ts) {
        if (expr instanceof PredicateExpr predicateExpr) {
            out.add(predicateExpr.rule);
            return;
        }
        if (expr instanceof BinaryExpr binary) {
            collectRules(binary.left, out, ts);
            collectRules(binary.right, out, ts);
            return;
        }
        if (expr instanceof NotExpr notExpr) {
            collectRules(notExpr.target(), out, ts);
            return;
        }
        if (expr instanceof IfElseExpr ifElse) {
            collectRules(ifElse.condition(), out, ts);
            collectRules(ifElse.thenExpr(), out, ts);
            if (ifElse.elseExpr() != null) {
                collectRules(ifElse.elseExpr(), out, ts);
            }
            return;
        }
        throw ts.error("Unsupported WHERE expression while collecting rules.");
    }

    private static RowFilterExpression toExpression(Expr expr, TokenStream ts) {
        if (expr instanceof PredicateExpr predicateExpr) {
            return new RowFilterExpression.Predicate(predicateExpr.rule);
        }
        if (expr instanceof BinaryExpr binary) {
            RowFilterExpression left = toExpression(binary.left, ts);
            RowFilterExpression right = toExpression(binary.right, ts);
            if (binary.operator == BinaryOp.AND) {
                return new RowFilterExpression.And(left, right);
            }
            return new RowFilterExpression.Or(left, right);
        }
        if (expr instanceof NotExpr notExpr) {
            return new RowFilterExpression.Not(toExpression(notExpr.target(), ts));
        }
        if (expr instanceof IfElseExpr ifElse) {
            RowFilterExpression condition = toExpression(ifElse.condition(), ts);
            RowFilterExpression thenExpr = toExpression(ifElse.thenExpr(), ts);
            RowFilterExpression elseExpr = ifElse.elseExpr() != null
                    ? toExpression(ifElse.elseExpr(), ts) : null;
            return new RowFilterExpression.IfElse(condition, thenExpr, elseExpr);
        }
        throw ts.error("Unsupported WHERE expression while building expression tree.");
    }

    private static Expr parseExpr(TokenStream ts) {
        return parseOr(ts);
    }

    private static Expr parseOr(TokenStream ts) {
        Expr left = parseAnd(ts);
        while (ts.matchKeyword("OR")) {
            left = new BinaryExpr(left, BinaryOp.OR, parseAnd(ts));
        }
        return left;
    }

    private static Expr parseAnd(TokenStream ts) {
        Expr left = parseUnary(ts);
        while (ts.matchKeyword("AND")) {
            left = new BinaryExpr(left, BinaryOp.AND, parseUnary(ts));
        }
        return left;
    }

    private static Expr parseUnary(TokenStream ts) {
        if (ts.matchKeyword("NOT")) {
            return new NotExpr(parseUnary(ts));
        }
        if (ts.matchKeyword("IF")) {
            return parseIfElse(ts);
        }
        if (ts.matchSymbol("(")) {
            Expr expr = parseExpr(ts);
            ts.expectSymbol(")");
            return expr;
        }
        return parsePredicate(ts);
    }

    /**
     * Parses an IF/ELSE conditional expression.
     * Syntax: IF &lt;predicate&gt; THEN ( &lt;expr&gt; ) [ELSE ( &lt;expr&gt; )]
     * The condition is a standard predicate (field op value).
     * THEN and ELSE branches are full sub-expressions (can contain AND/OR/NOT/IF).
     * Parentheses around the branches are optional but recommended.
     */
    private static Expr parseIfElse(TokenStream ts) {
        // Parse the condition: a standard predicate expression (can be parenthesized)
        Expr condition = parseOr(ts);
        ts.expectKeyword("THEN");
        Expr thenExpr;
        if (ts.matchSymbol("(")) {
            thenExpr = parseExpr(ts);
            ts.expectSymbol(")");
        } else {
            thenExpr = parseOr(ts);
        }
        Expr elseExpr = null;
        if (ts.matchKeyword("ELSE")) {
            if (ts.matchSymbol("(")) {
                elseExpr = parseExpr(ts);
                ts.expectSymbol(")");
            } else {
                elseExpr = parseOr(ts);
            }
        }
        return new IfElseExpr(condition, thenExpr, elseExpr);
    }

    private static Expr parsePredicate(TokenStream ts) {
        String field = ts.readIdentifierLike();

        if (ts.matchKeyword("IS")) {
            if (ts.matchKeyword("NOT")) {
                if (ts.matchKeyword("NULL")) return new PredicateExpr(new RowFilterRule(field, "IS_NOT_NULL", null, null, null));
                throw ts.error("Expected NULL after IS NOT");
            }
            if (ts.matchKeyword("NULL")) return new PredicateExpr(new RowFilterRule(field, "IS_NULL", null, null, null));
            if (ts.matchKeyword("TRUE")) return new PredicateExpr(new RowFilterRule(field, "IS_TRUE", null, null, null));
            if (ts.matchKeyword("FALSE")) return new PredicateExpr(new RowFilterRule(field, "IS_FALSE", null, null, null));
            throw ts.error("Expected NULL/TRUE/FALSE after IS");
        }

        if (ts.matchKeyword("NOT")) {
            if (ts.matchKeyword("IN")) {
                List<String> values = parseInValues(ts);
                return new PredicateExpr(new RowFilterRule(field, "NOT_IN", String.join(",", values), null, null));
            }
            if (ts.matchKeyword("LIKE") || ts.matchKeyword("ILIKE")) {
                String regex = sqlLikeToRegex(ts.readValue());
                return new PredicateExpr(new RowFilterRule(field, "REGEX", "(?i)^(?!" + regex + "$).*$", null, null));
            }
            throw ts.error("Expected IN or LIKE after NOT");
        }

        if (ts.matchKeyword("IN")) {
            List<String> values = parseInValues(ts);
            return new PredicateExpr(new RowFilterRule(field, "IN", String.join(",", values), null, null));
        }

        if (ts.matchKeyword("BETWEEN")) {
            String from = ts.readValue();
            ts.expectKeyword("AND");
            String to = ts.readValue();
            return new PredicateExpr(new RowFilterRule(field, "DATE_RANGE", null, from, to));
        }

        if (ts.matchKeyword("DATE_PRESET")) {
            return new PredicateExpr(new RowFilterRule(field, "DATE_PRESET", ts.readValue(), null, null));
        }

        if (ts.matchKeyword("CONTAINS")) {
            return new PredicateExpr(new RowFilterRule(field, "CONTAINS", ts.readValue(), null, null));
        }
        if (ts.matchKeyword("NOT_CONTAINS")) {
            return new PredicateExpr(new RowFilterRule(field, "NOT_CONTAINS", ts.readValue(), null, null));
        }
        if (ts.matchKeyword("STARTS_WITH")) {
            return new PredicateExpr(new RowFilterRule(field, "STARTS_WITH", ts.readValue(), null, null));
        }
        if (ts.matchKeyword("ENDS_WITH")) {
            return new PredicateExpr(new RowFilterRule(field, "ENDS_WITH", ts.readValue(), null, null));
        }
        if (ts.matchKeyword("REGEX")) {
            return new PredicateExpr(new RowFilterRule(field, "REGEX", ts.readValue(), null, null));
        }
        if (ts.matchKeyword("LIKE") || ts.matchKeyword("ILIKE")) {
            String regex = sqlLikeToRegex(ts.readValue());
            return new PredicateExpr(new RowFilterRule(field, "REGEX", "(?i)^" + regex + "$", null, null));
        }

        String symbol = ts.readSymbolOp();
        String value = ts.readValue();
        String mapped = switch (symbol) {
            case "=" -> "EQ";
            case "!=" -> "NEQ";
            case ">" -> "GT";
            case ">=" -> "GTE";
            case "<" -> "LT";
            case "<=" -> "LTE";
            default -> throw ts.error("Unsupported operator: " + symbol);
        };
        return new PredicateExpr(new RowFilterRule(field, mapped, value, null, null));
    }

    private static List<String> parseInValues(TokenStream ts) {
        ts.expectSymbol("(");
        List<String> values = ts.readCommaSeparatedValues();
        ts.expectSymbol(")");
        return values;
    }

    private static String sqlLikeToRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') {
                sb.append(".*");
            } else if (c == '_') {
                sb.append('.');
            } else {
                if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class Builder {
        private String collection;
        private List<String> requests = new ArrayList<>();
        private Map<String, List<ColumnSpec>> responseColumns = new LinkedHashMap<>();
        private String outputPrefix;
        private Map<String, RowFilterGroup> rowFilters = new LinkedHashMap<>();
        private Map<String, Map<String, DateFieldConfig>> dateConfig = new LinkedHashMap<>();
        private List<CustomTableSpec> customTables = new ArrayList<>();
        private Map<String, DataShapeSpec> dataShapes = new LinkedHashMap<>();
        private List<UnionSpec> unions = new ArrayList<>();
        private Map<String, ExpandSpec> expands = new LinkedHashMap<>();
        private List<SummaryItem> summaryItems = new ArrayList<>();
        private Map<String, SummaryQuerySpec> summaryQueries = new LinkedHashMap<>();

        private FilterSpec build() {
            SummarySpec summary = null;
            if (!summaryItems.isEmpty() || !summaryQueries.isEmpty()) {
                summary = new SummarySpec(
                        summaryItems.isEmpty() ? List.of() : List.copyOf(summaryItems),
                        summaryQueries.isEmpty() ? Map.of() : Map.copyOf(summaryQueries));
            }
            return new FilterSpec(
                    collection,
                    requests.isEmpty() ? null : List.copyOf(requests),
                    responseColumns.isEmpty() ? null : Map.copyOf(responseColumns),
                    outputPrefix,
                    null,
                    null,
                    rowFilters.isEmpty() ? null : Map.copyOf(rowFilters),
                    dateConfig.isEmpty() ? null : Map.copyOf(dateConfig),
                    customTables.isEmpty() ? null : List.copyOf(customTables),
                    dataShapes.isEmpty() ? null : Map.copyOf(dataShapes),
                    unions.isEmpty() ? null : List.copyOf(unions),
                    expands.isEmpty() ? null : Map.copyOf(expands),
                    summary
            );
        }
    }

    private static final class ParseState {
        private final Path source;
        private final Builder global = new Builder();
        private final Map<String, Builder> byCollection = new LinkedHashMap<>();
        private Builder current = global;

        private ParseState(Path source) {
            this.source = source;
        }

        private void switchCollection(String collectionName) {
            String key = normalizeCollectionName(collectionName);
            Builder b = byCollection.computeIfAbsent(key, ignored -> new Builder());
            if (b.collection == null) {
                b.collection = collectionName;
            }
            current = b;
        }

        private FilterSpec resolve(String preferredSelector) {
            if (byCollection.isEmpty()) {
                return global.build();
            }

            Builder selected;
            if (preferredSelector != null && !preferredSelector.isBlank()) {
                String key = normalizeCollectionName(preferredSelector);
                selected = byCollection.get(key);
                if (selected == null) {
                    String available = String.join(", ", byCollection.values().stream().map(b -> b.collection).toList());
                    throw new IllegalArgumentException(
                            source.getFileName() + ": collection selector \"" + preferredSelector +
                                    "\" does not match any COLLECTION block. Available: " + available);
                }
                return merge(global, selected).build();
            }

            if (byCollection.size() == 1) {
                selected = byCollection.values().iterator().next();
                return merge(global, selected).build();
            }

            String available = String.join(", ", byCollection.values().stream().map(b -> b.collection).toList());
            throw new IllegalArgumentException(
                    source.getFileName() + ": multiple COLLECTION blocks found (" + available +
                            "). Use --collection-name or --collection so the correct block can be selected.");
        }

        private static Builder merge(Builder global, Builder specific) {
            Builder out = new Builder();
            out.collection = specific.collection != null ? specific.collection : global.collection;

            LinkedHashSet<String> requests = new LinkedHashSet<>();
            requests.addAll(global.requests);
            requests.addAll(specific.requests);
            out.requests = new ArrayList<>(requests);

            out.responseColumns = new LinkedHashMap<>(global.responseColumns);
            out.responseColumns.putAll(specific.responseColumns);

            out.outputPrefix = specific.outputPrefix != null ? specific.outputPrefix : global.outputPrefix;

            out.rowFilters = new LinkedHashMap<>(global.rowFilters);
            out.rowFilters.putAll(specific.rowFilters);

            out.dateConfig = new LinkedHashMap<>(global.dateConfig);
            for (Map.Entry<String, Map<String, DateFieldConfig>> entry : specific.dateConfig.entrySet()) {
                Map<String, DateFieldConfig> merged = new LinkedHashMap<>(out.dateConfig.getOrDefault(entry.getKey(), Map.of()));
                merged.putAll(entry.getValue());
                out.dateConfig.put(entry.getKey(), merged);
            }

            out.customTables = new ArrayList<>(global.customTables);
            out.customTables.addAll(specific.customTables);

            out.dataShapes = new LinkedHashMap<>(global.dataShapes);
            out.dataShapes.putAll(specific.dataShapes);

            out.unions = new ArrayList<>(global.unions);
            out.unions.addAll(specific.unions);

            out.expands = new LinkedHashMap<>(global.expands);
            out.expands.putAll(specific.expands);

            out.summaryItems = new ArrayList<>(global.summaryItems);
            out.summaryItems.addAll(specific.summaryItems);

            out.summaryQueries = new LinkedHashMap<>(global.summaryQueries);
            out.summaryQueries.putAll(specific.summaryQueries);
            return out;
        }

        private static String normalizeCollectionName(String value) {
            if (value == null) {
                return "";
            }
            return value
                    .toLowerCase()
                    .replaceFirst("\\.json$", "")
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
        }
    }

    private sealed interface Expr permits BinaryExpr, NotExpr, PredicateExpr, IfElseExpr {
    }

    private record BinaryExpr(Expr left, BinaryOp operator, Expr right) implements Expr {
    }

    private enum BinaryOp {
        AND, OR
    }

    private record NotExpr(Expr target) implements Expr {
    }

    private record IfElseExpr(Expr condition, Expr thenExpr, Expr elseExpr) implements Expr {
    }

    private record PredicateExpr(RowFilterRule rule) implements Expr {
    }

    private enum TokenType {
        IDENT,
        STRING,
        SYMBOL,
        EOF
    }

    private record Token(TokenType type, String text, int line, int col) {
    }

    private static final class TokenStream {
        private static final Set<String> SYMBOLS = Set.of(";", ",", ":", "(", ")", "=", "!=", ">", ">=", "<", "<=", "+", "$");

        private final List<Token> tokens;
        private final Path source;
        private int index;

        TokenStream(String input, Path source) {
            this.source = source;
            this.tokens = tokenize(input);
            this.index = 0;
        }

        boolean peekType(TokenType type) {
            return peek().type == type;
        }

        boolean matchKeyword(String keyword) {
            Token t = peek();
            if (t.type == TokenType.IDENT && t.text.equalsIgnoreCase(keyword)) {
                index++;
                return true;
            }
            return false;
        }

        void expectKeyword(String keyword) {
            if (!matchKeyword(keyword)) {
                throw error("Expected keyword " + keyword + " but found '" + peek().text + "'");
            }
        }

        boolean matchSymbol(String symbol) {
            Token t = peek();
            if (t.type == TokenType.SYMBOL && t.text.equals(symbol)) {
                index++;
                return true;
            }
            return false;
        }

        boolean peekSymbol(String symbol) {
            Token t = peek();
            return t.type == TokenType.SYMBOL && t.text.equals(symbol);
        }

        String peekText() {
            return peek().text;
        }

        void expectSymbol(String symbol) {
            if (!matchSymbol(symbol)) {
                throw error("Expected symbol '" + symbol + "' but found '" + peek().text + "'");
            }
        }

        String readSymbolOp() {
            Token t = peek();
            if (t.type != TokenType.SYMBOL || !SYMBOLS.contains(t.text)) {
                throw error("Expected comparison operator but found '" + t.text + "'");
            }
            index++;
            return t.text;
        }

        String readIdentifierLike() {
            Token t = peek();
            if (t.type != TokenType.IDENT && t.type != TokenType.STRING) {
                throw error("Expected identifier but found '" + t.text + "'");
            }
            index++;
            return t.text;
        }

        String readValue() {
            Token t = peek();
            if (t.type != TokenType.IDENT && t.type != TokenType.STRING) {
                throw error("Expected value but found '" + t.text + "'");
            }
            index++;
            return t.text;
        }

        int readInt() {
            String value = readValue();
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw error("Expected integer but found '" + value + "'");
            }
        }

        List<String> readCommaSeparatedValues() {
            List<String> out = new ArrayList<>();
            out.add(readValue());
            while (matchSymbol(",")) {
                out.add(readValue());
            }
            return out;
        }

        List<String> readCommaSeparatedIdentifiers() {
            List<String> out = new ArrayList<>();
            out.add(readIdentifierLike());
            while (matchSymbol(",")) {
                out.add(readIdentifierLike());
            }
            return out;
        }

        IllegalArgumentException error(String message) {
            Token t = peek();
            String full = String.format(
                    "%s:%d:%d %s",
                    source.getFileName(),
                    t.line,
                    t.col,
                    message);
            return new IllegalArgumentException(full);
        }

        private Token peek() {
            return tokens.get(index);
        }

        private static List<Token> tokenize(String input) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            int line = 1;
            int col = 1;

            while (i < input.length()) {
                char ch = input.charAt(i);

                if (ch == '\n') {
                    i++;
                    line++;
                    col = 1;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    i++;
                    col++;
                    continue;
                }

                if (ch == '-' && i + 1 < input.length() && input.charAt(i + 1) == '-') {
                    while (i < input.length() && input.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (ch == '#') {
                    while (i < input.length() && input.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }

                int startCol = col;

                if (ch == '"' || ch == '\'') {
                    char quote = ch;
                    i++;
                    col++;
                    StringBuilder sb = new StringBuilder();
                    while (i < input.length() && input.charAt(i) != quote) {
                        char c = input.charAt(i);
                        if (c == '\\' && i + 1 < input.length()) {
                            char next = input.charAt(i + 1);
                            sb.append(next);
                            i += 2;
                            col += 2;
                            continue;
                        }
                        sb.append(c);
                        i++;
                        col++;
                    }
                    if (i >= input.length()) {
                        throw new IllegalArgumentException(String.format("line %d col %d: Unterminated string literal", line, startCol));
                    }
                    i++;
                    col++;
                    out.add(new Token(TokenType.STRING, sb.toString(), line, startCol));
                    continue;
                }

                String two = i + 1 < input.length() ? input.substring(i, i + 2) : "";
                if (Set.of("!=", ">=", "<=").contains(two)) {
                    out.add(new Token(TokenType.SYMBOL, two, line, startCol));
                    i += 2;
                    col += 2;
                    continue;
                }
                if (SYMBOLS.contains(String.valueOf(ch))) {
                    out.add(new Token(TokenType.SYMBOL, String.valueOf(ch), line, startCol));
                    i++;
                    col++;
                    continue;
                }

                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '*' || ch == '.' || ch == '-') {
                    int start = i;
                    while (i < input.length()) {
                        char c = input.charAt(i);
                        if (!(Character.isLetterOrDigit(c) || c == '_' || c == '*' || c == '.' || c == '-')) {
                            break;
                        }
                        i++;
                        col++;
                    }
                    String ident = input.substring(start, i);
                    out.add(new Token(TokenType.IDENT, ident, line, startCol));
                    continue;
                }

                throw new IllegalArgumentException(String.format("line %d col %d: Unexpected character '%s'", line, startCol, ch));
            }

            out.add(new Token(TokenType.EOF, "<EOF>", line, col));
            return out;
        }
    }
}
