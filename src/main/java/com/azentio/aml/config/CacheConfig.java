package com.azentio.aml.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-process caches for the small, read-heavy reference tables (FX rates, rule configuration,
 * watchlists) that the detection engine touches on every transaction.
 *
 * <p>A simple concurrent-map cache is intentional: these datasets are kilobytes, and every write
 * path evicts explicitly, so a distributed cache would add operational weight without benefit. The
 * cache names are declared up front so a typo in an eviction annotation fails fast.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String FX_RATES = "fxRates";
    public static final String ACTIVE_RULES = "activeRules";
    public static final String WATCHLIST_VALUES = "watchlistValues";

    @Bean
    public ConcurrentMapCacheManager cacheManager() {
        ConcurrentMapCacheManager manager =
                new ConcurrentMapCacheManager(FX_RATES, ACTIVE_RULES, WATCHLIST_VALUES);
        manager.setAllowNullValues(false);
        return manager;
    }
}
