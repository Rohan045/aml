package com.azentio.aml.domain.enums;

/** Source list a watchlist entry came from; drives the risk weight applied by the rule engine. */
public enum WatchlistType {
    SANCTIONS,
    FATF_BLACKLIST,
    FATF_GREYLIST,
    TAX_HAVEN,
    PEP,
    ADVERSE_MEDIA,
    INTERNAL_HIGH_RISK
}
