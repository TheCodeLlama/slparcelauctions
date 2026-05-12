package com.slparcelauctions.backend.realty.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.user.User;

class RealtyGroupReportTest {

    @Test
    void builder_assignsAllFields() {
        RealtyGroup group = RealtyGroup.builder()
                .name("Acme Realty").slug("acme-realty").leaderId(1L).build();
        User reporter = User.builder().username("reporter").email("r@x").build();

        RealtyGroupReport report = RealtyGroupReport.builder()
                .realtyGroup(group)
                .reporter(reporter)
                .reason(RealtyGroupReportReason.FRAUDULENT_LISTINGS)
                .details("they are scamming buyers")
                .build();

        assertThat(report.getRealtyGroup()).isSameAs(group);
        assertThat(report.getReporter()).isSameAs(reporter);
        assertThat(report.getReason()).isEqualTo(RealtyGroupReportReason.FRAUDULENT_LISTINGS);
        assertThat(report.getDetails()).isEqualTo("they are scamming buyers");
        assertThat(report.getPublicId()).isNotNull();
    }

    @Test
    void builder_defaultsStatusToOpen() {
        RealtyGroupReport report = RealtyGroupReport.builder()
                .reason(RealtyGroupReportReason.SPAM)
                .details("spam")
                .build();

        assertThat(report.getStatus()).isEqualTo(RealtyGroupReportStatus.OPEN);
        assertThat(report.getResolvedByAdmin()).isNull();
        assertThat(report.getResolvedAt()).isNull();
        assertThat(report.getResolutionNotes()).isNull();
    }

    @Test
    void setters_supportResolutionPath() {
        User admin = User.builder().username("admin").email("a@x").build();
        RealtyGroupReport report = RealtyGroupReport.builder()
                .reason(RealtyGroupReportReason.OTHER)
                .details("d")
                .build();

        report.setStatus(RealtyGroupReportStatus.RESOLVED);
        report.setResolvedByAdmin(admin);
        report.setResolutionNotes("acted on it");

        assertThat(report.getStatus()).isEqualTo(RealtyGroupReportStatus.RESOLVED);
        assertThat(report.getResolvedByAdmin()).isSameAs(admin);
        assertThat(report.getResolutionNotes()).isEqualTo("acted on it");
    }
}
