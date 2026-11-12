package com.starhub;

import org.junit.jupiter.api.Test;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplicationResponseGuardTest {
    @Test void lossOfReadinessBeforeFirstSuccessByteDoesNotAcknowledge()throws Exception{
        HttpServletResponse response=mock(HttpServletResponse.class);when(response.getStatus()).thenReturn(200);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream(){
            public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}
            public void write(int value){bytes.write(value);}
        });
        AtomicBoolean ready=new AtomicBoolean(false);AtomicInteger checks=new AtomicInteger();
        ReplicationResponseGuard guard=new ReplicationResponseGuard(response,()->{
            checks.incrementAndGet();if(!ready.get())throw StorageFailure.unavailable();
        });
        ServletOutputStream stream=guard.getOutputStream();
        assertThrows(StorageFailure.class,()->stream.write("success".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(0,bytes.size());
        ready.set(true);stream.write("success".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ready.set(false);stream.write('!');assertEquals("success!",bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2,checks.get());
    }
    @Test void unsuccessfulResponsesDoNotNeedReplicationConfirmation()throws Exception{
        HttpServletResponse response=mock(HttpServletResponse.class);when(response.getStatus()).thenReturn(503);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream(){
            public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}
            public void write(int value){bytes.write(value);}
        });
        ReplicationResponseGuard guard=new ReplicationResponseGuard(response,()->{throw new AssertionError("must not confirm a failure");});
        guard.getOutputStream().write('x');guard.confirmIfSuccessful();assertEquals(1,bytes.size());
    }
    @Test void emptySuccessfulResponseIsCheckedBeforeFlush()throws Exception{
        HttpServletResponse response=mock(HttpServletResponse.class);when(response.getStatus()).thenReturn(204);
        ReplicationResponseGuard guard=new ReplicationResponseGuard(response,()->{throw StorageFailure.unavailable();});
        assertThrows(StorageFailure.class,guard::flushBuffer);verify(response,never()).flushBuffer();
    }
}

