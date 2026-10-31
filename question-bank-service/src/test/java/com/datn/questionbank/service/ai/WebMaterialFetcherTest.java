package com.datn.questionbank.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebMaterialFetcherTest {
    static HttpServer server;
    static String base;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lesson", ex -> send(ex, 200, "text/html", """
                <html><head><meta charset="utf-8"><title>Quang hợp &amp; hô hấp</title><script>var secret=1</script><style>p{}</style></head>
                <body><nav>Trang chủ | Liên hệ</nav><article><h1>Quang hợp</h1><p>Quang hợp là quá trình cây xanh dùng năng lượng ánh sáng
                để tổng hợp chất hữu cơ từ CO&#8322; và nước.</p><ul><li>Pha sáng</li><li>Pha tối</li></ul>
                <p>%s</p></article><footer>© 2026</footer></body></html>""".formatted("Diệp lục hấp thụ ánh sáng đỏ và xanh tím. ".repeat(12)).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/hop", ex -> { ex.getResponseHeaders().add("Location", "/lesson"); send(ex, 302, "text/plain", new byte[0]); });
        server.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); send(ex, 301, "text/plain", new byte[0]); });
        server.createContext("/zip", ex -> send(ex, 200, "application/zip", new byte[]{1, 2, 3}));
        server.createContext("/big", ex -> send(ex, 200, "text/html", new byte[WebMaterialFetcher.MAX_BYTES + 10]));
        server.createContext("/doc.pdf", ex -> send(ex, 200, "application/octet-stream", "%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII)));
        server.createContext("/private", ex -> send(ex, 403, "text/html", new byte[0]));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterAll static void stop() { server.stop(0); }
    static void send(com.sun.net.httpserver.HttpExchange ex, int status, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (var o = ex.getResponseBody()) { o.write(body); }
        ex.close();
    }
    /** Loopback stub: address check off, everything else (parsing, redirects, limits) as in production. */
    static WebMaterialFetcher stubbed() { return new WebMaterialFetcher(2000, 2000, true) {
        @Override void check(URI u) {} }; }
    static String fetchError(WebMaterialFetcher f, String url) {
        return assertThrows(WebMaterialFetcher.FetchException.class, () -> f.fetch(url)).getMessage();
    }

    @Test void readsMainArticleTextWithCharsetAndFollowsRedirect() {
        var page = stubbed().fetch(base + "/hop");
        assertEquals(base + "/lesson", page.url());
        assertEquals("Quang hợp & hô hấp", page.title());
        assertTrue(page.text().startsWith("Quang hợp\n"), page.text());
        assertTrue(page.text().contains("từ CO₂ và nước"));
        assertTrue(page.text().contains("• Pha sáng"));
        assertFalse(page.text().contains("secret"));
        assertFalse(page.text().contains("Liên hệ"), "navigation dropped");
        assertFalse(page.text().contains("2026"), "footer dropped");
    }

    @Test void limitsAndTypes() {
        var f = stubbed();
        assertTrue(fetchError(f, base + "/loop").contains("chuyển hướng quá nhiều"));
        assertTrue(fetchError(f, base + "/zip").contains("Chỉ đọc được"));
        assertTrue(fetchError(f, base + "/big").contains("quá lớn"));
        assertTrue(fetchError(f, base + "/private").contains("403"));
        assertTrue(fetchError(f, base + "/missing").contains("404"));
        var pdf = f.fetch(base + "/doc.pdf");
        assertEquals("application/pdf", pdf.contentType());
        assertNull(pdf.text());
        assertEquals("doc.pdf", pdf.title());
    }

    @Test void urlShapeIsRestricted() {
        for (String bad : List.of("ftp://example.com/a", "file:///etc/passwd", "http://user:pw@example.com/", 
                "gopher://example.com", "", "http://", "javascript:alert(1)"))
            assertThrows(WebMaterialFetcher.FetchException.class, () -> WebMaterialFetcher.parse(bad), bad);
        assertThrows(WebMaterialFetcher.FetchException.class, () -> new WebMaterialFetcher(1000, 1000).check(WebMaterialFetcher.parse("https://example.com:8443/")));
        assertEquals("https://vi.wikipedia.org/wiki/Quang_h%E1%BB%A3p", WebMaterialFetcher.parse("vi.wikipedia.org/wiki/Quang_h%E1%BB%A3p").toString());
    }

    @Test void internalAddressesAreRefused() {
        var f = new WebMaterialFetcher(1000, 1000);
        for (String bad : List.of("http://127.0.0.1/", "http://localhost/", "http://auth-service:80/", "http://postgres/", "http://169.254.169.254/latest/meta-data",
                "http://10.0.0.5/", "http://192.168.1.1/", "http://172.18.0.1/", "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/",
                "http://100.64.1.1/", "http://0.0.0.0/", "http://2130706433/", "http://printer.local/"))
            assertThrows(WebMaterialFetcher.FetchException.class, () -> f.check(WebMaterialFetcher.parse(bad)), bad);
    }

    @Test void publicAddressClassification() throws Exception {
        assertTrue(WebMaterialFetcher.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(WebMaterialFetcher.isPublic(InetAddress.getByName("2606:4700::1111")));
        for (String a : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.100.100.100", "0.0.0.0", "224.0.0.1",
                "255.255.255.255", "::1", "fe80::1", "fc00::1", "::ffff:10.0.0.1", "2001:db8::1"))
            assertFalse(WebMaterialFetcher.isPublic(InetAddress.getByName(a)), a);
    }

    @Test void wikiChromeIsRemovedEvenWhenNested() {
        String t = HtmlText.text("""
                <main><div id="siteSub">Bách khoa toàn thư mở Wikipedia</div>
                <table class="box-Refimprove plainlinks metadata ambox ambox-content"><tr><td><div><b>Bài viết này cần thêm chú thích</b></div></td></tr></table>
                <h2>Cơ chế<span class="mw-editsection"><span class="mw-editsection-bracket">[</span><a href="#">sửa</a><span>]</span></span></h2>
                <p>Quang hợp gồm pha sáng và pha tối.<sup class="reference"><a href="#c1">[1]</a></sup> %s</p>
                <div class="reflist"><ol class="references"><li>Nguồn 1</li></ol></div></main>""".formatted("Chất diệp lục hấp thụ ánh sáng. ".repeat(20)));
        assertTrue(t.startsWith("Cơ chế\n\nQuang hợp gồm pha sáng và pha tối. Chất"), t);
        for (String junk : List.of("Bách khoa", "chú thích", "sửa", "[1]", "Nguồn 1")) assertFalse(t.contains(junk), junk + " in " + t);
        assertTrue(HtmlText.text("<div class=\"reference-content related-posts\"><p>" + "Nội dung chính. ".repeat(40) + "</p></div>").startsWith("Nội dung chính."),
                "only exact class tokens count as chrome");
        assertEquals("Định luật: V=IR", HtmlText.text("<p>Định luật: <span><math alttext=\"x\"><mi>V</mi><mo>=</mo></math><img src=\"f.svg\" alt=\"{\\displaystyle V=IR}\"></span></p>"));
        assertEquals("Dòng 1\n\nDòng 2", HtmlText.plain("  Dòng 1   \r\n   \n\n\n   Dòng 2  \n"));
    }

    @Test void htmlWithoutArticleUsesBodyAndDecodesEntities() {
        String t = HtmlText.text("<html><head><title>x</title></head><body><header>Menu</header><h2>Định luật Ôm</h2><p>I = U&nbsp;/&nbsp;R &le; 5&#x41;</p>"
                + "<table><tr><th>U</th><th>R</th></tr><tr><td>6</td><td>2</td></tr></table></body></html>");
        assertTrue(t.startsWith("Định luật Ôm\n\nI = U / R ≤ 5A"), t);
        assertTrue(t.contains("U | R") && t.contains("6 | 2"), "[" + t.replace("\n", "/") + "]");
        assertFalse(t.contains("Menu"));
    }
}
