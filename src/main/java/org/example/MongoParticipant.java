package org.example;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.util.function.Consumer;

public class MongoParticipant implements TwoPhaseParticipant {
    private final MongoClient mongoClient;
    private final String globalTxId;
    private final Consumer<ClientSession> mongoOperation;
    private ClientSession session;
    private boolean isPrepared = false;

    public MongoParticipant(MongoClient mongoClient, String globalTxId, Consumer<ClientSession> mongoOperation) {
        this.mongoClient = mongoClient;
        this.globalTxId = globalTxId;
        this.mongoOperation = mongoOperation;
    }

    @Override
    public String getName() {
        return "MongoDB-Participant";
    }

    @Override
    public void prepare() throws Exception {
        this.session = mongoClient.startSession();
        this.session.startTransaction();

        try {
            // Executa a operação dentro do contexto transacional da sessão
            mongoOperation.accept(session);
            this.isPrepared = true;
            System.out.printf("[%s] Voto SIM: Operação da transação '%s' validada com sucesso.%n", getName(), globalTxId);
        } catch (Exception e) {
            session.abortTransaction();
            throw new RuntimeException("[" + getName() + "] Erro durante a operação de Prepare: " + e.getMessage(), e);
        }
    }

    @Override
    public void commit() throws Exception {
        if (isPrepared && session != null && session.hasActiveTransaction()) {
            session.commitTransaction();
            System.out.printf("[%s] Transação '%s' comitada com sucesso.%n", getName(), globalTxId);
        }
    }

    @Override
    public void rollback() throws Exception {
        if (session != null && session.hasActiveTransaction()) {
            session.abortTransaction();
            System.out.printf("[%s] Transação '%s' abortada (Rollback executado).%n", getName(), globalTxId);
        }
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
        }
    }
}