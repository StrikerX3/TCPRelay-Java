package sun.net.www.protocol.rtmp;

public class Handler extends sun.net.www.protocol.http.Handler {
	@Override
	protected int getDefaultPort() {
		return 1935;
	}
}
