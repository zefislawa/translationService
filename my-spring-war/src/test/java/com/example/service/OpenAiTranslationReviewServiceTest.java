package com.example.service;

import com.example.api.dto.ReviewedTranslationItem;
import com.example.api.dto.TranslationReviewItem;
import com.example.api.dto.TranslationReviewResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiTranslationReviewServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void sendsResponsesApiRequestWithConfiguredModelReasoningVerbosityAndSchema() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);

        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body, containsString("\"model\":\"gpt-5.4\""));
                    assertThat(body, containsString("\"store\":false"));
                    assertThat(body, containsString("\"reasoning\":{\"effort\":\"low\"}"));
                    assertThat(body, containsString("\"verbosity\":\"low\""));
                    assertThat(body, containsString("\"type\":\"json_schema\""));
                    assertThat(body, containsString("\"name\":\"translation_review_result\""));
                    assertThat(body, containsString("\"additionalProperties\":false"));
                    assertThat(body, containsString("\"required\":[\"key\",\"finalText\",\"changed\",\"reason\",\"issues\"]"));
                    assertThat(body, containsString("omit that item from the response"));
                })
                .andRespond(openAiSuccessResponse());

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "selfService", List.of(item("PayNow", "Pay Now", "Плати сега")));

        assertEquals(1, response.getItems().size());
        assertFalse(response.getItems().get(0).isChanged());
        server.verify();
    }

    @Test
    void cancellationStopsOpenAiReviewBeforeStartingRequest() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);

        assertThrows(CancellationException.class, () -> service.reviewTranslations(
                "en",
                "bg",
                "crm",
                List.of(item("PayNow", "Pay Now", "ÐŸÐ»Ð°Ñ‚Ð¸ ÑÐµÐ³Ð°")),
                () -> true
        ));
        server.verify();
    }

    @Test
    void batchesLargeRequestsWithoutCallingOncePerString() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        server.expect(ExpectedCount.times(60), requestTo("https://api.openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(openAiSuccessResponse());

        List<TranslationReviewItem> items = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            items.add(item("key-" + i, "Source " + i, "Translation " + i));
        }

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "crm", items);

        assertEquals(6000, response.getSummary().getTotal());
        assertEquals(0, response.getSummary().getChanged());
        server.verify();
    }

    @Test
    void rejectsInvalidPlaceholderChangesAndFallsBackToGoogleText() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        String output = mapper.writeValueAsString(Map.of("items", List.of(Map.of(
                "key", "Greeting",
                "finalText", "Здравей",
                "changed", true,
                "reason", "Removed placeholder",
                "issues", List.of()
        ))));
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("output_text", output)), MediaType.APPLICATION_JSON));

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "crm",
                List.of(item("Greeting", "Hello {0}", "Здравей {0}")));
        ReviewedTranslationItem reviewed = response.getItems().get(0);

        assertEquals("Здравей {0}", reviewed.getFinalText());
        assertFalse(reviewed.isChanged());
        assertTrue(reviewed.getIssues().contains("openai_validation_failed"));
        assertEquals(1, response.getSummary().getFailed());
        assertEquals(0, response.getSummary().getUnchanged());
        server.verify();
    }

    @Test
    void fallsBackOnUnauthorizedWithoutRetryingWhenFailOnErrorIsFalse() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        server.expect(ExpectedCount.once(), requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "crm",
                List.of(item("PayNow", "Pay Now", "Плати сега")));

        assertEquals("Плати сега", response.getItems().get(0).getFinalText());
        assertEquals("fallback_to_google", response.getItems().get(0).getReason());
        server.verify();
    }

    @Test
    void retriesTransientOpenAiErrors() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        server.expect(requestTo("https://api.openai.test/v1/responses")).andRespond(withServerError());
        server.expect(requestTo("https://api.openai.test/v1/responses")).andRespond(openAiSuccessResponse());

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "crm",
                List.of(item("PayNow", "Pay Now", "Плати сега")));

        assertEquals(1, response.getSummary().getTotal());
        server.verify();
    }

    @Test
    void invalidJsonResponseFallsBackSafely() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("output_text", "{not json")), MediaType.APPLICATION_JSON));

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "crm",
                List.of(item("PayNow", "Pay Now", "Плати сега")));

        assertEquals("Плати сега", response.getItems().get(0).getFinalText());
        assertEquals("fallback_to_google", response.getItems().get(0).getReason());
        server.verify();
    }

    @Test
    void writesReportWithUsageSentStringsAndReceivedStrings() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(openAiSuccessResponse());

        service.reviewTranslations("en", "bg", "crm",
                List.of(item("PayNow", "Pay Now", "ÐŸÐ»Ð°Ñ‚Ð¸ ÑÐµÐ³Ð°")));

        String report = Files.readString(tempDir.resolve("openai-report.csv"));
        assertThat(report, containsString("input_tokens,output_tokens,total_tokens"));
        assertThat(report, containsString("\"PayNow\""));
        assertThat(report, containsString("\"Pay Now\""));
        assertThat(report, containsString("\"ÐŸÐ»Ð°Ñ‚Ð¸ ÑÐµÐ³Ð°\""));
        assertThat(report, containsString(",10,5,15,"));
        server.verify();
    }

    @Test
    void treatsOmittedItemsAsUnchangedToReduceOutputTokens() throws Exception {
        OpenAiTranslationReviewService service = newService(true, "gpt-5.4", 100, 3, 1);
        MockRestServiceServer server = bindMockServer(service);
        String output = mapper.writeValueAsString(Map.of("items", List.of(Map.of(
                "key", "NeedsChange",
                "finalText", "Подобрено",
                "changed", true,
                "reason", "Improved wording.",
                "issues", List.of()
        ))));
        server.expect(requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("output_text", output)), MediaType.APPLICATION_JSON));

        TranslationReviewResponse response = service.reviewTranslations("en", "bg", "crm", List.of(
                item("AlreadyGood", "Cancel", "Отказ"),
                item("NeedsChange", "Submit", "Събмит")
        ));

        assertEquals(2, response.getItems().size());
        assertEquals("Отказ", response.getItems().get(0).getFinalText());
        assertFalse(response.getItems().get(0).isChanged());
        assertEquals("Подобрено", response.getItems().get(1).getFinalText());
        assertTrue(response.getItems().get(1).isChanged());
        assertEquals(1, response.getSummary().getChanged());
        assertEquals(1, response.getSummary().getUnchanged());
        server.verify();
    }

    @Test
    void estimatesTokensWithFallbackCharacterCountDividedByFour() {
        assertEquals(0, OpenAiTranslationReviewService.estimateTokens(""));
        assertEquals(1, OpenAiTranslationReviewService.estimateTokens("a"));
        assertEquals(1, OpenAiTranslationReviewService.estimateTokens("abcd"));
        assertEquals(2, OpenAiTranslationReviewService.estimateTokens("abcde"));
    }

    @Test
    void calculatesCostFromConfiguredInputCachedInputAndOutputPrices() {
        OpenAiTranslationReviewService service = newService(
                true, "gpt-5.4", 100, 3, 1,
                "2.00", "0.50", "8.00", "0"
        );

        BigDecimal cost = service.calculateCost(1_000_000, 250_000, 500_000);

        assertEquals(new BigDecimal("5.625000000000"), cost);
    }

    @Test
    void estimatesOnlyItemsProvidedBySelectedRows() {
        OpenAiTranslationReviewService service = newService(
                true, "gpt-5.4", 100, 3, 1,
                "1.00", "0.00", "1.00", "0"
        );

        var estimate = service.estimateCost("en", "bg", "crm", List.of(
                item("selected.one", "One", "Ð•Ð´Ð½Ð¾"),
                item("selected.two", "Two", "Ð”Ð²Ðµ")
        ));

        assertEquals(2, estimate.getSelectedStringCount());
        assertTrue(estimate.getInputTokens() > 0);
        assertTrue(estimate.getOutputTokens() > 0);
    }

    @Test
    void warnsWhenEstimatedCostExceedsConfiguredThreshold() {
        OpenAiTranslationReviewService service = newService(
                true, "gpt-5.4", 100, 3, 1,
                "1000.00", "0.00", "1000.00", "0.000001"
        );

        var estimate = service.estimateCost("en", "bg", "crm",
                List.of(item("PayNow", "Pay Now", "ÐŸÐ»Ð°Ñ‚Ð¸ ÑÐµÐ³Ð°")));

        assertTrue(estimate.isThresholdExceeded());
        assertThat(estimate.getWarningMessage(), containsString("exceeds the configured threshold"));
    }

    private OpenAiTranslationReviewService newService(boolean enabled, String model, int batchSize, int retries, long backoffMs) {
        return newService(enabled, model, batchSize, retries, backoffMs, "0", "0", "0", "0");
    }

    private OpenAiTranslationReviewService newService(
            boolean enabled,
            String model,
            int batchSize,
            int retries,
            long backoffMs,
            String inputPricePer1M,
            String cachedInputPricePer1M,
            String outputPricePer1M,
            String maxEstimatedCostUsd
    ) {
        return new OpenAiTranslationReviewService(
                enabled,
                "test-key",
                model,
                "https://api.openai.test/v1/",
                60,
                batchSize,
                false,
                retries,
                backoffMs,
                1,
                "low",
                "low",
                "",
                tempDir.resolve("openai-report.csv").toString(),
                new BigDecimal(inputPricePer1M),
                new BigDecimal(cachedInputPricePer1M),
                new BigDecimal(outputPricePer1M),
                new BigDecimal(maxEstimatedCostUsd),
                mapper,
                new RestTemplateBuilder()
        );
    }

    private MockRestServiceServer bindMockServer(OpenAiTranslationReviewService service) throws Exception {
        Field restTemplateField = OpenAiTranslationReviewService.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        RestTemplate restTemplate = (RestTemplate) restTemplateField.get(service);
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    private TranslationReviewItem item(String key, String source, String translated) {
        TranslationReviewItem item = new TranslationReviewItem();
        item.setKey(key);
        item.setSourceText(source);
        item.setTranslatedText(translated);
        return item;
    }

    private org.springframework.test.web.client.ResponseCreator openAiSuccessResponse() {
        return request -> {
            JsonNode requestBody = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String payloadText = requestBody.path("input").get(1).path("content").get(0).path("text").asText();
            JsonNode payload = mapper.readTree(payloadText);
            List<Map<String, Object>> reviewedItems = new ArrayList<>();
            for (JsonNode inputItem : payload.path("items")) {
                Map<String, Object> reviewed = new LinkedHashMap<>();
                reviewed.put("key", inputItem.path("key").asText());
                reviewed.put("finalText", inputItem.path("translatedText").asText());
                reviewed.put("changed", false);
                reviewed.put("reason", "Already acceptable.");
                reviewed.put("issues", List.of());
                reviewedItems.add(reviewed);
            }
            String outputText = mapper.writeValueAsString(Map.of("items", reviewedItems));
            String responseBody = mapper.writeValueAsString(Map.of(
                    "output_text", outputText,
                    "usage", Map.of("input_tokens", 10, "output_tokens", 5, "total_tokens", 15)
            ));
            return withSuccess(responseBody, MediaType.APPLICATION_JSON).createResponse(request);
        };
    }
}
