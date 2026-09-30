package com.cs3219.foc.supplier.repository;

import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Composable filters for {@link SupplierRepository#findAll(Specification, org.springframework.data.domain.Sort)}. */
public final class SupplierSpecifications {
    private static final char LIKE_ESCAPE_CHAR = '\\'; // when user is input some char for searching, need to treat it as normal text

    private SupplierSpecifications() {}

    public static Specification<Supplier> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    /** case-insensitive substring matches name, building or location description. */
    public static Specification<Supplier> matchesText(String text) {
        var pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE_CHAR),
                cb.like(cb.lower(root.get("building")), pattern, LIKE_ESCAPE_CHAR),
                cb.like(cb.lower(root.get("locationDescription")), pattern, LIKE_ESCAPE_CHAR));
    }

    public static Specification<Supplier> inCategories(Collection<SupplierCategory> categories) {
        return (root, query, cb) -> root.get("category").in(categories);
    }

    /**
     * Suppliers open at {@code time}. Opening time is inclusive, closing time is exclusive. A closing time earlier
     * than the opening time means the supplier closes after midnight.
     */
    public static Specification<Supplier> openAt(LocalTime time) {
        return (root, query, cb) -> {
            var opening = root.<LocalTime>get("openingTime");
            var closing = root.<LocalTime>get("closingTime");
            var sameDay = cb.and(
                    cb.lessThanOrEqualTo(opening, closing),
                    cb.lessThanOrEqualTo(opening, time),
                    cb.greaterThan(closing, time));
            var overnight = cb.and(
                    cb.greaterThan(opening, closing),
                    cb.or(cb.lessThanOrEqualTo(opening, time), cb.greaterThan(closing, time)));
            return cb.or(sameDay, overnight);
        };
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
