package com.nepalanurag.lgremote;

/** Routes remote actions to the LG TV over its SSAP connection. */
final class LgRemoteController implements RemoteController {
    private final RemoteService service;
    private boolean isPlaying = true;

    LgRemoteController(RemoteService service) {
        this.service = service;
    }

    @Override
    public void sendNavigation(String name, Callback callback) {
        LgTvClient client = service.getLgClient();
        if (client == null) {
            callback.onResult(false, "The TV is not ready.");
            return;
        }
        if ("PLAY".equalsIgnoreCase(name) || "PLAY_PAUSE".equalsIgnoreCase(name) || "PLAYPAUSE".equalsIgnoreCase(name)) {
            String fg = service.getForegroundApp();
            if (fg != null && fg.toLowerCase().contains("youtube")) {
                client.sendButton("ENTER");
                client.sendButton("PLAY");
                client.playMedia(callback);
                return;
            }
            if (isPlaying) {
                client.pauseMedia((ok, err) -> {
                    isPlaying = false;
                    if (callback != null) callback.onResult(ok, err);
                });
            } else {
                client.playMedia((ok, err) -> {
                    isPlaying = true;
                    if (callback != null) callback.onResult(ok, err);
                });
            }
            return;
        }
        if ("PAUSE".equalsIgnoreCase(name)) {
            client.pauseMedia(callback);
            return;
        }
        if ("REWIND".equalsIgnoreCase(name)) {
            client.rewindMedia(callback);
            return;
        }
        if ("FASTFORWARD".equalsIgnoreCase(name)) {
            client.fastForwardMedia(callback);
            return;
        }
        if (!client.sendButton(name)) {
            callback.onResult(false, "The TV is not ready.");
            return;
        }
        callback.onResult(true, null);
    }

    @Override
    public void sendHome(Callback callback) {
        sendNavigation("HOME", callback);
    }

    @Override
    public void sendSettings(Callback callback) {
        LgTvClient client = service.getLgClient();
        if (client == null) {
            callback.onResult(false, "The TV is not ready.");
            return;
        }
        client.launchSettings();
        callback.onResult(true, null);
    }

    @Override
    public void sendText(String text, Callback callback) {
        LgTvClient client = service.getLgClient();
        if (client == null) {
            callback.onResult(false, "The TV is not ready.");
            return;
        }
        if (!client.sendText(text, callback::onResult)) {
            callback.onResult(false, "Tap a text field on the TV first.");
        }
    }

    @Override
    public void sendDelete(Callback callback) {
        LgTvClient client = service.getLgClient();
        if (client == null) {
            callback.onResult(false, "The TV is not ready.");
            return;
        }
        if (!client.deleteCharacters(1, callback::onResult)) {
            callback.onResult(false, "Tap a text field on the TV first.");
        }
    }

    @Override
    public void sendEnter(Callback callback) {
        LgTvClient client = service.getLgClient();
        if (client == null) {
            callback.onResult(false, "The TV is not ready.");
            return;
        }
        if (!client.sendEnterKey(callback::onResult)) {
            callback.onResult(false, "Tap a text field on the TV first.");
        }
    }
}
