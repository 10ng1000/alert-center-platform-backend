CREATE DATABASE IF NOT EXISTS alert_center DEFAULT CHARACTER SET utf8mb4;
USE alert_center;

CREATE TABLE IF NOT EXISTS mq_consume_record (
    event_id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS work_order (
    id VARCHAR(64) PRIMARY KEY,
    alarm_event_id VARCHAR(64) NOT NULL,
    alarm_code VARCHAR(64) NULL,
    device_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    assignee VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_notified_at TIMESTAMP NULL
);

SET @schema_name = DATABASE();

SET @add_updated_at_sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'work_order'
              AND column_name = 'updated_at'
        ),
        'SELECT 1',
        'ALTER TABLE work_order ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
    )
);
PREPARE stmt FROM @add_updated_at_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS admin_user (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS device_admin_binding (
    id VARCHAR(64) PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL UNIQUE,
    admin_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

SET @add_last_notified_at_sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = @schema_name
              AND table_name = 'work_order'
              AND column_name = 'last_notified_at'
        ),
        'SELECT 1',
        'ALTER TABLE work_order ADD COLUMN last_notified_at TIMESTAMP NULL'
    )
);
PREPARE stmt FROM @add_last_notified_at_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

