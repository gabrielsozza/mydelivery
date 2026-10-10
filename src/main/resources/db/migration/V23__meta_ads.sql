CREATE TABLE IF NOT EXISTS meta_ads_connections (
    restaurante_id BIGINT PRIMARY KEY,
    token_encrypted TEXT NOT NULL,
    token_expires_at DATETIME(6) NULL,
    ad_account_id VARCHAR(32) NULL,
    page_id VARCHAR(32) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_meta_ads_connection_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);

CREATE TABLE IF NOT EXISTS meta_ads_oauth_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX idx_meta_ads_oauth_expiry (expires_at)
);

CREATE TABLE IF NOT EXISTS meta_ads_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    campaign_id VARCHAR(32) NOT NULL,
    ad_account_id VARCHAR(32) NOT NULL,
    adset_id VARCHAR(32) NULL,
    creative_id VARCHAR(32) NULL,
    ad_id VARCHAR(32) NULL,
    name VARCHAR(120) NOT NULL,
    daily_budget_cents BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_meta_ads_campaign_id (campaign_id),
    INDEX idx_meta_ads_campaign_restaurante (restaurante_id, created_at),
    CONSTRAINT fk_meta_ads_campaign_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);
