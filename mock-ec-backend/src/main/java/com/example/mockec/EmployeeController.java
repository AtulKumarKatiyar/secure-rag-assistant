package com.example.mockec;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class EmployeeController {
    private final Map<String, EmployeeProfile> profiles = Map.of(
            "1001", new EmployeeProfile("1001", "Alice Sharma", "People Operations", "HRBP"),
            "1002", new EmployeeProfile("1002", "Ben Carter", "Engineering", "Platform Engineer"),
            "999", new EmployeeProfile("999", "Sensitive Test User", "Executive", "CFO"));

    private final Map<String, LeaveBalance> leaveBalances = Map.of(
            "1001", new LeaveBalance("1001", 18, 4, 2),
            "1002", new LeaveBalance("1002", 12, 8, 1),
            "999", new LeaveBalance("999", 3, 20, 0));

    @GetMapping("/employee/{id}/profile")
    @PreAuthorize("hasAuthority('SCOPE_profile:read')")
    EmployeeProfile profile(@PathVariable String id) {
        return profiles.getOrDefault(id, new EmployeeProfile(id, "Unknown", "Unknown", "Unknown"));
    }

    @GetMapping("/employee/{id}/leave-balance")
    @PreAuthorize("hasAuthority('SCOPE_leave:read')")
    LeaveBalance leaveBalance(@PathVariable String id) {
        return leaveBalances.getOrDefault(id, new LeaveBalance(id, 0, 0, 0));
    }

    record EmployeeProfile(String employeeId, String name, String department, String title) {
    }

    record LeaveBalance(String employeeId, int annualLeaveDaysLeft, int sickLeaveDaysLeft, int personalDaysLeft) {
    }
}
