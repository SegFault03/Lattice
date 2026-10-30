package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.sql.Types;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class AlterTablePanelTest {
    private static final List<ColumnMetadata> COLUMNS = List.of(
            new ColumnMetadata("id", "BIGINT", Types.BIGINT, 64, 0, false, true, true, null),
            new ColumnMetadata("email", "VARCHAR", Types.VARCHAR, 180, 0, true, false, false, null),
            new ColumnMetadata("amount", "DECIMAL", Types.DECIMAL, 12, 3, false, false, false, "0.000"));
    private static AlterTablePanel panel() {
        return new AlterTablePanel(new ConnectionConfig(DatabaseType.HSQLDB, "Local development"), "PUBLIC", "customers", COLUMNS);
    }
    @Test void navigationAndFilteringKeepEveryDraftIncludingInvalidValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(); panel.columnList().setSelectedIndex(1);
            input(panel,"Length / precision").setText("255");
            input(panel,"Filter columns").setText("AMOUNT");
            assertEquals(1,panel.columnList().getModel().getSize()); assertEquals(255,panel.batch().changes().getFirst().size());
            input(panel,"Filter columns").setText(""); panel.columnList().setSelectedIndex(2);
            input(panel,"Length / precision").setText("oops"); assertFalse(panel.needsPreview());
            panel.columnList().setSelectedIndex(1); assertEquals("255",input(panel,"Length / precision").getText());
            assertFalse(panel.needsPreview(),"An invalid hidden draft must block the whole batch");
            panel.columnList().setSelectedIndex(2); assertEquals("oops",input(panel,"Length / precision").getText());
            input(panel,"Length / precision").setText("15, 4"); assertEquals(2,panel.batch().changes().size());
        });
    }
    @Test void renameAndPropertiesOnTheSameColumnAreCombinedWithMultipleAdditionsAndTableRename() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); panel.columnList().setSelectedIndex(1);
            input(panel,"Column name").setText("email_address"); input(panel,"Length / precision").setText("255");
            add(panel,"loyalty"); add(panel,"nickname");
            panel.columnList().setSelectedIndex(1); assertEquals("email_address",input(panel,"Column name").getText());
            assertEquals("255",input(panel,"Length / precision").getText());
            button(panel,"Rename table").doClick(0); input(panel,"New table name").setText("contacts");
            panel.columnList().setSelectedIndex(2); input(panel,"Length / precision").setText("15, 4");
            assertEquals(6,panel.batch().changes().size());
            assertEquals(2,panel.batch().changes().stream().filter(c->c.kind()==TableAlteration.Kind.ADD).count());
            assertEquals("contacts",panel.batch().targetTableName());
            button(panel,"Rename table").doClick(0); assertEquals("contacts",input(panel,"New table name").getText());
            panel.showPreview(panel.batch(),List.of("all six changes")); assertTrue(panel.applyButton().isEnabled());
            assertEquals("Apply all changes",panel.applyButton().getText());
        });
    }
    @Test void staleBatchPreviewsNeverEnableApplyAndEditingAnotherColumnInvalidatesSql() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); panel.columnList().setSelectedIndex(1); input(panel,"Length / precision").setText("255"); var old=panel.batch();
            panel.columnList().setSelectedIndex(2); input(panel,"Length / precision").setText("15, 3");
            panel.showPreview(old,List.of("stale")); assertNull(panel.prepared()); assertFalse(panel.applyButton().isEnabled());
            panel.showPreview(panel.batch(),List.of("current")); assertTrue(panel.applyButton().isEnabled());
            input(panel,"Length / precision").setText("oops"); assertNull(panel.prepared()); assertFalse(panel.applyButton().isEnabled());
            button(panel,"Revert column changes").doClick(0); assertEquals(1,panel.batch().changes().size());
        });
    }
    @Test void droppingCanBeRestoredAndRemovingANewColumnNeverStagesDatabaseDrop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); panel.columnList().setSelectedIndex(1); input(panel,"Length / precision").setText("255");
            button(panel,"Drop column").doClick(0); assertEquals(TableAlteration.Kind.DROP,panel.batch().changes().getFirst().kind());
            button(panel,"Restore column").doClick(0); assertEquals(255,panel.batch().changes().getFirst().size());
            add(panel,"loyalty"); button(panel,"Drop column").doClick(0);
            assertEquals(3,panel.columnList().getModel().getSize()); assertEquals(1,panel.batch().changes().size());
            panel.columnList().setSelectedIndex(1); button(panel,"Revert column changes").doClick(0); assertFalse(panel.dirty());
        });
    }
    @Test void duplicateNamesAcrossDraftsBlockApplyAndFreeingANameAllowsItsReuse() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); add(panel,"email"); assertFalse(panel.needsPreview());
            panel.columnList().setSelectedIndex(1); input(panel,"Column name").setText("email_address");
            assertTrue(panel.needsPreview()); assertEquals(2,panel.batch().changes().size());
            panel.columnList().setSelectedIndex(3); input(panel,"Column name").setText("id"); assertFalse(panel.needsPreview());
        });
    }
    @Test void identityAndBusyStatesKeepTheirCorrectEnabledState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); assertFalse(input(panel,"Default value").isEnabled()); assertFalse(input(panel,"Length / precision").isEnabled());
            panel.setBusy(true); assertFalse(panel.closeButton().isEnabled()); assertFalse(panel.columnList().isEnabled());
            panel.setBusy(false); assertTrue(panel.closeButton().isEnabled()); assertFalse(input(panel,"Default value").isEnabled());
            panel.columnList().setSelectedIndex(1); input(panel,"Length / precision").setText("255");
            panel.previewFailed(panel.batch(),"offline"); assertFalse(panel.applyButton().isEnabled()); assertTrue(panel.refreshButton().isEnabled());
        });
    }
    @Test void emptyTablesOfferAddAndTheWholeViewFitsAtMinimumWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=new AlterTablePanel(new ConnectionConfig(DatabaseType.HSQLDB,"test"),"PUBLIC","empty",List.of());
            button(panel,"Add column").doClick(0); assertFalse(panel.needsPreview());
            for(int width:new int[]{780,940,1200}) {
                panel.setSize(width,690); for(int i=0;i<4;i++)layout(panel);
                assertTrue(panel.applyButton().getWidth()>0);
                for(JTextField field:find(panel,JTextField.class))if((field.isShowing()||field.getWidth()>0)&&!(field.getParent() instanceof JComboBox)) {
                    assertTrue(field.getWidth()>0); assertEquals(com.segfault03.ideadb.ui.DatabaseInputs.height(field),field.getHeight());
                }
            }
        });
    }
    @Test void recoveryKeepsOnlyRemainingChangesAndNeverRepeatsSuccessfulAdditionsOrRenames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); panel.columnList().setSelectedIndex(1); input(panel,"Column name").setText("email_address"); input(panel,"Length / precision").setText("255");
            add(panel,"loyalty"); add(panel,"nickname");
            List<TableAlteration> completed=panel.batch().orderedChanges().subList(0,3);
            var actual=new ArrayList<>(COLUMNS);
            actual.set(1,new ColumnMetadata("email_address","VARCHAR",Types.VARCHAR,255,0,true,false,false,null));
            actual.add(new ColumnMetadata("loyalty","VARCHAR",Types.VARCHAR,255,0,true,false,false,null));
            panel.recoverBatch(actual,completed,"customers");
            assertEquals(5,panel.columnList().getModel().getSize()); assertEquals(1,panel.batch().changes().size());
            assertEquals("nickname",panel.batch().changes().getFirst().name()); assertEquals(TableAlteration.Kind.ADD,panel.batch().changes().getFirst().kind());
        });
    }
    @Test void failedRefreshBlocksApplyUntilReloadAndEquivalentFormattingIsNotDirty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); panel.columnList().setSelectedIndex(1); input(panel,"Length / precision").setText("0180");
            assertFalse(panel.dirty()); assertFalse(panel.needsPreview());
            input(panel,"Length / precision").setText("255"); panel.requireReload(); assertFalse(panel.needsPreview());
            panel.columnList().setSelectedIndex(2); assertFalse(panel.needsPreview());
            panel.replaceColumns(COLUMNS,null); assertFalse(panel.dirty());
        });
    }
    @Test void recoveryDoesNotDropANewColumnThatReusedTheNameOfACompletedDrop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel=panel(); panel.columnList().setSelectedIndex(1); button(panel,"Drop column").doClick(0);
            add(panel,"email"); input(panel,"Length / precision").setText("32"); add(panel,"later");
            var completed=panel.batch().orderedChanges().subList(0,2);
            var actual=new ArrayList<>(COLUMNS); actual.set(1,new ColumnMetadata("email","VARCHAR",Types.VARCHAR,32,0,true,false,false,null));
            panel.recoverBatch(actual,completed,"customers");
            assertEquals(4,panel.columnList().getModel().getSize()); assertEquals(1,panel.batch().changes().size());
            assertEquals("later",panel.batch().changes().getFirst().name());
        });
    }
    private static void add(AlterTablePanel panel,String name) { button(panel,"Add column").doClick(0); input(panel,"Column name").setText(name); }
    private static JTextField input(Container p,String name) { return find(p,JTextField.class).stream().filter(c->name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow(); }
    private static JButton button(Container p,String name) { return find(p,JButton.class).stream().filter(c->name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow(); }
    private static <T extends Component> List<T> find(Container p,Class<T> type) {
        List<T> found=new ArrayList<>(); for(Component c:p.getComponents()) { if(type.isInstance(c))found.add(type.cast(c));if(c instanceof Container nested)found.addAll(find(nested,type)); }return found;
    }
    private static void layout(Container p) {p.doLayout();for(Component c:p.getComponents())if(c instanceof Container nested)layout(nested);}
}
