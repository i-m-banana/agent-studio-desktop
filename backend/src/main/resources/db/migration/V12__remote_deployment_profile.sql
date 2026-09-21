CREATE TABLE remote_deployment_profile (
    id INT PRIMARY KEY,
    local_source_root VARCHAR(1000) NOT NULL,
    remote_deploy_root VARCHAR(1000) NOT NULL,
    remote_backup_root VARCHAR(1000) NOT NULL,
    compose_file VARCHAR(255) NOT NULL,
    compose_project VARCHAR(160) NOT NULL,
    nginx_config VARCHAR(500) NOT NULL,
    health_url VARCHAR(500) NOT NULL,
    status VARCHAR(40) NOT NULL,
    last_error VARCHAR(1000),
    last_tested_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
