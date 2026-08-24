-- The application migrations own their ten per-service schemas.
-- Keycloak shares the dedicated Cloud PostgreSQL instance but not an app schema.
CREATE SCHEMA IF NOT EXISTS keycloak;
