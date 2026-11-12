package com.starhub;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/** A bounded UTF-8 provider response reader. Image strings are never materialized.
 * The caller owns/ closes the HTTP stream. No provider request or storage write
 * occurs here. Read-ahead bytes are not mistaken for consumed JSON envelope.
 */
final class StreamingMediaResponse {
    static final int MAX_IMAGE=12*1024*1024,MAX_IMAGES=4,ENVELOPE_BYTES=64*1024;
    static final long MAX_RAW=(long)MAX_IMAGES*4*((MAX_IMAGE+2)/3)+ENVELOPE_BYTES;
    private static final JsonFactory FACTORY=JsonFactory.builder()
            .disable(JsonFactory.Feature.CHARSET_DETECTION)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxNameLength(ENVELOPE_BYTES).maxStringLength(ENVELOPE_BYTES)
                    .maxNumberLength(1024).maxDocumentLength(MAX_RAW).maxTokenCount(ENVELOPE_BYTES).build()).build();
    private StreamingMediaResponse() { }

    static ObjectNode images(InputStream input,int expectedImages,String mime)throws IOException {
        if(input==null||expectedImages<1||expectedImages>MAX_IMAGES||mime==null||!Set.of("image/png","image/jpeg","image/webp").contains(mime))throw failure();
        TrackingInputStream tracked=new TrackingInputStream(input);
        Budget budget=new Budget(tracked);ArrayNode media=JsonNodeFactory.instance.arrayNode();
        try(JsonParser parser=FACTORY.createParser(tracked)) {
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw failure();
            boolean data=false;
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                if(parser.currentToken()!=JsonToken.FIELD_NAME)throw failure();
                String name=parser.currentName();JsonToken value=parser.nextToken();if(value==null)throw failure();
                if(name.equals("data")){
                    if(data||value!=JsonToken.START_ARRAY)throw failure();data=true;
                    while(parser.nextToken()!=JsonToken.END_ARRAY){
                        if(parser.currentToken()!=JsonToken.START_OBJECT||media.size()>=expectedImages)throw failure();
                        media.add(image(parser,budget,mime));budget.check(parser);
                    }
                }else if(name.equals("error")){
                    if(value!=JsonToken.VALUE_NULL)throw failure();
                }else skipMetadata(parser,budget);
                budget.check(parser);
            }
            if(!data||media.size()!=expectedImages||parser.nextToken()!=null)throw failure();
            budget.check(parser);
            if(tracked.count-budget.imageCharacters>ENVELOPE_BYTES)throw failure();
            return JsonNodeFactory.instance.objectNode().put("text","图片已生成。").set("media",media);
        }catch(IOException|RuntimeException invalid){
            // Do not propagate a parser exception containing provider body text.
            throw failure();
        }
    }
    private static ObjectNode image(JsonParser parser,Budget budget,String mime)throws IOException {
        byte[] bytes=null;
        while(parser.nextToken()!=JsonToken.END_OBJECT){
            if(parser.currentToken()!=JsonToken.FIELD_NAME)throw failure();
            String name=parser.currentName();JsonToken value=parser.nextToken();if(value==null)throw failure();
            if(name.equals("b64_json")){
                if(bytes!=null||value!=JsonToken.VALUE_STRING)throw failure();
                long start=parser.currentTokenLocation().getByteOffset();
                ImageOutput output=new ImageOutput();
                int decoded=parser.readBinaryValue(Base64Variants.MIME_NO_LINEFEEDS,output);
                Span span=budget.input.spans.remove(start);
                if(decoded<1||decoded!=output.size()||span==null||span.escaped||span.end<=start+2)throw failure();
                budget.imageCharacters=Math.addExact(budget.imageCharacters,span.base64Characters);
                budget.decodedBytes=Math.addExact(budget.decodedBytes,decoded);
                if(budget.decodedBytes>(long)MAX_IMAGES*MAX_IMAGE)throw failure();
                bytes=output.toByteArray();
            }else skipMetadata(parser,budget);
            budget.check(parser);
        }
        if(bytes==null)throw failure();
        return JsonNodeFactory.instance.objectNode().put("type","image").put("mime",mime).put("imageBytes",bytes);
    }
    /** Walk unknown metadata without a tree, including nested strings/values. */
    private static void skipMetadata(JsonParser parser,Budget budget)throws IOException {
        JsonToken token=parser.currentToken();
        if(token==JsonToken.START_OBJECT||token==JsonToken.START_ARRAY){
            int nesting=1;
            while(nesting>0){
                token=parser.nextToken();if(token==null)throw failure();
                if(token==JsonToken.START_OBJECT||token==JsonToken.START_ARRAY)nesting++;
                else if(token==JsonToken.END_OBJECT||token==JsonToken.END_ARRAY)nesting--;
                else if(token==JsonToken.VALUE_STRING)parser.getTextLength();
                budget.check(parser);
            }
        }else if(token==JsonToken.VALUE_STRING)parser.getTextLength();
        else if(token==JsonToken.FIELD_NAME||token==JsonToken.END_OBJECT||token==JsonToken.END_ARRAY||token==null)throw failure();
        budget.check(parser);
    }
    private static IOException failure(){return new IOException("Image response is invalid or exceeds supported bounds.");}
    private static final class ImageOutput extends ByteArrayOutputStream {
        ImageOutput(){super(8192);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){
            if(length<0||count>MAX_IMAGE-length)throw new ImageLimit();super.write(bytes,offset,length);
        }
        @Override public synchronized void write(int value){if(count==MAX_IMAGE)throw new ImageLimit();super.write(value);}
    }
    private static final class ImageLimit extends RuntimeException { }
    private record Span(long end,long base64Characters,boolean escaped) { }
    private static final class Budget {
        final TrackingInputStream input;long imageCharacters,decodedBytes;
        Budget(TrackingInputStream input){this.input=input;}
        void check(JsonParser parser)throws IOException {
            long consumed=parser.currentLocation().getByteOffset();
            if(consumed<0||consumed-imageCharacters>ENVELOPE_BYTES)throw failure();
            input.spans.entrySet().removeIf(entry->entry.getValue().end<=consumed);
        }
    }
    /** Keep lexical string spans/flags, never any string bytes. At most the
     * parser's bounded read-ahead plus its current token remains unconsumed. */
    private static final class TrackingInputStream extends FilterInputStream {
        final Map<Long,Span> spans=new HashMap<>();long count,start,characters;boolean inString,escape,escaped;
        TrackingInputStream(InputStream input){super(input);}
        private void observed(int value)throws IOException {
            if(count>=MAX_RAW)throw failure();
            if(!inString){if(value=='"'){inString=true;start=count;characters=0;escape=false;escaped=false;}}
            else if(escape)escape=false;
            else if(value=='\\'){escaped=true;escape=true;}
            else if(value=='"'){spans.put(start,new Span(count+1,characters,escaped));inString=false;if(spans.size()>ENVELOPE_BYTES)throw failure();}
            else if(value<32)escaped=true; // Raw JSON string controls are never legal base64 whitespace.
            else if(value>='A'&&value<='Z'||value>='a'&&value<='z'||value>='0'&&value<='9'||value=='+'||value=='/'||value=='=')characters++;
            count++;
        }
        @Override public int read()throws IOException{int value=in.read();if(value>=0)observed(value);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {
            int size=in.read(bytes,offset,(int)Math.min(length,Math.max(1,MAX_RAW-count+1)));
            if(size>0)for(int index=offset;index<offset+size;index++)observed(bytes[index]&255);
            return size;
        }
    }
}
