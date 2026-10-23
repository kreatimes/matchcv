package com.kreatimes.matchcv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// Junta todas las fuentes: las consulta en paralelo, aguanta que alguna falle y quita duplicados.
// El resto de la app solo habla con este servicio, nunca con una fuente concreta.
@Service
public class JobSearchService {

    private static final Logger log = LoggerFactory.getLogger(JobSearchService.class);
    private static final long TIMEOUT_SECONDS = 20;

    private final List<JobSource> sources;

    // Spring inyecta aqui todas las clases que implementan JobSource
    public JobSearchService(List<JobSource> sources) {
        this.sources = sources;
        log.info("Fuentes de ofertas activas: {}", sources.stream().map(JobSource::name).toList());
    }

    public List<String> sourceNames() {
        return sources.stream().map(JobSource::name).toList();
    }

    // ponytail: sin caché; si se agota la cuota de alguna API, cachear por consulta unas horas.
    public List<JobOffer> search(List<JobQuery> queries) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<JobOffer>>> calls = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (JobSource source : sources) {
                for (JobQuery query : queries) {
                    calls.add(pool.submit(() -> source.search(query)));
                    labels.add(source.name() + " '" + query.keywords() + "'");
                }
            }

            List<JobOffer> all = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                try {
                    all.addAll(calls.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (Exception e) {
                    // Una fuente caida no tumba la busqueda: seguimos con las demas
                    calls.get(i).cancel(true);
                    log.warn("Fuente fallida ({}): {}", labels.get(i), e.toString());
                }
            }
            return deduplicate(all);
        }
    }

    // La misma oferta puede salir en varias fuentes o en varias busquedas.
    // Se considera repetida si coinciden titulo, empresa y ciudad (sin tildes ni mayusculas).
    // Nos quedamos con la primera, salvo que una repetida traiga salario y la primera no.
    static List<JobOffer> deduplicate(List<JobOffer> offers) {
        Map<String, JobOffer> unique = new LinkedHashMap<>();
        for (JobOffer o : offers) {
            String key = Text.normalize(o.title()) + "|" + Text.normalize(o.company()) + "|" + Text.normalize(o.city());
            unique.merge(key, o, (kept, dup) -> kept.salaryMin() == null && dup.salaryMin() != null ? dup : kept);
        }
        return new ArrayList<>(unique.values());
    }
}
