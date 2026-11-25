package com.starhub;

import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Only build-time, allowlisted source resources are readable. No filesystem access. */
final class CallSources {
    private static final List<String> FILES=List.of(
        "server/ModelGateway.java","server/ConnectionCheck.java","server/ApiRoutes.java","server/ApiServlet.java",
        "server/ChatStream.java","server/MediaGateway.java","server/BatchGateway.java",
        "server/RealtimeBridge.java","server/RealtimeEndpoint.java","server/Parameters.java",
        "server/Catalog.java","server/SecurityFilter.java","server/CallSources.java",
        "browser/storage.js","browser/experience.js","browser/i18n.js","browser/i18n-data.js","browser/app.js","browser/replies.js","browser/advanced.js","browser/audio-capture.js",
        "browser/parameters.js","browser/api-links.js","browser/source-viewer.js");
    static ObjectNode read(String file)throws IOException{
        if(file==null||file.isEmpty()){
            ObjectNode out=ModelGateway.JSON.createObjectNode();ArrayNode files=out.putArray("files");
            for(String name:FILES){byte[] bytes=load(name);files.addObject().put("name",name).put("sha256",hash(bytes)).put("bytes",bytes.length);}
            return out;
        }
        if(!FILES.contains(file))throw new ModelGateway.Problem(404,"源码文件不存在。");
        byte[] bytes=load(file);
        return ModelGateway.JSON.createObjectNode().put("name",file).put("sha256",hash(bytes)).put("content",new String(bytes,StandardCharsets.UTF_8));
    }
    private static byte[] load(String file)throws IOException{
        try(InputStream in=CallSources.class.getResourceAsStream("/call-source/"+file)){
            if(in==null)throw new IOException("Packaged source missing");return in.readAllBytes();
        }
    }
    private static String hash(byte[] bytes){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format("%02x",b&255));return text.toString();}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
