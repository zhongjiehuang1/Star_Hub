package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="STARHUB_GEOIP_DB", matches=".+")
class GeoDatabaseTest {
    @Test void databaseCanLookUpKnownIpsAndFailsClosedForUnknown() {
        assertEquals("US", GeoCountry.forRequest("127.0.0.1", "8.8.8.8"));
        assertEquals("CN", GeoCountry.forRequest("127.0.0.1", "223.5.5.5"));
        assertEquals("HK", GeoCountry.forRequest("127.0.0.1", "202.181.243.1"));
        assertEquals("MO", GeoCountry.forRequest("127.0.0.1", "202.175.3.8"));
        assertEquals("GB", GeoCountry.forRequest("127.0.0.1", "81.2.69.142"));
        assertNull(GeoCountry.forRequest("127.0.0.1", "127.0.0.1"));
        assertNull(GeoCountry.forRequest("203.0.113.2", "8.8.8.8"));
    }
}
