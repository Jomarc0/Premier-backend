package com.premier.device.security;

public final class DeviceContext {

    private static final ThreadLocal<DevicePrincipal> CURRENT = new ThreadLocal<>();

    private DeviceContext() {
    }

    public static void set(DevicePrincipal principal) {
        CURRENT.set(principal);
    }

    public static DevicePrincipal get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
