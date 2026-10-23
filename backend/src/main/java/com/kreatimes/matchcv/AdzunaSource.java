package com.kreatimes.matchcv;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

// Ofertas de Adzuna España (https://developer.adzuna.com). Agregador: junta ofertas de muchos portales.
// Sus condiciones piden mostrar "Jobs by Adzuna" junto a sus ofertas (lo hace la app).
@Component
@ConditionalOnExpression("!'${matchcv.adzuna.app-id:}'.isBlank()")
public class AdzunaSource implements JobSource {

    private static final int RESULTS = 50;

    private final RestClient http;
    private final String appId;
    private final String appKey;

    public AdzunaSource(RestClient.Builder builder,
                        @Value("${matchcv.adzuna.app-id}") String appId,
                        @Value("${matchcv.adzuna.app-key}") String appKey) {
        this.http = builder.baseUrl("https://api.adzuna.com/v1/api/jobs/es").build();
        this.appId = appId;
        this.appKey = appKey;
    }

    @Override
    public String name() {
        return "adzuna";
    }

    @Override
    public List<JobOffer> search(JobQuery q) {
        JsonNode body = http.get()
                .uri(u -> {
                    u.path("/search/1")
                            .queryParam("app_id", appId)
                            .queryParam("app_key", appKey)
                            .queryParam("what", q.keywords())
                            .queryParam("results_per_page", RESULTS)
                            .queryParam("max_days_old", q.maxDaysOld())
                            .queryParam("content-type", "application/json");
                    if (q.province() != null && !q.province().isBlank()) {
                        u.queryParam("where", q.province());
                    }
                    return u.build();
                })
                .retrieve()
                .body(JsonNode.class);

        List<JobOffer> offers = new ArrayList<>();
        if (body == null) return offers;
        for (JsonNode o : body.path("results")) {
            // area: ["España", "Comunidad de Madrid", "Madrid", ...] -> la provincia suele ser la tercera
            JsonNode area = o.path("location").path("area");
            String province = area.size() > 2 ? area.get(2).asText() : area.path(area.size() - 1).asText(null);
            String text = Text.normalize(o.path("title").asText() + " " + o.path("description").asText());
            offers.add(new JobOffer(
                    "adzuna:" + o.path("id").asText(),
                    name(),
                    o.path("title").asText(null),
                    o.path("company").path("display_name").asText(null),
                    o.path("location").path("display_name").asText(null),
                    province,
                    o.path("description").asText(""),   // Adzuna solo da un extracto de la descripción
                    o.hasNonNull("salary_min") ? o.path("salary_min").asInt() : null,
                    o.hasNonNull("salary_max") ? o.path("salary_max").asInt() : null,
                    // Adzuna no tiene campo de remoto: lo deducimos del texto
                    text.contains("remoto") || text.contains("teletrabajo") || text.contains("remote"),
                    date(o.path("created").asText()),
                    o.path("redirect_url").asText(null)));
        }
        return offers;
    }

    private static LocalDate date(String iso) {
        return iso == null || iso.length() < 10 ? null : LocalDate.parse(iso.substring(0, 10));
    }
}