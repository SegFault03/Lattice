# Lattice 💠

**Lattice** is a fast, lightweight, and unrestricted in-app database management plugin for **IntelliJ IDEA** (Community and Ultimate editions). Connect to, explore, query, design, and modify databases directly inside your IDE without requiring paid Ultimate licenses or external database clients.

> ⚡ **Entirely vibe-coded with Google Gemini 3.8 Flash.**

<p align="center">
  <img src="src/main/resources/icons/lattice_large.svg" alt="Lattice Logo" width="96" height="96"/>
</p>

---

## 🌟 Key Features

### 1. Multi-Engine Support
- **MySQL (5.5+, with a compatible JDBC driver)**:
  - Connect via Standard Host / Port / User / Password / Database
  - Custom JDBC connection strings and SSL parameters
  - Bundled with `mysql-connector-j-9.0.0.jar`
- **HSQLDB (HyperSQL)**:
  - **In-Memory (`mem:`)**: Ultra-fast zero-configuration ephemeral database for testing and prototyping
  - **Embedded File (`file:`)**: Local persistent database file storage with file browser picker
  - **Remote Server (`hsql://`)**: Network connection to standalone HSQLDB instances
  - Bundled with `hsqldb-2.7.3.jar`
- **Driver selection**: use bundled drivers, choose/download a release from Maven Central, or browse to a local JDBC JAR. HSQLDB Java 8 variants are identified by `-jdk8`. Driver choices are saved per connection.
- **Server version detection**: Test Connection reports the actual database and JDBC driver versions after connecting.

The plugin targets **IntelliJ IDEA 2025.1 and later**, with Java 21 bytecode. Java 8 compatibility applies to database servers and JDBC drivers, not the IDE plugin runtime. See [the tested compatibility matrix](review/compatibility-2026-10-04.md) for exact versions and limitations.

---

### 2. Database Explorer (Tool Window)
- **Docked Tool Window**: Available on the right stripe labeled **Lattice** with a custom vector SVG icon designed for JetBrains New UI.
- **Hierarchical Tree View**:
  - **Data Sources**: Connected status indicators with distinct visual badges.
  - **Catalogs & Databases**: Schemas and database namespaces.
  - **Tables & Views**: Real-time table itemization.
  - **Columns**: Detailed column type information, nullability tags, auto-increment badges, and **golden key icons for Primary Keys (`[PK]`)**.
- **Context Menus**: Right-click actions on data sources, databases, and tables (Open Console, View Data, Create Table, Alter Table, Truncate Table, Drop Table, Export).

---

### 3. Interactive Data Grid & Inline Editor
- **Table Data Viewer**: Double-click any table in the explorer or open it in full editor tabs.
- **Inline Cell Editing & Highlight**: Double-click cells to modify values directly in-place. Modified cells are highlighted in soft blue so uncommitted edits are instantly visible.
- **Type Checking & Protection**:
  - Early type checking prevents invalid data entry.
  - Informative error indicators and commit safety checks.
  - Automatic detection and editing protection for auto-generated / auto-increment primary keys.
- **Periodic Auto-Refresh**: Configurable timer intervals (Off, 10s, 15s, 20s, 30s, 60s) for live monitoring of high-throughput tables.
- **Batch Commits & Revert**: Review and commit all pending updates and insertions in one click or discard edits cleanly.
- **Add & Delete Rows**: Add blank records or delete selected rows using primary key resolution.
- **WHERE Clause Filter Bar**: Filter live table records using custom SQL conditions (e.g. `price > 100 AND status = 'ACTIVE'`).
- **Pagination**: Configurable page sizes (50, 100, 250, 500, 1000) with compact page controls.
- **Export Data**: Export table contents to **CSV**, **JSON**, or **SQL INSERT** scripts.

---

### 4. Interactive SQL Console
- **Scratchpad Editor**: Code editor with monospaced font, line numbers, and indentation.
- **Shortcut Execution**: Press `Ctrl+Enter` (or `Cmd+Enter` on macOS) or click **Run**.
- **Asynchronous Execution**: Queries execute on background threads without freezing the IDE.
- **Dual Results Panel**:
  - **Results Tab**: Interactive tabular grid for `SELECT` queries with row counts.
  - **Messages Tab**: Execution time (ms), affected rows count for `INSERT`/`UPDATE`/`DELETE`, and formatted SQL errors.
- **Query History**: Automatically preserves recent queries for quick recall.
- **SQL Templates**: Quick snippet insertions for `SELECT * LIMIT 50`, `COUNT(*)`, `CREATE TABLE`, etc.

---

### 5. Visual DDL Wizards (Create & Modify)
- **Create Database / Schema**: Visual dialog to create new database namespaces.
- **Visual Table Designer (`Create Table...`)**:
  - Dynamic column editor: specify Column Name, Type, Size, Nullable, Primary Key, Auto Increment, and Default Value.
  - Add, remove, and reorder columns (`Move Up`, `Move Down`).
  - **Live DDL Preview**: Dynamically updates the exact `CREATE TABLE` SQL dialect in real time.
- **Modify Table (`Alter Table...`)**:
  - **Single-row scrollable tab interface** with optimized default dialog width.
  - Add Column with type and constraint specifications.
  - Modify column data types, sizes, nullability, and default values.
  - Rename columns seamlessly across MySQL and HSQLDB.
  - Drop columns with safe confirmation prompts.
  - Rename table in place.
- **Table Maintenance**: Quick actions to **Truncate Table** or **Drop Table** with confirmation dialogs.

---

## 📁 Repository Structure

```
Lattice/
├── src/
│   ├── main/
│   │   ├── java/com/vibe/ideadb/
│   │   │   ├── dialog/              # Connection, CreateTable, AlterTable, CreateDatabase dialogs
│   │   │   │   ├── AlterTableDialog.java
│   │   │   │   ├── ConnectionDialog.java
│   │   │   │   ├── CreateDatabaseDialog.java
│   │   │   │   └── CreateTableDialog.java
│   │   │   ├── editor/              # Virtual files, editor providers, welcome panel
│   │   │   │   ├── DatabaseEditorManager.java
│   │   │   │   ├── DatabaseFileEditor.java
│   │   │   │   ├── DatabaseFileEditorProvider.java
│   │   │   │   ├── DatabaseFileTypes.java
│   │   │   │   ├── DatabaseVirtualFile.java
│   │   │   │   ├── SqlConsoleVirtualFile.java
│   │   │   │   ├── TableDataVirtualFile.java
│   │   │   │   ├── WelcomePanel.java
│   │   │   │   └── WelcomeVirtualFile.java
│   │   │   ├── model/               # Data structures, configs, metadata, query results
│   │   │   │   ├── ColumnDefinition.java
│   │   │   │   ├── ColumnMetadata.java
│   │   │   │   ├── ConnectionConfig.java
│   │   │   │   ├── ConnectionTestResult.java
│   │   │   │   ├── DatabaseType.java
│   │   │   │   ├── HsqlMode.java
│   │   │   │   ├── QueryResult.java
│   │   │   │   └── TableMetadata.java
│   │   │   ├── service/             # JDBC drivers, connections, metadata, DDL & CRUD operations
│   │   │   │   ├── DataService.java
│   │   │   │   ├── DatabaseConnectionManager.java
│   │   │   │   ├── DdlService.java
│   │   │   │   ├── DriverRegistry.java
│   │   │   │   ├── DriverShim.java
│   │   │   │   ├── ExportService.java
│   │   │   │   └── MetadataService.java
│   │   │   ├── state/               # Persistent state component (saves connections across IDE sessions)
│   │   │   │   └── DatabaseSettingsState.java
│   │   │   └── ui/                  # ToolWindow factory, tree explorer, data grid, SQL console
│   │   │       ├── DatabaseMainPanel.java
│   │   │       ├── DatabaseToolWindowFactory.java
│   │   │       ├── DatabaseTreeCellRenderer.java
│   │   │       ├── Icons.java
│   │   │       ├── OpenDatabaseManagerAction.java
│   │   │       ├── SqlQueryConsolePanel.java
│   │   │       ├── TableDataEditorPanel.java
│   │   │       └── TreeNodeData.java
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml             # IntelliJ plugin descriptor & extension points
│   │       │   ├── pluginIcon.svg         # 40x40 vector Marketplace & Plugins dialog icon
│   │       │   └── pluginIcon_dark.svg    # 40x40 vector Dark theme Marketplace icon
│   │       └── icons/                     # Vector SVG icons
│   │           ├── lattice.svg            # 16x16 side-panel tool window icon (Light)
│   │           ├── lattice_dark.svg       # 16x16 side-panel tool window icon (Dark)
│   │           ├── lattice_large.svg      # 48x48 Welcome panel icon (Light)
│   │           ├── lattice_large_dark.svg # 48x48 Welcome panel icon (Dark)
│   │           ├── column.svg
│   │           ├── console.svg
│   │           ├── database.svg
│   │           ├── key.svg
│   │           └── table.svg
│   └── test/java/com/vibe/ideadb/
│       └── PluginIntegrationTest.java       # Comprehensive integration test suite
├── lib/                                   # Bundled JDBC drivers
│   ├── hsqldb-2.7.3.jar
│   └── mysql-connector-j-9.0.0.jar
├── build-plugin.ps1                       # Windows PowerShell compiler & packager (generates .zip)
├── build-plugin.sh                        # Linux & macOS Bash compiler & packager (generates .zip)
├── test-plugin.sh                         # Linux & macOS Bash integration test runner
├── build.gradle.kts                       # Standard Gradle IntelliJ Platform configuration
├── settings.gradle.kts
└── .gitignore
```

---

## 🚀 How to Build and Package

Running either build script compiles the Java sources, bundles JDBC drivers, and generates the distributable archive `build/Lattice-1.0.0.zip` ready for installation.

For a distribution supporting 2025 IDEs, compile against a 2025.1 SDK. The Windows script accepts `-IdeaHome <SDK directory>`; the Bash script accepts `IDEA_HOME`. Both emit Java 21 bytecode and keep their intermediate outputs in `build/standalone/`.

### Windows (PowerShell)
```powershell
powershell.exe -ExecutionPolicy Bypass -File .\build-plugin.ps1
```

### Linux / macOS (Bash)
```bash
chmod +x ./build-plugin.sh
./build-plugin.sh
```

The script outputs:
```
build/Lattice-1.0.0.zip
```

---

## 🧪 Running Integration Tests

The live regression suites expect the supplied local MySQL and HSQLDB fixtures to be running. Temporary test schemas are removed after each suite.

### Windows
```powershell
.\test-functional.ps1 -IdeaHome 'C:\path\to\idea-2025.1'
```

### Gradle (Java 21)
```powershell
.\gradlew.bat test integrationTest buildPlugin '-Plattice.ide.home=C:/path/to/idea-2025.1'
```

`test` runs pure regressions; `integrationTest` opts into live databases. Omitting `lattice.ide.home` downloads the configured 2025.1 SDK. Gradle packages into `build/distributions/`.

For the Java 8 driver/server matrix and Plugin Verifier commands, see [compatibility validation](review/compatibility-2026-10-04.md).

### Linux / macOS
```bash
chmod +x ./test-plugin.sh
./test-plugin.sh
```

---

## 📦 Installing into IntelliJ IDEA
1. Open IntelliJ IDEA.
2. Navigate to **Settings** (or **Preferences**) > **Plugins**.
3. Click the gear icon ⚙️ > **Install Plugin from Disk...**.
4. Select `build/Lattice-1.0.0.zip`.
5. Restart IntelliJ IDEA. The **Lattice** tool window will appear on the right stripe!

---

## 📄 License
MIT License. Free and open source for everyone.
