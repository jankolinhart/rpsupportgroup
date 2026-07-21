package com.reelypops.rpsupportgroup.group;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public content-category taxonomy (Cycle 12) on {@code /supportgroup/v1/categories} for the authenticated
 * ReelyPops client — the master list that backs the "choose a support group" filter chips.
 */
@RestController
@RequestMapping("/supportgroup/v1/categories")
public class SgCategoryController {

    private final SgCategoryService service;

    public SgCategoryController(SgCategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return service.list().stream().map(CategoryResponse::of).toList();
    }
}
