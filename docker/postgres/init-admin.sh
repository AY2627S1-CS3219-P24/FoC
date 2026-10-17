#!/bin/sh
set -eu

psql -X --set ON_ERROR_STOP=1 <<'SQL'
\getenv initial_admin_email INITIAL_ADMIN_EMAIL
\getenv initial_admin_name INITIAL_ADMIN_NAME
\getenv initial_admin_password INITIAL_ADMIN_PASSWORD

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users.users (id, email, name, roles, password_hash, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    lower(btrim(:'initial_admin_email')),
    btrim(:'initial_admin_name'),
    ARRAY['USER', 'ADMIN'],
    crypt(:'initial_admin_password', gen_salt('bf', 10)),
    now(),
    now()
)
ON CONFLICT (email) DO NOTHING;
SQL
