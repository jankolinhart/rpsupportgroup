package com.reelypops.rpsupportgroup.group;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A page of browse results (Cycle 12): the vetted configs on this page plus the paging metadata the client needs
 * to drive the carousel (which page, page size, totals, and whether another page follows). Explicit shape so the
 * JSON contract is stable (rather than serialising Spring's {@code Page}).
 */
public record PagedGroups(
        List<GroupResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    static PagedGroups of(Page<SupportGroupConfig> page) {
        return new PagedGroups(
                page.getContent().stream().map(GroupResponse::of).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
