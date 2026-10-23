package com.kreatimes.matchcv;

import java.text.Normalizer;

final class Text {

    private Text() {
    }

    // "Desarrollador Flutter (Madrid)" -> "desarrollador flutter madrid": sin tildes, mayúsculas ni signos
    static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }
}
