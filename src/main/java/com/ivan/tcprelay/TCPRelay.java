package com.ivan.tcprelay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class TCPRelay {
    private static int serverPort;

    private static String targetHost;
    private static int targetPort;
    static URL rtmpUrl;

    static int socketBufferSize;

    static boolean running;

    static boolean debug;

    public static final void main(final String[] args) throws Exception {
        System.out.println("TCPRelay v0.2 beta");
        System.out.println("  by Ivan \"StrikerX3\" Oliveira");
        if (args.length == 0) {
            System.out.println("Type \"tcprelay -?\" for instructions.");
        }
        System.out.println();

        // initialize configuration
        socketBufferSize = 64 * 1024;

        serverPort = 1935;

        targetHost = "live.twitch.tv";
        targetPort = 1935;

        // parse parameters from command line
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];

            // first, try parsing the parameter as an URL
            try {
                final URL url = new URL(arg);
                rtmpUrl = url;
                continue;
            } catch (final MalformedURLException e) {
                // ignore; don't even log this error.
            }

            // if this did not work, try the other parameters
            if ("-h".equalsIgnoreCase(arg) || "-help".equalsIgnoreCase(arg) || "-?".equalsIgnoreCase(arg)) {
                // help
                printHelpAndExit();
            } else if (arg.startsWith("-twitch.tv") || arg.startsWith("-ttv")) {
                // get twitch.tv ingest server list from http://api.twitch.tv/api/ingest/xsplit.xml
                listTwitchTvServersAndExit();
            } else if (arg.startsWith("-p:") || arg.startsWith("-rp:") || arg.startsWith("-port:") || arg.startsWith("-relay-port:")) {
                // set relay port
                serverPort = toInt(arg);
            } else if (arg.startsWith("-th:") || arg.startsWith("-target-host:")) {
                // set target host
                targetHost = toString(arg);
            } else if (arg.startsWith("-tp:") || arg.startsWith("-target-port:")) {
                // set target port
                targetPort = toInt(arg);
            } else if (arg.startsWith("-sbs:") || arg.startsWith("-socket-buffer-size:")) {
                // set socket buffer size (for debugging/tweaking purposes)
                socketBufferSize = toInt(arg) * 1024;
                System.out.println("Socket buffer size set to " + socketBufferSize / 1024 + " KB");
            } else if ("-debug".equalsIgnoreCase(arg)) {
                // set debug flag
                debug = true;
                System.out.println("Running in debug mode");
            } else {
                System.out.println("Invalid parameter: " + arg);
                System.out.println();
                printHelpAndExit();
            }
        }

        final String localHostName = InetAddress.getLocalHost().getHostName();
        try {
            final boolean isLoopback = InetAddress.getByName(targetHost).isLoopbackAddress();
            final boolean isLocalhost = targetHost.equalsIgnoreCase(localHostName);
            if ((isLoopback || isLocalhost) && targetPort == serverPort) {
                System.out.println("Invalid relay target: " + targetHost + ":" + targetPort);
                System.out.println("You should not relay data to the relay server itself!");
                System.exit(0);
            }
        } catch (final UnknownHostException e) {
            System.out.println("Unknown host: " + targetHost);
            debugTrace(e);
            System.exit(0);
        }

        try {
            final ServerSocket ss = new ServerSocket(serverPort);
            if (rtmpUrl != null) {
                targetHost = rtmpUrl.getHost();
                targetPort = rtmpUrl.getPort() == -1 ? rtmpUrl.getDefaultPort() : rtmpUrl.getPort();
            }
            System.out.println("Server up at " + localHostName + ":" + serverPort);
            System.out.println("Relaying to " + targetHost + ":" + targetPort);

            if (rtmpUrl != null) {
                final URL rtmp = new URL("rtmp", localHostName, serverPort == 1935 ? -1 : serverPort, rtmpUrl.getPath());
                System.out.println();
                System.out.println("RTMP URL provided: " + rtmpUrl);
                System.out.println("Use the following RTMP URL in XSplit: " + rtmp);
            }

            running = true;
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public synchronized void start() {
                    System.out.println("Shutting down server...");
                    running = false;
                    try {
                        ss.close();
                    } catch (final IOException e) {
                        debugTrace(e);
                    }
                }
            });

            // main server loop, will listen for all connections
            while (running) {
                Socket sSrc = null;
                try {
                    sSrc = ss.accept();

                    System.out.println("Connection established with " + sSrc.getRemoteSocketAddress());
                    final Socket sTarget = new Socket(targetHost, targetPort);

                    final Thread t1 = new Thread(new Pipe(sSrc, sTarget));
                    final Thread t2 = new Thread(new Pipe(sTarget, sSrc));

                    t1.start();
                    t2.start();
                } catch (final ConnectException e) {
                    System.out.println("Could not connect to " + targetHost + ":" + targetPort);
                    if (sSrc != null) {
                        sSrc.close();
                        System.out.println("Connection to " + sSrc.getRemoteSocketAddress() + " closed");
                    }
                    debugTrace(e);
                } catch (final SocketException e) {
                    System.out.println("Listener stopped.");
                    debugTrace(e);
                    break;
                }
            }
        } catch (final BindException e) {
            System.out.println("Port " + serverPort + " already in use. Check if TCPRelay is already running.");
        }
    }

    static void debugTrace(final Throwable e) {
        if (debug) {
            e.printStackTrace();
        }
    }

    private static String toString(final String arg) {
        return arg.substring(arg.indexOf(':') + 1);
    }

    private static void printHelpAndExit() {
        System.out.println("Syntax: tcprelay [-h | -help | -?]");
        System.out.println("                 [-twitch.tv | -ttv]");
        System.out.println("                 [-p:<port> | -rp:<port> | -port:<port> | -relay-port:<port>]");
        System.out.println("                 [-th:<host> | -target-host:<host>]");
        System.out.println("                 [-tp:<port> | -target-port:<port>]");
        System.out.println("                 [-debug]");
        System.out.println("                 [<url>]");
        System.out.println();
        System.out.println("  -h | -help | -?");
        System.out.println("      Prints this help");
        System.out.println("  -twitch.tv | -ttv");
        System.out.println("      Lists all available Twitch.tv ingest servers");
        System.out.println("  -p:<port> | -rp:<port> | -port:<port> | -relay-port:<port>");
        System.out.println("      Specifies the port the relay will listen to (default: 1935)");
        System.out.println("  -th:<host> | -target-host:<host>");
        System.out.println("      Specifies the target host address (default: live.twitch.tv)");
        System.out.println("  -tp:<port> | -target-port:<port>");
        System.out.println("      Specifies the target port (default: 1935)");
        System.out.println("  -debug");
        System.out.println("      Enters debug mode, which enables additional logging and tracing");
        System.out.println("      (use this if you're having issues with TCPRelay)");
        System.out.println("  <url>");
        System.out.println("      Extracts the target address from an URL (e.g. rtmp://live.twitch.tv/app)");
        System.out.println("      The target host:port is the address where all data will be relayed to.");
        System.out.println("      This will override both -th and -tp");
        System.out.println();
        System.out.println("Usage examples:");
        System.out.println("  tcprelay");
        System.out.println("    Starts a relay server listening to port 1935 targeting live.twitch.tv");
        System.out.println();
        System.out.println("  tcprelay -target-host:live-jfk.twitch.tv -target-port:1935");
        System.out.println("    Starts a relay server listening to port 1935 targeting the New York");
        System.out.println("    Twitch.tv server");
        System.out.println();
        System.out.println("  tcprelay rtmp://live.use.own3d.tv/live");
        System.out.println("    Starts a relay server listening to port 1935 targeting the own3d.tv server");

        System.exit(0);
    }

    private static void listTwitchTvServersAndExit() {
        System.out.println("Listing Twitch.tv ingest servers...");
        System.out.println();

        try {
            final URL url = new URL("http://api.twitch.tv/api/ingest/xsplit.xml");

            final URLConnection conn = url.openConnection();
            conn.connect();

            final InputStream input = conn.getInputStream();
            final byte[] b = new byte[4096];
            int len;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while ((len = input.read(b)) > -1) {
                buf.write(b, 0, len);
            }

            input.close();

            final List<IngestServerData> servers = new ArrayList<IngestServerData>();
            int maxNameLen = 0;

            final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            final DocumentBuilder db = dbf.newDocumentBuilder();
            final Document doc = db.parse(new ByteArrayInputStream(buf.toByteArray()));
            final NodeList list = doc.getElementsByTagName("location");
            for (int i = 0; i < list.getLength(); i++) {
                final Node node = list.item(i);
                final NamedNodeMap attrs = node.getAttributes();
                final String serverName = attrs.getNamedItem("name").getNodeValue();
                final String serverURL = attrs.getNamedItem("url").getNodeValue();

                maxNameLen = Math.max(maxNameLen, serverName.length());
                servers.add(new IngestServerData(serverName, serverURL));
            }
            final NodeList defaultList = doc.getElementsByTagName("default");
            for (int i = 0; i < defaultList.getLength(); i++) {
                final Node node = defaultList.item(i);
                final NamedNodeMap attrs = node.getAttributes();
                final String serverName = attrs.getNamedItem("name").getNodeValue() + " (default)";
                final String serverURL = attrs.getNamedItem("url").getNodeValue();

                maxNameLen = Math.max(maxNameLen, serverName.length());
                servers.add(new IngestServerData(serverName, serverURL));
            }

            Collections.sort(servers);
            for (final IngestServerData server : servers) {
                System.out.println(pad(server.getName(), maxNameLen) + " | " + server.getUrl());
            }
        } catch (final UnknownHostException e) {
            System.out.println("Unknown host: api.twitch.tv");
            debugTrace(e);
        } catch (final ConnectException e) {
            System.out.println("Could not connect to http://api.twitch.tv/api/ingest/xsplit.xml");
            debugTrace(e);
        } catch (final Exception e) {
            System.err.println("Something went awry while trying to retrieve Twitch.tv ingest server list!");
            e.printStackTrace();
        }

        System.exit(0);
    }

    private static String pad(final String text, final int length) {
        final StringBuilder sb = new StringBuilder(text);
        for (int i = text.length(); i < length; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static int toInt(final String arg) {
        try {
            final String str = toString(arg);
            return Integer.parseInt(str);
        } catch (final NumberFormatException e) {
            System.out.println("Invalid parameter: " + arg);
            debugTrace(e);
            System.exit(0);
        }
        return 0;
    }

    private static class IngestServerData implements Comparable<IngestServerData> {
        private final String name;
        private final String url;

        public IngestServerData(final String name, final String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public int compareTo(final IngestServerData o) {
            return name.compareTo(o.name);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (name == null ? 0 : name.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final IngestServerData other = (IngestServerData) obj;
            if (name == null) {
                if (other.name != null) {
                    return false;
                }
            } else if (!name.equals(other.name)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return getName() + " -- " + getUrl();
        }
    }

    private static class Pipe implements Runnable {
        private final Socket in;
        private final Socket out;

        public Pipe(final Socket in, final Socket out) {
            this.in = in;
            this.out = out;
        }

        private static void print(final String text) {
            System.out.println("[" + Thread.currentThread().getName() + "] " + text);
        }

        public void run() {
            try {
                print("Piping bytes from " + in.getInetAddress() + " to " + out.getInetAddress());
                final byte[] b = new byte[socketBufferSize];
                int len;
                for (;;) {
                    len = in.getInputStream().read(b);
                    if (len == -1) {
                        if (!in.isClosed()) {
                            in.close();
                        }
                        if (!out.isClosed()) {
                            out.close();
                        }
                        break;
                    }
                    //print("Read " + len + " bytes");
                    out.getOutputStream().write(b, 0, len);
                }
            } catch (final SocketException e) {
                debugTrace(e);
            } catch (final Exception e) {
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                System.err.println("[" + Thread.currentThread().getName() + "] " + sw.toString());
                debugTrace(e);
            }
            print("Connection from " + in.getInetAddress() + " to " + out.getInetAddress() + " was closed.");
        }
    }
}
