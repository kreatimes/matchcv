package com.kreatimes.matchcv;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// Ofertas de InfoJobs (https://developer.infojobs.net, operación "offer list").
// Solo se activa si hay client-id: sin claves la app arranca igual con las demás fuentes.
@Component
@ConditionalOnExpression("!'${matchcv.infojobs.client-id:}'.isBlank()")
public class InfoJobsSource implements JobSource {

    private static final int MAX_RESULTS = 50;

    private final RestClient http;

    public InfoJobsSource(RestClient.Builder builder,
                          @Value("${matchcv.infojobs.client-id}") String clientId,
                          @Value("${matchcv.infojobs.client-secret}") String clientSecret) {
        // InfoJobs usa autenticación Basic con el client id y el secret de tu app
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        this.http = builder
                .baseUrl("https://api.infojobs.net/api/9")
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    @Override
    public String name() {
        return "infojobs";
    }

    @Override
    public List<JobOffer> search(JobQuery q) {
        JsonNode body = http.get()
                .uri(u -> {
                    u.path("/offer")
                            .queryParam("q", q.keywords())
                            .queryParam("maxResults", MAX_RESULTS)
                            .queryParam("sinceDate", sinceDate(q.maxDaysOld()));
                    if (q.province() != null && !q.province().isBlank()) {
                        u.queryParam("province", provinceKey(q.province()));
                    }
                    return u.build();
                })
                .retrieve()
                .body(JsonNode.class);

        List<JobOffer> offers = new ArrayList<>();
        if (body == null) return offers;
        for (JsonNode o : body.path("offers")) {
            boolean monthly = Text.normalize(o.path("salaryPeriod").path("value").asText()).contains("mes");
            offers.add(new JobOffer(
                    "infojobs:" + o.path("id").asText(),
                    name(),
                    o.path("title").asText(null),
                    o.path("author").path("name").asText(null),
                    o.path("city").asText(null),
                    o.path("province").path("value").asText(null),
                    // ponytail: el listado solo trae los requisitos mínimos, no la descripción completa;
                    // si el matching se queda corto, pedir el detalle (/offer/{id}) solo de la lista corta.
                    o.path("requirementMin").asText(""),
                    salary(o.path("salaryMin").path("value").asText(), monthly),
                    salary(o.path("salaryMax").path("value").asText(), monthly),
                    Text.normalize(o.path("teleworking").path("value").asText()).contains("solo teletrabajo"),
                    date(o.path("published").asText()),
                    o.path("link").asText(null)));
        }
        return offers;
    }

    // InfoJobs solo acepta estos rangos de antigüedad
    static String sinceDate(int days) {
        if (days <= 1) return "_24_HOURS";
        if (days <= 7) return "_7_DAYS";
        if (days <= 15) return "_15_DAYS";
        return "ANY";
    }

    // "A Coruña" -> "a-coruna": formato de las claves del diccionario de provincias de InfoJobs
    static String provinceKey(String province) {
        return Text.normalize(province).replace(' ', '-');
    }

    // "24.000 €" -> 24000; los salarios mensuales se pasan a anuales (x12)
    static Integer salary(String value, boolean monthly) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 7) return null;
        int amount = Integer.parseInt(digits);
        return monthly ? amount * 12 : amount;
    }

    private static LocalDate date(String iso) {
        return iso == null || iso.length() < 10 ? null : LocalDate.parse(iso.substring(0, 10));
    }
}
