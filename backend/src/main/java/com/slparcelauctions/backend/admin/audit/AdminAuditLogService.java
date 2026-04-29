package com.slparcelauctions.backend.admin.audit;

import com.slparcelauctions.backend.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminActionRepository repo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public Page<AdminAuditLogRow> list(AdminAuditLogFilters filters, int page, int size) {
        Specification<AdminAction> spec = buildSpec(filters);
        Page<AdminAction> rows = repo.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<Long, String> emailById = lookupAdminEmails(rows.getContent());
        return rows.map(a -> toRow(a, emailById));
    }

    @Transactional(readOnly = true)
    public Stream<AdminAuditLogRow> exportCsvStream(AdminAuditLogFilters filters) {
        Specification<AdminAction> spec = buildSpec(filters);
        List<AdminAction> rows = repo.findAll(spec,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<Long, String> emailById = lookupAdminEmails(rows);
        return rows.stream().map(a -> toRow(a, emailById));
    }

    private Specification<AdminAction> buildSpec(AdminAuditLogFilters f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.actionType() != null) {
                predicates.add(cb.equal(root.get("actionType"), f.actionType()));
            }
            if (f.targetType() != null) {
                predicates.add(cb.equal(root.get("targetType"), f.targetType()));
            }
            if (f.adminUserId() != null) {
                predicates.add(cb.equal(root.get("adminUser").get("id"), f.adminUserId()));
            }
            if (f.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), f.to()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("notes")),
                        "%" + f.q().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Map<Long, String> lookupAdminEmails(List<AdminAction> actions) {
        List<Long> adminIds = actions.stream()
                .map(a -> a.getAdminUser().getId()).distinct().toList();
        if (adminIds.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        userRepo.findAllById(adminIds).forEach(u -> result.put(u.getId(), u.getEmail()));
        return result;
    }

    private AdminAuditLogRow toRow(AdminAction a, Map<Long, String> emailById) {
        Long adminId = a.getAdminUser().getId();
        return new AdminAuditLogRow(
                a.getId(),
                a.getCreatedAt(),
                a.getActionType(),
                adminId,
                emailById.getOrDefault(adminId, null),
                a.getTargetType(),
                a.getTargetId(),
                a.getNotes(),
                a.getDetails());
    }
}
