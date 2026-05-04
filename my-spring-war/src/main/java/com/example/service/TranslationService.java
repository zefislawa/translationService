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
import com.fasterxml.jackson.annotation.JsonAutoDetect;
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
import java.util.concurrent.ConcurrentHashMap;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Objects;
import java.util.concurrent.CancellationException;
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
    private final String googleGlossaryFile;
    private final String googleGlossaryBucket;
    private final String googleGlossaryObjectPrefix;
    private final String googleGlossaryResourceTemplate;
    private final String googleAdaptiveDatasetFile;
    private final String googleAdaptiveDatasetBucket;
    private final String googleAdaptiveDatasetObjectPrefix;
    private final String googleAdaptiveDatasetResourceTemplate;
    private final int googleBatchSize;
    private final int googleRetryAttempts;
    private final long googleRetryBackoffMs;
    private final String supportedLanguagesDisplayLocale;
    private final String referenceLanguageFile;
    private final String riskyTermsFile;
    private final boolean placeholderProtectionEnabled;
    private final boolean validationEnabled;
    private final Map<String, String> activeGlossariesByLanguagePair = new ConcurrentHashMap<>();
    private final Map<String, String> activeAdaptiveDatasetsByLanguagePair = new ConcurrentHashMap<>();
    private final Set<String> cancelledTranslationRequests = ConcurrentHashMap.newKeySet();
    private GoogleCredentials googleCredentials;
    private AccessToken cachedAccessToken;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{[^{}]+}}|\\{[^{}]+}|%\\d*\\$?[sdfoxegc]|<[^>]+>"
    );
    private static final String PLACEHOLDER_TOKEN_PREFIX = "__PH_";
    private static final String ADAPTIVE_DATASETS_REGISTRY_FILE = "adaptive-datasets.json";
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
            @Value("${myapp.google.glossaryFile:}") String googleGlossaryFile,
            @Value("${myapp.google.glossaryBucket:}") String googleGlossaryBucket,
            @Value("${myapp.google.glossaryObjectPrefix:}") String googleGlossaryObjectPrefix,
            @Value("${myapp.google.glossaryResourceTemplate:}") String googleGlossaryResourceTemplate,
            @Value("${myapp.google.adaptiveDatasetFile:}") String googleAdaptiveDatasetFile,
            @Value("${myapp.google.adaptiveDatasetBucket:}") String googleAdaptiveDatasetBucket,
            @Value("${myapp.google.adaptiveDatasetObjectPrefix:}") String googleAdaptiveDatasetObjectPrefix,
            @Value("${myapp.google.adaptiveDatasetResourceTemplate:}") String googleAdaptiveDatasetResourceTemplate,
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
        this.googleGlossaryFile = googleGlossaryFile;
        this.googleGlossaryBucket = googleGlossaryBucket;
        this.googleGlossaryObjectPrefix = googleGlossaryObjectPrefix;
        this.googleGlossaryResourceTemplate = googleGlossaryResourceTemplate;
        this.googleAdaptiveDatasetFile = googleAdaptiveDatasetFile;
        this.googleAdaptiveDatasetBucket = googleAdaptiveDatasetBucket;
        this.googleAdaptiveDatasetObjectPrefix = googleAdaptiveDatasetObjectPrefix;
        this.googleAdaptiveDatasetResourceTemplate = googleAdaptiveDatasetResourceTemplate;
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
        loadPersistedAdaptiveDatasets();
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

    public List<String> listGlossaryFiles() throws Exception {
        Path glossaryDirectory = resolveGlossaryDirectory();
        try (Stream<Path> stream = Files.list(glossaryDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    public List<String> listAdaptiveDatasetFiles() throws Exception {
        Path adaptiveDatasetDirectory = resolveAdaptiveDatasetDirectory();
        try (Stream<Path> stream = Files.list(adaptiveDatasetDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.toLowerCase().endsWith(".tsv"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    public String configuredAdaptiveDatasetFileName() throws Exception {
        if (googleAdaptiveDatasetFile == null || googleAdaptiveDatasetFile.isBlank()) {
            return "";
        }
        Path configuredPath = Path.of(googleAdaptiveDatasetFile.trim());
        if (!configuredPath.isAbsolute()) {
            configuredPath = resolveDataDir(null).resolve(configuredPath).normalize();
        }
        return configuredPath.getFileName() == null ? "" : configuredPath.getFileName().toString();
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
        return translateAndStore(customPath, fileName, targetLanguage, rows, null);
    }

    public TranslationExportResult translateAndStore(
            String customPath,
            String fileName,
            String targetLanguage,
            List<TranslationRow> rows,
            String translationRequestId
    ) throws Exception {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("No rows provided for translation");
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }
        throwIfTranslationCancelled(translationRequestId);

        String sourceLanguage = resolveSourceLanguage(fileName, rows);
        TranslationPipelineResult pipelineResult = runTranslationPipeline(
                customPath,
                rows,
                sourceLanguage,
                targetLanguage,
                translationRequestId
        );
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

    public void cancelTranslationRequest(String translationRequestId) {
        if (translationRequestId == null || translationRequestId.isBlank()) {
            return;
        }
        cancelledTranslationRequests.add(translationRequestId.trim());
    }

    public void clearTranslationCancellation(String translationRequestId) {
        if (translationRequestId == null || translationRequestId.isBlank()) {
            return;
        }
        cancelledTranslationRequests.remove(translationRequestId.trim());
    }

    private void throwIfTranslationCancelled(String translationRequestId) {
        if (translationRequestId == null || translationRequestId.isBlank()) {
            return;
        }
        if (cancelledTranslationRequests.contains(translationRequestId.trim())) {
            throw new CancellationException("Translation request was cancelled by user");
        }
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
        TranslationPipelineResult pipelineResult = runTranslationPipeline(customPath, rows, sourceLanguage, targetLanguage, null);
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

    private TranslationPipelineResult runTranslationPipeline(
            String customPath,
            List<TranslationRow> rows,
            String sourceLanguage,
            String targetLanguage,
            String translationRequestId
    ) {
        throwIfTranslationCancelled(translationRequestId);
        List<TranslationItem> flattenedItems = flattenRows(rows);
        Set<String> configuredRiskyTerms = loadConfiguredRiskyTerms(customPath);
        List<PreparedTranslationItem> preprocessedItems = preprocessItems(flattenedItems, configuredRiskyTerms);
        List<PreparedTranslationItem> protectedItems = placeholderProtectionEnabled
                ? protectPlaceholders(preprocessedItems)
                : preprocessedItems;
        throwIfTranslationCancelled(translationRequestId);
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
                : translateByRouteV1(sourceLanguage, targetLanguage, protectedItems, translationRequestId);
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

    private GoogleTranslationBatchResult callGoogleTranslationRoute(
            String sourceLanguage,
            String targetLanguage,
            List<PreparedTranslationItem> items,
            String adaptiveDataset,
            String translationRequestId
    ) {
        requireGoogleProjectId();
        boolean useAdaptiveDataset = adaptiveDataset != null && !adaptiveDataset.isBlank();
        String endpoint = "https://translation.googleapis.com/v3/projects/" + googleProjectId
                + "/locations/" + googleLocation + (useAdaptiveDataset ? ":adaptiveMtTranslate" : ":translateText");
        String url = UriComponentsBuilder.fromHttpUrl(endpoint).toUriString();

        List<String> allTranslations = new ArrayList<>(items.size());
        for (int start = 0; start < items.size(); start += googleBatchSize) {
            throwIfTranslationCancelled(translationRequestId);
            int end = Math.min(start + googleBatchSize, items.size());
            List<PreparedTranslationItem> batchItems = items.subList(start, end);
            List<Integer> translatableIndexes = new ArrayList<>(batchItems.size());
            List<String> contents = new ArrayList<>(batchItems.size());
            List<String> batchTranslations = new ArrayList<>(Collections.nCopies(batchItems.size(), null));
            for (int i = 0; i < batchItems.size(); i++) {
                String protectedText = batchItems.get(i).protectedText();
                if (protectedText == null || protectedText.isBlank()) {
                    batchTranslations.set(i, Objects.requireNonNullElse(protectedText, ""));
                    continue;
                }
                translatableIndexes.add(i);
                contents.add(protectedText);
            }

            if (contents.isEmpty()) {
                allTranslations.addAll(batchTranslations);
                continue;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resolveAccessTokenValue());

            log.info("Sending translation batch start={}, endExclusive={}, batchSize={}, source={}, target={}, model={}, glossaryEnabled={}",
                    start, end, batchItems.size(), sourceLanguage, targetLanguage, googleTranslationModel, googleGlossaryEnabled);

            List<String> selectedTranslations = translateContentsWithSplitting(
                    url,
                    headers,
                    sourceLanguage,
                    targetLanguage,
                    adaptiveDataset,
                    contents,
                    start,
                    end,
                    translationRequestId
            );
            if (selectedTranslations.size() != contents.size()) {
                throw new IllegalStateException("Google Translate returned an unexpected number of translated strings");
            }
            for (int i = 0; i < translatableIndexes.size(); i++) {
                batchTranslations.set(translatableIndexes.get(i), selectedTranslations.get(i));
            }
            allTranslations.addAll(batchTranslations);
        }
        String routeUsed = useAdaptiveDataset
                ? "google-translation-advanced/adaptiveMtTranslate"
                : "google-translation-advanced/translateText/translation-llm";
        return new GoogleTranslationBatchResult(allTranslations, routeUsed);
    }

    private List<String> translateContentsWithSplitting(
            String url,
            HttpHeaders headers,
            String sourceLanguage,
            String targetLanguage,
            String adaptiveDataset,
            List<String> contents,
            int start,
            int end,
            String translationRequestId
    ) {
        throwIfTranslationCancelled(translationRequestId);
        Object body = (adaptiveDataset != null && !adaptiveDataset.isBlank())
                ? new GoogleAdaptiveMtTranslateRequest(contents, adaptiveDataset)
                : new GoogleTranslateTextRequest(
                        contents,
                        sourceLanguage,
                        targetLanguage,
                        "text/plain",
                        googleTranslationModel,
                        resolveGlossaryConfig(sourceLanguage, targetLanguage)
                );
        try {
            ResponseEntity<GoogleTranslateTextResponse> response = executeTranslateBatchWithRetry(
                    url,
                    body,
                    headers,
                    start,
                    end,
                    translationRequestId
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
            return glossaryTranslations.size() == translations.size() ? glossaryTranslations : translations;
        } catch (HttpStatusCodeException ex) {
            int statusCode = ex.getStatusCode().value();
            if (statusCode >= 500 && statusCode < 600 && contents.size() > 1) {
                int middle = contents.size() / 2;
                int splitPoint = start + middle;
                log.warn("Translation batch range=[{}, {}) failed with status={}; retrying as two sub-batches: [{}, {}) and [{}, {})",
                        start, end, statusCode, start, splitPoint, splitPoint, end);
                List<String> firstHalf = translateContentsWithSplitting(
                        url,
                        headers,
                        sourceLanguage,
                        targetLanguage,
                        adaptiveDataset,
                        contents.subList(0, middle),
                        start,
                        splitPoint,
                        translationRequestId
                );
                List<String> secondHalf = translateContentsWithSplitting(
                        url,
                        headers,
                        sourceLanguage,
                        targetLanguage,
                        adaptiveDataset,
                        contents.subList(middle, contents.size()),
                        splitPoint,
                        end,
                        translationRequestId
                );
                List<String> merged = new ArrayList<>(contents.size());
                merged.addAll(firstHalf);
                merged.addAll(secondHalf);
                return merged;
            }
            throw ex;
        }
    }

    private List<TranslatedItemResult> translateByRouteV1(
            String sourceLanguage,
            String targetLanguage,
            List<PreparedTranslationItem> items,
            String translationRequestId
    ) {
        throwIfTranslationCancelled(translationRequestId);
        String adaptiveDataset = resolveAdaptiveDataset(sourceLanguage, targetLanguage);
        boolean adaptiveAvailable = adaptiveDataset != null && !adaptiveDataset.isBlank();
        List<PreparedTranslationItem> adaptiveCandidates = new ArrayList<>();
        List<PreparedTranslationItem> llmCandidates = new ArrayList<>();
        for (PreparedTranslationItem item : items) {
            if (adaptiveAvailable && item.metadata().risky() && item.metadata().shortText()) {
                adaptiveCandidates.add(item);
            } else {
                llmCandidates.add(item);
            }
        }

        Map<Integer, TranslatedItemResult> translatedByIndex = new LinkedHashMap<>();
        if (!adaptiveCandidates.isEmpty()) {
            GoogleTranslationBatchResult adaptiveTranslations = callGoogleTranslationRoute(
                    sourceLanguage,
                    targetLanguage,
                    adaptiveCandidates,
                    adaptiveDataset,
                    translationRequestId
            );
            for (int i = 0; i < adaptiveCandidates.size(); i++) {
                PreparedTranslationItem item = adaptiveCandidates.get(i);
                translatedByIndex.put(item.item().index(), new TranslatedItemResult(
                        item.item().index(),
                        item.item().fullKey(),
                        adaptiveTranslations.translatedTexts().get(i),
                        adaptiveTranslations.routeUsed(),
                        item.metadata().risky(),
                        item.metadata().riskReason()
                ));
            }
        }

        if (!llmCandidates.isEmpty()) {
            GoogleTranslationBatchResult llmTranslations = callGoogleTranslationRoute(
                    sourceLanguage,
                    targetLanguage,
                    llmCandidates,
                    null,
                    translationRequestId
            );
            for (int i = 0; i < llmCandidates.size(); i++) {
                PreparedTranslationItem item = llmCandidates.get(i);
                translatedByIndex.put(item.item().index(), new TranslatedItemResult(
                        item.item().index(),
                        item.item().fullKey(),
                        llmTranslations.translatedTexts().get(i),
                        llmTranslations.routeUsed(),
                        item.metadata().risky(),
                        item.metadata().riskReason()
                ));
            }
        }

        if (translatedByIndex.size() != items.size()) {
            throw new IllegalStateException("Translation route v1 returned an unexpected number of translated strings");
        }
        return translatedByIndex.values().stream()
                .sorted(Comparator.comparingInt(TranslatedItemResult::index))
                .toList();
    }

    private ResponseEntity<GoogleTranslateTextResponse> executeTranslateBatchWithRetry(
            String url,
            Object body,
            HttpHeaders headers,
            int start,
            int end,
            String translationRequestId
    ) {
        int attempt = 1;
        while (true) {
            throwIfTranslationCancelled(translationRequestId);
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
                throwIfTranslationCancelled(translationRequestId);
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

    public String synchronizeGlossary(String glossaryFilePathOrName, String sourceLanguage, String targetLanguage) throws Exception {
        requireGlossaryAutomationEnabled();
        String normalizedSourceLanguage = normalizeLanguageCodeOrThrow(sourceLanguage, "sourceLanguage");
        String normalizedTargetLanguage = normalizeLanguageCodeOrThrow(targetLanguage, "targetLanguage");
        if (normalizedSourceLanguage.equals(normalizedTargetLanguage)) {
            throw new IllegalArgumentException("sourceLanguage and targetLanguage must be different");
        }

        Path glossaryFile = resolveGlossaryCsvFile(glossaryFilePathOrName);
        validateGlossaryCsv(glossaryFile);

        String glossaryResourceName = buildGlossaryResourceName(normalizedSourceLanguage, normalizedTargetLanguage);
        String gcsUri = uploadGlossaryCsvToGcs(glossaryFile, normalizedSourceLanguage, normalizedTargetLanguage);
        recreateGlossaryResource(glossaryResourceName, normalizedSourceLanguage, normalizedTargetLanguage, gcsUri);
        String pairKey = languagePairKey(normalizedSourceLanguage, normalizedTargetLanguage);
        activeGlossariesByLanguagePair.put(pairKey, glossaryResourceName);
        log.info("Activated glossary {} for language pair {}", glossaryResourceName, pairKey);
        return glossaryResourceName;
    }

    public AdaptiveDatasetSyncResult synchronizeAdaptiveDataset(String tsvFilePathOrName, String sourceLanguage, String targetLanguage) throws Exception {
        requireAdaptiveDatasetAutomationConfigured();
        String normalizedSourceLanguage = normalizeLanguageCodeOrThrow(sourceLanguage, "sourceLanguage");
        String normalizedTargetLanguage = normalizeLanguageCodeOrThrow(targetLanguage, "targetLanguage");
        if (normalizedSourceLanguage.equals(normalizedTargetLanguage)) {
            throw new IllegalArgumentException("sourceLanguage and targetLanguage must be different");
        }

        Path adaptiveDatasetFile = resolveAdaptiveDatasetTsvFile(tsvFilePathOrName);
        validateAdaptiveDatasetTsv(adaptiveDatasetFile);

        String datasetResourceName = buildAdaptiveDatasetResourceName(normalizedSourceLanguage, normalizedTargetLanguage);
        String gcsUri = uploadAdaptiveDatasetTsvToGcs(adaptiveDatasetFile, normalizedSourceLanguage, normalizedTargetLanguage);
        createAdaptiveDatasetIfMissing(datasetResourceName, normalizedSourceLanguage, normalizedTargetLanguage);
        String importStatus = importAdaptiveDatasetTsv(datasetResourceName, gcsUri, adaptiveDatasetFile.getFileName().toString());

        String pairKey = languagePairKey(normalizedSourceLanguage, normalizedTargetLanguage);
        activeAdaptiveDatasetsByLanguagePair.put(pairKey, datasetResourceName);
        persistAdaptiveDatasetRegistry();
        log.info("Activated adaptive dataset {} for language pair {}", datasetResourceName, pairKey);
        return new AdaptiveDatasetSyncResult(datasetResourceName, importStatus, gcsUri);
    }

    private String resolveAdaptiveDataset(String sourceLanguage, String targetLanguage) {
        return activeAdaptiveDatasetsByLanguagePair.get(languagePairKey(sourceLanguage, targetLanguage));
    }

    private void loadPersistedAdaptiveDatasets() {
        Path registryFile = defaultDataDir.resolve(ADAPTIVE_DATASETS_REGISTRY_FILE);
        if (!Files.exists(registryFile)) {
            return;
        }
        try {
            Object raw = mapper.readValue(registryFile.toFile(), Object.class);
            if (!(raw instanceof Map<?, ?> persisted)) {
                log.warn("Ignoring malformed adaptive dataset registry at {}", registryFile);
                return;
            }
            for (Map.Entry<?, ?> entry : persisted.entrySet()) {
                String pair = entry.getKey() == null ? "" : entry.getKey().toString().trim();
                String dataset = entry.getValue() == null ? "" : entry.getValue().toString().trim();
                if (!pair.isBlank() && !dataset.isBlank()) {
                    activeAdaptiveDatasetsByLanguagePair.put(pair, dataset);
                }
            }
            if (!activeAdaptiveDatasetsByLanguagePair.isEmpty()) {
                log.info("Loaded {} persisted adaptive dataset mappings", activeAdaptiveDatasetsByLanguagePair.size());
            }
        } catch (Exception ex) {
            log.warn("Failed to load adaptive dataset registry {}: {}", registryFile, ex.getMessage());
        }
    }

    private void persistAdaptiveDatasetRegistry() {
        Path registryFile = defaultDataDir.resolve(ADAPTIVE_DATASETS_REGISTRY_FILE);
        try {
            Map<String, String> snapshot = new TreeMap<>(activeAdaptiveDatasetsByLanguagePair);
            mapper.writerWithDefaultPrettyPrinter().writeValue(registryFile.toFile(), snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist adaptive dataset registry: " + registryFile, ex);
        }
    }

    private GoogleGlossaryConfig resolveGlossaryConfig(String sourceLanguage, String targetLanguage) {
        if (!googleGlossaryEnabled) {
            return null;
        }

        String pairKey = languagePairKey(sourceLanguage, targetLanguage);
        String activeGlossary = activeGlossariesByLanguagePair.get(pairKey);
        if (activeGlossary != null && !activeGlossary.isBlank()) {
            return new GoogleGlossaryConfig(activeGlossary);
        }

        if (googleGlossaryId == null || googleGlossaryId.isBlank()) {
            return null;
        }

        String glossaryPath = normalizeGlossaryResourcePath(googleGlossaryId);
        return new GoogleGlossaryConfig(glossaryPath);
    }

    private void requireGlossaryAutomationEnabled() {
        if (!googleGlossaryEnabled) {
            throw new IllegalStateException("Glossary automation requires myapp.google.glossaryEnabled=true");
        }
        requireGoogleProjectId();
        if (googleLocation == null || googleLocation.isBlank()) {
            throw new IllegalStateException("Google location is missing. Configure myapp.google.location");
        }
        if (googleGlossaryBucket == null || googleGlossaryBucket.isBlank()) {
            throw new IllegalStateException("Glossary automation requires myapp.google.glossaryBucket");
        }
    }

    private void requireAdaptiveDatasetAutomationConfigured() {
        requireGoogleProjectId();
        if (googleLocation == null || googleLocation.isBlank()) {
            throw new IllegalStateException("Google location is missing. Configure myapp.google.location");
        }
        if (googleAdaptiveDatasetBucket == null || googleAdaptiveDatasetBucket.isBlank()) {
            throw new IllegalStateException("Adaptive dataset automation requires myapp.google.adaptiveDatasetBucket");
        }
    }

    private String normalizeLanguageCodeOrThrow(String languageCode, String fieldName) {
        if (languageCode == null || languageCode.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String normalized = languageCode.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*$")) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + languageCode);
        }
        return normalized;
    }

    private Path resolveGlossaryCsvFile(String glossaryFilePathOrName) throws Exception {
        String configuredOrRequested = glossaryFilePathOrName;
        if (configuredOrRequested == null || configuredOrRequested.isBlank()) {
            configuredOrRequested = googleGlossaryFile;
        }
        if (configuredOrRequested == null || configuredOrRequested.isBlank()) {
            throw new IllegalArgumentException("glossaryFilePath is required or configure myapp.google.glossaryFile");
        }

        Path candidatePath = Path.of(configuredOrRequested.trim());
        if (!candidatePath.isAbsolute()) {
            candidatePath = resolveDataDir(null).resolve(candidatePath).normalize();
        }
        if (!Files.isRegularFile(candidatePath)) {
            throw new IllegalArgumentException("Glossary CSV file not found: " + candidatePath);
        }
        return candidatePath;
    }

    private Path resolveAdaptiveDatasetTsvFile(String adaptiveDatasetFilePathOrName) throws Exception {
        String configuredOrRequested = adaptiveDatasetFilePathOrName;
        if (configuredOrRequested == null || configuredOrRequested.isBlank()) {
            configuredOrRequested = googleAdaptiveDatasetFile;
        }
        if (configuredOrRequested == null || configuredOrRequested.isBlank()) {
            throw new IllegalArgumentException("tsvFilePath is required or configure myapp.google.adaptiveDatasetFile");
        }

        Path candidatePath = Path.of(configuredOrRequested.trim());
        if (!candidatePath.isAbsolute()) {
            Path configuredDirectory = resolveAdaptiveDatasetConfiguredDirectory();
            if (configuredDirectory != null) {
                candidatePath = configuredDirectory.resolve(candidatePath).normalize();
            } else {
                candidatePath = resolveDataDir(null).resolve(candidatePath).normalize();
            }
        }
        if (!Files.isRegularFile(candidatePath)) {
            throw new IllegalArgumentException("Adaptive dataset TSV file not found: " + candidatePath);
        }
        return candidatePath;
    }

    private Path resolveAdaptiveDatasetConfiguredDirectory() throws Exception {
        if (googleAdaptiveDatasetFile == null || googleAdaptiveDatasetFile.isBlank()) {
            return null;
        }

        String configuredValue = googleAdaptiveDatasetFile.trim();
        Path configuredPath = Path.of(configuredValue);
        if (!configuredPath.isAbsolute()) {
            configuredPath = resolveDataDir(null).resolve(configuredPath).normalize();
        }

        if (Files.isDirectory(configuredPath) || endsWithPathSeparator(configuredValue)) {
            return configuredPath;
        }
        return configuredPath.getParent();
    }

    private Path resolveAdaptiveDatasetDirectory() throws Exception {
        if (googleAdaptiveDatasetFile == null || googleAdaptiveDatasetFile.isBlank()) {
            return resolveDataDir(null);
        }

        String configuredValue = googleAdaptiveDatasetFile.trim();
        Path configuredPath = Path.of(configuredValue);
        if (!configuredPath.isAbsolute()) {
            configuredPath = resolveDataDir(null).resolve(configuredPath).normalize();
        }

        Path directory;
        if (Files.isDirectory(configuredPath) || endsWithPathSeparator(configuredValue)) {
            directory = configuredPath;
        } else {
            Path parent = configuredPath.getParent();
            directory = parent == null ? resolveDataDir(null) : parent;
        }

        Files.createDirectories(directory);
        return directory;
    }

    private Path resolveGlossaryDirectory() throws Exception {
        if (googleGlossaryFile == null || googleGlossaryFile.isBlank()) {
            return resolveDataDir(null);
        }

        String configuredValue = googleGlossaryFile.trim();
        Path configuredPath = Path.of(configuredValue);
        if (!configuredPath.isAbsolute()) {
            configuredPath = resolveDataDir(null).resolve(configuredPath).normalize();
        }
        Path directory;
        if (Files.isDirectory(configuredPath) || endsWithPathSeparator(configuredValue)) {
            directory = configuredPath;
        } else {
            Path parent = configuredPath.getParent();
            directory = parent == null ? resolveDataDir(null) : parent;
        }
        Files.createDirectories(directory);
        return directory;
    }

    private boolean endsWithPathSeparator(String value) {
        return value.endsWith("/") || value.endsWith("\\");
    }

    private void validateGlossaryCsv(Path glossaryFile) throws Exception {
        List<String> lines = Files.readAllLines(glossaryFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Glossary CSV is empty: " + glossaryFile);
        }

        int validRows = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",", -1);
            if (columns.length != 2) {
                throw new IllegalArgumentException("Glossary CSV row " + (i + 1) + " must contain exactly 2 columns");
            }
            if (columns[0].trim().isEmpty() || columns[1].trim().isEmpty()) {
                throw new IllegalArgumentException("Glossary CSV row " + (i + 1) + " contains empty columns");
            }
            validRows++;
        }
        if (validRows == 0) {
            throw new IllegalArgumentException("Glossary CSV contains no valid rows: " + glossaryFile);
        }
    }

    private void validateAdaptiveDatasetTsv(Path tsvFile) throws Exception {
        List<String> lines = Files.readAllLines(tsvFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Adaptive dataset TSV is empty: " + tsvFile);
        }

        int validRows = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] columns = line.split("\\t", -1);
            if (columns.length != 2) {
                throw new IllegalArgumentException("Adaptive dataset TSV row " + (i + 1) + " must contain exactly 2 tab-separated columns");
            }
            if (columns[0].trim().isEmpty() || columns[1].trim().isEmpty()) {
                throw new IllegalArgumentException("Adaptive dataset TSV row " + (i + 1) + " contains empty columns");
            }
            validRows++;
        }
        if (validRows == 0) {
            throw new IllegalArgumentException("Adaptive dataset TSV contains no valid rows: " + tsvFile);
        }
    }

    private String uploadGlossaryCsvToGcs(Path glossaryFile, String sourceLanguage, String targetLanguage) throws Exception {
        String objectPathPrefix = normalizeGcsObjectPrefix();
        String objectName = objectPathPrefix
                + sourceLanguage + "-" + targetLanguage + "/"
                + System.currentTimeMillis() + "-" + glossaryFile.getFileName();
        String endpoint = UriComponentsBuilder
                .fromHttpUrl("https://storage.googleapis.com/upload/storage/v1/b/" + googleGlossaryBucket + "/o")
                .queryParam("uploadType", "media")
                .queryParam("name", objectName)
                .build()
                .encode()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/csv; charset=UTF-8"));
        headers.setBearerAuth(resolveAccessTokenValue());
        HttpEntity<byte[]> request = new HttpEntity<>(Files.readAllBytes(glossaryFile), headers);
        restTemplate.postForEntity(endpoint, request, Object.class);
        return "gs://" + googleGlossaryBucket + "/" + objectName;
    }

    private String uploadAdaptiveDatasetTsvToGcs(Path tsvFile, String sourceLanguage, String targetLanguage) throws Exception {
        String objectPathPrefix = normalizeAdaptiveDatasetGcsObjectPrefix();
        String objectName = objectPathPrefix
                + sourceLanguage + "-" + targetLanguage + "/"
                + System.currentTimeMillis() + "-" + tsvFile.getFileName();
        String endpoint = UriComponentsBuilder
                .fromHttpUrl("https://storage.googleapis.com/upload/storage/v1/b/" + googleAdaptiveDatasetBucket + "/o")
                .queryParam("uploadType", "media")
                .queryParam("name", objectName)
                .build()
                .encode()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/tab-separated-values; charset=UTF-8"));
        headers.setBearerAuth(resolveAccessTokenValue());
        HttpEntity<byte[]> request = new HttpEntity<>(Files.readAllBytes(tsvFile), headers);
        restTemplate.postForEntity(endpoint, request, Object.class);
        return "gs://" + googleAdaptiveDatasetBucket + "/" + objectName;
    }

    private String normalizeGcsObjectPrefix() {
        if (googleGlossaryObjectPrefix == null || googleGlossaryObjectPrefix.isBlank()) {
            return "";
        }
        String normalized = googleGlossaryObjectPrefix.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    private String normalizeAdaptiveDatasetGcsObjectPrefix() {
        if (googleAdaptiveDatasetObjectPrefix == null || googleAdaptiveDatasetObjectPrefix.isBlank()) {
            return "";
        }
        String normalized = googleAdaptiveDatasetObjectPrefix.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    private void createAdaptiveDatasetIfMissing(String datasetResourceName, String sourceLanguage, String targetLanguage) {
        if (adaptiveDatasetExists(datasetResourceName)) {
            return;
        }

        String parent = "projects/" + googleProjectId + "/locations/" + googleLocation;
        String createEndpoint = "https://translation.googleapis.com/v3/" + parent + "/adaptiveMtDatasets";
        String createUrl = UriComponentsBuilder.fromHttpUrl(createEndpoint)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolveAccessTokenValue());

        GoogleAdaptiveDatasetCreateRequest requestBody = new GoogleAdaptiveDatasetCreateRequest(
                datasetResourceName,
                sourceLanguage,
                targetLanguage
        );

        restTemplate.postForEntity(createUrl, new HttpEntity<>(requestBody, headers), Object.class);
        waitForAdaptiveDatasetAvailability(datasetResourceName);
    }

    private boolean adaptiveDatasetExists(String datasetResourceName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resolveAccessTokenValue());
        String getUrl = "https://translation.googleapis.com/v3/" + datasetResourceName;
        try {
            restTemplate.exchange(
                    getUrl,
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Object.class
            );
            return true;
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                return false;
            }
            throw ex;
        }
    }

    private void waitForAdaptiveDatasetAvailability(String datasetResourceName) {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (adaptiveDatasetExists(datasetResourceName)) {
                return;
            }
            sleepQuietly(1000L);
        }
        throw new IllegalStateException("Timed out waiting for adaptive dataset creation: " + datasetResourceName);
    }

    private String importAdaptiveDatasetTsv(String datasetResourceName, String gcsUri, String fileDisplayName) {
        String importUrl = "https://translation.googleapis.com/v3/" + datasetResourceName + ":importAdaptiveMtFile";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolveAccessTokenValue());

        GoogleAdaptiveDatasetImportRequest requestBody = new GoogleAdaptiveDatasetImportRequest(
                new GoogleAdaptiveDatasetFileInputSource(
                        new GoogleAdaptiveDatasetGcsInputSource(gcsUri),
                        fileDisplayName
                )
        );

        restTemplate.postForEntity(importUrl, new HttpEntity<>(requestBody, headers), Object.class);
        return waitForAdaptiveMtFileImport(datasetResourceName, fileDisplayName);
    }

    private String waitForAdaptiveMtFileImport(String datasetResourceName, String fileDisplayName) {
        String listUrl = "https://translation.googleapis.com/v3/" + datasetResourceName + "/adaptiveMtFiles";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resolveAccessTokenValue());
        String lastObservedStatus = "PENDING";

        for (int attempt = 0; attempt < 30; attempt++) {
            ResponseEntity<GoogleAdaptiveMtFilesListResponse> response = restTemplate.exchange(
                    listUrl,
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    GoogleAdaptiveMtFilesListResponse.class
            );
            GoogleAdaptiveMtFilesListResponse body = response.getBody();
            if (body != null && body.adaptiveMtFiles() != null) {
                for (GoogleAdaptiveMtFile file : body.adaptiveMtFiles()) {
                    if (file.displayName() == null || !file.displayName().equals(fileDisplayName)) {
                        continue;
                    }
                    String status = file.state() == null || file.state().isBlank() ? "IMPORTED" : file.state();
                    lastObservedStatus = status;
                    log.info("Adaptive dataset import status for {} and file {}: {}", datasetResourceName, fileDisplayName, status);
                    if ("FAILED".equalsIgnoreCase(status)) {
                        throw new IllegalStateException("Adaptive dataset import failed for " + datasetResourceName + " and file " + fileDisplayName);
                    }
                    if ("SUCCEEDED".equalsIgnoreCase(status)
                            || "ACTIVE".equalsIgnoreCase(status)
                            || "IMPORTED".equalsIgnoreCase(status)) {
                        return status;
                    }
                }
            }
            sleepQuietly(1000L);
        }
        throw new IllegalStateException("Timed out waiting for adaptive dataset import result: " + datasetResourceName
                + " (last observed status: " + lastObservedStatus + ")");
    }

    private void recreateGlossaryResource(
            String glossaryResourceName,
            String sourceLanguage,
            String targetLanguage,
            String gcsUri
    ) {
        deleteGlossaryIfExists(glossaryResourceName);
        String createEndpoint = "https://translation.googleapis.com/v3/projects/" + googleProjectId
                + "/locations/" + googleLocation + "/glossaries";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolveAccessTokenValue());
        GoogleGlossaryCreateRequest requestBody = new GoogleGlossaryCreateRequest(
                new GoogleGlossaryDefinition(
                        glossaryResourceName,
                        new GoogleGlossaryLanguagePair(sourceLanguage, targetLanguage),
                        new GoogleGlossaryInputConfig(new GoogleGlossaryGcsSource(gcsUri))
                )
        );
        ResponseEntity<GoogleLongRunningOperation> response = restTemplate.postForEntity(
                createEndpoint,
                new HttpEntity<>(requestBody.glossary(), headers),
                GoogleLongRunningOperation.class
        );
        waitForOperationCompletion(extractOperationName(response.getBody(), "create glossary"));
    }

    private void deleteGlossaryIfExists(String glossaryResourceName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resolveAccessTokenValue());
        String deleteUrl = "https://translation.googleapis.com/v3/" + glossaryResourceName;
        try {
            ResponseEntity<GoogleLongRunningOperation> response = restTemplate.exchange(
                    deleteUrl,
                    org.springframework.http.HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    GoogleLongRunningOperation.class
            );
            waitForOperationCompletion(extractOperationName(response.getBody(), "delete glossary"));
            log.info("Deleted previous glossary resource {}", glossaryResourceName);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
    }

    private String extractOperationName(GoogleLongRunningOperation operation, String actionName) {
        if (operation == null || operation.name() == null || operation.name().isBlank()) {
            throw new IllegalStateException("Failed to " + actionName + ": empty long-running operation name");
        }
        return operation.name();
    }

    private void waitForOperationCompletion(String operationName) {
        String operationUrl = "https://translation.googleapis.com/v3/" + operationName;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resolveAccessTokenValue());
        for (int attempt = 0; attempt < 120; attempt++) {
            ResponseEntity<GoogleLongRunningOperation> response = restTemplate.exchange(
                    operationUrl,
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    GoogleLongRunningOperation.class
            );
            GoogleLongRunningOperation operation = response.getBody();
            if (operation != null && Boolean.TRUE.equals(operation.done())) {
                if (operation.error() != null && operation.error().code() != 0) {
                    throw new IllegalStateException(
                            "Glossary operation failed: " + operation.error().code() + " " + operation.error().message()
                    );
                }
                return;
            }
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for glossary operation: " + operationName, ex);
            }
        }
        throw new IllegalStateException("Timed out waiting for glossary operation: " + operationName);
    }

    private String buildGlossaryResourceName(String sourceLanguage, String targetLanguage) {
        String template = (googleGlossaryResourceTemplate == null || googleGlossaryResourceTemplate.isBlank())
                ? "app-glossary-{source}-{target}"
                : googleGlossaryResourceTemplate.trim();
        String glossaryId = template
                .replace("{source}", sourceLanguage)
                .replace("{target}", targetLanguage)
                .replaceAll("[^a-zA-Z0-9_-]", "-")
                .toLowerCase(Locale.ROOT);
        if (glossaryId.isBlank()) {
            throw new IllegalStateException("Resolved glossary id is empty. Check myapp.google.glossaryResourceTemplate");
        }
        return "projects/" + googleProjectId + "/locations/" + googleLocation + "/glossaries/" + glossaryId;
    }

    private String buildAdaptiveDatasetResourceName(String sourceLanguage, String targetLanguage) {
        String template = (googleAdaptiveDatasetResourceTemplate == null || googleAdaptiveDatasetResourceTemplate.isBlank())
                ? "app-adaptive-{source}-{target}"
                : googleAdaptiveDatasetResourceTemplate.trim();
        String datasetId = template
                .replace("{source}", sourceLanguage)
                .replace("{target}", targetLanguage)
                .replaceAll("[^a-zA-Z0-9_-]", "-")
                .toLowerCase(Locale.ROOT);
        if (datasetId.isBlank()) {
            throw new IllegalStateException("Resolved adaptive dataset id is empty. Check myapp.google.adaptiveDatasetResourceTemplate");
        }
        return "projects/" + googleProjectId + "/locations/" + googleLocation + "/adaptiveMtDatasets/" + datasetId;
    }

    private String languagePairKey(String sourceLanguage, String targetLanguage) {
        String source = sourceLanguage == null ? "" : sourceLanguage.trim().toLowerCase(Locale.ROOT);
        String target = targetLanguage == null ? "" : targetLanguage.trim().toLowerCase(Locale.ROOT);
        return source + "->" + target;
    }

    private String normalizeGlossaryResourcePath(String rawGlossaryIdOrPath) {
        String normalized = rawGlossaryIdOrPath == null ? "" : rawGlossaryIdOrPath.trim();
        if (normalized.startsWith("projects/")) {
            return normalized;
        }
        return "projects/" + googleProjectId + "/locations/" + googleLocation + "/glossaries/" + normalized;
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
            String normalizedConfiguredPath = normalizeCredentialsPathValue(googleCredentialsPath);
            Path credentialsPath = Path.of(normalizedConfiguredPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(credentialsPath)) {
                throw new IllegalStateException("Google credentials file was not found at: " + credentialsPath);
            }
            try (InputStream credentialsStream = Files.newInputStream(credentialsPath)) {
                return GoogleCredentials.fromStream(credentialsStream);
            }
        }
        return GoogleCredentials.getApplicationDefault();
    }

    private String normalizeCredentialsPathValue(String configuredValue) {
        String normalized = configuredValue == null ? "" : configuredValue.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }

        normalized = stripOptionalAssignmentPrefix(normalized, "GOOGLE_APPLICATION_CREDENTIALS");
        normalized = stripOptionalAssignmentPrefix(normalized, "myapp.google.credentialsPath");
        normalized = stripOptionalAssignmentPrefix(normalized, "myapp.local.googleCredentialsPath");
        normalized = stripMatchingQuotes(normalized);
        return normalized.trim();
    }

    private String stripOptionalAssignmentPrefix(String rawValue, String propertyName) {
        if (!rawValue.startsWith(propertyName)) {
            return rawValue;
        }
        int separatorIndex = rawValue.indexOf('=');
        if (separatorIndex < 0) {
            return rawValue;
        }
        return rawValue.substring(separatorIndex + 1).trim();
    }

    private String stripMatchingQuotes(String rawValue) {
        if (rawValue.length() < 2) {
            return rawValue;
        }
        char first = rawValue.charAt(0);
        char last = rawValue.charAt(rawValue.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return rawValue.substring(1, rawValue.length() - 1);
        }
        return rawValue;
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
        if (googleProjectId == null || googleProjectId.isBlank()) {
            missingProperties.add("myapp.google.projectId");
        }
        if (googleLocation == null || googleLocation.isBlank()) {
            missingProperties.add("myapp.google.location");
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

    private record GoogleAdaptiveMtTranslateRequest(
            List<String> content,
            String dataset,
            String mimeType
    ) {
        private GoogleAdaptiveMtTranslateRequest(List<String> content, String dataset) {
            this(content, dataset, "text/plain");
        }
    }

    private record GoogleTranslationBatchResult(
            List<String> translatedTexts,
            String routeUsed
    ) {
    }

    private record GoogleGlossaryCreateRequest(GoogleGlossaryDefinition glossary) {
    }

    private record GoogleGlossaryDefinition(
            String name,
            GoogleGlossaryLanguagePair languagePair,
            GoogleGlossaryInputConfig inputConfig
    ) {
    }

    private record GoogleGlossaryLanguagePair(
            String sourceLanguageCode,
            String targetLanguageCode
    ) {
    }

    private record GoogleGlossaryInputConfig(GoogleGlossaryGcsSource gcsSource) {
    }

    private record GoogleGlossaryGcsSource(String inputUri) {
    }

    private record GoogleLongRunningOperation(
            String name,
            Boolean done,
            GoogleOperationError error
    ) {
    }

    private record GoogleOperationError(int code, String message) {
    }

    private record GoogleTranslateTextResponse(
            List<GoogleTextTranslation> translations,
            List<GoogleTextTranslation> glossaryTranslations
    ) {
    }

    private record GoogleTextTranslation(String translatedText) {
    }

    private record GoogleAdaptiveDatasetCreateRequest(
            String name,
            String sourceLanguageCode,
            String targetLanguageCode
    ) {
    }

    private record GoogleAdaptiveDatasetImportRequest(
            GoogleAdaptiveDatasetFileInputSource inputSource
    ) {
    }

    private record GoogleAdaptiveDatasetFileInputSource(
            GoogleAdaptiveDatasetGcsInputSource gcsInputSource,
            String displayName
    ) {
    }

    private record GoogleAdaptiveDatasetGcsInputSource(String inputUri) {
    }

    private record GoogleAdaptiveMtFilesListResponse(List<GoogleAdaptiveMtFile> adaptiveMtFiles) {
    }

    private record GoogleAdaptiveMtFile(String displayName, String state) {
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

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
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

    public record AdaptiveDatasetSyncResult(
            String dataset,
            String importStatus,
            String gcsUri
    ) {
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
