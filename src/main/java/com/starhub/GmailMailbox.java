package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded read-only Gmail operations. This class never invokes a model. */
final class GmailMailbox {
    private static final String MESSAGES="https://gmail.googleapis.com/gmail/v1/users/me/messages";
    private static final int MAX_BODY_BYTES=120_000, MAX_BODY_CHARS=20_000, MAX_PARTS=100;
    private final GmailTransport transport;
    GmailMailbox(GmailTransport transport){this.transport=java.util.Objects.requireNonNull(transport);}

    record Message(String id,String from,String subject,String date,String snippet,String body,boolean truncated) {
        @Override public String toString(){return "GmailMessage[content=redacted]";}
    }
    record Page(List<Message> messages,String nextPageToken) {
        Page {messages=List.copyOf(messages);}
        @Override public String toString(){return "GmailPage[content=redacted]";}
    }

    Page search(String accessToken,String query,int limit,String pageToken) throws IOException {
        if(!GmailOAuthClient.safe(accessToken,4096)||limit<1||limit>10||query==null||query.length()>512||
                query.chars().anyMatch(Character::isISOControl)||pageToken!=null&&!pageToken.isEmpty()&&!GmailOAuthClient.safe(pageToken,512))
            throw new IOException("Invalid Gmail search");
        Map<String,String> params=new LinkedHashMap<>();params.put("maxResults",Integer.toString(limit));
        if(!query.isBlank())params.put("q",query);if(pageToken!=null&&!pageToken.isEmpty())params.put("pageToken",pageToken);
        params.put("fields","messages(id),nextPageToken");
        JsonNode list=get(accessToken,URI.create(MESSAGES+"?"+GmailSecurity.form(params)));
        JsonNode ids=list.path("messages");if(!ids.isMissingNode()&&!ids.isArray())throw new IOException("Invalid Gmail search response");
        if(ids.isArray()&&ids.size()>limit)throw new IOException("Gmail search response exceeds limit");
        List<Message> results=new ArrayList<>();
        if(ids.isArray())for(JsonNode item:ids){String id=item.path("id").asText("");if(!validId(id))throw new IOException("Invalid Gmail message ID");results.add(message(accessToken,id,false));}
        String next=list.path("nextPageToken").asText("");if(!next.isEmpty()&&!GmailOAuthClient.safe(next,512))throw new IOException("Invalid Gmail page token");
        return new Page(results,next);
    }

    Message message(String accessToken,String id,boolean includeBody) throws IOException {
        if(!GmailOAuthClient.safe(accessToken,4096)||!validId(id))throw new IOException("Invalid Gmail message request");
        String query=includeBody?"format=full":"format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date";
        JsonNode item=get(accessToken,URI.create(MESSAGES+"/"+id+"?"+query));
        if(!id.equals(item.path("id").asText("")))throw new IOException("Gmail message identity mismatch");
        JsonNode payload=item.path("payload"),headers=payload.path("headers");String from="",subject="",date="";
        if(headers.isArray())for(JsonNode header:headers){String name=header.path("name").asText("");String value=bounded(header.path("value").asText(""),1000);
            if(name.equalsIgnoreCase("from")&&from.isEmpty())from=value;else if(name.equalsIgnoreCase("subject")&&subject.isEmpty())subject=value;else if(name.equalsIgnoreCase("date")&&date.isEmpty())date=value;}
        String body="";boolean truncated=false;
        if(includeBody){Body found=plain(payload,0,new int[]{0});if(found!=null){body=found.text();truncated=found.truncated();}}
        return new Message(id,from,subject,date,bounded(item.path("snippet").asText(""),1000),body,truncated);
    }

    private JsonNode get(String token,URI uri) throws IOException {
        GmailTransport.Reply reply=transport.request("GET",uri,token,null);
        if(reply.status()!=200)throw new IOException("Gmail request failed");
        try{JsonNode json=ModelGateway.JSON.readTree(reply.body());if(json==null||!json.isObject())throw new IOException("Invalid Gmail response");return json;}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IOException("Invalid Gmail response");}
    }
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{1,100}");}
    private static String bounded(String input,int limit){
        if(input==null)return "";String clean=input.replaceAll("[\\p{Cntrl}]"," ").trim();
        return clean.length()>limit?clean.substring(0,limit):clean;
    }
    private record Body(String text,boolean truncated) { }
    private static Body plain(JsonNode part,int depth,int[] count) throws IOException {
        if(depth>8||++count[0]>MAX_PARTS)throw new IOException("Gmail message structure exceeds limit");
        if(part==null||!part.isObject()||!part.path("filename").asText("").isEmpty()||part.path("body").hasNonNull("attachmentId"))return null;
        if("text/plain".equalsIgnoreCase(part.path("mimeType").asText(""))){
            String data=part.path("body").path("data").asText("");if(data.isEmpty())return new Body("",false);
            if(data.length()>((MAX_BODY_BYTES+2)/3)*4+4)throw new IOException("Gmail text exceeds limit");
            byte[] bytes;try{bytes=Base64.getUrlDecoder().decode(data);}catch(IllegalArgumentException bad){throw new IOException("Gmail text is invalid");}
            if(bytes.length>MAX_BODY_BYTES)throw new IOException("Gmail text exceeds limit");
            String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(java.nio.charset.CharacterCodingException invalid){return null;}
            int length=text.codePointCount(0,text.length());boolean truncated=length>MAX_BODY_CHARS;
            if(truncated)text=text.substring(0,text.offsetByCodePoints(0,MAX_BODY_CHARS));return new Body(text,truncated);
        }
        JsonNode parts=part.path("parts");if(parts.isArray())for(JsonNode child:parts){Body found=plain(child,depth+1,count);if(found!=null)return found;}
        return null;
    }
}
