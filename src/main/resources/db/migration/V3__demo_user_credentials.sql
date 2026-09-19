-- =============================================================================
-- V3 - Set the published demo credentials
--
-- Why this exists as its own migration
-- ------------------------------------
-- V2 seeded five demo accounts, one per RBAC role, but the plaintext behind
-- those hashes was never recorded anywhere - which makes the accounts useless
-- to a reviewer who needs to log in and exercise the role model. Rather than
-- edit V2 in place and break the Flyway checksum of every database that has
-- already run it, this migration restates the hashes forward.
--
-- Every account below shares the password published in README.md. That is a
-- deliberate demonstration convenience and nothing else.
--
-- !! DEVELOPMENT AND DEMONSTRATION ONLY !!
-- These credentials are public. Before this schema is used anywhere carrying
-- real customer data, every row seeded here must be deleted or rotated. The
-- hashes are BCrypt strength 12, matching the encoder configured in
-- SecurityConfig - if that strength changes, these become unusable.
-- =============================================================================

update app_users set password_hash = '$2a$12$q8J54QZWHjyNLVbAXeFQjetR4r8JaGNNksPJ.kYZCFmKv4uGRD/TO',
    password_changed_at = now() where username = 'admin';

update app_users set password_hash = '$2a$12$jDJz.Mp38g9//CLgDtg61.S3bLBfnFGbCIcmI9B.Ykk7SwIcdOj/O',
    password_changed_at = now() where username = 'compliance.officer';

update app_users set password_hash = '$2a$12$.pb6.e1gE0xMY6xYXVpt9uY0WqK/tzX6bHCUN4QryZwMVGIqRY/Om',
    password_changed_at = now() where username = 'senior.analyst';

update app_users set password_hash = '$2a$12$OYJKWU79UEaz0y8pNArTEefohTJnCtEYR5YX2aBFdlLoRWSgXZhA6',
    password_changed_at = now() where username = 'analyst';

update app_users set password_hash = '$2a$12$mkZV6/Y3WNQxhmVAsRDpMOXjiDao./O6grZV7P4eZ.OKHONGiSTkm',
    password_changed_at = now() where username = 'auditor';
