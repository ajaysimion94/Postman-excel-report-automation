package com.automation.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PostmanCollectionParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostmanCollection parse(Path collectionPath) throws IOException {
        JsonNode root = objectMapper.readTree(collectionPath.toFile());
        String name = root.path("info").path("name").asText(collectionPath.getFileName().toString());
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
        String url = parseUrl(requestNode.path("url"));
        List<RequestHeader> headers = parseHeaders(requestNode.path("header"));
        String body = parseBody(requestNode.path("body"));
        AuthDefinition requestAuth = parseAuth(requestNode.path("auth"));
        AuthDefinition effectiveAuth = requestAuth == null ? auth : requestAuth;

        return new RequestSpec(
                String.join(" / ", folderStack),
                itemNode.path("name").asText("Unnamed Request"),
                requestNode.path("method").asText("GET"),
                url,
                headers,
                body,
                effectiveAuth
        );
    }

    private String parseUrl(JsonNode urlNode) {
        if (urlNode.isTextual()) {
            return urlNode.asText();
        }

        String raw = urlNode.path("raw").asText();
        if (!raw.isBlank()) {
            return raw;
        }

        if (urlNode.has("host")) {
            String protocol = urlNode.path("protocol").asText("https");
            String host = joinTextArray(urlNode.path("host"), ".");
            String path = joinTextArray(urlNode.path("path"), "/");
            if (path.isBlank()) {
                return protocol + "://" + host;
            }
            return protocol + "://" + host + "/" + path;
        }

        return "";
    }

    private String parseBody(JsonNode bodyNode) {
        String mode = bodyNode.path("mode").asText();
        if ("raw".equalsIgnoreCase(mode)) {
            return bodyNode.path("raw").asText(null);
        }
        return null;
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

    private String joinTextArray(JsonNode arrayNode, String separator) {
        if (!arrayNode.isArray()) {
            return arrayNode.asText("");
        }

        List<String> values = new ArrayList<>();
        Iterator<JsonNode> iterator = arrayNode.elements();
        while (iterator.hasNext()) {
            values.add(iterator.next().asText());
        }
        return values.stream().collect(Collectors.joining(separator));
    }
}