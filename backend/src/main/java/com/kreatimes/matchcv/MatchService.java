package com.kreatimes.matchcv;

import com.kreatimes.matchcv.CvService.CvProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// Matching en dos pasos para gastar poca cuota de Gemini:
// 1. Embeddings (barato): nos quedamos con las topN ofertas mas parecidas al CV.
// 2. LLM (caro): una sola llamada puntua esas topN y explica que cumples y que te falta.
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);
    private static final int MAX_DAYS_OLD = 30;
    private static final int MAX_OFFER_CHARS = 1500;

    public record Match(JobOffer offer, int score, List<String> matches, List<String> missing, String reason) {
    }

    public record MatchResult(CvProfile profile, int offersFound, List<Match> matches) {
    }

    // Lo que devuelve la IA por cada oferta (index = numero de la oferta en el prompt)
    public record Score(int index, int score, List<String> matches, List<String> missing, String reason) {
    }

    private static final String PROMPT = """
            Puntúa de 0 a 100 cuánto encaja este candidato con cada oferta.
            - 80-100: cumple casi todo lo imprescindible. 50-79: encaja con carencias. <50: no encaja.
            - Ten en cuenta experiencia exigida, tecnologías, nivel e idiomas.
            - matches: requisitos de la oferta que el candidato cumple (máx. 4, muy breves).
            - missing: requisitos que no cumple o no aparecen en su perfil (máx. 4, muy breves).
            - reason: una frase explicando la nota.
            - Devuelve un elemento por cada oferta, con su número en index. Responde en español.

            CANDIDATO:
            %s

            OFERTAS:
            %s
            """;

    private final CvService cvService;
    private final JobSearchService jobSearchService;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final int topN;
    private final int maxQueries;

    public MatchService(CvService cvService, JobSearchService jobSearchService, EmbeddingModel embeddingModel,
                        ChatClient.Builder chatClientBuilder,
                        @Value("${matchcv.top-n}") int topN, @Value("${matchcv.max-queries}") int maxQueries) {
        this.cvService = cvService;
        this.jobSearchService = jobSearchService;
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClientBuilder.build();
        this.topN = topN;
        this.maxQueries = maxQueries;
    }

    // province permite buscar en otra provincia distinta a la del CV (null = la del CV)
    public MatchResult match(String filename, byte[] pdfBytes, String province) {
        CvService.CvAnalysis cv = cvService.analyze(filename, pdfBytes);
        CvProfile profile = cv.profile();

        String where = province != null && !province.isBlank() ? province : profile.province();
        List<JobQuery> queries = profile.searchKeywords().stream()
                .limit(maxQueries)
                .map(k -> new JobQuery(k, where, MAX_DAYS_OLD))
                .toList();
        List<JobOffer> offers = jobSearchService.search(queries);
        log.info("Ofertas encontradas: {} ({} busquedas)", offers.size(), queries.size());
        if (offers.isEmpty()) {
            return new MatchResult(profile, 0, List.of());
        }

        List<JobOffer> shortlist = mostSimilar(cv.text(), offers);
        return new MatchResult(profile, offers.size(), score(profile, shortlist));
    }

    // Paso 1: similitud coseno entre el embedding del CV y el de cada oferta
    List<JobOffer> mostSimilar(String cvText, List<JobOffer> offers) {
        if (offers.size() <= topN) {
            return offers;
        }
        List<String> texts = new ArrayList<>();
        texts.add(truncate(cvText, 6000));
        offers.forEach(o -> texts.add(offerText(o)));
        // Una sola peticion de embeddings para el CV y todas las ofertas
        List<float[]> vectors = embeddingModel.embed(texts);
        float[] cvVector = vectors.get(0);

        return IntStream.range(0, offers.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> cosine(cvVector, vectors.get(i + 1))).reversed())
                .limit(topN)
                .map(offers::get)
                .toList();
    }

    // Paso 2: el LLM puntua la lista corta en una sola llamada
    List<Match> score(CvProfile profile, List<JobOffer> offers) {
        String numbered = IntStream.range(0, offers.size())
                .mapToObj(i -> "[" + (i + 1) + "] " + offerText(offers.get(i)))
                .collect(Collectors.joining("\n\n"));

        List<Score> scores = chatClient.prompt()
                .user(PROMPT.formatted(describe(profile), numbered))
                .call()
                .entity(new ParameterizedTypeReference<List<Score>>() {
                });

        Map<Integer, Score> byIndex = (scores == null ? List.<Score>of() : scores).stream()
                .filter(s -> s.index() >= 1 && s.index() <= offers.size())
                .collect(Collectors.toMap(Score::index, Function.identity(), (a, b) -> a));

        // Si la IA se salta alguna oferta, la dejamos al final con nota 0 en vez de perderla
        return IntStream.range(0, offers.size())
                .mapToObj(i -> {
                    Score s = byIndex.get(i + 1);
                    return s == null
                            ? new Match(offers.get(i), 0, List.of(), List.of(), "Sin puntuar")
                            : new Match(offers.get(i), Math.max(0, Math.min(100, s.score())),
                            orEmpty(s.matches()), orEmpty(s.missing()), s.reason());
                })
                .sorted(Comparator.comparingInt(Match::score).reversed())
                .toList();
    }

    // También la usa PageService
    static String describe(CvProfile p) {
        return """
                Puesto: %s (%s, %s años de experiencia)
                Habilidades: %s
                Idiomas: %s
                Ubicación: %s
                Resumen: %s""".formatted(p.role(), p.seniority(), p.yearsExperience(),
                p.skills(), p.languages(), p.city(), p.summary());
    }

    private static String offerText(JobOffer o) {
        String salary = o.salaryMin() == null ? "no indicado" : o.salaryMin() + "-" + o.salaryMax() + " €";
        return truncate("%s | %s | %s%s | Salario: %s\n%s".formatted(
                o.title(), o.company(), o.city(), o.remote() ? " (remoto)" : "", salary, o.description()),
                MAX_OFFER_CHARS);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
