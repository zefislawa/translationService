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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiTranslationReviewService {
    private static final Logger log = LoggerFactory.getLogger(OpenAiTranslationReviewService.class);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[^{}]+}}|\\{[^{}]+}|%\\d*\\$?[sdfoxegc]|<[^>]+>");
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final String DEFAULT_REVIEW_INSTRUCTIONS = """
            You are a localization QA reviewer for software UI strings.

            Review Google-translated strings against the original OpenAI English (UK) source and telecom system/UI product context.
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
            - If the translation is already good, omit that item from the response.
            - Return only items that need a changed finalText or have issues to report.
            - Return only JSON matching the schema.
            """;

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int maxBatchSize;
    private final boolean failOnError;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final int maxConcurrentRequests;
    private final String reasoningEffort;
    private final String verbosity;
    private final String reviewInstructions;
    private final Path reportFile;
    private final Path summaryReportFile;
    private final BigDecimal inputPricePer1M;
    private final BigDecimal cachedInputPricePer1M;
    private final BigDecimal outputPricePer1M;
    private final BigDecimal maxEstimatedCostUsd;
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
            @Value("${openai.max-retries:3}") int maxRetries,
            @Value("${openai.retry-backoff-ms:1000}") long retryBackoffMs,
            @Value("${openai.max-concurrent-requests:1}") int maxConcurrentRequests,
            @Value("${openai.reasoning-effort:low}") String reasoningEffort,
            @Value("${openai.verbosity:low}") String verbosity,
            @Value("${openai.review-instructions:}") String reviewInstructions,
            @Value("${openai.report-path:reports/openai}") String reportPath,
            @Value("${openai.inputPricePer1M:0}") BigDecimal inputPricePer1M,
            @Value("${openai.cachedInputPricePer1M:0}") BigDecimal cachedInputPricePer1M,
            @Value("${openai.outputPricePer1M:0}") BigDecimal outputPricePer1M,
            @Value("${openai.maxEstimatedCostUsd:0}") BigDecimal maxEstimatedCostUsd,
            ObjectMapper mapper,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.failOnError = failOnError;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoffMs = Math.max(1L, retryBackoffMs);
        this.maxConcurrentRequests = Math.max(1, maxConcurrentRequests);
        this.reasoningEffort = reasoningEffort;
        this.verbosity = verbosity;
        this.reviewInstructions = reviewInstructions == null || reviewInstructions.isBlank()
                ? DEFAULT_REVIEW_INSTRUCTIONS
                : reviewInstructions.trim();
        this.reportFile = resolveReportFile(reportPath);
        this.summaryReportFile = resolveSummaryReportFile(this.reportFile);
        this.inputPricePer1M = positiveOrZero(inputPricePer1M);
        this.cachedInputPricePer1M = positiveOrZero(cachedInputPricePer1M);
        this.outputPricePer1M = positiveOrZero(outputPricePer1M);
        this.maxEstimatedCostUsd = positiveOrZero(maxEstimatedCostUsd);
        this.mapper = mapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .requestFactory(() -> new BufferingClientHttpRequestFactory(requestFactory))
                .additionalInterceptors(new OutboundApiLoggingInterceptor(mapper))
                .build();
    }

    public OpenAiCostEstimateResponse estimateCost(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> items) {
        List<TranslationReviewItem> selectedItems = items == null ? List.of() : items;
        long inputTokens = 0;
        long outputTokens = 0;

        for (int i = 0; i < selectedItems.size(); i += maxBatchSize) {
            List<TranslationReviewItem> batch = selectedItems.subList(i, Math.min(selectedItems.size(), i + maxBatchSize));
            inputTokens += estimateTokens(writeJson(buildRequest(sourceLanguage, targetLanguage, context, batch)));
            outputTokens += estimateTokens(writeJson(buildExpectedOutput(batch)));
        }

        long cachedInputTokens = 0;
        BigDecimal estimatedCost = calculateCost(inputTokens, cachedInputTokens, outputTokens);
        boolean thresholdExceeded = maxEstimatedCostUsd.compareTo(BigDecimal.ZERO) > 0
                && estimatedCost.compareTo(maxEstimatedCostUsd) > 0;

        OpenAiCostEstimateResponse response = new OpenAiCostEstimateResponse();
        response.setModel(model);
        response.setSelectedStringCount(selectedItems.size());
        response.setInputTokens(inputTokens);
        response.setCachedInputTokens(cachedInputTokens);
        response.setOutputTokens(outputTokens);
        response.setTotalTokens(inputTokens + outputTokens);
        response.setEstimatedCostUsd(estimatedCost.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        response.setThresholdExceeded(thresholdExceeded);
        if (thresholdExceeded) {
            response.setWarningMessage("Estimated OpenAI cost exceeds the configured threshold of $"
                    + maxEstimatedCostUsd.stripTrailingZeros().toPlainString() + ".");
        }
        return response;
    }

    public TranslationReviewResponse reviewTranslations(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> items) {
        return reviewTranslations(sourceLanguage, targetLanguage, context, items, NEVER_CANCELLED);
    }

    public TranslationReviewResponse reviewTranslations(
            String sourceLanguage,
            String targetLanguage,
            String context,
            List<TranslationReviewItem> items,
            BooleanSupplier cancellationRequested
    ) {
        throwIfCancelled(cancellationRequested);
        List<TranslationReviewItem> selectedItems = items == null ? List.of() : items;
        TranslationReviewResponse response = new TranslationReviewResponse();
        if (!enabled || apiKey.isBlank() || selectedItems.isEmpty()) {
            response.setItems(toFallbackItems(selectedItems));
            response.setSummary(summarize(response.getItems()));
            return response;
        }

        List<ReviewedTranslationItem> reviewed = new ArrayList<>();
        UsageSummary usageSummary = UsageSummary.empty();
        List<List<TranslationReviewItem>> batches = batches(selectedItems);
        if (maxConcurrentRequests <= 1 || batches.size() <= 1) {
            for (List<TranslationReviewItem> batch : batches) {
                throwIfCancelled(cancellationRequested);
                BatchReviewResult batchResult = reviewBatch(sourceLanguage, targetLanguage, context, batch, cancellationRequested);
                reviewed.addAll(batchResult.reviewedItems());
                usageSummary = usageSummary.plus(batchResult.usageSummary());
            }
        } else {
            log.info("Processing OpenAI review with bounded concurrency: batchCount={}, maxConcurrentRequests={}",
                    batches.size(), maxConcurrentRequests);
            BatchReviewResult batchResult = reviewBatchesConcurrently(sourceLanguage, targetLanguage, context, batches, cancellationRequested);
            reviewed.addAll(batchResult.reviewedItems());
            usageSummary = usageSummary.plus(batchResult.usageSummary());
        }
        response.setItems(reviewed);
        TranslationReviewResponse.Summary summary = summarize(reviewed);
        applyUsageSummary(summary, usageSummary);
        response.setSummary(summary);
        writeSummaryReport(sourceLanguage, targetLanguage, context, selectedItems.size(), batches.size(), summary);
        return response;
    }

    private List<List<TranslationReviewItem>> batches(List<TranslationReviewItem> items) {
        List<List<TranslationReviewItem>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += maxBatchSize) {
            batches.add(items.subList(i, Math.min(items.size(), i + maxBatchSize)));
        }
        return batches;
    }

    private BatchReviewResult reviewBatchesConcurrently(
            String sourceLanguage,
            String targetLanguage,
            String context,
            List<List<TranslationReviewItem>> batches,
            BooleanSupplier cancellationRequested
    ) {
        int workerCount = Math.min(maxConcurrentRequests, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CompletionService<BatchReviewResult> completionService = new ExecutorCompletionService<>(executor);
        List<ReviewedTranslationItem> reviewed = new ArrayList<>();
        UsageSummary usageSummary = UsageSummary.empty();
        int submitted = 0;
        int completed = 0;
        int inFlight = 0;

        try {
            while (completed < batches.size()) {
                while (submitted < batches.size() && inFlight < workerCount) {
                    throwIfCancelled(cancellationRequested);
                    List<TranslationReviewItem> batch = batches.get(submitted++);
                    completionService.submit(() -> reviewBatch(sourceLanguage, targetLanguage, context, batch, cancellationRequested));
                    inFlight++;
                }

                throwIfCancelled(cancellationRequested);
                Future<BatchReviewResult> future = completionService.poll(200, TimeUnit.MILLISECONDS);
                if (future == null) {
                    continue;
                }
                BatchReviewResult batchResult = awaitReviewedBatch(future);
                reviewed.addAll(batchResult.reviewedItems());
                usageSummary = usageSummary.plus(batchResult.usageSummary());
                completed++;
                inFlight--;
            }
            return new BatchReviewResult(reviewed, usageSummary);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("OpenAI translation review was interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    private BatchReviewResult awaitReviewedBatch(Future<BatchReviewResult> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for OpenAI review batch", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("OpenAI review batch failed", cause);
        }
    }

    private BatchReviewResult reviewBatch(
            String sourceLanguage,
            String targetLanguage,
            String context,
            List<TranslationReviewItem> batch,
            BooleanSupplier cancellationRequested
    ) {
        try {
            throwIfCancelled(cancellationRequested);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            Map<String, Object> body = buildRequest(sourceLanguage, targetLanguage, context, batch);
            ResponseEntity<JsonNode> entity = executeWithRetry(headers, body, batch.size(), cancellationRequested);
            throwIfCancelled(cancellationRequested);
            List<ReviewedTranslationItem> parsed = parseResponse(entity.getBody(), batch);
            UsageSummary usageSummary = logUsage(entity.getBody(), batch.size(), parsed);
            writeReport(sourceLanguage, targetLanguage, context, batch, entity.getBody(), parsed, usageSummary);
            return new BatchReviewResult(parsed, usageSummary);
        } catch (CancellationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("OpenAI translation review failed, falling back to Google output. reason={}", ex.getMessage());
            if (failOnError) {
                throw ex;
            }
            return new BatchReviewResult(toFallbackItems(batch), UsageSummary.empty());
        }
    }

    private ResponseEntity<JsonNode> executeWithRetry(
            HttpHeaders headers,
            Map<String, Object> body,
            int batchSize,
            BooleanSupplier cancellationRequested
    ) {
        int retriesUsed = 0;
        while (true) {
            try {
                throwIfCancelled(cancellationRequested);
                return restTemplate.postForEntity(baseUrl + "/responses", new HttpEntity<>(body, headers), JsonNode.class);
            } catch (Exception ex) {
                if (ex instanceof CancellationException) {
                    throw ex;
                }
                boolean retryable = isRetryable(ex);
                if (!retryable || retriesUsed >= maxRetries) {
                    throw ex;
                }
                long backoff = retryBackoffMs * (1L << retriesUsed);
                log.warn("Retrying OpenAI review batch after transient failure attempt={} batchSize={} retryInMs={}",
                        retriesUsed + 1, batchSize, backoff);
                sleep(backoff, cancellationRequested);
                retriesUsed++;
            }
        }
    }

    private String stripTrailingSlash(String value) {
        String normalized = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Path resolveReportFile(String configuredReportPath) {
        String rawPath = configuredReportPath == null || configuredReportPath.isBlank()
                ? "reports/openai"
                : configuredReportPath.trim();
        Path configuredPath = Path.of(rawPath).toAbsolutePath().normalize();
        String fileName = configuredPath.getFileName() == null ? "" : configuredPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".csv") || fileName.endsWith(".txt")) {
            return configuredPath;
        }
        return configuredPath.resolve("openai-translation-review-report.csv").normalize();
    }

    private Path resolveSummaryReportFile(Path rowReportFile) {
        Path parent = rowReportFile.getParent();
        String fileName = rowReportFile.getFileName() == null
                ? "openai-translation-review-report.csv"
                : rowReportFile.getFileName().toString();
        String baseName = fileName.replaceFirst("(?i)\\.(csv|txt)$", "");
        if (baseName.endsWith("-report")) {
            baseName = baseName.substring(0, baseName.length() - "-report".length()) + "-summary";
        } else {
            baseName = baseName + "-summary";
        }
        return (parent == null ? Path.of("") : parent).resolve(baseName + ".csv").toAbsolutePath().normalize();
    }

    public Path getReportDirectory() {
        Path parent = reportFile.getParent();
        return parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
    }

    private boolean isRetryable(Exception ex) {
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        if (ex instanceof HttpStatusCodeException httpEx) {
            int status = httpEx.getStatusCode().value();
            return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }
        return false;
    }

    static long estimateTokens(String value) {
        int characterCount = value == null ? 0 : value.length();
        return (long) Math.ceil(characterCount / 4.0);
    }

    BigDecimal calculateCost(long inputTokens, long cachedInputTokens, long outputTokens) {
        long normalizedInputTokens = Math.max(0, inputTokens);
        long normalizedCachedInputTokens = Math.min(Math.max(0, cachedInputTokens), normalizedInputTokens);
        long uncachedInputTokens = normalizedInputTokens - normalizedCachedInputTokens;
        long normalizedOutputTokens = Math.max(0, outputTokens);
        return BigDecimal.valueOf(uncachedInputTokens).multiply(inputPricePer1M)
                .add(BigDecimal.valueOf(normalizedCachedInputTokens).multiply(cachedInputPricePer1M))
                .add(BigDecimal.valueOf(normalizedOutputTokens).multiply(outputPricePer1M))
                .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
    }

    private BigDecimal positiveOrZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize OpenAI cost estimate payload", ex);
        }
    }

    private Map<String, Object> buildExpectedOutput(List<TranslationReviewItem> batch) {
        List<Map<String, Object>> expectedItems = new ArrayList<>();
        int expectedChangedItems = Math.max(1, (int) Math.ceil(batch.size() * 0.1));
        for (TranslationReviewItem item : batch.subList(0, Math.min(batch.size(), expectedChangedItems))) {
            expectedItems.add(Map.of(
                    "key", item.getKey() == null ? "" : item.getKey(),
                    "finalText", expectedFinalText(item),
                    "changed", true,
                    "reason", "Estimated changed/problem item.",
                    "issues", List.of()
            ));
        }
        return Map.of("items", expectedItems);
    }

    private String expectedFinalText(TranslationReviewItem item) {
        if (item.getTranslatedText() != null && !item.getTranslatedText().isBlank()) {
            return item.getTranslatedText();
        }
        return item.getSourceText() == null ? "" : item.getSourceText();
    }

    private void sleep(long delayMs, BooleanSupplier cancellationRequested) {
        long remainingMs = delayMs;
        try {
            while (remainingMs > 0) {
                throwIfCancelled(cancellationRequested);
                long sleepMs = Math.min(remainingMs, 200L);
                TimeUnit.MILLISECONDS.sleep(sleepMs);
                remainingMs -= sleepMs;
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting to retry OpenAI request");
        }
    }

    private Map<String, Object> buildRequest(String sourceLanguage, String targetLanguage, String context, List<TranslationReviewItem> batch) {
        TranslationReviewRequest request = new TranslationReviewRequest();
        request.setSourceLanguage(normalizeSourceLanguage(sourceLanguage));
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
                Map.of("role", "system", "content", List.of(Map.of("type", "input_text", "text", reviewInstructions))),
                Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", mapper.valueToTree(request).toString())))
        ));
        payload.put("max_output_tokens", 4000);
        return payload;
    }

    private void throwIfCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("Translation request was cancelled by user");
        }
    }

    private String normalizeSourceLanguage(String sourceLanguage) {
        if (sourceLanguage == null || sourceLanguage.isBlank()) {
            return "en-GB";
        }
        return sourceLanguage.equalsIgnoreCase("en") ? "en-GB" : sourceLanguage;
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
            if (!parsedItems.isArray()) {
                return toValidationFailedFallbackItems(batch);
            }
            Map<String, ReviewedTranslationItem> reviewedByKey = new LinkedHashMap<>();
            Set<String> seenKeys = new HashSet<>();
            for (JsonNode node : parsedItems) {
                String key = node.path("key").asText();
                TranslationReviewItem original = byKey.get(key);
                if (key.isBlank() || original == null || !seenKeys.add(key)) {
                    return toValidationFailedFallbackItems(batch);
                }
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
                reviewedByKey.put(key, reviewed);
                byKey.remove(key);
            }
            for (TranslationReviewItem item : batch) {
                out.add(reviewedByKey.getOrDefault(item.getKey(), fallbackItem(item)));
            }
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
            fallback.add(validationFailedFallbackItem(item));
        }
        return fallback;
    }

    private ReviewedTranslationItem validationFailedFallbackItem(TranslationReviewItem item) {
        ReviewedTranslationItem reviewed = fallbackItem(item);
        reviewed.setIssues(List.of("openai_validation_failed"));
        return reviewed;
    }

    private UsageSummary logUsage(JsonNode body, int batchSize, List<ReviewedTranslationItem> reviewedItems) {
        UsageSummary usageSummary = extractUsageSummary(body);
        long changed = reviewedItems.stream().filter(ReviewedTranslationItem::isChanged).count();
        long failed = reviewedItems.stream().filter(it -> it.getIssues() != null && it.getIssues().contains("openai_validation_failed")).count();
        log.info("OpenAI translation review completed: model={}, batchSize={}, changed={}, failed={}, inputTokens={}, outputTokens={}, totalTokens={}, estimatedCost={}",
                model,
                batchSize,
                changed,
                failed,
                usageSummary.inputTokens(),
                usageSummary.outputTokens(),
                usageSummary.totalTokens(),
                usageSummary.formattedEstimatedCostUsd());
        return usageSummary;
    }

    private UsageSummary extractUsageSummary(JsonNode body) {
        JsonNode usage = body == null ? null : body.path("usage");
        long inputTokens = usage == null ? 0 : usage.path("input_tokens").asLong(0);
        long outputTokens = usage == null ? 0 : usage.path("output_tokens").asLong(0);
        long totalTokens = usage == null ? 0 : usage.path("total_tokens").asLong(inputTokens + outputTokens);
        long cachedInputTokens = usage == null ? 0 : usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        BigDecimal estimatedCost = calculateCost(inputTokens, cachedInputTokens, outputTokens);
        return new UsageSummary(inputTokens, cachedInputTokens, outputTokens, totalTokens, estimatedCost);
    }

    private synchronized void writeReport(
            String sourceLanguage,
            String targetLanguage,
            String context,
            List<TranslationReviewItem> sentItems,
            JsonNode body,
            List<ReviewedTranslationItem> receivedItems,
            UsageSummary usageSummary
    ) {
        try {
            Path parent = reportFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean writeHeader = !Files.exists(reportFile) || Files.size(reportFile) == 0;
            StringBuilder csv = new StringBuilder();
            if (writeHeader) {
                csv.append("timestamp,response_id,model,source_language,target_language,context,batch_size,input_tokens,output_tokens,total_tokens,cached_input_tokens,estimated_cost_usd,key,sent_source_text,sent_translated_text,received_final_text,received_changed,received_reason,received_issues\n");
            }

            String responseId = body == null ? "" : body.path("id").asText("");
            String timestamp = Instant.now().toString();

            Map<String, ReviewedTranslationItem> receivedByKey = new LinkedHashMap<>();
            if (receivedItems != null) {
                for (ReviewedTranslationItem receivedItem : receivedItems) {
                    receivedByKey.put(receivedItem.getKey(), receivedItem);
                }
            }

            for (TranslationReviewItem sentItem : sentItems) {
                ReviewedTranslationItem receivedItem = receivedByKey.get(sentItem.getKey());
                csv.append(csvCell(timestamp)).append(',')
                        .append(csvCell(responseId)).append(',')
                        .append(csvCell(model)).append(',')
                        .append(csvCell(normalizeSourceLanguage(sourceLanguage))).append(',')
                        .append(csvCell(targetLanguage)).append(',')
                        .append(csvCell(context)).append(',')
                        .append(sentItems.size()).append(',')
                        .append(usageSummary.inputTokens()).append(',')
                        .append(usageSummary.outputTokens()).append(',')
                        .append(usageSummary.totalTokens()).append(',')
                        .append(usageSummary.cachedInputTokens()).append(',')
                        .append(csvCell(usageSummary.formattedEstimatedCostUsd())).append(',')
                        .append(csvCell(sentItem.getKey())).append(',')
                        .append(csvCell(sentItem.getSourceText())).append(',')
                        .append(csvCell(sentItem.getTranslatedText())).append(',')
                        .append(csvCell(receivedItem == null ? "" : receivedItem.getFinalText())).append(',')
                        .append(csvCell(String.valueOf(receivedItem != null && receivedItem.isChanged()))).append(',')
                        .append(csvCell(receivedItem == null ? "" : receivedItem.getReason())).append(',')
                        .append(csvCell(receivedItem == null || receivedItem.getIssues() == null ? "" : String.join(" | ", receivedItem.getIssues())))
                        .append('\n');
            }
            Files.writeString(reportFile, csv.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.warn("Failed to write OpenAI translation review report to {}: {}", reportFile, ex.getMessage());
        }
    }

    private String csvCell(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private synchronized void writeSummaryReport(
            String sourceLanguage,
            String targetLanguage,
            String context,
            int stringCount,
            int batchCount,
            TranslationReviewResponse.Summary summary
    ) {
        try {
            Path parent = summaryReportFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean writeHeader = !Files.exists(summaryReportFile) || Files.size(summaryReportFile) == 0;
            StringBuilder csv = new StringBuilder();
            if (writeHeader) {
                csv.append("timestamp,model,source_language,target_language,context,string_count,batch_count,changed,unchanged,failed,total_input_tokens,total_cached_input_tokens,total_output_tokens,total_tokens,total_estimated_cost_usd\n");
            }
            csv.append(csvCell(Instant.now().toString())).append(',')
                    .append(csvCell(model)).append(',')
                    .append(csvCell(normalizeSourceLanguage(sourceLanguage))).append(',')
                    .append(csvCell(targetLanguage)).append(',')
                    .append(csvCell(context)).append(',')
                    .append(stringCount).append(',')
                    .append(batchCount).append(',')
                    .append(summary.getChanged()).append(',')
                    .append(summary.getUnchanged()).append(',')
                    .append(summary.getFailed()).append(',')
                    .append(summary.getInputTokens()).append(',')
                    .append(summary.getCachedInputTokens()).append(',')
                    .append(summary.getOutputTokens()).append(',')
                    .append(summary.getTotalTokens()).append(',')
                    .append(csvCell(summary.getEstimatedCostUsd()))
                    .append('\n');
            Files.writeString(summaryReportFile, csv.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.warn("Failed to write OpenAI translation review summary report to {}: {}", summaryReportFile, ex.getMessage());
        }
    }

    private TranslationReviewResponse.Summary summarize(List<ReviewedTranslationItem> items) {
        TranslationReviewResponse.Summary summary = new TranslationReviewResponse.Summary();
        summary.setTotal(items.size());
        int changed = (int) items.stream().filter(ReviewedTranslationItem::isChanged).count();
        int failed = (int) items.stream()
                .filter(it -> it.getIssues() != null && it.getIssues().contains("openai_validation_failed"))
                .count();
        summary.setChanged(changed);
        summary.setUnchanged(items.size() - changed - failed);
        summary.setFailed(failed);
        return summary;
    }

    private void applyUsageSummary(TranslationReviewResponse.Summary summary, UsageSummary usageSummary) {
        summary.setInputTokens(usageSummary.inputTokens());
        summary.setCachedInputTokens(usageSummary.cachedInputTokens());
        summary.setOutputTokens(usageSummary.outputTokens());
        summary.setTotalTokens(usageSummary.totalTokens());
        summary.setEstimatedCostUsd(usageSummary.formattedEstimatedCostUsd());
    }

    private record BatchReviewResult(List<ReviewedTranslationItem> reviewedItems, UsageSummary usageSummary) {
    }

    private record UsageSummary(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCostUsd
    ) {
        private static UsageSummary empty() {
            return new UsageSummary(0, 0, 0, 0, BigDecimal.ZERO);
        }

        private UsageSummary plus(UsageSummary other) {
            if (other == null) {
                return this;
            }
            return new UsageSummary(
                    inputTokens + other.inputTokens,
                    cachedInputTokens + other.cachedInputTokens,
                    outputTokens + other.outputTokens,
                    totalTokens + other.totalTokens,
                    estimatedCostUsd.add(other.estimatedCostUsd)
            );
        }

        private String formattedEstimatedCostUsd() {
            return estimatedCostUsd.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
    }
}
