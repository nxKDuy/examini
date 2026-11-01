package com.datn.questionbank.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a public web page (or PDF) a teacher links as AI material. Guarded against server-side request forgery:
 * only http/https on the default ports, no credentials in the URL, every hop (redirects are followed by hand, at most
 * {@value #MAX_REDIRECTS}) must resolve only to public addresses — loopback, private, link-local (cloud metadata),
 * CGNAT, multicast and unique-local ranges are refused, and so are Docker service names. The JVM caches the lookup
 * it validated (networkaddress.cache.ttl, 30 s by default), so the connection uses the address that was checked.
 * Size ({@value #MAX_BYTES} bytes) and time limits keep a slow or huge page from tying up the service.
 */
@Component
public class WebMaterialFetcher {
    static final int MAX_BYTES = 3 * 1024 * 1024, MAX_REDIRECTS = 3;
    private final int connectMs, readMs;
    private final boolean allowPrivate;

    public record Fetched(String url, String title, byte[] body, String contentType, String text) {}
    public static class FetchException extends RuntimeException { public FetchException(String m) { super(m); } }

    @org.springframework.beans.factory.annotation.Autowired
    public WebMaterialFetcher(@Value("${question-bank.ai.web-connect-ms:5000}") int connectMs,
                              @Value("${question-bank.ai.web-read-ms:10000}") int readMs) { this(connectMs, readMs, false); }
    /** {@code allowPrivate} exists for tests against a loopback stub only. */
    WebMaterialFetcher(int connectMs, int readMs, boolean allowPrivate) { this.connectMs = connectMs; this.readMs = readMs; this.allowPrivate = allowPrivate; }

    public Fetched fetch(String raw) {
        URI uri = parse(raw);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            check(uri);
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) uri.toURL().openConnection(proxyFor(uri));
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectMs); c.setReadTimeout(readMs);
                c.setRequestProperty("User-Agent", "EXAMINI/1.0 (tai lieu cho giao vien soan cau hoi)");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,application/pdf;q=0.9,*/*;q=0.1");
                c.setRequestProperty("Accept-Language", "vi,en;q=0.8");
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isBlank()) throw new FetchException("Trang chuyển hướng không hợp lệ");
                    uri = parse(uri.resolve(loc.strip()).toString());
                    continue;
                }
                if (status == 401 || status == 403) throw new FetchException("Trang yêu cầu đăng nhập hoặc chặn truy cập (" + status + ")");
                if (status == 404 || status == 410) throw new FetchException("Không tìm thấy trang (" + status + ")");
                if (status >= 400) throw new FetchException("Trang trả lỗi " + status);
                long len = c.getContentLengthLong();
                if (len > MAX_BYTES) throw new FetchException("Trang quá lớn (giới hạn 3MB)");
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                byte[] body = read(c.getInputStream());
                if (type.startsWith("application/pdf") || startsWith(body, "%PDF-"))
                    return new Fetched(uri.toString(), fileTitle(uri), body, "application/pdf", null);
                if (!(type.isEmpty() || type.startsWith("text/html") || type.startsWith("application/xhtml") || type.startsWith("text/plain")))
                    throw new FetchException("Chỉ đọc được trang web, văn bản hoặc PDF (nhận được " + type.split(";")[0] + ")");
                String html = new String(body, charset(type, body));
                if (type.startsWith("text/plain")) return new Fetched(uri.toString(), fileTitle(uri), body, "text/plain", HtmlText.plain(html));
                return new Fetched(uri.toString(), title(html, uri), body, "text/html", HtmlText.text(html));
            } catch (FetchException e) {
                throw e;
            } catch (SocketTimeoutException e) {
                throw new FetchException("Trang phản hồi quá chậm");
            } catch (UnknownHostException e) {
                throw new FetchException("Không tìm thấy tên miền " + uri.getHost());
            } catch (IOException e) {
                throw new FetchException("Không tải được trang: " + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new FetchException("Trang chuyển hướng quá nhiều lần");
    }

    /** Outbound proxy only when the JVM is configured with one; the address check still applies to the target host. */
    private static Proxy proxyFor(URI uri) {
        try {
            var list = ProxySelector.getDefault() == null ? null : ProxySelector.getDefault().select(uri);
            return list == null || list.isEmpty() ? Proxy.NO_PROXY : list.getFirst();
        } catch (RuntimeException e) { return Proxy.NO_PROXY; }
    }

    static URI parse(String raw) {
        if (raw == null || raw.isBlank()) throw new FetchException("Chưa nhập đường dẫn");
        String s = raw.strip();
        if (s.length() > 2000) throw new FetchException("Đường dẫn quá dài");
        if (!s.contains("://") && !s.matches("(?i)^(file|data|javascript|mailto|ftp|gopher|jar|ldap|dict|ssh|sftp|tel):.*")) s = "https://" + s;
        URI u;
        try { u = new URI(s).normalize(); } catch (URISyntaxException e) {
            try { var url = new URL(s); u = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef()); }
            catch (Exception ex) { throw new FetchException("Đường dẫn không hợp lệ"); }
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new FetchException("Chỉ hỗ trợ đường dẫn http/https");
        if (u.getHost() == null || u.getHost().isBlank()) throw new FetchException("Đường dẫn thiếu tên miền");
        if (u.getRawUserInfo() != null) throw new FetchException("Đường dẫn không được chứa tên đăng nhập/mật khẩu");
        return u;
    }

    void check(URI u) {
        if (allowPrivate) return;
        int port = u.getPort();
        if (port != -1 && port != 80 && port != 443) throw new FetchException("Chỉ hỗ trợ cổng web mặc định (80/443)");
        String host = u.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        // A name without a dot is a container or intranet name (postgres, auth-service, localhost…), never a public site.
        if (!host.contains(".") && !host.contains(":")) throw new FetchException("Không được đọc địa chỉ nội bộ");
        if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) throw new FetchException("Không được đọc địa chỉ nội bộ");
        InetAddress[] all;
        try { all = InetAddress.getAllByName(host); }
        catch (UnknownHostException e) {
            // Proxy-only networks resolve names at the configured outbound proxy, which cannot reach this Docker network;
            // internal names and literal addresses were refused above, so only public-looking names get here.
            if (proxyFor(u) != Proxy.NO_PROXY && proxyFor(u).type() == Proxy.Type.HTTP) return;
            throw new FetchException("Không tìm thấy tên miền " + host);
        }
        for (InetAddress a : all) if (!isPublic(a)) throw new FetchException("Không được đọc địa chỉ nội bộ");
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int x = b[0] & 0xff, y = b[1] & 0xff;
            if (x == 0 || x >= 224) return false;                 // "this network", multicast, reserved, broadcast
            if (x == 100 && y >= 64 && y <= 127) return false;     // CGNAT 100.64/10
            if (x == 192 && y == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0/24 protocol assignments
            if (x == 198 && (y == 18 || y == 19)) return false;    // benchmarking
            return true;
        }
        if ((b[0] & 0xfe) == 0xfc) return false;                   // unique local fc00::/7
        boolean mapped = true;                                     // ::ffff:a.b.c.d → check the IPv4 inside
        for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
        if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            try { return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]})); } catch (UnknownHostException e) { return false; }
        }
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return false; // documentation
        return true;
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in) {
            var out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_BYTES) throw new FetchException("Trang quá lớn (giới hạn 3MB)");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)");
    static Charset charset(String contentType, byte[] body) {
        Matcher h = Pattern.compile("(?i)charset=([A-Za-z0-9_\\-]+)").matcher(contentType);
        String name = h.find() ? h.group(1) : null;
        if (name == null) {
            Matcher m = META_CHARSET.matcher(new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1));
            if (m.find()) name = m.group(1);
        }
        try { return name == null ? StandardCharsets.UTF_8 : Charset.forName(name); } catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    static String title(String html, URI u) {
        Matcher m = TITLE.matcher(html);
        String t = m.find() ? HtmlText.unescape(m.group(1)).replaceAll("\\s+", " ").strip() : "";
        return t.isEmpty() ? fileTitle(u) : (t.length() > 200 ? t.substring(0, 200) : t);
    }
    static String fileTitle(URI u) {
        String p = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
        String last = p.substring(p.lastIndexOf('/') + 1);
        try { last = URLDecoder.decode(last, StandardCharsets.UTF_8); } catch (Exception ignored) {}
        return last.isBlank() ? u.getHost() : last;
    }
    private static boolean startsWith(byte[] b, String prefix) {
        if (b.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (b[i] != prefix.charAt(i)) return false;
        return true;
    }
}
