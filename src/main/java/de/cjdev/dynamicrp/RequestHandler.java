package de.cjdev.dynamicrp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RequestHandler implements HttpHandler {
    public void handle(HttpExchange exchange) {
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                URI requestURI = exchange.getRequestURI();
                Map<String, String> queryParams = DynamicRPVelocity.mapGETQuery(requestURI.getQuery());
                String server = queryParams.get("server");
                String host_name;
                if (server != null && (host_name = DynamicRPVelocity.HOST_NAMES.get(server)) != null) {
                    String uuid = queryParams.get("uuid");
                    URL url = new URL("http://" + host_name + "/" + (uuid == null ? "" : "?uuid=" + uuid));
                    HttpURLConnection conn = (HttpURLConnection)url.openConnection();
                    conn.setRequestMethod("GET");
                    exchange.getRequestHeaders().forEach((key, values) -> {
                        for(String value : values) {
                            conn.addRequestProperty(key, value);
                        }

                    });
                    conn.setDoInput(true);
                    int responseCode = conn.getResponseCode();
                    exchange.sendResponseHeaders(responseCode, (long)conn.getContentLength());
                    InputStream is = conn.getInputStream();

                    try {
                        OutputStream os = exchange.getResponseBody();

                        try {
                            is.transferTo(os);
                        } catch (Throwable var35) {
                            if (os != null) {
                                try {
                                    os.close();
                                } catch (Throwable var33) {
                                    var35.addSuppressed(var33);
                                }
                            }

                            throw var35;
                        }

                        if (os != null) {
                            os.close();
                        }
                    } catch (Throwable var36) {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (Throwable var32) {
                                var36.addSuppressed(var32);
                            }
                        }

                        throw var36;
                    }

                    is.close();

                    conn.disconnect();
                    return;
                }

                String response = "File not found!";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(404, (long)responseBytes.length);
                OutputStream os = exchange.getResponseBody();

                try {
                    os.write(responseBytes);
                } catch (Throwable var37) {
                    if (os != null) {
                        try {
                            os.close();
                        } catch (Throwable var34) {
                            var37.addSuppressed(var34);
                        }
                    }

                    throw var37;
                }

                os.close();

                return;
            }

            String msg = "405 Method Not Allowed\n";
            exchange.getResponseHeaders().add("Allow", "GET");
            exchange.sendResponseHeaders(405, (long)msg.length());
            exchange.getResponseBody().write(msg.getBytes());
        } catch (Exception e) {
            e.printStackTrace();

            try {
                String msg = "Proxy error: " + e.getMessage();
                exchange.sendResponseHeaders(500, (long)msg.length());
                exchange.getResponseBody().write(msg.getBytes());
            } catch (Exception var31) {
            }

        } finally {
            try {
                exchange.close();
            } catch (Exception ignored) {
            }

        }

    }
}
