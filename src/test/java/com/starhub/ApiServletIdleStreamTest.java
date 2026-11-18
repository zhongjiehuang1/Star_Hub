package com.starhub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiServletIdleStreamTest {
    private static String event(String text){return "data: {\"choices\":[{\"delta\":{\"content\":\""+text+"\"}}]}\n\n";}
    @Test void servletStopsDeliveringExpiredStreamButDurablyKeepsTheWholePaidResult()throws Exception{
        try(var h=new ProviderOperationsTest.Harness()){
            String id=UUID.randomUUID().toString(),first=event("visible-before-expiry"),second=event("private-after-expiry"),all=first+second+"data: [DONE]\n\n";
            var input=h.input(id);h.operations.claim(h.owner,id,"test:generate",input);var ticket=h.operations.dispatch(h.owner,id,"test:generate",input);
            AtomicLong time=new AtomicLong();byte[] bytes=all.getBytes(StandardCharsets.UTF_8);int cutoff=first.getBytes(StandardCharsets.UTF_8).length;
            var fake=new ChatStream((prepared,handler)->handler.accept(200,"text/event-stream",new InputStream(){int index;
                public int read(){if(index==cutoff)time.set(2_000_000);return index<bytes.length?bytes[index++]&255:-1;}
                public int read(byte[] b,int off,int len){int value=read();if(value<0)return -1;b[off]=(byte)value;return 1;}
            }));
            var gate=new ResultReadGate(()->{if(time.get()>=2_000_000)throw new StorageFailure(401,"SESSION_ENDED","ended");return 1;},()->true,time::get);
            var res=new AtomicTransactionsTest.Capture();var servlet=new ApiServlet(fake);
            try(var context=ticket.enter()){ProviderOperations.beforeExternal();servlet.relayStream(res.response,ModelGateway.prepare(new ModelGatewayTest().request("deepseek"),true),null,h.operations,h.owner,ticket,gate);}
            String delivered=res.bytes.toString(StandardCharsets.UTF_8);assertTrue(delivered.contains("visible-before-expiry"));assertFalse(delivered.contains("private-after-expiry"));assertTrue(delivered.contains("SESSION_ENDED"));
            var stored=h.other().status(h.owner,id);assertEquals("COMPLETED",stored.state());assertEquals("visible-before-expiryprivate-after-expiry",stored.result().path("text").asText());
        }
    }
    @Test void servletContinuesToPersistPaidOutputAfterTheClientDisconnects()throws Exception{
        try(var h=new ProviderOperationsTest.Harness()){
            String id=UUID.randomUUID().toString(),all=event("durable-result")+"data: [DONE]\n\n";var input=h.input(id);h.operations.claim(h.owner,id,"test:generate",input);var ticket=h.operations.dispatch(h.owner,id,"test:generate",input);
            var fake=new ChatStream((prepared,handler)->handler.accept(200,"text/event-stream",new ByteArrayInputStream(all.getBytes(StandardCharsets.UTF_8))));
            var gate=new ResultReadGate(()->600000,()->true);HttpServletResponse res=mock(HttpServletResponse.class);
            when(res.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}public void write(int value)throws IOException{throw new EOFException("synthetic disconnect");}});
            try(var context=ticket.enter()){ProviderOperations.beforeExternal();new ApiServlet(fake).relayStream(res,ModelGateway.prepare(new ModelGatewayTest().request("deepseek"),true),null,h.operations,h.owner,ticket,gate);}
            assertEquals("durable-result",h.other().status(h.owner,id).result().path("text").asText());
        }
    }
}
