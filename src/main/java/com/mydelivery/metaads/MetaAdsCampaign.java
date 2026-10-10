package com.mydelivery.metaads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "meta_ads_campaigns")
@Getter @Setter
public class MetaAdsCampaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    @Column(name = "campaign_id", length = 32, nullable = false)
    private String campaignId;

    @Column(name = "ad_account_id", length = 32, nullable = false)
    private String adAccountId;

    @Column(name = "adset_id", length = 32)
    private String adsetId;

    @Column(name = "creative_id", length = 32)
    private String creativeId;

    @Column(name = "ad_id", length = 32)
    private String adId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "daily_budget_cents", nullable = false)
    private Long dailyBudgetCents;

    @Column(nullable = false, length = 16)
    private String status = "PAUSED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "end_at", nullable = false)
    private Instant endAt;
}
