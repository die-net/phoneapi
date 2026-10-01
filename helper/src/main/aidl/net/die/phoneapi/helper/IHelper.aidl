package net.die.phoneapi.helper;

import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import net.die.phoneapi.helper.ShellResult;
import net.die.phoneapi.helper.TouchscreenInfo;

// Implemented by the shell-UID helper process. Every method verifies that the caller is the
// PhoneAPI app's UID before doing anything.
interface IHelper {
    int protocolVersion() = 1;

    int pid() = 2;

    // Injects through InputManager. mode is an InputManager.INJECT_INPUT_EVENT_MODE_* value.
    boolean injectMotionEvent(in MotionEvent event, int mode) = 10;

    boolean injectKeyEvent(in KeyEvent event, int mode) = 11;

    // The primary touchscreen. deviceId 0 means the phone has none.
    TouchscreenInfo touchscreenInfo() = 12;

    // Connects to an abstract-namespace unix socket (e.g. "chrome_devtools_remote") as the shell
    // UID and returns the connected socket's fd.
    ParcelFileDescriptor openAbstractSocket(String name) = 20;

    // JSON array of abstract socket names matching devtools patterns, with owning pid/uid where
    // known: [{"name": "...", "pid": 123, "package": "..."}].
    String listDevtoolsSockets() = 21;

    // Runs a command as the shell UID. Output is truncated to maxOutputBytes each.
    ShellResult exec(in String[] argv, long timeoutMs, int maxOutputBytes) = 30;

    // Starts `logcat` with the given args and returns the read end of its stdout.
    ParcelFileDescriptor logcat(in String[] args) = 31;

    // Returns the read end of a pipe that will contain a PNG screenshot.
    ParcelFileDescriptor screencap() = 32;

    // Mirrors the given display onto the provided Surface (e.g. a MediaCodec input surface).
    boolean startMirror(in Surface surface, int width, int height, int displayId) = 40;

    void stopMirror() = 41;

    // Captures all device audio (REMOTE_SUBMIX, Android 11+) and returns a pipe of PCM s16le.
    ParcelFileDescriptor startAudioCapture(int sampleRate, int channels) = 42;

    void stopAudioCapture() = 43;

    oneway void shutdown() = 99;
}
