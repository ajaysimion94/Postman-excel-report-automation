package com.automation;

import com.automation.postman.PostmanCollection;
import com.automation.postman.PostmanCollectionParser;
import com.automation.postman.RequestSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmanCollectionParserTest {
    @Test
    void parsesCollectionVariablesFoldersAndAuth() throws Exception {
        String json = """
                {
                  "info": {"name": "Sample Collection"},
                  "variable": [{"key": "baseUrl", "value": "https://example.com"}],
                  "auth": {
                    "type": "basic",
                    "basic": [
                      {"key": "username", "value": "{{API_USERNAME}}"},
                      {"key": "password", "value": "{{API_PASSWORD}}"}
                    ]
                  },
                  "item": [
                    {
                      "name": "Users",
                      "item": [
                        {
                          "name": "List Users",
                          "request": {
                            "method": "GET",
                            "url": {"raw": "{{baseUrl}}/users"},
                            "header": [{"key": "Accept", "value": "application/json"}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        Path tempFile = Files.createTempFile("collection", ".json");
        Files.writeString(tempFile, json);

        PostmanCollection collection = new PostmanCollectionParser().parse(tempFile);

        assertEquals("Sample Collection", collection.name());
        assertEquals("https://example.com", collection.variables().get("baseUrl"));
        assertEquals(1, collection.requests().size());
        assertEquals("Users", collection.requests().get(0).folderPath());
        assertEquals("GET", collection.requests().get(0).method());
        assertNotNull(collection.requests().get(0).auth());
        assertEquals("basic", collection.requests().get(0).auth().type());
    }

    @Test
    void preservesStructuredRequestMetadataWithoutChangingFlattenedFields() throws Exception {
        String json = """
                {
                  "info": {"name": "Structured Collection"},
                  "item": [
                    {
                      "name": "Search Users",
                      "disabled": true,
                      "request": {
                        "method": "POST",
                        "description": {"content": "GraphQL search endpoint"},
                        "timeout": 1500,
                        "protocolProfileBehavior": {
                          "followRedirects": false,
                          "disableUrlEncoding": true,
                          "strictSSL": false
                        },
                        "url": {
                          "raw": "{{baseUrl}}/users/search",
                          "query": [
                            {"key": "page", "value": "2"},
                            {"key": "debug", "value": "true", "disabled": true}
                          ]
                        },
                        "body": {
                          "mode": "graphql",
                          "graphql": {
                            "query": "query Users { users { id name } }",
                            "variables": "{\\\"page\\\":2}"
                          }
                        }
                      }
                    }
                  ]
                }
                """;

        Path tempFile = Files.createTempFile("collection-structured", ".json");
        Files.writeString(tempFile, json);

        PostmanCollection collection = new PostmanCollectionParser().parse(tempFile);
        RequestSpec request = collection.requests().get(0);

        assertEquals(1, collection.requests().size());
        assertEquals("{{baseUrl}}/users/search?page=2", request.url());
        assertEquals("POST", request.method());
        assertEquals("GraphQL search endpoint", request.description());
        assertTrue(request.disabled());
        assertNotNull(request.urlSpec());
        assertEquals(2, request.urlSpec().query().size());
        assertNotNull(request.bodySpec());
        assertEquals("graphql", request.bodySpec().mode());
        assertEquals("query Users { users { id name } }", request.bodySpec().graphQlQuery());
        assertNotNull(request.settings());
        assertEquals(1500, request.settings().timeoutMillis());
        assertEquals(Boolean.FALSE, request.settings().followRedirects());
    }
}