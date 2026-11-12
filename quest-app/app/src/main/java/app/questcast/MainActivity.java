package app.questcast;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

/** The panel shown in the headset: pick a server, start a cast, read out the code. */
public class MainActivity extends Activity implements CastService.Listener {
    private static final int REQUEST_CAPTURE = 1;
    private static final String PREF_SERVER = "server";
    private static final String PREF_HEIGHT = "height";

    private SharedPreferences prefs;
    private View idleGroup, liveGroup;
    private EditText serverField;
    private RadioGroup quality;
    private TextView codeText, whereText, viewersText, messageText;

    /** Set between tapping Start and the system consent dialog answering. */
    private String pendingServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getPreferences(MODE_PRIVATE);
        idleGroup = findViewById(R.id.idleGroup);
        liveGroup = findViewById(R.id.liveGroup);
        serverField = findViewById(R.id.server);
        quality = findViewById(R.id.quality);
        codeText = findViewById(R.id.code);
        whereText = findViewById(R.id.where);
        viewersText = findViewById(R.id.viewers);
        messageText = findViewById(R.id.message);

        serverField.setText(prefs.getString(PREF_SERVER, BuildConfig.DEFAULT_SERVER));
        quality.check(prefs.getInt(PREF_HEIGHT, 720) == 1080 ? R.id.q1080 : R.id.q720);

        findViewById(R.id.start).setOnClickListener(v -> onStartClicked());
        findViewById(R.id.stop).setOnClickListener(v ->
                startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP)));

        if (savedInstanceState != null) pendingServer = savedInstanceState.getString("pendingServer");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pendingServer", pendingServer);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CastService.setListener(this);
        onCastState(CastService.getState());
    }

    @Override
    protected void onStop() {
        CastService.setListener(null);
        super.onStop();
    }

    private void onStartClicked() {
        String server = ServerUrl.normalize(serverField.getText().toString());
        if (server == null) {
            messageText.setText("Enter the address of your cast server first.");
            return;
        }
        messageText.setText("");
        pendingServer = server;
        prefs.edit()
                .putString(PREF_SERVER, serverField.getText().toString().trim())
                .putInt(PREF_HEIGHT, selectedHeight())
                .apply();

        // The system asks the wearer to allow sharing what they see.
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private int selectedHeight() {
        return quality.getCheckedRadioButtonId() == R.id.q1080 ? 1080 : 720;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null || pendingServer == null) {
            messageText.setText("Casting needs permission to share the headset view.");
            return;
        }
        Intent start = new Intent(this, CastService.class)
                .setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_RESULT_DATA, data)
                .putExtra(CastService.EXTRA_SERVER, pendingServer)
                .putExtra(CastService.EXTRA_HEIGHT, selectedHeight());
        pendingServer = null;
        startForegroundService(start);
    }

    @Override
    public void onCastState(CastService.State state) {
        idleGroup.setVisibility(state.running ? View.GONE : View.VISIBLE);
        liveGroup.setVisibility(state.running ? View.VISIBLE : View.GONE);
        messageText.setText(state.message == null ? "" : state.message);
        if (!state.running) return;

        codeText.setText(state.code == null
                ? "··· ···"
                : state.code.substring(0, 3) + " " + state.code.substring(3));
        whereText.setText(state.server == null ? "" : "Enter it at " + ServerUrl.display(state.server));
        viewersText.setText(state.viewers == 0 ? "Waiting for viewers"
                : state.viewers == 1 ? "1 person watching"
                : state.viewers + " people watching");
    }
}
