CREATE TABLE IF NOT EXISTS suppliers (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    building VARCHAR(255) NOT NULL,
    floor VARCHAR(32),
    location_description VARCHAR(500),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    opening_time TIME NOT NULL,
    closing_time TIME NOT NULL,
    image_url VARCHAR(2048),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_suppliers_active_name ON suppliers (active, name);
