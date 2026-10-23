package com.kreatimes.matchcv;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final CvService cvService;
    private final MatchService matchService;
    private final JobSearchService jobSearchService;

    public ApiController(CvService cvService, MatchService matchService, JobSearchService jobSearchService) {
        this.cvService = cvService;
        this.matchService = matchService;
        this.jobSearchService = jobSearchService;
    }

    // Solo el perfil extraído del CV (útil para comprobar qué ha entendido la IA)
    @PostMapping(value = "/cv/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CvService.CvProfile profile(@RequestParam("file") MultipartFile file) throws IOException {
        return cvService.analyze(file.getOriginalFilename(), file.getBytes()).profile();
    }

    // Flujo completo: CV -> perfil -> buscar en todas las fuentes -> puntuar
    @PostMapping(value = "/match", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MatchService.MatchResult match(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "province", required = false) String province)
            throws IOException {
        return matchService.match(file.getOriginalFilename(), file.getBytes(), province);
    }

    // Búsqueda directa sin IA, para probar las fuentes
    @GetMapping("/offers")
    public List<JobOffer> offers(@RequestParam String keywords,
                                 @RequestParam(required = false) String province,
                                 @RequestParam(defaultValue = "30") int maxDaysOld) {
        return jobSearchService.search(List.of(new JobQuery(keywords, province, maxDaysOld)));
    }

    @GetMapping("/sources")
    public List<String> sources() {
        return jobSearchService.sourceNames();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
