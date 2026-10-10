SET @crm_commercial_index_exists = (
 SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'crm_interactions' AND index_name = 'idx_crm_commercial_messages'
);
SET @crm_commercial_index_sql = IF(@crm_commercial_index_exists = 0,
 'CREATE INDEX idx_crm_commercial_messages ON crm_interactions(lead_id, type, id)',
 'SELECT 1');
PREPARE crm_commercial_index_stmt FROM @crm_commercial_index_sql;
EXECUTE crm_commercial_index_stmt;
DEALLOCATE PREPARE crm_commercial_index_stmt;
