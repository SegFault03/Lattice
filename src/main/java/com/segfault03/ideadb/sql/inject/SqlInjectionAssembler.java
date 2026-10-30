package com.segfault03.ideadb.sql.inject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Joins the pieces of a string expression into the SQL it spells. Text between literals (a variable, a call, a
 * template) is unknown, so a placeholder stands for it; the placeholder travels as the prefix of the next injected
 * place, so the injected file reads {@code select * from t where id = ?} while only the literals are editable.
 *
 * @param <H> the type of a literal's host element
 */
public final class SqlInjectionAssembler<H> {
    /** What stands for a part of the SQL that is only known at run time. */
    public static final String PLACEHOLDER = "?";

    /** A stretch of literal text, or an unknown part when {@code host} is null. */
    public record Part<H>(@Nullable H host, int start, int end, @NotNull String text) {
        public static <H> Part<H> literal(@NotNull H host, int start, int end, @NotNull String text) { return new Part<>(host, start, end, text); }
        public static <H> Part<H> dynamic() { return new Part<>(null, 0, 0, PLACEHOLDER); }
        public boolean isDynamic() { return host == null; }
    }

    /** One place to inject: the literal range, with the placeholder text that precedes or follows it. */
    public record Place<H>(@NotNull H host, int start, int end, @Nullable String prefix, @Nullable String suffix) {}

    private final List<Place<H>> places = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    public SqlInjectionAssembler(@NotNull List<Part<H>> parts) {
        boolean gap = false;
        for (Part<H> part : parts) {
            if (part.isDynamic()) { gap = true; continue; }
            if (part.start() == part.end()) continue; // an empty literal adds nothing and does not end a gap
            places.add(new Place<>(part.host(), part.start(), part.end(), gap ? PLACEHOLDER : null, null));
            if (gap) text.append(PLACEHOLDER);
            text.append(part.text());
            gap = false;
        }
        if (gap && !places.isEmpty()) {
            Place<H> last = places.remove(places.size() - 1);
            places.add(new Place<>(last.host(), last.start(), last.end(), last.prefix(), PLACEHOLDER));
            text.append(PLACEHOLDER);
        }
    }

    /** The SQL as it will appear in the injected file. */
    public @NotNull String text() { return text.toString(); }

    public @NotNull List<Place<H>> places() { return places; }
}
