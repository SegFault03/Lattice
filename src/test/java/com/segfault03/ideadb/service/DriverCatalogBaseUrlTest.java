package com.segfault03.ideadb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Maven Central override must work with or without a trailing slash. */
class DriverCatalogBaseUrlTest {
    private static final String DEFAULT = "https://repo.maven.apache.org/maven2/";

    @Test void unsetOrBlankKeepsMavenCentral() {
        assertEquals(DEFAULT, DriverCatalog.baseUrl(null));
        assertEquals(DEFAULT, DriverCatalog.baseUrl(""));
        assertEquals(DEFAULT, DriverCatalog.baseUrl("  "));
    }

    @Test void overrideEndsWithExactlyOneSlash() {
        assertEquals("http://localhost:8081/maven2/", DriverCatalog.baseUrl("http://localhost:8081/maven2"));
        assertEquals("http://localhost:8081/maven2/", DriverCatalog.baseUrl("http://localhost:8081/maven2/"));
        assertEquals("http://localhost:8081/maven2/", DriverCatalog.baseUrl(" http://localhost:8081/maven2// "));
    }
}
