package com.kreatimes.matchcv;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Flujo completo (CV -> perfil -> buscar -> puntuar) con la fuente de prueba y una IA falsa:
// se ejecuta gratis, sin claves de Gemini ni de InfoJobs.
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding.text=none",
        "spring.ai.google.genai.api-key=test",
        "spring.ai.google.genai.embedding.api-key=test",
        "matchcv.sources.fake.enabled=true",
        "matchcv.infojobs.client-id=",
        "matchcv.top-n=3"
})
class MatchcvFlowTest {

    @Autowired
    MatchService matchService;
    @Autowired
    JobSearchService jobSearchService;

    @Test
    void cvToRankedOffers() throws Exception {
        assertEquals(List.of("fake"), jobSearchService.sourceNames(), "sin claves solo está la fuente de prueba");

        var result = matchService.match("cv.pdf", pdf("Estudiante DAM. Flutter, Dart, Firebase, Java, Spring Boot."), null);

        assertEquals("Desarrollador móvil", result.profile().role());
        assertTrue(result.offersFound() > 3, "busca con varias palabras clave");
        assertEquals(3, result.matches().size(), "solo puntúa la lista corta (top-n)");
        assertTrue(result.matches().stream().noneMatch(m -> m.offer().province().equals("Barcelona")),
                "filtra por la provincia del CV");
        assertEquals(90, result.matches().get(0).score(), "ordenadas por nota");
        assertEquals("Sin puntuar", result.matches().get(2).reason(), "las que la IA se salta no se pierden");

        assertThrows(IllegalArgumentException.class, () -> matchService.match("cv.docx", new byte[0], null));
        assertThrows(IllegalArgumentException.class, () -> matchService.match("cv.pdf", "no es un pdf".getBytes(), null));
    }

    @Test
    void duplicatesAcrossSearchesAreRemoved() {
        var offers = jobSearchService.search(List.of(
                new JobQuery("flutter", "Madrid", 30),
                new JobQuery("desarrollador", "Madrid", 30)));
        long acme = offers.stream().filter(o -> Text.normalize(o.company()).equals("acme tech")).count();
        assertEquals(1, acme, "misma oferta en dos búsquedas y escrita distinto = una sola");
        assertEquals(offers.size(), offers.stream().map(JobOffer::id).distinct().count());
    }

    @Test
    void infoJobsHelpers() {
        assertEquals("a-coruna", InfoJobsSource.provinceKey("A Coruña"));
        assertEquals(24000, InfoJobsSource.salary("24.000 €", false));
        assertEquals(18000, InfoJobsSource.salary("1.500 €", true));
        assertNull(InfoJobsSource.salary("", false));
        assertEquals("_7_DAYS", InfoJobsSource.sinceDate(7));
        assertEquals("ANY", InfoJobsSource.sinceDate(30));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    @TestConfiguration
    static class FakeAi {

        // Embedding falso: cuenta letras, suficiente para ordenar por parecido
        @Bean
        EmbeddingModel embeddingModel() {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    List<Embedding> out = new ArrayList<>();
                    for (int i = 0; i < request.getInstructions().size(); i++) {
                        out.add(new Embedding(vector(request.getInstructions().get(i)), i));
                    }
                    return new EmbeddingResponse(out);
                }

                @Override
                public float[] embed(Document document) {
                    return vector(document.getText());
                }

                private float[] vector(String text) {
                    float[] v = new float[26];
                    for (char c : text.toLowerCase().toCharArray()) {
                        if (c >= 'a' && c <= 'z') v[c - 'a']++;
                    }
                    return v;
                }
            };
        }

        // Chat falso: un perfil fijo para el CV y notas para las ofertas 1 y 2 (la 3 se la "salta")
        @Bean
        ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    String json = prompt.getContents().contains("Extrae el perfil")
                            ? """
                            {"role":"Desarrollador móvil","seniority":"junior","yearsExperience":0,
                             "skills":["Flutter","Dart","Java"],"languages":["Inglés B2"],
                             "city":"San Fernando de Henares","province":"Madrid",
                             "searchKeywords":["Flutter","Desarrollador","Java"],"summary":"Estudiante DAM"}"""
                            : """
                            [{"index":1,"score":60,"matches":["Java"],"missing":["Docker"],"reason":"Encaja a medias"},
                             {"index":2,"score":90,"matches":["Flutter"],"missing":[],"reason":"Encaja muy bien"}]""";
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
                }
            };
        }
    }
}
