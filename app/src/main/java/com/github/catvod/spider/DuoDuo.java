package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DuoDuo extends Spider {

    private static final String SITE = "https://323433ssdfd.top";
    private static final String CLIENT = "8f3d2a1c7b6e5d4c9a0b1f2e3d4c5b6a";
    private static final String WEB_SIGN = "ddtvf65f3a83d6d9ad6f";
    // 播放地址解码签名所需的固定常量，从站点 web_app_wasm 中提取
    private static final String FINGER = "WF-2c064bc5b3400788f31b848849bc3a60f835423ba2dfe69d7ea93974c216e4f2";
    private static final String SK = "WEB-50a8e9c84a1dc05669a692ded99a2dac46527229e607a7be15db88dbc59059d1";
    private static final String AID = "com.web.player";
    private static final String VER = "1";
    private static final String PROTOBUF = "application/x-protobuf";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build();

    private Map<String, String> getHeaders() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", Util.CHROME);
        header.put("Accept", "application/json");
        header.put("X-Client", CLIENT);
        header.put("web-sign", WEB_SIGN);
        return header;
    }

    private JsonObject api(String path) {
        return Json.safeObject(OkHttp.string(SITE + path, getHeaders()));
    }

    @Override
    public String homeContent(boolean filter) {
        JsonObject data = api("/api.php/web/index/home").getAsJsonObject("data");
        List<Class> classes = new ArrayList<>();
        List<Vod> videos = new ArrayList<>();
        if (data != null && data.has("categories")) {
            for (JsonElement element : data.getAsJsonArray("categories")) {
                JsonObject category = element.getAsJsonObject();
                String name = text(category, "type_name");
                if (TextUtils.isEmpty(name)) continue;
                classes.add(new Class(name, name));
                JsonArray list = category.getAsJsonArray("videos");
                if (list == null) continue;
                for (JsonElement video : list) videos.add(parseVod(video.getAsJsonObject()));
            }
        }
        return Result.string(classes, videos);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        JsonObject root = api("/api.php/web/filter/vod?type_name=" + encode(tid) + "&page=" + pg + "&sort=hits");
        List<Vod> list = parseList(root.getAsJsonArray("data"));
        return Result.get().vod(list).page(intOf(root, "page", 1), intOf(root, "pageCount", 0), 0, 0).string();
    }

    @Override
    public String detailContent(List<String> ids) {
        JsonObject root = api("/api.php/web/vod/get_detail?vod_id=" + ids.get(0));
        JsonArray array = root.getAsJsonArray("data");
        if (array == null || array.size() == 0) return Result.error("获取详情失败");
        JsonObject object = array.get(0).getAsJsonObject();
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(text(object, "vod_name"));
        vod.setVodPic(text(object, "vod_pic"));
        vod.setVodRemarks(text(object, "vod_remarks"));
        vod.setVodYear(text(object, "vod_year"));
        vod.setVodArea(text(object, "vod_area"));
        vod.setVodActor(text(object, "vod_actor"));
        vod.setVodDirector(text(object, "vod_director"));
        vod.setVodContent(clean(text(object, "vod_content")));
        vod.setTypeName(text(object, "type_name"));
        Map<String, String> shows = new HashMap<>();
        JsonArray players = root.getAsJsonArray("vodplayer");
        if (players != null) for (JsonElement element : players) {
            JsonObject player = element.getAsJsonObject();
            shows.put(text(player, "from"), text(player, "show"));
        }
        String[] froms = text(object, "vod_play_from").split("\\$\\$\\$");
        String[] urls = text(object, "vod_play_url").split("\\$\\$\\$");
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        for (int i = 0; i < froms.length && i < urls.length; i++) {
            String from = froms[i];
            if (TextUtils.isEmpty(from) || TextUtils.isEmpty(urls[i])) continue;
            List<String> episodes = new ArrayList<>();
            for (String episode : urls[i].split("#")) {
                int index = episode.indexOf('$');
                String name = index >= 0 ? episode.substring(0, index) : episode;
                String raw = index >= 0 ? episode.substring(index + 1) : episode;
                if (TextUtils.isEmpty(raw)) continue;
                // 以 from|原始地址 的形式保存，供 playerContent 解码使用
                episodes.add(name + "$" + from + "|" + raw);
            }
            if (episodes.isEmpty()) continue;
            playFrom.add(shows.containsKey(from) ? shows.get(from) : from);
            playUrl.add(TextUtils.join("#", episodes));
        }
        vod.setVodPlayFrom(TextUtils.join("$$$", playFrom));
        vod.setVodPlayUrl(TextUtils.join("$$$", playUrl));
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        JsonObject root = api("/api.php/web/search/index?wd=" + encode(key) + "&page=" + pg + "&limit=15");
        return Result.get().vod(parseList(root.getAsJsonArray("data"))).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        int index = id.indexOf('|');
        String from = index >= 0 ? id.substring(0, index) : flag;
        String raw = index >= 0 ? id.substring(index + 1) : id;
        return Result.get().url(decode(from, raw)).string();
    }

    private List<Vod> parseList(JsonArray array) {
        List<Vod> list = new ArrayList<>();
        if (array == null) return list;
        for (JsonElement element : array) list.add(parseVod(element.getAsJsonObject()));
        return list;
    }

    private Vod parseVod(JsonObject object) {
        Vod vod = new Vod();
        vod.setVodId(text(object, "vod_id"));
        vod.setVodName(text(object, "vod_name"));
        vod.setVodPic(text(object, "vod_pic"));
        vod.setVodRemarks(text(object, "vod_remarks"));
        vod.setVodYear(text(object, "vod_year"));
        vod.setTypeName(text(object, "type_name"));
        return vod;
    }

    private String decode(String from, String raw) {
        try {
            long time = System.currentTimeMillis();
            String nonce = nonce();
            byte[] body = buildRequest(raw, from, time, nonce, signature(nonce, time));
            Map<String, String> header = getHeaders();
            header.put("Accept", PROTOBUF);
            header.put("Content-Type", PROTOBUF);
            Request request = new Request.Builder().url(SITE + "/api.php/web/decode/url").post(RequestBody.create(body, MediaType.get(PROTOBUF))).headers(Headers.of(header)).build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (response.body() == null) return raw;
                DecodeResult result = parseResponse(response.body().bytes());
                return result.code == 1 && !TextUtils.isEmpty(result.data) ? result.data : raw;
            }
        } catch (Throwable e) {
            return raw;
        }
    }

    // sign = SHA256(finger=..&id=..&nonce=..&sk=..&time=..&v=..)
    private String signature(String nonce, long time) throws Exception {
        String preimage = "finger=" + FINGER + "&id=" + AID + "&nonce=" + nonce + "&sk=" + SK + "&time=" + time + "&v=" + VER;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(preimage.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) builder.append(String.format("%02X", b));
        return builder.toString();
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    private byte[] buildRequest(String url, String from, long time, String nonce, String sign) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 1, url);
        writeString(out, 2, from);
        writeVarintField(out, 3, time);
        writeString(out, 4, nonce);
        writeString(out, 5, sign);
        writeString(out, 6, AID);
        writeVarintField(out, 7, 1);
        return out.toByteArray();
    }

    private void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, (field << 3) | 0);
        writeVarint(out, value);
    }

    private void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) out.write(b | 0x80);
            else {
                out.write(b);
                break;
            }
        }
    }

    private DecodeResult parseResponse(byte[] bytes) {
        DecodeResult result = new DecodeResult();
        int i = 0;
        while (i < bytes.length) {
            long tag = readVarint(bytes, i);
            int pos = i + varintSize(tag);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long value = readVarint(bytes, pos);
                if (field == 1) result.code = (int) value;
                pos += varintSize(value);
            } else if (wire == 2) {
                long length = readVarint(bytes, pos);
                pos += varintSize(length);
                String value = new String(bytes, pos, (int) length, StandardCharsets.UTF_8);
                if (field == 2) result.msg = value;
                else if (field == 3) result.data = value;
                pos += length;
            } else {
                break;
            }
            i = pos;
        }
        return result;
    }

    private long readVarint(byte[] bytes, int start) {
        long value = 0;
        int shift = 0;
        for (int i = start; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return value;
    }

    private int varintSize(long value) {
        int size = 1;
        while ((value >>>= 7) != 0) size++;
        return size;
    }

    private static class DecodeResult {
        int code;
        String msg;
        String data;
    }

    private String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) if (!item.isJsonNull()) parts.add(item.getAsString());
            return TextUtils.join(",", parts);
        }
        return element.getAsString();
    }

    private int intOf(JsonObject object, String key, int def) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? def : element.getAsInt();
    }

    private String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Throwable e) {
            return text;
        }
    }

    private String clean(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }
}
