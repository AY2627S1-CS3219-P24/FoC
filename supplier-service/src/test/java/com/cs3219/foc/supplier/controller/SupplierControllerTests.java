package com.cs3219.foc.supplier.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cs3219.foc.supplier.config.SecurityConfig;
import com.cs3219.foc.supplier.exception.GlobalExceptionHandler;
import com.cs3219.foc.supplier.exception.SupplierNotFoundException;
import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import com.cs3219.foc.supplier.service.SupplierService;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(SupplierControllerTests.TestConfig.class)
class SupplierControllerTests {
    private static final String VALID_BODY = """
            {"name":"Cool Spot","category":"FOOD","building":"COM2","floor":"1",
             "locationDescription":"Opp LT16","latitude":1.294,"longitude":103.7738,
             "openingTime":"09:00","closingTime":"21:30","imageUrl":null}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SupplierService service;

    private MockMvc mvc;
    private final UUID supplierId = UUID.randomUUID();
    private SupplierDto supplier;

    @BeforeEach
    void setUp() {
        reset(service);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        supplier = new SupplierDto(
                supplierId,
                "Cool Spot",
                SupplierCategory.FOOD,
                "COM2",
                "1",
                "Opp LT16",
                1.294,
                103.7738,
                LocalTime.of(9, 0),
                LocalTime.of(21, 30),
                null,
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private static JwtRequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/suppliers")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void userListsOnlyActiveSuppliersEvenWhenAskingForInactive() throws Exception {
        when(service.listSuppliers(false)).thenReturn(List.of(supplier));
        mvc.perform(get("/suppliers").param("includeInactive", "true").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Cool Spot"))
                .andExpect(jsonPath("$[0].openingTime").value("09:00:00"));
        verify(service).listSuppliers(false);
    }

    @Test
    void adminCanListInactiveSuppliers() throws Exception {
        when(service.listSuppliers(true)).thenReturn(List.of(supplier));
        mvc.perform(get("/suppliers").param("includeInactive", "true").with(admin()))
                .andExpect(status().isOk());
        verify(service).listSuppliers(true);
    }

    @Test
    void returnsNotFoundForMissingSupplier() throws Exception {
        when(service.getSupplier(supplierId, false)).thenThrow(new SupplierNotFoundException(supplierId));
        mvc.perform(get("/suppliers/{id}", supplierId).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Supplier not found: " + supplierId));
    }

    @Test
    void userCannotChangeSuppliers() throws Exception {
        mvc.perform(post("/suppliers")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/suppliers/{id}", supplierId)
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/suppliers/{id}/deactivate", supplierId).with(user())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminCreatesSupplier() throws Exception {
        when(service.createSupplier(any())).thenReturn(supplier);
        mvc.perform(post("/suppliers")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(supplierId.toString()));
        verify(service)
                .createSupplier(new SupplierRequest(
                        "Cool Spot",
                        SupplierCategory.FOOD,
                        "COM2",
                        "1",
                        "Opp LT16",
                        1.294,
                        103.7738,
                        LocalTime.of(9, 0),
                        LocalTime.of(21, 30),
                        null));
    }

    @Test
    void rejectsInvalidSupplier() throws Exception {
        mvc.perform(post("/suppliers")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","category":"FOOD","building":"COM2","latitude":91,
                                 "openingTime":"09:00","closingTime":"21:30","imageUrl":"javascript:alert(1)"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(service);
    }

    @Test
    void adminUpdatesAndDeactivatesSupplier() throws Exception {
        when(service.updateSupplier(eq(supplierId), any())).thenReturn(supplier);
        when(service.setSupplierActive(supplierId, false)).thenReturn(supplier);
        mvc.perform(put("/suppliers/{id}", supplierId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/suppliers/{id}/deactivate", supplierId).with(admin()))
                .andExpect(status().isOk());
        verify(service).setSupplierActive(supplierId, false);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SupplierController.class, SecurityConfig.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean
        SupplierService supplierService() {
            return mock(SupplierService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
