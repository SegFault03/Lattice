package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application settings for SQL editor support. The master switch is "automatic" until the user decides: on in
 * Community, off where the Ultimate Database plugin already provides SQL support.
 */
@State(name = "com.segfault03.ideadb.sql.settings.SqlEditorSettings", storages = @Storage("LatticeSqlEditor.xml"))
public final class SqlEditorSettings implements PersistentStateComponent<SqlEditorSettings.State> {
    public static class State {
        /** Null means automatic. */
        public Boolean enabled;
        public boolean handleSqlFiles = true;
        public boolean highlighting = true;
        public boolean completion = true;
        public boolean hover = true;
        /** The claim last applied to the platform's file type mapping; null before the first run. */
        public Boolean appliedClaim;
        public boolean takeoverNoticeShown;
        public boolean ultimateNoticeShown;
    }

    private State state = new State();

    public static SqlEditorSettings getInstance() { return ApplicationManager.getApplication().getService(SqlEditorSettings.class); }

    /** Pure rule behind the automatic master switch, separated so it can be tested without an Ultimate IDE. */
    public static boolean effectiveEnabled(@Nullable Boolean explicit, boolean ultimateActive) {
        return explicit != null ? explicit : !ultimateActive;
    }

    @Override public synchronized @NotNull State getState() { return state; }
    @Override public synchronized void loadState(@NotNull State loaded) { state = loaded; }

    public synchronized boolean isEnabled() { return effectiveEnabled(state.enabled, UltimateDatabasePlugin.isActive()); }
    public synchronized boolean isExplicit() { return state.enabled != null; }
    public synchronized void setEnabled(boolean enabled) { state.enabled = enabled; }

    public synchronized boolean isHandleSqlFiles() { return state.handleSqlFiles; }
    public synchronized void setHandleSqlFiles(boolean value) { state.handleSqlFiles = value; }
    public synchronized boolean isHighlighting() { return state.highlighting; }
    public synchronized void setHighlighting(boolean value) { state.highlighting = value; }
    public synchronized boolean isCompletion() { return state.completion; }
    public synchronized void setCompletion(boolean value) { state.completion = value; }
    public synchronized boolean isHover() { return state.hover; }
    public synchronized void setHover(boolean value) { state.hover = value; }

    /** Whether Lattice should own the .sql file type right now. */
    public synchronized boolean isTakeoverEffective() { return isEnabled() && state.handleSqlFiles; }
    public synchronized boolean isHighlightingEffective() { return isEnabled() && state.highlighting; }
    public synchronized boolean isCompletionEffective() { return isEnabled() && state.completion; }
    public synchronized boolean isHoverEffective() { return isEnabled() && state.hover; }

    public synchronized @Nullable Boolean appliedClaim() { return state.appliedClaim; }
    public synchronized void appliedClaim(boolean claim) { state.appliedClaim = claim; }
    public synchronized boolean takeoverNoticeShown() { return state.takeoverNoticeShown; }
    public synchronized void takeoverNoticeShown(boolean shown) { state.takeoverNoticeShown = shown; }
    public synchronized boolean ultimateNoticeShown() { return state.ultimateNoticeShown; }
    public synchronized void ultimateNoticeShown(boolean shown) { state.ultimateNoticeShown = shown; }
}
