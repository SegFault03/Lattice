package com.segfault03.ideadb.sql;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;

/** A blocking read action through API that is current on every IDE target: newer platforms deprecate ReadAction.compute. */
public final class ReadActions {
    private ReadActions() {}

    public static <T> T compute(Computable<T> action) { return ApplicationManager.getApplication().runReadAction(action); }
}
