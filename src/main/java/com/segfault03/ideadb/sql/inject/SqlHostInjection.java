package com.segfault03.ideadb.sql.inject;

import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import org.jetbrains.annotations.NotNull;

/** Registers an assembled string as one multi-place injection of Lattice SQL. */
public final class SqlHostInjection {
    private SqlHostInjection() {}

    public static <H extends PsiLanguageInjectionHost> void register(@NotNull MultiHostRegistrar registrar, @NotNull SqlInjectionAssembler<H> assembled) {
        if (assembled.places().isEmpty()) return;
        var injection = registrar.startInjecting(LatticeSqlLanguage.INSTANCE);
        for (var place : assembled.places()) {
            injection.addPlace(place.prefix(), place.suffix(), place.host(), new TextRange(place.start(), place.end()));
        }
        injection.doneInjecting();
    }
}
