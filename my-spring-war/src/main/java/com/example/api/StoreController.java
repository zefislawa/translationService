package com.example.api;

import com.example.storage.JsonFileStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/store")
public class StoreController {

    private final JsonFileStore store;

    public StoreController(JsonFileStore store) {
        this.store = store;
    }

    @PostMapping("/{name}")
    public Map<String, Object> save(@PathVariable("name") String name, @RequestBody Map<String, Object> body) throws Exception {
        store.write(name, body);
        return Map.of("saved", true, "name", name, "dir", store.dir());
    }

    @GetMapping("/{name}")
    public Object load(@PathVariable("name") String name) throws Exception {
        Object val = store.read(name);
        if (val == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        return val;
    }
}
