#!/bin/bash
# Cria os usuarios da aplicacao no PDB FREEPDB1.
# Executado pela imagem gvenzl/oracle-free apenas na primeira inicializacao (volume de dados vazio).
# Para executar de novo: docker compose down -v && docker compose up -d
#
# O corpo roda em subshell: se a imagem fizer "source" do arquivo (quando ele nao e executavel),
# as opcoes do shell e as variaveis daqui nao vazam para o entrypoint.
(
set -euo pipefail

for var in WALLET_OWNER_PASSWORD WALLET_APP_PASSWORD WALLET_AUDIT_PASSWORD; do
  if [ -z "${!var:-}" ]; then
    echo "01-create-users: variavel $var nao definida" >&2
    exit 1
  fi
  if [[ ! "${!var}" =~ ^[A-Za-z][A-Za-z0-9_]{7,29}$ ]]; then
    echo "01-create-users: $var deve ter 8-30 caracteres, comecar com letra e conter apenas letras, numeros e _" >&2
    exit 1
  fi
done

sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET FEEDBACK OFF
SET VERIFY OFF
ALTER SESSION SET CONTAINER = FREEPDB1;

DECLARE
  PROCEDURE create_user(p_user IN VARCHAR2, p_password IN VARCHAR2, p_quota IN BOOLEAN) IS
    v_count PLS_INTEGER;
  BEGIN
    SELECT COUNT(*) INTO v_count FROM dba_users WHERE username = p_user;
    IF v_count = 0 THEN
      EXECUTE IMMEDIATE 'CREATE USER ' || p_user || ' IDENTIFIED BY "' || p_password || '"'
        || ' DEFAULT TABLESPACE USERS'
        || CASE WHEN p_quota THEN ' QUOTA UNLIMITED ON USERS' ELSE ' QUOTA 0 ON USERS' END;
    END IF;
  END;
BEGIN
  create_user('WALLET_AUDIT', '${WALLET_AUDIT_PASSWORD}', TRUE);
  create_user('WALLET_OWNER', '${WALLET_OWNER_PASSWORD}', TRUE);
  create_user('WALLET_APP',   '${WALLET_APP_PASSWORD}',   FALSE);
END;
/

-- Por padrao o Oracle concede INHERIT PRIVILEGES de todo usuario novo a PUBLIC: codigo invoker rights de outro
-- schema chamado por estes usuarios executaria com os privilegios deles. Revogado para os tres.
DECLARE
  e_not_granted EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_not_granted, -1927);
BEGIN
  FOR u IN (SELECT username FROM dba_users WHERE username IN ('WALLET_OWNER', 'WALLET_APP', 'WALLET_AUDIT')) LOOP
    BEGIN
      EXECUTE IMMEDIATE 'REVOKE INHERIT PRIVILEGES ON USER ' || u.username || ' FROM PUBLIC';
    EXCEPTION
      WHEN e_not_granted THEN NULL;
    END;
  END LOOP;
END;
/

-- Dono da trilha de auditoria: so cria os proprios objetos.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE, CREATE TRIGGER TO WALLET_AUDIT;

-- Dono do modelo: tabelas, sequences, package, triggers e views de leitura.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE, CREATE TRIGGER, CREATE VIEW TO WALLET_OWNER;

-- Aplicacao: apenas sessao. Grants de objeto (EXECUTE no package, READ nas views) vem das migrations.
GRANT CREATE SESSION TO WALLET_APP;

EXIT
SQL

echo "01-create-users: usuarios WALLET_OWNER, WALLET_APP e WALLET_AUDIT prontos em FREEPDB1"
)
