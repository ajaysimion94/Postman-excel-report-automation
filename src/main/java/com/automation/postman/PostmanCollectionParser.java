package com.automation.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PostmanCollectionParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostmanCollection parse(Path collectionPath) throws IOException {
        return parseRoot(objectMapper.readTree(collectionPath.toFile()), collectionPath.getFileName().toString());
    }

    /** Parses an unsaved collection buffer for web validation and execution. */
    public PostmanCollection parseSource(String source, String filename) throws IOException {
        return parseRoot(objectMapper.readTree(source), filename);
    }

    private PostmanCollection parseRoot(JsonNode root, String filename) {
        if (root == null || !root.isObject() || !root.path("item").isArray()) {
            throw new IllegalArgumentException("A Postman collection must be a JSON object with an item array.");
        }
        String name = root.path("info").path("name").asText(filename);
        Map<String, String> variables = parseVariables(root.path("variable"));
        AuthDefinition collectionAuth = parseAuth(root.path("auth"));
        List<RequestSpec> requests = new ArrayList<>();
        parseItems(root.path("item"), new ArrayList<>(), collectionAuth, requests);
        return new PostmanCollection(name, Map.copyOf(variables), List.copyOf(requests));
    }

    private void parseItems(JsonNode itemsNode, List<String> folderStack, AuthDefinition inheritedAuth, List<RequestSpec> requests) {
        if (!itemsNode.isArray()) {
            return;
        }

        for (JsonNode itemNode : itemsNode) {
            AuthDefinition localAuth = parseAuth(itemNode.path("auth"));
            AuthDefinition effectiveAuth = localAuth == null ? inheritedAuth : localAuth;
            if (itemNode.has("request")) {
                requests.add(parseRequest(itemNode, folderStack, effectiveAuth));
                continue;
            }

            if (itemNode.has("item")) {
                List<String> childFolders = new ArrayList<>(folderStack);
                childFolders.add(itemNode.path("name").asText("Folder"));
                parseItems(itemNode.path("item"), childFolders, effectiveAuth, requests);
            }
        }
    }

    private RequestSpec parseRequest(JsonNode itemNode, List<String> folderStack, AuthDefinition auth) {
        JsonNode requestNode = itemNode.path("request");
        RequestUrlSpec urlSpec = parseUrlSpec(requestNode.path("url"));
        String url = renderUrl(urlSpec);
        List<RequestHeader> headers = parseHeaders(requestNode.path("header"));
        RequestBodySpec bodySpec = parseBodySpec(requestNode.path("body"));
        String body = renderBody(bodySpec);
        AuthDefinition requestAuth = parseAuth(requestNode.path("auth"));
        AuthDefinition effectiveAuth = requestAuth == null ? auth : requestAuth;
        String description = firstNonBlank(
                parseDescription(requestNode.path("description")),
                parseDescription(itemNode.path("description"))
        );
        boolean disabled = itemNode.path("disabled").asBoolean(false);
        RequestSettings settings = parseRequestSettings(itemNode, requestNode);

        return new RequestSpec(
                String.join(" / ", folderStack),
                itemNode.path("name").asText("Unnamed Request"),
                requestNode.path("method").asText("GET"),
                url,
                headers,
                body,
                effectiveAuth,
                description,
                disabled,
                urlSpec,
                bodySpec,
                settings
        );
    }

    private RequestUrlSpec parseUrlSpec(JsonNode urlNode) {
        if (urlNode.isTextual()) {
            return new RequestUrlSpec(urlNode.asText(), "", List.of(), List.of(), List.of());
        }

        String raw = urlNode.path("raw").asText("");
        String protocol = urlNode.path("protocol").asText("");
        List<String> host = readTextArray(urlNode.path("host"));
        List<String> path = readTextArray(urlNode.path("path"));
        List<RequestQueryParam> query = parseQueryParams(urlNode.path("query"));

        return new RequestUrlSpec(raw, protocol, host, path, query);
    }

    private String renderUrl(RequestUrlSpec urlSpec) {
        if (urlSpec == null) {
            return "";
        }

        String raw = defaultString(urlSpec.raw());
        String base;
        if (!raw.isBlank()) {
            base = raw;
        } else if (!urlSpec.host().isEmpty()) {
            String protocol = urlSpec.protocol() == null || urlSpec.protocol().isBlank() ? "https" : urlSpec.protocol();
            String host = String.join(".", urlSpec.host());
            String path = String.join("/", urlSpec.path());
            base = path.isBlank() ? protocol + "://" + host : protocol + "://" + host + "/" + path;
        } else {
            base = "";
        }

        if (!urlSpec.query().isEmpty()) {
            List<String> params = new ArrayList<>();
            for (RequestQueryParam queryParam : urlSpec.query()) {
                if (queryParam.disabled()) {
                    continue;
                }
                String key = defaultString(queryParam.key());
                String value = defaultString(queryParam.value());
                if (!key.isBlank()) {
                    String pair = key + "=" + value;
                    if (!base.contains(key + "=")) {
                        params.add(pair);
                    }
                }
            }
            if (!params.isEmpty()) {
                String separator = base.contains("?") ? "&" : "?";
                base = base + separator + String.join("&", params);
            }
        }

        return base;
    }

    private RequestBodySpec parseBodySpec(JsonNode bodyNode) {
        if (bodyNode == null || bodyNode.isMissingNode() || bodyNode.isNull() || bodyNode.isEmpty()) {
            return null;
        }

        String mode = bodyNode.path("mode").asText("");
        switch (mode.toLowerCase()) {
            case "raw" -> {
                return new RequestBodySpec(mode, bodyNode.path("raw").asText(null), List.of(), List.of(), null, null, null);
            }
            case "urlencoded" -> {
                return new RequestBodySpec(
                        mode,
                        null,
                        parseBodyFields(bodyNode.path("urlencoded")),
                        List.of(),
                        null,
                        null,
                        null
                );
            }
            case "formdata" -> {
                return new RequestBodySpec(
                        mode,
                        null,
                        List.of(),
                        parseBodyFields(bodyNode.path("formdata")),
                        null,
                        null,
                        null
                );
            }
            case "file", "binary" -> {
                JsonNode fileNode = bodyNode.path(mode);
                return new RequestBodySpec(mode, null, List.of(), List.of(), readStringValue(fileNode.path("src")), null, null);
            }
            case "graphql" -> {
                JsonNode graphQlNode = bodyNode.path("graphql");
                return new RequestBodySpec(
                        mode,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        graphQlNode.path("query").asText(null),
                        graphQlNode.path("variables").asText(null)
                );
            }
            default -> {
                return new RequestBodySpec(mode, null, List.of(), List.of(), null, null, null);
            }
        }
    }

    private String renderBody(RequestBodySpec bodySpec) {
        if (bodySpec == null || bodySpec.mode() == null) {
            return null;
        }

        switch (bodySpec.mode().toLowerCase()) {
            case "raw" -> {
                return bodySpec.raw();
            }
            case "urlencoded" -> {
                List<String> pairs = new ArrayList<>();
                for (RequestBodyField field : bodySpec.urlEncoded()) {
                    if (field.disabled()) {
                        continue;
                    }
                    String key = defaultString(field.key());
                    String value = defaultString(field.value());
                    if (!key.isBlank()) {
                        pairs.add(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8)
                                + "=" +
                                java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return pairs.isEmpty() ? null : String.join("&", pairs);
            }
            case "formdata" -> {
                List<String> parts = new ArrayList<>();
                for (RequestBodyField field : bodySpec.formData()) {
                    if (field.disabled()) {
                        continue;
                    }
                    String key = defaultString(field.key());
                    String value = "file".equalsIgnoreCase(defaultString(field.type()))
                            ? defaultString(field.source())
                            : defaultString(field.value());
                    if (!key.isBlank()) {
                        parts.add("\"" + key + "\": \"" + value + "\"");
                    }
                }
                if (parts.isEmpty()) {
                    return null;
                }
                System.err.println("[WARN] Request body mode is 'formdata'. Multipart sending is not supported; "
                        + "fields are included as JSON for visibility.");
                return "{" + String.join(", ", parts) + "}";
            }
            default -> {
                return null;
            }
        }
    }

    private List<RequestHeader> parseHeaders(JsonNode headersNode) {
        if (!headersNode.isArray()) {
            return List.of();
        }

        List<RequestHeader> headers = new ArrayList<>();
        for (JsonNode headerNode : headersNode) {
            if (headerNode.path("disabled").asBoolean(false)) {
                continue;
            }
            headers.add(new RequestHeader(
                    headerNode.path("key").asText(),
                    headerNode.path("value").asText("")
            ));
        }
        return List.copyOf(headers);
    }

    private List<RequestQueryParam> parseQueryParams(JsonNode queryNode) {
        if (!queryNode.isArray()) {
            return List.of();
        }

        List<RequestQueryParam> params = new ArrayList<>();
        for (JsonNode queryParamNode : queryNode) {
            params.add(new RequestQueryParam(
                    queryParamNode.path("key").asText(null),
                    queryParamNode.path("value").asText(""),
                    queryParamNode.path("disabled").asBoolean(false)
            ));
        }
        return List.copyOf(params);
    }

    private List<RequestBodyField> parseBodyFields(JsonNode fieldsNode) {
        if (!fieldsNode.isArray()) {
            return List.of();
        }

        List<RequestBodyField> fields = new ArrayList<>();
        for (JsonNode fieldNode : fieldsNode) {
            fields.add(new RequestBodyField(
                    fieldNode.path("key").asText(null),
                    fieldNode.path("value").asText(null),
                    fieldNode.path("type").asText("text"),
                    readStringValue(fieldNode.path("src")),
                    fieldNode.path("disabled").asBoolean(false),
                    fieldNode.path("contentType").asText(null)
            ));
        }
        return List.copyOf(fields);
    }

    private AuthDefinition parseAuth(JsonNode authNode) {
        if (authNode == null || authNode.isMissingNode() || authNode.isNull() || authNode.isEmpty()) {
            return null;
        }

        String type = authNode.path("type").asText();
        if (type.isBlank()) {
            return null;
        }

        JsonNode detailsNode = authNode.path(type);
        Map<String, String> values = new HashMap<>();
        if (detailsNode.isArray()) {
            for (JsonNode entry : detailsNode) {
                values.put(entry.path("key").asText(), entry.path("value").asText());
            }
        }

        return new AuthDefinition(type, Map.copyOf(values));
    }

    private RequestSettings parseRequestSettings(JsonNode itemNode, JsonNode requestNode) {
        JsonNode profileNode = requestNode.path("protocolProfileBehavior");
        if (profileNode.isMissingNode() || profileNode.isNull() || profileNode.isEmpty()) {
            profileNode = itemNode.path("protocolProfileBehavior");
        }

        Integer timeoutMillis = parseIntegerNode(requestNode.path("timeout"));
        Boolean followRedirects = parseBooleanNode(profileNode.path("followRedirects"));
        Boolean disableUrlEncoding = parseBooleanNode(profileNode.path("disableUrlEncoding"));
        Boolean strictSsl = parseBooleanNode(profileNode.path("strictSSL"));
        Map<String, String> protocolProfile = parseScalarMap(profileNode);

        if (timeoutMillis == null
                && followRedirects == null
                && disableUrlEncoding == null
                && strictSsl == null
                && protocolProfile.isEmpty()) {
            return null;
        }

        return new RequestSettings(timeoutMillis, followRedirects, disableUrlEncoding, strictSsl, protocolProfile);
    }

    private Map<String, String> parseVariables(JsonNode variableNode) {
        if (!variableNode.isArray()) {
            return Map.of();
        }

        Map<String, String> variables = new HashMap<>();
        for (JsonNode entry : variableNode) {
            String key = entry.path("key").asText();
            if (!key.isBlank()) {
                variables.put(key, entry.path("value").asText(""));
            }
        }
        return variables;
    }

    private Map<String, String> parseScalarMap(JsonNode objectNode) {
        if (objectNode == null || !objectNode.isObject()) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isValueNode()) {
                values.put(entry.getKey(), valueNode.asText());
            }
        }
        return Map.copyOf(values);
    }

    private Boolean parseBooleanNode(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        }
        if (valueNode.isTextual()) {
            String text = valueNode.asText().trim();
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private Integer parseIntegerNode(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        if (valueNode.canConvertToInt()) {
            return valueNode.asInt();
        }
        if (valueNode.isTextual()) {
            try {
                return Integer.parseInt(valueNode.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String parseDescription(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return null;
        }
        if (descriptionNode.isTextual()) {
            String text = descriptionNode.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (descriptionNode.isObject()) {
            String content = descriptionNode.path("content").asText("").trim();
            return content.isEmpty() ? null : content;
        }
        return null;
    }

    private String readStringValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isArray()) {
            List<String> values = readTextArray(valueNode);
            return values.isEmpty() ? null : values.get(0);
        }
        String text = valueNode.asText("");
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private List<String> readTextArray(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            String singleValue = arrayNode.asText("");
            return singleValue.isBlank() ? List.of() : List.of(singleValue);
        }

        List<String> values = new ArrayList<>();
        Iterator<JsonNode> iterator = arrayNode.elements();
        while (iterator.hasNext()) {
            values.add(iterator.next().asText());
        }
        return List.copyOf(values);
    }
}
