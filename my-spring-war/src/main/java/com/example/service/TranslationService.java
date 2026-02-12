package com.example.service;

import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class TranslationService {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path defaultDataDir;
    private final ObjectMapper mapper;

    public TranslationService(@Value("${myapp.dataDir}") String defaultDataDir, ObjectMapper mapper) throws Exception {
        this.defaultDataDir = Path.of(defaultDataDir).toAbsolutePath();
        this.mapper = mapper;
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

    public TranslationExportResult exportGoogleTranslatePayload(String customPath, String fileName, String targetLanguage, List<TranslationRow> rows) throws Exception {
        if (rows == null) {
            rows = List.of();
        }

        Path dir = resolveDataDir(customPath);
        String cleanName = stripJsonExtension(fileName);
        String outputFileName = cleanName + "-google-v2-" + targetLanguage + "-" + TS_FORMAT.format(LocalDateTime.now()) + ".json";

        List<String> texts = rows.stream().map(TranslationRow::getText).toList();
        List<String> ids = rows.stream().map(r -> r.getSection() + "." + r.getKey()).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", targetLanguage);
        payload.put("format", "text");
        payload.put("q", texts);

        // Metadata makes it easy to map translated rows back in the next integration step.
        payload.put("ids", ids);

        Path out = dir.resolve(outputFileName);
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), payload);

        return new TranslationExportResult(out.toAbsolutePath().toString(), targetLanguage, texts.size());
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

    private String stripJsonExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return "translations";
        return fileName.replaceFirst("(?i)\\.json$", "");
    }
}
