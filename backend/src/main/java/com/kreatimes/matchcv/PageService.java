package com.kreatimes.matchcv;

import com.kreatimes.matchcv.CvService.CvProfile;
import com.kreatimes.matchcv.MatchService.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Analiza una página de resultados que el usuario está viendo en el navegador de la app.
// Una sola llamada a Gemini saca las ofertas del texto de la página y las puntúa contra el CV.
// No visitamos ninguna web: el texto lo manda la app, de la página que el usuario tiene abierta.
@Service
public class PageService {

    private static final Logger log = LoggerFactory.getLogger(PageService.class);
    private static final int MAX_TEXT_CHARS = 60_000;
    private static final int MAX_LINKS = 300;

    public record PageLink(String text, String href) {
    }

    public record AnalyzeRequest(CvProfile profile, String role, String portal, String text, List<PageLink> links) {
    }

    // Lo que devuelve la IA por cada oferta que encuentra en la página
    public record Found(String title, String company, String city, String modality, String contract,
                        Integer salaryMin, Integer salaryMax, String url,
                        int score, List<String> matches, List<String> missing, String reason) {
    }

    private static final String PROMPT = """
            Este es el texto de una página de resultados del portal de empleo %s.
            Incluye menús, filtros, anuncios y pie de página: ignóralos.

            1. Extrae TODAS las ofertas de empleo que aparecen en la página, sin inventar ninguna.
            2. Puntúa de 0 a 100 cuánto encaja el candidato con cada oferta para este puesto: %s.
               - 80-100: cumple casi todo lo imprescindible. 50-79: encaja con carencias. <50: no encaja.
               - Ten en cuenta experiencia exigida, tecnologías, nivel, idiomas y estudios.
               - Si la oferta es de otro sector distinto al puesto buscado, nota baja.

            Campos de cada oferta:
            - title, company, city: tal como aparecen.
            - modality: "Presencial", "Híbrido" o "Teletrabajo" si aparece; si no, null.
            - contract: tipo de contrato y jornada si aparecen, p. ej. "Indefinido · Jornada completa"; si no, null.
            - salaryMin, salaryMax: salario bruto ANUAL en euros, como número, si aparece; si no, null.
            - url: el enlace de la oferta, copiado de la lista ENLACES (el que tenga un texto igual
              o parecido al título). Si no hay ninguno, null. No inventes enlaces.
            - matches: requisitos que el candidato cumple (máx. 4, muy breves).
            - missing: requisitos que no cumple o no aparecen en su perfil (máx. 4, muy breves).
            - reason: una frase explicando la nota.
            Responde en español.

            CANDIDATO:
            %s

            ENLACES (texto -> url):
            %s

            PÁGINA:
            %s
            """;

    private final ChatClient chatClient;

    public PageService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<Match> analyze(AnalyzeRequest r) {
        if (r == null || r.profile() == null) {
            throw new IllegalArgumentException("Falta el perfil del CV");
        }
        if (r.text() == null || r.text().isBlank()) {
            throw new IllegalArgumentException("La página está vacía");
        }
        String portal = r.portal() == null || r.portal().isBlank() ? "web" : r.portal().strip().toLowerCase();
        String role = r.role() == null || r.role().isBlank() ? r.profile().role() : r.role();
        Map<String, String> links = usefulLinks(r.links());

        String linkList = links.entrySet().stream()
                .map(e -> e.getValue() + " -> " + e.getKey())
                .collect(Collectors.joining("\n"));
        String text = r.text().length() > MAX_TEXT_CHARS ? r.text().substring(0, MAX_TEXT_CHARS) : r.text();

        long start = System.currentTimeMillis();
        List<Found> found;
        try {
            found = chatClient.prompt()
                    .user(PROMPT.formatted(portal, role, MatchService.describe(r.profile()), linkList, text))
                    .call()
                    .entity(new ParameterizedTypeReference<List<Found>>() {
                    });
        } catch (RuntimeException e) {
            log.warn("No se ha podido analizar la página: {}", e.getMessage());
            throw new IllegalArgumentException("No se ha podido analizar la página. Inténtalo de nuevo.");
        }
        log.info("Página de {} analizada: {} ofertas en {} ms",
                portal, found == null ? 0 : found.size(), System.currentTimeMillis() - start);

        return (found == null ? List.<Found>of() : found).stream()
                .filter(f -> f.title() != null && !f.title().isBlank())
                .map(f -> toMatch(f, portal, links))
                .sorted(Comparator.comparingInt(Match::score).reversed())
                .toList();
    }

    private static Match toMatch(Found f, String portal, Map<String, String> links) {
        // Solo aceptamos enlaces que de verdad estaban en la página: la IA no puede inventar URLs
        String url = f.url() != null && links.containsKey(f.url()) ? f.url() : null;
        String key = url != null ? url : Text.normalize(f.title()) + "|" + Text.normalize(f.company());
        String modality = f.modality() == null ? "" : f.modality();
        JobOffer offer = new JobOffer(
                portal + ":" + key,
                portal,
                f.title(),
                f.company(),
                f.city(),
                null,
                Stream.of(f.modality(), f.contract())
                        .filter(s -> s != null && !s.isBlank())
                        .collect(Collectors.joining(" · ")),
                f.salaryMin(),
                f.salaryMax(),
                Text.normalize(modality).contains("teletrabajo") || Text.normalize(modality).contains("remoto"),
                null,
                url);
        return new Match(offer, Math.max(0, Math.min(100, f.score())),
                f.matches() == null ? List.of() : f.matches(),
                f.missing() == null ? List.of() : f.missing(),
                f.reason());
    }

    // Enlaces de la página sin duplicados ni basura (iconos, "Ver más"...): url -> texto
    static Map<String, String> usefulLinks(List<PageLink> links) {
        Map<String, String> out = new LinkedHashMap<>();
        if (links == null) return out;
        for (PageLink l : links) {
            if (out.size() >= MAX_LINKS) break;
            if (l == null || l.href() == null || !l.href().startsWith("http")) continue;
            String text = l.text() == null ? "" : l.text().strip().replaceAll("\\s+", " ");
            if (text.length() < 5) continue;
            out.putIfAbsent(l.href(), text.length() > 150 ? text.substring(0, 150) : text);
        }
        return out;
    }
}
