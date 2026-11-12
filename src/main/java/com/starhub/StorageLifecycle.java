package com.starhub;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.WebListener;
import java.util.concurrent.*;

@WebListener
public final class StorageLifecycle implements ServletContextListener {
    interface Initializer { FileStorage create()throws Exception; }
    private final Initializer initializer;
    private final Object lock=new Object();
    private ScheduledExecutorService retries;
    private boolean closing,initializing,reportedFailure;

    public StorageLifecycle(){this(()->{
        var config=AccountServlet.config();
        return "true".equals(config.getProperty("storage.coordinator"))?new FileStorage(config):null;
    });}
    StorageLifecycle(Initializer initializer){this.initializer=initializer;}

    public void contextInitialized(ServletContextEvent event) {
        synchronized(lock){
            if(closing||retries!=null)return;
            retries=Executors.newSingleThreadScheduledExecutor(work->{Thread thread=new Thread(work,"starhub-storage-initialization");thread.setDaemon(true);return thread;});
            retries.scheduleWithFixedDelay(()->attempt(event.getServletContext()),0,5,TimeUnit.SECONDS);
        }
    }

    void attempt(ServletContext context){
        synchronized(lock){if(closing||initializing||FileStorage.active!=null)return;initializing=true;}
        FileStorage candidate=null;
        try{
            candidate=initializer.create();
            synchronized(lock){
                if(!closing&&FileStorage.active==null&&candidate!=null){FileStorage.active=candidate;candidate=null;reportedFailure=false;}
            }
        }catch(Exception failure){
            synchronized(lock){if(!closing&&!reportedFailure){context.log("Star Hub storage remains unavailable: initialization will retry.");reportedFailure=true;}}
        }finally{
            // A constructor finishing after shutdown must never publish a coordinator.
            if(candidate!=null)try{candidate.close();}catch(Exception failure){context.log("Star Hub storage requires recovery before reopening.");}
            synchronized(lock){initializing=false;}
        }
    }

    public void contextDestroyed(ServletContextEvent event) {
        FileStorage service;ScheduledExecutorService executor;
        synchronized(lock){closing=true;executor=retries;if(executor!=null)executor.shutdownNow();service=FileStorage.active;FileStorage.active=null;}
        if(executor!=null)try{executor.awaitTermination(20,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        if(service!=null)try{service.close();}catch(Exception failure){event.getServletContext().log("Star Hub storage requires recovery before reopening.");}
    }
}
