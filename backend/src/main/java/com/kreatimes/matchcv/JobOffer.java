package com.kreatimes.matchcv;

import java.time.LocalDate;

// Oferta en formato común: cada fuente traduce sus datos a esto.
// id lleva prefijo de la fuente ("infojobs:abc") para que no choquen entre fuentes.
public record JobOffer(
        String id,
        String source,
        String title,
        String company,
        String city,
        String province,
        String description,
        Integer salaryMin,   // anual en euros; null si la oferta no lo dice
        Integer salaryMax,
        boolean remote,
        LocalDate published,
        String url
) {
}
