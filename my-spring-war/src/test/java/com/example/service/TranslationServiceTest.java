package com.example.service;

import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
    void loadRowsUsesConfiguredReferenceLanguageFile() throws Exception {
        TranslationService service = new TranslationService(
                tempDir.toString(),
                "",
                "en",
                "bg",
                "dummy-project-id",
                "global",
                "general/translation-llm",
                false,
                "bg-terms",
                50,
                3,
                10,
                "en",
                "bg",
                "",
                true,
                true,
                new ObjectMapper(),
                new RestTemplateBuilder()
        );

        Files.writeString(tempDir.resolve("fr.json"), """
                {
                  "b" : {
                    "apply" : "Appliquer"
                  }
                }
                """);

        Files.writeString(tempDir.resolve("bg.json"), """
                {
                  "b" : {
                    "apply" : "Приложи"
                  }
                }
                """);

        List<TranslationRow> rows = service.loadRows(null, "fr.json");
        assertEquals(1, rows.size());
        assertEquals("Приложи", rows.get(0).getEnglishReference());
    }

    @Test
    void translateAndStoreSkipsBlankContentsInGoogleBatchRequests() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);
        MockRestServiceServer server = bindMockServer(service);

        String url = "https://translation.googleapis.com/v3/projects/dummy-project-id/locations/global:translateText";
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    ByteArrayOutputStream requestBody = (ByteArrayOutputStream) request.getBody();
                    JsonNode body = new ObjectMapper().readTree(requestBody.toString(StandardCharsets.UTF_8));
                    assertEquals(2, body.path("contents").size());
                    assertEquals("Apply", body.path("contents").get(0).asText());
                    assertEquals("Cancel", body.path("contents").get(1).asText());
                })
                .andRespond(withSuccess("""
                        {
                          "translations":[
                            {"translatedText":"Приложи"},
                            {"translatedText":"Откажи"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        service.translateAndStore(null, "en.json", "bg", List.of(
                new TranslationRow("b", "apply", "Apply", ""),
                new TranslationRow("b", "empty", "", ""),
                new TranslationRow("b", "cancel", "Cancel", "")
        ));
        server.verify();

        JsonNode translated = new ObjectMapper().readTree(Files.readString(tempDir.resolve("bg.json")));
        assertEquals("Приложи", translated.path("b").path("apply").asText());
        assertEquals("", translated.path("b").path("empty").asText());
        assertEquals("Откажи", translated.path("b").path("cancel").asText());
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
        JsonNode rowsNode = report.path("rows");
        JsonNode summary = report.path("summary");

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
        assertEquals(3, rowsNode.size());
        assertEquals("VALID", rowsNode.get(0).path("validationStatus").asText());
        assertEquals(3, summary.path("totalStringsProcessed").asInt());
        assertEquals(3, summary.path("validCount").asInt());
        assertEquals(0, summary.path("invalidCount").asInt());
        assertEquals(0, summary.path("warningCount").asInt());
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
        Object protectedItem = protectedItems.get(0);

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
        assertEquals(row.getText(), restored.get(0));
    }

    @Test
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
        Method issuesAccessor = validationReport.getClass().getDeclaredMethod("issues");
        List<?> baselineIssues = (List<?>) issuesAccessor.invoke(validationReport);
        assertTrue(baselineIssues.isEmpty());

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
                List.of(newTranslatedItemResult(0, "b.placeholder", unresolvedOutput, "mock-route", false, "")),
                List.of(unresolvedOutput),
                "en",
                "bg",
                Set.of()
        );

        List<?> issues = (List<?>) issuesAccessor.invoke(failedReport);
        assertEquals(1, issues.size());
        Method messageAccessor = issues.get(0).getClass().getDeclaredMethod("message");
        assertTrue(((String) messageAccessor.invoke(issues.get(0))).contains("unresolved placeholder tokens remained after restoration"));
    }

    @Test
    void constructorFailsFastWhenGlossaryEnabledButGlossaryConfigMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new TranslationService(
                tempDir.toString(),
                "",
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
                true,
                true,
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

    @Test
    void normalizeCredentialsPathValueStripsAccidentalPropertyAssignments() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);
        Method normalizeMethod = TranslationService.class.getDeclaredMethod("normalizeCredentialsPathValue", String.class);
        normalizeMethod.setAccessible(true);

        String normalizedLocalProperty = (String) normalizeMethod.invoke(
                service,
                "myapp.local.googleCredentialsPath=C:/Users/example/credentials.json"
        );
        assertEquals("C:/Users/example/credentials.json", normalizedLocalProperty);

        String normalizedEnvProperty = (String) normalizeMethod.invoke(
                service,
                "GOOGLE_APPLICATION_CREDENTIALS=C:/Users/example/credentials.json"
        );
        assertEquals("C:/Users/example/credentials.json", normalizedEnvProperty);
    }

    @Test
    void normalizeCredentialsPathValueStripsWrappingQuotes() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);
        Method normalizeMethod = TranslationService.class.getDeclaredMethod("normalizeCredentialsPathValue", String.class);
        normalizeMethod.setAccessible(true);

        String normalized = (String) normalizeMethod.invoke(service, "\"C:/Users/example/credentials.json\"");
        assertEquals("C:/Users/example/credentials.json", normalized);
    }

    @Test
    void flattenAndRebuildPreservesOriginalJsonStructure() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);
        ObjectMapper objectMapper = new ObjectMapper();
        Object sourcePayload = objectMapper.readValue("""
                {
                  "b": {"title": "Hello", "count": 3},
                  "meta": ["keep", "array"]
                }
                """, Object.class);

        Method rebuild = TranslationService.class.getDeclaredMethod("rebuildTranslatedPayload", Object.class, Map.class);
        rebuild.setAccessible(true);
        Object rebuilt = rebuild.invoke(service, sourcePayload, Map.of("b.title", "Bonjour"));
        JsonNode rebuiltNode = objectMapper.valueToTree(rebuilt);

        assertEquals("Bonjour", rebuiltNode.path("b").path("title").asText());
        assertEquals(3, rebuiltNode.path("b").path("count").asInt());
        assertEquals("keep", rebuiltNode.path("meta").get(0).asText());
    }

    @Test
    void riskyStringClassificationMarksShortUiAndConfiguredRiskTerms() throws Exception {
        Path riskyTermsFile = tempDir.resolve("risky-terms.txt");
        Files.writeString(riskyTermsFile, "sync now\n");
        TranslationService service = createService(riskyTermsFile.toString(), false, "en", "bg", 50);

        Method flattenRows = TranslationService.class.getDeclaredMethod("flattenRows", List.class);
        flattenRows.setAccessible(true);
        List<?> flattened = (List<?>) flattenRows.invoke(service, List.of(
                new TranslationRow("b", "cta", "Apply", ""),
                new TranslationRow("x", "sync", "Please sync now", "")
        ));

        Method preprocessItems = TranslationService.class.getDeclaredMethod("preprocessItems", List.class, Set.class);
        preprocessItems.setAccessible(true);
        List<?> preprocessed = (List<?>) preprocessItems.invoke(service, flattened, Set.of("sync now"));

        Method metadataAccessor = preprocessed.get(0).getClass().getDeclaredMethod("metadata");
        Object metadata1 = metadataAccessor.invoke(preprocessed.get(0));
        Method riskyAccessor = metadata1.getClass().getDeclaredMethod("risky");
        assertTrue((Boolean) riskyAccessor.invoke(metadata1));
        Object metadata2 = metadataAccessor.invoke(preprocessed.get(1));
        assertTrue((Boolean) riskyAccessor.invoke(metadata2));
    }

    @Test
    void validationDetectsMissingPlaceholderTokens() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);
        TranslationRow row = new TranslationRow("b", "name", "Hello {{name}}", "");
        Method flattenRows = TranslationService.class.getDeclaredMethod("flattenRows", List.class);
        flattenRows.setAccessible(true);
        List<?> flattened = (List<?>) flattenRows.invoke(service, List.of(row));
        Method preprocessItems = TranslationService.class.getDeclaredMethod("preprocessItems", List.class, Set.class);
        preprocessItems.setAccessible(true);
        List<?> preprocessed = (List<?>) preprocessItems.invoke(service, flattened, Set.of());
        Method protectPlaceholders = TranslationService.class.getDeclaredMethod("protectPlaceholders", List.class);
        protectPlaceholders.setAccessible(true);
        List<?> protectedItems = (List<?>) protectPlaceholders.invoke(service, preprocessed);

        Method validateResults = TranslationService.class.getDeclaredMethod("validateResults", List.class, List.class, List.class, String.class, String.class, Set.class);
        validateResults.setAccessible(true);
        Object report = validateResults.invoke(
                service,
                protectedItems,
                List.of(newTranslatedItemResult(0, "b.name", "Bonjour", "mock", false, "")),
                List.of("Bonjour"),
                "en",
                "fr",
                Set.of()
        );

        Method summaryAccessor = report.getClass().getDeclaredMethod("summary");
        Object summary = summaryAccessor.invoke(report);
        Method invalidCountAccessor = summary.getClass().getDeclaredMethod("invalidCount");
        assertEquals(1, invalidCountAccessor.invoke(summary));
    }

    @Test
    void duplicateSourceConsistencyAddsWarnings() throws Exception {
        TranslationService service = createService("", false, "en", "bg", 50);
        Method flattenRows = TranslationService.class.getDeclaredMethod("flattenRows", List.class);
        flattenRows.setAccessible(true);
        List<?> flattened = (List<?>) flattenRows.invoke(service, List.of(
                new TranslationRow("b", "k1", "Apply", ""),
                new TranslationRow("m", "k2", "Apply", "")
        ));
        Method preprocessItems = TranslationService.class.getDeclaredMethod("preprocessItems", List.class, Set.class);
        preprocessItems.setAccessible(true);
        List<?> preprocessed = (List<?>) preprocessItems.invoke(service, flattened, Set.of());
        Method protectPlaceholders = TranslationService.class.getDeclaredMethod("protectPlaceholders", List.class);
        protectPlaceholders.setAccessible(true);
        List<?> protectedItems = (List<?>) protectPlaceholders.invoke(service, preprocessed);

        Method validateResults = TranslationService.class.getDeclaredMethod("validateResults", List.class, List.class, List.class, String.class, String.class, Set.class);
        validateResults.setAccessible(true);
        Object report = validateResults.invoke(
                service,
                protectedItems,
                List.of(
                        newTranslatedItemResult(0, "b.k1", "Aplicar", "mock", true, ""),
                        newTranslatedItemResult(1, "m.k2", "Aplicación", "mock", true, "")
                ),
                List.of("Aplicar", "Aplicación"),
                "en",
                "es",
                Set.of()
        );
        Method summaryAccessor = report.getClass().getDeclaredMethod("summary");
        Object summary = summaryAccessor.invoke(report);
        Method warningsAccessor = summary.getClass().getDeclaredMethod("warningCount");
        assertEquals(2, warningsAccessor.invoke(summary));
    }

    @Test
    void integrationSmallSampleGeneratesJsonAndCsvReports() throws Exception {
        TranslationService service = createService("", false, "en", "en", 50);
        Files.writeString(tempDir.resolve("en.json"), """
                {
                  "b": {"hello": "Hello"}
                }
                """);
        service.translateAndStore(null, "en.json", "en", List.of(new TranslationRow("b", "hello", "Hello", "")));
        assertTrue(Files.exists(tempDir.resolve("en.validation-report.json")));
        assertTrue(Files.exists(tempDir.resolve("en.validation-report.csv")));
        String csv = Files.readString(tempDir.resolve("en.validation-report.csv"));
        assertTrue(csv.contains("full_key,prefix"));
        assertTrue(csv.contains("summary_metric,value"));
    }

    @Test
    void integrationRiskySubsetIncludesRiskyFlagsInReportRows() throws Exception {
        TranslationService service = createService("", false, "en", "en", 50);
        Files.writeString(tempDir.resolve("en.json"), """
                {
                  "b": {"apply": "Apply"},
                  "x": {"longText": "This is a long neutral sentence"}
                }
                """);
        service.translateAndStore(null, "en.json", "en", List.of(
                new TranslationRow("b", "apply", "Apply", ""),
                new TranslationRow("x", "longText", "This is a long neutral sentence", "")
        ));
        JsonNode report = new ObjectMapper().readTree(Files.readString(tempDir.resolve("en.validation-report.json")));
        assertTrue(report.path("rows").get(0).path("riskyFlag").asBoolean());
    }

    @Test
    void integrationFullRunDryWithMockedGoogleResponsesWorksEndToEnd() throws Exception {
        TranslationService service = createService("", false, "en", "fr", 50);
        Files.writeString(tempDir.resolve("en.json"), """
                {
                  "b": {"hello": "Hello {{name}}"}
                }
                """);
        MockRestServiceServer server = bindMockServer(service);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("translation.googleapis.com")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "translations":[{"translatedText":"Bonjour __PH_NAME__"}]
                        }
                        """, MediaType.APPLICATION_JSON));

        service.translateAndStore(null, "en.json", "fr", List.of(
                new TranslationRow("b", "hello", "Hello {{name}}", "")
        ));
        server.verify();

        JsonNode translated = new ObjectMapper().readTree(Files.readString(tempDir.resolve("fr.json")));
        assertEquals("Bonjour {{name}}", translated.path("b").path("hello").asText());
        JsonNode report = new ObjectMapper().readTree(Files.readString(tempDir.resolve("fr.validation-report.json")));
        assertEquals("VALID", report.path("rows").get(0).path("validationStatus").asText());
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

    private Object newTranslatedItemResult(int index, String fullKey, String translatedText, String route, boolean risky, String riskReason) throws Exception {
        Class<?> translatedItemResultClass = Class.forName("com.example.service.TranslationService$TranslatedItemResult");
        var constructor = translatedItemResultClass.getDeclaredConstructor(int.class, String.class, String.class, String.class, boolean.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(index, fullKey, translatedText, route, risky, riskReason);
    }

    private MockRestServiceServer bindMockServer(TranslationService service) throws Exception {
        Field restTemplateField = TranslationService.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        RestTemplate restTemplate = (RestTemplate) restTemplateField.get(service);
        return MockRestServiceServer.bindTo(restTemplate).build();
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
                "",
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
                true,
                true,
                new ObjectMapper(),
                new RestTemplateBuilder()
        );
    }
}
