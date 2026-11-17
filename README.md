
Aplicação em Java que demonstra na prática o padrão **Two-Phase Commit (2PC)** para gerenciar transações atômicas distribuídas em ambientes heterogêneos: um banco relacional (**PostgreSQL**) e um banco de documentos NoSQL (**MongoDB**).

---

💻 Pré-requisitos

Antes de iniciar, verifique se você tem instalado:

    Docker e Docker Compose

    Java JDK 17 ou superior

    Apache Maven 3.8+ (ou utilize o Maven integrado na sua IDE, como IntelliJ IDEA)

Verifique no terminal:

docker --version
docker compose version
java -version
mvn -version

🚀 Como Configurar e Executar
1. Subir os Bancos de Dados com Docker

Na pasta raiz do projeto (onde está o arquivo docker-compose.yml), execute:

docker compose up -d

Confira se os containers estão saudáveis:

docker compose ps

Você verá 2pc-postgres e 2pc-mongo no estado Up.

2. Executar a Aplicação
Opção A: Pelo Terminal (Maven)

    Linux / macOS / Git Bash:
    Bash

    mvn clean compile exec:java -Dexec.mainClass="com.example.TwoPhaseCommitApp"

    Windows (PowerShell):
    Powershell

    mvn clean compile exec:java "-Dexec.mainClass=com.example.TwoPhaseCommitApp"

Opção B: Pelo IntelliJ IDEA

    Abra a pasta do projeto no IntelliJ IDEA (File > Open).

    Aguarde o Maven sincronizar as dependências no canto inferior direito.

    Navegue até src/main/java/com/example/TwoPhaseCommitApp.java.

    Clique com o botão direito no arquivo e escolha Run 'TwoPhaseCommitApp.main()' (ou clique no ícone verde de Play ao lado da assinatura do método main).
    

📊 Comportamento Esperado dos Logs

Ao executar, você deverá ver um log detalhado demonstrando o 2PC em cada etapa:

Inicializando esquemas e conexões...
Esquemas inicializados com sucesso.

######################################################################
EXECUÇÃO DO CENÁRIO 1: Postgres -> MongoDB (MongoDB falha -> Rollback PG)
######################################################################
[Coordinator] Solicitando PREPARE para PostgreSQL-Participant...
[PostgreSQL-Participant] Voto SIM: Transação 'tx_sc1_...' preparada com sucesso.
[Coordinator] Solicitando PREPARE para MongoDB-Participant...
[Coordinator] FALHA NA FASE 1: [MongoDB-Participant] Erro durante a operação de Prepare...
>>> INICIANDO TRANSAÇÃO 2PC - FASE 2: GLOBAL ROLLBACK
[PostgreSQL-Participant] Transação preparada 'tx_sc1_...' revertida (ROLLBACK PREPARED).
--- [VERIFICAÇÃO FINAL] Cenário 1 (Ambos devem estar vazios) ---
PostgreSQL -> Registro encontrado? false (Saldo: null)
MongoDB    -> Registro encontrado? false (Saldo: null)

######################################################################
EXECUÇÃO DO CENÁRIO 2: MongoDB -> Postgres (Postgres falha -> Rollback Mongo)
######################################################################
[Coordinator] Solicitando PREPARE para MongoDB-Participant...
[MongoDB-Participant] Voto SIM: Operação da transação 'tx_sc2_...' validada com sucesso.
[Coordinator] Solicitando PREPARE para PostgreSQL-Participant...
[Coordinator] FALHA NA FASE 1: ERROR: new row for relation "accounts" violates check constraint "accounts_balance_check"
>>> INICIANDO TRANSAÇÃO 2PC - FASE 2: GLOBAL ROLLBACK
[MongoDB-Participant] Transação 'tx_sc2_...' abortada (Rollback executado).
--- [VERIFICAÇÃO FINAL] Cenário 2 (Ambos devem estar vazios) ---
PostgreSQL -> Registro encontrado? false (Saldo: null)
MongoDB    -> Registro encontrado? false (Saldo: null)

######################################################################
EXECUÇÃO DO CENÁRIO 3: Atualização simultânea com leitor contínuo a 10ms
######################################################################
[Poller @ 18:23:50.527] Postgres: 100,00  | MongoDB: 100,00 
[Poller @ 18:23:50.600] Postgres: 100,00  | MongoDB: 100,00  <-- Fase 1: Tupla preparada, sem Leitura Suja (Dirty Read)
[PostgreSQL-Participant] Transação 'tx_sc3_...' comitada com sucesso.
[MongoDB-Participant] Transação 'tx_sc3_...' comitada com sucesso.
[Poller @ 18:23:50.712] Postgres: 999,00  | MongoDB: 999,00  <-- Fase 2: Ambos comitados atomicamente
--- [VERIFICAÇÃO FINAL] Cenário 3 (Ambos devem ter Saldo = 999.00) ---
PostgreSQL -> Registro encontrado? true (Saldo: 999.0)
MongoDB    -> Registro encontrado? true (Saldo: 999.0)

🛑 Como Encerrar o Ambiente

Para finalizar os containers e liberar as portas após os testes:

docker compose down -v

