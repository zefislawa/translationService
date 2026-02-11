package com.example.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class JsonFileStore {

    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileStore(@Value("${myapp.dataDir}") String dataDir) throws Exception {
        this.dataDir = Path.of(dataDir).toAbsolutePath();
        Files.createDirectories(this.dataDir);
    }

    public void write(String name, Object value) throws Exception {
        Path file = dataDir.resolve(name + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }

    public Object read(String name) throws Exception {
        Path file = dataDir.resolve(name + ".json");
        if (!Files.exists(file)) return null;
        return mapper.readValue(file.toFile(), Object.class);
    }

    public String dir() {
        return dataDir.toString();
    }
}
