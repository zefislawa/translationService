package com.example.api;

import com.example.api.dto.TranslationExportRequest;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationFileLoadRequest;
import com.example.api.dto.SupportedLanguage;
import com.example.api.dto.TranslationRow;
import com.example.service.TranslationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/translations")
public class TranslationController {

    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @GetMapping("/files")
    public Map<String, Object> listFiles(@RequestParam(value = "path", required = false) String path) throws Exception {
        List<String> files = translationService.listJsonFiles(path);
        return Map.of("path", path, "files", files);
    }

    @GetMapping("/supported-languages")
    public List<SupportedLanguage> supportedLanguages() {
        return translationService.getSupportedLanguages();
    }

    @PostMapping("/load")
    public List<TranslationRow> loadRows(@RequestBody TranslationFileLoadRequest request) throws Exception {
        return translationService.loadRows(request.getPath(), request.getFileName());
    }

    @PostMapping("/translate")
    public TranslationExportResult translateAndStore(@RequestBody TranslationExportRequest request) throws Exception {
        return translationService.translateAndStore(
                request.getPath(),
                request.getFileName(),
                request.getTargetLanguage(),
                request.getRows()
        );
    }
}
