package com.cs3219.foc.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cs3219.foc.supplier.exception.SupplierImageException;
import com.cs3219.foc.supplier.infrastructure.storage.SupplierImageStorage;
import com.cs3219.foc.supplier.mapper.SupplierMapperImpl;
import com.cs3219.foc.supplier.model.entity.Supplier;
import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import com.cs3219.foc.supplier.repository.SupplierRepository;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SupplierImageServiceTests {
    private static final String OLD_KEY = "11111111-1111-1111-1111-111111111111.jpg";

    private final UUID supplierId = UUID.randomUUID();
    private final MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1});
    private SupplierRepository repository;
    private SupplierImageStorage storage;
    private SupplierImageService service;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        // Stand in for the transaction so after-commit and after-rollback callbacks can be triggered.
        TransactionSynchronizationManager.initSynchronization();
        repository = mock(SupplierRepository.class);
        storage = mock(SupplierImageStorage.class);
        var processor = mock(SupplierImageProcessor.class);
        when(processor.process(any())).thenReturn(new byte[] {9});
        supplier = Supplier.builder()
                .id(supplierId)
                .name("Cool Spot")
                .category(SupplierCategory.FOOD)
                .building("COM2")
                .openingHours(new ArrayList<>())
                .build();
        when(repository.findById(supplierId)).thenReturn(Optional.of(supplier));
        service = new SupplierImageService(repository, new SupplierMapperImpl(), storage, processor);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void finishTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }

    @Test
    void uploadStoresANewFileAndServesItFromThisService() {
        var dto = service.upload(supplierId, file);
        assertThat(supplier.getImageKey()).matches("[0-9a-f-]{36}\\.jpg");
        verify(storage).write(eq(supplier.getImageKey()), eq(new byte[] {9}));
        assertThat(dto.imageUrl())
                .isEqualTo("/api/suppliers/" + supplierId + "/image?v="
                        + supplier.getImageKey().substring(0, 8));
    }

    @Test
    void replacedFileIsDeletedOnlyAfterCommit() {
        supplier.setImageKey(OLD_KEY);
        service.upload(supplierId, file);
        verify(storage, never()).delete(OLD_KEY);
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).delete(OLD_KEY);
    }

    @Test
    void newFileIsDeletedWhenTheTransactionRollsBack() {
        supplier.setImageKey(OLD_KEY);
        service.upload(supplierId, file);
        var newKey = supplier.getImageKey();
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).delete(newKey);
        verify(storage, never()).delete(OLD_KEY);
    }

    @Test
    void removeClearsTheUploadedImage() {
        supplier.setImageKey(OLD_KEY);
        var dto = service.remove(supplierId);
        assertThat(dto.imageUrl()).isNull();
        assertThat(supplier.getImageKey()).isNull();
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).delete(OLD_KEY);
    }

    @Test
    void readingASupplierWithoutAnUploadedImageIsNotFound() {
        assertThatThrownBy(() -> service.read(supplierId)).isInstanceOf(SupplierImageException.class);
        verify(storage, never()).read(anyString());
    }
}
