package com.starhub;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.*;
import java.util.Properties;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit synthetic capacity transport; production endpoint validation remains active. */
final class ProbeHttpFixture {
    static Properties endpoints(Properties p){for(int node:new int[]{1,2})p.setProperty("storage.node"+node+".probe.url","http://127.0.0.1:"+(940+node)+"/status");return p;}
    static HttpClient actualDirectClient()throws Exception {var field=StorageCluster.class.getDeclaredField("HTTP");field.setAccessible(true);return (HttpClient)field.get(null);}
    @SuppressWarnings({"unchecked","rawtypes"})
    static HttpClient synthetic(HttpServer server)throws Exception {
        HttpClient proxy=mock(HttpClient.class),actual=actualDirectClient();
        when(proxy.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            HttpRequest original=call.getArgument(0);int node=original.uri().getPort()-940;
            if(node<1||node>2||!original.uri().toString().equals("http://127.0.0.1:"+(940+node)+"/status"))throw new AssertionError("Only the validated private probe may reach this synthetic adapter");
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/status?node="+node)).timeout(original.timeout().orElseThrow()).GET();
            original.headers().map().forEach((name,values)->values.forEach(value->request.header(name,value)));
            return actual.sendAsync(request.build(),(HttpResponse.BodyHandler)call.getArgument(1));
        });return proxy;
    }
    static StorageCluster cluster(Properties p,HttpServer server)throws Exception{return new StorageCluster(endpoints(p),synthetic(server));}
}
