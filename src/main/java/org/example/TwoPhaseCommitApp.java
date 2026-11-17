package org.example;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.sql.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;

public class TwoPhaseCommitApp {
    private static final String PG_URL = "jdbc:postgresql://localhost:5432/two_phase_db";
    private static final String PG_USER = "postgres";
    private static final String PG_PASS = "postgrespassword";
    private static final String MONGO_URI = "mongodb://localhost:27017/?replicaSet=rs0&directConnection=true";

    // Reutiliza o cliente MongoDB para evitar recriar conexões a cada 10ms no poller
    private static final MongoClient SHARED_MONGO_CLIENT = MongoClients.create(MONGO_URI);

    public static void main(String[] args) throws Exception {
        System.out.println("Inicializando esquemas e conexões...");
        initDatabases();

        System.out.println("\n######################################################################");
        System.out.println("EXECUÇÃO DO CENÁRIO 1: Postgres -> MongoDB (MongoDB falha -> Rollback PG)");
        System.out.println("######################################################################");
        runScenario1();

        System.out.println("\n######################################################################");
        System.out.println("EXECUÇÃO DO CENÁRIO 2: MongoDB -> Postgres (Postgres falha -> Rollback Mongo)");
        System.out.println("######################################################################");
        runScenario2();

        System.out.println("\n######################################################################");
        System.out.println("EXECUÇÃO DO CENÁRIO 3: Atualização simultânea com leitor contínuo a 10ms");
        System.out.println("######################################################################");
        runScenario3();

        SHARED_MONGO_CLIENT.close();
        System.exit(0);
    }

    private static Connection getPgConnection() throws SQLException {
        return DriverManager.getConnection(PG_URL, PG_USER, PG_PASS);
    }

    private static void initDatabases() throws SQLException {
        try (Connection conn = getPgConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS accounts");
            stmt.execute("CREATE TABLE accounts (" +
                    "id INT PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "balance NUMERIC(10, 2) NOT NULL CHECK (balance >= 0))");
        }

        MongoDatabase db = SHARED_MONGO_CLIENT.getDatabase("two_phase_db");
        db.getCollection("accounts").drop();
        db.createCollection("accounts");
        System.out.println("Esquemas inicializados com sucesso.");
    }

    private static void runScenario1() throws Exception {
        int accountId = 101;
        String txId = "tx_sc1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Connection pgConn = getPgConnection();

        TwoPhaseParticipant pgParticipant = new PostgresParticipant(
                pgConn,
                txId,
                "INSERT INTO accounts (id, name, balance) VALUES (?, ?, ?)",
                accountId, "Alice", 1500.00
        );

        TwoPhaseParticipant mongoParticipant = new MongoParticipant(
                SHARED_MONGO_CLIENT,
                txId,
                session -> {
                    MongoCollection<Document> coll = SHARED_MONGO_CLIENT.getDatabase("two_phase_db").getCollection("accounts");
                    coll.insertOne(session, new Document("_id", accountId).append("name", "Alice").append("balance", 1500.00));
                    throw new RuntimeException("Erro forçado no MongoDB para acionar o Rollback no Postgres!");
                }
        );

        TwoPhaseCoordinator.execute(Arrays.asList(pgParticipant, mongoParticipant));
        verifyState(accountId, "Cenário 1 (Ambos devem estar vazios)");
    }

    private static void runScenario2() throws Exception {
        int accountId = 202;
        String txId = "tx_sc2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Connection pgConn = getPgConnection();

        TwoPhaseParticipant mongoParticipant = new MongoParticipant(
                SHARED_MONGO_CLIENT,
                txId,
                session -> {
                    MongoCollection<Document> coll = SHARED_MONGO_CLIENT.getDatabase("two_phase_db").getCollection("accounts");
                    coll.insertOne(session, new Document("_id", accountId).append("name", "Bob").append("balance", 2000.00));
                }
        );

        TwoPhaseParticipant pgParticipant = new PostgresParticipant(
                pgConn,
                txId,
                "INSERT INTO accounts (id, name, balance) VALUES (?, ?, ?)",
                accountId, "Bob", -50.00 // Saldo negativo viola CHECK constraint
        );

        TwoPhaseCoordinator.execute(Arrays.asList(mongoParticipant, pgParticipant));
        verifyState(accountId, "Cenário 2 (Ambos devem estar vazios)");
    }

    private static void runScenario3() throws Exception {
        int accountId = 303;

        // Registro inicial com Saldo = 100.00
        try (Connection conn = getPgConnection(); PreparedStatement stmt = conn.prepareStatement("INSERT INTO accounts VALUES (?, ?, ?)")) {
            stmt.setInt(1, accountId);
            stmt.setString(2, "Charlie");
            stmt.setDouble(3, 100.00);
            stmt.executeUpdate();
        }
        SHARED_MONGO_CLIENT.getDatabase("two_phase_db").getCollection("accounts")
                .insertOne(new Document("_id", accountId).append("name", "Charlie").append("balance", 100.00));

        System.out.println("Registro ID 303 inicializado com Saldo = 100.00");

        ScheduledExecutorService pollerExecutor = Executors.newSingleThreadScheduledExecutor();
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        // Poller consultando os 2 bancos a cada 10ms
        pollerExecutor.scheduleAtFixedRate(() -> {
            try {
                Double pgBalance = readPgBalance(accountId);
                Double mongoBalance = readMongoBalance(accountId);
                System.out.printf("[Poller @ %s] Postgres: %-7s | MongoDB: %-7s%n",
                        LocalTime.now().format(timeFormatter),
                        pgBalance != null ? String.format("%.2f", pgBalance) : "N/A",
                        mongoBalance != null ? String.format("%.2f", mongoBalance) : "N/A");
            } catch (Exception ignored) {}
        }, 0, 10, TimeUnit.MILLISECONDS);

        Thread.sleep(60);

        String txId = "tx_sc3_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Connection pgConn = getPgConnection();

        TwoPhaseParticipant pgParticipant = new PostgresParticipant(
                pgConn,
                txId,
                "UPDATE accounts SET balance = ? WHERE id = ?",
                999.00, accountId
        );

        TwoPhaseParticipant mongoParticipant = new MongoParticipant(
                SHARED_MONGO_CLIENT,
                txId,
                session -> {
                    try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                    MongoCollection<Document> coll = SHARED_MONGO_CLIENT.getDatabase("two_phase_db").getCollection("accounts");
                    coll.updateOne(session, Filters.eq("_id", accountId), Updates.set("balance", 999.00));
                }
        );

        TwoPhaseCoordinator.execute(Arrays.asList(pgParticipant, mongoParticipant));

        Thread.sleep(100);

        pollerExecutor.shutdown();
        pollerExecutor.awaitTermination(2, TimeUnit.SECONDS);

        verifyState(accountId, "Cenário 3 (Ambos devem ter Saldo = 999.00)");
    }

    private static Double readPgBalance(int id) throws SQLException {
        try (Connection conn = getPgConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getDouble("balance");
            }
        }
        return null;
    }

    private static Double readMongoBalance(int id) {
        Document doc = SHARED_MONGO_CLIENT.getDatabase("two_phase_db").getCollection("accounts")
                .find(Filters.eq("_id", id)).first();
        if (doc != null) return doc.getDouble("balance");
        return null;
    }

    private static void verifyState(int accountId, String context) throws SQLException {
        Double pgBal = readPgBalance(accountId);
        Double mgBal = readMongoBalance(accountId);
        System.out.println("\n--- [VERIFICAÇÃO FINAL] " + context + " ---");
        System.out.printf("PostgreSQL -> Registro encontrado? %s (Saldo: %s)%n", (pgBal != null), pgBal);
        System.out.printf("MongoDB    -> Registro encontrado? %s (Saldo: %s)%n", (mgBal != null), mgBal);
        System.out.println("----------------------------------------------------------------------\n");
    }
}