CREATE TABLE ssh_workspace_config (
    id INT PRIMARY KEY,
    host_name VARCHAR(255) NOT NULL,
    port_number INT NOT NULL,
    username_value VARCHAR(160) NOT NULL,
    remote_root VARCHAR(1000) NOT NULL,
    host_key_sha256 VARCHAR(160) NOT NULL,
    password_secret VARCHAR(160) NOT NULL,
    status VARCHAR(40) NOT NULL,
    last_error VARCHAR(1000),
    last_tested_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
