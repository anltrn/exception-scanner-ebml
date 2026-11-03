-- =====================================================================
--  Kod karmaşıklığı tabloları (env.method_risk_analyze, env.method_similarity)
--  için geçiş: scan_run_id'yi env.exception_scan_run.id'ye (bigint) bağlar ve
--  aynı taramadaki repoları ayırmak için project_key / repo sütunlarını ekler.
--  Tablolar envanter tarafında oluşturulmuştur; bu dosya bir kez çalıştırılır.
-- =====================================================================

-- scan_run_id: uuid -> env.exception_scan_run.id (bigint). Tablolarda veri varsa uuid değerleri taşınamaz;
-- bu komutlar tabloyu boşaltır (TRUNCATE). Veri saklanacaksa önce yedekleyin.
TRUNCATE env.method_risk_analyze, env.method_similarity;

ALTER TABLE env.method_risk_analyze
    ALTER COLUMN scan_run_id TYPE bigint USING NULL,
    ADD CONSTRAINT fk_method_risk_analyze_run FOREIGN KEY (scan_run_id)
        REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    ADD COLUMN project_key varchar(100),
    ADD COLUMN repo        varchar(200);

ALTER TABLE env.method_similarity
    ALTER COLUMN scan_run_id TYPE bigint USING NULL,
    ADD CONSTRAINT fk_method_similarity_run FOREIGN KEY (scan_run_id)
        REFERENCES env.exception_scan_run (id) ON DELETE CASCADE,
    ADD COLUMN project_key varchar(100),
    ADD COLUMN repo        varchar(200);

CREATE INDEX ix_method_risk_analyze_run  ON env.method_risk_analyze (scan_run_id, project_key, repo);
CREATE INDEX ix_method_similarity_run    ON env.method_similarity (scan_run_id, project_key, repo);
CREATE INDEX ix_method_risk_analyze_java ON env.method_risk_analyze (java_method_id);

COMMENT ON COLUMN env.method_risk_analyze.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.method_risk_analyze.project_key IS 'Metodun bulunduğu reponun projesi (Bitbucket proje anahtarı)';
COMMENT ON COLUMN env.method_risk_analyze.repo IS 'Metodun bulunduğu repo (slug); file_path bu repoya göre yazılır';
COMMENT ON COLUMN env.method_similarity.scan_run_id IS 'Kaydın ait olduğu tarama (env.exception_scan_run.id); tarama kaydı silinince bu kayıtlar da silinir (ON DELETE CASCADE)';
COMMENT ON COLUMN env.method_similarity.project_key IS 'Grubun bulunduğu reponun projesi (Bitbucket proje anahtarı)';
COMMENT ON COLUMN env.method_similarity.repo IS 'Grubun bulunduğu repo (slug); group_key ve members_json''daki dosya yolları bu repoya göredir';
