/*
 * Copyright 2016 enrico.olivelli.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package herddb.cli;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import herddb.client.ClientConfiguration;
import herddb.client.HDBConnection;
import herddb.client.TableSpaceDumpReceiver;
import herddb.jdbc.HerdDBConnection;
import herddb.jdbc.HerdDBDataSource;
import herddb.log.LogSequenceNumber;
import herddb.model.Record;
import herddb.model.Table;
import herddb.model.TableSpace;
import herddb.storage.DataStorageManagerException;
import herddb.utils.ExtendedDataOutputStream;
import herddb.utils.SimpleBufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.LogManager;
import java.util.stream.Collectors;
import javax.xml.ws.Holder;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 *
 * @author enrico.olivelli
 */
public class HerdDBCLI {

    public static void main(String... args) {
        DefaultParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption("x", "url", true, "JDBC URL");
        options.addOption("u", "username", true, "JDBC Username");
        options.addOption("pwd", "password", true, "JDBC Password");
        options.addOption("q", "query", true, "Execute inline query");
        options.addOption("v", "verbose", false, "Verbose output");
        options.addOption("s", "schema", true, "Default tablespace (SQL schema)");
        options.addOption("f", "file", true, "SQL Script to execute (statement separated by 'GO' lines)");
        options.addOption("g", "script", true, "Groovy Script to execute");
        options.addOption("i", "ignoreerrors", false, "Ignore SQL Errors during file execution");
        options.addOption("d", "dump", true, "Dump tablespace");
        options.addOption("r", "restore", true, "Restore tablespace");
        org.apache.commons.cli.CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException error) {
            println("Syntax error: " + error);
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("herddb", options, true);
            System.exit(1);
            return;
        }
        if (args.length == 0) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("herddb", options, true);
            System.exit(1);
            return;
        }

        boolean verbose = commandLine.hasOption("verbose");
        if (!verbose) {
            LogManager.getLogManager().reset();
        }
        String url = commandLine.getOptionValue("url", "jdbc:herddb:server:localhost:7000");
        String username = commandLine.getOptionValue("username", ClientConfiguration.PROPERTY_CLIENT_USERNAME_DEFAULT);
        String password = commandLine.getOptionValue("password", ClientConfiguration.PROPERTY_CLIENT_PASSWORD_DEFAULT);
        String schema = commandLine.getOptionValue("schema", TableSpace.DEFAULT);
        String query = commandLine.getOptionValue("query", "");
        String file = commandLine.getOptionValue("file", "");
        String dump = commandLine.getOptionValue("dump", "");
        String restore = commandLine.getOptionValue("restore", "");
        String script = commandLine.getOptionValue("script", "");
        boolean ignoreerrors = commandLine.hasOption("ignoreerrors");
        try (HerdDBDataSource datasource = new HerdDBDataSource()) {
            datasource.setUrl(url);
            datasource.setUsername(username);
            datasource.setPassword(password);

            try (Connection connection = datasource.getConnection();
                    Statement statement = connection.createStatement()) {
                connection.setSchema(schema);
                if (!query.isEmpty()) {
                    executeStatement(verbose, ignoreerrors, query, statement);
                } else if (!file.isEmpty()) {
                    StringBuilder currentStatement = new StringBuilder();
                    for (String line : Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8)) {
                        if (line.trim().equalsIgnoreCase("GO")) {
                            executeStatement(verbose, ignoreerrors, currentStatement.toString(), statement);
                            currentStatement.setLength(0);
                        } else {
                            currentStatement.append(line + "\n");
                        }
                    }
                    executeStatement(verbose, ignoreerrors, currentStatement.toString(), statement);
                } else if (!script.isEmpty()) {
                    Map<String, Object> variables = new HashMap<>();
                    variables.put("connection", connection);
                    variables.put("datasource", datasource);
                    variables.put("statement", statement);
                    GroovyShell shell = new GroovyShell(new Binding(variables));
                    shell.evaluate(new File(script));
                } else if (!dump.isEmpty()) {
                    List<String> tablesToDump = new ArrayList<>();
                    try (ResultSet rs = statement.executeQuery("SELECT table_name FROM " + schema + ".systables WHERE systemtable='false'")) {
                        while (rs.next()) {
                            String tablename = rs.getString(1).toLowerCase();
                            tablesToDump.add(tablename);
                        }
                    }
                    Path outputfile = Paths.get(dump).toAbsolutePath();
                    println("Dumping tables " + tablesToDump + " from tablespace " + schema + " to " + outputfile);

                    try (OutputStream fout = Files.newOutputStream(outputfile, StandardOpenOption.CREATE_NEW);
                            SimpleBufferedOutputStream oo = new SimpleBufferedOutputStream(fout);
                            ExtendedDataOutputStream out = new ExtendedDataOutputStream(fout)) {
                        dumpTablespace(schema, connection, out);
                    }
                } else if (!restore.isEmpty()) {

                    Path inputfile = Paths.get(dump).toAbsolutePath();
                    println("Restoring tablespace" + schema + " from file " + inputfile);

                    List<String> tablesToDrop = new ArrayList<>();
                    try (ResultSet rs = statement.executeQuery("SELECT table_name,systemtable FROM " + schema + ".systables WHERE systemtable='false'")) {
                        while (rs.next()) {
                            String tablename = rs.getString(1).toLowerCase();
                            tablesToDrop.add(tablename);
                        }
                    }
                    for (String table : tablesToDrop) {
                        statement.execute("DROP TABLE " + schema + "." + table);
                    }
                } else {
                    HelpFormatter formatter = new HelpFormatter();
                    formatter.printHelp("herddb", options, true);
                    System.exit(1);
                    return;
                }
            }

        } catch (Exception error) {
            if (verbose) {
                error.printStackTrace();
            } else {
                println("error:" + error);
            }
            System.exit(1);
        }
    }

    private static void executeStatement(boolean verbose, boolean ignoreerrors, String query, final Statement statement) throws SQLException {
        if (query.trim().isEmpty()) {
            return;
        }
        if (verbose) {
            System.out.println("Executing query:" + query);
        }
        try {
            boolean resultSet = statement.execute(query);
            if (resultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> columns = new ArrayList<>();
                    int ccount = md.getColumnCount();
                    for (int i = 1; i <= ccount; i++) {
                        columns.add(md.getColumnName(i));
                    }
                    System.out.println(columns.stream().collect(Collectors.joining(";")));

                    while (rs.next()) {
                        List<String> values = new ArrayList<>();
                        for (int i = 1; i <= ccount; i++) {
                            String value = rs.getString(i);
                            if (value == null) {
                                value = "<NULL>";
                            }
                            values.add(value);
                        }
                        System.out.println(values.stream().collect(Collectors.joining(";")));
                    }
                }
            } else {
                int updateCount = statement.getUpdateCount();
                System.out.println("UPDATE COUNT: " + updateCount);
            }
        } catch (SQLException err) {
            if (ignoreerrors) {
                println("ERROR:" + err);
            } else {
                throw err;
            }
        }
    }

    private static void dumpTablespace(String schema, Connection connection, ExtendedDataOutputStream out) throws Exception {
        HerdDBConnection hcon = connection.unwrap(HerdDBConnection.class);
        HDBConnection hdbconnection = hcon.getConnection();
        Holder<Throwable> errorHolder = new Holder<>();
        CountDownLatch waiter = new CountDownLatch(1);
        hdbconnection.dumpTableSpace(schema, new TableSpaceDumpReceiver() {

            long tableCount;

            @Override
            public void onError(Throwable error) throws DataStorageManagerException {
                errorHolder.value = error;
                waiter.countDown();
            }

            @Override
            public void finish() throws DataStorageManagerException {
                waiter.countDown();
            }

            @Override
            public void endTable() throws DataStorageManagerException {
                println("endTable, records " + tableCount);
                try {
                    out.writeVInt(Integer.MIN_VALUE); // EndOfTableMarker
                } catch (IOException err) {
                    throw new DataStorageManagerException(err);
                }
            }

            @Override
            public void receiveTableDataChunk(List<Record> record) throws DataStorageManagerException {
                try {
                    out.writeVInt(record.size());
                    for (Record r : record) {
                        out.writeArray(r.key.data);
                        out.writeArray(r.value.data);
                    }
                    tableCount += record.size();
                } catch (IOException err) {
                    throw new DataStorageManagerException(err);
                }
            }

            @Override
            public void beginTable(Table table) throws DataStorageManagerException {
                println("beginTable " + table.name);
                tableCount = 0;
                try {
                    out.writeArray(table.serialize());
                } catch (IOException err) {
                    throw new DataStorageManagerException(err);
                }
            }

            @Override
            public void start(LogSequenceNumber logSequenceNumber) throws DataStorageManagerException {
                println("Backup at log position: " + logSequenceNumber);
            }

        }, 64 * 1024);
        if (errorHolder.value != null) {
            throw new Exception(errorHolder.value);
        }
        waiter.await();
    }

    private static void println(Object msg) {
        System.out.println(msg);
    }
}
