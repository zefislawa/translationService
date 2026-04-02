package com.example.service;

import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationExportResult;
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
                "global",
                "",
                "en",
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
    @Test
    void compareFilesReturnsDifferencesForMissingAndChangedKeys() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "dummy-project-id",
                "global",
                "",
                "en",
                "en",
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        Files.writeString(tempDir.resolve("fr.json"), """
                {
                  "b" : {
                    "hello" : "bonjour",
                    "onlyFr" : "fr-value"
                  }
                }
                """);

        Files.writeString(tempDir.resolve("de.json"), """
                {
                  "b" : {
                    "hello" : "hallo",
                    "onlyDe" : "de-value"
                  }
                }
                """);

        TranslationCompareResult result = service.compareFiles(null, "fr.json", "de.json");

        assertEquals(3, result.differences().size());
        assertEquals("Different values", result.differences().get(0).status());
        assertEquals("b.hello", result.differences().get(0).keyPath());
        assertEquals("Missing in file 1", result.differences().get(1).status());
        assertEquals("Missing in file 2", result.differences().get(2).status());
    }

    @Test
    void translateAndImportMergesRowsIntoTargetFileWhenLanguagesMatch() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "dummy-project-id",
                "global",
                "",
                "en",
                "en",
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        Path targetFile = tempDir.resolve("de.json");
        Files.writeString(targetFile, """
                {
                  "b" : {
                    "existing" : "hallo"
                  }
                }
                """);

        List<TranslationRow> rows = List.of(
                new TranslationRow("b", "newKey", "neuer Wert", ""),
                new TranslationRow("b", "existing", "aktualisiert", "")
        );

        service.translateAndImport(null, "de.json", "de.json", rows);

        String actual = Files.readString(targetFile);
        assertEquals("""
                {
                  "b" : {
                    "existing" : "aktualisiert",
                    "newKey" : "neuer Wert"
                  }
                }""", actual);
    }

    @Test
    void translateAndStoreFallsBackToReferenceLanguageWhenFileNameIsNotLanguageCode() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "dummy-project-id",
                "global",
                "",
                "en",
                "en",
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        List<TranslationRow> rows = List.of(
                new TranslationRow("b", "Apply", "Apply", "")
        );

        TranslationExportResult result = service.translateAndStore(null, "risky_strings_subset.json", "en", rows);

        assertEquals("en", result.language());
        assertEquals(1, result.count());
        assertEquals("""
                {
                  "b" : {
                    "Apply" : "Apply"
                  }
                }""", Files.readString(tempDir.resolve("en.json")));
    }

    @Test
    void loadRowsPreservesExactPrefixKeyAndSourceText() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "dummy-project-id",
                "global",
                "",
                "en",
                "en",
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        Files.writeString(tempDir.resolve("en.json"), """
                {
                  "prefix one" : {
                    "key.with.dot" : "Keep  spacing  and \\n newline",
                    "ExactCaseKey" : "Text With CASE"
                  }
                }
                """);

        List<TranslationRow> rows = service.loadRows(null, "en.json");
        assertEquals(2, rows.size());
        assertEquals("prefix one", rows.get(0).getSection());
        assertEquals("key.with.dot", rows.get(0).getKey());
        assertEquals("Keep  spacing  and \n newline", rows.get(0).getText());
        assertEquals("Keep  spacing  and \n newline", rows.get(0).getEnglishReference());
        assertEquals("ExactCaseKey", rows.get(1).getKey());
        assertEquals("Text With CASE", rows.get(1).getText());
    }

    @Test
    void loadRowsIgnoresNonStringLeafValues() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "dummy-project-id",
                "global",
                "",
                "en",
                "en",
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        Files.writeString(tempDir.resolve("en.json"), """
                {
                  "prefix" : {
                    "valid" : "ok",
                    "number" : 123,
                    "nested" : {
                      "inner" : "value"
                    },
                    "nullValue" : null
                  }
                }
                """);

        List<TranslationRow> rows = service.loadRows(null, "en.json");
        assertEquals(1, rows.size());
        assertEquals("prefix", rows.get(0).getSection());
        assertEquals("valid", rows.get(0).getKey());
        assertEquals("ok", rows.get(0).getText());
    }

}
