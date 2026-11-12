package app.questcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Captures what the wearer sees and streams it to every viewer who entered
 * the session code. Runs as a foreground service so the cast keeps going
 * while the wearer is in a game or another app.
 *
 * Everything that touches the socket or a peer connection runs on one worker
 * thread ({@link #worker}), so no locking is needed.
 */
public class CastService extends Service {
    static final String ACTION_START = "app.questcast.action.START";
    static final String ACTION_STOP = "app.questcast.action.STOP";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String EXTRA_SERVER = "server";
    static final String EXTRA_HEIGHT = "height";

    private static final String TAG = "QuestCast";
    private static final String CHANNEL_ID = "cast";
    private static final int NOTIFICATION_ID = 1;
    private static final int FPS = 30;
    private static final int RETRY_SECONDS = 3;
    private static final int KEEPALIVE_SECONDS = 60;

    // ---------- State shared with the activity ----------

    static final class State {
        final boolean running;
        final String server;
        final String code;
        final int viewers;
        final String message;

        State(boolean running, String server, String code, int viewers, String message) {
            this.running = running;
            this.server = server;
            this.code = code;
            this.viewers = viewers;
            this.message = message;
        }
    }

    interface Listener {
        void onCastState(State state);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static State state = new State(false, null, null, 0, null);
    private static Listener listener;

    /** Main thread only. */
    static State getState() {
        return state;
    }

    /** Main thread only. */
    static void setListener(Listener l) {
        listener = l;
    }

    private static void publish(State s) {
        MAIN.post(() -> {
            state = s;
            if (listener != null) listener.onCastState(s);
        });
    }

    // ---------- Service ----------

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Peer> peers = new HashMap<>();

    private boolean started;   // main thread
    private boolean stopped;   // worker thread
    private String server;
    private String code;

    private OkHttpClient http;
    private WebSocket socket;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper surfaceHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            if (started) shutdown(null);
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;

        Intent permission = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        server = intent.getStringExtra(EXTRA_SERVER);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 720);
        int width = height * 16 / 9;

        try {
            // Must be in the foreground, as a media-projection service, before capture starts.
            startForeground(NOTIFICATION_ID, buildNotification(null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed", e);
            publish(new State(false, null, null, 0, "The headset did not allow casting to start."));
            stopSelf();
            return START_NOT_STICKY;
        }

        publish(new State(true, server, null, 0, "Connecting…"));
        run(() -> {
            try {
                startCapture(permission, width, height);
            } catch (Throwable t) {
                Log.e(TAG, "capture failed", t);
                shutdown("Could not capture the headset view.");
                return;
            }
            connect();
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown(null);
        worker.shutdown();
        super.onDestroy();
    }

    private void run(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The service is already gone.
        }
    }

    // ---------- Capture ----------

    private void startCapture(Intent permission, int width, int height) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(getApplicationContext())
                .createInitializationOptions());

        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, false))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        videoSource = factory.createVideoSource(/* isScreencast= */ true);
        surfaceHelper = SurfaceTextureHelper.create("QuestCastCapture", eglBase.getEglBaseContext());
        capturer = new ScreenCapturerAndroid(permission, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // The wearer revoked sharing, or another app took over screen capture.
                shutdown("Screen sharing was stopped.");
            }
        });
        capturer.initialize(surfaceHelper, getApplicationContext(), videoSource.getCapturerObserver());
        capturer.startCapture(width, height, FPS);

        videoTrack = factory.createVideoTrack("screen", videoSource);
    }

    // ---------- Signaling ----------

    private void connect() {
        if (stopped) return;
        if (http == null) {
            http = new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build();
        }
        Request request;
        try {
            request = new Request.Builder().url(ServerUrl.socket(server)).build();
        } catch (IllegalArgumentException e) {
            shutdown("That cast server address isn't valid.");
            return;
        }
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                ws.send("{\"type\":\"host\"}");
                scheduleKeepalive(ws);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                run(() -> onServerMessage(ws, text));
            }

            @Override
            public void onClosed(WebSocket ws, int closeCode, String reason) {
                run(() -> onSocketLost(ws));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.w(TAG, "socket failed: " + t);
                run(() -> onSocketLost(ws));
            }
        });
    }

    /** Regular traffic keeps a free hosted relay from going to sleep mid-cast. */
    private void scheduleKeepalive(WebSocket ws) {
        try {
            worker.schedule(() -> {
                if (stopped || ws != socket) return;
                ws.send("{\"type\":\"keepalive\"}");
                scheduleKeepalive(ws);
            }, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // The service is already gone.
        }
    }

    private void onSocketLost(WebSocket ws) {
        if (stopped || ws != socket) return;
        socket = null;
        code = null;
        closePeers();
        updateNotification();
        publish(new State(true, server, null, 0, "Can't reach the cast server. Retrying…"));
        try {
            worker.schedule(this::connect, RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // The service is already gone.
        }
    }

    private void onServerMessage(WebSocket ws, String text) {
        if (stopped || ws != socket) return;
        try {
            JSONObject msg = new JSONObject(text);
            switch (msg.optString("type")) {
                case "hosted":
                    code = msg.getString("code");
                    updateNotification();
                    publishLive();
                    break;
                case "viewer-joined":
                    addViewer(msg.getString("viewerId"));
                    break;
                case "viewer-left":
                    removeViewer(msg.getString("viewerId"));
                    break;
                case "signal":
                    onSignal(msg.getString("from"), msg.getJSONObject("data"));
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad message from server", e);
        }
    }

    private void sendSignal(String viewerId, JSONObject data) {
        WebSocket ws = socket;
        if (ws == null) return;
        try {
            ws.send(new JSONObject().put("type", "signal").put("to", viewerId).put("data", data).toString());
        } catch (JSONException ignored) {
        }
    }

    // ---------- Viewers ----------

    /** One connection to one viewer. */
    private final class Peer implements PeerConnection.Observer {
        final String id;
        final List<IceCandidate> pendingCandidates = new ArrayList<>();
        PeerConnection pc;
        RtpSender sender;
        boolean answered;
        boolean connected;

        Peer(String id) {
            this.id = id;
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            run(() -> {
                if (peers.get(id) != this) return;
                try {
                    sendSignal(id, new JSONObject().put("candidate", new JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)));
                } catch (JSONException ignored) {
                }
            });
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
            run(() -> {
                if (peers.get(id) != this) return;
                connected = newState == PeerConnection.PeerConnectionState.CONNECTED;
                if (newState == PeerConnection.PeerConnectionState.FAILED) removeViewer(id);
                else publishLive();
            });
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel channel) {}
        @Override public void onRenegotiationNeeded() {}
    }

    private void addViewer(String viewerId) {
        removeViewer(viewerId);

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.singletonList(
                PeerConnection.IceServer.builder(Arrays.asList(
                        "stun:stun.l.google.com:19302",
                        "stun:stun.cloudflare.com:3478")).createIceServer()));
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        Peer peer = new Peer(viewerId);
        peer.pc = factory.createPeerConnection(config, peer);
        if (peer.pc == null) return;
        peer.sender = peer.pc.addTrack(videoTrack, Collections.singletonList("cast"));
        peers.put(viewerId, peer);

        peer.pc.createOffer(new SdpCallback() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                run(() -> {
                    if (peers.get(viewerId) != peer) return;
                    peer.pc.setLocalDescription(new SdpCallback() {
                        @Override
                        public void onSetSuccess() {
                            run(() -> {
                                if (peers.get(viewerId) != peer) return;
                                try {
                                    sendSignal(viewerId, new JSONObject().put("sdp", new JSONObject()
                                            .put("type", "offer")
                                            .put("sdp", offer.description)));
                                } catch (JSONException ignored) {
                                }
                            });
                        }
                    }, offer);
                });
            }
        }, new MediaConstraints());
    }

    private void onSignal(String viewerId, JSONObject data) throws JSONException {
        Peer peer = peers.get(viewerId);
        if (peer == null) return;

        if (data.has("sdp")) {
            JSONObject sdp = data.getJSONObject("sdp");
            if (!"answer".equals(sdp.optString("type"))) return;
            peer.pc.setRemoteDescription(new SdpCallback() {
                @Override
                public void onSetSuccess() {
                    run(() -> {
                        if (peers.get(viewerId) != peer) return;
                        peer.answered = true;
                        for (IceCandidate c : peer.pendingCandidates) peer.pc.addIceCandidate(c);
                        peer.pendingCandidates.clear();
                        tuneForScreenContent(peer.sender);
                    });
                }
            }, new SessionDescription(SessionDescription.Type.ANSWER, sdp.getString("sdp")));
        } else if (data.has("candidate")) {
            JSONObject c = data.getJSONObject("candidate");
            IceCandidate candidate = new IceCandidate(
                    c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.getString("candidate"));
            if (peer.answered) peer.pc.addIceCandidate(candidate);
            else peer.pendingCandidates.add(candidate);
        }
    }

    /** Keep the picture sharp enough to zoom into: allow a high bitrate and drop frames before pixels. */
    private static void tuneForScreenContent(RtpSender sender) {
        try {
            RtpParameters params = sender.getParameters();
            for (RtpParameters.Encoding encoding : params.encodings) {
                encoding.maxBitrateBps = 8_000_000;
                encoding.maxFramerate = FPS;
            }
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
            sender.setParameters(params);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not tune sender", e);
        }
    }

    private void removeViewer(String viewerId) {
        Peer peer = peers.remove(viewerId);
        if (peer == null) return;
        peer.pc.dispose();
        publishLive();
    }

    private void closePeers() {
        for (Peer peer : peers.values()) peer.pc.dispose();
        peers.clear();
    }

    private void publishLive() {
        if (stopped) return;
        int watching = 0;
        for (Peer peer : peers.values()) if (peer.connected) watching++;
        publish(new State(true, server, code, watching, null));
    }

    // ---------- Shutdown ----------

    /** Safe to call from any thread, any number of times. */
    private void shutdown(String message) {
        run(() -> {
            if (stopped) return;
            stopped = true;

            if (socket != null) {
                // Say goodbye first so viewers see "Cast ended" at once.
                socket.send("{\"type\":\"leave\"}");
                socket.close(1000, null);
            }
            socket = null;
            closePeers();
            try {
                if (capturer != null) {
                    capturer.stopCapture();
                    capturer.dispose();
                }
                if (videoTrack != null) videoTrack.dispose();
                if (videoSource != null) videoSource.dispose();
                if (surfaceHelper != null) surfaceHelper.dispose();
                if (factory != null) factory.dispose();
                if (eglBase != null) eglBase.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "cleanup problem", e);
            }
            if (http != null) http.dispatcher().executorService().shutdown();

            publish(new State(false, null, null, 0, message));
            MAIN.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        });
    }

    // ---------- Notification ----------

    private Notification buildNotification(String sessionCode) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle("Casting this headset")
                .setContentText(sessionCode == null
                        ? "Connecting…"
                        : "Session code " + sessionCode.substring(0, 3) + " " + sessionCode.substring(3))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(code));
    }

    // ---------- Helpers ----------

    /** SdpObserver with empty defaults, so callers override only what they need. */
    private abstract static class SdpCallback implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String error) {
            Log.w(TAG, "SDP create failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, "SDP set failed: " + error);
        }
    }
}
