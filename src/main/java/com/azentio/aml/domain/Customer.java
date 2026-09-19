package com.azentio.aml.domain;

import com.azentio.aml.domain.enums.Channel;
import com.azentio.aml.domain.enums.CustomerSegment;
import com.azentio.aml.domain.enums.EducationLevel;
import com.azentio.aml.domain.enums.EmploymentStatus;
import com.azentio.aml.domain.enums.Gender;
import com.azentio.aml.domain.enums.KycStatus;
import com.azentio.aml.domain.enums.MaritalStatus;
import com.azentio.aml.domain.enums.RiskRating;
import jakarta.persistence.Cacheable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * KYC master record. Mirrors the {@code customers.csv} feed; {@code customer_id} (e.g.
 * {@code CUST_00001}) is the natural key shared with every downstream feed.
 *
 * <p>Name, e-mail, phone and national id are PII and must be masked in list views.
 */
@Entity
@Table(
        name = "customers",
        indexes = {
            @Index(name = "idx_customer_risk_rating", columnList = "risk_rating"),
            @Index(name = "idx_customer_kyc_status", columnList = "kyc_status"),
            @Index(name = "idx_customer_segment", columnList = "customer_segment"),
            @Index(name = "idx_customer_country_city", columnList = "country,city")
        })
@Cacheable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Customer extends BaseEntity {

    @Id
    @NotBlank
    @Size(max = 32)
    @Column(name = "customer_id", length = 32, nullable = false, updatable = false)
    @ToString.Include
    private String customerId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Size(max = 100)
    @Column(name = "last_name", length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "gender", length = 16)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Age as supplied by the source feed; retained verbatim for reconciliation. */
    @PositiveOrZero
    @Column(name = "age")
    private Integer age;

    @Email
    @Size(max = 160)
    @Column(name = "email", length = 160)
    private String email;

    @Size(max = 32)
    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    /** Government identifier (PAN/Aadhaar-style). Synthetic only; never a real identifier. */
    @Size(max = 64)
    @Column(name = "national_id", length = 64)
    private String nationalId;

    @Size(max = 100)
    @Column(name = "city", length = 100)
    private String city;

    @Size(max = 100)
    @Column(name = "state", length = 100)
    private String state;

    /** ISO 3166-1 alpha-2 country of residence, e.g. {@code IN}. */
    @Size(max = 2)
    @Column(name = "country", length = 2)
    private String country;

    @Size(max = 20)
    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Size(max = 120)
    @Column(name = "occupation", length = 120)
    private String occupation;

    @Column(name = "annual_income", precision = 19, scale = 2)
    private BigDecimal annualIncome;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "marital_status", length = 20)
    private MaritalStatus maritalStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "education_level", length = 20)
    private EducationLevel educationLevel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "employment_status", length = 20)
    private EmploymentStatus employmentStatus;

    @Column(name = "customer_since")
    private LocalDate customerSince;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_segment", length = 20)
    private CustomerSegment customerSegment;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kyc_status", length = 20, nullable = false)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "risk_rating", length = 20, nullable = false)
    @Builder.Default
    private RiskRating riskRating = RiskRating.LOW;

    /** Politically Exposed Person flag - an aggravating factor in risk scoring. */
    @NotNull
    @Column(name = "is_politically_exposed", nullable = false)
    @Builder.Default
    private Boolean politicallyExposed = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "preferred_channel", length = 32)
    private Channel preferredChannel;

    @Column(name = "email_verified")
    private Boolean emailVerified;

    @Column(name = "phone_verified")
    private Boolean phoneVerified;

    @PositiveOrZero
    @Column(name = "num_complaints_last_year")
    private Integer complaintsLastYear;

    @OneToMany(
            mappedBy = "customer",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();

    public void addAccount(Account account) {
        accounts.add(account);
        account.setCustomer(this);
    }

    public String getFullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Customer that)) {
            return false;
        }
        return customerId != null && customerId.equals(that.customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(customerId);
    }
}
