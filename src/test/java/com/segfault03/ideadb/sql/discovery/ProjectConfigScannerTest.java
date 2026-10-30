package com.segfault03.ideadb.sql.discovery;

import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectConfigScannerTest {
    private static List<String> described(String file, String text) {
        return ProjectConfigScanner.urlsIn(file, text).stream().map(ParsedJdbcUrl::describe).toList();
    }

    @Test void configFileNames() {
        for (String name : new String[] {"application.properties", "application.yml", "application.yaml", "application-dev.yml", "application-prod.properties",
                "bootstrap.yml", "persistence.xml", "hibernate.cfg.xml", "hibernate.properties", "context.xml", "jdbc.properties", "database.properties", "db-test.properties"}) {
            assertTrue(ProjectConfigScanner.isConfigFileName(name), name);
        }
        for (String name : new String[] {"pom.xml", "build.gradle", "application.java", "applications.yml", "readme.properties", "messages.properties", "log4j.properties", "docker-compose.yml"}) {
            assertFalse(ProjectConfigScanner.isConfigFileName(name), name);
        }
    }

    @Test void aSpringPropertiesFile() {
        String text = """
                # datasource
                spring.datasource.url=jdbc:postgresql://localhost:5432/shop
                spring.datasource.username=shop_app
                spring.datasource.password=hunter2
                spring.jpa.hibernate.ddl-auto=update
                """;
        assertEquals(List.of("PostgreSQL localhost:5432/shop"), described("application.properties", text));
    }

    @Test void aSpringYamlFileWithNestedKeysCommentsAndProfiles() {
        String text = """
                spring:
                  datasource:
                    url: jdbc:mysql://db:3306/shop?useSSL=false   # local
                    username: root
                    password: "hunter2"
                  jpa:
                    show-sql: true
                ---
                spring:
                  config:
                    activate:
                      on-profile: test
                  datasource:
                    url: 'jdbc:h2:mem:testdb'
                """;
        assertEquals(List.of("MySQL db:3306/shop", "H2 in-memory testdb"), described("application.yml", text));
    }

    @Test void aFlatDottedYamlKeyAndOtherFrameworksUrlKeys() {
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.yml", "spring.datasource.url: jdbc:postgresql://pg/shop\n"));
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties", "quarkus.datasource.jdbc.url=jdbc:postgresql://pg:5432/shop\n"));
        assertEquals(List.of("MySQL db:3306/shop"), described("application.yml", "datasources:\n  default:\n    url: jdbc:mysql://db/shop\n"));
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties", "spring.datasource.hikari.jdbc-url=jdbc:postgresql://pg/shop\n"));
    }

    @Test void escapesAsWrittenByPropertiesStoreAreUnescaped() {
        assertEquals(List.of("MySQL localhost:3306/shop"), described("application.properties", "spring.datasource.url=jdbc\\:mysql\\://localhost\\:3306/shop\n"));
        assertEquals(List.of("MySQL localhost:3306/shop"), described("application.properties", "spring.datasource.url\\=ignored\nspring\\.datasource\\.url = jdbc\\:mysql\\://localhost\\:3306/shop\n"));
        assertEquals("jdbc:mysql://h/a b\t\\x", ProjectConfigScanner.properties("k=jdbc\\:mysql\\://h/a\\ b\\t\\\\x\n").get("k"));
        assertEquals("caf\u00e9", ProjectConfigScanner.properties("k=caf\\u00e9\n").get("k"));
        assertEquals("a=b:c d", ProjectConfigScanner.properties("a\\=b\\:c\\ d=a\\=b\\:c d\n").get("a=b:c d"));
    }

    @Test void continuationLinesAndBangCommentsInPropertiesFiles() {
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties",
                "! generated\n# also a comment\nspring.datasource.url=jdbc:postgresql:\\\n    //pg:5432/\\\n    shop\n"));
        assertEquals(List.of("MySQL h:3306/shop"), described("application.properties", "! spring.datasource.url=jdbc:postgresql://x/y\nspring.datasource.url=jdbc\\:mysql\\://h\\:3306/\\\nshop\n"));
    }

    @Test void placeholdersTakeTheirDefaultOrAnotherSettingOfTheFile() {
        assertEquals(List.of("PostgreSQL localhost:5432/shop"),
                described("application.properties", "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:shop}\n"));
        assertEquals(List.of("MySQL dbhost:3306/shop"),
                described("application.properties", "db.host=dbhost\nspring.datasource.url=jdbc:mysql://${db.host}/shop\n"));
        assertEquals(List.of("PostgreSQL localhost:5432/shop"),
                described("application.properties", "spring.datasource.url=jdbc:postgresql://${DB_HOST:-localhost}/shop\n"));
    }

    @Test void anUnresolvedPlaceholderLeavesThatPartUnknownInsteadOfInventingIt() {
        var urls = ProjectConfigScanner.urlsIn("application.properties", "spring.datasource.url=jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}\n");
        assertEquals(1, urls.size());
        assertNull(urls.get(0).host());
        assertNull(urls.get(0).database());
        assertEquals(DatabaseType.POSTGRESQL, urls.get(0).type());
        assertEquals(List.of(), described("application.properties", "spring.datasource.url=@db.url@\n"));
    }

    @Test void onlyJdbcUrlsUnderUrlKeysAreRead() {
        assertEquals(List.of(), described("application.properties", "app.homepage=jdbc:mysql://x/y\nspring.datasource.driver-class-name=org.postgresql.Driver\n"));
        assertEquals(List.of(), described("application.properties", "spring.r2dbc.url=r2dbc:postgresql://pg/shop\n"));
        assertEquals(List.of(), described("application.properties", "spring.datasource.url=jdbc:oracle:thin:@//h:1521/s\n"));
    }

    @Test void duplicatesAreCollapsed() {
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties",
                "spring.datasource.url=jdbc:postgresql://pg/shop\nflyway.url=jdbc:postgresql://pg:5432/shop?x=1\n"));
    }

    @Test void persistenceXmlHibernateConfigAndTomcatContext() {
        String persistence = """
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
                  <persistence-unit name="shop">
                    <properties>
                      <property name="jakarta.persistence.jdbc.url" value="jdbc:postgresql://pg:5432/shop"/>
                      <property name="jakarta.persistence.jdbc.user" value="bob"/>
                      <property name="jakarta.persistence.jdbc.password" value="hunter2"/>
                    </properties>
                  </persistence-unit>
                </persistence>
                """;
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("persistence.xml", persistence));
        String hibernate = """
                <?xml version="1.0"?>
                <!DOCTYPE hibernate-configuration PUBLIC "-//Hibernate/Hibernate Configuration DTD 3.0//EN" "http://www.hibernate.org/dtd/hibernate-configuration-3.0.dtd">
                <hibernate-configuration><session-factory>
                  <property name="hibernate.connection.url">jdbc:mysql://localhost/shop</property>
                  <property name="hibernate.connection.password">hunter2</property>
                </session-factory></hibernate-configuration>
                """;
        assertEquals(List.of("MySQL localhost:3306/shop"), described("hibernate.cfg.xml", hibernate));
        assertEquals(List.of("H2 in-memory demo"), described("context.xml", "<Context><Resource name=\"jdbc/demo\" url=\"jdbc:h2:mem:demo\" username=\"sa\" password=\"x\"/></Context>"));
    }

    @Test void aMalformedOrHostileXmlFileYieldsNothingAndLoadsNothing() {
        assertEquals(List.of(), described("persistence.xml", "<persistence><property name='x' value='jdbc:mysql://h/d'"));
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><persistence><property name=\"hibernate.connection.url\" value=\"jdbc:mysql://h/&e;\"/></persistence>";
        for (var url : ProjectConfigScanner.urlsIn("persistence.xml", xxe)) assertFalse(url.describe().contains("root:"), "an external entity must not be read");
    }

    @Test void secretsAreNeverKept() {
        String text = "spring.datasource.url=jdbc:mysql://h/d\nspring.datasource.username=bob\nspring.datasource.password=hunter2\napi.token=abc\nsecret.key=zzz\nuser=x\n";
        var settings = ProjectConfigScanner.properties(text);
        // A user name is the one kept setting; it is not a secret and only prefills a dialog.
        assertEquals(java.util.Set.of("spring.datasource.url", "spring.datasource.username", "user"), settings.keySet());
        var yaml = ProjectConfigScanner.yaml("spring:\n  datasource:\n    url: jdbc:mysql://h/d\n    password: hunter2\n    username: bob\n");
        assertEquals(java.util.Set.of("spring.datasource.url", "spring.datasource.username"), yaml.keySet());
        var xml = ProjectConfigScanner.xml("<p><property name=\"jakarta.persistence.jdbc.password\" value=\"hunter2\"/><property name=\"jakarta.persistence.jdbc.url\" value=\"jdbc:mysql://h/d\"/></p>");
        assertEquals(java.util.Set.of("jakarta.persistence.jdbc.url"), xml.keySet());
        assertFalse(settings.toString().contains("hunter2") || yaml.toString().contains("hunter2") || xml.toString().contains("hunter2"));
    }

    @Test void propertiesSyntaxDetails() {
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties", "spring.datasource.url : jdbc:postgresql://pg/shop\n"));
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties", "spring.datasource.url jdbc:postgresql://pg/shop\n"));
        assertEquals(List.of("PostgreSQL pg:5432/shop"), described("application.properties", "spring.datasource.url=jdbc:postgresql:\\\n    //pg/shop\n"));
        assertEquals(List.of(), described("application.properties", "#spring.datasource.url=jdbc:postgresql://pg/shop\n!spring.datasource.url=jdbc:postgresql://pg/shop\n"));
    }

    // ---- the user name next to a URL ------------------------------------------------------------------------------

    private static String userOf(String file, String text) {
        var found = ProjectConfigScanner.findIn(file, text);
        assertEquals(1, found.size());
        return found.get(0).user();
    }

    @Test void theUserNameBesideASpringUrlIsRead() {
        assertEquals("shop_app", userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop\nspring.datasource.username=shop_app\nspring.datasource.password=hunter2\n"));
        assertEquals("root", userOf("application.yml", "spring:\n  datasource:\n    url: jdbc:mysql://h/d\n    username: root\n    password: \"hunter2\"\n"));
        assertEquals("bob", userOf("application.properties", "spring.datasource.url=jdbc:mysql://h/d\nspring.datasource.user=bob\n"));
    }

    @Test void theUserNameOfPersistenceAndHibernateConfigurationsIsRead() {
        assertEquals("bob", userOf("persistence.xml", "<persistence><persistence-unit name='u'><properties>"
                + "<property name='jakarta.persistence.jdbc.url' value='jdbc:mysql://h/d'/><property name='jakarta.persistence.jdbc.user' value='bob'/>"
                + "<property name='jakarta.persistence.jdbc.password' value='hunter2'/></properties></persistence-unit></persistence>"));
        assertEquals("alice", userOf("persistence.xml", "<persistence><persistence-unit name='u'><properties>"
                + "<property name='javax.persistence.jdbc.url' value='jdbc:mysql://h/d'/><property name='javax.persistence.jdbc.user' value='alice'/></properties></persistence-unit></persistence>"));
        assertEquals("hib", userOf("hibernate.cfg.xml", "<hibernate-configuration><session-factory>"
                + "<property name=\"hibernate.connection.url\">jdbc:postgresql://h/d</property><property name=\"hibernate.connection.username\">hib</property>"
                + "<property name=\"hibernate.connection.password\">hunter2</property></session-factory></hibernate-configuration>"));
        assertEquals("sa", userOf("context.xml", "<Context><Resource name=\"jdbc/demo\" url=\"jdbc:h2:mem:demo\" username=\"sa\" password=\"x\"/></Context>"));
    }

    @Test void quarkusKeepsItsUserOneLevelAboveTheJdbcUrl() {
        assertEquals("quark", userOf("application.properties", "quarkus.datasource.jdbc.url=jdbc:postgresql://h/d\nquarkus.datasource.username=quark\nquarkus.datasource.password=x\n"));
    }

    @Test void aUserParameterInTheUrlIsTheFallback() {
        assertEquals("urluser", userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop?user=urluser&password=hunter2\n"));
        assertEquals("sa", userOf("application.properties", "spring.datasource.url=jdbc:h2:mem:shop;USER=sa;PASSWORD=hunter2\n"));
        assertEquals("filebob", userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop?user=urluser\nspring.datasource.username=filebob\n"),
                "the file's own setting wins");
    }

    @Test void placeholdersInTheUserNameResolveToTheirDefaultOrNothing() {
        assertEquals("app", userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop\nspring.datasource.username=${DB_USER:app}\n"));
        assertNull(userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop\nspring.datasource.username=${DB_USER}\n"));
    }

    @Test void noUserMeansNull() {
        assertNull(userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop\n"));
        assertNull(userOf("application.properties", "spring.datasource.url=jdbc:postgresql://pg/shop\nspring.mail.username=mailer\n"), "a user of some other setting is not this database's");
    }

    @Test void thePasswordIsNeverTakenForTheUser() {
        for (String text : new String[] {
                "spring.datasource.url=jdbc:postgresql://pg/shop\nspring.datasource.password=hunter2\n",
                "spring.datasource.url=jdbc:postgresql://pg/shop?password=hunter2\n",
                "spring.datasource.url=jdbc:postgresql://pg/shop\nspring.datasource.username=${spring.datasource.password}\nspring.datasource.password=hunter2\n"}) {
            var found = ProjectConfigScanner.findIn("application.properties", text);
            assertEquals(1, found.size());
            assertFalse(String.valueOf(found.get(0)).contains("hunter2"), text);
            assertNull(found.get(0).user(), text);
        }
    }

    @Test void theSameDatabaseInTwoPlacesKeepsTheUserOfWhicheverNamesOne() {
        var found = ProjectConfigScanner.findIn("application.yml", "spring:\n  datasource:\n    url: jdbc:mysql://h/d\n---\nspring:\n  datasource:\n    url: jdbc:mysql://h/d\n    username: bob\n");
        assertEquals(1, found.size());
        assertEquals("bob", found.get(0).user());
    }
}
