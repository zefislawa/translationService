package com.example.api;

import com.example.api.dto.TranslationRow;
import com.example.service.TranslationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/translations")
public class TranslationController {

    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @GetMapping("/{file}")
    public List<TranslationRow> getRows(@PathVariable("file") String file) throws Exception {
        return translationService.loadRows(file);
    }
}
