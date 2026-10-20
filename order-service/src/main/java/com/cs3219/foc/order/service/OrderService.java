package com.cs3219.foc.order.service;

import static com.cs3219.foc.order.model.entity.CreditState.*;
import static com.cs3219.foc.order.model.entity.ErrandStatus.*;

import com.cs3219.foc.order.config.OrderProperties;
import com.cs3219.foc.order.exception.*;
import com.cs3219.foc.order.infrastructure.client.CreditClient;
import com.cs3219.foc.order.infrastructure.client.SupplierClient;
import com.cs3219.foc.order.infrastructure.client.UserClient;
import com.cs3219.foc.order.mapper.ErrandMapper;
import com.cs3219.foc.order.model.dto.*;
import com.cs3219.foc.order.model.dto.event.*;
import com.cs3219.foc.order.model.entity.CreditState;
import com.cs3219.foc.order.model.entity.Errand;
import com.cs3219.foc.order.model.entity.ErrandStatus;
import com.cs3219.foc.order.repository.ErrandRepository;
import com.cs3219.foc.order.security.CurrentUser;
import com.cs3219.foc.order.security.CurrentUserService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class OrderService {
    private final ErrandRepository repository;
    private final CurrentUserService currentUserService;
    private final CreditClient creditClient;
    private final SupplierClient supplierClient;
    private final UserClient userClient;
    private final ErrandMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final OrderProperties properties;
    private final TransactionTemplate transactions;

    public OrderService(
            ErrandRepository repository,
            CurrentUserService currentUserService,
            CreditClient creditClient,
            SupplierClient supplierClient,
            UserClient userClient,
            ErrandMapper mapper,
            ApplicationEventPublisher events,
            Clock clock,
            OrderProperties properties,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.creditClient = creditClient;
        this.supplierClient = supplierClient;
        this.userClient = userClient;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public CreateErrandResponse create(CreateErrandRequest request) {
        UUID actor = currentUserService.requireCurrentUserId();
        Optional<Errand> existing = transactions.execute(
                tx -> repository.findByRequesterIdAndCreateRequestId(actor, request.createRequestId()));
        if (existing.isPresent()) return creationResponse(matching(existing.get(), request));

        // Account and supplier HTTP run before the write transaction, only for a new request.
        creditClient.requireAccountReady();
        var supplier = supplierClient.get(request.supplierId());
        if (supplier == null || !request.supplierId().equals(supplier.id()) || !supplier.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier is not available");
        }
        var address = Stream.of(supplier.building(), supplier.floor(), supplier.locationDescription())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
        if (address.length() > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pickup address is too long");

        Errand created;
        try {
            created = transactions.execute(tx -> {
                var now = now();
                var errand = new Errand();
                errand.setId(UUID.randomUUID());
                errand.setRequesterId(actor);
                errand.setSupplierId(supplier.id());
                errand.setPickupName(supplier.name());
                errand.setPickupAddress(address);
                errand.setDeliveryLocation(request.deliveryLocation());
                errand.setDescription(request.description());
                errand.setNotes(request.notes());
                errand.setCreditReward(request.creditReward());
                errand.setCreateRequestId(request.createRequestId());
                errand.setStatus(CREATING);
                errand.setCreatedAt(now);
                errand.setUpdatedAt(now);
                errand.setExpiresAt(now.plusMinutes(request.expiryMinutes()));
                repository.saveAndFlush(errand);
                return errand;
            });
        } catch (DataIntegrityViolationException conflict) {
            // The losing insert is rolled back before reading the winning request in a fresh transaction.
            Optional<Errand> original = transactions.execute(
                    tx -> repository.findByRequesterIdAndCreateRequestId(actor, request.createRequestId()));
            if (original.isEmpty()) throw conflict;
            return creationResponse(matching(original.get(), request));
        }

        // The durable record commits before HTTP. Concurrent repeats return it without reserving again.
        try {
            CreditReservationResponse reservation = creditClient.reserve(
                    new ReserveCreditRequest(created.getId(), created.getId(), created.getCreditReward()));
            if (reservation == null
                    || reservation.outcome() == null
                    || !Set.of("RESERVED", "INSUFFICIENT_BALANCE").contains(reservation.outcome())) {
                throw new RestClientException("Credit returned an invalid reservation outcome");
            }
            Errand finalized = transactions.execute(tx -> {
                Errand errand = locked(created.getId());
                if ("INSUFFICIENT_BALANCE".equals(reservation.outcome())) {
                    errand.setStatus(CREATE_FAILED);
                    errand.setCreationFailureCode(reservation.outcome());
                    errand.setEndedAt(now());
                    errand.setUpdatedAt(errand.getEndedAt());
                    repository.saveAndFlush(errand);
                } else if (!now().isBefore(errand.getExpiresAt())) {
                    expire(errand);
                } else {
                    errand.setStatus(OPEN);
                    errand.setCreditState(RESERVED);
                    errand.setUpdatedAt(now());
                    repository.saveAndFlush(errand);
                }
                return errand;
            });
            return creationResponse(finalized);
        } catch (RuntimeException failure) {
            // HTTP failure cannot establish whether Credit committed. Never mark an unknown result as failed.
            log.error(
                    "Order creation needs investigation errandId={} requesterId={} createRequestId={}",
                    created.getId(),
                    actor,
                    request.createRequestId(),
                    failure);
            throw failure;
        }
    }

    public PageDto<ErrandDto> list(UUID requesterId, Pageable pageable) {
        CurrentUser actor = currentUserService.requireCurrentUser();
        validatePageable(pageable);
        if (requesterId != null && !actor.hasRole("ADMIN")) throw new AccessDeniedException("Admin query required");
        Page<Errand> page = transactions.execute(tx -> requesterId == null
                ? repository.findAvailable(actor.userId(), now(), stable(pageable))
                : repository.findByRequesterId(requesterId, stable(pageable)));
        return page(page);
    }

    public PageDto<ErrandDto> mine(String as, Pageable pageable) {
        UUID actor = currentUserService.requireCurrentUserId();
        validatePageable(pageable);
        if (!Set.of("requester", "courier").contains(as)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "as must be requester or courier");
        }
        Page<Errand> page = transactions.execute(tx -> as.equals("requester")
                ? repository.findByRequesterId(actor, stable(pageable))
                : repository.findByCourierId(actor, stable(pageable)));
        return page(page);
    }

    public ErrandDto get(UUID id) {
        CurrentUser actor = currentUserService.requireCurrentUser();
        Errand errand =
                transactions.execute(tx -> repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id)));
        if (!actor.hasRole("ADMIN")
                && !actor.userId().equals(errand.getRequesterId())
                && !actor.userId().equals(errand.getCourierId())
                && !(errand.getStatus() == OPEN && now().isBefore(errand.getExpiresAt()))) {
            throw new AccessDeniedException("Order is not visible to this user");
        }
        return dto(errand);
    }

    public ErrandActionResponse accept(UUID id) {
        UUID actor = currentUserService.requireCurrentUserId();
        creditClient.requireAccountReady();
        Errand errand = transactions.execute(tx -> {
            var lockedErrand = locked(id);
            if (actor.equals(lockedErrand.getRequesterId()))
                throw new AccessDeniedException("Cannot accept your own order");
            if (lockedErrand.getStatus() != OPEN || !now().isBefore(lockedErrand.getExpiresAt())) {
                throw new OrderConflictException("Order is not open for acceptance");
            }
            var now = now();
            lockedErrand.setCourierId(actor);
            lockedErrand.setStatus(ACCEPTED);
            lockedErrand.setAcceptedAt(now);
            lockedErrand.setUpdatedAt(now);
            return repository.saveAndFlush(lockedErrand);
        });
        return mapper.toActionResponse(errand);
    }

    public ErrandActionResponse pickUp(UUID id) {
        UUID actor = currentUserService.requireCurrentUserId();
        Errand errand = transactions.execute(tx -> {
            var lockedErrand = locked(id);
            if (!actor.equals(lockedErrand.getCourierId()))
                throw new AccessDeniedException("Only the assigned courier can pick up");
            if (lockedErrand.getStatus() != ACCEPTED)
                throw new OrderConflictException("Order must be accepted before pickup");
            var now = now();
            lockedErrand.setStatus(PICKED_UP);
            lockedErrand.setPickedUpAt(now);
            lockedErrand.setUpdatedAt(now);
            return repository.saveAndFlush(lockedErrand);
        });
        return mapper.toActionResponse(errand);
    }

    public ErrandActionResponse complete(UUID id) {
        UUID actor = currentUserService.requireCurrentUserId();
        Errand errand = transactions.execute(tx -> {
            var lockedErrand = locked(id);
            requireRequester(lockedErrand, actor);
            if (lockedErrand.getStatus() == COMPLETED) return lockedErrand;
            if (lockedErrand.getStatus() != PICKED_UP)
                throw new OrderConflictException("Order must be picked up before completion");
            end(lockedErrand, COMPLETED, SETTLEMENT_PENDING);
            events.publishEvent(new ErrandCompletedEvent(
                    lockedErrand.getSettlementEventId(),
                    "ErrandCompletedEvent",
                    1,
                    lockedErrand.getEndedAt(),
                    new ErrandCompletedEvent.Payload(
                            id,
                            lockedErrand.getRequesterId(),
                            lockedErrand.getCreditReward(),
                            lockedErrand.getCourierId())));
            return lockedErrand;
        });
        return mapper.toActionResponse(errand);
    }

    public ErrandActionResponse cancel(UUID id) {
        UUID actor = currentUserService.requireCurrentUserId();
        Errand errand = transactions.execute(tx -> {
            var lockedErrand = locked(id);
            requireRequester(lockedErrand, actor);
            if (lockedErrand.getStatus() == CANCELLED) return lockedErrand;
            if (lockedErrand.getStatus() != OPEN) throw new OrderConflictException("Only open orders can be cancelled");
            end(lockedErrand, CANCELLED, RELEASE_PENDING);
            events.publishEvent(new ErrandCancelledEvent(
                    lockedErrand.getSettlementEventId(),
                    "ErrandCancelledEvent",
                    1,
                    lockedErrand.getEndedAt(),
                    new ErrandCancelledEvent.Payload(id, actor, lockedErrand.getCreditReward())));
            return lockedErrand;
        });
        return mapper.toActionResponse(errand);
    }

    public void expireOpenOrders() {
        List<UUID> ids = transactions.execute(
                tx -> repository.findExpiredIds(OPEN, now(), PageRequest.of(0, properties.expiryBatchSize())));
        for (var id : ids) {
            transactions.executeWithoutResult(
                    tx -> repository.findForUpdateById(id).ifPresent(errand -> {
                        if (errand.getStatus() == OPEN && !now().isBefore(errand.getExpiresAt())) expire(errand);
                    }));
        }
    }

    public void handleSettlementResult(CreditSettlementResultEvent event) {
        var payload = event.payload();
        transactions.executeWithoutResult(tx -> {
            var errand = locked(payload.errandId());
            boolean payout = errand.getStatus() == COMPLETED;
            boolean refund = errand.getStatus() == CANCELLED || errand.getStatus() == EXPIRED;
            checkResult(
                    (payout || refund)
                            && Objects.equals(errand.getSettlementEventId(), payload.operationId())
                            && Objects.equals(errand.getRequesterId(), payload.requesterId())
                            && errand.getCreditReward() == payload.amount()
                            && Objects.equals(payload.courierId(), payout ? errand.getCourierId() : null)
                            && Objects.equals(payload.settlementType(), payout ? "PAYOUT" : "REFUND"),
                    errand,
                    payload.operationId());
            if (Set.of("RESERVATION_MISMATCH", "NO_RESERVATION", "TERMINAL_CONFLICT")
                    .contains(payload.outcome())) {
                log.error(
                        "Credit settlement conflict errandId={} operationId={} outcome={}",
                        errand.getId(),
                        payload.operationId(),
                        payload.outcome());
                return;
            }
            checkResult(Objects.equals(payload.outcome(), payout ? "PAID" : "REFUNDED"), errand, payload.operationId());
            CreditState target = payout ? SETTLED : RELEASED;
            if (errand.getCreditState() == target) return;
            checkResult(
                    errand.getCreditState() == (payout ? SETTLEMENT_PENDING : RELEASE_PENDING),
                    errand,
                    payload.operationId());
            errand.setCreditState(target);
            errand.setUpdatedAt(now());
            repository.saveAndFlush(errand);
        });
    }

    private void expire(Errand errand) {
        end(errand, EXPIRED, RELEASE_PENDING);
        events.publishEvent(new ErrandExpiredEvent(
                errand.getSettlementEventId(),
                "ErrandExpiredEvent",
                1,
                errand.getEndedAt(),
                new ErrandExpiredEvent.Payload(errand.getId(), errand.getRequesterId(), errand.getCreditReward())));
    }

    private void end(Errand errand, ErrandStatus status, CreditState creditState) {
        var now = now();
        errand.setStatus(status);
        errand.setCreditState(creditState);
        errand.setSettlementEventId(UUID.randomUUID());
        errand.setEndedAt(now);
        errand.setUpdatedAt(now);
        repository.saveAndFlush(errand);
    }

    private Errand matching(Errand errand, CreateErrandRequest r) {
        if (!Objects.equals(errand.getSupplierId(), r.supplierId())
                || !Objects.equals(errand.getDeliveryLocation(), r.deliveryLocation())
                || !Objects.equals(errand.getDescription(), r.description())
                || !Objects.equals(errand.getNotes(), r.notes())
                || errand.getCreditReward() != r.creditReward()
                || !errand.getExpiresAt()
                        .toInstant()
                        .equals(errand.getCreatedAt()
                                .plusMinutes(r.expiryMinutes())
                                .toInstant())) {
            throw new OrderConflictException("Creation request ID was already used with different content");
        }
        return errand;
    }

    private CreateErrandResponse creationResponse(Errand errand) {
        return new CreateErrandResponse(
                errand.getId(), errand.getStatus(), errand.getCreationFailureCode(), "/api/orders/" + errand.getId());
    }

    private Errand locked(UUID id) {
        return repository.findForUpdateById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private void requireRequester(Errand errand, UUID actor) {
        if (!actor.equals(errand.getRequesterId()))
            throw new AccessDeniedException("Only the requester can perform this action");
    }

    private void checkResult(boolean matches, Errand errand, UUID operationId) {
        if (!matches) {
            log.error(
                    "Credit result does not match errandId={} operationId={} status={}",
                    errand.getId(),
                    operationId,
                    errand.getStatus());
            throw new IllegalArgumentException(
                    "Credit result does not match order " + errand.getId() + " operation " + operationId);
        }
    }

    private ErrandDto dto(Errand errand) {
        return dtos(List.of(errand)).getFirst();
    }

    private List<ErrandDto> dtos(List<Errand> errands) {
        Set<UUID> ids = errands.stream()
                .flatMap(errand -> Stream.of(errand.getRequesterId(), errand.getCourierId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserBriefDto> names = userClient.find(ids);
        return errands.stream()
                .map(errand -> mapper.toDto(
                        errand,
                        names.getOrDefault(errand.getRequesterId(), new UserBriefDto(errand.getRequesterId(), null)),
                        errand.getCourierId() == null
                                ? null
                                : names.getOrDefault(
                                        errand.getCourierId(), new UserBriefDto(errand.getCourierId(), null))))
                .toList();
    }

    private PageDto<ErrandDto> page(Page<Errand> page) {
        return new PageDto<>(dtos(page.getContent()), page.getTotalElements(), page.getNumber(), page.getSize());
    }

    private Pageable stable(Pageable page) {
        return PageRequest.of(
                page.getPageNumber(),
                page.getPageSize(),
                page.getSort().getOrderFor("id") == null
                        ? page.getSort().and(Sort.by(Sort.Direction.DESC, "id"))
                        : page.getSort());
    }

    private void validatePageable(Pageable page) {
        if (page.isUnpaged() || page.getPageSize() > 100 || page.getPageSize() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be 1-100");
        }
        for (var order : page.getSort()) {
            if (!Set.of("id", "createdAt", "expiresAt").contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported order sort field");
            }
        }
    }
}
