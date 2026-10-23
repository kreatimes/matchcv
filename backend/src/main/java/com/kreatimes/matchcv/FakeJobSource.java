package com.kreatimes.matchcv;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

// Fuente de prueba: ofertas inventadas en fake-offers.json.
// Sirve para programar y probar todo sin claves ni gastar cuota de ninguna API.
@Component
@ConditionalOnProperty(name = "matchcv.sources.fake.enabled", havingValue = "true")
public class FakeJobSource implements JobSource {

    private final List<JobOffer> offers;

    public FakeJobSource(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = new ClassPathResource("fake-offers.json").getInputStream()) {
            List<JobOffer> raw = objectMapper.readValue(in, new TypeReference<List<JobOffer>>() {
            });
            // Fechas relativas a hoy (una oferta nueva cada dia hacia atras) para que nunca caduquen
            this.offers = IntStream.range(0, raw.size())
                    .mapToObj(i -> withPublished(raw.get(i), LocalDate.now().minusDays(i)))
                    .toList();
        }
    }

    @Override
    public String name() {
        return "fake";
    }

    // Igual que un buscador real: alguna palabra clave en título o descripción, misma provincia
    // (o remoto) y publicada hace menos de maxDaysOld días.
    @Override
    public List<JobOffer> search(JobQuery q) {
        List<String> words = Arrays.stream(Text.normalize(q.keywords()).split(" "))
                .filter(w -> w.length() >= 3)
                .toList();
        String province = Text.normalize(q.province());
        LocalDate since = LocalDate.now().minusDays(q.maxDaysOld());

        return offers.stream()
                .filter(o -> {
                    String text = Text.normalize(o.title() + " " + o.description());
                    return words.stream().anyMatch(text::contains);
                })
                .filter(o -> province.isEmpty() || o.remote() || Text.normalize(o.province()).equals(province))
                .filter(o -> o.published() == null || !o.published().isBefore(since))
                .toList();
    }

    private static JobOffer withPublished(JobOffer o, LocalDate date) {
        return new JobOffer(o.id(), o.source(), o.title(), o.company(), o.city(), o.province(), o.description(),
                o.salaryMin(), o.salaryMax(), o.remote(), date, o.url());
    }
}
