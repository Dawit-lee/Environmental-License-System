package com.envpermit.core.user;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    public enum Role { ADMIN, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    private String passwordHash;
    private String name;

    @Enumerated(EnumType.STRING)
    private Role role;

    private Long companyId;

    protected User() {
    }

    public User(String email, String passwordHash, String name, Role role) {
        this(email, passwordHash, name, role, null);
    }

    public User(String email, String passwordHash, String name, Role role, Long companyId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.companyId = companyId;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public Role getRole() { return role; }
    public Long getCompanyId() { return companyId; }
}
