\set ECHO none
\set ON_ERROR_STOP on

\getenv database FOC_DATABASE_NAME
\getenv username FOC_DATABASE_USERNAME
\getenv password FOC_DATABASE_PASSWORD
\getenv admin PGUSER

-- Create the role without changing existing credentials.
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'username', :'password'
)
WHERE NOT EXISTS (
    SELECT FROM pg_roles WHERE rolname = :'username'
)
\gexec

-- Reject roles with elevated privileges or membership in other roles.
SELECT EXISTS (
    SELECT FROM pg_roles
    WHERE rolname = :'username'
      AND (rolsuper OR rolcreatedb OR rolcreaterole
           OR rolreplication OR rolbypassrls OR NOT rolcanlogin)
) OR EXISTS (
    SELECT FROM pg_auth_members AS membership
    JOIN pg_roles AS member ON member.oid = membership.member
    WHERE member.rolname = :'username'
) AS unsafe_role
\gset

\if :unsafe_role
    DO $$ BEGIN RAISE EXCEPTION 'Existing service role has unexpected privileges'; END $$;
\endif

-- Creating a database for another owner requires SET ROLE permission.
GRANT :"username" TO :"admin" WITH INHERIT FALSE, SET TRUE;

-- Create the database if missing; psql runs this outside a transaction.
SELECT format(
    'CREATE DATABASE %I OWNER %I',
    :'database', :'username'
)
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = :'database'
)
\gexec

-- Verify database ownership.
SELECT EXISTS (
    SELECT FROM pg_database AS database
    JOIN pg_roles AS owner ON owner.oid = database.datdba
    WHERE database.datname = :'database'
      AND owner.rolname <> :'username'
) AS wrong_owner
\gset

\if :wrong_owner
    DO $$ BEGIN RAISE EXCEPTION 'Existing service database has an unexpected owner'; END $$;
\endif

REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database" TO :"username";

\echo Set up database :database
