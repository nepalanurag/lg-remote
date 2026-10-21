package com.nepalanurag.lgremote;

/** Routes remote actions to the LG TV over its SSAP connection. */
final class LgRemoteController implements RemoteController {
    private final RemoteService service;

    LgRemoteController(RemoteService service) {
        this.service = service;
    }

    @Override
    public void sendNavigation(String name, Callback callback) {
        LgTvClient client = service.getLgClient();
        if (client == null || !client.sendButton(name)) {
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
