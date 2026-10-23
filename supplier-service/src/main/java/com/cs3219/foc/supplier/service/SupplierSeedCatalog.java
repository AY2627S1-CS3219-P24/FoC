package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.model.dto.OpeningHoursDto;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;

/** Suppliers and opening hours from the shared CSV catalog. */
final class SupplierSeedCatalog {
    private static final String SUPPLIERS = "seed/supplier-seed-data.csv";
    private static final String OPENING_HOURS = "seed/supplier-opening-hours.csv";

    private SupplierSeedCatalog() {}

    static List<SeedSupplier> load() {
        var supplierRows = rows(SUPPLIERS);
        var hourRows = rows(OPENING_HOURS);
        var supplierHeader = header(supplierRows, SUPPLIERS);
        var hourHeader = header(hourRows, OPENING_HOURS);

        var hoursBySupplier = new HashMap<UUID, List<OpeningHoursDto>>();
        var seenHours = new HashSet<String>();
        for (var row : hourRows.subList(1, hourRows.size())) {
            var supplierId = uuid(value(row, hourHeader, "supplier_id"), OPENING_HOURS);
            var day = day(value(row, hourHeader, "day_of_week"), OPENING_HOURS);
            if (!seenHours.add(supplierId + ":" + day.getValue())) {
                throw new IllegalArgumentException(OPENING_HOURS + " repeats " + supplierId + " on day " + day);
            }
            var opensAt = time(value(row, hourHeader, "opens_at"), OPENING_HOURS);
            var closesAt = time(value(row, hourHeader, "closes_at"), OPENING_HOURS);
            if (opensAt.equals(closesAt)) {
                throw new IllegalArgumentException(
                        OPENING_HOURS + " has the same open and close time for " + supplierId);
            }
            hoursBySupplier
                    .computeIfAbsent(supplierId, ignored -> new ArrayList<>())
                    .add(new OpeningHoursDto(day, opensAt, closesAt));
        }

        var suppliers = new ArrayList<SeedSupplier>();
        var seenSuppliers = new HashSet<UUID>();
        for (var row : supplierRows.subList(1, supplierRows.size())) {
            var id = uuid(value(row, supplierHeader, "id"), SUPPLIERS);
            if (!seenSuppliers.add(id)) {
                throw new IllegalArgumentException(SUPPLIERS + " repeats " + id);
            }
            var hours = hoursBySupplier.remove(id);
            suppliers.add(new SeedSupplier(
                    id,
                    required(value(row, supplierHeader, "name"), "name"),
                    category(value(row, supplierHeader, "category")),
                    required(value(row, supplierHeader, "building"), "building"),
                    blankToNull(value(row, supplierHeader, "floor")),
                    blankToNull(value(row, supplierHeader, "location_description")),
                    coordinate(value(row, supplierHeader, "latitude"), -90, 90),
                    coordinate(value(row, supplierHeader, "longitude"), -180, 180),
                    active(value(row, supplierHeader, "active")),
                    hours == null ? List.of() : List.copyOf(hours)));
        }
        if (!hoursBySupplier.isEmpty()) {
            throw new IllegalArgumentException(
                    OPENING_HOURS + " refers to unknown suppliers " + hoursBySupplier.keySet());
        }
        return List.copyOf(suppliers);
    }

    private static List<List<String>> rows(String path) {
        try {
            var csv = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            return parse(csv);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
    }

    private static Map<String, Integer> header(List<List<String>> rows, String path) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(path + " is empty");
        }
        var header = new HashMap<String, Integer>();
        var columns = rows.getFirst();
        for (var index = 0; index < columns.size(); index++) {
            header.put(columns.get(index), index);
        }
        return header;
    }

    private static String value(List<String> row, Map<String, Integer> header, String column) {
        var index = header.get(column);
        if (index == null || index >= row.size()) {
            throw new IllegalArgumentException("Missing column " + column);
        }
        return row.get(index).strip();
    }

    private static String required(String value, String column) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(column + " is blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    private static UUID uuid(String value, String path) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(path + " has an invalid id " + value, exception);
        }
    }

    private static SupplierCategory category(String value) {
        try {
            return SupplierCategory.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown supplier category " + value, exception);
        }
    }

    private static DayOfWeek day(String value, String path) {
        try {
            return DayOfWeek.of(Integer.parseInt(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + " has an invalid day " + value, exception);
        }
    }

    private static LocalTime time(String value, String path) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + " has an invalid time " + value, exception);
        }
    }

    private static Double coordinate(String value, double minimum, double maximum) {
        if (value.isBlank()) {
            return null;
        }
        var coordinate = Double.valueOf(value);
        if (coordinate < minimum || coordinate > maximum) {
            throw new IllegalArgumentException("Coordinate " + value + " is out of range");
        }
        return coordinate;
    }

    private static boolean active(String value) {
        if (value.equals("true") || value.equals("false")) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("Active must be true or false, got " + value);
    }

    /** RFC 4180 rows, including quoted commas. */
    static List<List<String>> parse(String csv) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        var quoted = false;
        for (var index = 0; index < csv.length(); index++) {
            var character = csv.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n') {
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else if (character != '\r') {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV has an unterminated quote");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    record SeedSupplier(
            UUID id,
            String name,
            SupplierCategory category,
            String building,
            String floor,
            String locationDescription,
            Double latitude,
            Double longitude,
            boolean active,
            List<OpeningHoursDto> openingHours) {}
}
