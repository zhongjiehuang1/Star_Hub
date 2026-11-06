package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import javax.servlet.http.*;

/** Tab-scoped guest data in the server session. The cookie contains no user data. */
public final class GuestSessionServlet extends HttpServlet {
    static final int MAX_FIELD=12_000_000, MAX_SESSION=24_000_000, MAX_TOTAL=64_000_000, MAX_TABS=64;
    private static final Object LOCK=new Object();
    private static long total;
    private static final Set<String> FIELDS=Set.of("history","keys","pending");
    private static final String ATTRIBUTE="starhub.guest.tabs";
    private static final class Tab {
        final Map<String,String> values=new HashMap<>(); final Map<String,Long> versions=new HashMap<>(); StringBuilder staging; String field,transaction,lastFlush; int next; long version;
        int size(){return values.values().stream().mapToInt(String::length).sum()+(staging==null?0:staging.length());}
    }
    private static final class Tabs implements HttpSessionBindingListener {
        final Map<String,Tab> tabs=new HashMap<>();
        int size(){return tabs.values().stream().mapToInt(Tab::size).sum();}
        @Override public void valueUnbound(HttpSessionBindingEvent event){synchronized(LOCK){total-=size();tabs.clear();}}
    }
    private void send(HttpServletResponse res,int status,JsonNode body)throws IOException{res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");ModelGateway.JSON.writeValue(res.getOutputStream(),body);}
    protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException{
        String origin=req.getHeader("Origin"),expected=System.getenv().getOrDefault("STARHUB_PUBLIC_ORIGIN","https://starhub.zhongjiehuang.com");
        if(!"web".equals(req.getHeader("X-Starhub-Client"))||!Objects.equals(origin,expected)||req.getContentType()==null||!req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")){send(res,403,ModelGateway.JSON.createObjectNode());return;}
        byte[] bytes=req.getInputStream().readNBytes(1_048_577);if(bytes.length>1_048_576){send(res,413,ModelGateway.JSON.createObjectNode());return;}
        JsonNode body;try{body=ModelGateway.JSON.readTree(bytes);}catch(Exception e){send(res,400,ModelGateway.JSON.createObjectNode());return;}
        if(body==null||!body.isObject()||!body.path("tab").asText().matches("[0-9a-f]{32}")){send(res,400,ModelGateway.JSON.createObjectNode());return;}
        String tabId=body.path("tab").asText(),action=body.path("action").asText(),field=body.path("field").asText(),tx=body.path("tx").asText();
        HttpSession session=req.getSession(true);ObjectNode out=ModelGateway.JSON.createObjectNode();
        synchronized(session){String epoch=(String)session.getAttribute("starhub.guest.epoch");if(epoch==null){epoch=UUID.randomUUID().toString();session.setAttribute("starhub.guest.epoch",epoch);}out.put("epoch",epoch);}
        if(action.equals("get")){
            Map<String,String> saved;String after=body.path("after").asText();
            synchronized(LOCK){Tabs tabs=(Tabs)session.getAttribute(ATTRIBUTE);Tab tab=tabs==null?null:tabs.tabs.get(tabId);saved=tab==null?Map.of():new HashMap<>(tab.values);if(after.matches("[0-9a-f]{32}")&&(tab==null||!after.equals(tab.lastFlush)))out.put("pendingFlush",true);}
            for(var entry:saved.entrySet()){try{out.set(entry.getKey(),ModelGateway.JSON.readTree(entry.getValue()));}catch(Exception ignored){}}send(res,200,out);return;
        }
        synchronized(LOCK){
            Tabs tabs=(Tabs)session.getAttribute(ATTRIBUTE);if(tabs==null){tabs=new Tabs();session.setAttribute(ATTRIBUTE,tabs);}
            Tab tab=tabs.tabs.get(tabId);
            if(tab==null){if(tabs.tabs.size()>=MAX_TABS){send(res,429,out);return;}tab=new Tab();tabs.tabs.put(tabId,tab);}
            if(action.equals("snapshot")){
                long version=body.path("version").asLong();JsonNode values=body.path("values");if(!tx.matches("[0-9a-f]{32}")||version<=0||!values.isObject()){send(res,400,out);return;}
                Map<String,String> replacements=new HashMap<>();long increase=0;
                for(String name:FIELDS)if(values.has(name)&&version>tab.versions.getOrDefault(name,0L)){String value=values.get(name).toString();if(value.length()>MAX_FIELD){send(res,413,out);return;}replacements.put(name,value);increase+=value.length()-tab.values.getOrDefault(name,"").length();}
                if((long)tabs.size()+increase>MAX_SESSION||total+increase>MAX_TOTAL){send(res,413,out);return;}
                for(var entry:replacements.entrySet()){tab.values.put(entry.getKey(),entry.getValue());tab.versions.put(entry.getKey(),version);}total+=increase;tab.lastFlush=tx;send(res,200,out);return;
            }
            if(!FIELDS.contains(field)||!tx.matches("[0-9a-f]{32}")){send(res,400,out);return;}
            if(action.equals("begin")){long version=body.path("version").asLong();if(version<=0){send(res,400,out);return;}if(tab.staging!=null)total-=tab.staging.length();tab.staging=new StringBuilder();tab.field=field;tab.transaction=tx;tab.version=version;tab.next=0;send(res,200,out);return;}
            if(tab.staging==null||!field.equals(tab.field)||!tx.equals(tab.transaction)){send(res,409,out);return;}
            if(action.equals("chunk")){
                String chunk=body.path("data").asText();if(!body.path("data").isTextual()||chunk.length()>100_000||body.path("index").asInt(-1)!=tab.next){send(res,400,out);return;}
                if((long)tab.staging.length()+chunk.length()>MAX_FIELD||(long)tabs.size()+chunk.length()>MAX_SESSION||total+chunk.length()>MAX_TOTAL){send(res,413,out);return;}
                tab.staging.append(chunk);tab.next++;total+=chunk.length();send(res,200,out);return;
            }
            if(action.equals("commit")){
                String value=tab.staging.toString();try{if(ModelGateway.JSON.readTree(value)==null)throw new IOException();}catch(Exception e){send(res,400,out);return;}
                if(tab.version>tab.versions.getOrDefault(field,0L)){String previous=tab.values.put(field,value);if(previous!=null)total-=previous.length();tab.versions.put(field,tab.version);}else total-=value.length();tab.staging=null;tab.field=null;tab.transaction=null;send(res,200,out);return;
            }
            send(res,400,out);
        }
    }
}
