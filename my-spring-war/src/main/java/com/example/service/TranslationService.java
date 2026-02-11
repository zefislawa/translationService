package com.example.service;

import com.example.api.dto.TranslationRow;
import com.example.storage.JsonFileStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TranslationService {

    private final JsonFileStore store;

    public TranslationService(JsonFileStore store) {
        this.store = store;
    }

    @SuppressWarnings("unchecked")
    public List<TranslationRow> loadRows(String fileName) throws Exception {
        Object raw = store.read(fileName);
        if (raw == null) return List.of();

        if (!(raw instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("Invalid JSON format: expected object at root");
        }

        List<TranslationRow> rows = new ArrayList<>();

        for (Map.Entry<?, ?> sectionEntry : top.entrySet()) {
            String section = String.valueOf(sectionEntry.getKey());
            Object sectionVal = sectionEntry.getValue();

            if (!(sectionVal instanceof Map<?, ?> sectionMap)) {
                // skip or throw; skipping is more tolerant
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
}
