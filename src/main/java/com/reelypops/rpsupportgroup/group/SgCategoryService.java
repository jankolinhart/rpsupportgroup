package com.reelypops.rpsupportgroup.group;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Content-category taxonomy (Cycle 12, §13.11): the admin curates a fixed master list of categories and assigns
 * them to support groups. Categories are config metadata (not round truth), admin-owned and not vet-locked; the
 * client reads them only (browse filter / search). Deleting a category unassigns it from every group.
 */
@Service
public class SgCategoryService {

    private final SgCategoryRepository categories;
    private final SupportGroupConfigRepository configs;

    public SgCategoryService(SgCategoryRepository categories, SupportGroupConfigRepository configs) {
        this.categories = categories;
        this.configs = configs;
    }

    @Transactional(readOnly = true)
    public List<SgCategory> list() {
        return categories.findAllByOrderByLabelAsc();
    }

    /** Add a category to the master taxonomy. The slug is normalised (lower-cased); a duplicate slug is a conflict. */
    @Transactional
    public SgCategory create(String slug, String label) {
        String normalized = normalize(slug);
        if (categories.existsBySlug(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "category already exists: " + normalized);
        }
        return categories.save(SgCategory.of(normalized, label.trim()));
    }

    /**
     * Remove a category from the master taxonomy: unassign it from every group that carries it, then delete the
     * master row. Returns how many groups were affected so the admin UI can confirm the impact.
     */
    @Transactional
    public int delete(String slug) {
        String normalized = normalize(slug);
        SgCategory category = requireCategory(normalized);
        List<SupportGroupConfig> affected = configs.findByCategories_Slug(normalized);
        affected.forEach(c -> c.unassignCategory(normalized));
        categories.delete(category);
        return affected.size();
    }

    /** Assign a category to a group (idempotent). Both the group and the category must exist. */
    @Transactional
    public void assign(String igAccount, String slug) {
        SgCategory category = requireCategory(normalize(slug));
        requireConfig(igAccount).assignCategory(category);
    }

    /** Unassign a category from a group (idempotent). The group must exist; an unknown slug is a no-op. */
    @Transactional
    public void unassign(String igAccount, String slug) {
        requireConfig(igAccount).unassignCategory(normalize(slug));
    }

    private SgCategory requireCategory(String slug) {
        return categories.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no category: " + slug));
    }

    private SupportGroupConfig requireConfig(String igAccount) {
        return configs.findByIgAccount(igAccount)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no config for " + igAccount));
    }

    private static String normalize(String slug) {
        return slug.trim().toLowerCase();
    }
}
