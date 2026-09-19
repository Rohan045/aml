package com.azentio.aml.repository.projection;

/** Count of alerts grouped by a single dimension (typology, status or severity). */
public interface AlertCountByGroup {

    String getGroupKey();

    long getTotal();
}
