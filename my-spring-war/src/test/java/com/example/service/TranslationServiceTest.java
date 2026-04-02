package com.example.service;

import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveRowsAppendsNewKeysToEndOfExistingSection() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);

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
        TranslationService service = createService("", false, "en", "bg", 50);

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
        TranslationService service = createService("", false, "en", "bg", 50);

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
        TranslationService service = createService("", false, "en", "bg", 50);

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
        TranslationService service = createService("", false, "en", "bg", 50);

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
        TranslationService service = createService("", false, "en", "bg", 50);

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

    @Test
    void translateAndStoreWritesPreprocessingMetadataIncludingConfiguredRiskyTerms() throws Exception {
        Path riskyTermsFile = tempDir.resolve("risky-terms.txt");
        Files.writeString(riskyTermsFile, """
                # one term per line
                sync now
                """);

        TranslationService service = createService(riskyTermsFile.toString(), false, "en", "bg", 50);

        List<TranslationRow> rows = List.of(
                new TranslationRow("b", "cta", "Apply", ""),
                new TranslationRow("m", "placeholder", "Hi {{name}} {id}", ""),
                new TranslationRow("x", "configured", "Please sync now", "")
        );

        service.translateAndStore(null, "en.json", "en", rows);

        Path reportFile = tempDir.resolve("en.validation-report.json");
        JsonNode report = new ObjectMapper().readTree(Files.readString(reportFile));
        JsonNode preprocessing = report.path("preprocessing");

        assertEquals(3, preprocessing.size());
        assertEquals(1, preprocessing.get(0).path("wordCount").asInt());
        assertTrue(preprocessing.get(0).path("shortText").asBoolean());
        assertTrue(preprocessing.get(0).path("risky").asBoolean());
        assertEquals("short-ui-prefix,ambiguous-term", preprocessing.get(0).path("riskReason").asText());

        assertTrue(preprocessing.get(1).path("containsPlaceholders").asBoolean());
        assertEquals(2, preprocessing.get(1).path("placeholders").size());
        assertEquals("{{name}}", preprocessing.get(1).path("placeholders").get(0).asText());
        assertEquals("{id}", preprocessing.get(1).path("placeholders").get(1).asText());

        assertTrue(preprocessing.get(2).path("risky").asBoolean());
        assertEquals("configured-risky-term", preprocessing.get(2).path("riskReason").asText());
        assertFalse(preprocessing.get(2).path("containsPlaceholders").asBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void protectPlaceholdersUsesStableSafeTokensAndRestoresRepeatedPlaceholders() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);

        TranslationRow row = new TranslationRow("b", "placeholder", "Hi {{name}} and {id} and {{name}} and {{0}}", "");
        Method flattenRows = TranslationService.class.getDeclaredMethod("flattenRows", List.class);
        flattenRows.setAccessible(true);
        List<?> flattened = (List<?>) flattenRows.invoke(service, List.of(row));

        Method preprocessItems = TranslationService.class.getDeclaredMethod("preprocessItems", List.class, Set.class);
        preprocessItems.setAccessible(true);
        List<?> preprocessed = (List<?>) preprocessItems.invoke(service, flattened, Set.of());

        Method protectPlaceholders = TranslationService.class.getDeclaredMethod("protectPlaceholders", List.class);
        protectPlaceholders.setAccessible(true);
        List<?> protectedItems = (List<?>) protectPlaceholders.invoke(service, preprocessed);
        Object protectedItem = protectedItems.getFirst();

        Method protectedTextAccessor = protectedItem.getClass().getDeclaredMethod("protectedText");
        String protectedText = (String) protectedTextAccessor.invoke(protectedItem);
        assertTrue(protectedText.contains("__PH_NAME__"));
        assertTrue(protectedText.contains("__PH_ID__"));
        assertTrue(protectedText.contains("__PH_0__"));
        assertEquals(2, countOccurrences(protectedText, "__PH_NAME__"));

        Method placeholdersAccessor = protectedItem.getClass().getDeclaredMethod("placeholders");
        Map<String, String> placeholders = (Map<String, String>) placeholdersAccessor.invoke(protectedItem);
        assertEquals("{{name}}", placeholders.get("__PH_NAME__"));
        assertEquals("{id}", placeholders.get("__PH_ID__"));
        assertEquals("{{0}}", placeholders.get("__PH_0__"));

        Method restorePlaceholders = TranslationService.class.getDeclaredMethod("restorePlaceholders", List.class, List.class);
        restorePlaceholders.setAccessible(true);
        List<String> restored = (List<String>) restorePlaceholders.invoke(service, protectedItems, List.of(protectedText));
        assertEquals(row.getText(), restored.getFirst());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateResultsMarksItemInvalidWhenProtectedTokensRemainAfterRestoration() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);

        TranslationRow row = new TranslationRow("b", "placeholder", "Hello {{name}}", "");
        Method runTranslationPipeline = TranslationService.class.getDeclaredMethod(
                "runTranslationPipeline",
                String.class,
                List.class,
                String.class,
                String.class
        );
        runTranslationPipeline.setAccessible(true);
        Object pipelineResult = runTranslationPipeline.invoke(service, null, List.of(row), "en", "en");

        Method reportAccessor = pipelineResult.getClass().getDeclaredMethod("validationReport");
        Object validationReport = reportAccessor.invoke(pipelineResult);
        Method errorsAccessor = validationReport.getClass().getDeclaredMethod("errors");
        List<?> baselineErrors = (List<?>) errorsAccessor.invoke(validationReport);
        assertTrue(baselineErrors.isEmpty());

        Method flattenRows = TranslationService.class.getDeclaredMethod("flattenRows", List.class);
        flattenRows.setAccessible(true);
        List<?> flattened = (List<?>) flattenRows.invoke(service, List.of(row));
        Method preprocessItems = TranslationService.class.getDeclaredMethod("preprocessItems", List.class, Set.class);
        preprocessItems.setAccessible(true);
        List<?> preprocessed = (List<?>) preprocessItems.invoke(service, flattened, Set.of());
        Method protectPlaceholders = TranslationService.class.getDeclaredMethod("protectPlaceholders", List.class);
        protectPlaceholders.setAccessible(true);
        List<?> protectedItems = (List<?>) protectPlaceholders.invoke(service, preprocessed);

        String unresolvedOutput = "Bonjour __PH_BROKEN__";
        Method validateResults = TranslationService.class.getDeclaredMethod(
                "validateResults",
                List.class,
                List.class,
                List.class,
                String.class,
                String.class,
                Set.class
        );
        validateResults.setAccessible(true);
        Object failedReport = validateResults.invoke(
                service,
                protectedItems,
                List.of(unresolvedOutput),
                List.of(unresolvedOutput),
                "en",
                "bg",
                Set.of()
        );

        List<?> errors = (List<?>) errorsAccessor.invoke(failedReport);
        assertEquals(1, errors.size());
        Method messageAccessor = errors.getFirst().getClass().getDeclaredMethod("message");
        assertTrue(((String) messageAccessor.invoke(errors.getFirst())).contains("unresolved placeholder tokens remained after restoration"));
    }

    @Test
    void constructorFailsFastWhenGlossaryEnabledButGlossaryConfigMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                "",
                "bg",
                "dummy-project-id",
                "global",
                "general/translation-llm",
                true,
                "",
                50,
                3,
                10,
                "en",
                "en",
                "",
                new ObjectMapper(),
                new RestTemplateBuilder()
        ));

        assertTrue(exception.getMessage().contains("myapp.google.sourceLanguage"));
        assertTrue(exception.getMessage().contains("myapp.google.glossaryId"));
    }

    @Test
    void constructorFailsWhenBatchSizeIsNotPositive() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> createService(
                "",
                false,
                "en",
                "bg",
                0
        ));
        assertTrue(exception.getMessage().contains("batch size"));
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private TranslationService createService(
            String riskyTermsFile,
            boolean glossaryEnabled,
            String sourceLanguage,
            String targetLanguage,
            int batchSize
    ) throws Exception {
        return new TranslationService(
                tempDir.toString(),
                "dummy-api-key",
                sourceLanguage,
                targetLanguage,
                "dummy-project-id",
                "global",
                "general/translation-llm",
                glossaryEnabled,
                "bg-terms",
                batchSize,
                3,
                10,
                "en",
                "en",
                riskyTermsFile,
                new ObjectMapper(),
                new RestTemplateBuilder()
        );
    }
}
