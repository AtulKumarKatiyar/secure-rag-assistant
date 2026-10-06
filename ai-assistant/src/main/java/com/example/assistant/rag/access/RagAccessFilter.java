package com.example.assistant.rag.access;

import com.example.assistant.rag.ingestion.AccessMetadataKeys;
import com.example.assistant.rag.ingestion.AccessVisibility;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/**
 * Builds the metadata access predicate applied to every RAG retrieval.
 *
 * <p>The predicate is intentionally <strong>fail-closed</strong> and visibility-scoped:
 *
 * <pre>
 * visibility == 'PUBLIC'
 *   OR (visibility == 'TENANT_PRIVATE'         AND tenantId == &lt;caller tenant&gt;)
 *   OR (visibility == 'ENTITLEMENT_RESTRICTED' AND (entitlementGroup == g1 OR ...))
 * </pre>
 *
 * <p>The scoping matters. Entitlement-restricted documents carry an empty {@code tenantId},
 * so a naive {@code tenantId == ''} clause would hand them to every anonymous caller.
 * Each branch therefore requires its own visibility value before the attribute is compared.
 *
 * <p>The expression is assembled as a {@link Filter.Expression} tree rather than a string so
 * that tenant ids and entitlement group names are passed as typed values. They can never be
 * concatenated into the expression and change its meaning.
 */
public final class RagAccessFilter {

    private RagAccessFilter() {
    }

    /**
     * @param tenantId          the authenticated caller's tenant, or blank/null for no tenant
     * @param entitlementGroups the authenticated caller's entitlement groups, may be empty
     * @return a predicate that only admits documents the caller is allowed to see
     */
    public static Filter.Expression forCaller(String tenantId, List<String> entitlementGroups) {
        Filter.Expression predicate = eq(AccessMetadataKeys.VISIBILITY, AccessVisibility.PUBLIC.name());

        if (tenantId != null && !tenantId.isBlank()) {
            predicate = or(predicate, group(and(
                    visibilityIs(AccessVisibility.TENANT_PRIVATE),
                    eq(AccessMetadataKeys.TENANT_ID, tenantId))));
        }

        var groupMatch = entitlementGroupMatch(entitlementGroups);
        if (groupMatch != null) {
            predicate = or(predicate, group(and(
                    visibilityIs(AccessVisibility.ENTITLEMENT_RESTRICTED),
                    groupMatch)));
        }

        return predicate;
    }

    /**
     * The access predicate wrapped in a group, for use as an operand of a surrounding
     * {@code AND}. Without the group the rendered expression would read
     * {@code A or B and ticker == 'X'}, and SpEL binds {@code and} tighter than {@code or}, so the
     * access branches would silently stop applying to the rest of the query.
     */
    public static Filter.Group groupedForCaller(String tenantId, List<String> entitlementGroups) {
        return new Filter.Group(forCaller(tenantId, entitlementGroups));
    }

    private static Filter.Operand entitlementGroupMatch(List<String> entitlementGroups) {
        if (entitlementGroups == null) {
            return null;
        }
        Filter.Operand match = null;
        for (String entitlementGroup : entitlementGroups) {
            if (entitlementGroup == null || entitlementGroup.isBlank()) {
                continue;
            }
            var clause = eq(AccessMetadataKeys.ENTITLEMENT_GROUP, entitlementGroup);
            match = match == null ? clause : or(match, clause);
        }
        return match;
    }

    private static Filter.Expression visibilityIs(AccessVisibility visibility) {
        return eq(AccessMetadataKeys.VISIBILITY, visibility.name());
    }

    private static Filter.Expression eq(String key, String value) {
        return new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key(key), new Filter.Value(value));
    }

    private static Filter.Expression or(Filter.Operand left, Filter.Operand right) {
        return new Filter.Expression(Filter.ExpressionType.OR, left, right);
    }

    private static Filter.Expression and(Filter.Operand left, Filter.Operand right) {
        return new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }

    private static Filter.Group group(Filter.Expression expression) {
        return new Filter.Group(expression);
    }
}
