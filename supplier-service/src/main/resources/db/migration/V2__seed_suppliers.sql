INSERT INTO suppliers (id, name, category, building, floor, location_description, latitude, longitude, image_url, active, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'Anna''s x Soup Union', 'FOOD', 'Central Library', '1', 'Next to NUS Co-op', 1.296444, 103.773032, 'https://raw.githubusercontent.com/CS3219-AY2627S1/FoC-Template/main/data/images/ANNA.jpeg', TRUE, now(), now()),
    (gen_random_uuid(), 'NUS Co-op', 'SHOPPING', 'Central Library', '1', 'Inside the library on the right side', 1.2967866, 103.7732677, 'https://raw.githubusercontent.com/CS3219-AY2627S1/FoC-Template/main/data/images/NUS_COOP.jpeg', TRUE, now(), now()),
    (gen_random_uuid(), 'Printer @ Com 2', 'PRINTING', 'COM2', '1', 'Next to LT19', 1.2938347, 103.7744572, 'https://raw.githubusercontent.com/CS3219-AY2627S1/FoC-Template/main/data/images/PRINTER_COM2.jpeg', TRUE, now(), now()),
    (gen_random_uuid(), 'Cool Spot', 'FOOD', 'COM2', '1', 'Opp LT16', 1.2940156, 103.7738478, 'https://raw.githubusercontent.com/CS3219-AY2627S1/FoC-Template/main/data/images/COOL_SPOT.jpeg', TRUE, now(), now()),
    (gen_random_uuid(), 'InstaChef', 'FOOD', 'The Terrace', '1', 'Next to foyer', 1.2938898, 103.7736305, 'https://raw.githubusercontent.com/CS3219-AY2627S1/FoC-Template/main/data/images/INSTACHEF.jpeg', TRUE, now(), now()),
    (gen_random_uuid(), 'Cafe+ Robot Cafe', 'COFFEE', 'Central Library', '1', 'Opp to central library entrance', 1.296444, 103.773032, 'https://raw.githubusercontent.com/CS3219-AY2627S1/FoC-Template/main/data/images/ROBOT_CAFE.jpeg', TRUE, now(), now()),
    (gen_random_uuid(), 'A Hot Hideout', 'FOOD', 'Prince George''s Park', '2', 'Near PGP entrance', 1.2908445, 103.7770891, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Arise and Shine', 'FOOD', 'Engineering Block E4', '4', 'Near LT6', 1.2991517, 103.769064, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Bakehaus / Aurea', 'FOOD', 'The Ridge', '1', 'Near COM2', 1.2946778, 103.7707872, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Central Square @ YIH', 'FOOD', 'Yusof Ishak House', '1', 'Closest to Opp UHC bus stop', 1.2984401, 103.7726256, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Pasta Express', 'FOOD', 'Frontier', '1', 'Aircon section', 1.2947819, 103.7704435, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'TOMORO COFFEE', 'COFFEE', 'Hon Sui Sen Memorial Library', '2', 'Inside HSSML', 1.2931259, 103.7719943, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Octobox', 'SHOPPING', 'Prince George''s Park', '2', 'Near PGP entrance', 1.2904347, 103.7787588, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Smooy', 'FOOD', 'COM3', '1', 'The Terrace @ COM3', 1.2948308, 103.7716305, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Goh Bros E-Print Pte Ltd', 'PRINTING', 'Yusof Ishak House', '5', 'Take the long staircase up YIH', 1.2984905, 103.7720544, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Cheers Unmanned Convenience Store', 'SHOPPING', 'Engineering Block E3', '4', 'Take right from Arise n Shine', 1.2994341, 103.7526298, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Nami', 'FOOD', 'Innovation 4.0', '1', 'Opp TCOMS', 1.2942982, 103.7708813, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Supersnacks', 'FOOD', 'Prince George''s Park', '1', 'At level 1 in Prince George''s Park Residences, Block 10', 1.2913847, 103.7776367, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'Good Day Cafe', 'COFFEE', 'Medicine+Science Library', '1', 'Inside MedScience library', 1.2967989, 103.7794336, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'The Coffee Roaster', 'COFFEE', 'Blk AS8', '1', 'Behind central library bus stop', 1.296252229, 103.7720926, NULL, TRUE, now(), now()),
    (gen_random_uuid(), 'he by He Brews', 'COFFEE', 'Engineering Block EA', '1', 'Near LT7 & Engineering Auditorium', 1.300566804, 103.7707577, NULL, TRUE, now(), now());

INSERT INTO supplier_opening_hours (id, supplier_id, day_of_week, opens_at, closes_at)
SELECT gen_random_uuid(), s.id, d.day, h.opens_at::time, h.closes_at::time
FROM suppliers s
JOIN (VALUES
    ('Anna''s x Soup Union', '09:00', '18:00'),
    ('NUS Co-op', '09:00', '16:00'),
    ('Printer @ Com 2', '00:00', '23:59'),
    ('Cool Spot', '09:00', '21:30'),
    ('InstaChef', '00:00', '23:59'),
    ('Cafe+ Robot Cafe', '00:00', '23:59'),
    ('A Hot Hideout', '11:00', '21:30'),
    ('Arise and Shine', '08:00', '18:00'),
    ('Bakehaus / Aurea', '08:00', '21:00'),
    ('Central Square @ YIH', '08:00', '20:00'),
    ('Pasta Express', '09:30', '19:30'),
    ('TOMORO COFFEE', '08:15', '18:00'),
    ('Octobox', '00:00', '23:59'),
    ('Smooy', '11:00', '21:00'),
    ('Goh Bros E-Print Pte Ltd', '09:00', '18:00'),
    ('Cheers Unmanned Convenience Store', '00:00', '23:59'),
    ('Nami', '08:00', '17:30'),
    ('Supersnacks', '11:00', '02:00'),
    ('Good Day Cafe', '07:30', '18:30'),
    ('The Coffee Roaster', '08:00', '17:30'),
    ('he by He Brews', '08:00', '17:00')
) AS h(name, opens_at, closes_at) ON h.name = s.name
CROSS JOIN generate_series(1, 7) AS d(day);
