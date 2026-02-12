package com.example.api;

import com.example.api.dto.TranslationExportRequest;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationFileLoadRequest;
import com.example.api.dto.TranslationPathRequest;
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

    @PostMapping("/files")
    public Map<String, Object> listFiles(@RequestBody(required = false) TranslationPathRequest request) throws Exception {
        String path = request == null ? null : request.getPath();
        List<String> files = translationService.listJsonFiles(path);
        return Map.of("path", path == null ? "" : path, "files", files);
    }

    @PostMapping("/load")
    public List<TranslationRow> loadRows(@RequestBody TranslationFileLoadRequest request) throws Exception {
        return translationService.loadRows(request.getPath(), request.getFileName());
    }

    @PostMapping("/translate")
    public TranslationExportResult exportTranslationPayload(@RequestBody TranslationExportRequest request) throws Exception {
        return translationService.exportGoogleTranslatePayload(
                request.getPath(),
                request.getFileName(),
                request.getTargetLanguage(),
                request.getRows()
        );
    }
}
