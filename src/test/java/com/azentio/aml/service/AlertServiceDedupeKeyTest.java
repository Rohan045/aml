package com.azentio.aml.service;

import static com.azentio.aml.detection.TestFixtures.BASE_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.azentio.aml.detection.RuleFinding;
import com.azentio.aml.domain.enums.AmlTypology;
import com.azentio.aml.repository.AlertRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertServiceDedupeKeyTest {

    private final AlertService service = new AlertService(
            mock(AlertRepository.class), mock(AlertWriter.class), mock(AuditService.class));

    @Test
    void dedupeKeyBucketsSamePatternWithinConfiguredWindow() {
        RuleFinding first = finding(BASE_TIME.plusSeconds(60), "CUST-1", AmlTypology.STRUCTURING, "ACC-1");
        RuleFinding sameBucket = finding(BASE_TIME.plusSeconds(23 * 3600), "CUST-1", AmlTypology.STRUCTURING, "ACC-1");

        assertThat(service.dedupeKey(first, 24)).isEqualTo(service.dedupeKey(sameBucket, 24));
    }

    @Test
    void dedupeKeyChangesAtNextTimeBucketAndIncludesDiscriminator() {
        RuleFinding first = finding(BASE_TIME.plusSeconds(60), "CUST-1", AmlTypology.STRUCTURING, "ACC-1");
        RuleFinding nextBucket = finding(BASE_TIME.plusSeconds(25 * 3600), "CUST-1", AmlTypology.STRUCTURING, "ACC-1");
        RuleFinding otherPattern = finding(BASE_TIME.plusSeconds(60), "CUST-1", AmlTypology.STRUCTURING, "ACC-2");

        assertThat(service.dedupeKey(first, 24)).isNotEqualTo(service.dedupeKey(nextBucket, 24));
        assertThat(service.dedupeKey(first, 24)).isNotEqualTo(service.dedupeKey(otherPattern, 24));
    }

    @Test
    void dedupeKeyDefaultsInvalidWindowTo24HoursAndUsesDashForNullDiscriminator() {
        RuleFinding nullDiscriminator = finding(BASE_TIME.plusSeconds(60), "CUST-1", AmlTypology.BEHAVIOURAL_DEVIATION, null);

        String invalidWindowKey = service.dedupeKey(nullDiscriminator, 0);

        assertThat(invalidWindowKey).isEqualTo(service.dedupeKey(nullDiscriminator, 24));
        assertThat(invalidWindowKey).contains("CUST-1|BEHAVIOURAL_DEVIATION|-|");
    }

    private static RuleFinding finding(Instant windowStart, String customerId, AmlTypology typology, String discriminator) {
        return RuleFinding.builder()
                .customerId(customerId)
                .typology(typology)
                .discriminator(discriminator)
                .windowStart(windowStart)
                .detectedAt(BASE_TIME)
                .ruleWeight(10)
                .build();
    }
}
