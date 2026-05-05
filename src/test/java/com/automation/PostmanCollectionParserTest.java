package com.automation;

import com.automation.postman.PostmanCollection;
import com.automation.postman.PostmanCollectionParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}