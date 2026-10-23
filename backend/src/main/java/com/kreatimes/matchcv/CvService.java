package com.kreatimes.matchcv;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

// CV en PDF -> texto -> perfil estructurado (JSON) con Gemini.
@Service
public class CvService {

    // Lo que la IA saca del CV. No guardamos nombre, email ni telefono: no hacen falta para buscar.
    public record CvProfile(
            String role,                 // puesto que busca o encaja, p. ej. "Desarrollador móvil Flutter"
            String seniority,            // "prácticas", "junior", "mid" o "senior"
            Integer yearsExperience,     // años de experiencia en el sector del puesto
            List<String> skills,         // tecnologías y competencias
            List<String> languages,      // idiomas con nivel, p. ej. "Inglés B2"
            String city,
            String province,             // provincia española, p. ej. "Madrid"; null si no aparece
            List<String> searchKeywords, // 2-4 búsquedas cortas para un portal de empleo
            String summary               // 2-3 frases sobre el candidato, para comparar con las ofertas
    ) {
    }

    public record CvAnalysis(String text, CvProfile profile) {
    }

    private static final String PROMPT = """
            Extrae el perfil profesional de este CV para buscarle ofertas de empleo.
            Reglas:
            - Usa solo lo que pone el CV. Si un dato no aparece, déjalo en null.
            - yearsExperience: años trabajando en el sector del puesto que busca, no en otros sectores.
            - searchKeywords: entre 2 y 4 búsquedas cortas (1-3 palabras), como las escribiría
              alguien en InfoJobs. De la más a la menos importante. Ejemplo: "Flutter", "Desarrollador Java".
              Si el CV indica que busca prácticas (FCT, becas), la primera búsqueda debe ser
              "Prácticas" seguida de su especialidad, por ejemplo "Prácticas DAM".
            - Responde en español.

            CV:
            %s
            """;

    private final ChatClient chatClient;

    public CvService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public CvAnalysis analyze(String filename, byte[] pdfBytes) {
        String text = readPdf(filename, pdfBytes);
        // .entity() le pide a la IA un JSON con la forma de CvProfile y lo convierte solo
        CvProfile profile = chatClient.prompt()
                .user(PROMPT.formatted(text))
                .call()
                .entity(CvProfile.class);
        if (profile == null || profile.searchKeywords() == null || profile.searchKeywords().isEmpty()) {
            throw new IllegalArgumentException("No se ha podido sacar un perfil de este CV");
        }
        return new CvAnalysis(text, profile);
    }

    static String readPdf(String filename, byte[] pdfBytes) {
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("El CV tiene que estar en PDF");
        }
        ByteArrayResource resource = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        String text;
        try {
            text = new PagePdfDocumentReader(resource).get().stream()
                    .map(Document::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n"))
                    .replaceAll("[ \\t]+", " ");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("No se ha podido leer el PDF. ¿Está dañado?");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("El PDF no tiene texto (¿es un PDF escaneado?)");
        }
        return text;
    }
}
