package com.segfault03.ideadb.model;

import java.math.*;
import java.sql.Types;
import java.time.*;
import java.util.*;

/** The same conversion validates pending input and produces the JDBC-bound value. */
public final class CellValueConverter {
    private CellValueConverter() {}
    public static Object convert(DatabaseType dialect,ColumnMetadata column,Object value) {
        if(column==null || value==RowDefaults.Value.USE_DEFAULT) return value;
        if(value==null) { if(!column.isNullable() && !column.isAutoIncrement()) throw new IllegalArgumentException("Column '" + column.getName() + "' cannot be NULL"); return null; }
        String type=column.getTypeName().toUpperCase(Locale.ROOT), text=value.toString().trim();
        int jdbc=column.getDataType();
        if(isText(jdbc)) {
            String literal=value.toString();
            if(column.getColumnSize()>0 && literal.codePointCount(0,literal.length())>column.getColumnSize()) throw new IllegalArgumentException("Text exceeds " + column.getColumnSize() + " characters");
            return literal;
        }
        try {
            if(jdbc==Types.BINARY || jdbc==Types.VARBINARY || jdbc==Types.LONGVARBINARY || jdbc==Types.BLOB) {
                byte[] bytes=value instanceof byte[] binary ? binary : text.startsWith("base64:") ? Base64.getDecoder().decode(text.substring(7)) : text.startsWith("0x") ? HexFormat.of().parseHex(text.substring(2)) : null;
                if(bytes==null) throw new IllegalArgumentException("Binary input must be 0x hex or base64: encoded bytes");
                if(column.getColumnSize()>0 && bytes.length>column.getColumnSize()) throw new IllegalArgumentException("Binary value exceeds column capacity"); return bytes;
            }
            if(jdbc==Types.TINYINT || jdbc==Types.SMALLINT || jdbc==Types.INTEGER || jdbc==Types.BIGINT) {
                boolean unsigned=type.contains("UNSIGNED"); int bits=type.contains("MEDIUMINT") ? 24 : jdbc==Types.TINYINT ? 8 : jdbc==Types.SMALLINT ? 16 : jdbc==Types.INTEGER ? 32 : 64;
                BigInteger number=new BigInteger(text), limit=BigInteger.ONE.shiftLeft(unsigned ? bits : bits-1);
                BigInteger min=unsigned ? BigInteger.ZERO : limit.negate(), max=limit.subtract(BigInteger.ONE);
                if(number.compareTo(min)<0 || number.compareTo(max)>0) throw new IllegalArgumentException("Integer must be between " + min + " and " + max);
                if(unsigned && bits==64) return number;
                if(jdbc==Types.BIGINT || (unsigned && bits==32)) return number.longValueExact();
                if(jdbc==Types.INTEGER || unsigned) return number.intValueExact();
                return jdbc==Types.SMALLINT ? number.shortValueExact() : number.byteValueExact();
            }
            if(jdbc==Types.DECIMAL || jdbc==Types.NUMERIC) {
                BigDecimal number=new BigDecimal(text), normalized=number.stripTrailingZeros();
                int scale=Math.max(0,normalized.scale()), integerDigits=number.signum()==0 ? 0 : Math.max(0,normalized.precision()-normalized.scale());
                if(scale>column.getDecimalDigits() || (column.getColumnSize()>0 && integerDigits>column.getColumnSize()-column.getDecimalDigits())) throw new IllegalArgumentException("Decimal exceeds precision/scale " + column.getColumnSize() + "," + column.getDecimalDigits());
                return number;
            }
            if(jdbc==Types.REAL || jdbc==Types.FLOAT || jdbc==Types.DOUBLE) {
                double number=Double.parseDouble(text);
                if(!Double.isFinite(number) || (jdbc==Types.REAL && !Float.isFinite((float)number))) throw new IllegalArgumentException("Expected a finite floating-point value");
                return jdbc==Types.REAL ? (Object)(float)number : number;
            }
            if(jdbc==Types.BOOLEAN || jdbc==Types.BIT) {
                if(jdbc==Types.BIT && column.getColumnSize()>1) {
                    if(value instanceof byte[] bytes) return bytes;
                    if(!text.matches("[01]+") || text.length()>column.getColumnSize()) throw new IllegalArgumentException("Expected a bit string within column capacity");
                    return dialect==DatabaseType.MYSQL ? new BigInteger(text,2) : new BigInteger(text,2).toByteArray();
                }
                return switch(text.toLowerCase(Locale.ROOT)) { case "true","1","t","yes" -> true; case "false","0","f","no" -> false; default -> throw new IllegalArgumentException("Expected true/false or 1/0"); };
            }
            if(jdbc==Types.DATE) return value instanceof java.sql.Date date ? date.toLocalDate() : LocalDate.parse(text);
            if(jdbc==Types.TIME_WITH_TIMEZONE) return value instanceof OffsetTime ? value : OffsetTime.parse(text);
            if(jdbc==Types.TIME) {
                if(dialect==DatabaseType.MYSQL) {
                    var match=java.util.regex.Pattern.compile("-?(\\d{1,3}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{1,6}))?").matcher(text);
                    if(!match.matches() || Integer.parseInt(match.group(1))>838) throw new IllegalArgumentException("MySQL TIME must be within -838:59:59 and 838:59:59");
                    if(match.group(4)!=null && match.group(4).length()>column.getDecimalDigits()) throw new IllegalArgumentException("TIME exceeds fractional precision"); return text;
                }
                return value instanceof java.sql.Time time ? time.toLocalTime() : LocalTime.parse(text);
            }
            if(jdbc==Types.TIMESTAMP_WITH_TIMEZONE) return value instanceof OffsetDateTime ? value : OffsetDateTime.parse(text.replace(' ','T'));
            if(jdbc==Types.TIMESTAMP || type.contains("DATETIME")) {
                LocalDateTime timestamp=value instanceof java.sql.Timestamp time ? time.toLocalDateTime() : text.length()==10 ? LocalDate.parse(text).atStartOfDay() : LocalDateTime.parse(text.replace(' ','T'));
                if(timestamp.getNano()!=0 && column.getDecimalDigits()<9) {
                    int divisor=(int)Math.pow(10,9-Math.max(0,column.getDecimalDigits()));
                    if(timestamp.getNano()%divisor!=0) throw new IllegalArgumentException("Timestamp exceeds fractional precision");
                }
                return timestamp;
            }
            return value;
        } catch(NumberFormatException | java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid " + type + " value: " + text,error); }
    }
    public static String validate(DatabaseType dialect,ColumnMetadata column,Object value) {
        try { convert(dialect,column,value); return null; } catch(IllegalArgumentException error) { return error.getMessage(); }
    }
    private static boolean isText(int jdbc) { return jdbc==Types.CHAR || jdbc==Types.VARCHAR || jdbc==Types.LONGVARCHAR || jdbc==Types.NCHAR || jdbc==Types.NVARCHAR || jdbc==Types.LONGNVARCHAR || jdbc==Types.CLOB || jdbc==Types.NCLOB; }
}
