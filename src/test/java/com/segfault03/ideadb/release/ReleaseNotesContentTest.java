package com.segfault03.ideadb.release;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseNotesContentTest {
    @Test void packagedGuidesHaveReadableOfflineScreenshots() throws Exception {
        assertFalse(ReleaseNotesContent.version().contains("${"));
        assertEquals(3, ReleaseNotesContent.features().size());
        for (var feature : ReleaseNotesContent.features()) {
            assertFalse(feature.title().isBlank()); assertEquals(3, feature.steps().size());
            assertFalse(feature.alt().isBlank());
            try (var input = getClass().getResourceAsStream(feature.image())) {
                assertNotNull(input, feature.image());
                var image = ImageIO.read(input); assertNotNull(image);
                assertTrue(image.getWidth() >= 1200); assertTrue(image.getHeight() >= 760);
            }
        }
    }
    @Test void fullNotesContainTheUpcomingReleaseRatherThanOlderReleases() {
        String notes = ReleaseNotesContent.changelog();
        assertTrue(notes.startsWith("## [0.1.0-alpha]"));
        assertTrue(notes.contains("PostgreSQL"));
        assertFalse(notes.contains("## [0.0.2-alpha]"));
    }
}
