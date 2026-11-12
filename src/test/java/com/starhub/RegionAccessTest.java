package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;

class RegionAccessTest {
    @Test void operationalEndpointsReachTheirOwnHealthAndBearerChecksWithoutGeoLookup()throws Exception{
        for(String path:new String[]{"/health","/server","/cluster-health","/internal/recovery-ready"}){
            HttpServletRequest req=mock(HttpServletRequest.class);HttpServletResponse res=mock(HttpServletResponse.class);FilterChain chain=mock(FilterChain.class);
            when(req.getMethod()).thenReturn("GET");when(req.getServletPath()).thenReturn("/api");when(req.getPathInfo()).thenReturn(path);
            new SecurityFilter(()->null).doFilter(req,res,chain);
            verify(chain).doFilter(req,res);verify(req,never()).getSession(anyBoolean());
        }
    }
    @Test void regionPolicyFailsClosedAndAllowsVerifiedSession() {
        for (String code : new String[]{null, "", "XX", " xx ", "CN", "hk", "MO", "invalid"}) assertTrue(RegionAccess.requiresVerification(code));
        assertFalse(RegionAccess.requiresVerification("GB"));
        assertFalse(RegionAccess.requiresVerification("US"));
        HttpSession session = mock(HttpSession.class);
        assertFalse(RegionAccess.allowed("CN", session));
        when(session.getAttribute(RegionAccess.VERIFIED)).thenReturn(true);
        assertTrue(RegionAccess.allowed("CN", session));
    }
    @Test void forgedForwardedIpFromNonLoopbackIsIgnored() {
        assertNull(GeoCountry.forRequest("203.0.113.2", "8.8.8.8"));
        assertNull(GeoCountry.forRequest("127.0.0.1", "8.8.8.8, 1.1.1.1"));
    }
    @Test void unknownLocationGateRejectsWrongPasswordAndAcceptsCorrectPassword() throws Exception {
        Map<String,Object> attributes = new HashMap<>();
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
        doAnswer(i -> { attributes.put(i.getArgument(0), i.getArgument(1)); return null; }).when(session).setAttribute(anyString(), any());
        doAnswer(i -> { attributes.remove(i.getArgument(0)); return null; }).when(session).removeAttribute(anyString());
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        StringWriter body = new StringWriter();
        when(res.getWriter()).thenReturn(new PrintWriter(body, true));
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getSession(false)).thenReturn(session);
        when(req.getSession(true)).thenReturn(session);
        when(req.getContextPath()).thenReturn("/starhub");
        when(req.getServletPath()).thenReturn("/original");
        when(req.getRequestURI()).thenReturn("/starhub/original");
        when(req.getQueryString()).thenReturn("x=1");
        when(req.getMethod()).thenReturn("GET");
        SecurityFilter filter = new SecurityFilter(() -> "test-password");
        filter.doFilter(req, res, chain);
        assertTrue(body.toString().contains("暂时无法确认"));
        assertEquals("/starhub/original?x=1", attributes.get(RegionAccess.RETURN_TO));
        String token = (String) attributes.get(RegionAccess.TOKEN);
        assertNotNull(token);
        when(req.getServletPath()).thenReturn("/_access");
        when(req.getRequestURI()).thenReturn("/starhub/_access");
        when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(req.getParameter("starhub_access_token")).thenReturn(token);
        when(req.getParameter("starhub_access_password")).thenReturn("wrong");
        filter.doFilter(req, res, chain);
        assertTrue(body.toString().contains("验证失败"));
        assertNull(attributes.get(RegionAccess.VERIFIED));
        when(req.getParameter("starhub_access_password")).thenReturn("test-password");
        filter.doFilter(req, res, chain);
        assertEquals(true, attributes.get(RegionAccess.VERIFIED));
        verify(req).changeSessionId();
        verify(res).setStatus(303);
        verify(res).setHeader("Location", "/starhub/original?x=1");
        assertNull(attributes.get(RegionAccess.TOKEN));
        verify(chain, never()).doFilter(any(), any());
    }
    @Test void missingPasswordDoesNotSilentlyAllowAccess() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class); HttpServletResponse res = mock(HttpServletResponse.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        new SecurityFilter(() -> null).doFilter(req, res, mock(FilterChain.class));
        verify(res).sendError(503, "Tester access is not configured");
    }
}
