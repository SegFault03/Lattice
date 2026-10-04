package com.vibe.ideadb.model;

public class ConnectionTestResult {
    private final boolean success;
    private final String databaseProductName;
    private final String databaseProductVersion;
    private final String driverName;
    private final String driverVersion;
    private final long responseTimeMs;
    private final String errorMessage;

    public ConnectionTestResult(boolean success, String databaseProductName, String databaseProductVersion,
                                String driverName, String driverVersion, long responseTimeMs, String errorMessage) {
        this.success = success;
        this.databaseProductName = databaseProductName;
        this.databaseProductVersion = databaseProductVersion;
        this.driverName = driverName;
        this.driverVersion = driverVersion;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = errorMessage;
    }

    public static ConnectionTestResult success(String dbName, String dbVer, String driverName, String driverVer, long timeMs) {
        return new ConnectionTestResult(true, dbName, dbVer, driverName, driverVer, timeMs, null);
    }

    public static ConnectionTestResult failure(String errorMessage, long timeMs) {
        return new ConnectionTestResult(false, null, null, null, null, timeMs, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public String getDatabaseProductName() { return databaseProductName; }
    public String getDatabaseProductVersion() { return databaseProductVersion; }
    public String getDriverName() { return driverName; }
    public String getDriverVersion() { return driverVersion; }
    public long getResponseTimeMs() { return responseTimeMs; }
    public String getErrorMessage() { return errorMessage; }

    public String getSummaryMessage() {
        if (success) {
            return String.format("Connection successful!\nDatabase: %s %s\nDriver: %s %s\nPing: %d ms",
                    databaseProductName, databaseProductVersion, driverName, driverVersion, responseTimeMs);
        } else {
            return "Connection failed: " + errorMessage;
        }
    }
}
