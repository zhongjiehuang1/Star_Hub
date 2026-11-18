package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Explicit, isolated stress harness; no external provider and no production DB.
 * Run separately in a 512 MiB heap with either argument "account" or "guest".
 * H2 runs in-process, unlike production MySQL, so RSS/heap figures are not a
 * production workload capacity guarantee. */
public final class ImageEditMemoryHarness {
    private static final int INPUT_BYTES=4*1024*1024-1, IMAGE_BYTES=12*1024*1024;
    private static InputStream text(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));}
    private static final class Fill extends InputStream {
        long left;Fill(long bytes){left=bytes;}
        public int read(){if(left==0)return -1;left--;return 'A';}
        public int read(byte[] b,int off,int len){if(len==0)return 0;if(left==0)return -1;int n=(int)Math.min(len,left);Arrays.fill(b,off,off+n,(byte)'A');left-=n;return n;}
    }
    private static InputStream images(int count){
        List<InputStream> streams=new ArrayList<>();streams.add(text("{\"data\":["));
        for(int i=0;i<count;i++){streams.add(text((i==0?"":",")+"{\"b64_json\":\""));streams.add(new Fill(4L*(IMAGE_BYTES/3)));streams.add(text("\"}"));}
        streams.add(text("]}"));return new SequenceInputStream(Collections.enumeration(streams));
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        require((args.length==1||args.length==2)&&Set.of("account","guest").contains(args[0]),"Specify account or guest and optional count 1 or 4");
        int count=args.length==1?4:Integer.parseInt(args[1]);require(count==1||count==4,"Count must be 1 or 4");
        require(Runtime.getRuntime().maxMemory()<=512L*1024*1024,"Use -Xmx512m");
        AtomicBoolean run=new AtomicBoolean(true);AtomicLong peak=new AtomicLong();
        Thread sampler=new Thread(()->{while(run.get()){long n=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();peak.accumulateAndGet(n,Math::max);try{Thread.sleep(5);}catch(InterruptedException stopped){return;}}},"heap-sampler");sampler.setDaemon(true);sampler.start();
        try(var admission=ProviderAdmission.tryEnter();var h=new ProviderOperationsTest.Harness()){
            require(admission!=null,"Heavy task admission unavailable");require(ProviderAdmission.tryEnter()==null,"Concurrent heavy task was not refused");
            boolean account=args[0].equals("account");String op=UUID.randomUUID().toString();
            ProviderOperations.Owner owner=account?new ProviderOperations.Owner("account:1:"+h.f.generation+":"+"a".repeat(32),c->{}):h.owner;
            ObjectNode input=h.input(op).put("provider","openai").put("model","gpt-image-2.6-flare").put("task","image").put("imageEdit",true)
                    .put("prompt","Synthetic image edit; no provider call.").put("file","data:image/png;base64,"+Base64.getEncoder().encodeToString(new byte[INPUT_BYTES]));
            input.putObject("parameters").put("n",count);
            byte[] raw=ModelGateway.JSON.writeValueAsBytes(input);require(raw.length<6*1024*1024,"Request must fit HTTP limit");
            input=(ObjectNode)ModelGateway.JSON.readTree(raw);String kind="/run::image";
            require(h.operations.claim(owner,op,kind,input).state().equals("CLAIMED"),"Claim failed");
            var ticket=h.operations.dispatch(owner,op,kind,input);
            Constructor<MediaGateway.Response> ctor=MediaGateway.Response.class.getDeclaredConstructor(int.class,String.class,ObjectNode.class);ctor.setAccessible(true);
            AtomicInteger syntheticCalls=new AtomicInteger();
            MediaGateway gateway=new MediaGateway((method,url,key,type,body)->{
                require(url.equals(ApiRoutes.imageEdit())&&type.equals("application/json"),"Incorrect synthetic edit route");
                require(body.length<6*1024*1024,"Provider request too large");require(MediaGateway.imageResponseOptions(body).path("n").asInt()==count,"Incorrect image count");
                ProviderOperations.beforeExternal();syntheticCalls.incrementAndGet();
                ObjectNode parsed=StreamingMediaResponse.images(images(count),count,"image/png");
                try{return ctor.newInstance(200,"application/json",parsed);}catch(ReflectiveOperationException failure){throw new IOException(failure);}
            });
            try(var context=ticket.enter()){
                ObjectNode result=gateway.run(input);require(result.path("media").size()==count,"Incorrect response count");
                if(account){
                    List<FileStorage.Upload> uploads=new ArrayList<>();var metadata=ModelGateway.JSON.createArrayNode();
                    for(int i=0;i<count;i++){
                        byte[] bytes=result.path("media").get(i).path("imageBytes").binaryValue();require(bytes.length==IMAGE_BYTES,"Incorrect output length");
                        var upload=new FileStorage.Upload("synthetic-"+i+".png","image/png",bytes);uploads.add(upload);var item=upload.metadata();item.remove("id");metadata.add(item);
                    }
                    ObjectNode reserved=h.f.files.reserve(1,op,metadata,2);
                    for(int i=0;i<count;i++)((ObjectNode)result.path("media").get(i)).put("fileId",reserved.path("ids").get(i).asText());
                    ticket.prepareAccountImages(result);h.f.files.complete(h.f.ingress(),1,op,uploads);
                }
                var receipt=ticket.complete(200,result);require(receipt.state().equals("COMPLETED"),"Result not completed");
            }
            var recovered=h.other().status(owner,op);require(recovered.state().equals("COMPLETED")&&recovered.result().path("media").size()==count,"Receipt recovery failed");
            require(h.other().claim(owner,op,kind,input).state().equals("COMPLETED")&&syntheticCalls.get()==1,"Replayed provider call");
            System.out.println("PASS mode="+args[0]+" requestBytes="+raw.length+" inputBytes="+INPUT_BYTES+" imageCount="+count+" imageBytesEach="+IMAGE_BYTES+" syntheticCalls="+syntheticCalls.get()+" heapMaxBytes="+Runtime.getRuntime().maxMemory()+" sampledHeapPeakBytes="+peak.get()+" database=H2-in-process productionProviderCalls=0");
        }finally{run.set(false);sampler.join();}
    }
}
