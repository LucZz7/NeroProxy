package com.necxa.proxy.vpn;

/** JNI entry points of hev-socks5-tunnel. Class path is set by -DPKGNAME/-DCLSNAME in Application.mk. */
public final class TProxy {
    private TProxy() {}
    public static native void TProxyStartService(String configPath, int tunFd);
    public static native void TProxyStopService();
}
