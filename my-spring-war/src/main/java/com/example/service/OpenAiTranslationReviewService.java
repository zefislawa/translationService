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
    private final boolean failOnError;
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
            @Value("${openai.fail-on-error:false}") boolean failOnError,
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
        this.failOnError = failOnError;
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
            List<ReviewedTranslationItem> parsed = parseResponse(entity.getBody(), batch);
            logUsage(entity.getBody(), batch.size(), parsed);
            return parsed;
        } catch (Exception ex) {
            log.warn("OpenAI translation review failed, falling back to Google output. reason={}", ex.getMessage());
            if (failOnError) {
                throw ex;
            }
            return toFallbackItems(batch);
        }
    }

    private Map<String, Object> buildRequest(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> batch) {
        String instruction = """
                You are a localization QA reviewer for software UI strings.

                Review Google-translated strings against the original English source and product context.
                Improve only when needed.

                Rules:
                - Preserve the original meaning.
                - Keep the translation suitable for software UI.
                - Keep wording concise and natural.
                - Preserve placeholders exactly, including {0}, {1}, {{count}}, %s, %d.
                - Preserve HTML tags exactly.
                - Preserve ICU/plural/message syntax exactly.
                - Do not translate product names, technical identifiers, resource keys, or code-like values.
                - Do not invent extra meaning.
                - If the translation is already good, return it unchanged.
                - Return only JSON matching the schema.
                """;
        TranslationReviewRequest request = new TranslationReviewRequest();
        request.setSourceLanguage(sourceLanguage);
        request.setTargetLanguage(targetLanguage);
        request.setContext(context);
        request.setItems(batch);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("store", false);
        payload.put("reasoning", Map.of("effort", reasoningEffort));
        payload.put("text", Map.of(
                "verbosity", verbosity,
                "format", Map.of(
                        "type", "json_schema",
                        "name", "translation_review_result",
                        "strict", true,
                        "schema", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "properties", Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "additionalProperties", false,
                                                        "properties", Map.of(
                                                                "key", Map.of("type", "string"),
                                                                "finalText", Map.of("type", "string"),
                                                                "changed", Map.of("type", "boolean"),
                                                                "reason", Map.of("type", "string"),
                                                                "issues", Map.of("type", "array", "items", Map.of("type", "string"))
                                                        ),
                                                        "required", List.of("key", "finalText", "changed", "reason", "issues")
                                                )
                                        )
                                ),
                                "required", List.of("items")
                        )
                )
        ));
        payload.put("input", List.of(
                Map.of("role", "system", "content", List.of(Map.of("type", "input_text", "text", instruction))),
                Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", mapper.valueToTree(request).toString())))
        ));
        payload.put("max_output_tokens", 8000);
        return payload;
    }

    private List<ReviewedTranslationItem> parseResponse(JsonNode body, List<TranslationReviewItem> batch) {
        if (body == null) return toFallbackItems(batch);
        String jsonText = extractOutputText(body);
        if (jsonText.isBlank()) return toFallbackItems(batch);
        try {
            JsonNode parsed = mapper.readTree(jsonText);
            Map<String, TranslationReviewItem> byKey = new LinkedHashMap<>();
            for (TranslationReviewItem item : batch) byKey.put(item.getKey(), item);
            List<ReviewedTranslationItem> out = new ArrayList<>();
            JsonNode parsedItems = parsed.path("items");
            if (!parsedItems.isArray() || parsedItems.size() != batch.size()) {
                return toValidationFailedFallbackItems(batch);
            }
            for (JsonNode node : parsedItems) {
                String key = node.path("key").asText();
                TranslationReviewItem original = byKey.get(key);
                if (original == null) continue;
                String finalText = node.path("finalText").asText(original.getTranslatedText());
                ReviewedTranslationItem reviewed = new ReviewedTranslationItem();
                reviewed.setKey(key);
                List<String> issues = new ArrayList<>(toIssues(node.path("issues")));
                if (!isValidReviewItem(original, finalText, issues)) {
                    reviewed.setFinalText(original.getTranslatedText());
                    reviewed.setChanged(false);
                    issues.add("openai_validation_failed");
                } else {
                    reviewed.setFinalText(finalText);
                    reviewed.setChanged(!Objects.equals(finalText, original.getTranslatedText()));
                }
                reviewed.setReason(node.path("reason").asText(""));
                reviewed.setIssues(issues);
                out.add(reviewed);
                byKey.remove(key);
            }
            byKey.values().forEach(item -> out.add(fallbackItem(item)));
            return out;
        } catch (Exception ex) {
            log.warn("OpenAI response parse failed, using fallback. reason={}", ex.getMessage());
            return toFallbackItems(batch);
        }
    }

    private String extractOutputText(JsonNode body) {
        JsonNode outputTextNode = body.path("output_text");
        if (outputTextNode.isTextual() && !outputTextNode.asText().isBlank()) {
            return outputTextNode.asText();
        }
        if (outputTextNode.isArray() && !outputTextNode.isEmpty()) {
            StringBuilder collected = new StringBuilder();
            for (JsonNode node : outputTextNode) {
                if (node.isTextual()) {
                    collected.append(node.asText());
                }
            }
            if (!collected.isEmpty()) {
                return collected.toString();
            }
        }
        for (JsonNode outputEntry : body.path("output")) {
            for (JsonNode contentEntry : outputEntry.path("content")) {
                String text = contentEntry.path("text").asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private boolean placeholdersMatch(String source, String candidate) {
        return extract(source).equals(extract(candidate));
    }

    private boolean tagsMatch(String source, String candidate) {
        return extractTags(source).equals(extractTags(candidate));
    }

    private List<String> extractTags(String text) {
        List<String> tags = new ArrayList<>();
        Matcher matcher = Pattern.compile("<[^>]+>").matcher(text == null ? "" : text);
        while (matcher.find()) tags.add(matcher.group());
        return tags;
    }

    private boolean isLikelyIcuBalanced(String value) {
        int braces = 0;
        for (char c : (value == null ? "" : value).toCharArray()) {
            if (c == '{') braces++;
            if (c == '}') braces--;
            if (braces < 0) return false;
        }
        return braces == 0;
    }

    private boolean isValidReviewItem(TranslationReviewItem original, String finalText, List<String> issues) {
        if (finalText == null) return false;
        if (finalText.isBlank() && original.getTranslatedText() != null && !original.getTranslatedText().isBlank()) return false;
        if (!placeholdersMatch(original.getSourceText(), finalText) || !placeholdersMatch(original.getTranslatedText(), finalText)) return false;
        if (!tagsMatch(original.getSourceText(), finalText) || !tagsMatch(original.getTranslatedText(), finalText)) return false;
        if (!isLikelyIcuBalanced(finalText)) return false;
        Integer maxLength = original.getMaxLength();
        if (maxLength != null && maxLength > 0 && finalText.length() > maxLength) {
            issues.add("max_length_exceeded");
            return false;
        }
        return true;
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

    private List<ReviewedTranslationItem> toValidationFailedFallbackItems(List<TranslationReviewItem> items) {
        List<ReviewedTranslationItem> fallback = new ArrayList<>();
        for (TranslationReviewItem item : items) {
            ReviewedTranslationItem reviewed = fallbackItem(item);
            reviewed.setIssues(List.of("openai_validation_failed"));
            fallback.add(reviewed);
        }
        return fallback;
    }

    private void logUsage(JsonNode body, int batchSize, List<ReviewedTranslationItem> reviewedItems) {
        JsonNode usage = body == null ? null : body.path("usage");
        long inputTokens = usage == null ? 0 : usage.path("input_tokens").asLong(0);
        long outputTokens = usage == null ? 0 : usage.path("output_tokens").asLong(0);
        long totalTokens = usage == null ? 0 : usage.path("total_tokens").asLong(0);
        long changed = reviewedItems.stream().filter(ReviewedTranslationItem::isChanged).count();
        long failed = reviewedItems.stream().filter(it -> it.getIssues() != null && it.getIssues().contains("openai_validation_failed")).count();
        log.info("OpenAI translation review completed: model={}, batchSize={}, changed={}, failed={}, inputTokens={}, outputTokens={}, totalTokens={}",
                model, batchSize, changed, failed, inputTokens, outputTokens, totalTokens);
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
