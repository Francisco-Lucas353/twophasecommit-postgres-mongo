package org.example;

import java.util.ArrayList;
import java.util.List;

public class TwoPhaseCoordinator {

    public static boolean execute(List<TwoPhaseParticipant> participants) {
        List<TwoPhaseParticipant> preparedParticipants = new ArrayList<>();
        boolean success = false;

        System.out.println("\n========================================================");
        System.out.println(">>> INICIANDO TRANSAÇÃO 2PC - FASE 1: PREPARE (VOTAÇÃO)");
        System.out.println("========================================================");

        try {
            for (TwoPhaseParticipant participant : participants) {
                System.out.printf("[Coordinator] Solicitando PREPARE para %s...%n", participant.getName());
                participant.prepare();
                preparedParticipants.add(participant);
            }

            System.out.println("\n--------------------------------------------------------");
            System.out.println(">>> FASE 1 CONCLUÍDA COM SUCESSO: TODOS VOTARAM 'SIM'");
            System.out.println(">>> INICIANDO TRANSAÇÃO 2PC - FASE 2: GLOBAL COMMIT");
            System.out.println("--------------------------------------------------------");

            for (TwoPhaseParticipant participant : preparedParticipants) {
                participant.commit();
            }
            success = true;
            System.out.println("[Coordinator] Transação distribuída finalizada com SUCESSO.");
        } catch (Exception e) {
            System.err.println("\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.err.printf("[Coordinator] FALHA NA FASE 1: %s%n", e.getMessage());
            System.err.println(">>> INICIANDO TRANSAÇÃO 2PC - FASE 2: GLOBAL ROLLBACK");
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

            for (TwoPhaseParticipant participant : participants) {
                try {
                    participant.rollback();
                } catch (Exception rbEx) {
                    System.err.printf("[Coordinator] Erro ao reverter %s: %s%n", participant.getName(), rbEx.getMessage());
                }
            }
        } finally {
            for (TwoPhaseParticipant participant : participants) {
                participant.close();
            }
        }
        return success;
    }
}