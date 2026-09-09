package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long relationshipManagerId;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;

    @Column
    private String idNumber;

    @Column
    private String idType;

    @Column
    private LocalDate dateOfBirth;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Account> accounts = new ArrayList<>();

    public Customer() {}

    private Customer(Builder b) {
        this.id = b.id;
        this.firstName = b.firstName;
        this.lastName = b.lastName;
        this.email = b.email;
        this.phone = b.phone;
        this.status = b.status;
        this.idNumber = b.idNumber;
        this.idType = b.idType;
        this.dateOfBirth = b.dateOfBirth;
        this.relationshipManagerId = b.relationshipManagerId;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private CustomerStatus status;
        private String idNumber;
        private String idType;
        private LocalDate dateOfBirth;
        private Long relationshipManagerId;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder firstName(String v) { this.firstName = v; return this; }
        public Builder lastName(String v) { this.lastName = v; return this; }
        public Builder email(String v) { this.email = v; return this; }
        public Builder phone(String v) { this.phone = v; return this; }
        public Builder status(CustomerStatus v) { this.status = v; return this; }
        public Builder idNumber(String v) { this.idNumber = v; return this; }
        public Builder idType(String v) { this.idType = v; return this; }
        public Builder dateOfBirth(LocalDate v) { this.dateOfBirth = v; return this; }
        public Builder relationshipManagerId(Long v) { this.relationshipManagerId = v; return this; }
        public Customer build() { return new Customer(this); }
    }

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public CustomerStatus getStatus() { return status; }
    public List<Account> getAccounts() { return accounts; }

    public Long getRelationshipManagerId() { return relationshipManagerId; }

    public String getIdNumber() { return idNumber; }
    public String getIdType() { return idType; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }

    public void setFirstName(String v) { this.firstName = v; }
    public void setLastName(String v) { this.lastName = v; }
    public void setEmail(String v) { this.email = v; }
    public void setPhone(String v) { this.phone = v; }
    public void setStatus(CustomerStatus v) { this.status = v; }

    public void setRelationshipManagerId(Long v) { this.relationshipManagerId = v; }

    public void setIdNumber(String v) { this.idNumber = v; }
    public void setIdType(String v) { this.idType = v; }
    public void setDateOfBirth(LocalDate v) { this.dateOfBirth = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Customer c)) return false;
        return Objects.equals(id, c.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
