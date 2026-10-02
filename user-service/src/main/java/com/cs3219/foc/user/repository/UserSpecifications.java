package com.cs3219.foc.user.repository;

import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import java.util.List;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecifications {
    private UserSpecifications() {}

    public static Specification<User> matchesText(String text) {
        return (root, query, cb) -> cb.or(
                cb.gt(cb.locate(cb.lower(root.get("name")), text), 0),
                cb.gt(cb.locate(cb.lower(root.get("email")), text), 0));
    }

    public static Specification<User> hasRole(UserRole role) {
        return (root, query, cb) ->
                ((HibernateCriteriaBuilder) cb).collectionContains(root.<List<UserRole>>get("roles"), role);
    }

    public static Specification<User> hasFaculty(String faculty) {
        return (root, query, cb) -> cb.equal(root.get("faculty"), faculty);
    }

    public static Specification<User> hasNoFaculty() {
        return (root, query, cb) -> cb.isNull(root.get("faculty"));
    }
}
