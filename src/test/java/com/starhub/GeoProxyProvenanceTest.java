package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GeoProxyProvenanceTest {
    @Test void rawLoopbackProxyPreservesItsSingleClientAddress(){
        assertEquals("8.8.8.8",GeoCountry.trustedClientAddress("127.0.0.1","8.8.8.8",null));
        assertEquals("8.8.8.8",GeoCountry.trustedClientAddress("::1","8.8.8.8",false));
    }
    @Test void containerMarkedForwardedAddressMustMatchTheEntryHeader(){
        assertEquals("8.8.8.8",GeoCountry.trustedClientAddress("8.8.8.8","8.8.8.8",Boolean.TRUE));
        assertNull(GeoCountry.trustedClientAddress("223.5.5.5","8.8.8.8",Boolean.TRUE));
    }
    @Test void ordinaryClientHeadersCannotImpersonateTheContainerMarker(){
        assertNull(GeoCountry.trustedClientAddress("8.8.8.8","8.8.8.8",null));
        assertNull(GeoCountry.trustedClientAddress("8.8.8.8","8.8.8.8","true"));
        assertNull(GeoCountry.trustedClientAddress("8.8.8.8","8.8.8.8",Boolean.FALSE));
    }
    @Test void malformedListsNamesScopesAndNoncanonicalIPv4FailClosed(){
        for(String value:new String[]{"example.com","bad","127.1","8.8.8.8, 223.5.5.5","8.8.8.8:443","fe80::1%en0","999.8.8.8",""}){
            assertNull(GeoCountry.trustedClientAddress("127.0.0.1",value,Boolean.TRUE),value);
            assertNull(GeoCountry.trustedClientAddress(value,"8.8.8.8",Boolean.TRUE),value);
        }
    }
}
