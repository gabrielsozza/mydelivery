package com.mydelivery.metaads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "meta_ads_oauth_states")
@Getter @Setter
public class MetaAdsOauthState {
    @Id
    @Column(name = "state_hash", length = 64)
    private String stateHash;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
