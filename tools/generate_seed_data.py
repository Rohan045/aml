#!/usr/bin/env python3
"""Generate the Sentinel AML synthetic demonstration dataset.

Why a generator rather than three checked-in CSV files
------------------------------------------------------
Two of the eight detection rules are relative rather than absolute. Behavioural
deviation compares a day against the customer's own 90-day baseline, and dormant
reactivation compares a transaction against 180 days of silence. Neither can be
expressed by a static file without the file going stale: a dataset written today
stops demonstrating a 90-day baseline the moment it is three months old.

So the dates are computed from an anchor - today by default, overridable with
``--anchor`` for reproducible output - and every scenario is positioned relative
to it. Re-running the script always produces data that trips the same rules.

The output is deliberately small and hand-designed. A million random rows would
prove nothing; what a reviewer needs is a dataset where each alert can be traced
back to the exact transactions that caused it, and where the clean customers
prove the rules do *not* fire on ordinary behaviour.

Usage
-----
    python tools/generate_seed_data.py [--out seed] [--anchor 2026-03-01]
"""

from __future__ import annotations

import argparse
import csv
import os
import random
from datetime import date, datetime, timedelta

# Deterministic output: the same anchor must always produce the same file, or a
# reviewer comparing two runs cannot tell a code change from dice.
random.seed(20260101)

CUSTOMER_HEADER = [
    "customer_id", "first_name", "last_name", "gender", "date_of_birth", "age",
    "email", "phone_number", "national_id", "city", "state", "country",
    "postal_code", "occupation", "annual_income", "marital_status",
    "education_level", "employment_status", "customer_since",
    "customer_segment", "kyc_status", "risk_rating", "is_politically_exposed",
    "preferred_channel", "email_verified", "phone_verified",
    "num_complaints_last_year",
]

ACCOUNT_HEADER = [
    "account_id", "customer_id", "account_type", "account_status", "currency",
    "open_date", "close_date", "risk_rating", "branch_code", "branch_city",
    "current_balance", "avg_monthly_balance_6m", "credit_limit",
    "credit_utilization_pct", "overdraft_enabled", "card_type",
    "is_joint_account", "num_linked_devices", "mobile_banking_enrolled",
    "last_login_date", "avg_monthly_txn_count", "account_tier",
]

TRANSACTION_HEADER = [
    "transaction_id", "account_id", "customer_id", "direction",
    "transaction_type", "channel", "amount", "currency",
    "transaction_timestamp", "value_date", "status", "narration",
    "balance_after", "counterparty_name", "counterparty_account",
    "counterparty_bank", "counterparty_bank_code", "counterparty_country",
    "origin_country", "destination_country", "is_cross_border",
    "merchant_category_code", "branch_code", "device_id", "ip_address",
]

CREDIT_TYPES = {
    "CASH_DEPOSIT", "TRANSFER_IN", "WIRE_IN", "CHEQUE_DEPOSIT",
    "LOAN_DISBURSEMENT", "INTEREST_CREDIT",
}

customers: list = []
accounts: list = []
transactions: list = []

_txn_sequence = 0


def customer(cid, first, last, **overrides):
    """Register a customer with sensible defaults for the fields no scenario cares about."""
    row = {
        "customer_id": cid, "first_name": first, "last_name": last,
        "gender": "M", "date_of_birth": "1985-04-12", "age": 40,
        "email": f"{first.lower()}.{last.lower()}@example.com",
        "phone_number": f"+91-98{random.randint(10000000, 99999999)}",
        "national_id": f"NID{random.randint(100000000, 999999999)}",
        "city": "Mumbai", "state": "Maharashtra", "country": "IN",
        "postal_code": "400001", "occupation": "Salaried",
        "annual_income": "850000.00", "marital_status": "MARRIED",
        "education_level": "GRADUATE", "employment_status": "EMPLOYED",
        "customer_since": "2019-06-01", "customer_segment": "RETAIL",
        "kyc_status": "VERIFIED", "risk_rating": "LOW",
        "is_politically_exposed": "N", "preferred_channel": "MOBILE_APP",
        "email_verified": "Y", "phone_verified": "Y",
        "num_complaints_last_year": 0,
    }
    row.update(overrides)
    customers.append(row)
    return cid


def account(aid, cid, **overrides):
    """Register an account.

    Currency defaults to USD so the USD-denominated rule thresholds can be read
    straight off the amount column without doing mental FX.
    """
    row = {
        "account_id": aid, "customer_id": cid, "account_type": "SAVINGS",
        "account_status": "ACTIVE", "currency": "USD",
        "open_date": "2019-06-05", "close_date": "", "risk_rating": "LOW",
        "branch_code": "BR001", "branch_city": "Mumbai",
        "current_balance": "24500.00", "avg_monthly_balance_6m": "21000.00",
        "credit_limit": "0.00", "credit_utilization_pct": "0.00",
        "overdraft_enabled": "N", "card_type": "CLASSIC",
        "is_joint_account": "N", "num_linked_devices": 2,
        "mobile_banking_enrolled": "Y", "last_login_date": "",
        "avg_monthly_txn_count": 22, "account_tier": "SILVER",
    }
    row.update(overrides)
    accounts.append(row)
    return aid


def txn(aid, cid, when, amount, ttype, **overrides):
    """Register a transaction.

    Direction is derived from the type rather than passed in, because a feed
    that can disagree with itself about whether a cash deposit is a credit is a
    feed that will eventually produce a nonsensical alert.
    """
    global _txn_sequence
    _txn_sequence += 1
    row = {
        "transaction_id": f"TXN_{_txn_sequence:07d}",
        "account_id": aid, "customer_id": cid,
        "direction": "CREDIT" if ttype in CREDIT_TYPES else "DEBIT",
        "transaction_type": ttype, "channel": "INTERNET_BANKING",
        "amount": f"{amount:.2f}", "currency": "USD",
        "transaction_timestamp": when.strftime("%Y-%m-%dT%H:%M:%S"),
        "value_date": when.strftime("%Y-%m-%d"), "status": "POSTED",
        "narration": "", "balance_after": "",
        "counterparty_name": "", "counterparty_account": "",
        "counterparty_bank": "", "counterparty_bank_code": "",
        "counterparty_country": "", "origin_country": "IN",
        "destination_country": "IN", "is_cross_border": "N",
        "merchant_category_code": "", "branch_code": "BR001",
        "device_id": f"DEV-{random.randint(1000, 9999)}",
        "ip_address": f"10.20.{random.randint(0, 255)}.{random.randint(1, 254)}",
    }
    row.update(overrides)
    transactions.append(row)
    return row


def build(anchor):
    """Lay out every scenario relative to the anchor date."""
    midnight = datetime.combine(anchor, datetime.min.time())

    def day(offset_days, hour=10, minute=0):
        return midnight - timedelta(days=offset_days) + timedelta(hours=hour, minutes=minute)

    # ------------------------------------------------------------------
    # 1. Clean control customers.
    #
    # These exist to prove the negative. A ruleset that alerts on everything is
    # as useless as one that alerts on nothing, and the only way to show the
    # thresholds discriminate is to include activity that sits just underneath
    # them and must stay silent.
    # ------------------------------------------------------------------
    for n in range(1, 5):
        cid = customer(f"CUST_1{n:04d}", "Clean", f"Control{n}")
        aid = account(f"ACC_1{n:05d}", cid)
        for d in range(60, 0, -1):
            if d % 30 == 0:
                txn(aid, cid, day(d, 9), 3200 + n * 50, "TRANSFER_IN",
                    narration="Monthly salary",
                    counterparty_name="Acme Systems Pvt Ltd")
            txn(aid, cid, day(d, 13, 20), round(random.uniform(18, 240), 2),
                "CARD_PAYMENT", channel="CARD_POS",
                merchant_category_code="5411", narration="Retail purchase")
        # Deliberately just under the CTR threshold: must not alert.
        txn(aid, cid, day(4, 11), 9850.00, "CHEQUE_DEPOSIT", channel="BRANCH",
            narration="Sale proceeds - legitimate, single event")

    # ------------------------------------------------------------------
    # 2. CTR threshold breach (rule CTR_THRESHOLD_10K).
    # A single unambiguous cash deposit over USD 10,000.
    # ------------------------------------------------------------------
    cid = customer("CUST_20001", "Rohit", "Mehra", occupation="Jeweller",
                   customer_segment="PREMIUM", annual_income="4200000.00",
                   risk_rating="MEDIUM")
    aid = account("ACC_20001", cid, account_type="CURRENT",
                  current_balance="88000.00", account_tier="GOLD")
    txn(aid, cid, day(2, 11, 15), 12500.00, "CASH_DEPOSIT",
        channel="CASH_COUNTER", narration="Cash deposit - counter")

    # ------------------------------------------------------------------
    # 3. Structuring (rule STRUCTURING_24H).
    # Five deposits inside one rolling 24-hour window, each parked in the
    # 9,000-9,999.99 band. Individually every one is legal; together they are
    # the textbook evasion pattern, which is exactly why the rule looks at the
    # window rather than at the transaction.
    # ------------------------------------------------------------------
    cid = customer("CUST_20002", "Sunil", "Kapoor", occupation="Trader",
                   risk_rating="MEDIUM", num_complaints_last_year=1)
    aid = account("ACC_20002", cid, account_type="CURRENT", branch_code="BR014",
                  current_balance="51200.00")
    for hour, amount in ((9, 9400.00), (12, 9750.00), (16, 9200.00), (20, 9950.00)):
        txn(aid, cid, day(3, hour), amount, "CASH_DEPOSIT",
            channel="CASH_COUNTER", narration="Cash deposit", branch_code="BR014")
    # Fifth deposit the next morning, still inside 24h of the 20:00 one, and
    # at a different branch - the same hand, a different window.
    txn(aid, cid, day(2, 8, 30), 9600.00, "CASH_DEPOSIT", channel="CASH_COUNTER",
        narration="Cash deposit", branch_code="BR022")

    # ------------------------------------------------------------------
    # 4. Rapid movement / layering (rule RAPID_MOVEMENT_48H).
    # 60,000 arrives and 55,500 (92.5%) leaves within 28 hours, split across
    # three beneficiaries. The account is a conduit, not a store of value.
    # ------------------------------------------------------------------
    cid = customer("CUST_20003", "Farhan", "Sheikh", occupation="Consultant",
                   customer_segment="SME", risk_rating="MEDIUM",
                   annual_income="1800000.00")
    aid = account("ACC_20003", cid, account_type="CURRENT",
                  current_balance="6100.00", avg_monthly_balance_6m="7400.00")
    txn(aid, cid, day(5, 10), 60000.00, "WIRE_IN", channel="SWIFT",
        narration="Inward remittance - consultancy",
        counterparty_name="Meridian Holdings Ltd",
        counterparty_bank="Northern Star Bank", counterparty_country="AE",
        origin_country="AE", destination_country="IN", is_cross_border="Y")
    for hours_later, amount, beneficiary in (
        (4, 24000.00, "Larkspur Ventures"),
        (19, 19500.00, "Cobalt Trade Partners"),
        (28, 12000.00, "Westbridge Logistics"),
    ):
        txn(aid, cid, day(5, 10) + timedelta(hours=hours_later), amount,
            "TRANSFER_OUT", channel="RTGS", narration="Outward transfer",
            counterparty_name=beneficiary,
            counterparty_account=f"99{random.randint(10000000, 99999999)}")

    # ------------------------------------------------------------------
    # 5. High-risk jurisdiction exposure (rule HIGH_RISK_JURISDICTION).
    # Amount is irrelevant to this rule; exposure to a call-for-action
    # jurisdiction is reportable on its own.
    # ------------------------------------------------------------------
    cid = customer("CUST_20004", "Imran", "Qureshi", occupation="Exporter",
                   customer_segment="SME", risk_rating="HIGH",
                   annual_income="6500000.00")
    aid = account("ACC_20004", cid, account_type="CURRENT", risk_rating="HIGH",
                  current_balance="143000.00", account_tier="PLATINUM")
    txn(aid, cid, day(6, 14, 5), 7400.00, "WIRE_OUT", channel="SWIFT",
        narration="Machinery parts settlement",
        counterparty_name="Zarand Petrochem FZE", counterparty_bank="Bank Mellat",
        counterparty_bank_code="BKMTIRTH", counterparty_country="IR",
        origin_country="IN", destination_country="IR", is_cross_border="Y")
    txn(aid, cid, day(1, 16, 40), 3150.00, "WIRE_IN", channel="SWIFT",
        narration="Advance against export order",
        counterparty_name="Pyongsong Metals JV", counterparty_country="KP",
        origin_country="KP", destination_country="IN", is_cross_border="Y")

    # ------------------------------------------------------------------
    # 6. Behavioural deviation (rule BEHAVIOURAL_DEVIATION_3X).
    # A hundred days of very consistent low-value activity, then one day at
    # roughly eleven times the baseline. No single transaction breaches any
    # amount threshold - only the comparison against the customer's own history
    # makes this visible, which is the whole point of the rule.
    # ------------------------------------------------------------------
    cid = customer("CUST_20005", "Anita", "Deshpande", gender="F",
                   occupation="Teacher", annual_income="620000.00")
    aid = account("ACC_20005", cid, avg_monthly_txn_count=18,
                  current_balance="14200.00")
    for d in range(100, 1, -1):
        txn(aid, cid, day(d, 11), round(random.uniform(120, 210), 2),
            "CARD_PAYMENT", channel="CARD_POS", merchant_category_code="5812",
            narration="Everyday spend")
        if d % 30 == 0:
            txn(aid, cid, day(d, 8), 1450.00, "TRANSFER_IN", narration="Salary")
    for hour, amount in ((9, 1900.00), (11, 2250.00), (14, 1750.00), (17, 2400.00)):
        txn(aid, cid, day(1, hour), amount, "TRANSFER_OUT", channel="IMPS",
            narration="Outward transfer",
            counterparty_name="Harbour Point Traders")

    # ------------------------------------------------------------------
    # 7. Round-amount pattern (rule ROUND_AMOUNT_PATTERN).
    # Real payments carry odd cents because they settle real invoices. Exact
    # thousands repeated within a day are manufactured.
    # ------------------------------------------------------------------
    cid = customer("CUST_20006", "Vikram", "Nair", occupation="Contractor",
                   customer_segment="SME", annual_income="2100000.00")
    aid = account("ACC_20006", cid, account_type="CURRENT",
                  current_balance="37000.00")
    for hour, amount in ((9, 5000.00), (12, 3000.00), (15, 2000.00), (18, 4000.00)):
        txn(aid, cid, day(4, hour), amount, "TRANSFER_OUT", channel="NEFT",
            narration="Sub-contractor settlement",
            counterparty_name="Pinnacle Works",
            counterparty_account="88214500991")

    # ------------------------------------------------------------------
    # 8. High-risk counterparty (rule HIGH_RISK_COUNTERPARTY), on a PEP.
    # Domestic, mid-sized, unremarkable - except for who is on the other side.
    # ------------------------------------------------------------------
    cid = customer("CUST_20007", "Priya", "Chandran", gender="F",
                   occupation="Company Director", customer_segment="HNI",
                   is_politically_exposed="Y", risk_rating="HIGH",
                   annual_income="9800000.00")
    aid = account("ACC_20007", cid, account_type="CURRENT", risk_rating="HIGH",
                  current_balance="264000.00", account_tier="PLATINUM",
                  card_type="INFINITE")
    txn(aid, cid, day(3, 15, 25), 6800.00, "TRANSFER_OUT", channel="RTGS",
        narration="Consultancy retainer",
        counterparty_name="Volkov Trading LLC",
        counterparty_account="40817810099",
        counterparty_bank="Northern Star Bank")
    txn(aid, cid, day(7, 10, 5), 4300.00, "TRANSFER_OUT", channel="NEFT",
        narration="Platform settlement", counterparty_name="Apex Crypto Exchange")

    # ------------------------------------------------------------------
    # 9. Dormant reactivation (rule DORMANT_REACTIVATION).
    # Nothing for over a year, then a large credit immediately pushed onward -
    # the classic rented mule account.
    # ------------------------------------------------------------------
    cid = customer("CUST_20008", "Sanjay", "Bhatt", occupation="Retired",
                   employment_status="RETIRED", annual_income="240000.00",
                   age=68, date_of_birth="1957-02-19")
    aid = account("ACC_20008", cid, account_status="DORMANT",
                  current_balance="310.00", avg_monthly_balance_6m="310.00",
                  avg_monthly_txn_count=0, account_tier="BASIC")
    txn(aid, cid, day(400, 10), 180.00, "INTEREST_CREDIT",
        narration="Quarterly interest")
    txn(aid, cid, day(8, 12, 30), 24000.00, "TRANSFER_IN", channel="IMPS",
        narration="Inward transfer", counterparty_name="Silverline Exports")
    txn(aid, cid, day(8, 18, 45), 21500.00, "CASH_WITHDRAWAL", channel="ATM",
        narration="ATM withdrawal - multiple")

    # ------------------------------------------------------------------
    # 10. Multi-rule escalation.
    #
    # One account that trips structuring, rapid movement and jurisdiction
    # exposure in the same week. This is the case that demonstrates risk-score
    # aggregation and alert de-duplication: several rules, one investigation.
    # ------------------------------------------------------------------
    cid = customer("CUST_20009", "Deepak", "Raghav", occupation="Import-Export",
                   customer_segment="SME", risk_rating="HIGH",
                   annual_income="5400000.00", num_complaints_last_year=2)
    aid = account("ACC_20009", cid, account_type="CURRENT", risk_rating="HIGH",
                  branch_code="BR031", branch_city="Surat",
                  current_balance="9800.00", avg_monthly_balance_6m="11500.00")
    for hour, amount in ((10, 9300.00), (13, 9800.00), (17, 9100.00)):
        txn(aid, cid, day(9, hour), amount, "CASH_DEPOSIT",
            channel="CASH_COUNTER", narration="Cash deposit", branch_code="BR031")
    txn(aid, cid, day(9, 21), 25000.00, "WIRE_OUT", channel="SWIFT",
        narration="Supplier settlement",
        counterparty_name="Golden Sands Exchange", counterparty_country="MM",
        origin_country="IN", destination_country="MM", is_cross_border="Y")
    txn(aid, cid, day(8, 11), 15000.00, "WIRE_IN", channel="SWIFT",
        narration="Inward remittance", counterparty_name="Harbour Freight DMCC",
        counterparty_country="AE", origin_country="AE", destination_country="IN",
        is_cross_border="Y")
    txn(aid, cid, day(8, 20), 14000.00, "TRANSFER_OUT", channel="RTGS",
        narration="Onward transfer", counterparty_name="Crescent Metals")

    transactions.sort(key=lambda r: r["transaction_timestamp"])


def write(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, header, rows in (
        ("customers.csv", CUSTOMER_HEADER, customers),
        ("accounts.csv", ACCOUNT_HEADER, accounts),
        ("transactions.csv", TRANSACTION_HEADER, transactions),
    ):
        path = os.path.join(out_dir, name)
        with open(path, "w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=header, extrasaction="raise")
            writer.writeheader()
            writer.writerows(rows)
        print(f"{path}: {len(rows)} rows")


def main():
    parser = argparse.ArgumentParser(description="Generate Sentinel AML seed data")
    parser.add_argument("--out", default="seed", help="output directory (default: seed)")
    parser.add_argument(
        "--anchor",
        default=None,
        help="ISO date the scenarios are positioned relative to (default: today)",
    )
    args = parser.parse_args()
    anchor = date.fromisoformat(args.anchor) if args.anchor else date.today()
    build(anchor)
    write(args.out)
    print(f"anchor date: {anchor.isoformat()}")


if __name__ == "__main__":
    main()
