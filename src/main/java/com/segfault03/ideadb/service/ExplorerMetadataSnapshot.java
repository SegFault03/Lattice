package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Search metadata is read off the EDT; one inaccessible namespace does not hide other results. */
public record ExplorerMetadataSnapshot(List<Namespace> namespaces, List<String> errors, int depth) {
    public record Table(TableMetadata metadata, List<ColumnMetadata> columns, boolean columnsLoaded) {}
    public record Namespace(String name, List<Table> tables, boolean tablesLoaded) {}

    public static ExplorerMetadataSnapshot read(Connection connection, ConnectionConfig config, int depth,
                                                BooleanSupplier cancelled) throws Exception {
        MetadataService service = MetadataService.getInstance();
        List<Namespace> namespaces = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        checkCancelled(cancelled);
        for (String name : service.getDatabases(connection, config)) {
            checkCancelled(cancelled);
            List<Table> tables = new ArrayList<>();
            boolean tablesLoaded = false;
            if (depth >= 2) {
                try {
                    for (TableMetadata table : service.getTables(connection, config, name)) {
                        checkCancelled(cancelled);
                        List<ColumnMetadata> columns = List.of();
                        boolean columnsLoaded = false;
                        if (depth >= 3) {
                            try {
                                columns = List.copyOf(service.getColumns(connection, config, name, table.getName()));
                                columnsLoaded = true;
                            } catch (Exception failure) {
                                checkCancelled(cancelled);
                                errors.add(name + "." + table.getName() + ": " + failure.getMessage());
                            }
                        }
                        tables.add(new Table(table, columns, columnsLoaded));
                    }
                    tablesLoaded = true;
                } catch (CancellationException cancelledWork) { throw cancelledWork; }
                catch (Exception failure) {
                    checkCancelled(cancelled);
                    errors.add(name + ": " + failure.getMessage());
                }
            }
            namespaces.add(new Namespace(name, List.copyOf(tables), tablesLoaded));
        }
        return new ExplorerMetadataSnapshot(List.copyOf(namespaces), List.copyOf(errors), depth);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
    }
}
