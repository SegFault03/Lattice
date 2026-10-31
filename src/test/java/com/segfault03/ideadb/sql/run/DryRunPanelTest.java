package com.segfault03.ideadb.sql.run;

import com.intellij.testFramework.LightVirtualFile;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DryRunResult;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DryRunPanelTest {
    private static final String SQL = "update orders set status = :status where id = :id";

    @BeforeAll static void enableSvgIcons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }

    private static ConnectionConfig config() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "shop db");
        config.setDatabaseName("shop");
        return config;
    }

    private static SqlRunTarget target(ConnectionConfig config) {
        return new SqlRunTarget(SQL, SqlDialects.MYSQL, config, new LightVirtualFile("q.sql"), SqlParameters.of(SQL, SqlDialects.MYSQL, false));
    }

    private static Map<String, SqlParameterValue> values() {
        Map<String, SqlParameterValue> values = new LinkedHashMap<>();
        values.put(":status", new SqlParameterValue(SqlParameterValue.Type.TEXT, "shipped"));
        values.put(":id", new SqlParameterValue(SqlParameterValue.Type.NULL, ""));
        return values;
    }

    private static DryRunPanel running() {
        ConnectionConfig config = config();
        DryRunPanel panel = new DryRunPanel(null, config);
        panel.showRunning(target(config), "DML", values());
        return panel;
    }

    @Test void headerShowsConnectionSchemaKindAndValues() {
        DryRunPanel panel = running();
        String header = panel.headerText();
        assertTrue(header.contains("shop db"), header);
        assertTrue(header.contains("shop"), header);
        assertTrue(header.contains("DML"), header);
        assertTrue(header.contains(":status = shipped"), header);
        assertTrue(header.contains(":id = NULL"), header);
    }

    @Test void headerOmitsValuesWithoutParameters() {
        ConnectionConfig config = config();
        DryRunPanel panel = new DryRunPanel(null, config);
        panel.showRunning(new SqlRunTarget("select 1", SqlDialects.MYSQL, config, new LightVirtualFile("q.sql"),
                SqlParameters.of("select 1", SqlDialects.MYSQL, false)), "Query", Map.of());
        assertFalse(panel.headerText().contains(" = "), panel.headerText());
    }

    @Test void planShownMonospace() {
        DryRunPanel panel = running();
        panel.show(new DryRunResult.Plan("-> Table scan on orders", false, 4));
        assertFalse(panel.isRunning());
        assertTrue(panel.bodyText().contains("-> Table scan on orders"));
        assertTrue(panel.bodyMonospaced());
        assertEquals("-> Table scan on orders", panel.copyText());
        assertFalse(panel.bodyText().contains("bound parameters"));
    }

    @Test void literalFallbackNoteShown() {
        DryRunPanel panel = running();
        panel.show(new DryRunResult.Plan("plan", true, 4));
        assertTrue(panel.bodyText().contains("Shown with the values written into the SQL; this database does not accept bound parameters here."),
                panel.bodyText());
    }

    @Test void rolledBackShowsRowsAndBadge() {
        DryRunPanel panel = running();
        panel.show(new DryRunResult.RolledBack(3, List.of("Data truncated"), 12));
        String body = panel.bodyText();
        assertTrue(body.contains("3 row(s) affected"), body);
        assertTrue(body.contains("12 ms"), body);
        assertTrue(body.contains("Data truncated"), body);
        assertTrue(body.contains("Rolled back"), body);
        assertEquals("", panel.bannerText());
    }

    @Test void outcomeUnknownShowsBanner() {
        DryRunPanel panel = running();
        panel.show(new DryRunResult.OutcomeUnknown("shop db", "connection reset"));
        String banner = panel.bannerText();
        assertEquals("Outcome unknown: the rollback on \"shop db\" could not be confirmed", banner);
        assertTrue(panel.bodyText().contains("connection reset"), panel.bodyText());
        assertTrue(panel.bodyText().contains("Lattice closed its connection, so the database should discard the changes; check the data if it matters."),
                panel.bodyText());
        assertFalse(panel.bodyText().contains("uncommitted"));
        assertFalse(panel.bodyText().contains("Rolled back"));
    }

    @Test void failedShowsMessage() {
        DryRunPanel panel = running();
        panel.show(new DryRunResult.Failed("Table \"X\" not found"));
        assertTrue(panel.bodyText().contains("Table \"X\" not found"));
        assertEquals("", panel.bannerText());
    }

    @Test void cancelledCopy() {
        DryRunPanel panel = running();
        panel.show(new DryRunResult.Cancelled(true));
        assertTrue(panel.bodyText().contains("Cancelled; any changes were rolled back"), panel.bodyText());
        panel.show(new DryRunResult.Cancelled(false));
        assertEquals("Cancelled", panel.bodyText());
    }

    @Test void runningShowsKindAndBlocksSecondRun() {
        DryRunPanel panel = running();
        assertTrue(panel.isRunning());
        assertTrue(panel.bodyText().contains("Running"), panel.bodyText());
        assertTrue(panel.bodyText().contains("DML"), panel.bodyText());
    }

    @Test void rerunDisabledWhileRunning() {
        DryRunPanel panel = running();
        assertFalse(panel.rerunEnabled());
        panel.show(new DryRunResult.Cancelled(false));
        assertTrue(panel.rerunEnabled());
        panel.showRunning(target(config()), "DML", values());
        assertFalse(panel.rerunEnabled());
    }

    @Test void rerunInvokesHandlerOnlyWhenIdle() {
        DryRunPanel panel = running();
        AtomicInteger calls = new AtomicInteger();
        panel.setRerunHandler(calls::incrementAndGet);
        panel.clickRerun();
        assertEquals(0, calls.get());
        panel.show(new DryRunResult.Failed("x"));
        panel.clickRerun();
        assertEquals(1, calls.get());
    }
}
