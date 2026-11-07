package com.segfault03.ideadb.sql.card;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DryRunResult;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.doc.SqlDocRenderer;
import com.segfault03.ideadb.sql.doc.SqlTarget;
import com.segfault03.ideadb.sql.run.DryRunPanel;
import com.segfault03.ideadb.sql.run.SqlParameterForm;
import com.segfault03.ideadb.sql.run.SqlParameterValue;
import com.segfault03.ideadb.sql.run.SqlParameters;
import com.segfault03.ideadb.sql.run.SqlRunTarget;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.validate.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renders the What's New guide images: the real editor, schema and panels, painted headlessly. Popups cannot open without a
 * display, so they are composed around the editor from what the features return. Set {@code LATTICE_WHATS_NEW_OUTPUT} to
 * write the guide images, and {@code LATTICE_README_OUTPUT} to write the README's SQL editor images ({@code sql-editor.png}
 * and {@code dry-run.png}, the completion and dry-run scenes), in the Islands Light theme, which needs an IDE that has it;
 * otherwise they are only rendered, which keeps the scenes working on the compile baseline.
 */
public class WhatsNewRendersJavaFixtureTest extends H2SqlFixtureTestCase {
    private static final String THEME = "Islands Light", EDITOR_SCHEME = "Light", EDITOR_SCHEME_FILE = "themes/expUI/expUI_lightScheme.xml";
    private static final int WIDTH = 1040, SCALE = 2, TAB = 34;

    /** True when the images are being written, which needs the Islands Light theme and real icons. */
    private static boolean writing() {
        return System.getenv("LATTICE_WHATS_NEW_OUTPUT") != null || System.getenv("LATTICE_README_OUTPUT") != null;
    }

    private static Path outputDirectory(String variable) throws IOException {
        String output = System.getenv(variable);
        return output == null ? null : Files.createDirectories(Path.of(output));
    }

    static {
        // Fixtures paint platform icons as empty placeholders unless real ones are active before AllIcons is first read.
        if (writing()) com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
    }

    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE CUSTOMERS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(80) NOT NULL, EMAIL VARCHAR(120))",
                "CREATE TABLE ORDERS (ID INTEGER NOT NULL PRIMARY KEY, CUSTOMER_ID INTEGER NOT NULL, STATUS VARCHAR(20), TOTAL DECIMAL(10,2), "
                        + "PLACED_AT TIMESTAMP, FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMERS (ID))",
                "CREATE TABLE ORDER_ITEMS (ORDER_ID INTEGER NOT NULL, SKU VARCHAR(40) NOT NULL, QUANTITY INTEGER, FOREIGN KEY (ORDER_ID) REFERENCES ORDERS (ID))",
                "CREATE VIEW OPEN_ORDERS AS SELECT ID, TOTAL FROM ORDERS WHERE STATUS = 'open'",
                "COMMENT ON TABLE CUSTOMERS IS 'People and companies who place orders'",
                "COMMENT ON COLUMN ORDERS.STATUS IS 'open, shipped or cancelled'"};
    }

    /** The unified IDE's Ultimate startup activities cannot be created in a fixture; for a render their logged errors are noise. */
    private static <T extends Throwable> void tolerant(com.intellij.util.ThrowableRunnable<T> work) throws T {
        if (!writing()) {
            work.run();
            return;
        }
        com.intellij.testFramework.LoggedErrorProcessor.executeWith(new com.intellij.testFramework.LoggedErrorProcessor() {
            @Override public @org.jetbrains.annotations.NotNull java.util.Set<Action> processError(@org.jetbrains.annotations.NotNull String category,
                    @org.jetbrains.annotations.NotNull String message, String @org.jetbrains.annotations.NotNull [] details, Throwable throwable) {
                return java.util.EnumSet.of(Action.STDERR);
            }
        }, work);
    }

    @Override protected void setUp() throws Exception { tolerant(super::setUp); }

    public void testRenderGuides() throws Exception { tolerant(this::renderGuides); }

    private void renderGuides() throws Exception {
        Path directory = outputDirectory("LATTICE_WHATS_NEW_OUTPUT");
        Path readme = outputDirectory("LATTICE_README_OUTPUT");
        if (writing()) applyTheme();
        // The unified IDE bundles the Database plugin, and cards and the run actions only exist where it is disabled.
        com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin.setActiveForTests(false, getTestRootDisposable());
        com.segfault03.ideadb.sql.settings.SqlFileTypeClaim.apply(true);
        myFixture.enableInspections(LatticeSqlSyntaxInspection.class, LatticeSqlUnknownTableInspection.class, LatticeSqlUnknownColumnInspection.class,
                LatticeSqlAmbiguousColumnInspection.class, LatticeSqlAliasInspection.class);
        loadSchema();
        BufferedImage schema = schemaScene();
        write(directory, "guide-schema.png", schema);
        write(readme, "sql-editor.png", schema);
        write(directory, "guide-strings.png", stringsScene());
        BufferedImage dryRun = dryRunScene();
        write(directory, "guide-dry-run.png", dryRun);
        write(readme, "dry-run.png", dryRun);
        write(directory, "guide-validation.png", validationScene());
    }

    /** The fixture's LafManager is a stub with no themes, so the theme is installed the way the real manager does it. */
    private static void applyTheme() throws Exception {
        var theme = com.intellij.ide.ui.laf.UiThemeProviderListManager.Companion.getInstance().findThemeById(THEME);
        assertNotNull(THEME + " is not in this IDE", theme);
        com.intellij.openapi.util.IconLoader.activate();
        UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.IntelliJLaf());
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        theme.installTheme(defaults);
        // The real manager sets the UI font on every component; the JBR bundles it.
        Font inter = new Font("Inter", Font.PLAIN, 13);
        assertEquals("Inter", inter.getFamily());
        for (Object key : new ArrayList<>(defaults.keySet())) {
            if (key instanceof String name && name.endsWith(".font") && defaults.get(key) instanceof Font font) {
                defaults.put(key, new javax.swing.plaf.FontUIResource(inter.deriveFont(font.getStyle())));
            }
        }
        // The fixture registers only Classic Light and Darcula; the theme's scheme is read from the file the IDE bundles.
        var scheme = new com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl(EditorColorsManager.getInstance().getGlobalScheme());
        try (var input = com.intellij.ide.ui.laf.UiThemeProviderListManager.class.getClassLoader().getResourceAsStream(EDITOR_SCHEME_FILE)) {
            assertNotNull(EDITOR_SCHEME_FILE + " is not in this IDE", input);
            scheme.readExternal(com.intellij.openapi.util.JDOMUtil.load(input));
        }
        assertEquals(EDITOR_SCHEME, scheme.getName());
        EditorColorsManager.getInstance().setGlobalScheme(scheme);
        System.out.println("Rendering on " + ApplicationInfo.getInstance().getBuild().asString() + " in " + theme.getName() + " with the " + scheme.getName() + " editor scheme");
    }

    private static void write(Path directory, String name, BufferedImage image) throws IOException {
        assertTrue(name, image.getWidth() >= 1200 && image.getHeight() >= 760);
        if (directory != null) ImageIO.write(image, "png", directory.resolve(name).toFile());
    }

    /** Completion as you type, and a table's card from hovering it. */
    private BufferedImage schemaScene() {
        myFixture.configureByText("orders.sql", "-- Open orders and who placed them\nselect o.id, o.total, o.status, o.placed_at\n  from orders o\n  join <caret>");
        EditorEx editor = prepared();
        myFixture.doHighlighting();
        Point caret = editor.offsetToXY(editor.getCaretModel().getOffset());
        List<String[]> rows = new ArrayList<>();
        List<Icon> icons = new ArrayList<>();
        for (LookupElement item : myFixture.completeBasic()) {
            if (rows.size() == 6) break;
            var presentation = new LookupElementPresentation();
            item.renderElement(presentation);
            rows.add(new String[] {item.getLookupString(), presentation.getTypeText() == null ? "" : presentation.getTypeText()});
            icons.add(presentation.getIcon());
        }
        String card = SqlDocRenderer.render(new SqlTarget.Table("PUBLIC", "CUSTOMERS"), SqlSchemaService.getInstance().schema(h2));
        return scene("orders.sql", AllIcons.FileTypes.Text, editor, 420, 420 - TAB, g -> {
            completion(g, editor, rows, icons, left(editor) + caret.x, TAB + caret.y + editor.getLineHeight() + 2);
            popup(g, hoverCard(card), WIDTH - 452, TAB + 12, 440, hoverHeight(card));
        });
    }

    /** SQL cards in Java code, with the toolbar over the card under the mouse. */
    private BufferedImage stringsScene() {
        String source = """
                import java.sql.*;

                class OrderRepository {
                    int ship(Connection connection, int order) throws SQLException {

                        String sql = \"""
                                update orders
                                   set status = 'shipped'
                                 where id = ?
                                \""";
                        var statement = connection.prepareStatement(sql);
                        statement.setInt(1, order);
                        return statement.executeUpdate();
                    }

                    ResultSet open(Connection connection) throws SQLException {
                        return connection.prepareStatement("select id, total from orders where status = 'open'").executeQuery();
                    }
                }
                """;
        myFixture.configureByText("OrderRepository.java", source);
        EditorEx editor = prepared();
        editor.getSettings().setLineMarkerAreaShown(false);
        // The fixture has no JDK, so ordinary code would be colored as unresolved; a real project resolves it.
        editor.getColorsScheme().setAttributes(com.intellij.openapi.editor.colors.CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES, new com.intellij.openapi.editor.markup.TextAttributes());
        myFixture.doHighlighting();
        List<Rectangle> cards = new ArrayList<>();
        for (var highlighter : SqlCardPass.highlighters(editor)) cards.add(SqlCardRenderer.boundsFor(editor, highlighter));
        SqlCard update = ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile())).get(0);
        List<Icon> buttons = new ArrayList<>();
        for (String id : List.of("com.segfault03.lattice.sql.run.rollback", "com.segfault03.lattice.sql.run.sendToConsole", SqlCardActionsJavaFixtureTest.COPY)) {
            AnAction action = ActionManager.getInstance().getAction(id);
            AnActionEvent event = SqlCardActionsJavaFixtureTest.event(myFixture, action, update);
            action.update(event);
            buttons.add(event.getPresentation().getIcon());
        }
        buttons.add(AllIcons.Actions.More);
        return scene("OrderRepository.java", AllIcons.FileTypes.Java, editor, 470, 470 - TAB, g -> {
            Rectangle card = cards.get(0);
            int width = buttons.size() * 30 + 8;
            // Where the real toolbar goes: above the card's top-right corner.
            int x = left(editor) + card.x + card.width - width, y = TAB + card.y - 34;
            box(g, x, y, width, 32);
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i) != null) buttons.get(i).paintIcon(null, g, x + 11 + i * 30, y + 8);
        });
    }

    /** The parameter card over a statement, and the rolled-back result in the Dry Run tab. */
    private BufferedImage dryRunScene() {
        String text = "-- Which orders would this touch?\n\n\nupdate orders\n   set status = :status\n where customer_id = :customer;\n\n\n\n";
        myFixture.configureByText("orders.sql", text);
        EditorEx editor = prepared();
        myFixture.doHighlighting();
        String sql = "update orders\n   set status = :status\n where customer_id = :customer";
        // A display name and database for the panel's header; nothing connects to this configuration.
        ConnectionConfig shop = new ConnectionConfig(DatabaseType.H2, "shop");
        shop.setDatabaseName("SHOP");
        var parameters = SqlParameters.of(sql, SqlDialects.H2, false);
        Map<String, SqlParameterValue> values = new LinkedHashMap<>();
        values.put(":status", new SqlParameterValue(SqlParameterValue.Type.TEXT, "'shipped'"));
        values.put(":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "42"));
        h2.setName("shop");
        var form = new SqlParameterForm(sql, parameters, DatabaseType.H2, h2, values, "Run and Roll Back");
        var panel = new DryRunPanel(null, shop);
        panel.showRunning(new SqlRunTarget(sql, SqlDialects.H2, shop, new LightVirtualFile("orders.sql"), parameters), "DML", values);
        panel.show(new DryRunResult.RolledBack(3, List.of(), 18));
        Point statement = editor.offsetToXY(text.indexOf("update"));
        int resultTop = 226, height = 500;
        return scene("orders.sql", AllIcons.FileTypes.Text, editor, height, resultTop - TAB, g -> {
            Dimension size = form.getPreferredSize();
            int formWidth = Math.max(size.width, 560), formHeight = heightFor(form, formWidth);
            popup(g, form, left(editor) + statement.x - 8, TAB + statement.y - 34, formWidth, formHeight);
            g.setColor(UIUtil.getPanelBackground());
            g.fillRect(0, resultTop, WIDTH, TAB);
            g.setColor(JBColor.border());
            g.drawLine(0, resultTop, WIDTH, resultTop);
            g.setColor(UIUtil.getLabelForeground());
            g.setFont(UIUtil.getLabelFont());
            g.drawString("Dry Run: shop", 14, resultTop + 22);
            place(g, panel, 0, resultTop + TAB, WIDTH, height - resultTop - TAB);
        });
    }

    /** Findings underlined from the cached schema, with the message and fixes of the last. */
    private BufferedImage validationScene() {
        String text = "selct * from customers;\n\nselect o.totl, o.status\n  from orders o;\n\nselect id, total\n  from orderz\n";
        myFixture.configureByText("report.sql", text);
        EditorEx editor = prepared();
        var findings = myFixture.doHighlighting().stream().filter(info -> info.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) >= 0).toList();
        assertFalse("the scene needs findings", findings.isEmpty());
        var last = findings.stream().filter(info -> info.getStartOffset() == text.indexOf("orderz")).findFirst().orElseThrow();
        Point at = editor.offsetToXY(last.getStartOffset());
        // Errors reach the editor's own painting here; warnings do not, so they are underlined from the same findings.
        List<Rectangle> warnings = new ArrayList<>();
        for (var finding : findings) {
            if (finding.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0) continue;
            Point from = editor.offsetToXY(finding.getStartOffset()), to = editor.offsetToXY(finding.getEndOffset());
            warnings.add(new Rectangle(from.x, from.y, to.x - from.x, editor.getLineHeight()));
        }
        var warning = editor.getColorsScheme().getAttributes(com.intellij.openapi.editor.colors.CodeInsightColors.WARNINGS_ATTRIBUTES);
        Color wave = warning != null && warning.getEffectColor() != null ? warning.getEffectColor() : new Color(0xC29E4A);
        return scene("report.sql", AllIcons.FileTypes.Text, editor, 400, 400 - TAB, g -> {
            g.setColor(wave);
            for (Rectangle range : warnings) {
                int baseline = TAB + range.y + range.height - 2;
                for (int wx = left(editor) + range.x, up = 0; wx < left(editor) + range.x + range.width; wx += 2, up ^= 1) {
                    g.drawLine(wx, baseline - up, wx + 2, baseline - (up ^ 1));
                }
            }
            int x = left(editor) + at.x, y = TAB + at.y + editor.getLineHeight() + 4;
            String message = String.valueOf(last.getDescription());
            g.setFont(UIUtil.getLabelFont());
            box(g, x, y, Math.max(g.getFontMetrics().stringWidth(message) + 28, 330), 62);
            g.setColor(UIUtil.getLabelForeground());
            g.drawString(message, x + 14, y + 24);
            g.setColor(JBUI.CurrentTheme.Link.Foreground.ENABLED);
            g.drawString("Alt+Enter for the fix or to suppress it", x + 14, y + 46);
        });
    }

    /** The current editor scheme without the caret row, so a scene shows only what it is about. */
    private EditorEx prepared() {
        EditorEx editor = (EditorEx) myFixture.getEditor();
        EditorColorsScheme scheme = (EditorColorsScheme) EditorColorsManager.getInstance().getGlobalScheme().clone();
        scheme.setColor(EditorColors.CARET_ROW_COLOR, null);
        editor.setColorsScheme(scheme);
        editor.getSettings().setLineNumbersShown(true);
        editor.getSettings().setRightMarginShown(false);
        editor.getScrollPane().setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        editor.getScrollPane().setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        layout(editor.getComponent(), WIDTH, 300);
        return editor;
    }

    /** Where the editor's text starts in the scene: offsetToXY counts from the text, right of the gutter. */
    private static int left(EditorEx editor) { return editor.getGutterComponentEx().getWidth(); }

    private static BufferedImage scene(String file, Icon icon, EditorEx editor, int height, int editorHeight, Consumer<Graphics2D> over) {
        BufferedImage image = new BufferedImage(WIDTH * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(editor.getColorsScheme().getDefaultBackground());
        g.fillRect(0, 0, WIDTH, height);
        g.setColor(UIUtil.getPanelBackground());
        g.fillRect(0, 0, WIDTH, TAB);
        g.setColor(JBColor.border());
        g.drawLine(0, TAB - 1, WIDTH, TAB - 1);
        icon.paintIcon(null, g, 14, 9);
        g.setColor(UIUtil.getLabelForeground());
        g.setFont(UIUtil.getLabelFont());
        g.drawString(file, 36, 22);
        g.setColor(JBUI.CurrentTheme.Link.Foreground.ENABLED);
        g.fillRect(0, TAB - 3, 36 + g.getFontMetrics().stringWidth(file) + 16, 3);
        layout(editor.getComponent(), WIDTH, editorHeight);
        Graphics2D area = (Graphics2D) g.create(0, TAB, WIDTH, editorHeight);
        editor.getComponent().paint(area);
        area.dispose();
        over.accept(g);
        g.dispose();
        return image;
    }

    private static void completion(Graphics2D g, EditorEx editor, List<String[]> rows, List<Icon> icons, int x, int y) {
        g.setFont(editor.getColorsScheme().getFont(EditorFontType.PLAIN));
        FontMetrics metrics = g.getFontMetrics();
        int width = 0;
        for (String[] row : rows) width = Math.max(width, metrics.stringWidth(row[0]) + metrics.stringWidth(row[1]));
        width = Math.min(width + 76, 480);
        box(g, x, y, width, rows.size() * 24 + 4);
        for (int i = 0; i < rows.size(); i++) {
            int top = y + 2 + i * 24;
            if (i == 0) {
                g.setColor(UIUtil.getListSelectionBackground(true));
                g.fillRect(x + 2, top, width - 4, 24);
            }
            if (icons.get(i) != null) icons.get(i).paintIcon(null, g, x + 8, top + 4);
            g.setColor(UIUtil.getLabelForeground());
            g.drawString(rows.get(i)[0], x + 32, top + 17);
            g.setColor(UIUtil.getContextHelpForeground());
            g.drawString(rows.get(i)[1], x + width - 10 - metrics.stringWidth(rows.get(i)[1]), top + 17);
        }
    }

    private static JEditorPane hoverCard(String card) {
        Font font = UIUtil.getLabelFont();
        String style = "<style>body, td, th, span, b, a, div, p { font-family: '" + font.getFamily() + "'; font-size: " + font.getSize() + "pt; }</style>";
        JEditorPane pane = new JEditorPane("text/html", "<html><head>" + style + "</head><body style='margin:6px'>" + card + "</body></html>");
        pane.setEditable(false);
        pane.setBackground(JBColor.background());
        return pane;
    }

    private static int hoverHeight(String card) { return heightFor(hoverCard(card), 440); }

    private static int heightFor(JComponent component, int width) {
        component.setSize(width, Short.MAX_VALUE);
        return component.getPreferredSize().height;
    }

    private static void popup(Graphics2D g, JComponent component, int x, int y, int width, int height) {
        box(g, x, y, width, height);
        place(g, component, x, y, width, height);
    }

    private static void place(Graphics2D g, JComponent component, int x, int y, int width, int height) {
        layout(component, width, height);
        Graphics2D area = (Graphics2D) g.create(x, y, width, height);
        // Components embedded in a text pane take their bounds during its first paint, so the second one shows them.
        BufferedImage scratch = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D first = scratch.createGraphics();
        component.printAll(first);
        first.dispose();
        layoutTree(component);
        component.printAll(area);
        printEmbedded(area, component, component);
        area.dispose();
    }

    /** A text pane holds embedded components in a plain container, whose paint does nothing unless it is showing. */
    private static void printEmbedded(Graphics2D g, Component root, Component component) {
        if (!(component instanceof Container container)) return;
        for (Component child : container.getComponents()) {
            if (component instanceof javax.swing.text.JTextComponent && !(child instanceof JComponent) && child instanceof Container holder && holder.getComponentCount() > 0) {
                Rectangle at = SwingUtilities.convertRectangle(container, child.getBounds(), root);
                Component embedded = holder.getComponent(0);
                layout(embedded, at.width, at.height);
                Graphics2D area = (Graphics2D) g.create(at.x, at.y, at.width, at.height);
                embedded.printAll(area);
                area.dispose();
            } else {
                printEmbedded(g, root, child);
            }
        }
    }

    private static void box(Graphics2D g, int x, int y, int width, int height) {
        for (int i = 6; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 7));
            g.fillRoundRect(x - i, y - i + 2, width + 2 * i, height + 2 * i, 12, 12);
        }
        g.setColor(JBColor.background());
        g.fillRoundRect(x, y, width, height, 8, 8);
        g.setColor(JBColor.border());
        g.drawRoundRect(x, y, width, height, 8, 8);
    }

    private static void layout(Component component, int width, int height) {
        component.setSize(width, height);
        for (int pass = 0; pass < 4; pass++) layoutTree(component);
    }

    private static void layoutTree(Component component) {
        component.doLayout();
        if (component instanceof Container container) for (Component child : container.getComponents()) layoutTree(child);
    }
}
