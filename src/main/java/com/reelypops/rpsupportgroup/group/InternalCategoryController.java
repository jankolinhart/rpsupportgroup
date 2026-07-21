package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal admin surface for the content-category taxonomy (Cycle 12) on
 * {@code /supportgroup/v1/internal/categories}, authenticated by the shared {@code X-Internal-Api-Key}. The
 * rpadminserver BFF curates the master list here (list / create / delete); deleting a category cascades off every
 * group that carried it and reports how many were affected.
 */
@RestController
@RequestMapping("/supportgroup/v1/internal/categories")
public class InternalCategoryController {

    private final SgCategoryService service;

    public InternalCategoryController(SgCategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return service.list().stream().map(CategoryResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest req) {
        return CategoryResponse.of(service.create(req.slug(), req.label()));
    }

    @DeleteMapping("/{slug}")
    public CategoryDeletionResult delete(@PathVariable String slug) {
        return new CategoryDeletionResult(service.delete(slug));
    }
}
