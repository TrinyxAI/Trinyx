-- Durable record of who granted which comp plan to whom.
--
-- WHY
-- Admin CREDIT grants stamp "Admin grant by user N" into credit_ledger.description, so the actor
-- of a credit movement is always recoverable. Admin PLAN grants stamped nothing: the PLAN_GRANTED
-- event went only to the SLF4J "AUDIT" logger, and Loki keeps 30 days. When we needed to answer
-- "who put this account on TEAM, and when?" for a grant made on 2026-06-01, the answer had to be
-- inferred from ledger side effects rather than read: no table in the database names the actor.
-- A tier change is a money-affecting, privilege-affecting action; it has to leave a row.
--
-- Deliberately narrow: this records plan grants, not "admin actions" in general. A generic audit
-- table would be speculative, and AuditLogger keeps covering everything else as it does today.
-- Failures are recorded too - a refused grant (has_paid_subscription, forbidden_non_admin) is
-- exactly the kind of attempt worth being able to look up later.
CREATE TABLE IF NOT EXISTS auth.admin_plan_grant_audit (
    id              bigserial PRIMARY KEY,
    actor_user_id   bigint,                 -- null only if the gateway sent no identity
    target_user_id  bigint,
    target_email    varchar(255),           -- as supplied, when the target was named by email
    requested_plan  varchar(32)  NOT NULL,
    previous_plan   varchar(32),
    succeeded       boolean      NOT NULL,
    failure_reason  varchar(64),
    client_ip       varchar(64),
    created_at      timestamptz  NOT NULL DEFAULT now()
);

-- Reading patterns: "everything that touched this account" and "everything this admin did".
CREATE INDEX IF NOT EXISTS idx_apga_target ON auth.admin_plan_grant_audit (target_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_apga_actor  ON auth.admin_plan_grant_audit (actor_user_id, created_at DESC);
