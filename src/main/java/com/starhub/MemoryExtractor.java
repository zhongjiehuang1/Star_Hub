package com.starhub;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, local first-person extraction. This is not semantic memory. */
final class MemoryExtractor {
    record Candidate(String kind, String text) {}
    private static final Pattern SENSITIVE = Pattern.compile(
        "(?iu)(密码|口令|验证码|密钥|身份证|护照|电话|手机|邮箱|住址|地址|银行卡|信用卡|疾病|诊断|药物|怀孕|性取向|宗教|政治|password|passcode|secret|api[ _-]?key|token|e-?mail|phone|address|passport|diagnos|medicine|religion|sexual|https?://|@|\\d{6,})");
    private static final Pattern NAME_ZH = Pattern.compile("^(?:我叫|我的名字是|请(?:以后|今后)?称呼我为?|以后请叫我)([\\p{IsHan}]{2,4}|[A-Za-z][A-Za-z .'-]{0,38}[A-Za-z])[。.!！]?$",Pattern.UNICODE_CASE);
    private static final Pattern NAME_EN = Pattern.compile("^(?:my name is|call me) ([A-Za-z][A-Za-z .'-]{0,38}[A-Za-z])[.!]?$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private MemoryExtractor() {}
    static boolean containsSensitive(String value){return value==null||SENSITIVE.matcher(value).find();}

    // Only the deterministic first-stage categories are currently admissible.
    // A future semantic extractor needs its own validation and privacy review.
    static boolean allowed(Candidate candidate){
        if(candidate==null||candidate.kind()==null||candidate.text()==null)return false;
        return switch(candidate.kind()){
            case "NAME" -> extract("我叫"+candidate.text()).stream().anyMatch(candidate::equals);
            case "RESPONSE_LANGUAGE" -> List.of("中文","English").contains(candidate.text());
            case "RESPONSE_STYLE" -> List.of("简洁","详细").contains(candidate.text());
            default -> false;
        };
    }

    static List<Candidate> extract(String input) {
        if(input==null||input.length()>300||input.indexOf('\n')>=0||input.indexOf('\r')>=0)return List.of();
        String value=Normalizer.normalize(input,Normalizer.Form.NFKC).trim();
        if(value.isEmpty()||value.codePoints().anyMatch(Character::isISOControl)||SENSITIVE.matcher(value).find())return List.of();
        Matcher name=NAME_ZH.matcher(value);
        if(!name.matches())name=NAME_EN.matcher(value);
        if(name.matches()){
            String person=name.group(1).trim();
            if(person.matches("[他们她们你我让停].*")||person.contains("  "))return List.of();
            return List.of(new Candidate("NAME",person));
        }
        String normalized=value.replaceAll("[。.!！]$","").toLowerCase(Locale.ROOT);
        if(normalized.matches("(?:我(?:更)?(?:喜欢|希望|习惯)(?:你)?(?:用|使用)?|请(?:以后|今后)?(?:用|使用))(?:中文|汉语)(?:回答|回复|交流)?")
                ||normalized.matches("(?:i (?:prefer|want)|please use) (?:chinese|mandarin)(?: (?:responses|replies))?"))
            return List.of(new Candidate("RESPONSE_LANGUAGE","中文"));
        if(normalized.matches("(?:我(?:更)?(?:喜欢|希望|习惯)(?:你)?(?:用|使用)?|请(?:以后|今后)?(?:用|使用))(?:英文|英语)(?:回答|回复|交流)?")
                ||normalized.matches("(?:i (?:prefer|want)|please use) english(?: (?:responses|replies))?"))
            return List.of(new Candidate("RESPONSE_LANGUAGE","English"));
        if(normalized.matches("(?:我(?:更)?喜欢|请(?:以后|今后)?(?:给我)?)?(?:简洁|简短)(?:的)?(?:回答|回复)")
                ||normalized.matches("(?:i (?:prefer|want)|please give me) (?:concise|brief) (?:answers|responses|replies)"))
            return List.of(new Candidate("RESPONSE_STYLE","简洁"));
        if(normalized.matches("(?:我(?:更)?喜欢|请(?:以后|今后)?(?:给我)?)?(?:详细|详尽)(?:的)?(?:回答|回复)")
                ||normalized.matches("(?:i (?:prefer|want)|please give me) (?:detailed|thorough) (?:answers|responses|replies)"))
            return List.of(new Candidate("RESPONSE_STYLE","详细"));
        return List.of();
    }
}
