package com.mydelivery.metaads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "meta_ads_connections")
@Getter @Setter
public class MetaAdsConnection {
    @Id
    @Column(name = "restaurante_id")
    private Long restauranteId;

    @Column(name = "token_encrypted", columnDefinition = "TEXT", nullable = false)
    private String tokenEncrypted;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "ad_account_id", length = 32)
    private String adAccountId;

    @Column(name = "page_id", length = 32)
    private String pageId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
