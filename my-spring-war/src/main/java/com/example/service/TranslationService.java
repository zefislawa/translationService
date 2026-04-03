package com.example.service;

import com.example.config.OutboundApiLoggingInterceptor;
import com.example.api.dto.TranslationCompareDifference;
import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationRow;
import com.example.api.dto.SupportedLanguage;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.Collectors;

@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final Path defaultDataDir;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;
    private final String googleCredentialsPath;
    private final String configuredSourceLanguage;
    private final String configuredTargetLanguage;
    private final String googleProjectId;
    private final String googleLocation;
    private final String googleTranslationModel;
    private final boolean googleGlossaryEnabled;
    private final String googleGlossaryId;
    private final int googleBatchSize;
    private final int googleRetryAttempts;
    private final long googleRetryBackoffMs;
    private final String supportedLanguagesDisplayLocale;
    private final String referenceLanguageFile;
    private final String riskyTermsFile;
    private final boolean placeholderProtectionEnabled;
    private final boolean validationEnabled;
    private GoogleCredentials googleCredentials;
    private AccessToken cachedAccessToken;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{[^{}]+}}|\\{[^{}]+}|%\\d*\\$?[sdfoxegc]|<[^>]+>"
    );
    private static final String PLACEHOLDER_TOKEN_PREFIX = "__PH_";
    private static final Pattern PROTECTED_PLACEHOLDER_TOKEN_PATTERN = Pattern.compile("__PH_[A-Z0-9_]+__");
    private static final Set<String> UI_FOCUSED_PREFIXES = Set.of("b", "m", "l");
    private static final Set<String> DEFAULT_AMBIGUOUS_TERMS = Set.of(
            "close", "clear", "apply", "lead", "charge", "rate", "run", "view", "basket", "shopping cart"
    );

    public TranslationService(
            @Value("${myapp.dataDir}") String defaultDataDir,
            @Value("${myapp.google.credentialsPath:}") String googleCredentialsPath,
            @Value("${myapp.google.sourceLanguage:}") String configuredSourceLanguage,
            @Value("${myapp.google.targetLanguage:}") String configuredTargetLanguage,
            @Value("${myapp.google.projectId}") String googleProjectId,
            @Value("${myapp.google.location:global}") String googleLocation,
            @Value("${myapp.google.model:general/translation-llm}") String googleTranslationModel,
            @Value("${myapp.google.glossaryEnabled:false}") boolean googleGlossaryEnabled,
            @Value("${myapp.google.glossaryId:}") String googleGlossaryId,
            @Value("${myapp.google.batchSize:50}") int googleBatchSize,
            @Value("${myapp.google.retryAttempts:3}") int googleRetryAttempts,
            @Value("${myapp.google.retryBackoffMs:500}") long googleRetryBackoffMs,
            @Value("${myapp.google.supportedLanguagesDisplayLocale:en}") String supportedLanguagesDisplayLocale,
            @Value("${myapp.referenceLanguageFile:en}") String referenceLanguageFile,
            @Value("${myapp.riskyTermsFile:risky-terms.txt}") String riskyTermsFile,
            @Value("${myapp.translation.placeholderProtectionEnabled:true}") boolean placeholderProtectionEnabled,
            @Value("${myapp.translation.validationEnabled:true}") boolean validationEnabled,
            ObjectMapper mapper,
            RestTemplateBuilder restTemplateBuilder
    ) throws Exception {
        this.defaultDataDir = Path.of(defaultDataDir).toAbsolutePath();
        this.googleCredentialsPath = googleCredentialsPath;
        this.configuredSourceLanguage = configuredSourceLanguage;
        this.configuredTargetLanguage = configuredTargetLanguage;
        this.googleProjectId = googleProjectId;
        this.googleLocation = googleLocation;
        this.googleTranslationModel = googleTranslationModel;
        this.googleGlossaryEnabled = googleGlossaryEnabled;
        this.googleGlossaryId = googleGlossaryId;
        this.googleBatchSize = googleBatchSize;
        this.googleRetryAttempts = googleRetryAttempts;
        this.googleRetryBackoffMs = googleRetryBackoffMs;
        this.supportedLanguagesDisplayLocale = supportedLanguagesDisplayLocale;
        this.referenceLanguageFile = referenceLanguageFile;
        this.riskyTermsFile = riskyTermsFile;
        this.placeholderProtectionEnabled = placeholderProtectionEnabled;
        this.validationEnabled = validationEnabled;
        requireValidBatchSize();
        requireValidRetrySettings();
        validateGlossaryConfiguration();
        this.mapper = mapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setOutputStreaming(false);

        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(60))
                .requestFactory(() -> new BufferingClientHttpRequestFactory(requestFactory))
                .additionalInterceptors(new OutboundApiLoggingInterceptor(mapper))
                .build();
        Files.createDirectories(this.defaultDataDir);
    }

    public List<String> listJsonFiles(String customPath) throws Exception {
        Path dir = resolveDataDir(customPath);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.toLowerCase().endsWith(".json"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    public List<TranslationRow> loadRows(String customPath, String fileName) throws Exception {
        Path file = resolveJsonFile(customPath, fileName);
        if (!Files.exists(file)) {
            return List.of();
        }

        Path englishFile = resolveJsonFile(customPath, normalizeReferenceLanguageFile(referenceLanguageFile));

        Object raw = mapper.readValue(file.toFile(), Object.class);
        if (raw == null) return List.of();

        if (!(raw instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("Invalid JSON format: expected object at root");
        }

        List<TranslationItem> sourceItems = flattenPrefixTranslationJson(top, file);
        Map<String, Map<String, String>> englishBySection = readSectionMap(englishFile);
        List<TranslationRow> rows = new ArrayList<>(sourceItems.size());
        for (TranslationItem item : sourceItems) {
            String englishReference = englishBySection
                    .getOrDefault(item.prefix(), Map.of())
                    .getOrDefault(item.key(), "");
            rows.add(new TranslationRow(item.prefix(), item.key(), item.sourceText(), englishReference));
        }
        return rows;
    }


    private String normalizeReferenceLanguageFile(String configuredReferenceLanguageFile) {
        if (configuredReferenceLanguageFile == null || configuredReferenceLanguageFile.isBlank()) {
            return "en.json";
        }

        String trimmedValue = configuredReferenceLanguageFile.trim();
        return trimmedValue.toLowerCase().endsWith(".json")
                ? trimmedValue
                : trimmedValue + ".json";
    }

    private Map<String, Map<String, String>> readSectionMap(Path file) throws Exception {
        if (!Files.exists(file)) {
            return Map.of();
        }

        Object raw = mapper.readValue(file.toFile(), Object.class);
        if (!(raw instanceof Map<?, ?> top)) {
            return Map.of();
        }

        List<TranslationItem> items = flattenPrefixTranslationJson(top, file);
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (TranslationItem item : items) {
            result.computeIfAbsent(item.prefix(), ignored -> new LinkedHashMap<>())
                    .put(item.key(), item.sourceText());
        }
        return result;
    }

    private List<TranslationItem> flattenPrefixTranslationJson(Map<?, ?> top, Path sourceFile) {
        List<TranslationItem> items = new ArrayList<>();
        int index = 0;
        for (Map.Entry<?, ?> prefixEntry : top.entrySet()) {
            String prefix = String.valueOf(prefixEntry.getKey());
            Object keyValues = prefixEntry.getValue();
            if (!(keyValues instanceof Map<?, ?> nestedMap)) {
                log.warn("Ignoring non-object prefix value while reading {} at prefix '{}': {}",
                        sourceFile.getFileName(), prefix, keyValues == null ? "null" : keyValues.getClass().getSimpleName());
                continue;
            }

            for (Map.Entry<?, ?> keyEntry : nestedMap.entrySet()) {
                String key = String.valueOf(keyEntry.getKey());
                Object sourceText = keyEntry.getValue();
                if (!(sourceText instanceof String text)) {
                    log.warn("Ignoring non-string translation value while reading {} at '{}.{}': {}",
                            sourceFile.getFileName(), prefix, key, sourceText == null ? "null" : sourceText.getClass().getSimpleName());
                    continue;
                }
                items.add(new TranslationItem(index++, prefix, key, prefix + "." + key, text));
            }
        }
        return items;
    }

    public TranslationExportResult translateAndStore(String customPath, String fileName, String targetLanguage, List<TranslationRow> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("No rows provided for translation");
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }

        String sourceLanguage = resolveSourceLanguage(fileName, rows);
        TranslationPipelineResult pipelineResult = runTranslationPipeline(customPath, rows, sourceLanguage, targetLanguage);
        List<String> translatedTexts = pipelineResult.translatedTexts();

        if (translatedTexts.size() != rows.size()) {
            throw new IllegalStateException("Google Translate returned an unexpected number of translated strings");
        }

        Map<String, String> translatedByFullKey = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            TranslationRow row = rows.get(i);
            translatedByFullKey.put(row.getSection() + "." + row.getKey(), translatedTexts.get(i));
        }

        Path sourceFile = resolveJsonFile(customPath, fileName);
        Path outputFile = sourceFile.getParent().resolve(targetLanguage + ".json").normalize();
        Object sourcePayload = mapper.readValue(sourceFile.toFile(), Object.class);
        Object translatedPayload = rebuildTranslatedPayload(sourcePayload, translatedByFullKey);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), translatedPayload);
        writeValidationReport(outputFile, pipelineResult.validationReport());

        return new TranslationExportResult(outputFile.toAbsolutePath().toString(), targetLanguage, translatedTexts.size());
    }

    private Object rebuildTranslatedPayload(Object sourcePayload, Map<String, String> translatedByFullKey) {
        if (!(sourcePayload instanceof Map<?, ?> sourceTopLevel)) {
            throw new IllegalArgumentException("Invalid source JSON format: expected object at root");
        }

        Map<String, Object> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<?, ?> prefixEntry : sourceTopLevel.entrySet()) {
            String prefix = String.valueOf(prefixEntry.getKey());
            Object prefixValue = prefixEntry.getValue();
            if (!(prefixValue instanceof Map<?, ?> nestedMap)) {
                rebuilt.put(prefix, prefixValue);
                continue;
            }

            Map<String, Object> rebuiltNested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> nestedEntry : nestedMap.entrySet()) {
                String key = String.valueOf(nestedEntry.getKey());
                Object value = nestedEntry.getValue();
                if (value instanceof String) {
                    String fullKey = prefix + "." + key;
                    rebuiltNested.put(key, translatedByFullKey.getOrDefault(fullKey, (String) value));
                } else {
                    rebuiltNested.put(key, value);
                }
            }
            rebuilt.put(prefix, rebuiltNested);
        }
        return rebuilt;
    }

    public TranslationExportResult translateAndImport(
            String customPath,
            String sourceFileName,
            String targetFileName,
            List<TranslationRow> rows
    ) throws Exception {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("No rows provided for translation");
        }

        String sourceLanguage = extractLanguageFromFileName(sourceFileName);
        String targetLanguage = extractLanguageFromFileName(targetFileName);
        TranslationPipelineResult pipelineResult = runTranslationPipeline(customPath, rows, sourceLanguage, targetLanguage);
        List<String> translatedTexts = pipelineResult.translatedTexts();

        if (translatedTexts.size() != rows.size()) {
            throw new IllegalStateException("Google Translate returned an unexpected number of translated strings");
        }

        Path outputFile = resolveJsonFile(customPath, targetFileName);
        Map<String, Map<String, String>> existingPayload = readSectionMap(outputFile);

        for (int i = 0; i < rows.size(); i++) {
            TranslationRow row = rows.get(i);
            String section = row.getSection() == null ? "" : row.getSection().trim();
            String key = row.getKey() == null ? "" : row.getKey().trim();

            if (section.isEmpty() || key.isEmpty()) {
                throw new IllegalArgumentException("Each compare row must include a non-empty section and key");
            }

            existingPayload
                    .computeIfAbsent(section, ignored -> new LinkedHashMap<>())
                    .put(key, translatedTexts.get(i));
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), existingPayload);
        writeValidationReport(outputFile, pipelineResult.validationReport());
        return new TranslationExportResult(outputFile.getFileName().toString(), targetLanguage, translatedTexts.size());
    }

    public TranslationCompareResult compareFiles(String customPath, String fileName1, String fileName2) throws Exception {
        Path file1 = resolveJsonFile(customPath, fileName1);
        Path file2 = resolveJsonFile(customPath, fileName2);

        Map<String, String> flattenedFile1 = flattenForCompare(readSectionMap(file1));
        Map<String, String> flattenedFile2 = flattenForCompare(readSectionMap(file2));

        TreeSet<String> allKeyPaths = new TreeSet<>();
        allKeyPaths.addAll(flattenedFile1.keySet());
        allKeyPaths.addAll(flattenedFile2.keySet());

        List<TranslationCompareDifference> differences = new ArrayList<>();
        for (String keyPath : allKeyPaths) {
            String valueInFile1 = flattenedFile1.get(keyPath);
            String valueInFile2 = flattenedFile2.get(keyPath);

            if (Objects.equals(valueInFile1, valueInFile2)) {
                continue;
            }

            String status;
            if (valueInFile1 == null) {
                status = "Missing in file 1";
            } else if (valueInFile2 == null) {
                status = "Missing in file 2";
            } else {
                status = "Different values";
            }

            differences.add(new TranslationCompareDifference(
                    keyPath,
                    Objects.requireNonNullElse(valueInFile1, ""),
                    Objects.requireNonNullElse(valueInFile2, ""),
                    status
            ));
        }

        return new TranslationCompareResult(fileName1, fileName2, differences);
    }

    private Map<String, String> flattenForCompare(Map<String, Map<String, String>> sections) {
        Map<String, String> flattened = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> sectionEntry : sections.entrySet()) {
            String section = sectionEntry.getKey();
            for (Map.Entry<String, String> keyEntry : sectionEntry.getValue().entrySet()) {
                flattened.put(section + "." + keyEntry.getKey(), keyEntry.getValue());
            }
        }
        return flattened;
    }

    public Path saveRows(String customPath, String fileName, List<TranslationRow> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("No rows provided to save");
        }

        Map<String, Map<String, String>> incomingPayload = new LinkedHashMap<>();
        for (TranslationRow row : rows) {
            if (row == null) {
                continue;
            }

            String section = row.getSection() == null ? "" : row.getSection().trim();
            String key = row.getKey() == null ? "" : row.getKey().trim();

            if (section.isEmpty()) {
                throw new IllegalArgumentException("Each row must have a non-empty section");
            }
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Each row must have a non-empty key");
            }

            incomingPayload
                    .computeIfAbsent(section, ignored -> new LinkedHashMap<>())
                    .put(key, Objects.requireNonNullElse(row.getText(), ""));
        }

        Path outputFile = resolveJsonFile(customPath, fileName);
        Map<String, Map<String, String>> existingPayload = readSectionMap(outputFile);
        Map<String, Map<String, String>> payload = mergeWithExistingOrder(existingPayload, incomingPayload);
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), payload);
        return outputFile;
    }

    private Map<String, Map<String, String>> mergeWithExistingOrder(
            Map<String, Map<String, String>> existingPayload,
            Map<String, Map<String, String>> incomingPayload
    ) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> existingSectionEntry : existingPayload.entrySet()) {
            String section = existingSectionEntry.getKey();
            if (!incomingPayload.containsKey(section)) {
                continue;
            }

            Map<String, String> mergedSection = new LinkedHashMap<>();
            Map<String, String> existingSectionValues = existingSectionEntry.getValue();
            Map<String, String> incomingSectionValues = incomingPayload.get(section);

            for (String existingKey : existingSectionValues.keySet()) {
                if (incomingSectionValues.containsKey(existingKey)) {
                    mergedSection.put(existingKey, incomingSectionValues.get(existingKey));
                }
            }

            for (Map.Entry<String, String> incomingEntry : incomingSectionValues.entrySet()) {
                mergedSection.putIfAbsent(incomingEntry.getKey(), incomingEntry.getValue());
            }

            merged.put(section, mergedSection);
        }

        for (Map.Entry<String, Map<String, String>> incomingSectionEntry : incomingPayload.entrySet()) {
            merged.putIfAbsent(incomingSectionEntry.getKey(), new LinkedHashMap<>(incomingSectionEntry.getValue()));
        }

        return merged;
    }

    public List<SupportedLanguage> getSupportedLanguages() {
        requireGoogleProjectId();
        String url = UriComponentsBuilder
                .fromHttpUrl("https://translation.googleapis.com/v3/projects/" + googleProjectId + "/locations/" + googleLocation + "/supportedLanguages")
                .queryParam("displayLanguageCode", supportedLanguagesDisplayLocale)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resolveAccessTokenValue());

        ResponseEntity<GoogleSupportedLanguagesResponse> response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                GoogleSupportedLanguagesResponse.class
        );

        GoogleSupportedLanguagesResponse responseBody = response.getBody();
        if (responseBody == null || responseBody.languages() == null) {
            throw new IllegalStateException("Google supported languages response is empty");
        }

        return responseBody.languages().stream()
                .filter(language -> language.languageCode() != null && !language.languageCode().isBlank())
                .map(language -> new SupportedLanguage(language.languageCode(), Objects.requireNonNullElse(language.displayName(), language.languageCode())))
                .sorted(Comparator.comparing(SupportedLanguage::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private TranslationPipelineResult runTranslationPipeline(String customPath, List<TranslationRow> rows, String sourceLanguage, String targetLanguage) {
        List<TranslationItem> flattenedItems = flattenRows(rows);
        Set<String> configuredRiskyTerms = loadConfiguredRiskyTerms(customPath);
        List<PreparedTranslationItem> preprocessedItems = preprocessItems(flattenedItems, configuredRiskyTerms);
        List<PreparedTranslationItem> protectedItems = placeholderProtectionEnabled
                ? protectPlaceholders(preprocessedItems)
                : preprocessedItems;
        List<TranslatedItemResult> translatedItems = sourceLanguage.equalsIgnoreCase(targetLanguage)
                ? protectedItems.stream()
                .map(item -> new TranslatedItemResult(
                        item.item().index(),
                        item.item().fullKey(),
                        item.protectedText(),
                        "identity/no-translation",
                        item.metadata().risky(),
                        item.metadata().riskReason()
                ))
                .toList()
                : translateByRouteV1(sourceLanguage, targetLanguage, protectedItems);
        List<String> translatedProtectedTexts = translatedItems.stream()
                .sorted(Comparator.comparingInt(TranslatedItemResult::index))
                .map(TranslatedItemResult::translatedText)
                .toList();

        if (translatedProtectedTexts.size() != protectedItems.size()) {
            throw new IllegalStateException("Google Translation API returned an unexpected number of translated strings");
        }

        List<String> restoredTexts = placeholderProtectionEnabled
                ? restorePlaceholders(protectedItems, translatedProtectedTexts)
                : translatedProtectedTexts;
        ValidationReport validationReport = validationEnabled
                ? validateResults(
                protectedItems,
                translatedItems,
                restoredTexts,
                sourceLanguage,
                targetLanguage,
                configuredRiskyTerms
        )
                : createValidationSkippedReport(protectedItems, translatedItems, restoredTexts);
        return new TranslationPipelineResult(restoredTexts, validationReport);
    }

    private ValidationReport createValidationSkippedReport(
            List<PreparedTranslationItem> items,
            List<TranslatedItemResult> translatedItems,
            List<String> restoredTexts
    ) {
        List<PreprocessingReportItem> preprocessingItems = new ArrayList<>(items.size());
        List<ValidationReportRow> rows = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PreparedTranslationItem item = items.get(i);
            TranslatedItemResult translatedItem = translatedItems.get(i);
            String restored = restoredTexts.get(i);
            PreprocessingMetadata metadata = item.metadata();
            preprocessingItems.add(new PreprocessingReportItem(
                    item.item().fullKey(),
                    item.item().prefix(),
                    item.item().key(),
                    metadata.wordCount(),
                    metadata.shortText(),
                    metadata.containsPlaceholders(),
                    metadata.placeholders(),
                    metadata.risky(),
                    metadata.riskReason()
            ));
            rows.add(new ValidationReportRow(
                    item.item().fullKey(),
                    item.item().prefix(),
                    item.normalizedText(),
                    item.protectedText(),
                    translatedItem.translatedText(),
                    restored,
                    item.metadata().risky(),
                    translatedItem.route(),
                    "VALID",
                    new ArrayList<>()
            ));
        }

        Map<String, Long> countsByPrefix = rows.stream()
                .collect(Collectors.groupingBy(ValidationReportRow::prefix, LinkedHashMap::new, Collectors.counting()));
        return new ValidationReport(
                rows,
                preprocessingItems,
                List.of(),
                new ValidationSummary(
                        items.size(),
                        items.size(),
                        0,
                        0,
                        Map.of(),
                        countsByPrefix,
                        0
                )
        );
    }

    private List<TranslationItem> flattenRows(List<TranslationRow> rows) {
        List<TranslationItem> items = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            TranslationRow row = rows.get(i);
            String prefix = row.getSection();
            String key = row.getKey();
            items.add(new TranslationItem(i, prefix, key, prefix + "." + key, Objects.requireNonNullElse(row.getText(), "")));
        }
        return items;
    }

    private List<PreparedTranslationItem> preprocessItems(List<TranslationItem> items, Set<String> configuredRiskyTerms) {
        return items.stream()
                .map(item -> {
                    String normalizedText = item.sourceText().replace("\r\n", "\n");
                    List<String> placeholders = extractPlaceholders(normalizedText);
                    int wordCount = countWords(normalizedText);
                    boolean isShortText = wordCount <= 3;
                    boolean isRiskyPrefix = isShortText && UI_FOCUSED_PREFIXES.contains(normalizePrefix(item.prefix()));
                    boolean hasAmbiguousTerm = containsAmbiguousTerm(normalizedText);
                    boolean hasConfiguredRiskyTerm = containsConfiguredRiskyTerm(normalizedText, configuredRiskyTerms);
                    boolean risky = isRiskyPrefix || hasAmbiguousTerm || hasConfiguredRiskyTerm;
                    String riskReason = buildRiskReason(isRiskyPrefix, hasAmbiguousTerm, hasConfiguredRiskyTerm);

                    PreprocessingMetadata metadata = new PreprocessingMetadata(
                            wordCount,
                            isShortText,
                            !placeholders.isEmpty(),
                            placeholders,
                            risky,
                            riskReason
                    );
                    return new PreparedTranslationItem(item, normalizedText, normalizedText, Map.of(), metadata);
                })
                .toList();
    }

    private List<PreparedTranslationItem> protectPlaceholders(List<PreparedTranslationItem> items) {
        List<PreparedTranslationItem> result = new ArrayList<>(items.size());
        for (PreparedTranslationItem item : items) {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(item.normalizedText());
            Map<String, String> placeholderMap = new LinkedHashMap<>();
            Map<String, String> placeholderToToken = new LinkedHashMap<>();
            StringBuffer protectedBuffer = new StringBuffer();
            while (matcher.find()) {
                String placeholder = matcher.group();
                String token = placeholderToToken.computeIfAbsent(
                        placeholder,
                        key -> buildProtectedPlaceholderToken(key, item.normalizedText(), placeholderMap.keySet())
                );
                placeholderMap.putIfAbsent(token, placeholder);
                matcher.appendReplacement(protectedBuffer, Matcher.quoteReplacement(token));
            }
            matcher.appendTail(protectedBuffer);
            result.add(new PreparedTranslationItem(
                    item.item(),
                    item.normalizedText(),
                    protectedBuffer.toString(),
                    placeholderMap,
                    item.metadata()
            ));
        }
        return result;
    }

    private List<String> callGoogleTranslationLlm(
            String sourceLanguage,
            String targetLanguage,
            List<PreparedTranslationItem> items
    ) {
        requireGoogleProjectId();

        String endpoint = "https://translation.googleapis.com/v3/projects/" + googleProjectId
                + "/locations/" + googleLocation + ":translateText";
        String url = UriComponentsBuilder.fromHttpUrl(endpoint).toUriString();

        List<String> allTranslations = new ArrayList<>(items.size());
        for (int start = 0; start < items.size(); start += googleBatchSize) {
            int end = Math.min(start + googleBatchSize, items.size());
            List<PreparedTranslationItem> batchItems = items.subList(start, end);
            List<String> contents = batchItems.stream()
                    .map(PreparedTranslationItem::protectedText)
                    .toList();
            GoogleTranslateTextRequest body = new GoogleTranslateTextRequest(
                    contents,
                    sourceLanguage,
                    targetLanguage,
                    "text/plain",
                    googleTranslationModel,
                    resolveGlossaryConfig()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resolveAccessTokenValue());

            log.info("Sending translation batch start={}, endExclusive={}, batchSize={}, source={}, target={}, model={}, glossaryEnabled={}",
                    start, end, batchItems.size(), sourceLanguage, targetLanguage, googleTranslationModel, googleGlossaryEnabled);

            ResponseEntity<GoogleTranslateTextResponse> response = executeTranslateBatchWithRetry(
                    url,
                    body,
                    headers,
                    start,
                    end
            );

            GoogleTranslateTextResponse responseBody = response.getBody();
            if (responseBody == null || responseBody.translations() == null) {
                throw new IllegalStateException("Google Translate response is empty");
            }

            List<String> translations = responseBody.translations()
                    .stream()
                    .map(GoogleTextTranslation::translatedText)
                    .toList();
            List<String> glossaryTranslations = responseBody.glossaryTranslations() == null
                    ? List.of()
                    : responseBody.glossaryTranslations().stream()
                    .map(GoogleTextTranslation::translatedText)
                    .toList();

            List<String> selectedTranslations = glossaryTranslations.size() == translations.size()
                    ? glossaryTranslations
                    : translations;
            if (selectedTranslations.size() != contents.size()) {
                throw new IllegalStateException("Google Translate returned an unexpected number of translated strings");
            }
            allTranslations.addAll(selectedTranslations);
        }
        return allTranslations;
    }

    private List<TranslatedItemResult> translateByRouteV1(String sourceLanguage, String targetLanguage, List<PreparedTranslationItem> items) {
        List<String> translatedTexts = callGoogleTranslationLlm(sourceLanguage, targetLanguage, items);
        if (translatedTexts.size() != items.size()) {
            throw new IllegalStateException("Translation route v1 returned an unexpected number of translated strings");
        }

        List<TranslatedItemResult> perItemResults = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PreparedTranslationItem item = items.get(i);
            perItemResults.add(new TranslatedItemResult(
                    item.item().index(),
                    item.item().fullKey(),
                    translatedTexts.get(i),
                    "google-translation-advanced/translateText/translation-llm",
                    item.metadata().risky(),
                    item.metadata().riskReason()
            ));
        }
        return perItemResults.stream()
                .sorted(Comparator.comparingInt(TranslatedItemResult::index))
                .toList();
    }

    private ResponseEntity<GoogleTranslateTextResponse> executeTranslateBatchWithRetry(
            String url,
            GoogleTranslateTextRequest body,
            HttpHeaders headers,
            int start,
            int end
    ) {
        int attempt = 1;
        while (true) {
            try {
                return restTemplate.postForEntity(
                        url,
                        new HttpEntity<>(body, headers),
                        GoogleTranslateTextResponse.class
                );
            } catch (ResourceAccessException | HttpStatusCodeException ex) {
                boolean transientFailure = isTransientFailure(ex);
                if (!transientFailure || attempt >= googleRetryAttempts) {
                    String statusCode = ex instanceof HttpStatusCodeException statusException
                            ? String.valueOf(statusException.getStatusCode().value())
                            : "n/a";
                    log.error("Translation batch failed after attempt={} for range=[{}, {}) status={} message={}",
                            attempt, start, end, statusCode, sanitizeErrorMessage(ex.getMessage()));
                    throw ex;
                }
                long delayMs = googleRetryBackoffMs * (1L << (attempt - 1));
                log.warn("Transient translation failure attempt={} for range=[{}, {}) statusOrType={}, retryInMs={}",
                        attempt,
                        start,
                        end,
                        ex instanceof HttpStatusCodeException statusException
                                ? statusException.getStatusCode().value()
                                : ex.getClass().getSimpleName(),
                        delayMs);
                sleepQuietly(delayMs);
                attempt++;
            }
        }
    }

    private boolean isTransientFailure(Exception ex) {
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        if (ex instanceof HttpStatusCodeException statusException) {
            int code = statusException.getStatusCode().value();
            return code == 429 || (code >= 500 && code < 600);
        }
        return false;
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "n/a";
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    private void sleepQuietly(long delayMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Google Translate request", interruptedException);
        }
    }

    private List<String> restorePlaceholders(List<PreparedTranslationItem> items, List<String> translatedTexts) {
        List<String> restored = new ArrayList<>(translatedTexts.size());
        for (int i = 0; i < items.size(); i++) {
            PreparedTranslationItem item = items.get(i);
            String text = translatedTexts.get(i);
            for (Map.Entry<String, String> placeholder : item.placeholders().entrySet()) {
                text = text.replace(placeholder.getKey(), placeholder.getValue());
            }
            restored.add(text);
        }
        return restored;
    }

    private String buildProtectedPlaceholderToken(String placeholder, String sourceText, Set<String> existingTokens) {
        String innerValue = placeholder;
        if (placeholder.startsWith("{{") && placeholder.endsWith("}}")) {
            innerValue = placeholder.substring(2, placeholder.length() - 2);
        } else if (placeholder.startsWith("{") && placeholder.endsWith("}")) {
            innerValue = placeholder.substring(1, placeholder.length() - 1);
        }

        String normalizedInnerValue = innerValue.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");

        if (normalizedInnerValue.isBlank()) {
            normalizedInnerValue = "TOKEN";
        }

        String baseToken = PLACEHOLDER_TOKEN_PREFIX + normalizedInnerValue + "__";
        String token = baseToken;
        int suffix = 2;
        while (existingTokens.contains(token) || sourceText.contains(token)) {
            token = PLACEHOLDER_TOKEN_PREFIX + normalizedInnerValue + "_" + suffix++ + "__";
        }
        return token;
    }

    private ValidationReport validateResults(
            List<PreparedTranslationItem> items,
            List<TranslatedItemResult> translatedItems,
            List<String> restoredTexts,
            String sourceLanguage,
            String targetLanguage,
            Set<String> configuredRiskyTerms
    ) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<PreprocessingReportItem> preprocessingItems = new ArrayList<>();
        List<ValidationReportRow> reportRows = new ArrayList<>();
        Map<String, Map<String, Set<String>>> translationsByNormalizedSource = new LinkedHashMap<>();
        int duplicateInconsistencyFindings = 0;

        for (int i = 0; i < items.size(); i++) {
            PreparedTranslationItem item = items.get(i);
            TranslatedItemResult translatedItem = translatedItems.get(i);
            String translatedProtected = translatedItem.translatedText();
            String restored = restoredTexts.get(i);
            String fullKey = item.item().fullKey();
            List<String> itemIssueMessages = new ArrayList<>();
            boolean hasError = false;
            boolean hasWarning = false;

            Set<String> missingTokens = new HashSet<>();
            for (String token : item.placeholders().keySet()) {
                if (!translatedProtected.contains(token)) {
                    missingTokens.add(token);
                }
            }
            if (!missingTokens.isEmpty()) {
                String message = "missing placeholder tokens " + missingTokens;
                issues.add(new ValidationIssue(fullKey, item.item().prefix(), "missing-placeholder-token", "ERROR", message));
                itemIssueMessages.add(message);
                hasError = true;
            }

            if (restored.isBlank() && !item.normalizedText().isBlank()) {
                String message = "translated result became blank";
                issues.add(new ValidationIssue(fullKey, item.item().prefix(), "blank-translation", "ERROR", message));
                itemIssueMessages.add(message);
                hasError = true;
            }

            Matcher unresolvedTokenMatcher = PROTECTED_PLACEHOLDER_TOKEN_PATTERN.matcher(restored);
            if (unresolvedTokenMatcher.find()) {
                String message = "unresolved placeholder tokens remained after restoration";
                issues.add(new ValidationIssue(fullKey, item.item().prefix(), "leftover-protected-token", "ERROR", message));
                itemIssueMessages.add(message);
                hasError = true;
            }

            List<String> missingOriginalPlaceholders = new ArrayList<>();
            for (String placeholder : item.placeholders().values()) {
                if (!restored.contains(placeholder)) {
                    missingOriginalPlaceholders.add(placeholder);
                }
            }
            if (!missingOriginalPlaceholders.isEmpty()) {
                String message = "missing restored placeholders " + missingOriginalPlaceholders;
                issues.add(new ValidationIssue(fullKey, item.item().prefix(), "missing-restored-placeholder", "ERROR", message));
                itemIssueMessages.add(message);
                hasError = true;
            }

            if (!sourceLanguage.equalsIgnoreCase(targetLanguage) && containsConfiguredRiskyTerm(item.normalizedText(), configuredRiskyTerms)) {
                String normalizedSource = normalizeForTermCheck(item.normalizedText());
                String normalizedRestored = normalizeForTermCheck(restored);
                for (String glossaryTerm : configuredRiskyTerms) {
                    if (normalizedSource.contains(glossaryTerm) && normalizedRestored.contains(glossaryTerm)) {
                        String message = "possible untranslated glossary term leak: '" + glossaryTerm + "'";
                        issues.add(new ValidationIssue(fullKey, item.item().prefix(), "glossary-term-leak", "WARNING", message));
                        itemIssueMessages.add(message);
                        hasWarning = true;
                    }
                }
            }

            if (item.metadata().shortText()) {
                int sourceLength = item.normalizedText().trim().length();
                int restoredLength = restored.trim().length();
                if (sourceLength > 0 && restoredLength > sourceLength * 3 + 15) {
                    String message = "short UI text expanded significantly (" + sourceLength + " -> " + restoredLength + " chars)";
                    issues.add(new ValidationIssue(fullKey, item.item().prefix(), "short-ui-expansion", "WARNING", message));
                    itemIssueMessages.add(message);
                    hasWarning = true;
                }
            }

            String normalizedSource = normalizeForTermCheck(item.normalizedText());
            if (!normalizedSource.isEmpty()) {
                translationsByNormalizedSource
                        .computeIfAbsent(normalizedSource, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(restored, ignored -> new LinkedHashSet<>())
                        .add(fullKey);
            }

            PreprocessingMetadata metadata = item.metadata();
            preprocessingItems.add(new PreprocessingReportItem(
                    fullKey,
                    item.item().prefix(),
                    item.item().key(),
                    metadata.wordCount(),
                    metadata.shortText(),
                    metadata.containsPlaceholders(),
                    metadata.placeholders(),
                    metadata.risky(),
                    metadata.riskReason()
            ));
            reportRows.add(new ValidationReportRow(
                    fullKey,
                    item.item().prefix(),
                    item.normalizedText(),
                    item.protectedText(),
                    translatedProtected,
                    restored,
                    item.metadata().risky(),
                    translatedItem.route(),
                    hasError ? "INVALID" : hasWarning ? "WARNING" : "VALID",
                    itemIssueMessages
            ));
        }

        for (Map.Entry<String, Map<String, Set<String>>> entry : translationsByNormalizedSource.entrySet()) {
            Map<String, Set<String>> translationToKeys = entry.getValue();
            if (translationToKeys.size() <= 1) {
                continue;
            }
            String message = "same source text produced inconsistent translations in this run";
            for (Set<String> keys : translationToKeys.values()) {
                for (String key : keys) {
                    ValidationReportRow row = reportRows.stream()
                            .filter(validation -> validation.fullKey().equals(key))
                            .findFirst()
                            .orElse(null);
                    String prefix = row == null ? "" : row.prefix();
                    issues.add(new ValidationIssue(key, prefix, "duplicate-inconsistency", "WARNING", message));
                    duplicateInconsistencyFindings++;
                    if (row != null) {
                        row.issuesOrWarnings().add(message);
                        if ("VALID".equals(row.validationStatus())) {
                            row.validationStatus = "WARNING";
                        }
                    }
                }
            }
        }

        int invalidCount = (int) reportRows.stream().filter(row -> "INVALID".equals(row.validationStatus())).count();
        int warningCount = (int) reportRows.stream().filter(row -> "WARNING".equals(row.validationStatus())).count();
        int validCount = reportRows.size() - invalidCount - warningCount;
        Map<String, Long> issueCountsByType = issues.stream()
                .collect(Collectors.groupingBy(ValidationIssue::type, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> countsByPrefix = reportRows.stream()
                .collect(Collectors.groupingBy(ValidationReportRow::prefix, LinkedHashMap::new, Collectors.counting()));

        return new ValidationReport(
                reportRows,
                preprocessingItems,
                issues,
                new ValidationSummary(
                        items.size(),
                        validCount,
                        invalidCount,
                        warningCount,
                        issueCountsByType,
                        countsByPrefix,
                        duplicateInconsistencyFindings
                )
        );
    }

    private int countWords(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return (int) Arrays.stream(trimmed.split("\\s+"))
                .filter(token -> !token.isBlank())
                .count();
    }

    private List<String> extractPlaceholders(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        Set<String> placeholders = new LinkedHashSet<>();
        while (matcher.find()) {
            placeholders.add(matcher.group());
        }
        return List.copyOf(placeholders);
    }

    private boolean containsAmbiguousTerm(String text) {
        String normalizedText = normalizeForTermCheck(text);
        for (String term : DEFAULT_AMBIGUOUS_TERMS) {
            if (normalizedText.contains(normalizeForTermCheck(term))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsConfiguredRiskyTerm(String text, Set<String> configuredRiskyTerms) {
        if (configuredRiskyTerms.isEmpty()) {
            return false;
        }
        String normalizedText = normalizeForTermCheck(text);
        for (String term : configuredRiskyTerms) {
            if (normalizedText.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForTermCheck(String text) {
        return text == null
                ? ""
                : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String normalizePrefix(String prefix) {
        return prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
    }

    private String buildRiskReason(boolean riskyPrefix, boolean ambiguousTerm, boolean configuredTerm) {
        List<String> reasons = new ArrayList<>();
        if (riskyPrefix) {
            reasons.add("short-ui-prefix");
        }
        if (ambiguousTerm) {
            reasons.add("ambiguous-term");
        }
        if (configuredTerm) {
            reasons.add("configured-risky-term");
        }
        return String.join(",", reasons);
    }

    private Set<String> loadConfiguredRiskyTerms(String customPath) {
        if (riskyTermsFile == null || riskyTermsFile.isBlank()) {
            return Set.of();
        }

        Path riskyTermsPath = Path.of(riskyTermsFile);
        if (!riskyTermsPath.isAbsolute()) {
            try {
                riskyTermsPath = resolveDataDir(customPath).resolve(riskyTermsPath).normalize();
            } catch (Exception e) {
                log.warn("Failed to resolve risky terms file path '{}': {}", riskyTermsFile, e.getMessage());
                return Set.of();
            }
        }

        if (!Files.exists(riskyTermsPath)) {
            return Set.of();
        }

        try (Stream<String> lines = Files.lines(riskyTermsPath)) {
            Set<String> terms = lines
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .map(this::normalizeForTermCheck)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return Collections.unmodifiableSet(terms);
        } catch (Exception e) {
            log.warn("Failed to load risky terms from {}: {}", riskyTermsPath, e.getMessage());
            return Set.of();
        }
    }

    private void writeValidationReport(Path outputFile, ValidationReport report) throws Exception {
        String fileName = outputFile.getFileName().toString().replaceFirst("(?i)\\.json$", "");
        Path jsonReportFile = outputFile.getParent().resolve(fileName + ".validation-report.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(jsonReportFile.toFile(), report);
        Path csvReportFile = outputFile.getParent().resolve(fileName + ".validation-report.csv");
        Files.writeString(csvReportFile, buildCsvReport(report), StandardCharsets.UTF_8);
    }

    private String buildCsvReport(ValidationReport report) {
        StringBuilder csv = new StringBuilder();
        csv.append("full_key,prefix,source_text,protected_source_text,translated_protected_text,final_translated_text,risky_flag,route_used,validation_status,issues_or_warnings\n");
        for (ValidationReportRow row : report.rows()) {
            String issuesJoined = String.join(" | ", row.issuesOrWarnings());
            csv.append(csvCell(row.fullKey())).append(',')
                    .append(csvCell(row.prefix())).append(',')
                    .append(csvCell(row.sourceText())).append(',')
                    .append(csvCell(row.protectedSourceText())).append(',')
                    .append(csvCell(row.translatedProtectedText())).append(',')
                    .append(csvCell(row.finalTranslatedText())).append(',')
                    .append(csvCell(String.valueOf(row.riskyFlag()))).append(',')
                    .append(csvCell(row.routeUsed())).append(',')
                    .append(csvCell(row.validationStatus())).append(',')
                    .append(csvCell(issuesJoined))
                    .append('\n');
        }
        csv.append('\n');
        csv.append("summary_metric,value\n");
        csv.append("total_strings_processed,").append(report.summary().totalStringsProcessed()).append('\n');
        csv.append("valid_count,").append(report.summary().validCount()).append('\n');
        csv.append("invalid_count,").append(report.summary().invalidCount()).append('\n');
        csv.append("warning_count,").append(report.summary().warningCount()).append('\n');
        csv.append("duplicate_inconsistency_findings,").append(report.summary().duplicateInconsistencyFindings()).append('\n');
        for (Map.Entry<String, Long> issueEntry : report.summary().issueCountsByType().entrySet()) {
            csv.append(csvCell("issue_count_by_type:" + issueEntry.getKey())).append(',')
                    .append(issueEntry.getValue()).append('\n');
        }
        for (Map.Entry<String, Long> prefixEntry : report.summary().countsByPrefix().entrySet()) {
            csv.append(csvCell("count_by_prefix:" + prefixEntry.getKey())).append(',')
                    .append(prefixEntry.getValue()).append('\n');
        }
        return csv.toString();
    }

    private String csvCell(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private GoogleGlossaryConfig resolveGlossaryConfig() {
        if (!googleGlossaryEnabled || googleGlossaryId == null || googleGlossaryId.isBlank()) {
            return null;
        }

        String glossaryPath = googleGlossaryId.startsWith("projects/")
                ? googleGlossaryId
                : "projects/" + googleProjectId + "/locations/" + googleLocation + "/glossaries/" + googleGlossaryId;
        return new GoogleGlossaryConfig(glossaryPath);
    }

    private void requireGoogleProjectId() {
        if (googleProjectId == null || googleProjectId.isBlank()) {
            throw new IllegalStateException("Google project id is missing. Configure it via environment variable or local.properties");
        }
    }

    private synchronized String resolveAccessTokenValue() {
        try {
            if (googleCredentials == null) {
                googleCredentials = loadGoogleCredentials().createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
            if (cachedAccessToken == null || tokenExpiringSoon(cachedAccessToken)) {
                googleCredentials.refreshIfExpired();
                cachedAccessToken = googleCredentials.getAccessToken();
            }
            if (cachedAccessToken == null || cachedAccessToken.getTokenValue() == null || cachedAccessToken.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google access token is empty after credential refresh.");
            }
            return cachedAccessToken.getTokenValue();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to resolve Google Application Default Credentials. Set GOOGLE_APPLICATION_CREDENTIALS or myapp.google.credentialsPath.",
                    ex
            );
        }
    }

    private GoogleCredentials loadGoogleCredentials() throws Exception {
        if (googleCredentialsPath != null && !googleCredentialsPath.isBlank()) {
            Path credentialsPath = Path.of(googleCredentialsPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(credentialsPath)) {
                throw new IllegalStateException("Google credentials file was not found at: " + credentialsPath);
            }
            try (InputStream credentialsStream = Files.newInputStream(credentialsPath)) {
                return GoogleCredentials.fromStream(credentialsStream);
            }
        }
        return GoogleCredentials.getApplicationDefault();
    }

    private boolean tokenExpiringSoon(AccessToken token) {
        Instant expiration = token.getExpirationTime() == null ? null : token.getExpirationTime().toInstant();
        return expiration == null || expiration.minusSeconds(60).isBefore(Instant.now());
    }

    private void requireValidBatchSize() {
        if (googleBatchSize <= 0) {
            throw new IllegalStateException("Google batch size must be greater than zero");
        }
    }

    private void requireValidRetrySettings() {
        if (googleRetryAttempts <= 0) {
            throw new IllegalStateException("Google retry attempts must be greater than zero");
        }
        if (googleRetryBackoffMs < 0) {
            throw new IllegalStateException("Google retry backoff must be zero or greater");
        }
    }

    private void validateGlossaryConfiguration() {
        if (!googleGlossaryEnabled) {
            return;
        }

        List<String> missingProperties = new ArrayList<>();
        if (configuredSourceLanguage == null || configuredSourceLanguage.isBlank()) {
            missingProperties.add("myapp.google.sourceLanguage");
        }
        if (configuredTargetLanguage == null || configuredTargetLanguage.isBlank()) {
            missingProperties.add("myapp.google.targetLanguage");
        }
        if (googleProjectId == null || googleProjectId.isBlank()) {
            missingProperties.add("myapp.google.projectId");
        }
        if (googleLocation == null || googleLocation.isBlank()) {
            missingProperties.add("myapp.google.location");
        }
        if (googleGlossaryId == null || googleGlossaryId.isBlank()) {
            missingProperties.add("myapp.google.glossaryId");
        }

        if (!missingProperties.isEmpty()) {
            throw new IllegalStateException(
                    "Glossary is enabled but required Google glossary configuration is missing: "
                            + String.join(", ", missingProperties)
            );
        }
    }

    private Path resolveDataDir(String customPath) throws Exception {
        Path dir = (customPath == null || customPath.isBlank())
                ? defaultDataDir
                : Path.of(customPath).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
    }

    private Path resolveJsonFile(String customPath, String fileName) throws Exception {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }

        String normalized = fileName.endsWith(".json") ? fileName : fileName + ".json";
        Path dir = resolveDataDir(customPath);
        return dir.resolve(normalized).normalize();
    }

    private String extractLanguageFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required to detect source language");
        }

        String normalized = Path.of(fileName).getFileName().toString().replaceFirst("(?i)\\.json$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Cannot detect source language from file name: " + fileName);
        }
        if (!normalized.matches("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")) {
            throw new IllegalArgumentException("Invalid language code in file name: " + fileName);
        }
        return normalized;
    }

    private String resolveSourceLanguage(String fileName, List<TranslationRow> rows) {
        String fallbackLanguage = extractLanguageFromFileName(normalizeReferenceLanguageFile(referenceLanguageFile));

        if (fileName == null || fileName.isBlank()) {
            return inferSourceLanguageFromRows(rows, fallbackLanguage);
        }

        String normalized = Path.of(fileName).getFileName().toString().replaceFirst("(?i)\\.json$", "");
        if (!normalized.matches("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")) {
            return inferSourceLanguageFromRows(rows, fallbackLanguage);
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

    private String inferSourceLanguageFromRows(List<TranslationRow> rows, String fallbackLanguage) {
        String fallback = fallbackLanguage.toLowerCase(Locale.ROOT);
        if (rows == null || rows.isEmpty()) {
            return fallback;
        }

        int latinLikeRows = 0;
        int analyzedRows = 0;
        for (TranslationRow row : rows) {
            String text = row == null ? null : row.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            boolean hasLetter = false;
            boolean hasNonLatinLetter = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!Character.isLetter(ch)) {
                    continue;
                }
                hasLetter = true;
                Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
                if (block != Character.UnicodeBlock.BASIC_LATIN
                        && block != Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                        && block != Character.UnicodeBlock.LATIN_EXTENDED_A
                        && block != Character.UnicodeBlock.LATIN_EXTENDED_B) {
                    hasNonLatinLetter = true;
                    break;
                }
            }
            if (!hasLetter) {
                continue;
            }

            analyzedRows++;
            if (!hasNonLatinLetter) {
                latinLikeRows++;
            }
        }

        if (analyzedRows == 0) {
            return fallback;
        }
        double latinRatio = (double) latinLikeRows / analyzedRows;
        if (latinRatio >= 0.8d) {
            return "en";
        }
        return fallback;
    }

    private record GoogleTranslateTextRequest(
            List<String> contents,
            String sourceLanguageCode,
            String targetLanguageCode,
            String mimeType,
            String model,
            GoogleGlossaryConfig glossaryConfig
    ) {
    }

    private record GoogleGlossaryConfig(String glossary) {
    }

    private record GoogleTranslateTextResponse(
            List<GoogleTextTranslation> translations,
            List<GoogleTextTranslation> glossaryTranslations
    ) {
    }

    private record GoogleTextTranslation(String translatedText) {
    }

    private record GoogleSupportedLanguagesResponse(List<GoogleSupportedLanguage> languages) {
    }

    private record GoogleSupportedLanguage(
            String languageCode,
            String displayName
    ) {
        @JsonCreator
        private GoogleSupportedLanguage(
                @JsonProperty("language") @JsonAlias("languageCode") String languageCode,
                @JsonProperty("name") @JsonAlias("displayName") String displayName
        ) {
            this.languageCode = languageCode;
            this.displayName = displayName;
        }
    }

    private record TranslationItem(int index, String prefix, String key, String fullKey, String sourceText) {
    }

    private record PreparedTranslationItem(
            TranslationItem item,
            String normalizedText,
            String protectedText,
            Map<String, String> placeholders,
            PreprocessingMetadata metadata
    ) {
    }

    private record PreprocessingMetadata(
            int wordCount,
            boolean shortText,
            boolean containsPlaceholders,
            List<String> placeholders,
            boolean risky,
            String riskReason
    ) {
    }

    private record PreprocessingReportItem(
            String fullKey,
            String prefix,
            String key,
            int wordCount,
            boolean shortText,
            boolean containsPlaceholders,
            List<String> placeholders,
            boolean risky,
            String riskReason
    ) {
    }

    private record ValidationIssue(
            String fullKey,
            String prefix,
            String type,
            String severity,
            String message
    ) {
    }

    private static final class ValidationReportRow {
        private final String fullKey;
        private final String prefix;
        private final String sourceText;
        private final String protectedSourceText;
        private final String translatedProtectedText;
        private final String finalTranslatedText;
        private final boolean riskyFlag;
        private final String routeUsed;
        private String validationStatus;
        private final List<String> issuesOrWarnings;

        private ValidationReportRow(String fullKey,
                                    String prefix,
                                    String sourceText,
                                    String protectedSourceText,
                                    String translatedProtectedText,
                                    String finalTranslatedText,
                                    boolean riskyFlag,
                                    String routeUsed,
                                    String validationStatus,
                                    List<String> issuesOrWarnings) {
            this.fullKey = fullKey;
            this.prefix = prefix;
            this.sourceText = sourceText;
            this.protectedSourceText = protectedSourceText;
            this.translatedProtectedText = translatedProtectedText;
            this.finalTranslatedText = finalTranslatedText;
            this.riskyFlag = riskyFlag;
            this.routeUsed = routeUsed;
            this.validationStatus = validationStatus;
            this.issuesOrWarnings = issuesOrWarnings;
        }

        public String fullKey() { return fullKey; }
        public String prefix() { return prefix; }
        public String sourceText() { return sourceText; }
        public String protectedSourceText() { return protectedSourceText; }
        public String translatedProtectedText() { return translatedProtectedText; }
        public String finalTranslatedText() { return finalTranslatedText; }
        public boolean riskyFlag() { return riskyFlag; }
        public String routeUsed() { return routeUsed; }
        public String validationStatus() { return validationStatus; }
        public List<String> issuesOrWarnings() { return issuesOrWarnings; }
    }

    private record ValidationSummary(
            int totalStringsProcessed,
            int validCount,
            int invalidCount,
            int warningCount,
            Map<String, Long> issueCountsByType,
            Map<String, Long> countsByPrefix,
            int duplicateInconsistencyFindings
    ) {
    }

    private record ValidationReport(
            List<ValidationReportRow> rows,
            List<PreprocessingReportItem> preprocessing,
            List<ValidationIssue> issues,
            ValidationSummary summary
    ) {
    }

    private record TranslationPipelineResult(List<String> translatedTexts, ValidationReport validationReport) {
    }

    private record TranslatedItemResult(
            int index,
            String fullKey,
            String translatedText,
            String route,
            boolean risky,
            String riskReason
    ) {
    }
}
