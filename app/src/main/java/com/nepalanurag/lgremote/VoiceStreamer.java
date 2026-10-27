package com.nepalanurag.lgremote;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures 16-bit PCM mono audio from the phone's microphone and streams it to the
 * Android TV box over the active Google TV Remote v2 voice protocol session.
 */
final class VoiceStreamer {
    private static final String TAG = "LgRemote";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHUNK_SIZE = 8192; // 8192 bytes = 512ms of 16-bit mono 8 kHz audio

    public interface Listener {
        void onStarted();
        void onStopped();
        void onError(String message);
    }

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<byte[]> pendingChunks = new ConcurrentLinkedQueue<>();
    private volatile int currentSessionId = -1;
    private volatile Listener activeListener;
    private Thread recordThread;
    private AudioRecord audioRecord;

    /** Starts voice streaming initiated by the phone (sends KEYCODE_SEARCH). */
    void start(Listener listener) {
        if (!setupAudio(listener)) {
            return;
        }
        currentSessionId = -1;
        activeListener = listener;

        AndroidTvRemote.startVoice(new AndroidTvRemote.VoiceCallback() {
            @Override
            public void onVoiceReady(int sessionId) {
                currentSessionId = sessionId;
                Log.d(TAG, "VoiceStreamer: TV voice ready session=" + sessionId);
                flushPending();
                if (listener != null) {
                    listener.onStarted();
                }
            }

            @Override
            public void onVoiceEnded(int sessionId) {
                Log.d(TAG, "VoiceStreamer: TV ended session=" + sessionId);
                stop();
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "VoiceStreamer: TV voice error: " + error);
                stop();
                if (listener != null) {
                    listener.onError(error);
                }
            }
        });

        startRecordingThread();
    }

    /** Starts voice streaming for a session initiated by the TV box (e.g. Nuvio speech search). */
    void startForIncomingSession(int sessionId, Listener listener) {
        if (recording.get() && currentSessionId == sessionId) {
            return;
        }
        stop();
        if (!setupAudio(listener)) {
            return;
        }
        currentSessionId = sessionId;
        activeListener = listener;
        Log.d(TAG, "VoiceStreamer: started for incoming TV voice session=" + sessionId);
        if (listener != null) {
            listener.onStarted();
        }
        startRecordingThread();
    }

    @SuppressLint("MissingPermission")
    private boolean setupAudio(Listener listener) {
        if (recording.get()) {
            return false;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            if (listener != null) {
                listener.onError("Audio recording is not supported on this device.");
            }
            return false;
        }
        int bufSize = Math.max(minBuf * 2, CHUNK_SIZE * 4);

        // Try VOICE_RECOGNITION first, fallback to MIC
        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Exception ignored) {
        }
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            if (record != null) {
                record.release();
            }
            try {
                record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError("Failed to initialize microphone: " + e.getMessage());
                }
                return false;
            }
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) {
                listener.onError("Microphone could not be initialized.");
            }
            record.release();
            return false;
        }

        audioRecord = record;
        recording.set(true);
        pendingChunks.clear();

        try {
            audioRecord.startRecording();
            Log.d(TAG, "VoiceStreamer: AudioRecord recording started, state=" + audioRecord.getRecordingState());
        } catch (Exception e) {
            recording.set(false);
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
            if (listener != null) {
                listener.onError("Could not start microphone recording.");
            }
            return false;
        }
        return true;
    }

    private void flushPending() {
        while (!pendingChunks.isEmpty()) {
            byte[] chunk = pendingChunks.poll();
            if (chunk != null && currentSessionId >= 0) {
                AndroidTvRemote.sendVoiceChunk(currentSessionId, chunk);
            }
        }
    }

    private void startRecordingThread() {
        recordThread = new Thread(() -> {
            byte[] buffer = new byte[CHUNK_SIZE];
            boolean speechStarted = false;
            int silenceChunks = 0;
            int totalChunks = 0;
            final int maxChunks = 32; // ~16 seconds max recording
            final int silenceChunkLimit = 12; // ~6s silence after speech

            while (recording.get()) {
                AudioRecord rec = audioRecord;
                if (rec == null) {
                    break;
                }
                int read = 0;
                while (read < CHUNK_SIZE && recording.get()) {
                    int r = rec.read(buffer, read, CHUNK_SIZE - read);
                    if (r <= 0) {
                        break;
                    }
                    read += r;
                }

                if (read > 0) {
                    byte[] toSend = new byte[read];
                    System.arraycopy(buffer, 0, toSend, 0, read);

                    // Compute RMS to track speech and background noise
                    long sum = 0;
                    for (int i = 0; i < read - 1; i += 2) {
                        short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                        sum += (long) sample * sample;
                    }
                    int samples = read / 2;
                    double rms = samples > 0 ? Math.sqrt((double) sum / samples) : 0;

                    int sid = currentSessionId;
                    if (sid >= 0) {
                        AndroidTvRemote.sendVoiceChunk(sid, toSend);
                        Log.d(TAG, "VoiceStreamer: chunk sid=" + sid + " len=" + read + " rms=" + (int) rms);
                    } else {
                        if (pendingChunks.size() < 20) {
                            pendingChunks.add(toSend);
                        }
                    }

                    totalChunks++;
                    if (rms > 120) {
                        speechStarted = true;
                        silenceChunks = 0;
                    } else if (speechStarted) {
                        silenceChunks++;
                        if (silenceChunks >= silenceChunkLimit) {
                            Log.d(TAG, "VoiceStreamer: silence detected after speech, ending session");
                            break;
                        }
                    }

                    if (totalChunks >= maxChunks) {
                        Log.d(TAG, "VoiceStreamer: maximum voice duration reached, ending session");
                        break;
                    }
                } else if (read < 0) {
                    Log.w(TAG, "VoiceStreamer: AudioRecord read error=" + read);
                    break;
                }
            }
            stop();
        }, "voice-streamer");
        recordThread.setDaemon(true);
        recordThread.start();
    }

    void stop() {
        if (!recording.getAndSet(false)) {
            return;
        }
        int sid = currentSessionId;
        currentSessionId = -1;
        if (sid >= 0) {
            AndroidTvRemote.endVoice(sid);
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        if (recordThread != null) {
            recordThread.interrupt();
            recordThread = null;
        }
        Listener l = activeListener;
        activeListener = null;
        if (l != null) {
            l.onStopped();
        }
    }

    boolean isRecording() {
        return recording.get();
    }
}
