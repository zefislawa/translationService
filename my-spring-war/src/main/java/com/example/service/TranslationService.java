package com.example.service;

import com.example.config.OutboundApiLoggingInterceptor;
import com.example.api.dto.TranslationCompareDifference;
import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationRow;
import com.example.api.dto.SupportedLanguage;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final Path defaultDataDir;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;
    private final String googleApiKey;
    private final String googleProjectId;
    private final String googleLocation;
    private final String googleGlossaryId;
    private final String supportedLanguagesDisplayLocale;
    private final String referenceLanguageFile;
    private final String riskyTermsFile;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{[^{}]+}}|\\{[^{}]+}|%\\d*\\$?[sdfoxegc]|<[^>]+>"
    );
    private static final String PLACEHOLDER_TOKEN_PREFIX = "__PH_";
    private static final Set<String> UI_FOCUSED_PREFIXES = Set.of("b", "m", "l");
    private static final Set<String> DEFAULT_AMBIGUOUS_TERMS = Set.of(
            "close", "clear", "apply", "lead", "charge", "rate", "run", "view", "basket", "shopping cart"
    );

    public TranslationService(
            @Value("${myapp.dataDir}") String defaultDataDir,
            @Value("${myapp.google.apiKey}") String googleApiKey,
            @Value("${myapp.google.projectId}") String googleProjectId,
            @Value("${myapp.google.location:global}") String googleLocation,
            @Value("${myapp.google.glossaryId:}") String googleGlossaryId,
            @Value("${myapp.google.supportedLanguagesDisplayLocale:en}") String supportedLanguagesDisplayLocale,
            @Value("${myapp.referenceLanguageFile:en}") String referenceLanguageFile,
            @Value("${myapp.riskyTermsFile:risky-terms.txt}") String riskyTermsFile,
            ObjectMapper mapper,
            RestTemplateBuilder restTemplateBuilder
    ) throws Exception {
        this.defaultDataDir = Path.of(defaultDataDir).toAbsolutePath();
        this.googleApiKey = googleApiKey;
        this.googleProjectId = googleProjectId;
        this.googleLocation = googleLocation;
        this.googleGlossaryId = googleGlossaryId;
        this.supportedLanguagesDisplayLocale = supportedLanguagesDisplayLocale;
        this.referenceLanguageFile = referenceLanguageFile;
        this.riskyTermsFile = riskyTermsFile;
        this.mapper = mapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(60))
                .requestFactory(() -> new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
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

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
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

        String sourceLanguage = resolveSourceLanguage(fileName);
        TranslationPipelineResult pipelineResult = runTranslationPipeline(customPath, rows, sourceLanguage, targetLanguage);
        List<String> translatedTexts = pipelineResult.translatedTexts();

        if (translatedTexts.size() != rows.size()) {
            throw new IllegalStateException("Google Translate returned an unexpected number of translated strings");
        }

        Map<String, Map<String, String>> translatedPayload = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            TranslationRow row = rows.get(i);
            translatedPayload
                    .computeIfAbsent(row.getSection(), k -> new LinkedHashMap<>())
                    .put(row.getKey(), translatedTexts.get(i));
        }

        Path sourceFile = resolveJsonFile(customPath, fileName);
        Path outputFile = sourceFile.getParent().resolve(targetLanguage + ".json").normalize();
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), translatedPayload);
        writeValidationReport(outputFile, pipelineResult.validationReport());

        return new TranslationExportResult(outputFile.toAbsolutePath().toString(), targetLanguage, translatedTexts.size());
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
        requireGoogleApiKey();
        String url = UriComponentsBuilder
                .fromHttpUrl("https://translation.googleapis.com/language/translate/v2/languages")
                .queryParam("key", googleApiKey)
                .queryParam("target", supportedLanguagesDisplayLocale)
                .toUriString();

        ResponseEntity<GoogleSupportedLanguagesResponse> response = restTemplate.getForEntity(
                url,
                GoogleSupportedLanguagesResponse.class
        );

        GoogleSupportedLanguagesResponse responseBody = response.getBody();
        if (responseBody == null || responseBody.data() == null || responseBody.data().languages() == null) {
            throw new IllegalStateException("Google supported languages response is empty");
        }

        return responseBody.data().languages().stream()
                .filter(language -> language.languageCode() != null && !language.languageCode().isBlank())
                .map(language -> new SupportedLanguage(language.languageCode(), Objects.requireNonNullElse(language.displayName(), language.languageCode())))
                .sorted(Comparator.comparing(SupportedLanguage::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private TranslationPipelineResult runTranslationPipeline(String customPath, List<TranslationRow> rows, String sourceLanguage, String targetLanguage) {
        List<TranslationItem> flattenedItems = flattenRows(rows);
        Set<String> configuredRiskyTerms = loadConfiguredRiskyTerms(customPath);
        List<PreparedTranslationItem> preprocessedItems = preprocessItems(flattenedItems, configuredRiskyTerms);
        List<PreparedTranslationItem> protectedItems = protectPlaceholders(preprocessedItems);
        List<String> translatedProtectedTexts = sourceLanguage.equalsIgnoreCase(targetLanguage)
                ? protectedItems.stream().map(PreparedTranslationItem::protectedText).toList()
                : callGoogleTranslationLlm(sourceLanguage, targetLanguage, protectedItems);

        if (translatedProtectedTexts.size() != protectedItems.size()) {
            throw new IllegalStateException("Google Translation API returned an unexpected number of translated strings");
        }

        List<String> restoredTexts = restorePlaceholders(protectedItems, translatedProtectedTexts);
        ValidationReport validationReport = validateResults(protectedItems, translatedProtectedTexts, restoredTexts);
        return new TranslationPipelineResult(restoredTexts, validationReport);
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
            StringBuffer protectedBuffer = new StringBuffer();
            int placeholderCounter = 0;
            while (matcher.find()) {
                String token = PLACEHOLDER_TOKEN_PREFIX + placeholderCounter++ + "__";
                placeholderMap.put(token, matcher.group());
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
        requireGoogleApiKey();
        requireGoogleProjectId();

        String endpoint = "https://translation.googleapis.com/v3/projects/" + googleProjectId
                + "/locations/" + googleLocation + ":translateText";
        String url = UriComponentsBuilder
                .fromHttpUrl(endpoint)
                .queryParam("key", googleApiKey)
                .toUriString();

        List<String> contents = items.stream().map(PreparedTranslationItem::protectedText).toList();
        GoogleTranslateTextRequest body = new GoogleTranslateTextRequest(
                contents,
                sourceLanguage,
                targetLanguage,
                "text/plain",
                "general/translation-llm",
                resolveGlossaryConfig()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GoogleTranslateTextResponse> response = restTemplate.postForEntity(
                url,
                new HttpEntity<>(body, headers),
                GoogleTranslateTextResponse.class
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

    private ValidationReport validateResults(
            List<PreparedTranslationItem> items,
            List<String> translatedProtectedTexts,
            List<String> restoredTexts
    ) {
        List<String> issues = new ArrayList<>();
        List<PreprocessingReportItem> preprocessingItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PreparedTranslationItem item = items.get(i);
            String translatedProtected = translatedProtectedTexts.get(i);
            String restored = restoredTexts.get(i);

            Set<String> missingTokens = new HashSet<>();
            for (String token : item.placeholders().keySet()) {
                if (!translatedProtected.contains(token)) {
                    missingTokens.add(token);
                }
            }

            if (!missingTokens.isEmpty()) {
                issues.add(item.item().fullKey() + ": missing placeholder tokens " + missingTokens);
            }
            if (restored.isBlank() && !item.normalizedText().isBlank()) {
                issues.add(item.item().fullKey() + ": translated result became blank");
            }

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
        }
        return new ValidationReport(items.size(), issues.size(), issues, preprocessingItems);
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
        Path reportFile = outputFile.getParent().resolve(fileName + ".validation-report.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
    }

    private GoogleGlossaryConfig resolveGlossaryConfig() {
        if (googleGlossaryId == null || googleGlossaryId.isBlank()) {
            return null;
        }

        String glossaryPath = googleGlossaryId.startsWith("projects/")
                ? googleGlossaryId
                : "projects/" + googleProjectId + "/locations/" + googleLocation + "/glossaries/" + googleGlossaryId;
        return new GoogleGlossaryConfig(glossaryPath);
    }

    private void requireGoogleApiKey() {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            throw new IllegalStateException("Google API key is missing. Configure it via environment variable or local.properties");
        }
    }

    private void requireGoogleProjectId() {
        if (googleProjectId == null || googleProjectId.isBlank()) {
            throw new IllegalStateException("Google project id is missing. Configure it via environment variable or local.properties");
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

    private String resolveSourceLanguage(String fileName) {
        String fallbackLanguage = extractLanguageFromFileName(normalizeReferenceLanguageFile(referenceLanguageFile));

        if (fileName == null || fileName.isBlank()) {
            return fallbackLanguage.toLowerCase(Locale.ROOT);
        }

        String normalized = Path.of(fileName).getFileName().toString().replaceFirst("(?i)\\.json$", "");
        if (!normalized.matches("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")) {
            return fallbackLanguage.toLowerCase(Locale.ROOT);
        }

        return normalized.toLowerCase(Locale.ROOT);
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

    private record GoogleSupportedLanguagesResponse(GoogleSupportedLanguagesData data) {
    }

    private record GoogleSupportedLanguagesData(List<GoogleSupportedLanguage> languages) {
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

    private record ValidationReport(
            int totalItems,
            int issueCount,
            List<String> issues,
            List<PreprocessingReportItem> preprocessing
    ) {
    }

    private record TranslationPipelineResult(List<String> translatedTexts, ValidationReport validationReport) {
    }
}
