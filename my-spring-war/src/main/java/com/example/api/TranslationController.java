package com.example.api;

import com.example.api.dto.TranslationCompareRequest;
import com.example.api.dto.TranslationCompareResult;
import com.example.api.dto.TranslationCompareTranslateImportRequest;
import com.example.api.dto.TranslationExportRequest;
import com.example.api.dto.TranslationExportResult;
import com.example.api.dto.TranslationFileLoadRequest;
import com.example.api.dto.GlossarySyncRequest;
import com.example.api.dto.GlossarySyncResponse;
import com.example.api.dto.AdaptiveDatasetSyncRequest;
import com.example.api.dto.AdaptiveDatasetSyncResponse;
import com.example.api.dto.TranslationSaveRequest;
import com.example.api.dto.SupportedLanguage;
import com.example.api.dto.TranslationRow;
import com.example.service.TranslationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/translations")
public class TranslationController {

    private final TranslationService translationService;
    private final String crmDataDirectory;
    private final String crmGlossaryDirectory;
    private final String crmAdaptiveDatasetDirectory;
    private final String selfServiceDataDirectory;
    private final String selfServiceGlossaryDirectory;
    private final String selfServiceAdaptiveDatasetDirectory;

    public TranslationController(
            TranslationService translationService,
            @Value("${myapp.crm.sourceFilesDirectory:data}") String crmDataDirectory,
            @Value("${myapp.crm.glossaryDirectory:data}") String crmGlossaryDirectory,
            @Value("${myapp.crm.adaptiveDatasetDirectory:data}") String crmAdaptiveDatasetDirectory,
            @Value("${myapp.selfService.sourceFilesDirectory:data}") String selfServiceDataDirectory,
            @Value("${myapp.selfService.glossaryDirectory:data}") String selfServiceGlossaryDirectory,
            @Value("${myapp.selfService.adaptiveDatasetDirectory:data}") String selfServiceAdaptiveDatasetDirectory
    ) {
        this.translationService = translationService;
        this.crmDataDirectory = crmDataDirectory;
        this.crmGlossaryDirectory = crmGlossaryDirectory;
        this.crmAdaptiveDatasetDirectory = crmAdaptiveDatasetDirectory;
        this.selfServiceDataDirectory = selfServiceDataDirectory;
        this.selfServiceGlossaryDirectory = selfServiceGlossaryDirectory;
        this.selfServiceAdaptiveDatasetDirectory = selfServiceAdaptiveDatasetDirectory;
    }

    @GetMapping("/files")
    public Map<String, Object> listFiles(@RequestParam(value = "context", required = false, defaultValue = "crm") String context) throws Exception {
        String path = resolveSourceDirectory(context);
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
    public Map<String, Object> listGlossaryFiles(@RequestParam(value = "context", required = false, defaultValue = "crm") String context) throws Exception {
        List<String> files = listFilesByExtension(resolveGlossaryDirectory(context), null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files", files);
        return response;
    }

    @GetMapping("/admin/adaptive-dataset/files")
    public Map<String, Object> listAdaptiveDatasetFiles(@RequestParam(value = "context", required = false, defaultValue = "crm") String context) throws Exception {
        List<String> files = listFilesByExtension(resolveAdaptiveDatasetDirectory(context), ".tsv");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files", files);
        response.put("configuredFile", translationService.configuredAdaptiveDatasetFileName());
        return response;
    }

    @PostMapping("/load")
    public List<TranslationRow> loadRows(@RequestBody TranslationFileLoadRequest request) throws Exception {
        return translationService.loadRows(resolveSourceDirectory(request.getContext()), request.getFileName());
    }

    @PostMapping("/translate")
    public TranslationExportResult translateAndStore(
            @RequestBody TranslationExportRequest request,
            @RequestHeader(value = "X-Translation-Request-Id", required = false) String translationRequestId
    ) throws Exception {
        try {
            return translationService.translateAndStore(
                    resolveSourceDirectory(request.getContext()),
                    request.getFileName(),
                    request.getTargetLanguage(),
                    request.getRows(),
                    translationRequestId
            );
        } finally {
            translationService.clearTranslationCancellation(translationRequestId);
        }
    }

    @PostMapping("/translate/{requestId}/cancel")
    public Map<String, Object> cancelTranslation(@PathVariable("requestId") String requestId) {
        translationService.cancelTranslationRequest(requestId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("status", "cancel_requested");
        return response;
    }


    @PostMapping("/compare")
    public TranslationCompareResult compareFiles(@RequestBody TranslationCompareRequest request) throws Exception {
        return translationService.compareFiles(
                resolveSourceDirectory(request.getContext()),
                request.getFileName1(),
                request.getFileName2()
        );
    }

    @PostMapping("/compare/translate-import")
    public TranslationExportResult translateImportCompareRows(@RequestBody TranslationCompareTranslateImportRequest request) throws Exception {
        return translationService.translateAndImport(
                resolveSourceDirectory(request.getContext()),
                request.getSourceFileName(),
                request.getTargetFileName(),
                request.getRows()
        );
    }

    @PostMapping("/save")
    public Map<String, Object> saveRows(@RequestBody TranslationSaveRequest request) throws Exception {
        Path savedFile = translationService.saveRows(
                resolveSourceDirectory(request.getContext()),
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
                resolveGlossaryDirectory(request.getContext()) + "/" + request.getGlossaryFilePath(),
                sourceLanguage,
                targetLanguage
        );
        return new GlossarySyncResponse(sourceLanguage, targetLanguage, glossary);
    }

    @PostMapping("/admin/adaptive-dataset/sync")
    public AdaptiveDatasetSyncResponse syncAdaptiveDataset(@RequestBody AdaptiveDatasetSyncRequest request) throws Exception {
        String sourceLanguage = request.getSourceLanguage();
        String targetLanguage = request.getTargetLanguage();
        TranslationService.AdaptiveDatasetSyncResult syncResult = translationService.synchronizeAdaptiveDataset(
                resolveAdaptiveDatasetDirectory(request.getContext()) + "/" + request.getTsvFilePath(),
                sourceLanguage,
                targetLanguage
        );
        return new AdaptiveDatasetSyncResponse(
                sourceLanguage,
                targetLanguage,
                syncResult.dataset(),
                syncResult.importStatus(),
                syncResult.gcsUri()
        );
    }
    private String resolveSourceDirectory(String context) {
        return "selfService".equalsIgnoreCase(context) ? selfServiceDataDirectory : crmDataDirectory;
    }

    private String resolveGlossaryDirectory(String context) {
        return "selfService".equalsIgnoreCase(context) ? selfServiceGlossaryDirectory : crmGlossaryDirectory;
    }

    private String resolveAdaptiveDatasetDirectory(String context) {
        return "selfService".equalsIgnoreCase(context) ? selfServiceAdaptiveDatasetDirectory : crmAdaptiveDatasetDirectory;
    }

    private List<String> listFilesByExtension(String directory, String extension) throws Exception {
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> extension == null || name.toLowerCase().endsWith(extension))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

}
