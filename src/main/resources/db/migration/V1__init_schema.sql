CREATE TABLE office (
                        id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                        tenant_id  VARCHAR(64)  NOT NULL,
                        code       VARCHAR(32)  NOT NULL,
                        name       VARCHAR(200) NOT NULL,
                        CONSTRAINT uq_office_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE employee (
                          id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
                          tenant_id           VARCHAR(64)  NOT NULL,
                          employee_id         VARCHAR(64)  NOT NULL,
                          name                VARCHAR(200),
                          email               VARCHAR(254),
                          phone               VARCHAR(20),
                          manager_id          VARCHAR(64),
                          office_code         VARCHAR(32),
                          shift_code          VARCHAR(32),
                          pickup_point        VARCHAR(255),
                          special_assistance  VARCHAR(500),
                          hr_version          BIGINT       NOT NULL,
                          hr_hash             BIGINT       NOT NULL,
                          bucket              INT          NOT NULL,
                          active              BOOLEAN      NOT NULL,
                          deactivation_source VARCHAR(32),
                          created_at          TIMESTAMP(6) NOT NULL,
                          updated_at          TIMESTAMP(6) NOT NULL,
                          row_version         BIGINT       NOT NULL DEFAULT 0,
                          CONSTRAINT uq_employee_tenant_emp UNIQUE (tenant_id, employee_id)
);
CREATE INDEX ix_employee_bucket ON employee (tenant_id, bucket, active);

CREATE TABLE outbox_event (
                              id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                              event_id        VARCHAR(36)  NOT NULL,
                              tenant_id       VARCHAR(64)  NOT NULL,
                              employee_id     VARCHAR(64)  NOT NULL,
                              event_type      VARCHAR(40)  NOT NULL,
                              payload         TEXT         NOT NULL,
                              created_at      TIMESTAMP(6) NOT NULL,
                              published_at    TIMESTAMP(6) NULL,
                              attempts        INT          NOT NULL DEFAULT 0,
                              next_attempt_at TIMESTAMP(6) NOT NULL,
                              last_error      VARCHAR(255),
                              CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);
CREATE INDEX ix_outbox_pending ON outbox_event (published_at, next_attempt_at, id);
CREATE INDEX ix_outbox_employee ON outbox_event (tenant_id, employee_id, id);

CREATE TABLE reconciliation_run (
                                    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                                    tenant_id     VARCHAR(64)  NOT NULL,
                                    status        VARCHAR(32)  NOT NULL,
                                    started_at    TIMESTAMP(6) NOT NULL,
                                    finished_at   TIMESTAMP(6) NULL,
                                    file_rows     BIGINT       NOT NULL DEFAULT 0,
                                    rejected_rows BIGINT       NOT NULL DEFAULT 0,
                                    added         BIGINT       NOT NULL DEFAULT 0,
                                    changed       BIGINT       NOT NULL DEFAULT 0,
                                    unchanged     BIGINT       NOT NULL DEFAULT 0,
                                    stale         BIGINT       NOT NULL DEFAULT 0,
                                    missing       BIGINT       NOT NULL DEFAULT 0,
                                    store_active  BIGINT       NOT NULL DEFAULT 0,
                                    missing_ratio DOUBLE       NOT NULL DEFAULT 0,
                                    threshold     DOUBLE       NOT NULL,
                                    deactivated   BIGINT       NOT NULL DEFAULT 0,
                                    decided_by    VARCHAR(64),
                                    decided_at    TIMESTAMP(6) NULL,
                                    failure_code  VARCHAR(64)
);
CREATE INDEX ix_recon_tenant ON reconciliation_run (tenant_id, id);

CREATE TABLE reconciliation_rejection (
                                          id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                          run_id      BIGINT       NOT NULL,
                                          line_number BIGINT       NOT NULL,
                                          employee_id VARCHAR(64),
                                          codes       VARCHAR(500) NOT NULL
);
CREATE INDEX ix_rejection_run ON reconciliation_rejection (run_id, id);

CREATE TABLE pending_deactivation (
                                      id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                      run_id      BIGINT      NOT NULL,
                                      tenant_id   VARCHAR(64) NOT NULL,
                                      employee_id VARCHAR(64) NOT NULL,
                                      CONSTRAINT uq_pending_run_emp UNIQUE (run_id, employee_id)
);

CREATE TABLE cab_trip (
                          id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                          tenant_id    VARCHAR(64)  NOT NULL,
                          office_code  VARCHAR(32)  NOT NULL,
                          departure_at TIMESTAMP(6) NOT NULL,
                          capacity     INT          NOT NULL,
                          seats_booked INT          NOT NULL DEFAULT 0
);

CREATE TABLE booking (
                         id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                         tenant_id        VARCHAR(64)  NOT NULL,
                         employee_id      VARCHAR(64)  NOT NULL,
                         trip_id          BIGINT       NOT NULL,
                         status           VARCHAR(16)  NOT NULL,
                         cancelled_reason VARCHAR(64),
                         updated_at       TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_booking_employee ON booking (tenant_id, employee_id, status);

CREATE TABLE app_user (
                          id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                          username      VARCHAR(64)  NOT NULL,
                          password_hash VARCHAR(100) NOT NULL,
                          role          VARCHAR(32)  NOT NULL,
                          tenant_id     VARCHAR(64)  NOT NULL,
                          employee_id   VARCHAR(64),
                          CONSTRAINT uq_app_user_username UNIQUE (username)
);