package com.example.assistant.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.List;

@Component
@RequestScope
public class TenantContext {
    private String tenantId = "demo";
    private List<String> entitlementGroups = List.of();

    public String tenantId() {
        return tenantId;
    }

    public List<String> entitlementGroups() {
        return entitlementGroups;
    }

    public void setTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            this.tenantId = tenantId.trim();
        }
    }

    public void setEntitlementGroups(List<String> entitlementGroups) {
        if (entitlementGroups != null) {
            this.entitlementGroups = entitlementGroups.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
    }
}
