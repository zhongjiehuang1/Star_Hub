package com.starhub;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/** Confirm the writable replication view before a successful response is committed. */
final class ReplicationResponseGuard extends HttpServletResponseWrapper {
    private final Runnable confirm;
    private boolean confirmed;
    private ServletOutputStream stream;
    private PrintWriter writer;

    ReplicationResponseGuard(HttpServletResponse response,Runnable confirm){super(response);this.confirm=confirm;}

    void confirmIfSuccessful(){
        int status=getStatus();
        if(!confirmed&&status>=200&&status<400){confirm.run();confirmed=true;}
    }

    @Override public ServletOutputStream getOutputStream()throws IOException{
        if(writer!=null)throw new IllegalStateException("Writer already selected");
        if(stream==null){
            ServletOutputStream target=super.getOutputStream();
            stream=new ServletOutputStream(){
                @Override public boolean isReady(){return target.isReady();}
                @Override public void setWriteListener(WriteListener listener){target.setWriteListener(listener);}
                @Override public void write(int value)throws IOException{confirmIfSuccessful();target.write(value);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{
                    if(length>0)confirmIfSuccessful();target.write(bytes,offset,length);
                }
                @Override public void flush()throws IOException{confirmIfSuccessful();target.flush();}
                @Override public void close()throws IOException{confirmIfSuccessful();target.close();}
            };
        }
        return stream;
    }

    @Override public PrintWriter getWriter()throws IOException{
        if(stream!=null)throw new IllegalStateException("Output stream already selected");
        if(writer==null){
            PrintWriter target=super.getWriter();
            writer=new PrintWriter(new Writer(){
                @Override public void write(char[] chars,int offset,int length){if(length>0)confirmIfSuccessful();target.write(chars,offset,length);}
                @Override public void flush(){confirmIfSuccessful();target.flush();}
                @Override public void close(){confirmIfSuccessful();target.close();}
            });
        }
        return writer;
    }

    @Override public void flushBuffer()throws IOException{confirmIfSuccessful();super.flushBuffer();}
    @Override public void sendRedirect(String location)throws IOException{confirmIfSuccessful();super.sendRedirect(location);}
}

