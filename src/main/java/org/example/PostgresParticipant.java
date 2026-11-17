package org.example;

import org.example.TwoPhaseParticipant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class PostgresParticipant implements TwoPhaseParticipant {
    private final Connection connection;
    private final String globalTxId;
    private final String sql;
    private final Object[] params;
    private boolean isPrepared = false;

    public PostgresParticipant(Connection connection, String globalTxId, String sql, Object... params) {
        this.connection = connection;
        this.globalTxId = globalTxId;
        this.sql = sql;
        this.params = params;
    }

    @Override
    public String getName() {
        return "PostgreSQL-Participant";
    }

    @Override
    public void prepare() throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PREPARE TRANSACTION '" + globalTxId + "'");
            // IMPORTANTE: Volta para autocommit=true pois COMMIT PREPARED
            // não pode ser executado dentro de blocos transacionais BEGIN
            connection.setAutoCommit(true);
            this.isPrepared = true;
            System.out.printf("[%s] Voto SIM: Transação '%s' preparada com sucesso.%n", getName(), globalTxId);
        } catch (SQLException e) {
            connection.rollback();
            connection.setAutoCommit(true);
            throw new RuntimeException("[" + getName() + "] Falha ao preparar transação: " + e.getMessage(), e);
        }
    }

    @Override
    public void commit() throws Exception {
        if (!isPrepared) return;
        connection.setAutoCommit(true);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("COMMIT PREPARED '" + globalTxId + "'");
            System.out.printf("[%s] Transação '%s' comitada com sucesso.%n", getName(), globalTxId);
        }
    }

    @Override
    public void rollback() throws Exception {
        try {
            connection.setAutoCommit(true);
            try (Statement stmt = connection.createStatement()) {
                if (isPrepared) {
                    stmt.execute("ROLLBACK PREPARED '" + globalTxId + "'");
                    System.out.printf("[%s] Transação preparada '%s' revertida (ROLLBACK PREPARED).%n", getName(), globalTxId);
                } else {
                    connection.rollback();
                    System.out.printf("[%s] Transação local revertida.%n", getName());
                }
            }
        } catch (SQLException e) {
            System.err.printf("[%s] Aviso no rollback: %s%n", getName(), e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}