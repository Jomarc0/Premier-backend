package com.premier.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

final class AllowedOrigins {

    static final String DEFAULT =
        "http://localhost:5173,http://localhost:5174,http://localhost:5175,http://localhost:5176,http://localhost:5177,"
            + "http://127.0.0.1:5173,http://127.0.0.1:5174,http://127.0.0.1:5175,http://127.0.0.1:5176,http://127.0.0.1:5177,"
            + "https://premierusers.vercel.app,https://premierrfid.vercel.app,https://premierdriver.vercel.app,"
            + "https://premieradmin.vercel.app,https://premier-staff.vercel.app";

    private static final List<String> LOCAL_ORIGINS = List.of(
        "http://localhost:5173",
        "http://localhost:5174",
        "http://localhost:5175",
        "http://localhost:5176",
        "http://localhost:5177",
        "http://127.0.0.1:5173",
        "http://127.0.0.1:5174",
        "http://127.0.0.1:5175",
        "http://127.0.0.1:5176",
        "http://127.0.0.1:5177"
    );

    private AllowedOrigins() {
    }

    static List<String> parse(String origins) {
        LinkedHashSet<String> parsed = new LinkedHashSet<>(LOCAL_ORIGINS);
        if (origins != null) {
            Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .forEach(parsed::add);
        }
        return List.copyOf(parsed);
    }
}
