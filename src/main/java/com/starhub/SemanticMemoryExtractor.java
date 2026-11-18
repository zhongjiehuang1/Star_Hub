package com.starhub;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validate an untrusted model proposal against the exact, direct user sentence. */
final class SemanticMemoryExtractor {
    private static final Pattern DIRECT=Pattern.compile("(?iu)^(?:(?:(?:请)?(?:记住|记得))\\s*我(?:叫|是|有|喜欢|爱好|偏好|习惯|使用|学习|擅长|从事|经营|专注于|计划|希望|需要)|以后(?:请)?(?:用|使用)(?:中文|汉语|英文|英语)(?=回答|回复|交流)|(?:我的名字是|请(?:以后|今后)?称呼我为?|以后请叫我|请叫我)|我(?:叫|是|有|喜欢|爱好|偏好|习惯|使用|学习|擅长|从事|经营|专注于|计划|希望|需要)|(?:please )?remember (?:that )?i (?:am|have|like|love|prefer|enjoy|use|study|work as|speciali[sz]e in|plan to|want|need)|(?:my name is|call me) |i (?:am|have|like|love|prefer|enjoy|use|study|work as|speciali[sz]e in|plan to|want|need))\\s*.+$");
    private static final Pattern EXCLUDE=Pattern.compile("(?iu)(邮件|收件|发件|转发|邮箱|信件|gmail|e-?mail|inbox|forwarded|from:|subject:|他说|她说|他们|别人说|举例|例如|假设|如果|不再|并不|不是|不喜欢|don't|do not|not |never |ignore|instruction|system|developer|prompt|忽略|指令|系统提示|扮演|假装|越狱|口令|secret|password|(?:健康|医疗|患有|服药|诊断|药物|疾病|癌症|抑郁|怀孕|性取向|宗教|政治|收入|存款|银行卡|身份证|护照|手机号|电话号码|住址|家庭地址|medical|diagnos|cancer|pregnan|religion|politic|salary|bank|address|passport))");
    private SemanticMemoryExtractor() {}

    static String eligibleInput(String userInput){
        if(userInput==null||userInput.length()>AccountMemories.MAX_TEXT)return null;
        String input=Normalizer.normalize(userInput,Normalizer.Form.NFKC).strip();
        if(input.isEmpty()||input.length()>AccountMemories.MAX_TEXT||input.indexOf('\n')>=0||input.indexOf('\r')>=0||input.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)||MemoryExtractor.containsSensitive(input)||EXCLUDE.matcher(input).find()||!DIRECT.matcher(input).matches())return null;
        // Quoted or forwarded text is not proof that the user holds the fact.
        if(input.codePoints().anyMatch(c->c=='"'||c=='“'||c=='”'||c=='‘'||c=='’'||c=='「'||c=='」'||c=='>'||c=='<'))return null;
        return input;
    }

    static List<MemoryExtractor.Candidate> validate(String userInput,String providerReply)throws IOException{
        if(providerReply==null||providerReply.length()>8192)return List.of();
        String input=eligibleInput(userInput);if(input==null)return List.of();
        JsonNode object;
        try(JsonParser parser=ModelGateway.JSON.getFactory().createParser(providerReply)){
            object=ModelGateway.JSON.readTree(parser);if(parser.nextToken()!=null)return List.of();
        }catch(IOException invalid){return List.of();}
        if(object==null||!object.isObject()||object.size()!=2||!object.path("kind").isTextual()||!object.path("quote").isTextual())return List.of();
        String kind=object.path("kind").asText().toUpperCase(Locale.ROOT),quote=Normalizer.normalize(object.path("quote").asText(),Normalizer.Form.NFKC).strip();
        if(!List.of("PREFERENCE","FACT").contains(kind)||!quote.equals(input))return List.of();
        return List.of(new MemoryExtractor.Candidate(kind,quote));
    }
}
