package br.com.yalog.family;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

final class ReportApi {
    record Reply(int status, JSONObject data) {}

    static Reply post(String base, String endpoint, String key, JSONObject payload)
            throws IOException, JSONException {
        URI address = URI.create(base + "/api/" + endpoint);
        if (!"https".equals(address.getScheme())) throw new IOException("HTTPS obrigatório");
        HttpURLConnection connection = (HttpURLConnection) address.toURL().openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (key != null) connection.setRequestProperty("Authorization", "Bearer " + key);
            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) {
                output.write(data);
            }
            int status = connection.getResponseCode();
            if (status != 200) return new Reply(status, null);
            try (var input = connection.getInputStream()) {
                var buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                for (int count; (count = input.read(chunk)) != -1;) {
                    if (buffer.size() + count > 8192) throw new IOException("Resposta extensa");
                    buffer.write(chunk, 0, count);
                }
                return new Reply(status, new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8)));
            }
        } finally {
            connection.disconnect();
        }
    }
}
