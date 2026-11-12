package com.starhub;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Startup policy: the JDK must not transparently repeat a provider request. */
final class ProviderTransportPolicy {
    static final Map<String,String> FLAGS=Map.of(
        "sun.net.http.retryPost","false",
        "jdk.httpclient.disableRetryConnect","true",
        "jdk.httpclient.enableAllMethodRetry","false",
        "jdk.httpclient.redirects.retrylimit","1");
    static void require()throws StorageFailure {
        if(!valid(ManagementFactory.getRuntimeMXBean().getInputArguments(),System.getProperties()))
            throw new StorageFailure(503,"PROVIDER_TRANSPORT_POLICY","模型服务的禁止自动重试策略尚未通过启动检查；本次请求未获得发送许可。");
    }
    static boolean valid(List<String> arguments,Properties properties){
        for(var flag:FLAGS.entrySet()){
            String prefix="-D"+flag.getKey()+"=",atStartup=null;
            for(String arg:arguments)if(arg.startsWith(prefix))atStartup=arg.substring(prefix.length());
            if(!flag.getValue().equals(atStartup)||!flag.getValue().equals(properties.getProperty(flag.getKey())))return false;
        }
        // JDK HTTP header/content logging could disclose API keys and conversation data.
        String logging=properties.getProperty("jdk.httpclient.HttpClient.log","none");
        return (logging.isBlank()||logging.equals("none"))&&!Boolean.parseBoolean(properties.getProperty("jdk.internal.httpclient.disableHostnameVerification","false"));
    }
    private static final class Client {
        static final HttpClient VALUE=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    static HttpClient client()throws StorageFailure {require();return Client.VALUE;}
    record Reply(int status,String contentType,byte[] bytes){}
    static void writeBody(HttpURLConnection connection,byte[] body)throws IOException {
        require();connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);
        try(OutputStream output=connection.getOutputStream()){output.write(body);}
    }
    static Reply get(URI uri,Map<String,String> headers,int timeoutSeconds,int limit)throws IOException {
        HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds)).GET();headers.forEach(request::header);
        CompletableFuture<HttpResponse<byte[]>> pending=client().sendAsync(request.build(),info->new LimitedBody(limit));
        try{
            HttpResponse<byte[]> response=pending.get(timeoutSeconds,TimeUnit.SECONDS);
            return new Reply(response.statusCode(),response.headers().firstValue("Content-Type").orElse("application/json"),response.body());
        }catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();throw new IOException("Provider request interrupted",interrupted);}
        catch(TimeoutException timeout){pending.cancel(true);throw new java.net.SocketTimeoutException("Provider response timed out");}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof IOException io)throw io;throw new IOException("Provider request failed",cause);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final int limit;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final CompletableFuture<byte[]> result=new CompletableFuture<>();Flow.Subscription subscription;
        LimitedBody(int limit){if(limit<0)throw new IllegalArgumentException();this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription incoming){if(subscription!=null){incoming.cancel();return;}subscription=incoming;incoming.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){if(buffer.remaining()>limit-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("Provider response exceeds the allowed size"));return;}
                byte[] chunk=new byte[Math.min(8192,buffer.remaining())];while(buffer.hasRemaining()){int length=Math.min(chunk.length,buffer.remaining());buffer.get(chunk,0,length);bytes.write(chunk,0,length);}}
            subscription.request(1);
        }
        public void onError(Throwable failed){result.completeExceptionally(failed);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private ProviderTransportPolicy(){}
}
