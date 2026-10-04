import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Trusted, fixed-destination HTTP adapter. Runs inside the network=none container. */
public class PreviewBridge {
    public static void main(String[] args) throws Exception {
        var in = new DataInputStream(System.in);
        var method = in.readUTF(); var path = in.readUTF();
        if (!java.util.Set.of("GET", "HEAD", "POST", "DELETE", "PUT").contains(method)
                || !path.startsWith("/") || path.startsWith("//") || path.contains("\r") || path.contains("\n"))
            throw new IllegalArgumentException("invalid request");
        var uri = URI.create("http://127.0.0.1:3000" + path);
        if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 3000 || uri.getUserInfo() != null)
            throw new IllegalArgumentException("fixed destination required");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8));
        int count = in.readInt(); if (count < 0 || count > 16) throw new IllegalArgumentException();
        for (int i = 0; i < count; i++) {
            var key = in.readUTF(); var value = in.readUTF();
            if (!java.util.Set.of("accept", "content-type", "cookie", "user-agent").contains(key)) throw new IllegalArgumentException();
            request.header(key, value);
        }
        int size = in.readInt(); if (size < 0 || size > 1024 * 1024) throw new IllegalArgumentException();
        var body = in.readNBytes(size); if (body.length != size) throw new EOFException();
        request.method(method, size == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        var response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes; try (var stream = response.body()) { bytes = stream.readNBytes(4 * 1024 * 1024 + 1); }
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("response limit");
        var headers = new java.util.ArrayList<String[]>();
        for (var key : java.util.List.of("content-type", "location", "set-cookie"))
            for (var value : response.headers().allValues(key)) {
                if (key.equals("location") && (!value.startsWith("/") || value.startsWith("//"))) continue;
                // Never allow a preview cookie to target another origin.
                if (key.equals("set-cookie") && value.toLowerCase().contains("domain=")) continue;
                headers.add(new String[]{key, value});
            }
        var out = new DataOutputStream(System.out); out.writeInt(response.statusCode()); out.writeInt(headers.size());
        for (var h : headers) { out.writeUTF(h[0]); out.writeUTF(h[1]); }
        out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
}
