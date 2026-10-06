package com.example.assistant.rag.access;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.converter.SimpleVectorStoreFilterExpressionConverter;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluates the access predicate the same way {@code SimpleVectorStore} does at query time, by
 * converting it to SpEL and running it against document metadata.
 *
 * <p>Asserting on the rendered expression alone would not prove the isolation behaviour, so these
 * tests execute the predicate.
 */
class RagAccessFilterTest {

    private static final String CLIENT_A = "clientA";
    private static final String CLIENT_B = "clientB";
    private static final String PREMIUM = "premium-research";

    @Test
    void publicDocumentIsVisibleToEveryone() {
        var publicDoc = Map.<String, Object>of(
                "visibility", "PUBLIC",
                "tenantId", "");

        assertThat(admits(null, List.of(), publicDoc)).isTrue();
        assertThat(admits(CLIENT_A, List.of(), publicDoc)).isTrue();
    }

    @Test
    void tenantPrivateDocumentIsVisibleOnlyToItsTenant() {
        var clientADoc = Map.<String, Object>of(
                "visibility", "TENANT_PRIVATE",
                "tenantId", CLIENT_A);

        assertThat(admits(CLIENT_A, List.of(), clientADoc)).isTrue();
        assertThat(admits(CLIENT_B, List.of(), clientADoc)).isFalse();
        assertThat(admits(null, List.of(), clientADoc)).isFalse();
    }

    @Test
    void entitlementDocumentRequiresTheGroup() {
        var premiumDoc = Map.<String, Object>of(
                "visibility", "ENTITLEMENT_RESTRICTED",
                "tenantId", "",
                "entitlementGroup", PREMIUM);

        assertThat(admits(CLIENT_A, List.of(PREMIUM), premiumDoc)).isTrue();
        assertThat(admits(CLIENT_A, List.of(), premiumDoc)).isFalse();
        assertThat(admits(null, List.of(), premiumDoc)).isFalse();
    }

    /**
     * Regression test. Entitlement-restricted documents carry an empty {@code tenantId}, so a
     * predicate of the shape {@code PUBLIC || tenantId == ''} would hand every one of them to an
     * anonymous caller. Each branch must be scoped by its own visibility value.
     */
    @Test
    void anonymousCallerCannotReachEntitlementDocumentsViaEmptyTenant() {
        var premiumDoc = Map.<String, Object>of(
                "visibility", "ENTITLEMENT_RESTRICTED",
                "tenantId", "",
                "entitlementGroup", PREMIUM);

        assertThat(admits("", List.of(), premiumDoc)).isFalse();
        assertThat(admits(" ", List.of(), premiumDoc)).isFalse();
    }

    @Test
    void tenantCallerWithoutEntitlementCannotReachEntitlementDocuments() {
        var premiumDoc = Map.<String, Object>of(
                "visibility", "ENTITLEMENT_RESTRICTED",
                "tenantId", "",
                "entitlementGroup", PREMIUM);

        assertThat(admits(CLIENT_A, List.of(), premiumDoc)).isFalse();
        assertThat(admits(CLIENT_A, List.of("other-group"), premiumDoc)).isFalse();
    }

    @Test
    void blankTenantOmitsTheTenantBranchEntirely() {
        var rendered = render(null, List.of());

        assertThat(rendered).contains("'PUBLIC'");
        assertThat(rendered).doesNotContain("'TENANT_PRIVATE'");
        assertThat(rendered).doesNotContain("'tenantId'");
    }

    @Test
    void noEntitlementsOmitsTheEntitlementBranchEntirely() {
        var rendered = render(CLIENT_A, List.of());

        assertThat(rendered).contains("'TENANT_PRIVATE'");
        assertThat(rendered).doesNotContain("'ENTITLEMENT_RESTRICTED'");
    }

    /**
     * The access predicate is always ANDed with the caller's own criteria. If its OR branches are
     * not grouped, the rendered SpEL reads {@code PUBLIC or TENANT_PRIVATE and ticker == 'X'}, and
     * because SpEL binds {@code and} tighter than {@code or} the ticker restriction stops applying
     * to the PUBLIC branch. This test would fail in that case.
     */
    @Test
    void groupedPredicateKeepsItsBranchesTogetherWhenAndedWithOtherCriteria() {
        var combined = new Filter.Expression(
                Filter.ExpressionType.AND,
                RagAccessFilter.groupedForCaller(CLIENT_A, List.of()),
                new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("ticker"), new Filter.Value("AAPL")));

        // A public document for a different ticker must still be rejected by the ticker clause.
        assertThat(admits(combined, Map.of("visibility", "PUBLIC", "tenantId", "", "ticker", "MSFT"))).isFalse();
        assertThat(admits(combined, Map.of("visibility", "PUBLIC", "tenantId", "", "ticker", "AAPL"))).isTrue();

        // Tenant-private documents are admitted only for the owning tenant, for the right ticker.
        assertThat(admits(combined, Map.of("visibility", "TENANT_PRIVATE", "tenantId", CLIENT_A, "ticker", "AAPL"))).isTrue();
        assertThat(admits(combined, Map.of("visibility", "TENANT_PRIVATE", "tenantId", CLIENT_B, "ticker", "AAPL"))).isFalse();
    }

    private static boolean admits(String tenantId, List<String> groups, Map<String, Object> metadata) {
        return admits(RagAccessFilter.forCaller(tenantId, groups), metadata);
    }

    private static boolean admits(Filter.Expression expression, Map<String, Object> metadata) {
        var spel = new SimpleVectorStoreFilterExpressionConverter().convertExpression(expression);

        var context = new StandardEvaluationContext();
        context.setVariable("metadata", metadata);
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(spel).getValue(context, Boolean.class));
    }

    private static String render(String tenantId, List<String> groups) {
        Filter.Expression expression = RagAccessFilter.forCaller(tenantId, groups);
        return new SimpleVectorStoreFilterExpressionConverter().convertExpression(expression);
    }
}
