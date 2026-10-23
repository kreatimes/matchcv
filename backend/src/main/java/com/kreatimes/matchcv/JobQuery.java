package com.kreatimes.matchcv;

// Búsqueda neutra: cada fuente la traduce a sus propios parámetros.
// province puede ser null (toda España).
public record JobQuery(String keywords, String province, int maxDaysOld) {
}
