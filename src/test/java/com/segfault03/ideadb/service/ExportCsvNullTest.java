package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Importers distinguish an empty unquoted field (NULL) from {@code ""} (empty string); the export must keep them apart. */
class ExportCsvNullTest {
    @Test void nullAndEmptyStringStayDistinct(@TempDir Path directory) throws Exception {
        QueryResult result = QueryResult.forResultSet(List.of("a", "b", "c"), List.of("VARCHAR", "VARCHAR", "VARCHAR"),
                List.of(Arrays.asList(null, "", "x")), 0);
        Path file = directory.resolve("out.csv");
        ExportService.getInstance().exportToCsv(result, file.toFile());
        assertEquals(List.of("a,b,c", ",\"\",x"), Files.readAllLines(file));
    }
}
