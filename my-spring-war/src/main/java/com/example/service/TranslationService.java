package com.example.service;

import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class TranslationService {

    private final Path defaultDataDir;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;
    private final String googleApiKey;

    public TranslationService(
            @Value("${myapp.dataDir}") String defaultDataDir,
            @Value("${myapp.google.apiKey}") String googleApiKey,
            ObjectMapper mapper,
            RestTemplateBuilder restTemplateBuilder
    ) throws Exception {
        this.defaultDataDir = Path.of(defaultDataDir).toAbsolutePath();
        this.googleApiKey = googleApiKey;
        this.mapper = mapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(15))
                .setReadTimeout(Duration.ofSeconds(60))
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

        Object raw = mapper.readValue(file.toFile(), Object.class);
        if (raw == null) return List.of();

        if (!(raw instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("Invalid JSON format: expected object at root");
        }

        List<TranslationRow> rows = new ArrayList<>();

        for (Map.Entry<?, ?> sectionEntry : top.entrySet()) {
            String section = String.valueOf(sectionEntry.getKey());
            Object sectionVal = sectionEntry.getValue();

            if (!(sectionVal instanceof Map<?, ?> sectionMap)) {
                continue;
            }

            for (Map.Entry<?, ?> kv : sectionMap.entrySet()) {
                String key = String.valueOf(kv.getKey());
                String text = kv.getValue() == null ? "" : String.valueOf(kv.getValue());
                rows.add(new TranslationRow(section, key, text));
            }
        }

        return rows;
    }

    public TranslationExportResult translateAndStore(String customPath, String fileName, String targetLanguage, List<TranslationRow> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("No rows provided for translation");
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }

        String sourceLanguage = extractLanguageFromFileName(fileName);
        List<String> contents = rows.stream().map(TranslationRow::getText).toList();
        List<String> translatedTexts = callGoogleTranslate(sourceLanguage, targetLanguage, contents);

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

        return new TranslationExportResult(outputFile.toAbsolutePath().toString(), targetLanguage, translatedTexts.size());
    }

    private List<String> callGoogleTranslate(String sourceLanguage, String targetLanguage, List<String> contents) {
        String url = UriComponentsBuilder
                .fromHttpUrl("https://translation.googleapis.com/language/translate/v2")
                .queryParam("key", googleApiKey)
                .toUriString();

        GoogleTranslateRequest body = new GoogleTranslateRequest(contents, sourceLanguage, targetLanguage, "text");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GoogleTranslateResponse> response = restTemplate.postForEntity(
                url,
                new HttpEntity<>(body, headers),
                GoogleTranslateResponse.class
        );

        GoogleTranslateResponse responseBody = response.getBody();
        if (responseBody == null || responseBody.data() == null || responseBody.data().translations() == null) {
            throw new IllegalStateException("Google Translate response is empty");
        }

        return responseBody.data().translations()
                .stream()
                .map(GoogleTranslation::translatedText)
                .toList();
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
        return normalized;
    }

    private record GoogleTranslateRequest(
            List<String> q,
            String source,
            String target,
            String format
    ) {
    }

    private record GoogleTranslateResponse(GoogleTranslateData data) {
    }

    private record GoogleTranslateData(List<GoogleTranslation> translations) {
    }

    private record GoogleTranslation(String translatedText) {
    }
}
