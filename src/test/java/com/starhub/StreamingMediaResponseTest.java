package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Only synthetic streams. No HTTP provider, key, credential or stored data. */
class StreamingMediaResponseTest {
    private static final int MAX=StreamingMediaResponse.MAX_IMAGE;
    private static InputStream text(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
    private static ObjectNode parse(String value,int images)throws IOException{return StreamingMediaResponse.images(text(value),images,"image/png");}
    private static String one(String value){return "{\"data\":[{\"b64_json\":\""+value+"\"}]}";}
    private static void denied(String value,int expected){assertThrows(IOException.class,()->parse(value,expected));}
    private static class Fill extends InputStream {
        long remaining;final int value,step;long bytesRead;
        Fill(int value,long count){this(value,count,Integer.MAX_VALUE);}
        Fill(int value,long count,int step){this.value=value;remaining=count;this.step=step;}
        @Override public int read(){if(remaining==0)return -1;remaining--;bytesRead++;return value;}
        @Override public int read(byte[] b,int off,int len){if(len==0)return 0;if(remaining==0)return -1;int n=(int)Math.min(Math.min(remaining,len),step);Arrays.fill(b,off,off+n,(byte)value);remaining-=n;bytesRead+=n;return n;}
    }
    private static InputStream sequence(InputStream... streams){return new SequenceInputStream(Collections.enumeration(Arrays.asList(streams)));}
    /** Every big decoded count is divisible by3: repeated A encodes zero bytes. */
    private static InputStream generated(int images,int decoded){
        assertEquals(0,decoded%3);List<InputStream> parts=new ArrayList<>();parts.add(text("{\"data\":["));
        for(int i=0;i<images;i++){parts.add(text((i==0?"":",")+"{\"b64_json\":\""));parts.add(new Fill('A',4L*(decoded/3)));parts.add(text("\"}"));}
        parts.add(text("]}"));return new SequenceInputStream(Collections.enumeration(parts));
    }
    private static void assertImages(ObjectNode out,int count,int length)throws Exception {
        assertEquals("图片已生成。",out.path("text").asText());assertEquals(count,out.path("media").size());
        for(var image:out.path("media")){assertEquals("image",image.path("type").asText());assertEquals("image/png",image.path("mime").asText());assertTrue(image.path("imageBytes").isBinary());byte[] value=image.path("imageBytes").binaryValue();assertEquals(length,value.length);assertEquals(0,value[0]);assertEquals(0,value[value.length-1]);}
    }
    @Test void smallBinaryResultAndBoundedMetadata()throws Exception {
        ObjectNode value=parse("{\"created\":123,\"error\":null,\"extra\":{\"nested\":[true,false,null,\"信息\"]},\"data\":[{\"revised_prompt\":\"hello\",\"b64_json\":\"AQID\"}]}",1);
        assertArrayEquals(new byte[]{1,2,3},value.path("media").get(0).path("imageBytes").binaryValue());
    }
    @Test void exactImageCountAndNonemptyBinaryAreMandatory(){
        for(String value:List.of("{}","[]","{\"data\":null}","{\"data\":[]}","{\"data\":[{}]}","{\"data\":[{\"url\":\"https://example.invalid/image\"}]}",one(""),"{\"data\":[{\"b64_json\":null}]}","{\"data\":[{\"b64_json\":1}]}"))denied(value,1);
        denied(one("AAAA"),2);denied("{\"data\":[{\"b64_json\":\"AAAA\"},{\"b64_json\":\"AAAA\"}]}",1);
        denied(one("AAAA"),0);denied(one("AAAA"),5);
    }
    @Test void oneMaximumImageStreamsWithoutBase64StringTree()throws Exception {
        assertImages(StreamingMediaResponse.images(generated(1,MAX),1,"image/png"),1,MAX);
    }
    @Test void fourMaximumImagesStreamUnderTheDecodedBudget()throws Exception {
        assertImages(StreamingMediaResponse.images(generated(4,MAX),4,"image/png"),4,MAX);
    }
    @Test void twoConcurrentMaximumResponsesRemainIndependentlyBounded()throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Callable<ObjectNode> parse=()->{start.await();return StreamingMediaResponse.images(generated(4,MAX),4,"image/png");};
            Future<ObjectNode> one=workers.submit(parse),two=workers.submit(parse);start.countDown();
            assertImages(one.get(60,TimeUnit.SECONDS),4,MAX);assertImages(two.get(60,TimeUnit.SECONDS),4,MAX);
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void perImageOverflowCannotBeHiddenBySmallerTotal(){assertThrows(IOException.class,()->StreamingMediaResponse.images(generated(1,MAX+3),1,"image/png"));}
    @Test void envelopeBoundaryCountsUtf8WhitespaceAndJsonPunctuation()throws Exception {
        String json=one("AAAA");int overhead=json.getBytes(StandardCharsets.UTF_8).length-4;int room=StreamingMediaResponse.ENVELOPE_BYTES-overhead;
        assertImages(StreamingMediaResponse.images(sequence(new Fill(' ',room),text(json)),1,"image/png"),1,3);
        assertThrows(IOException.class,()->StreamingMediaResponse.images(sequence(new Fill(' ',room+1),text(json)),1,"image/png"));
        assertThrows(IOException.class,()->StreamingMediaResponse.images(sequence(text(json),new Fill(' ',room+1)),1,"image/png"));
    }
    @Test void base64WhitespaceConsumesEnvelopeBudgetInsteadOfImageAllowance()throws Exception {
        String json=one("AAAA AAAA");int overhead=json.length()-8,room=StreamingMediaResponse.ENVELOPE_BYTES-overhead;
        assertImages(StreamingMediaResponse.images(sequence(new Fill(' ',room),text(json)),1,"image/png"),1,6);
        assertThrows(IOException.class,()->StreamingMediaResponse.images(sequence(new Fill(' ',room+1),text(json)),1,"image/png"));
    }
    @Test void parserReadAheadDoesNotSpendFutureImageBytesAsEnvelope()throws Exception {
        byte[] json=(" ".repeat(4000)+one("AAAA")+" ".repeat(4000)).getBytes(StandardCharsets.UTF_8);
        for(int step:List.of(1,7,8192,65536)){
            InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(json)){@Override public int read(byte[] b,int off,int len)throws IOException{return in.read(b,off,Math.min(len,step));}};
            assertImages(StreamingMediaResponse.images(fragmented,1,"image/png"),1,3);
        }
    }
    @Test void escapedBinaryDuplicateDataAndDuplicateBinaryAreRefused(){
        for(String binary:List.of("\\u0041AAA","AA\\/A","AAAA\\nAAAA","AAAA\nAAAA","AAAA\tAAAA","not base64"))denied(one(binary),1);
        denied("{\"data\":[{\"b64_json\":\"AAAA\",\"b64_json\":\"AAAA\"}]}",1);
        denied("{\"data\":[{\"b64_json\":\"AAAA\"}],\"data\":[{\"b64_json\":\"AAAA\"}]}",1);
        denied("{\"data\":[{\"b64_json\":\"AAAA\",\"b64_\\u006ason\":\"AAAA\"}]}",1);
    }
    @Test void providerErrorsTrailingJsonAndOversizedUnknownMetadataAreRefused(){
        denied("{\"error\":{\"message\":\"private synthetic text\"},\"data\":[{\"b64_json\":\"AAAA\"}]}",1);
        denied(one("AAAA")+"{}",1);denied(one("AAAA")+"trailing",1);
        denied("{\"metadata\":\""+"A".repeat(65537)+"\",\"data\":[{\"b64_json\":\"AAAA\"}]}",1);
        denied("{\"metadata\":"+"[".repeat(40)+"0"+"]".repeat(40)+",\"data\":[{\"b64_json\":\"AAAA\"}]}",1);
    }
    @Test void invalidMimeUtf16AndTransportErrorsStayGeneric(){
        assertThrows(IOException.class,()->StreamingMediaResponse.images(text(one("AAAA")),1,"image/png\r\nunsafe"));
        assertThrows(IOException.class,()->StreamingMediaResponse.images(new ByteArrayInputStream(one("AAAA").getBytes(StandardCharsets.UTF_16)),1,"image/png"));
        IOException error=assertThrows(IOException.class,()->StreamingMediaResponse.images(new InputStream(){public int read()throws IOException{throw new IOException("synthetic-private-provider-body");}},1,"image/png"));
        assertFalse(error.toString().contains("synthetic-private-provider-body"));assertNull(error.getCause());
    }
    @Test void callerOwnsTheInputStream()throws Exception {
        boolean[] closed={false};InputStream stream=new FilterInputStream(text(one("AAAA"))){@Override public void close(){closed[0]=true;}};
        StreamingMediaResponse.images(stream,1,"image/png");assertFalse(closed[0]);
    }
    public static void main(String[] args)throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory()<=512L*1024*1024,"Run explicit local harness with -Xmx512m");
        StreamingMediaResponseTest test=new StreamingMediaResponseTest();int count=0;
        for(var method:StreamingMediaResponseTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){try{method.invoke(test);count++;}catch(java.lang.reflect.InvocationTargetException failure){throw new AssertionError("Synthetic case failed: "+method.getName(),failure.getCause());}}
        System.out.println("PASS streaming synthetic cases="+count+" maximumConcurrentResponses=2 decodedBytesPerResponse="+(4L*MAX)+" heapMaxBytes="+Runtime.getRuntime().maxMemory()+" noProviderCalls=true");
    }
}
