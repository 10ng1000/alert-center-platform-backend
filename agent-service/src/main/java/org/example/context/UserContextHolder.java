package org.example.context;

public final class UserContextHolder {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void setCurrentUser(String userId) {
        CURRENT_USER.set(userId);
    }

    public static String getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}