package com.starhub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;

/** A bounded, local GeoNames city lookup using compact, sorted UTF-8 byte arrays. */
final class CityLookup {
    private static final int INDEX_SIZE = 15860226;
    private static final int RECORDS_SIZE = 10191093;
    private static final int MAX_RESULTS = 5;
    private static final Semaphore LOOKUPS = new Semaphore(2);
    private static final Map<String, String> CN_PROVINCES = Map.ofEntries(
        Map.entry("01","安徽"), Map.entry("02","浙江"), Map.entry("03","江西"), Map.entry("04","江苏"),
        Map.entry("05","吉林"), Map.entry("06","青海"), Map.entry("07","福建"), Map.entry("08","黑龙江"),
        Map.entry("09","河南"), Map.entry("10","河北"), Map.entry("11","湖南"), Map.entry("12","湖北"),
        Map.entry("13","新疆"), Map.entry("14","西藏"), Map.entry("15","甘肃"), Map.entry("16","广西"),
        Map.entry("18","贵州"), Map.entry("19","辽宁"), Map.entry("20","内蒙古"), Map.entry("21","宁夏"),
        Map.entry("22","北京"), Map.entry("23","上海"), Map.entry("24","山西"), Map.entry("25","山东"),
        Map.entry("26","陕西"), Map.entry("28","天津"), Map.entry("29","云南"), Map.entry("30","广东"),
        Map.entry("31","海南"), Map.entry("32","四川"), Map.entry("33","重庆"));

    record City(String id, String name, String label, String country, double latitude, double longitude) {}
    record Result(List<City> matches, boolean truncated) {}
    private record Query(String name, String country, String admin) {}
    private record Region(String country, String admin) {}
    static final class Busy extends Exception {}

    static Result find(String input) throws IOException, Busy {
        Query query = parse(input);
        if (query == null) return new Result(List.of(), false);
        if (!LOOKUPS.tryAcquire()) throw new Busy();
        try { return search(IndexHolder.INDEX, IndexHolder.RECORDS, IndexHolder.ADMIN, query); }
        finally { LOOKUPS.release(); }
    }

    private static final class IndexHolder {
        static final byte[] INDEX = loadCompressed("/geonames/cities-index.tsv.gz", INDEX_SIZE);
        static final byte[] RECORDS = loadCompressed("/geonames/cities-records.tsv.gz", RECORDS_SIZE);
        static final Map<String,String> ADMIN = loadAdmin();
    }
    private static byte[] loadCompressed(String path, int size) {
        byte[] bytes = new byte[size];
        try (InputStream resource = CityLookup.class.getResourceAsStream(path)) {
            if (resource == null) throw new IOException("missing city data");
            try (InputStream in = new GZIPInputStream(resource, 8192)) {
                int at = 0, n;
                while (at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) != -1) at += n;
                if (at != bytes.length || in.read() != -1) throw new IOException("unexpected city index size");
            }
            return bytes;
        } catch (IOException failure) { throw new IllegalStateException("City index unavailable", failure); }
    }
    private static Map<String,String> loadAdmin() {
        Map<String,String> names = new HashMap<>();
        try (InputStream resource = CityLookup.class.getResourceAsStream("/geonames/admin1-codes.txt")) {
            if (resource == null) throw new IOException("missing administrative names");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] f = line.split("\\t", 4);
                    if (f.length >= 2) names.put(f[0], f[1]);
                }
            }
        } catch (IOException failure) { throw new IllegalStateException("City administration names unavailable", failure); }
        return Collections.unmodifiableMap(names);
    }
    private static Query parse(String input) {
        if (input == null || input.length() > 120) return null;
        String q = Normalizer.normalize(input, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if (q.codePointCount(0,q.length()) < 2 || q.codePointCount(0,q.length()) > 100 || q.chars().anyMatch(Character::isISOControl)) return null;
        String country = "", admin = "";
        String[] parts = q.split(",", -1);
        if (parts.length > 3) return null;
        if (parts.length > 1) {
            q = parts[0].strip();
            String qualifier = parts[parts.length-1].strip();
            country = countryCode(qualifier);
            if (parts.length == 3) {
                if (country.isEmpty()) return null;
                Region region = region(parts[1].strip());
                if (region == null || !country.equals(region.country)) return null;
                admin = region.admin;
            } else if (country.isEmpty()) {
                Region region = region(qualifier);
                if (region == null) return null;
                country = region.country;
                admin = region.admin;
            }
        }
        for (String[] prefix : new String[][] {{"中华人民共和国", "CN"}, {"中国", "CN"}, {"英国", "GB"}, {"美国", "US"}}) {
            if (q.startsWith(prefix[0]) && q.substring(prefix[0].length()).codePointCount(0, q.length() - prefix[0].length()) >= 2) {
                if (!country.isEmpty() && !country.equals(prefix[1])) return null;
                country = prefix[1];
                q = q.substring(prefix[0].length()).strip();
                break;
            }
        }
        for (var entry : CN_PROVINCES.entrySet()) {
            String province = entry.getValue();
            // A municipality itself may be the city being searched (北京市); only
            // consume a province prefix when a separate city name remains.
            String remainder = q.startsWith(province) ? q.substring(province.length()) : "";
            if (remainder.startsWith("省") || remainder.startsWith("市")) remainder = remainder.substring(1);
            if (remainder.codePointCount(0, remainder.length()) >= 2) {
                if (!country.isEmpty() && !country.equals("CN")) return null;
                if (!admin.isEmpty() && !admin.equals(entry.getKey())) return null;
                country = "CN"; admin = entry.getKey();
                q = remainder;
                break;
            }
        }
        if (q.codePointCount(0,q.length()) < 2) return null;
        return new Query(q, country, admin);
    }
    private static Region region(String value) {
        return switch (value) {
            case "england" -> new Region("GB", "ENG");
            case "scotland" -> new Region("GB", "SCT");
            case "wales" -> new Region("GB", "WLS");
            case "northern ireland" -> new Region("GB", "NIR");
            case "anhui" -> new Region("CN", "01");
            case "zhejiang" -> new Region("CN", "02");
            case "jiangxi" -> new Region("CN", "03");
            case "jiangsu" -> new Region("CN", "04");
            case "jilin" -> new Region("CN", "05");
            case "qinghai" -> new Region("CN", "06");
            case "fujian" -> new Region("CN", "07");
            case "heilongjiang" -> new Region("CN", "08");
            case "henan" -> new Region("CN", "09");
            case "hebei" -> new Region("CN", "10");
            case "hunan" -> new Region("CN", "11");
            case "hubei" -> new Region("CN", "12");
            case "xinjiang" -> new Region("CN", "13");
            case "tibet" -> new Region("CN", "14");
            case "gansu" -> new Region("CN", "15");
            case "guangxi" -> new Region("CN", "16");
            case "guizhou" -> new Region("CN", "18");
            case "liaoning" -> new Region("CN", "19");
            case "inner mongolia" -> new Region("CN", "20");
            case "ningxia" -> new Region("CN", "21");
            case "beijing" -> new Region("CN", "22");
            case "shanghai" -> new Region("CN", "23");
            case "shanxi" -> new Region("CN", "24");
            case "shandong" -> new Region("CN", "25");
            case "shaanxi" -> new Region("CN", "26");
            case "tianjin" -> new Region("CN", "28");
            case "yunnan" -> new Region("CN", "29");
            case "guangdong" -> new Region("CN", "30");
            case "hainan" -> new Region("CN", "31");
            case "sichuan" -> new Region("CN", "32");
            case "chongqing" -> new Region("CN", "33");
            default -> null;
        };
    }
    private static String countryCode(String qualifier) {
        if (Set.of("uk","u.k.","united kingdom","英国").contains(qualifier)) return "GB";
        if (Set.of("china","中国","中华人民共和国").contains(qualifier)) return "CN";
        if (Set.of("usa","u.s.a.","united states","美国").contains(qualifier)) return "US";
        for (String country : Locale.getISOCountries()) {
            Locale locale = new Locale("",country);
            if (country.equalsIgnoreCase(qualifier) || locale.getDisplayCountry(Locale.ENGLISH).equalsIgnoreCase(qualifier)) return country;
        }
        return "";
    }
    private static int compareKey(byte[] bytes, int start, byte[] key) {
        int i = 0;
        while (i < key.length && start+i < bytes.length && bytes[start+i] != '\t') {
            int diff = (bytes[start+i] & 255) - (key[i] & 255);
            if (diff != 0) return diff;
            i++;
        }
        if (i == key.length) return start+i < bytes.length && bytes[start+i] == '\t' ? 0 : 1;
        return -1;
    }
    private static Result search(byte[] bytes, byte[] records, Map<String,String> admins, Query query) {
        byte[] key = query.name.getBytes(StandardCharsets.UTF_8);
        int low = 0, high = bytes.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            int start = mid;
            while (start > low && bytes[start - 1] != '\n') start--;
            int end = mid;
            while (end < bytes.length && bytes[end] != '\n') end++;
            if (compareKey(bytes, start, key) < 0) low = end + 1; else high = start;
        }
        List<City> matches = new ArrayList<>();
        boolean chinese = query.name.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff);
        int pos = low;
        while (pos < bytes.length && compareKey(bytes, pos, key) == 0) {
            int end = pos;
            while (end < bytes.length && bytes[end] != '\n') end++;
            int tab = pos;
            while (tab < end && bytes[tab] != '\t') tab++;
            int record = Integer.parseInt(new String(bytes, tab + 1, end-tab-1, StandardCharsets.UTF_8));
            int recordEnd = record;
            while (recordEnd < records.length && records[recordEnd] != '\n') recordEnd++;
            String[] f = new String(records, record, recordEnd-record, StandardCharsets.UTF_8).split("\\t", -1);
            pos = end+1;
            if (f.length != 6 || (!query.country.isEmpty() && !query.country.equals(f[4])) || (!query.admin.isEmpty() && !query.admin.equals(f[5]))) continue;
            if (matches.size() == MAX_RESULTS) return new Result(List.copyOf(matches), true);
            String display = chinese ? query.name : f[1];
            String admin = f[4].equals("CN") && chinese ? CN_PROVINCES.getOrDefault(f[5], admins.getOrDefault(f[4]+"."+f[5], "")) : admins.getOrDefault(f[4]+"."+f[5], "");
            String nation = chinese && f[4].equals("CN") ? "中国" : chinese && f[4].equals("GB") ? "英国" : new Locale("", f[4]).getDisplayCountry(Locale.ENGLISH);
            String label = display + (admin.isEmpty() || admin.equalsIgnoreCase(display) ? "" : ", " + admin) + ", " + nation;
            matches.add(new City(f[0], f[1], label, f[4], Double.parseDouble(f[2]), Double.parseDouble(f[3])));
        }
        return new Result(List.copyOf(matches), false);
    }
}
