package com.segfault03.ideadb.service;

import java.sql.*;
import java.util.concurrent.*;

/** Read-only diagnostic for the chosen HSQLDB jar, independent of IDE/UI scheduling. */
public final class HsqlCancellationProbe {
    public static void main(String[] arguments) throws Exception {
        Class.forName("org.hsqldb.jdbc.JDBCDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:cancel_probe", "SA", "")) {
            DatabaseMetaData metadata = connection.getMetaData();
            System.out.println("Driver: " + metadata.getDriverName() + " " + metadata.getDriverVersion());
            String values = "(VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9))";
            String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SYSTEM_COLUMNS A CROSS JOIN "
                    + "INFORMATION_SCHEMA.SYSTEM_COLUMNS B CROSS JOIN " + values + " C(n) CROSS JOIN " + values + " D(n)";
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                for (boolean cancel : new boolean[]{false, true}) {
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(15);
                        CountDownLatch started = new CountDownLatch(1);
                        long executionStart = System.nanoTime();
                        Future<String> result = worker.submit(() -> {
                            started.countDown();
                            try (ResultSet rows = statement.executeQuery(sql)) {
                                rows.next();
                                return "returned COUNT=" + rows.getLong(1);
                            } catch (SQLException error) { return "SQLException: " + error.getSQLState() + " " + error.getMessage(); }
                        });
                        if (!started.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Query worker did not start");
                        if (cancel) {
                            Thread.sleep(250);
                            long requestStart = System.nanoTime();
                            statement.cancel();
                            System.out.println("Statement.cancel returned after " + millis(requestStart) + " ms; worker done=" + result.isDone());
                        }
                        System.out.println((cancel ? "With cancellation: " : "Without cancellation: ")
                                + result.get(20, TimeUnit.SECONDS) + "; worker finished after " + millis(executionStart) + " ms");
                    }
                }
            } finally { worker.shutdownNow(); }
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("VALUES(1)")) {
                rows.next();
                System.out.println("Connection usable afterwards: " + (rows.getInt(1) == 1));
            }
        }
    }

    private static long millis(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
