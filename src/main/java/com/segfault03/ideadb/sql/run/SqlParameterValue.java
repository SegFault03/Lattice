package com.segfault03.ideadb.sql.run;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/** A typed value entered for one SQL parameter. */
public record SqlParameterValue(@NotNull Type type, @NotNull String text) {
    public enum Type { TEXT, NUMBER, BOOLEAN, DATE, TIMESTAMP, NULL }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern TIMESTAMP = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})(\\.\\d{1,9})?");

    public @Nullable String validationError() {
        try {
            switch (type) {
                case NUMBER -> new BigDecimal(text.trim());
                case BOOLEAN -> {
                    if (!isBoolean()) return "Enter true or false";
                }
                case DATE -> LocalDate.parse(text, DATE);
                case TIMESTAMP -> {
                    var matcher = TIMESTAMP.matcher(text);
                    if (!matcher.matches()) return "Enter a timestamp as yyyy-MM-dd HH:mm:ss[.fffffffff]";
                    LocalDateTime.parse(matcher.group(1), SECONDS);
                }
                case TEXT, NULL -> { }
            }
        } catch (NumberFormatException e) {
            return "Enter a number such as 12 or 3.5";
        } catch (DateTimeParseException e) {
            return type == Type.DATE ? "Enter a date as yyyy-MM-dd" : "Enter a timestamp as yyyy-MM-dd HH:mm:ss[.fffffffff]";
        }
        return null;
    }

    /** The value as a JDBC binding; call only when {@link #validationError()} is null. */
    public @Nullable Object jdbcValue() {
        return switch (type) {
            case TEXT -> text;
            case NUMBER -> new BigDecimal(text.trim());
            case BOOLEAN -> Boolean.valueOf(text.equalsIgnoreCase("true"));
            case DATE -> java.sql.Date.valueOf(text);
            case TIMESTAMP -> java.sql.Timestamp.valueOf(text);
            case NULL -> null;
        };
    }

    private boolean isBoolean() {
        return text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false");
    }
}
