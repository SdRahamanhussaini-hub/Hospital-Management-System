CREATE DATABASE IF NOT EXISTS medicare_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE medicare_db;

CREATE TABLE IF NOT EXISTS app_users (
    user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    login_name VARCHAR(40) NULL,
    full_name VARCHAR(150) NOT NULL,
    phone CHAR(10) NULL,
    password_salt VARBINARY(16) NOT NULL,
    password_hash VARBINARY(32) NOT NULL,
    role ENUM('PATIENT', 'STAFF') NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_app_users_login_name (login_name),
    UNIQUE KEY uq_app_users_phone (phone)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- For a new database, this creates the complete table. Existing appointment
-- rows remain intact when the account link is added below.
CREATE TABLE IF NOT EXISTS appointments (
    appointment_id VARCHAR(32) NOT NULL,
    patient_user_id BIGINT UNSIGNED NULL,
    patient_name VARCHAR(150) NOT NULL,
    age TINYINT UNSIGNED NOT NULL,
    phone CHAR(10) NOT NULL,
    gender VARCHAR(20) NOT NULL,
    doctor VARCHAR(100) NOT NULL,
    department VARCHAR(100) NOT NULL,
    appointment_date DATE NOT NULL,
    appointment_time TIME NOT NULL,
    symptoms TEXT NOT NULL,
    consultation_fee INT UNSIGNED NOT NULL,
    reappointment_fee INT UNSIGNED NOT NULL DEFAULT 0,
    status ENUM('Booked', 'Came', 'Cancelled', 'Not Attended', 'Reappointed') NOT NULL DEFAULT 'Booked',
    active_slot VARCHAR(255) GENERATED ALWAYS AS (
        CASE WHEN status = 'Booked'
             THEN CONCAT(doctor, '|', appointment_date, '|', appointment_time)
             ELSE NULL
        END
    ) STORED,
    PRIMARY KEY (appointment_id),
    UNIQUE KEY uq_appointments_active_slot (active_slot),
    KEY ix_appointments_status_date (status, appointment_date),
    KEY ix_appointments_patient_user (patient_user_id),
    CONSTRAINT fk_appointments_patient_user
        FOREIGN KEY (patient_user_id) REFERENCES app_users(user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- IDs are allocated by MySQL so they remain unique across patients and restarts.
CREATE TABLE IF NOT EXISTS appointment_id_sequence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Seed the sequence from existing APT numbers. INSERT IGNORE makes this safe to rerun.
INSERT IGNORE INTO appointment_id_sequence (id)
SELECT CAST(SUBSTRING(appointment_id, 4) AS UNSIGNED)
FROM appointments
WHERE appointment_id REGEXP '^APT[0-9]+$';

-- Idempotently add the account link and its index/foreign key to the existing
-- appointments table created by the earlier version of this application.
SET @has_patient_user_id = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'appointments'
      AND column_name = 'patient_user_id'
);
SET @ddl = IF(@has_patient_user_id = 0,
    'ALTER TABLE appointments ADD COLUMN patient_user_id BIGINT UNSIGNED NULL',
    'SELECT 1');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @has_patient_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'appointments'
      AND index_name = 'ix_appointments_patient_user'
);
SET @ddl = IF(@has_patient_index = 0,
    'ALTER TABLE appointments ADD INDEX ix_appointments_patient_user (patient_user_id)',
    'SELECT 1');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @has_patient_fk = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'appointments'
      AND constraint_name = 'fk_appointments_patient_user'
      AND constraint_type = 'FOREIGN KEY'
);
SET @ddl = IF(@has_patient_fk = 0,
    'ALTER TABLE appointments ADD CONSTRAINT fk_appointments_patient_user FOREIGN KEY (patient_user_id) REFERENCES app_users(user_id)',
    'SELECT 1');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
