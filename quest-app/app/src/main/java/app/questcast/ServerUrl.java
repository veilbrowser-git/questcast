package app.questcast;

import okhttp3.HttpUrl;

/** Turns whatever was typed in the "Cast server" field into usable addresses. */
final class ServerUrl {
    private ServerUrl() {}

    /**
     * Returns the server's base address ("http://192.168.1.20:8787",
     * "https://cast.example.com"), or null if the input isn't an address.
     * With no scheme typed, home-network addresses get http and public
     * names get https.
     */
    static String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return null;
        s = s.replaceFirst("(?i)^ws(s?)://", "http$1://");
        if (!s.matches("(?i)^https?://.*")) {
            String host = s.split("[/:]", 2)[0];
            boolean local = host.matches("[0-9.]+")
                    || host.equalsIgnoreCase("localhost")
                    || host.toLowerCase().endsWith(".local")
                    || !host.contains(".");
            s = (local ? "http://" : "https://") + s;
        }
        HttpUrl url = HttpUrl.parse(s);
        if (url == null) return null;
        boolean defaultPort = url.port() == HttpUrl.defaultPort(url.scheme());
        return url.scheme() + "://" + url.host() + (defaultPort ? "" : ":" + url.port());
    }

    /** The WebSocket endpoint for a base address from {@link #normalize}. */
    static String socket(String base) {
        return base.replaceFirst("^http", "ws") + "/ws";
    }

    /** The address a viewer types into a browser, without the scheme. */
    static String display(String base) {
        return base.replaceFirst("^https?://", "");
    }
}
