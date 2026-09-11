package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 次元城动画 https://www.cycani.org
 */
public class Cycani extends Spider {

    private static final String SITE = "https://www.cycani.org";
    private static final String APP_NAME = "cyc_web";
    private static final String APP_VERSION = "cycweb";
    private static final String TIME_ZONE = "Asia/Shanghai";
    private static final int PAGE_SIZE = 20;
    private static final String LOGIN_ID = "cycani_login";
    private static final String QR_API = "https://api.qrserver.com/v1/create-qr-code/?size=600x600&margin=10&data=";

    private static volatile ServerSocket server;
    private static volatile int port;
    private static volatile String secret = "";

    private String token = "";
    private String account = "";
    private String password = "";
    private String qrApi = QR_API;

    @Override
    public void init(Context context, String extend) {
        JsonObject json = TextUtils.isEmpty(extend) ? new JsonObject() : Json.safeObject(extend);
        account = text(json, "account");
        if (TextUtils.isEmpty(account)) account = text(json, "username");
        password = text(json, "password");
        String api = text(json, "qrApi");
        if (!TextUtils.isEmpty(api)) qrApi = api;
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) load();
        if (!TextUtils.isEmpty(account) && !TextUtils.isEmpty(password)) login();
    }

    private boolean login() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", account);
            body.addProperty("password", password);
            JsonObject root = Json.safeObject(OkHttp.post(SITE + "/api/auth/login", body.toString(), headers()).getBody());
            JsonObject data = object(root, "data");
            if (data == null) return false;
            String value = text(data, "token");
            if (TextUtils.isEmpty(value)) value = text(data, "access_token");
            if (TextUtils.isEmpty(value)) return false;
            token = value;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        json.addProperty("account", account);
        json.addProperty("password", password);
        Path.write(Path.tv("cycani"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void load() {
        String content = Path.read(Path.tv("cycani"));
        if (TextUtils.isEmpty(content)) return;
        JsonObject json = Json.safeObject(content);
        account = text(json, "account");
        password = text(json, "password");
    }

    private Map<String, String> headers() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", Util.CHROME);
        header.put("Accept", "application/json");
        header.put("X-App-Name", APP_NAME);
        header.put("X-App-Version", APP_VERSION);
        header.put("X-Time-Zone", TIME_ZONE);
        if (!TextUtils.isEmpty(token)) header.put("Authorization", "Bearer " + token);
        return header;
    }

    private JsonObject api(String path) {
        return Json.safeObject(OkHttp.string(SITE + path, headers()));
    }

    private JsonObject data(String path) {
        JsonObject object = object(api(path), "data");
        return object == null ? new JsonObject() : object;
    }

    @Override
    public String homeContent(boolean filter) {
        ensureServer();
        List<Class> classes = new ArrayList<>();
        JsonObject filters = new JsonObject();
        JsonArray zones = data("/api/video-zones").getAsJsonArray("list");
        if (zones != null) for (JsonElement element : zones) {
            JsonObject zone = element.getAsJsonObject();
            String id = text(zone, "id");
            String name = text(zone, "name");
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
            classes.add(new Class(id, name));
            JsonArray options = buildFilters(object(zone, "filters"));
            if (options.size() > 0) filters.add(id, options);
        }
        List<Vod> videos = new ArrayList<>();
        videos.add(loginVod());
        JsonArray groups = data("/api/index/recommend").getAsJsonArray("list");
        if (groups != null) for (JsonElement element : groups) {
            JsonObject group = element.getAsJsonObject();
            videos.addAll(parseList(group.getAsJsonArray("videos")));
        }
        return Result.string(classes, videos, filters);
    }

    private JsonArray buildFilters(JsonObject filters) {
        JsonArray array = new JsonArray();
        if (filters == null) return array;
        array.add(buildFilter("tag", "分类", filters.getAsJsonArray("categories")));
        array.add(buildFilter("year", "年份", filters.getAsJsonArray("years")));
        for (int i = array.size() - 1; i >= 0; i--) if (array.get(i).getAsJsonObject().getAsJsonArray("value").size() == 0) array.remove(i);
        return array;
    }

    private JsonObject buildFilter(String key, String name, JsonArray values) {
        JsonObject filter = new JsonObject();
        filter.addProperty("key", key);
        filter.addProperty("name", name);
        JsonArray value = new JsonArray();
        JsonObject all = new JsonObject();
        all.addProperty("n", "全部");
        all.addProperty("v", "");
        value.add(all);
        if (values != null) for (JsonElement element : values) {
            JsonObject item = new JsonObject();
            item.addProperty("n", element.getAsString());
            item.addProperty("v", element.getAsString());
            value.add(item);
        }
        filter.add("value", value);
        return filter;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        StringBuilder url = new StringBuilder("/api/videos?page=" + pg + "&page_size=" + PAGE_SIZE + "&zone_id=" + tid);
        if (extend != null) {
            append(url, "tag", extend.get("tag"));
            append(url, "year", extend.get("year"));
            append(url, "area", extend.get("area"));
            append(url, "language", extend.get("language"));
        }
        JsonObject object = data(url.toString());
        List<Vod> list = parseList(object.getAsJsonArray("list"));
        int page = intOf(object, "pager", "page", 1);
        int size = intOf(object, "pager", "page_size", PAGE_SIZE);
        int total = intOf(object, "pager", "total", list.size());
        int count = size <= 0 ? 1 : (int) Math.ceil((double) total / size);
        return Result.get().vod(list).page(page, count, size, total).string();
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        if (LOGIN_ID.equals(id)) return Result.string(loginDetail());
        JsonObject object = object(api("/api/videos/" + id), "data");
        if (object == null) return Result.error("获取详情失败");
        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(text(object, "title"));
        vod.setVodPic(text(object, "cover_url"));
        vod.setVodRemarks(text(object, "remarks"));
        vod.setVodYear(text(object, "year"));
        vod.setVodArea(text(object, "area"));
        vod.setVodActor(text(object, "actor"));
        vod.setVodDirector(text(object, "director"));
        vod.setVodContent(clean(text(object, "description")));
        vod.setVodTag(text(object, "tags"));
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        JsonArray sources = object.getAsJsonArray("play_from");
        if (sources != null) for (JsonElement element : sources) {
            JsonObject source = element.getAsJsonObject();
            String code = text(source, "code");
            if (TextUtils.isEmpty(code)) continue;
            List<String> episodes = new ArrayList<>();
            for (JsonObject section : sections(id, code)) {
                String sid = text(section, "id");
                String title = text(section, "title");
                if (TextUtils.isEmpty(sid)) continue;
                episodes.add((TextUtils.isEmpty(title) ? sid : title) + "$" + sid);
            }
            if (episodes.isEmpty()) continue;
            String name = text(source, "title");
            playFrom.add(TextUtils.isEmpty(name) ? code : name);
            playUrl.add(TextUtils.join("#", episodes));
        }
        vod.setVodPlayFrom(TextUtils.join("$$$", playFrom));
        vod.setVodPlayUrl(TextUtils.join("$$$", playUrl));
        return Result.string(vod);
    }

    private List<JsonObject> sections(String videoId, String code) {
        List<JsonObject> list = new ArrayList<>();
        int page = 1;
        while (page <= 20) {
            JsonObject object = data("/api/videos/" + videoId + "/sections?player_code=" + encode(code) + "&page=" + page + "&page_size=100");
            JsonArray array = object.getAsJsonArray("list");
            if (array == null) break;
            for (JsonElement element : array) list.add(element.getAsJsonObject());
            int total = intOf(object, "pager", "total", list.size());
            if (list.size() >= total || array.size() == 0) break;
            page++;
        }
        return list;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        JsonObject object = data("/api/videos/search?q=" + encode(key) + "&page=" + pg + "&page_size=" + PAGE_SIZE);
        return Result.get().vod(parseList(object.getAsJsonArray("list"))).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(token)) return Result.error("次元城播放需要登录，请打开「次元城登录」扫码登录");
        JsonObject object = object(api("/api/v2/sections/" + id + "/play-url"), "data");
        if (object == null) return Result.error("获取播放地址失败");
        String url = text(object, "url");
        if (TextUtils.isEmpty(url)) return Result.error("获取播放地址失败");
        return Result.get().url(url).header(headers()).string();
    }

    @Override
    public String action(String action) {
        if ("login".equalsIgnoreCase(action)) {
            ensureServer();
            Notify.show("请用手机浏览器打开：" + loginUrl());
            return "";
        }
        if ("logout".equalsIgnoreCase(action)) {
            token = "";
            account = "";
            password = "";
            Path.clear(Path.tv("cycani"));
            Notify.show("已退出次元城登录");
            return "";
        }
        return "";
    }

    private Vod loginVod() {
        Vod vod = new Vod();
        vod.setVodId(LOGIN_ID);
        vod.setVodName("次元城登录");
        vod.setVodPic(qrUrl());
        vod.setVodRemarks(TextUtils.isEmpty(token) ? "未登录·点击扫码" : "已登录");
        return vod;
    }

    private Vod loginDetail() {
        ensureServer();
        Vod vod = new Vod();
        vod.setVodId(LOGIN_ID);
        vod.setVodName("次元城登录");
        vod.setVodPic(qrUrl());
        vod.setVodRemarks(TextUtils.isEmpty(token) ? "未登录" : "已登录");
        vod.setVodContent("手机扫码打开登录页面，输入次元城账号密码，电视端将自动登录。\n\n登录地址：" + loginUrl());
        return vod;
    }

    private String qrUrl() {
        return qrApi + encode(loginUrl());
    }

    private String loginUrl() {
        ensureServer();
        return "http://" + lanIp() + ":" + port + "/" + secret;
    }

    private String lanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    private synchronized void ensureServer() {
        if (server != null && !server.isClosed()) return;
        try {
            if (TextUtils.isEmpty(secret)) secret = Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF));
            ServerSocket socket = new ServerSocket(0);
            socket.setReuseAddress(true);
            port = socket.getLocalPort();
            server = socket;
            Thread thread = new Thread(() -> loop(socket), "cycani-login");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable ignored) {
        }
    }

    private void loop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                handle(socket.accept());
            } catch (Throwable ignored) {
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            InputStream in = client.getInputStream();
            String head = readHead(in);
            if (TextUtils.isEmpty(head)) {
                client.close();
                return;
            }
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first.length > 0 ? first[0] : "";
            String path = first.length > 1 ? first[1] : "";
            int length = 0;
            for (String line : lines) {
                int index = line.indexOf(':');
                if (index > 0 && line.substring(0, index).trim().equalsIgnoreCase("Content-Length")) length = (int) parse(line.substring(index + 1).trim());
            }
            String body = length > 0 ? readBody(in, length) : "";
            String prefix = "/" + secret;
            if (!path.startsWith(prefix)) {
                write(client, page("404", "<h3>页面不存在</h3>"));
            } else if (path.startsWith(prefix + "/login") && "POST".equalsIgnoreCase(method)) {
                Map<String, String> params = form(body);
                if (submit(params.get("account"), params.get("password"))) write(client, page("登录成功", "<h3>登录成功</h3><p>可以关闭此页面，回到电视继续使用。</p>"));
                else write(client, page("登录失败", "<h3>登录失败</h3><p>账号或密码不正确，请返回重试。</p><p><a href=\"" + prefix + "\">返回重试</a></p>"));
            } else {
                write(client, form());
            }
            client.close();
        } catch (Throwable ignored) {
        }
    }

    private long parse(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    private String readHead(InputStream in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            int a = -1, b = -1, c = -1, d = -1;
            while ((d = in.read()) != -1) {
                out.write(d);
                if (a == '\r' && b == '\n' && c == '\r' && d == '\n') break;
                a = b;
                b = c;
                c = d;
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private String readBody(InputStream in, int length) {
        byte[] buffer = new byte[length];
        int offset = 0;
        try {
            while (offset < length) {
                int read = in.read(buffer, offset, length - offset);
                if (read < 0) break;
                offset += read;
            }
        } catch (Throwable ignored) {
        }
        return new String(buffer, 0, offset, StandardCharsets.UTF_8);
    }

    private void write(Socket client, String html) {
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private Map<String, String> form(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        for (String pair : body.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) continue;
            try {
                map.put(URLDecoder.decode(pair.substring(0, index), "UTF-8"), URLDecoder.decode(pair.substring(index + 1), "UTF-8"));
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private boolean submit(String user, String pass) {
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) return false;
        account = user;
        password = pass;
        if (!login()) return false;
        save();
        Notify.show("次元城登录成功");
        return true;
    }

    private String form() {
        return page("次元城登录", "<h3>次元城登录</h3><p>请输入次元城账号密码，登录后电视端自动同步。</p>"
                + "<form method=\"post\" action=\"/" + secret + "/login\">"
                + "<input name=\"account\" placeholder=\"账号 / 邮箱\" autocomplete=\"username\">"
                + "<input name=\"password\" type=\"password\" placeholder=\"密码\" autocomplete=\"current-password\">"
                + "<button type=\"submit\">登录</button></form>");
    }

    private String page(String title, String body) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><style>"
                + "body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;background:#0f1115;color:#e6e8eb;margin:0;padding:32px 20px;}"
                + "h3{margin:0 0 12px;font-size:22px;}p{color:#9aa4b2;line-height:1.6;}"
                + "form{max-width:360px;margin:24px auto 0;display:flex;flex-direction:column;gap:14px;}"
                + "input{height:48px;border-radius:12px;border:1px solid #2a2f3a;background:#171a21;color:#e6e8eb;padding:0 14px;font-size:16px;outline:none;}"
                + "input:focus{border-color:#3b82f6;}"
                + "button{height:48px;border:0;border-radius:12px;background:#3b82f6;color:#fff;font-size:16px;font-weight:600;}"
                + "a{color:#60a5fa;}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private List<Vod> parseList(JsonArray array) {
        List<Vod> list = new ArrayList<>();
        if (array == null) return list;
        for (JsonElement element : array) list.add(parseVod(element.getAsJsonObject()));
        return list;
    }

    private Vod parseVod(JsonObject object) {
        Vod vod = new Vod();
        vod.setVodId(text(object, "video_id"));
        vod.setVodName(text(object, "title"));
        vod.setVodPic(text(object, "cover_url"));
        vod.setVodRemarks(text(object, "remarks"));
        vod.setVodYear(text(object, "year"));
        return vod;
    }

    private void append(StringBuilder url, String key, String value) {
        if (TextUtils.isEmpty(value)) return;
        url.append('&').append(key).append('=').append(encode(value));
    }

    private JsonObject object(JsonObject object, String key) {
        if (object == null) return null;
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private int intOf(JsonObject object, String parent, String key, int def) {
        JsonObject pager = object(object, parent);
        if (pager == null) return def;
        JsonElement element = pager.get(key);
        return element == null || element.isJsonNull() ? def : element.getAsInt();
    }

    private String text(JsonObject object, String key) {
        if (object == null) return "";
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) if (!item.isJsonNull()) parts.add(item.getAsString());
            return TextUtils.join(",", parts);
        }
        return element.getAsString();
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
