package com.example.api;

import com.example.api.dto.TranslationCompareRequest;
import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationCompareTranslateImportRequest;
import com.example.api.dto.TranslationExportRequest;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationFileLoadRequest;
import com.example.api.dto.GlossarySyncRequest;
import com.example.api.dto.GlossarySyncResponse;
import com.example.api.dto.TranslationSaveRequest;
import com.example.api.dto.SupportedLanguage;
import com.example.api.dto.TranslationRow;
import com.example.service.TranslationService;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path", path);
        response.put("files", files);
        return response;
    }

    @GetMapping("/supported-languages")
    public List<SupportedLanguage> supportedLanguages() {
        return translationService.getSupportedLanguages();
    }

    @GetMapping("/admin/glossary/files")
    public Map<String, Object> listGlossaryFiles() throws Exception {
        List<String> files = translationService.listGlossaryFiles();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files", files);
        return response;
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


    @PostMapping("/compare")
    public TranslationCompareResult compareFiles(@RequestBody TranslationCompareRequest request) throws Exception {
        return translationService.compareFiles(
                request.getPath(),
                request.getFileName1(),
                request.getFileName2()
        );
    }

    @PostMapping("/compare/translate-import")
    public TranslationExportResult translateImportCompareRows(@RequestBody TranslationCompareTranslateImportRequest request) throws Exception {
        return translationService.translateAndImport(
                request.getPath(),
                request.getSourceFileName(),
                request.getTargetFileName(),
                request.getRows()
        );
    }

    @PostMapping("/save")
    public Map<String, Object> saveRows(@RequestBody TranslationSaveRequest request) throws Exception {
        Path savedFile = translationService.saveRows(
                request.getPath(),
                request.getFileName(),
                request.getRows()
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("file", savedFile.toAbsolutePath().toString());
        response.put("rowCount", request.getRows() == null ? 0 : request.getRows().size());
        return response;
    }

    @PostMapping("/admin/glossary/sync")
    public GlossarySyncResponse syncGlossary(@RequestBody GlossarySyncRequest request) throws Exception {
        String sourceLanguage = request.getSourceLanguage();
        String targetLanguage = request.getTargetLanguage();
        String glossary = translationService.synchronizeGlossary(
                request.getGlossaryFilePath(),
                sourceLanguage,
                targetLanguage
        );
        return new GlossarySyncResponse(sourceLanguage, targetLanguage, glossary);
    }
}
