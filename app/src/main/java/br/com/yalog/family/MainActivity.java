package br.com.yalog.family;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 1;
    private TextView usage;
    private TextView location;
    private CancellationSignal locationRequest;
    private JSONObject latestLocation;
    private JSONObject latestUsage;
    private TextView linkStatus;
    private SharedPreferences link;
    private boolean syncing;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 22, 43));
        getWindow().setNavigationBarColor(Color.rgb(15, 22, 43));
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.rgb(15, 22, 43));
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);

        addText(content, "YALog Family", 28);
        addText(content, "Aparelho do dependente · Piloto Android", 18);
        addText(content, "Você pode compartilhar um resumo manualmente ou ativar o envio automático com aviso na tela.", 16);
        addButton(content, "Configurar acesso ao tempo de uso", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (ActivityNotFoundException e) {
                usage.setText("Este aparelho não oferece uma tela de acesso ao uso.");
            }
        });
        addButton(content, "Atualizar resumo de uso", this::refreshUsage);
        usage = addText(content, "", 16);
        addButton(content, "Consultar localização agora", this::requestLocation);
        location = addText(content, "Localização sob demanda, com o app aberto.", 16);
        link = getSharedPreferences("family_link", MODE_PRIVATE);
        addButton(content, "Vincular ao responsável", this::showPairing);
        addButton(content, "Compartilhar resumo", this::sendReport);
        addButton(content, "Ativar envio automático", this::confirmAutomatic);
        addButton(content, "Pausar envio automático", () -> {
            stopService(new Intent(this, SharingService.class));
            linkStatus.setText("Envio automático pausado. O painel mantém o último resumo.");
        });
        addButton(content, "Configurar limites de aplicativos", () ->
                new AlertDialog.Builder(this).setTitle("Limites de aplicativos")
                        .setMessage("O recurso de acessibilidade identifica qual aplicativo abriu para aplicar as regras do responsável. O YALog Family não lê a tela. Você pode desativar nas configurações.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Abrir configurações", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show());
        addButton(content, "Desvincular aparelho", this::disconnect);
        linkStatus = addText(content, link.contains("token")
                ? "Vínculo ativo neste aparelho." : "Aparelho sem vínculo.", 16);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUsage();
    }

    @Override
    protected void onStop() {
        if (locationRequest != null) {
            locationRequest.cancel();
            locationRequest = null;
            location.setText("Consulta encerrada. Toque para consultar novamente.");
        }
        super.onStop();
    }

    private void refreshUsage() {
        latestUsage = DeviceUsage.usage(this);
        if (latestUsage == null) {
            usage.setText("Tempo de uso aguardando autorização nas configurações do Android.");
            return;
        }
        JSONArray apps = latestUsage.optJSONArray("apps");
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            long seconds = app.optLong("seconds");
            summary.append(app.optString("label")).append(" · ")
                    .append(seconds / 60).append(" min ").append(seconds % 60).append(" s\n");
        }
        usage.setText(summary.length() == 0 ? "Nenhum registro de uso disponível."
                : "Estimativa desde o início do dia\n" + summary.toString().trim());
    }

    private void requestLocation() {
        latestLocation = null;
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        readLocation();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                readLocation();
            } else {
                location.setText("Localização aguardando autorização.");
            }
        }
    }

    private void readLocation() {
        LocationManager manager = getSystemService(LocationManager.class);
        boolean precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (locationRequest != null) {
            locationRequest.cancel();
            locationRequest = null;
        }
        String provider = LocationManager.NETWORK_PROVIDER;
        if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (!manager.isProviderEnabled(provider)) {
            location.setText("Ative a localização do aparelho ou libere a localização precisa.");
            return;
        }
        CancellationSignal request = new CancellationSignal();
        locationRequest = request;
        location.setText("Consultando localização…");
        try {
            manager.getCurrentLocation(provider, request, getMainExecutor(), point -> {
                if (locationRequest != request) return;
                locationRequest = null;
                if (point == null) {
                    location.setText("Localização indisponível. Tente novamente.");
                    return;
                }
                latestLocation = object("lat", point.getLatitude(), "lon", point.getLongitude(),
                        "accuracy", point.getAccuracy(), "at", point.getTime());
                String time = DateFormat.getDateTimeInstance().format(new Date(point.getTime()));
                location.setText(String.format(Locale.getDefault(),
                        "Latitude %.5f · Longitude %.5f\nPrecisão estimada %.0f m · %s",
                        point.getLatitude(), point.getLongitude(), point.getAccuracy(), time));
            });
        } catch (SecurityException | IllegalArgumentException e) {
            locationRequest = null;
            location.setText("Localização indisponível. Confira permissões e configurações.");
        }
    }

    private void showPairing() {
        if (syncing) return;
        if (link.contains("token")) {
            linkStatus.setText("Desvincule o aparelho antes de usar outro convite.");
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText server = new EditText(this);
        server.setHint("https://servidor");
        server.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = new EditText(this);
        code.setHint("Código temporário");
        code.setSingleLine(true);
        fields.addView(server);
        fields.addView(code);
        new AlertDialog.Builder(this).setTitle("Vincular ao responsável")
                .setMessage("O responsável poderá consultar a localização e o tempo de uso que você enviar.")
                .setView(fields).setNegativeButton("Cancelar", null)
                .setPositiveButton("Vincular", (dialog, which) -> {
                    String origin = server.getText().toString().trim();
                    String invite = code.getText().toString().trim();
                    try {
                        URI uri = URI.create(origin);
                        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                                || !invite.matches("[A-Za-z0-9_-]{22}")) throw new IllegalArgumentException();
                        String base = "https://" + uri.getRawAuthority();
                        runRequest(base, "pair", null, object("code", invite), result -> {
                            String key = result.optString("token");
                            if (!key.matches("[A-Za-z0-9_-]{43}")) {
                                linkStatus.setText("O servidor retornou um vínculo inválido.");
                                return;
                            }
                            link.edit().putString("base", base).putString("token", key).apply();
                            linkStatus.setText("Vinculado a " + result.optString("label", "responsável") + ".");
                        });
                    } catch (IllegalArgumentException e) {
                        linkStatus.setText("Informe o endereço HTTPS e o código exibidos no painel.");
                    }
                }).show();
    }

    private void sendReport() {
        String key = link.getString("token", null);
        if (key == null) {
            linkStatus.setText("Vincule o aparelho antes de compartilhar.");
            return;
        }
        refreshUsage();
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            latestLocation = null;
        }
        JSONObject payload = object("location", latestLocation, "usage", latestUsage);
        runRequest(link.getString("base", ""), "report", key, payload,
                result -> {
                    if (key.equals(link.getString("token", ""))) AppLimitsService.receive(link, result);
                    linkStatus.setText("Resumo enviado ao responsável e regras atualizadas.");
                });
    }

    private void disconnect() {
        String key = link.getString("token", null);
        if (key == null || syncing) return;
        new AlertDialog.Builder(this).setTitle("Desvincular aparelho")
                .setMessage("O último resumo será removido do painel do responsável.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desvincular", (dialog, which) ->
                        runRequest(link.getString("base", ""), "disconnect", key, object(),
                                result -> clearLink())).show();
    }

    private void clearLink() {
        stopService(new Intent(this, SharingService.class));
        link.edit().clear().apply();
        latestLocation = null;
        location.setText("Localização sob demanda, com o app aberto.");
        linkStatus.setText("Aparelho sem vínculo.");
    }

    private void runRequest(String base, String endpoint, String key, JSONObject payload,
            java.util.function.Consumer<JSONObject> success) {
        if (syncing) return;
        syncing = true;
        linkStatus.setText("Comunicando com o servidor…");
        new Thread(() -> {
            try {
                var reply = ReportApi.post(base, endpoint, key, payload);
                if (reply.status() == 401 && key != null) {
                    finishRequest(this::clearLink);
                } else if (reply.status() == 404 && "pair".equals(endpoint)) {
                    finishRequest(() -> linkStatus.setText("Convite expirado ou já usado."));
                } else if (reply.status() != 200) {
                    finishRequest(() -> linkStatus.setText("Servidor recusou a operação (" + reply.status() + ")."));
                } else {
                    finishRequest(() -> success.accept(reply.data()));
                }
            } catch (IOException | JSONException | IllegalArgumentException e) {
                finishRequest(() -> linkStatus.setText("Falha na conexão. Confira o endereço e tente novamente."));
            }
        }).start();
    }

    private void confirmAutomatic() {
        if (!link.contains("token")) {
            linkStatus.setText("Vincule o aparelho antes de ativar o envio.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            linkStatus.setText("Autorize a localização usando Consultar localização agora.");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
            linkStatus.setText("Autorize os avisos e toque novamente em Ativar envio automático.");
            return;
        }
        if (!getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            linkStatus.setText("Ative as notificações do YALog Family antes de compartilhar.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Ativar envio automático")
                .setMessage("Envia localização e tempo de uso autorizado ao responsável. Um aviso ficará visível e você poderá pausar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ativar", (dialog, which) -> {
                    try {
                        startForegroundService(new Intent(this, SharingService.class));
                        linkStatus.setText("Ativando envio automático. Confira o aviso do Android.");
                    } catch (SecurityException | IllegalStateException e) {
                        linkStatus.setText("O Android recusou a ativação. Confira as permissões.");
                    }
                }).show();
    }

    private void finishRequest(Runnable update) {
        runOnUiThread(() -> {
            syncing = false;
            if (!isFinishing() && !isDestroyed()) update.run();
        });
    }

    static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) {
                result.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return result;
    }

    private TextView addText(LinearLayout content, String value, int size) {
        TextView view = new TextView(this);
        view.setTextColor(Color.rgb(223, 241, 249));
        view.setTextSize(size);
        view.setText(value);
        content.addView(view);
        return view;
    }

    private void addButton(LinearLayout content, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        content.addView(button);
    }
}
