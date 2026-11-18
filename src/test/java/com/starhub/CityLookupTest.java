package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.HttpServletRequest;

class CityLookupTest {
    @Test void apiExposesBoundedJsonWithoutExternalRequest() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn("/maps/cities");
        when(request.getParameter("q")).thenReturn("安徽省滁州市");
        var capture = new AtomicTransactionsTest.Capture();
        new ApiServlet().doGet(request, capture.response);
        assertEquals(200, capture.status.get());
        var body = ModelGateway.JSON.readTree(capture.bytes.toByteArray());
        assertEquals("1814757", body.path("matches").get(0).path("id").asText());
        assertTrue(body.path("matches").get(0).path("latitude").isNumber());
        assertFalse(body.path("truncated").asBoolean());
    }
    @Test void resolvesChineseProvinceWithoutInventingBoundaries() throws Exception {
        CityLookup.Result result = CityLookup.find("安徽省滁州市");
        assertFalse(result.truncated());
        assertEquals(1, result.matches().size());
        var city = result.matches().get(0);
        assertEquals("1814757", city.id());
        assertTrue(city.label().contains("安徽"));
        assertEquals("CN", city.country());
        assertEquals(32.32194, city.latitude(), 0.00001);
        assertEquals(118.29778, city.longitude(), 0.00001);
        assertTrue(CityLookup.find("浙江省滁州市").matches().isEmpty());
        assertEquals("1814757", CityLookup.find("中国安徽省滁州市").matches().get(0).id());
        assertEquals("1814757", CityLookup.find("Chuzhou, Anhui, China").matches().get(0).id());
        assertTrue(CityLookup.find("Chuzhou, Zhejiang, China").matches().isEmpty());
        assertTrue(CityLookup.find("Chuzhou, Anhui, UK").matches().isEmpty());
    }
    @Test void preservesAmbiguityAndRespectsCountryQualifier() throws Exception {
        CityLookup.Result all = CityLookup.find("Sheffield");
        assertTrue(all.matches().size() > 1 || all.truncated());
        var uk = CityLookup.find("Sheffield, UK").matches();
        assertTrue(uk.stream().anyMatch(c -> c.id().equals("2638077")));
        assertTrue(uk.stream().allMatch(c -> c.country().equals("GB")));
        var england = CityLookup.find("SHEFFIELD, ENGLAND").matches();
        assertTrue(england.stream().anyMatch(c -> c.id().equals("2638077")));
        assertTrue(england.stream().allMatch(c -> c.country().equals("GB") && c.label().contains("England")));
        assertFalse(CityLookup.find("Sheffield, Scotland").matches().stream().anyMatch(c -> c.id().equals("2638077")));
        assertTrue(CityLookup.find("Sheffield, England, China").matches().isEmpty());
        assertTrue(CityLookup.find("Sheffield, UnrecognizedCountry").matches().isEmpty());
    }
    @Test void exactMatchingAndBounds() throws Exception {
        assertTrue(CityLookup.find("滁州不存在").matches().isEmpty());
        assertTrue(CityLookup.find("x".repeat(121)).matches().isEmpty());
        assertTrue(CityLookup.find("谢菲尔德, 英国").matches().stream().anyMatch(c -> c.id().equals("2638077")));
        var chineseUk = CityLookup.find("英国谢菲尔德").matches();
        assertTrue(chineseUk.stream().anyMatch(c -> c.id().equals("2638077")));
        assertTrue(chineseUk.stream().allMatch(c -> c.country().equals("GB")));
    }
    @Test void handlesConcurrentLookupsWithoutUnboundedAllocation() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i=0; i<100; i++) futures.add(pool.submit(() -> {
                try { assertFalse(CityLookup.find("安徽省滁州市").matches().isEmpty()); }
                catch (CityLookup.Busy permitted) { /* Admission is intentionally bounded. */ }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }));
            for (var future : futures) future.get();
        } finally { pool.shutdownNow(); }
    }
}
