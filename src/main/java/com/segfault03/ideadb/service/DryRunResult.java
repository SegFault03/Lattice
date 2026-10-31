package com.segfault03.ideadb.service;

import java.util.List;

/** Outcome of a dry run: an execution plan, a rolled-back run, or a reason nothing could be shown. */
public sealed interface DryRunResult {
    /** The plan text; {@code literalFallback} is true when it came from the statement with values written inline. */
    record Plan(String text, boolean literalFallback, long elapsedMs) implements DryRunResult {}
    record RolledBack(int rows, List<String> warnings, long elapsedMs) implements DryRunResult {}
    record Failed(String message) implements DryRunResult {}
    /** The rollback could not be confirmed, so the named connection may hold uncommitted changes. */
    record OutcomeUnknown(String connectionName, String message) implements DryRunResult {}
    record Cancelled(boolean rolledBack) implements DryRunResult {}
}
