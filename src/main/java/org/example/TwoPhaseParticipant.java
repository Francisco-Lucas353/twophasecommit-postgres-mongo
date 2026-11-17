package org.example;

public interface TwoPhaseParticipant {
    String getName();

    void prepare() throws Exception;

    void commit() throws Exception;

    void rollback() throws Exception;

    void close();
}