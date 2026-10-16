package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class CreateTablePanelTest {
    private static CreateTablePanel panel() {return new CreateTablePanel(new ConnectionConfig(DatabaseType.HSQLDB,"Local development"),"PUBLIC");}
    @Test void defaultsHaveAValidProviderSpecificPreviewAndDetachedDefinitions() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(var engine:DatabaseType.values()) {
                var p=new CreateTablePanel(new ConnectionConfig(engine,"test"),"PUBLIC");assertNotNull(p.prepared(),p.problem());assertFalse(p.dirty());
                assertTrue(p.prepared().sql().startsWith("CREATE TABLE"));assertTrue(p.prepared().definition().columns().getFirst().autoIncrement());
                assertEquals(engine==DatabaseType.POSTGRESQL?"INTEGER":"INT",p.prepared().definition().columns().getFirst().type());
                p.prepared().definition().definitions().getFirst().setName("bad");assertEquals("id",p.prepared().definition().columns().getFirst().name());
            }
        });
    }
    @Test void invalidDraftsSurviveNavigationAndFilteringAndBlockTheEntireDefinition() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var p=panel();p.columnList().setSelectedIndex(1);input(p,"Length / precision").setText("oops");assertNull(p.prepared());
            input(p,"Filter columns").setText("created");assertEquals(1,p.columnList().getModel().getSize());assertEquals("oops",input(p,"Length / precision").getText());
            p.columnList().setSelectedIndex(0);assertEquals("created_at",input(p,"Column name").getText());assertNull(p.prepared());
            input(p,"Filter columns").setText("");p.columnList().setSelectedIndex(1);assertEquals("oops",input(p,"Length / precision").getText());
            input(p,"Length / precision").setText("120");assertNotNull(p.prepared());assertTrue(p.dirty());
        });
    }
    @Test void additionRemovalAndReorderingPreserveEveryOtherDefinitionAndUseActualColumnOrder() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var p=panel();button(p,"Add column").doClick(0);input(p,"Column name").setText("email");input(p,"Length / precision").setText("180");
            button(p,"Move column up").doClick(0);assertEquals(List.of("id","name","email","created_at"),names(p));
            input(p,"Filter columns").setText("email");button(p,"Move column up").doClick(0);assertEquals(List.of("id","email","name","created_at"),names(p));
            input(p,"Filter columns").setText("");p.columnList().setSelectedIndex(1);assertEquals("180",input(p,"Length / precision").getText());
            button(p,"Remove column").doClick(0);assertEquals(List.of("id","name","created_at"),names(p));assertFalse(p.dirty());
            assertTrue(p.prepared().sql().indexOf("\"name\"")<p.prepared().sql().indexOf("\"created_at\""));
        });
    }
    @Test void duplicateNamesAndNoColumnsDisableCreateAndCanBeCorrectedWithoutResetting() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var p=panel();p.columnList().setSelectedIndex(1);input(p,"Column name").setText("ID");assertNull(p.prepared());assertFalse(p.createButton().isEnabled());
            input(p,"Column name").setText("name");assertNotNull(p.prepared());
            for(int i=0;i<3;i++)button(p,"Remove column").doClick(0);assertNull(p.prepared());assertTrue(p.problem().contains("at least one"));
            button(p,"Add column").doClick(0);assertNotNull(p.prepared());assertTrue(p.createButton().isEnabled());
        });
    }
    @Test void keysIdentityAndBusyStateEnforceConstraintsWithoutErasingDefaults() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var p=panel();assertFalse(input(p,"Default value").isEnabled());assertFalse(box(p,"Allow NULL values").isEnabled());
            p.columnList().setSelectedIndex(1);input(p,"Default value").setText("TEXT:kept");box(p,"Primary key").doClick(0);
            assertFalse(box(p,"Allow NULL values").isSelected());assertFalse(box(p,"Allow NULL values").isEnabled());
            assertEquals(2,p.prepared().definition().columns().stream().filter(TableCreation.Column::primaryKey).count());
            p.setBusy(true);assertFalse(p.createButton().isEnabled());assertFalse(p.cancelButton().isEnabled());assertFalse(p.columnList().isEnabled());
            p.setBusy(false);assertTrue(p.createButton().isEnabled());assertEquals("TEXT:kept",input(p,"Default value").getText());
            assertFalse(box(p,"Allow NULL values").isEnabled());p.columnList().setSelectedIndex(0);assertFalse(input(p,"Default value").isEnabled());
        });
    }
    @Test void preparedSqlTracksNamesAndDecimalScaleAndSizingKeepsCompactInputs() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var p=panel();button(p,"Add column").doClick(0);input(p,"Column name").setText("amount");combo(p).setSelectedItem("DECIMAL");input(p,"Length / precision").setText("12, 3");
            p.tableNameField().setText("quoted\"table");assertTrue(p.prepared().sql().contains("\"quoted\"\"table\""));assertTrue(p.prepared().sql().contains("DECIMAL(12,3)"));
            var old=p.prepared();input(p,"Default value").setText("SQL:3.234");assertNotEquals(old,p.prepared());assertEquals("",old.definition().columns().getLast().defaultValue());
            for(int width:new int[]{860,1040,1200}){p.setSize(width,780);for(int i=0;i<5;i++)layout(p);assertTrue(p.createButton().getWidth()>0);
                for(JTextField field:find(p,JTextField.class))if(field.getWidth()>0)assertEquals(com.segfault03.ideadb.ui.DatabaseInputs.height(field),field.getHeight());}
        });
    }
    private static List<String> names(CreateTablePanel p) {return p.prepared().definition().columns().stream().map(TableCreation.Column::name).toList();}
    private static JTextField input(Container p,String name){return find(p,JTextField.class).stream().filter(c->name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();}
    private static JButton button(Container p,String name){return find(p,JButton.class).stream().filter(c->name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();}
    private static JCheckBox box(Container p,String name){return find(p,JCheckBox.class).stream().filter(c->name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();}
    private static JComboBox<?> combo(Container p){return find(p,JComboBox.class).getFirst();}
    private static <T extends Component> List<T> find(Container p,Class<T> type){List<T> found=new ArrayList<>();for(Component c:p.getComponents()){if(type.isInstance(c))found.add(type.cast(c));if(c instanceof Container child)found.addAll(find(child,type));}return found;}
    private static void layout(Container p){p.doLayout();for(Component c:p.getComponents())if(c instanceof Container child)layout(child);}
}
