-- =============================================================================
-- Sentinel AML - V2 reference and seed data
--
-- Seeds only operational *reference* data: detection rule configuration,
-- watchlists, FX rates and demo user accounts. No customer / account /
-- transaction data is seeded here - that arrives through CSV ingestion.
--
-- Idempotent: every statement uses ON CONFLICT DO NOTHING so the migration is
-- safe to re-run against a partially populated database during development.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. FX rates -> USD (the base currency every rule threshold is expressed in)
--
-- Indicative rates, effective-dated. Replace with a live feed in production;
-- the effective_from/effective_to design means historical alerts always
-- re-evaluate against the rate that applied on the transaction date.
-- -----------------------------------------------------------------------------
insert into exchange_rates (from_currency, to_currency, rate, effective_from, source, created_at, created_by)
values
    ('USD', 'USD', 1.00000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('INR', 'USD', 0.01200000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('EUR', 'USD', 1.08000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('GBP', 'USD', 1.27000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('AED', 'USD', 0.27230000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('SGD', 'USD', 0.74000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('CHF', 'USD', 1.13000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('HKD', 'USD', 0.12800000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('JPY', 'USD', 0.00640000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('AUD', 'USD', 0.65000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('CAD', 'USD', 0.73000000,  date '2024-01-01', 'SEED', now(), 'SYSTEM'),
    ('RUB', 'USD', 0.01100000,  date '2024-01-01', 'SEED', now(), 'SYSTEM')
on conflict (from_currency, to_currency, effective_from) do nothing;

-- -----------------------------------------------------------------------------
-- 2. Detection rule configuration
--
-- One row per rule. Compliance tunes thresholds by UPDATEing these rows and
-- bumping config_version; the engine reloads without a redeployment. Every
-- threshold below is stated in USD (threshold_currency), compared against
-- transactions.base_amount.
-- -----------------------------------------------------------------------------
insert into rule_configs (
    rule_code, rule_name, description, typology, enabled, execution_order, severity, risk_weight,
    threshold_amount, threshold_amount_min, threshold_currency, min_occurrences, time_window_hours,
    deviation_multiplier, lookback_days, ratio_threshold, dedupe_window_hours, parameters,
    config_version, effective_from, created_at, created_by)
values
    -- Business rule 1: CTR-style single-transaction threshold.
    ('CTR_THRESHOLD_10K',
     'Currency Transaction Report Threshold',
     'Flags any single transaction whose USD-equivalent value is greater than or equal to 10,000. Mirrors the regulatory CTR filing threshold.',
     'THRESHOLD_BREACH', true, 10, 'HIGH', 30,
     10000.00, null, 'USD', null, null,
     null, null, null, 24,
     '{"inclusive":true,"appliesTo":["CASH_DEPOSIT","CASH_WITHDRAWAL","WIRE_IN","WIRE_OUT","TRANSFER_IN","TRANSFER_OUT"]}',
     1, now(), now(), 'SYSTEM'),

    -- Business rule 2: structuring / smurfing just below the CTR threshold.
    ('STRUCTURING_24H',
     'Structuring Below Reporting Threshold',
     'Flags three or more transactions on the same account within a rolling 24-hour window where each transaction falls between USD 9,000 and 9,999.99 - the classic pattern for evading the CTR threshold.',
     'STRUCTURING', true, 20, 'HIGH', 35,
     9999.99, 9000.00, 'USD', 3, 24,
     null, null, null, 24,
     '{"scope":"ACCOUNT","windowType":"ROLLING","boundsInclusive":true}',
     1, now(), now(), 'SYSTEM'),

    -- Business rule 3: funds in, funds straight back out (layering).
    ('RAPID_MOVEMENT_48H',
     'Rapid Movement of Funds',
     'Flags an account where at least 80% of an incoming deposit is transferred out again within 48 hours, indicating pass-through layering rather than genuine economic activity.',
     'RAPID_MOVEMENT', true, 30, 'HIGH', 30,
     1000.00, null, 'USD', null, 48,
     null, null, 0.8000, 48,
     '{"scope":"ACCOUNT","minimumInflowUsd":1000,"outflowTypes":["TRANSFER_OUT","WIRE_OUT","CASH_WITHDRAWAL"]}',
     1, now(), now(), 'SYSTEM'),

    -- Business rule 4: any exposure to a sanctioned / high-risk jurisdiction.
    ('HIGH_RISK_JURISDICTION',
     'High-Risk Jurisdiction Exposure',
     'Flags any transaction whose counterparty, origin or destination country appears on the sanctions, FATF blacklist or FATF greylist watchlist. Always alerts, regardless of amount.',
     'HIGH_RISK_JURISDICTION', true, 40, 'CRITICAL', 40,
     null, null, 'USD', null, null,
     null, null, null, 24,
     '{"matchFields":["counterpartyCountry","originCountry","destinationCountry"],"listTypes":["SANCTIONS","FATF_BLACKLIST","FATF_GREYLIST"]}',
     1, now(), now(), 'SYSTEM'),

    -- Business rule 5: customer behaves unlike their own 90-day baseline.
    ('BEHAVIOURAL_DEVIATION_3X',
     'Behavioural Deviation From Baseline',
     'Flags a customer whose daily transaction value or count exceeds three times their own 90-day rolling average. Detects sudden activity spikes that amount thresholds alone would miss.',
     'BEHAVIOURAL_DEVIATION', true, 50, 'MEDIUM', 25,
     null, null, 'USD', null, 24,
     3.00, 90, null, 24,
     '{"scope":"CUSTOMER","metrics":["VALUE","COUNT"],"minimumBaselineDays":14,"minimumDailyValueUsd":500}',
     1, now(), now(), 'SYSTEM'),

    -- Supplementary: repeated suspiciously round amounts.
    ('ROUND_AMOUNT_PATTERN',
     'Repeated Round-Number Amounts',
     'Flags three or more transactions within 24 hours whose amounts are exact multiples of 1,000, a hallmark of manufactured rather than organic payment activity.',
     'ROUND_AMOUNT_PATTERN', true, 60, 'MEDIUM', 15,
     1000.00, null, 'USD', 3, 24,
     null, null, null, 24,
     '{"scope":"ACCOUNT","roundingMultiple":1000,"minimumAmountUsd":1000}',
     1, now(), now(), 'SYSTEM'),

    -- Supplementary: named counterparty on an internal or sanctions list.
    ('HIGH_RISK_COUNTERPARTY',
     'High-Risk Counterparty',
     'Flags a transaction whose counterparty name, bank or account number matches a sanctions, adverse-media or internal high-risk watchlist entry.',
     'HIGH_RISK_COUNTERPARTY', true, 70, 'HIGH', 30,
     null, null, 'USD', null, null,
     null, null, null, 24,
     '{"matchFields":["counterpartyName","counterpartyBank","counterpartyAccount"],"listTypes":["SANCTIONS","ADVERSE_MEDIA","INTERNAL_HIGH_RISK"]}',
     1, now(), now(), 'SYSTEM'),

    -- Supplementary: dormant account suddenly springs to life.
    ('DORMANT_REACTIVATION',
     'Dormant Account Reactivation',
     'Flags significant activity on an account that has been dormant for 180 days or more - a common mule-account signature.',
     'DORMANT_ACCOUNT_REACTIVATION', true, 80, 'MEDIUM', 20,
     5000.00, null, 'USD', null, null,
     null, 180, null, 24,
     '{"scope":"ACCOUNT","dormancyDays":180,"applicableStatuses":["DORMANT","INACTIVE"]}',
     1, now(), now(), 'SYSTEM')
on conflict (rule_code) do nothing;

-- -----------------------------------------------------------------------------
-- 3. Watchlists
--
-- Illustrative seed data for the demo. In production these rows are replaced by
-- a feed from the sanctions/FATF data provider. normalized_value is the
-- uppercased, punctuation-stripped form the matcher compares against.
-- -----------------------------------------------------------------------------

-- FATF "Call for Action" jurisdictions - highest weight, always critical.
insert into watchlist_entries (
    subject_type, list_type, entry_value, normalized_value, display_name,
    risk_weight, source, active, effective_from, notes, created_at, created_by)
values
    ('COUNTRY', 'FATF_BLACKLIST', 'KP', 'KP', 'Korea, Democratic People''s Republic of', 100, 'FATF', true, now(), 'FATF call-for-action jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_BLACKLIST', 'IR', 'IR', 'Iran, Islamic Republic of',              100, 'FATF', true, now(), 'FATF call-for-action jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_BLACKLIST', 'MM', 'MM', 'Myanmar',                                 90, 'FATF', true, now(), 'FATF call-for-action jurisdiction', now(), 'SYSTEM')
on conflict (subject_type, list_type, normalized_value) do nothing;

-- FATF "Increased Monitoring" jurisdictions.
insert into watchlist_entries (
    subject_type, list_type, entry_value, normalized_value, display_name,
    risk_weight, source, active, effective_from, notes, created_at, created_by)
values
    ('COUNTRY', 'FATF_GREYLIST', 'SY', 'SY', 'Syrian Arab Republic',        70, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'YE', 'YE', 'Yemen',                        70, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'SS', 'SS', 'South Sudan',                  65, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'HT', 'HT', 'Haiti',                        65, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'ML', 'ML', 'Mali',                         60, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'BF', 'BF', 'Burkina Faso',                 60, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'CD', 'CD', 'Congo, Democratic Republic',   60, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM'),
    ('COUNTRY', 'FATF_GREYLIST', 'MZ', 'MZ', 'Mozambique',                   55, 'FATF', true, now(), 'FATF increased-monitoring jurisdiction', now(), 'SYSTEM')
on conflict (subject_type, list_type, normalized_value) do nothing;

-- Secrecy / low-transparency jurisdictions: elevate risk but do not auto-alert
-- on their own (the engine treats TAX_HAVEN as a scoring uplift, not a trigger).
insert into watchlist_entries (
    subject_type, list_type, entry_value, normalized_value, display_name,
    risk_weight, source, active, effective_from, notes, created_at, created_by)
values
    ('COUNTRY', 'TAX_HAVEN', 'KY', 'KY', 'Cayman Islands',          40, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM'),
    ('COUNTRY', 'TAX_HAVEN', 'VG', 'VG', 'Virgin Islands, British', 40, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM'),
    ('COUNTRY', 'TAX_HAVEN', 'PA', 'PA', 'Panama',                  35, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM'),
    ('COUNTRY', 'TAX_HAVEN', 'SC', 'SC', 'Seychelles',              35, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM'),
    ('COUNTRY', 'TAX_HAVEN', 'BZ', 'BZ', 'Belize',                  35, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM'),
    ('COUNTRY', 'TAX_HAVEN', 'VU', 'VU', 'Vanuatu',                 30, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM'),
    ('COUNTRY', 'TAX_HAVEN', 'MH', 'MH', 'Marshall Islands',        30, 'INTERNAL', true, now(), 'Offshore financial centre', now(), 'SYSTEM')
on conflict (subject_type, list_type, normalized_value) do nothing;

-- Fictional counterparties and banks used by the demo dataset.
insert into watchlist_entries (
    subject_type, list_type, entry_value, normalized_value, display_name,
    risk_weight, source, active, effective_from, notes, created_at, created_by)
values
    ('COUNTERPARTY', 'SANCTIONS',          'Volkov Trading LLC',      'VOLKOV TRADING LLC',      'Volkov Trading LLC',      95, 'DEMO', true, now(), 'Fictional sanctioned entity for demonstration', now(), 'SYSTEM'),
    ('COUNTERPARTY', 'SANCTIONS',          'Zarand Petrochem FZE',    'ZARAND PETROCHEM FZE',    'Zarand Petrochem FZE',    95, 'DEMO', true, now(), 'Fictional sanctioned entity for demonstration', now(), 'SYSTEM'),
    ('COUNTERPARTY', 'ADVERSE_MEDIA',      'Apex Crypto Exchange',    'APEX CRYPTO EXCHANGE',    'Apex Crypto Exchange',    60, 'DEMO', true, now(), 'Fictional adverse-media subject for demonstration', now(), 'SYSTEM'),
    ('COUNTERPARTY', 'INTERNAL_HIGH_RISK', 'Golden Sands Exchange',   'GOLDEN SANDS EXCHANGE',   'Golden Sands Exchange',   55, 'DEMO', true, now(), 'Fictional money-service business for demonstration', now(), 'SYSTEM'),
    ('BANK',         'SANCTIONS',          'Bank Mellat',             'BANK MELLAT',             'Bank Mellat',             90, 'DEMO', true, now(), 'Fictional sanctioned institution for demonstration', now(), 'SYSTEM'),
    ('BANK',         'INTERNAL_HIGH_RISK', 'Northern Star Bank',      'NORTHERN STAR BANK',      'Northern Star Bank',      50, 'DEMO', true, now(), 'Fictional correspondent bank for demonstration', now(), 'SYSTEM')
on conflict (subject_type, list_type, normalized_value) do nothing;

-- -----------------------------------------------------------------------------
-- 4. Demo user accounts (one per RBAC role)
--
-- !! DEVELOPMENT AND DEMONSTRATION ONLY !!
-- These are throwaway credentials for the local demo environment; the passwords
-- are published in the README so reviewers can log in. They must be deleted or
-- rotated before this schema is used anywhere real - see the note in README.md.
-- Hashes are BCrypt, strength 12.
-- -----------------------------------------------------------------------------
insert into app_users (
    username, password_hash, full_name, email, role,
    enabled, account_locked, failed_login_attempts, password_changed_at, created_at, created_by)
values
    ('admin',
     '$2a$12$rvHpGPsgi8qyMbe58GeYSuwslGi0vyQKNMEgFeMXFvHRhaSDLp1M2',
     'Platform Administrator', 'admin@meridiantrust.example', 'ADMIN',
     true, false, 0, now(), now(), 'SYSTEM'),
    ('compliance.officer',
     '$2a$12$/75WA72rU1VUXdMYOlbOfOjEN.Nu3vduIa5je5slhLY06AaI7lxN6',
     'Priya Raghunathan', 'priya.raghunathan@meridiantrust.example', 'COMPLIANCE_OFFICER',
     true, false, 0, now(), now(), 'SYSTEM'),
    ('senior.analyst',
     '$2a$12$Lj4Ukn8UAwiESTWdID8g4uVQJJQkmjAuIA0QdE4EQ5kGO3XGxmiuu',
     'Daniel Okonkwo', 'daniel.okonkwo@meridiantrust.example', 'SENIOR_ANALYST',
     true, false, 0, now(), now(), 'SYSTEM'),
    ('analyst',
     '$2a$12$kYdFMWsf23YpDNFg4UWGZOaOcfaStoHKkzI7zcr7TyHovjRA86WBq',
     'Mei Lin Chen', 'meilin.chen@meridiantrust.example', 'ANALYST',
     true, false, 0, now(), now(), 'SYSTEM'),
    ('auditor',
     '$2a$12$RPQh6ri1yBU9Kk9.6GdvueAEXosyHfxeEM8.UhssaS28AmPtSNGcK',
     'Regulatory Auditor', 'auditor@meridiantrust.example', 'AUDITOR',
     true, false, 0, now(), now(), 'SYSTEM')
on conflict (username) do nothing;
