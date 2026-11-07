package com.segfault03.ideadb.sql.lang;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The IDE looks descriptions up by name and fails at runtime (the Settings pages throw) when one is missing. */
class PluginDescriptionsTest {
    private static NodeList elements(String tag) throws Exception {
        try (InputStream in = PluginDescriptionsTest.class.getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull(in);
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getElementsByTagName(tag);
        }
    }

    @Test
    void everyIntentionHasADescription() throws Exception {
        NodeList actions = elements("intentionAction");
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < actions.getLength(); i++) {
            String className = ((Element) actions.item(i)).getElementsByTagName("className").item(0).getTextContent().trim();
            // The platform names the directory after the class without package and without the '$' of a nested class.
            String directory = className.substring(className.lastIndexOf('.') + 1).replace("$", "");
            if (PluginDescriptionsTest.class.getResource("/intentionDescriptions/" + directory + "/description.html") == null) missing.add(directory);
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyInspectionHasADescription() throws Exception {
        NodeList inspections = elements("localInspection");
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < inspections.getLength(); i++) {
            String name = ((Element) inspections.item(i)).getAttribute("shortName");
            if (PluginDescriptionsTest.class.getResource("/inspectionDescriptions/" + name + ".html") == null) missing.add(name);
        }
        assertEquals(List.of(), missing);
    }
}
