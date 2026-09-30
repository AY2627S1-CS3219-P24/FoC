package com.cs3219.foc.supplier.controller;

import com.cs3219.foc.supplier.model.dto.SupplierDto;
import com.cs3219.foc.supplier.model.dto.SupplierRequest;
import com.cs3219.foc.supplier.model.dto.SupplierSearchCriteria;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import com.cs3219.foc.supplier.service.SupplierService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {
    private final SupplierService supplierService;

    /**
     * Searches suppliers, all filters are optional and combined with AND, eg:
     * {@code /suppliers?q=coffee&category=FOOD&category=COFFEE&openNow=true}. Inactive suppliers are only returned
     * to admins when purposely chose that option.
     */
    @GetMapping
    public List<SupplierDto> listSuppliers(
            @RequestParam(required = false) String q,
            @RequestParam(name = "category", required = false) List<SupplierCategory> categories,
            @RequestParam(defaultValue = "false") boolean openNow,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            Authentication authentication) {
        var criteria = new SupplierSearchCriteria(
                q, categories == null ? List.of() : categories, openNow, includeInactive && isAdmin(authentication));
        return supplierService.listSuppliers(criteria);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierDto createSupplier(@Valid @RequestBody SupplierRequest request) {
        return supplierService.createSupplier(request);
    }

    @GetMapping("/{id}")
    public SupplierDto getSupplier(@PathVariable UUID id, Authentication authentication) {
        return supplierService.getSupplier(id, isAdmin(authentication));
    }

    @PutMapping("/{id}")
    public SupplierDto updateSupplier(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.updateSupplier(id, request);
    }

    @PostMapping("/{id}/deactivate")
    public SupplierDto deactivateSupplier(@PathVariable UUID id) {
        return supplierService.setSupplierActive(id, false);
    }

    @PostMapping("/{id}/activate")
    public SupplierDto activateSupplier(@PathVariable UUID id) {
        return supplierService.setSupplierActive(id, true);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
