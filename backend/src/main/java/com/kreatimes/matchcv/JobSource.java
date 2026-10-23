package com.kreatimes.matchcv;

import java.util.List;

// Una fuente de ofertas (InfoJobs, Jooble, Adzuna, ATS...).
// Para añadir una nueva basta con crear un @Component que implemente esta interfaz.
public interface JobSource {

    String name();

    List<JobOffer> search(JobQuery query);
}
