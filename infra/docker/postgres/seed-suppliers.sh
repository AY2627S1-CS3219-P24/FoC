#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
seed_dir=${1:-"$script_dir/../../data/csv"}

for file in supplier-seed-data.csv supplier-opening-hours.csv; do
    if [ ! -r "$seed_dir/$file" ]; then
        echo "Supplier seed file is not readable: $seed_dir/$file" >&2
        exit 1
    fi
done

psql -X --set ON_ERROR_STOP=1 --single-transaction --file=- --set seed_dir="$seed_dir" <<'SQL'
\cd :seed_dir

-- Validate the complete input, including entries that already exist in the database.
CREATE TEMP TABLE seed_suppliers (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL CHECK (btrim(name) <> ''),
    category VARCHAR(32) NOT NULL CHECK (category IN ('FOOD', 'COFFEE', 'SHOPPING', 'PRINTING')),
    building VARCHAR(255) NOT NULL CHECK (btrim(building) <> ''),
    floor VARCHAR(32),
    location_description VARCHAR(500),
    latitude DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    active BOOLEAN NOT NULL
) ON COMMIT DROP;

CREATE TEMP TABLE seed_opening_hours (
    supplier_id UUID NOT NULL REFERENCES seed_suppliers (id),
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,
    PRIMARY KEY (supplier_id, day_of_week),
    CHECK (opens_at <> closes_at)
) ON COMMIT DROP;

\copy seed_suppliers FROM 'supplier-seed-data.csv' WITH (FORMAT csv, HEADER MATCH)
\copy seed_opening_hours FROM 'supplier-opening-hours.csv' WITH (FORMAT csv, HEADER MATCH)

-- Existing suppliers and their entire schedules are left untouched.
WITH inserted_suppliers AS (
    INSERT INTO suppliers (
        id, name, category, building, floor, location_description,
        latitude, longitude, active, created_at, updated_at
    )
    SELECT id, name, category, building, floor, location_description,
           latitude, longitude, active, now(), now()
    FROM seed_suppliers
    ORDER BY id
    ON CONFLICT (id) DO NOTHING
    RETURNING id
)
INSERT INTO supplier_opening_hours (id, supplier_id, day_of_week, opens_at, closes_at)
SELECT gen_random_uuid(), h.supplier_id, h.day_of_week, h.opens_at, h.closes_at
FROM seed_opening_hours h
JOIN inserted_suppliers s ON s.id = h.supplier_id;
SQL
