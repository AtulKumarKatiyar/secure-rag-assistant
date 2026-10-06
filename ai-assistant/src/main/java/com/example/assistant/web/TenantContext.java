package com.example.assistant.web;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.List;

/**
 * Per-request caller identity, derived from the verified JWT.
 *
 * <p>This used to be populated straight from the {@code /chat} request body, which let any caller
 * claim any tenant. It is now only ever filled from an authenticated token.
 *
 * <p>Defaults are fail-closed. An absent {@code tenant_id} leaves the tenant blank, and
 * {@code RagAccessFilter} omits the tenant-private branch entirely when the tenant is blank, so a
 * caller without the claim sees public documents only.
 */
@Component
@RequestScope
public class TenantContext {

    public static final String TENANT_ID_CLAIM = "tenant_id";
    public static final String ENTITLEMENT_GROUPS_CLAIM = "entitlement_groups";

    private String tenantId = "";
    private List<String> entitlementGroups = List.of();

    public String tenantId() {
        return tenantId;
    }

    public List<String> entitlementGroups() {
        return entitlementGroups;
    }

    /**
     * Production population path: called once per request from the verified JWT.
     */
    public void setFromToken(Jwt jwt) {
        if (jwt == null) {
            this.tenantId = "";
            this.entitlementGroups = List.of();
            return;
        }
        this.tenantId = normalizeTenant(jwt.getClaimAsString(TENANT_ID_CLAIM));
        this.entitlementGroups = normalizeGroups(jwt.getClaimAsStringList(ENTITLEMENT_GROUPS_CLAIM));
    }

    public void setTenantId(String tenantId) {
        this.tenantId = normalizeTenant(tenantId);
    }

    public void setEntitlementGroups(List<String> entitlementGroups) {
        this.entitlementGroups = normalizeGroups(entitlementGroups);
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null ? "" : tenantId.trim();
    }

    private static List<String> normalizeGroups(List<String> entitlementGroups) {
        if (entitlementGroups == null) {
            return List.of();
        }
        return entitlementGroups.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
