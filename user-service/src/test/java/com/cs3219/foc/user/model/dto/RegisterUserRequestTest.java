package com.cs3219.foc.user.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterUserRequestTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"        ", "\t\t\t\t\t\t\t\t", "\n\n\n\n\n\n\n\n"})
    void rejectsBlankPasswords(String password) {
        var request = new RegisterUserRequest("jamie@example.com", "Jamie", password);
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password")
                        && violation.getConstraintDescriptor().getAnnotation() instanceof NotBlank));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "abcdefg"})
    void rejectsShortPasswords(String password) {
        var request = new RegisterUserRequest("jamie@example.com", "Jamie", password);
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password")
                        && violation.getConstraintDescriptor().getAnnotation() instanceof Size));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefgh", " abcdefgh ", "abcd efgh"})
    void acceptsValidPasswordsWithoutChangingWhitespace(String password) {
        var request = new RegisterUserRequest("jamie@example.com", "Jamie", password);
        assertTrue(validator.validate(request).isEmpty());
        assertEquals(password, request.password());
    }
}
