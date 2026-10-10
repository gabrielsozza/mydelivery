package com.mydelivery.metaads;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaAdsCampaignRepository extends JpaRepository<MetaAdsCampaign, Long> {
    List<MetaAdsCampaign> findByRestauranteIdOrderByCreatedAtDesc(Long restauranteId);
}
