ALTER TABLE remote_deployment_profile
    ADD COLUMN local_compose_file VARCHAR(255) NOT NULL DEFAULT 'docker-compose.yml';
