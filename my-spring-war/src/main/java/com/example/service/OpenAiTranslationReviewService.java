package com.example.service;

import com.example.api.dto.*;
import com.example.config.OutboundApiLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiTranslationReviewService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiTranslationReviewService.class);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[^{}]+}}|\\{[^{}]+}|%\\d*\\$?[sdfoxegc]|<[^>]+>");

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int maxBatchSize;
    private final String reasoningEffort;
    private final String verbosity;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;

    public OpenAiTranslationReviewService(
            @Value("${openai.enabled:true}") boolean enabled,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-5.4}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.timeout-seconds:60}") int timeoutSeconds,
            @Value("${openai.max-batch-size:100}") int maxBatchSize,
            @Value("${openai.reasoning-effort:low}") String reasoningEffort,
            @Value("${openai.verbosity:low}") String verbosity,
            ObjectMapper mapper,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl;
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.reasoningEffort = reasoningEffort;
        this.verbosity = verbosity;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .requestFactory(() -> new BufferingClientHttpRequestFactory(requestFactory))
                .additionalInterceptors(new OutboundApiLoggingInterceptor(mapper))
                .build();
    }

    public TranslationReviewResponse reviewTranslations(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> items) {
        TranslationReviewResponse response = new TranslationReviewResponse();
        if (!enabled || apiKey.isBlank() || items == null || items.isEmpty()) {
            response.setItems(toFallbackItems(items));
            response.setSummary(summarize(response.getItems()));
            return response;
        }

        List<ReviewedTranslationItem> reviewed = new ArrayList<>();
        for (int i = 0; i < items.size(); i += maxBatchSize) {
            List<TranslationReviewItem> batch = items.subList(i, Math.min(items.size(), i + maxBatchSize));
            reviewed.addAll(reviewBatch(sourceLanguage, targetLanguage, context, batch));
        }
        response.setItems(reviewed);
        response.setSummary(summarize(reviewed));
        return response;
    }

    private List<ReviewedTranslationItem> reviewBatch(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> batch) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            Map<String, Object> body = buildRequest(sourceLanguage, targetLanguage, context, batch);
            ResponseEntity<JsonNode> entity = restTemplate.postForEntity(baseUrl + "/responses", new HttpEntity<>(body, headers), JsonNode.class);
            return parseResponse(entity.getBody(), batch);
        } catch (Exception ex) {
            log.warn("OpenAI translation review failed, falling back to Google output. reason={}", ex.getMessage());
            return toFallbackItems(batch);
        }
    }

    private Map<String, Object> buildRequest(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> batch) {
        String instruction = "Review translations and improve only if needed. Preserve placeholders and tags exactly. Return strict JSON object with items[] containing key, finalText, changed, reason, issues.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("reasoning", Map.of("effort", reasoningEffort));
        payload.put("text", Map.of("verbosity", verbosity));
        payload.put("input", List.of(
                Map.of("role", "system", "content", List.of(Map.of("type", "input_text", "text", instruction))),
                Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", mapper.valueToTree(Map.of(
                        "sourceLanguage", sourceLanguage,
                        "targetLanguage", targetLanguage,
                        "context", context,
                        "items", batch
                )).toString())))
        ));
        payload.put("max_output_tokens", 8000);
        return payload;
    }

    private List<ReviewedTranslationItem> parseResponse(JsonNode body, List<TranslationReviewItem> batch) {
        if (body == null) return toFallbackItems(batch);
        JsonNode textNode = body.path("output_text");
        String jsonText = textNode.isTextual() ? textNode.asText() : "";
        if (jsonText.isBlank()) return toFallbackItems(batch);
        try {
            JsonNode parsed = mapper.readTree(jsonText);
            Map<String, TranslationReviewItem> byKey = new LinkedHashMap<>();
            for (TranslationReviewItem item : batch) byKey.put(item.getKey(), item);
            List<ReviewedTranslationItem> out = new ArrayList<>();
            for (JsonNode node : parsed.path("items")) {
                String key = node.path("key").asText();
                TranslationReviewItem original = byKey.get(key);
                if (original == null) continue;
                String finalText = node.path("finalText").asText(original.getTranslatedText());
                if (!placeholdersMatch(original.getSourceText(), finalText)) {
                    finalText = original.getTranslatedText();
                }
                ReviewedTranslationItem reviewed = new ReviewedTranslationItem();
                reviewed.setKey(key);
                reviewed.setFinalText(finalText);
                reviewed.setChanged(!Objects.equals(finalText, original.getTranslatedText()));
                reviewed.setReason(node.path("reason").asText(""));
                reviewed.setIssues(toIssues(node.path("issues")));
                out.add(reviewed);
                byKey.remove(key);
            }
            byKey.values().forEach(item -> out.add(fallbackItem(item)));
            return out;
        } catch (Exception ex) {
            return toFallbackItems(batch);
        }
    }

    private boolean placeholdersMatch(String source, String candidate) {
        return extract(source).equals(extract(candidate));
    }

    private List<String> extract(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    private List<String> toIssues(JsonNode issues) {
        if (!issues.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode issue : issues) list.add(issue.asText(""));
        return list;
    }

    private List<ReviewedTranslationItem> toFallbackItems(List<TranslationReviewItem> items) {
        if (items == null) return List.of();
        return items.stream().map(this::fallbackItem).toList();
    }

    private ReviewedTranslationItem fallbackItem(TranslationReviewItem item) {
        ReviewedTranslationItem fallback = new ReviewedTranslationItem();
        fallback.setKey(item.getKey());
        fallback.setFinalText(item.getTranslatedText());
        fallback.setChanged(false);
        fallback.setReason("fallback_to_google");
        fallback.setIssues(List.of());
        return fallback;
    }

    private TranslationReviewResponse.Summary summarize(List<ReviewedTranslationItem> items) {
        TranslationReviewResponse.Summary summary = new TranslationReviewResponse.Summary();
        summary.setTotal(items.size());
        int changed = (int) items.stream().filter(ReviewedTranslationItem::isChanged).count();
        summary.setChanged(changed);
        summary.setUnchanged(items.size() - changed);
        summary.setFailed(0);
        return summary;
    }
}
