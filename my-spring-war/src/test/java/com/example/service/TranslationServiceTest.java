package com.example.service;

import com.example.api.dto.TranslationRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveRowsAppendsNewKeysToEndOfExistingSection() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "dummy-project-id",
                "en",
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        Path targetFile = tempDir.resolve("fr.json");
        Files.writeString(targetFile, """
                {
                  "b" : {
                    "existing1" : "value1",
                    "existing2" : "value2"
                  },
                  "x" : {
                    "x1" : "valueX"
                  }
                }
                """);

        List<TranslationRow> rows = List.of(
                new TranslationRow("b", "newKey", "new value", ""),
                new TranslationRow("b", "existing1", "updated value1", ""),
                new TranslationRow("b", "existing2", "updated value2", ""),
                new TranslationRow("x", "x1", "updated valueX", "")
        );

        service.saveRows(null, "fr.json", rows);

        String actual = Files.readString(targetFile);
        assertEquals("""
                {
                  "b" : {
                    "existing1" : "updated value1",
                    "existing2" : "updated value2",
                    "newKey" : "new value"
                  },
                  "x" : {
                    "x1" : "updated valueX"
                  }
                }""", actual);
    }
}
