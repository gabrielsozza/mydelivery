package com.mydelivery.metaads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "restaurant_meta_tracking")
@Getter @Setter
public class RestaurantMetaTracking {
    @Id @Column(name = "restaurante_id") private Long restauranteId;
    @Column(name = "pixel_id", nullable = false, length = 30) private String pixelId;
    @Column(name = "token_encrypted", columnDefinition = "TEXT") private String tokenEncrypted;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
