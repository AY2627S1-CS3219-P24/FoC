
INSERT INTO suppliers (id, name, category, building, floor, location_description, latitude, longitude,
                       image_url, active, created_at, updated_at)
SELECT gen_random_uuid(), d.name, d.category, d.building, d.floor, d.location_description, d.latitude, d.longitude,
       NULL, d.active, now(), now()
FROM (VALUES
    ('CoffeeBean @ COM3', 'COFFEE', 'COM3', '1', '#01-05, next to the main entrance', 1.2948, 103.7736, TRUE),
    ('Printer 1 @ PGP', 'PRINTING', 'Prince George''s Park', '1', 'Beside the vending machines', 1.2911, 103.7803, TRUE),
    ('Tamarind Hill', 'FOOD', 'UTown', '1', 'Stephen Riady Centre', 1.3050, 103.7730, TRUE),
    ('Late Night Prata @ UTown', 'FOOD', 'UTown', '1', 'Town Plaza, near the fountain', 1.3044, 103.7736, TRUE),
    ('Old Kiosk @ YIH', 'SHOPPING', 'Yusof Ishak House', '1', 'Closed for renovation', 1.2985, 103.7725, FALSE),
    ('Frontier Drinks Stall', 'COFFEE', 'Frontier', '1', 'Stall 12', 1.2966, 103.7802, FALSE)
) AS d(name, category, building, floor, location_description, latitude, longitude, active)
WHERE NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.name = d.name);

-- Days not listed are closed. day_of_week: 1 = Monday ... 7 = Sunday.
INSERT INTO supplier_opening_hours (id, supplier_id, day_of_week, opens_at, closes_at)
SELECT gen_random_uuid(), s.id, h.day_of_week, h.opens_at, h.closes_at
FROM (
    SELECT 'CoffeeBean @ COM3' AS name, d AS day_of_week, TIME '08:00' AS opens_at, TIME '20:00' AS closes_at
    FROM generate_series(1, 5) d
    UNION ALL SELECT 'CoffeeBean @ COM3', 6, TIME '10:00', TIME '16:00'
    UNION ALL SELECT 'Printer 1 @ PGP', d, TIME '00:00', TIME '23:59' FROM generate_series(1, 7) d
    UNION ALL SELECT 'Tamarind Hill', d, TIME '11:00', TIME '21:00' FROM generate_series(1, 6) d
    UNION ALL SELECT 'Late Night Prata @ UTown', d, TIME '18:00', TIME '03:00' FROM generate_series(4, 7) d
    UNION ALL SELECT 'Old Kiosk @ YIH', d, TIME '09:00', TIME '17:00' FROM generate_series(1, 5) d
    UNION ALL SELECT 'Frontier Drinks Stall', d, TIME '07:30', TIME '15:00' FROM generate_series(1, 5) d
) AS h
JOIN suppliers s ON s.name = h.name
WHERE NOT EXISTS (
    SELECT 1 FROM supplier_opening_hours o WHERE o.supplier_id = s.id AND o.day_of_week = h.day_of_week
);
