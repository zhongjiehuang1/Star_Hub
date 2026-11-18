package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GmailCallbackPrivacyTest {
    @Test void blockedCallbackNeverStoresOrRedirectsItsQuery() throws Exception {
        Map<String,Object> attributes=new HashMap<>();HttpSession session=mock(HttpSession.class);
        when(session.getAttribute(anyString())).thenAnswer(i->attributes.get(i.getArgument(0)));
        doAnswer(i->{attributes.put(i.getArgument(0),i.getArgument(1));return null;}).when(session).setAttribute(anyString(),any());
        doAnswer(i->{attributes.remove(i.getArgument(0));return null;}).when(session).removeAttribute(anyString());
        HttpServletRequest req=mock(HttpServletRequest.class);HttpServletResponse res=mock(HttpServletResponse.class);
        StringWriter body=new StringWriter();when(res.getWriter()).thenReturn(new PrintWriter(body,true));
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");when(req.getSession(false)).thenReturn(session);when(req.getSession(true)).thenReturn(session);
        when(req.getContextPath()).thenReturn("/starhub");when(req.getServletPath()).thenReturn("/api/gmail");when(req.getPathInfo()).thenReturn("/callback");
        when(req.getRequestURI()).thenReturn("/starhub/api/gmail/callback");when(req.getQueryString()).thenReturn("code=secret-code&state=secret-state");when(req.getMethod()).thenReturn("GET");
        SecurityFilter filter=new SecurityFilter(()->"test-password");filter.doFilter(req,res,mock(FilterChain.class));
        assertEquals("/starhub/",attributes.get(RegionAccess.RETURN_TO));verify(req,never()).getQueryString();
        assertFalse(attributes.toString().contains("secret-code"));assertFalse(body.toString().contains("secret-state"));
        when(req.getServletPath()).thenReturn("/_access");when(req.getPathInfo()).thenReturn(null);when(req.getRequestURI()).thenReturn("/starhub/_access");when(req.getMethod()).thenReturn("POST");
        when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");when(req.getParameter("starhub_access_token")).thenReturn((String)attributes.get(RegionAccess.TOKEN));when(req.getParameter("starhub_access_password")).thenReturn("test-password");
        filter.doFilter(req,res,mock(FilterChain.class));verify(res).setHeader("Location","/starhub/");
    }
}
