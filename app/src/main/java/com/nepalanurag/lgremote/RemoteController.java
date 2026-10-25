package com.nepalanurag.lgremote;

/**
 * One remote implementation per active screen. The UI always talks to the controller selected by
 * {@link RemoteService#activeRemote()}; volume, mute and power stay TV-only by design.
 */
public interface RemoteController {
    interface Callback {
        void onResult(boolean ok, String error);
    }

    void sendNavigation(String name, Callback callback);

    default void sendNavigationLong(String name, Callback callback) {
        sendNavigation(name, callback);
    }

    void sendHome(Callback callback);

    void sendSettings(Callback callback);

    void sendText(String text, Callback callback);

    void sendDelete(Callback callback);

    void sendEnter(Callback callback);
}
