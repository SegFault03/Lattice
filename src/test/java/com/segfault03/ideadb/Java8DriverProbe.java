package com.segfault03.ideadb;

import java.sql.*;
import java.util.Properties;

/** Deliberately Java 8 source/API only; run in a separate Java 8 JVM. */
public final class Java8DriverProbe {
    public static void main(String[] args) throws Exception {
        boolean mysql = args[0].equals("MYSQL");
        boolean postgres = args[0].equals("POSTGRESQL");
        String name = postgres ? "org.postgresql.Driver" : mysql ? "com.mysql.cj.jdbc.Driver" : "org.hsqldb.jdbc.JDBCDriver";
        Driver driver;
        try { driver = (Driver)Class.forName(name).newInstance(); }
        catch (ClassNotFoundException old) { driver = (Driver)Class.forName(mysql ? "com.mysql.jdbc.Driver" : "org.hsqldb.jdbcDriver").newInstance(); }
        Properties properties = new Properties(); properties.setProperty("user", postgres ? "postgres" : mysql ? "root" : "SA"); properties.setProperty("password", postgres ? "lattice-test" : "");
        try (Connection connection = driver.connect(args[1], properties); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(mysql || postgres ? "SELECT 1" : "VALUES(1)")) {
            if (!result.next() || result.getInt(1) != 1) throw new AssertionError("Java 8 query failed");
            System.out.println("PASS Java 8 " + connection.getMetaData().getDriverVersion() + " / " + connection.getMetaData().getDatabaseProductVersion());
        }
        // Old MySQL cleanup threads can outlive main; this probe owns the process.
        System.exit(0);
    }
}
