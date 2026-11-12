-- Maintenance-only provisioning of the replicated coordinator decision store.
-- Preserve the table and its records; runtime must not create or drop it.
CREATE TABLE IF NOT EXISTS starhub_JBossTSTxTable (
  StateType INTEGER NOT NULL,
  Hidden INTEGER NOT NULL,
  TypeName VARCHAR(255) NOT NULL,
  UidString VARCHAR(255) NOT NULL,
  ObjectState BLOB,
  PRIMARY KEY (UidString, TypeName, StateType)
) ENGINE=InnoDB;
-- Bind the existing coordinator UUID only after writers stop, prepared XA
-- transactions are resolved, and the original file store has been verified empty.
CREATE TABLE IF NOT EXISTS starhub_decision_store_identity (
  ID INTEGER NOT NULL PRIMARY KEY,
  STORE_UUID CHAR(36) NOT NULL
) ENGINE=InnoDB;
