package com.starhub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
/** Convert Gemini grounding metadata while preserving its required suggestions widget. */
final class SearchMetadata {
 static void gemini(JsonNode metadata,ObjectNode details){
  if(!metadata.isObject())return;
  if(metadata.path("webSearchQueries").isArray()&&!metadata.path("webSearchQueries").isEmpty())details.put("webSearchUsed",true);
  String widget=metadata.path("searchEntryPoint").path("renderedContent").asText("");if(!widget.isEmpty()&&widget.length()<=100000)details.put("googleWidget",widget);
  for(JsonNode chunk:metadata.path("groundingChunks")){String url=chunk.path("web").path("uri").asText(),title=chunk.path("web").path("title").asText();if(url.startsWith("https://")&&details.withArray("webCitations").size()<50){boolean duplicate=false;for(JsonNode c:details.path("webCitations"))if(c.path("url").asText().equals(url))duplicate=true;if(!duplicate)details.withArray("webCitations").addObject().put("url",url).put("title",title).put("end_index",-1);}}
 }
 static void responses(JsonNode item,ObjectNode details){
  if(!item.path("type").asText().equals("web_search_call"))return;details.put("webSearchUsed",true);
  for(JsonNode source:item.path("action").path("sources")){String url=source.path("url").asText(),title=source.path("title").asText();if(!(url.startsWith("https://")||url.startsWith("http://")))continue;boolean seen=false;for(JsonNode old:details.path("webCitations"))if(old.path("url").asText().equals(url))seen=true;if(!seen&&details.withArray("webCitations").size()<50)details.withArray("webCitations").addObject().put("url",url).put("title",title).put("end_index",-1);}
 }
}
